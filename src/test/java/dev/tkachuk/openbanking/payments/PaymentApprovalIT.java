package dev.tkachuk.openbanking.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import dev.tkachuk.openbanking.BankCoreStub;
import dev.tkachuk.openbanking.KeycloakStub;
import dev.tkachuk.openbanking.PostgresTestcontainer;
import dev.tkachuk.openbanking.TestBrowser;
import dev.tkachuk.openbanking.TestProviders;
import dev.tkachuk.openbanking.TestTokens;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.web.client.RestClient;

/**
 * Step 5 end to end: provider A initiates a payment, the customer signs in on the bank's page and approves it, and
 * the bank makes the transfer at bank-core's WireMock, under the payment's id as its Idempotency-Key. Same context
 * as BankOpenApiIT; BankCoreStub keeps the retry job from running by itself, so a test runs it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, BankCoreStub.class, KeycloakStub.class})
class PaymentApprovalIT {

    private static final String REQUEST_ID = "4f1c7a2e-9b3d-4e6f-8a5c-2d7e9b1f3a6c";
    private static final String CUSTOMER = TestBrowser.CUSTOMER;
    private static final String IBAN_OWN = "UA843999990000000000000000001";
    private static final String IBAN_OTHERS = "UA573999990000000000000000002";
    private static final String IBAN_OTHER_BANK = "UA213223130000026007233566001";
    private static final String TPP_REDIRECT = "https://tpp.example/payments/done";
    private static final String PAYMENTS = "/v2/payments/domestic-credit-transfers";
    private static final String TRANSFERS = "/api/v1/transfers";
    private static final UUID TRANSFER_ID = UUID.fromString("8d2f4b6a-0c1e-4f3a-9b5d-7e9f1a3c5b70");

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("bankCore")
    private WireMockServer bankCore;

    @Autowired
    @Qualifier("keycloak")
    private WireMockServer keycloak;

    @Autowired
    private PaymentService payments;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    private RestClient api;

    @BeforeEach
    void setUp() {
        bankCore.resetAll();
        bankCoreKeeps(IBAN_OWN, CUSTOMER);
        bankCoreKeeps(IBAN_OTHERS, "C1002");
        keycloak.resetAll();
        KeycloakStub.serveSigningKeys(keycloak);
        Map<String, Object> claims = TestTokens.claimsFor(TestProviders.PROVIDER_A);
        claims.put("scope", "accounts payments");
        api = RestClient.builder().baseUrl("http://localhost:" + port).defaultHeader("X-Request-ID", REQUEST_ID)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.sign(claims))
                .defaultHeader(TestProviders.CERTIFICATE_HEADER,
                        TestProviders.certificateHeader(TestProviders.PROVIDER_A))
                .build();
    }

    @Test
    void shouldMakePaymentWithinTheBankOnceTheCustomerApproves() {
        bankCoreAnswersTransfer(201, "COMPLETED");
        String paymentId = initiate(IBAN_OWN, IBAN_OTHERS);
        String page = "/customer/payments/" + paymentId;
        assertThat(paymentStatus(paymentId)).isEqualTo("RCVD");
        TestBrowser browser = new TestBrowser(port);

        HttpResponse<String> payment = browser.openSignedIn(page, keycloak, CUSTOMER);
        assertThat(payment.body()).contains("150.50 UAH", "Olena Kovalenko", IBAN_OTHERS, "Rent for November",
                "Approve");
        HttpResponse<String> decided = browser.submit(page, payment, Map.of("decision", "approve"));

        assertThat(decided.statusCode()).isEqualTo(302);
        assertThat(TestBrowser.location(decided)).isEqualTo(TPP_REDIRECT);
        assertThat(paymentStatus(paymentId)).isEqualTo("ACCC");
        bankCore.verify(1, postRequestedFor(urlEqualTo(TRANSFERS))
                .withHeader("Idempotency-Key", equalTo(paymentId))
                .withRequestBody(equalToJson("""
                        {"fromIban": "%s", "toIban": "%s", "toName": "Olena Kovalenko", "amount": 150.50,
                         "currency": "UAH", "description": "Rent for November"}
                        """.formatted(IBAN_OWN, IBAN_OTHERS))));
        // The decision ended the session: the next page asks to sign in again.
        assertThat(browser.get(page).statusCode()).isEqualTo(302);
    }

    @Test
    void shouldSendPaymentToAnotherBankAndLearnItsAnswerWhenTheProviderReads() {
        bankCoreAnswersTransfer(201, "PENDING");
        String paymentId = initiate(IBAN_OWN, IBAN_OTHER_BANK);
        approve(paymentId);
        bankCoreKeepsTransfer("PENDING");
        assertThat(paymentStatus(paymentId)).isEqualTo("ACSP");

        bankCoreKeepsTransfer("COMPLETED");

        assertThat(paymentStatus(paymentId)).isEqualTo("ACSC");
    }

    @Test
    void shouldRejectPaymentBankCoreRefusesWithItsReason() {
        bankCore.stubFor(post(urlEqualTo(TRANSFERS)).willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/problem+json").withBody("""
                        {"type": "urn:bank-core:error:insufficient-funds", "title": "Insufficient funds",
                         "status": 422, "code": "INSUFFICIENT_FUNDS"}
                        """)));
        String paymentId = initiate(IBAN_OWN, IBAN_OTHERS);

        approve(paymentId);

        String status = api.get().uri(PAYMENTS + "/{id}/status", paymentId).retrieve().body(String.class);
        assertThat((String) JsonPath.read(status, "$.transactionStatus")).isEqualTo("RJCT");
        assertThat((String) JsonPath.read(status, "$.reasonCode")).isEqualTo("AM04");
    }

    @Test
    void shouldMakePaymentLaterWhenBankCoreWasDownAtTheApproval() {
        bankCore.stubFor(post(urlEqualTo(TRANSFERS)).willReturn(aResponse().withStatus(503)));
        String paymentId = initiate(IBAN_OWN, IBAN_OTHERS);
        approve(paymentId);
        assertThat(paymentStatus(paymentId)).isEqualTo("ACTC");
        // As if the retry delay (an hour in these tests) had passed since the approval's attempt.
        jdbc.sql("UPDATE payment SET attempted_at = attempted_at - interval '2 hours' WHERE id = :id")
                .param("id", UUID.fromString(paymentId)).update();
        bankCoreAnswersTransfer(201, "COMPLETED");

        payments.retryApproved();

        assertThat(paymentStatus(paymentId)).isEqualTo("ACCC");
        bankCore.verify(2, postRequestedFor(urlEqualTo(TRANSFERS)).withHeader("Idempotency-Key", equalTo(paymentId)));
    }

    @Test
    void shouldLetCustomerOnlyRejectPaymentFromSomeoneElsesAccount() {
        String paymentId = initiate(IBAN_OTHERS, IBAN_OWN);
        String page = "/customer/payments/" + paymentId;
        TestBrowser browser = new TestBrowser(port);
        HttpResponse<String> payment = browser.openSignedIn(page, keycloak, CUSTOMER);
        assertThat(payment.body()).contains("not yours");

        HttpResponse<String> approval = browser.submit(page, payment, Map.of("decision", "approve"));
        assertThat(approval.statusCode()).isEqualTo(403);
        assertThat(paymentStatus(paymentId)).isEqualTo("RCVD");

        HttpResponse<String> rejection = browser.submit(page, payment, Map.of("decision", "reject"));
        assertThat(TestBrowser.location(rejection)).isEqualTo(TPP_REDIRECT);
        assertThat(paymentStatus(paymentId)).isEqualTo("RJCT");
        bankCore.verify(0, postRequestedFor(urlEqualTo(TRANSFERS)));
    }

    @Test
    void shouldCancelPaymentTheCustomerHasNotDecidedOn() {
        String paymentId = initiate(IBAN_OWN, IBAN_OTHERS);

        ResponseEntity<Void> cancelled = api.delete().uri(PAYMENTS + "/{id}", paymentId).retrieve()
                .toBodilessEntity();

        assertThat(cancelled.getStatusCode().value()).isEqualTo(204);
        assertThat(paymentStatus(paymentId)).isEqualTo("CANC");
        TestBrowser browser = new TestBrowser(port);
        assertThat(browser.openSignedIn("/customer/payments/" + paymentId, keycloak, CUSTOMER).body())
                .contains("cancelled by the provider")
                .doesNotContain("Approve");
    }

    @Test
    void shouldAnswerAnotherProvidersPaymentAsUnknown() {
        String paymentId = initiate(IBAN_OWN, IBAN_OTHERS);
        Map<String, Object> claims = TestTokens.claimsFor(TestProviders.PROVIDER_B);
        claims.put("scope", "payments");
        RestClient providerB = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("X-Request-ID", REQUEST_ID)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.sign(claims))
                .defaultHeader(TestProviders.CERTIFICATE_HEADER,
                        TestProviders.certificateHeader(TestProviders.PROVIDER_B))
                .build();

        ResponseEntity<String> read = providerB.get().uri(PAYMENTS + "/{id}/status", paymentId).retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> { })
                .toEntity(String.class);

        assertThat(read.getStatusCode().value()).isEqualTo(403);
        assertThat((String) JsonPath.read(read.getBody(), "$.code")).isEqualTo("RESOURCE_UNKNOWN");
    }

    // The retry job runs only if scheduling is on; in these tests its delay is too long for it to run by itself.
    @Test
    void shouldScheduleTheRetryOfApprovedPayments() {
        assertThat(scheduledTasks.getScheduledTasks())
                .extracting(task -> task.getTask().getRunnable().toString())
                .anyMatch(task -> task.contains("PaymentRetryJob.retry"));
    }

    private String initiate(String debtorIban, String creditorIban) {
        String body = """
                {"debtorAccount": {"iban": "%s"}, "instructedAmount": {"currency": "UAH", "amount": "150.50"},
                 "creditorAccount": {"iban": "%s"}, "creditor": {"name": "Olena Kovalenko"},
                 "remittanceInformationUnstructured": "Rent for November"}
                """.formatted(debtorIban, creditorIban);
        ResponseEntity<String> response = api.post().uri(PAYMENTS)
                .header("PSU-IP-Address", "192.0.2.10").header("TPP-Redirect-URI", TPP_REDIRECT)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        String paymentId = JsonPath.read(response.getBody(), "$.paymentId");
        String scaRedirect = JsonPath.read(response.getBody(), "$._links.scaRedirect.href");
        assertThat(scaRedirect).isEqualTo("https://localhost:8443/customer/payments/" + paymentId);
        return paymentId;
    }

    private void approve(String paymentId) {
        String page = "/customer/payments/" + paymentId;
        TestBrowser browser = new TestBrowser(port);
        HttpResponse<String> decided = browser.submit(page, browser.openSignedIn(page, keycloak, CUSTOMER),
                Map.of("decision", "approve"));
        assertThat(TestBrowser.location(decided)).isEqualTo(TPP_REDIRECT);
    }

    private String paymentStatus(String paymentId) {
        String body = api.get().uri(PAYMENTS + "/{id}/status", paymentId).retrieve().body(String.class);
        return JsonPath.read(body, "$.transactionStatus");
    }

    private void bankCoreAnswersTransfer(int status, String transferStatus) {
        bankCore.stubFor(post(urlEqualTo(TRANSFERS)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(transfer(transferStatus))));
    }

    private void bankCoreKeepsTransfer(String transferStatus) {
        bankCore.stubFor(get(urlEqualTo(TRANSFERS + "/" + TRANSFER_ID)).willReturn(okJson(transfer(transferStatus))));
    }

    private static String transfer(String status) {
        return """
                {"id": "%s", "fromIban": "%s", "toIban": "%s", "toName": "Olena Kovalenko", "amount": 150.50,
                 "currency": "UAH", "description": "Rent for November", "status": "%s",
                 "createdAt": "2026-11-25T10:00:00Z"}
                """.formatted(TRANSFER_ID, IBAN_OWN, IBAN_OTHERS, status);
    }

    private void bankCoreKeeps(String iban, String customerId) {
        bankCore.stubFor(get(urlEqualTo("/api/v1/accounts/" + iban)).willReturn(okJson("""
                {"iban": "%s", "holderName": "Test Holder", "currency": "UAH", "balance": 100.00,
                 "customerId": "%s", "createdAt": "2026-11-20T08:00:00Z"}
                """.formatted(iban, customerId))));
    }
}
