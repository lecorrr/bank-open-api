package dev.tkachuk.openbanking.bankcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.tkachuk.openbanking.common.web.RequestIdFilter;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BankCoreClientTest {

    private static final String IBAN = "UA843999990000000000000000001";
    private static final String ACCOUNT_URL = "http://bank-core.test:8080/api/v1/accounts/" + IBAN;
    private static final String ACCOUNT_JSON = """
            {"iban": "%s", "holderName": "Test Holder", "currency": "UAH", "balance": 850.00,
             "createdAt": "2026-11-20T08:00:00Z"}
            """.formatted(IBAN);
    private static final String REQUEST_ID = "99391c7e-ad88-49ec-a2ad-99ddcb1f7721";

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
    void shouldReadAccountAndSkipFieldsItDoesNotNeed() {
        bankCore.expect(requestTo(ACCOUNT_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ACCOUNT_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.findAccount(IBAN)).contains(new BankCoreAccount(IBAN, "UAH", new BigDecimal("850.00")));
        bankCore.verify();
    }

    @Test
    void shouldFindNothingWhenBankCoreHasNoSuchAccount() {
        bankCore.expect(requestTo(ACCOUNT_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.findAccount(IBAN)).isEmpty();
    }

    @Test
    void shouldPassTheProvidersRequestIdToBankCore() {
        MDC.put(RequestIdFilter.MDC_KEY, REQUEST_ID);
        bankCore.expect(requestTo(ACCOUNT_URL)).andExpect(header("X-Request-Id", REQUEST_ID))
                .andRespond(withSuccess(ACCOUNT_JSON, MediaType.APPLICATION_JSON));

        client.findAccount(IBAN);

        bankCore.verify();
    }

    @Test
    void shouldSendNoRequestIdWhenThereIsNone() {
        bankCore.expect(requestTo(ACCOUNT_URL)).andExpect(headerDoesNotExist("X-Request-Id"))
                .andRespond(withSuccess(ACCOUNT_JSON, MediaType.APPLICATION_JSON));

        client.findAccount(IBAN);

        bankCore.verify();
    }

    // Any other refusal means the two services disagree, which the provider cannot fix by changing its request.
    @ParameterizedTest
    @ValueSource(ints = {400, 500, 503})
    void shouldReportErrorAnswerAsUnavailable(int status) {
        bankCore.expect(requestTo(ACCOUNT_URL)).andRespond(withStatus(HttpStatusCode.valueOf(status)));

        assertThatThrownBy(() -> client.findAccount(IBAN))
                .isInstanceOf(BankCoreUnavailableException.class)
                .hasMessage("bank-core answered " + status);
    }

    @Test
    void shouldReportUnreachableBankCoreAsUnavailable() {
        bankCore.expect(requestTo(ACCOUNT_URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.findAccount(IBAN))
                .isInstanceOf(BankCoreUnavailableException.class)
                .hasMessage("bank-core could not be reached (ResourceAccessException)");
    }
}
