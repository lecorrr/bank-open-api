package dev.tkachuk.openbanking;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
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
import org.springframework.web.client.RestClient;

/**
 * The whole service on a random port, with PostgreSQL in Testcontainers and WireMock in place of bank-core and of
 * Keycloak's keys. Every call comes from provider A, with its token and certificate as Traefik passes them on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, BankCoreStub.class, KeycloakStub.class})
class BankOpenApiIT {

    private static final String REQUEST_ID = "99391c7e-ad88-49ec-a2ad-99ddcb1f7721";
    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String IBAN_A = "UA843999990000000000000000001";
    private static final String IBAN_B = "UA033999990000000000000000004";

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("bankCore")
    private WireMockServer bankCore;

    @Autowired
    private JdbcClient jdbc;

    private RestClient api;

    @BeforeEach
    void setUp() {
        bankCore.resetAll();
        bankCoreKeeps(IBAN_A, "UAH", "850.00");
        bankCoreKeeps(IBAN_B, "USD", "10.00");
        api = RestClient.builder().baseUrl("http://localhost:" + port).defaultHeader("X-Request-ID", REQUEST_ID)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.tokenFor(TestProviders.PROVIDER_A))
                .defaultHeader(TestProviders.CERTIFICATE_HEADER,
                        TestProviders.certificateHeader(TestProviders.PROVIDER_A))
                .build();
    }

    @Test
    void shouldReadAccountsOnlyWhileTheConsentIsValid() {
        String consentId = createConsent(4);
        assertThat(consentStatus(consentId)).isEqualTo("received");
        assertRefused(read("/v2/accounts", consentId), 401, "CONSENT_INVALID");

        customerApproves(consentId);

        ResponseEntity<String> list = read("/v2/accounts?withBalance=true", consentId);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        List<String> ibans = JsonPath.read(list.getBody(), "$.accounts[*].iban");
        assertThat(ibans).containsExactly(IBAN_A, IBAN_B);
        String amountA = JsonPath.read(list.getBody(), "$.accounts[0].balances[0].balanceAmount.amount");
        assertThat(amountA).isEqualTo("850.00");
        String resourceB = JsonPath.read(list.getBody(), "$.accounts[1].resourceId");

        ResponseEntity<String> balances = read("/v2/accounts/" + resourceB + "/balances", consentId);
        assertThat(balances.getStatusCode().value()).isEqualTo(200);
        String amountB = JsonPath.read(balances.getBody(), "$.balances[0].balanceAmount.amount");
        assertThat(amountB).isEqualTo("10.00");

        ResponseEntity<Void> deleted = api.delete().uri("/v2/consents/account-access/{id}", consentId)
                .retrieve().toBodilessEntity();
        assertThat(deleted.getStatusCode().value()).isEqualTo(204);
        assertThat(consentStatus(consentId)).isEqualTo("terminatedByTpp");
        assertRefused(read("/v2/accounts", consentId), 401, "CONSENT_INVALID");
    }

    @Test
    void shouldLimitReadsWithoutTheCustomerToFrequencyPerDay() {
        String consentId = createConsent(2);
        customerApproves(consentId);

        assertThat(read("/v2/accounts", consentId).getStatusCode().value()).isEqualTo(200);
        assertThat(read("/v2/accounts", consentId).getStatusCode().value()).isEqualTo(200);
        assertRefused(read("/v2/accounts", consentId), 429, "ACCESS_EXCEEDED");

        ResponseEntity<String> byCustomer = api.get().uri("/v2/accounts").header("Consent-ID", consentId)
                .header("PSU-IP-Address", "192.0.2.10").retrieve().toEntity(String.class);
        assertThat(byCustomer.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void shouldContinueTheProvidersTraceAndPassItsRequestIdToBankCore() {
        String consentId = createConsent(4);
        customerApproves(consentId);

        api.get().uri("/v2/accounts").header("Consent-ID", consentId)
                .header("traceparent", "00-" + TRACE_ID + "-b7ad6b7169203331-01")
                .retrieve().toBodilessEntity();

        bankCore.verify(getRequestedFor(urlEqualTo("/api/v1/accounts/" + IBAN_A))
                .withHeader("X-Request-Id", equalTo(REQUEST_ID))
                .withHeader("traceparent", matching("00-" + TRACE_ID + "-[0-9a-f]{16}-01")));
    }

    @Test
    void shouldAnswer503WhenBankCoreFails() {
        String consentId = createConsent(4);
        customerApproves(consentId);
        bankCore.stubFor(get(urlEqualTo("/api/v1/accounts/" + IBAN_A)).willReturn(serverError()));

        assertRefused(read("/v2/accounts", consentId), 503, "SERVICE_UNAVAILABLE");
    }

    @Test
    void shouldReportReadinessOnTheProbePath() {
        String body = api.get().uri("/actuator/health/readiness").retrieve().body(String.class);

        String status = JsonPath.read(body, "$.status");
        assertThat(status).isEqualTo("UP");
    }

    private String createConsent(int frequencyPerDay) {
        String body = """
                {"access": {"payments": [
                     {"account": {"iban": "%s"}, "rights": ["accountDetails", "balances"]},
                     {"account": {"iban": "%s", "currency": "USD"}, "rights": ["balances"]}]},
                 "consentType": "detailed", "recurringIndicator": true, "validTo": "9999-12-31",
                 "frequencyPerDay": %d}
                """.formatted(IBAN_A, IBAN_B, frequencyPerDay);
        ResponseEntity<String> response = api.post().uri("/v2/consents/account-access")
                .header("PSU-IP-Address", "192.0.2.10").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return JsonPath.read(response.getBody(), "$.consentId");
    }

    // The customer's approval page comes in step 4; until then the test approves in the database.
    private void customerApproves(String consentId) {
        int approved = jdbc.sql("UPDATE consent SET status = 'valid' WHERE id = :id AND status = 'received'")
                .param("id", UUID.fromString(consentId))
                .update();
        assertThat(approved).isEqualTo(1);
    }

    private String consentStatus(String consentId) {
        String body = api.get().uri("/v2/consents/account-access/{id}/status", consentId).retrieve()
                .body(String.class);
        return JsonPath.read(body, "$.consentStatus");
    }

    private ResponseEntity<String> read(String path, String consentId) {
        return api.get().uri(path).header("Consent-ID", consentId).retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> { })
                .toEntity(String.class);
    }

    private static void assertRefused(ResponseEntity<String> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        String answeredCode = JsonPath.read(response.getBody(), "$.code");
        assertThat(answeredCode).isEqualTo(code);
    }

    private void bankCoreKeeps(String iban, String currency, String balance) {
        bankCore.stubFor(get(urlEqualTo("/api/v1/accounts/" + iban)).willReturn(okJson("""
                {"iban": "%s", "holderName": "Test Holder", "currency": "%s", "balance": %s,
                 "createdAt": "2026-11-20T08:00:00Z"}
                """.formatted(iban, currency, balance))));
    }
}
