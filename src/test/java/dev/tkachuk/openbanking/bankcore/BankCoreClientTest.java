package dev.tkachuk.openbanking.bankcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.tkachuk.openbanking.common.web.RequestIdFilter;
import java.net.SocketTimeoutException;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BankCoreClientTest {

    private static final String ACCOUNTS_URL = "http://bank-core.test:8080/api/v1/accounts";
    private static final String REQUEST_ID = "99391c7e-ad88-49ec-a2ad-99ddcb1f7721";
    private static final String IBAN_A = "UA843999990000000000000000001";
    private static final String IBAN_B = "UA033999990000000000000000004";

    // The server binds to the builder before the client builds its RestClient from it.
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer bankCore = MockRestServiceServer.bindTo(builder).build();
    private final BankCoreClient client =
            new BankCoreClient(builder, new BankCoreProperties(URI.create("http://bank-core.test:8080")));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldReadAccountsAndSkipFieldsItDoesNotNeed() {
        bankCore.expect(requestTo(ACCOUNTS_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"iban": "%s", "holderName": "Test Holder", "currency": "UAH", "balance": 850.00,
                          "createdAt": "2026-11-20T08:00:00Z"},
                         {"iban": "%s", "holderName": "Test Holder", "currency": "USD", "balance": 10.00,
                          "createdAt": "2026-11-20T08:00:01Z"}]
                        """.formatted(IBAN_A, IBAN_B), MediaType.APPLICATION_JSON));

        assertThat(client.listAccounts())
                .containsExactly(new BankCoreAccount(IBAN_A, "UAH"), new BankCoreAccount(IBAN_B, "USD"));
        bankCore.verify();
    }

    @Test
    void shouldReadNoAccounts() {
        bankCore.expect(requestTo(ACCOUNTS_URL)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.listAccounts()).isEmpty();
    }

    @Test
    void shouldPassTheProvidersRequestIdToBankCore() {
        MDC.put(RequestIdFilter.MDC_KEY, REQUEST_ID);
        bankCore.expect(requestTo(ACCOUNTS_URL)).andExpect(header("X-Request-Id", REQUEST_ID))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.listAccounts();

        bankCore.verify();
    }

    @Test
    void shouldSendNoRequestIdWhenThereIsNone() {
        bankCore.expect(requestTo(ACCOUNTS_URL)).andExpect(headerDoesNotExist("X-Request-Id"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.listAccounts();

        bankCore.verify();
    }

    @Test
    void shouldReportErrorAnswerAsUnavailable() {
        bankCore.expect(requestTo(ACCOUNTS_URL)).andRespond(withServerError());

        assertThatThrownBy(client::listAccounts)
                .isInstanceOf(BankCoreUnavailableException.class)
                .hasMessage("bank-core answered 500");
    }

    @Test
    void shouldReportUnreachableBankCoreAsUnavailable() {
        bankCore.expect(requestTo(ACCOUNTS_URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(client::listAccounts)
                .isInstanceOf(BankCoreUnavailableException.class)
                .hasMessage("bank-core could not be reached (ResourceAccessException)");
    }
}
