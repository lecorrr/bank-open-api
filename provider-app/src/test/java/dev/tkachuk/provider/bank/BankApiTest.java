package dev.tkachuk.provider.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.tkachuk.provider.MutableClock;
import dev.tkachuk.provider.TestProperties;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

class BankApiTest {

    private static final String API = TestProperties.API_URL;
    private static final String IBAN = "UA843999990000000000000000001";
    private static final String PAYEE = "UA573999990000000000000000002";
    private static final String PSU_IP = "192.0.2.10";
    private static final URI BACK = URI.create("https://provider.localhost:8443/consents/done?state=abc");

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer bank = MockRestServiceServer.bindTo(builder).build();
    private final BankTokens tokens = mock(BankTokens.class);
    private final BankApi api;

    BankApiTest() {
        when(tokens.accessToken()).thenReturn("token-1");
        api = new BankApi(builder.build(), tokens, TestProperties.PROVIDER,
                new MutableClock(Instant.parse("2026-11-25T10:00:00Z")));
    }

    @Test
    void shouldAskForAConsentToReadTheAccountsBalancesFor90Days() {
        asProvider(bank.expect(requestTo(API + "/v2/consents/account-access")).andExpect(method(HttpMethod.POST)))
                .andExpect(header("PSU-IP-Address", PSU_IP))
                .andExpect(header("TPP-Redirect-URI", BACK.toString()))
                .andExpect(content().json("""
                        {"access": {"payments": [{"account": {"iban": "%s"}, "rights": ["balances"]}]},
                         "consentType": "detailed", "recurringIndicator": true, "validTo": "2027-02-23",
                         "frequencyPerDay": 4}
                        """.formatted(IBAN)))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"consentStatus": "received", "consentId": "consent-1",
                         "_links": {"scaRedirect": {"href": "https://localhost:8443/customer/consents/consent-1"}}}
                        """));

        Started consent = api.startConsent(IBAN, PSU_IP, BACK);

        assertThat(consent).isEqualTo(new Started("consent-1",
                URI.create("https://localhost:8443/customer/consents/consent-1")));
        bank.verify();
    }

    @Test
    void shouldRefuseToSendTheCustomerToAPageThatIsNotHttps() {
        bank.expect(requestTo(API + "/v2/consents/account-access")).andRespond(withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON).body("""
                        {"consentStatus": "received", "consentId": "consent-1",
                         "_links": {"scaRedirect": {"href": "http://localhost:8443/customer/consents/consent-1"}}}
                        """));

        assertThatThrownBy(() -> api.startConsent(IBAN, PSU_IP, BACK)).isInstanceOf(BankApiException.class);
    }

    @Test
    void shouldReadTheConsentsStatus() {
        asProvider(bank.expect(requestTo(API + "/v2/consents/account-access/consent-1/status")))
                .andRespond(withSuccess("{\"consentStatus\": \"valid\"}", MediaType.APPLICATION_JSON));

        assertThat(api.consentStatus("consent-1")).isEqualTo("valid");
    }

    @Test
    void shouldReadEachAccountsBalancesWithTheCustomerPresent() {
        asProvider(bank.expect(requestTo(API + "/v2/accounts")))
                .andExpect(header("Consent-ID", "consent-1"))
                .andExpect(header("PSU-IP-Address", PSU_IP))
                .andRespond(withSuccess("""
                        {"accounts": [{"resourceId": "acc-1", "iban": "%s", "currency": "UAH"}]}
                        """.formatted(IBAN), MediaType.APPLICATION_JSON));
        asProvider(bank.expect(requestTo(API + "/v2/accounts/acc-1/balances")))
                .andExpect(header("Consent-ID", "consent-1"))
                .andExpect(header("PSU-IP-Address", PSU_IP))
                .andRespond(withSuccess("""
                        {"account": {"iban": "%s"}, "balances": [
                          {"balanceAmount": {"currency": "UAH", "amount": "850.00"}, "balanceType": "closingBooked"}]}
                        """.formatted(IBAN), MediaType.APPLICATION_JSON));

        List<Account> accounts = api.accounts("consent-1", PSU_IP);

        assertThat(accounts).containsExactly(new Account("acc-1", IBAN, "UAH",
                List.of(new Account.Balance("closingBooked", "850.00", "UAH"))));
    }

    @Test
    void shouldSayWhatTheBankRefusedWithItsCode() {
        bank.expect(requestTo(API + "/v2/accounts")).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("""
                        {"type": "about:blank", "title": "Unauthorized", "status": 401,
                         "code": "CONSENT_INVALID", "detail": "The consent is not valid"}
                        """));

        assertThatThrownBy(() -> api.accounts("consent-1", PSU_IP)).isInstanceOfSatisfying(BankApiException.class,
                refused -> {
                    assertThat(refused.status()).isEqualTo(401);
                    assertThat(refused.code()).isEqualTo("CONSENT_INVALID");
                    assertThat(refused.detail()).isEqualTo("The consent is not valid");
                });
    }

    @Test
    void shouldEndTheConsent() {
        asProvider(bank.expect(requestTo(API + "/v2/consents/account-access/consent-1"))
                .andExpect(method(HttpMethod.DELETE)))
                .andRespond(withNoContent());

        api.deleteConsent("consent-1");

        bank.verify();
    }

    @Test
    void shouldStartAPaymentInHryvnias() {
        asProvider(bank.expect(requestTo(API + "/v2/payments/domestic-credit-transfers"))
                .andExpect(method(HttpMethod.POST)))
                .andExpect(header("TPP-Redirect-URI", BACK.toString()))
                .andExpect(content().json("""
                        {"debtorAccount": {"iban": "%s"}, "instructedAmount": {"currency": "UAH", "amount": "1.00"},
                         "creditorAccount": {"iban": "%s"}, "creditor": {"name": "Olena Kovalenko"},
                         "remittanceInformationUnstructured": "For the demo"}
                        """.formatted(IBAN, PAYEE)))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"transactionStatus": "RCVD", "paymentId": "payment-1",
                         "_links": {"scaRedirect": {"href": "https://localhost:8443/customer/payments/payment-1"}}}
                        """));

        Started payment = api.startPayment(new PaymentOrder(IBAN, PAYEE, "Olena Kovalenko", "1.00", "For the demo"),
                PSU_IP, BACK);

        assertThat(payment.id()).isEqualTo("payment-1");
        bank.verify();
    }

    @Test
    void shouldReadThePaymentsStatus() {
        asProvider(bank.expect(requestTo(API + "/v2/payments/domestic-credit-transfers/payment-1/status")))
                .andRespond(withSuccess("{\"transactionStatus\": \"ACCC\"}", MediaType.APPLICATION_JSON));

        assertThat(api.paymentStatus("payment-1")).isEqualTo("ACCC");
    }

    // Every call to the API: the provider's token, and a request id of its own.
    private static ResponseActions asProvider(ResponseActions request) {
        return request.andExpect(header("Authorization", "Bearer token-1"))
                .andExpect(header("X-Request-ID", Matchers.matchesPattern("[0-9a-f-]{36}")));
    }
}
