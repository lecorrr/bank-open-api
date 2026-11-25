package dev.tkachuk.openbanking.bankcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

class BankCoreClientTest {

    private static final String IBAN = "UA843999990000000000000000001";
    private static final String ACCOUNT_URL = "http://bank-core.test:8080/api/v1/accounts/" + IBAN;
    private static final String ACCOUNT_JSON = """
            {"iban": "%s", "holderName": "Test Holder", "currency": "UAH", "balance": 850.00, "customerId": "C1001",
             "createdAt": "2026-11-20T08:00:00Z"}
            """.formatted(IBAN);
    private static final String REQUEST_ID = "99391c7e-ad88-49ec-a2ad-99ddcb1f7721";
    private static final String TRANSFERS_URL = "http://bank-core.test:8080/api/v1/transfers";
    private static final UUID TRANSFER_ID = UUID.fromString("8d2f4b6a-0c1e-4f3a-9b5d-7e9f1a3c5b70");
    private static final String PAYMENT_ID = "5b7e2c9a-1d4f-4a6b-8c3e-9f0a1b2c3d4e";
    private static final TransferOrder ORDER = new TransferOrder(IBAN, "UA573999990000000000000000002",
            "Olena Kovalenko", new BigDecimal("150.50"), "UAH", "Rent for November");
    private static final String TRANSFER_JSON = """
            {"id": "%s", "fromIban": "%s", "fromName": null, "toIban": "UA573999990000000000000000002",
             "toName": "Olena Kovalenko", "amount": 150.50, "currency": "UAH", "description": "Rent for November",
             "status": "%s", "createdAt": "2026-11-25T10:00:00Z"}
            """;

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

        assertThat(client.findAccount(IBAN))
                .contains(new BankCoreAccount(IBAN, "UAH", new BigDecimal("850.00"), "C1001"));
        bankCore.verify();
    }

    // bank-core 0.8.0 answers null for the bank's own accounts; bank-core before 0.8.0 leaves the field out.
    @Test
    void shouldReadAccountWithoutCustomer() {
        bankCore.expect(requestTo(ACCOUNT_URL)).andRespond(withSuccess("""
                {"iban": "%s", "holderName": "Bank", "currency": "UAH", "balance": 0, "customerId": null}
                """.formatted(IBAN), MediaType.APPLICATION_JSON));

        assertThat(client.findAccount(IBAN)).hasValueSatisfying(account -> assertThat(account.customerId()).isNull());
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

    @Test
    void shouldMakeTransferUnderThePaymentsKey() {
        bankCore.expect(requestTo(TRANSFERS_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", PAYMENT_ID))
                .andExpect(content().json("""
                        {"fromIban": "%s", "toIban": "UA573999990000000000000000002", "toName": "Olena Kovalenko",
                         "amount": 150.50, "currency": "UAH", "description": "Rent for November"}
                        """.formatted(IBAN), JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body(TRANSFER_JSON.formatted(TRANSFER_ID, IBAN, "COMPLETED")));

        assertThat(client.transfer(ORDER, PAYMENT_ID))
                .isEqualTo(new BankCoreTransfer(TRANSFER_ID, BankCoreTransfer.Status.COMPLETED));
        bankCore.verify();
    }

    // A repeat under the same key gets the transfer made the first time, as it is now.
    @Test
    void shouldReturnTransferMadeEarlierUnderTheSameKey() {
        bankCore.expect(requestTo(TRANSFERS_URL)).andRespond(
                withSuccess(TRANSFER_JSON.formatted(TRANSFER_ID, IBAN, "PENDING"), MediaType.APPLICATION_JSON)
                        .header("Idempotent-Replayed", "true"));

        assertThat(client.transfer(ORDER, PAYMENT_ID))
                .isEqualTo(new BankCoreTransfer(TRANSFER_ID, BankCoreTransfer.Status.PENDING));
    }

    @ParameterizedTest
    @CsvSource({"422, INSUFFICIENT_FUNDS", "404, ACCOUNT_NOT_FOUND", "400, VALIDATION_FAILED"})
    void shouldReportRefusalWithBankCoresCode(int status, String code) {
        bankCore.expect(requestTo(TRANSFERS_URL)).andRespond(problem(status, code));

        assertThatThrownBy(() -> client.transfer(ORDER, PAYMENT_ID))
                .isInstanceOfSatisfying(TransferRefusedException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void shouldReportConflictAsUnavailable() {
        bankCore.expect(requestTo(TRANSFERS_URL)).andRespond(problem(409, "CONFLICT"));

        assertThatThrownBy(() -> client.transfer(ORDER, PAYMENT_ID))
                .isInstanceOf(BankCoreUnavailableException.class)
                .hasMessage("bank-core answered 409 CONFLICT");
    }

    @Test
    void shouldReportReusedKeyAsBug() {
        bankCore.expect(requestTo(TRANSFERS_URL)).andRespond(problem(422, "IDEMPOTENCY_KEY_REUSED"));

        assertThatThrownBy(() -> client.transfer(ORDER, PAYMENT_ID)).isInstanceOf(IllegalStateException.class);
    }

    // A proxy's error page has no code: the outcome is unknown, not refused.
    @Test
    void shouldReportAnswerWithoutBankCoresCodeAsUnavailable() {
        bankCore.expect(requestTo(TRANSFERS_URL)).andRespond(withStatus(HttpStatusCode.valueOf(502))
                .contentType(MediaType.TEXT_HTML).body("<html><body>Bad Gateway</body></html>"));

        assertThatThrownBy(() -> client.transfer(ORDER, PAYMENT_ID))
                .isInstanceOf(BankCoreUnavailableException.class)
                .hasMessage("bank-core answered 502");
    }

    @Test
    void shouldReportTransferToUnreachableBankCoreAsUnavailable() {
        bankCore.expect(requestTo(TRANSFERS_URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.transfer(ORDER, PAYMENT_ID))
                .isInstanceOf(BankCoreUnavailableException.class)
                .hasMessage("bank-core could not be reached (ResourceAccessException)");
    }

    @Test
    void shouldReadTransferById() {
        bankCore.expect(requestTo(TRANSFERS_URL + "/" + TRANSFER_ID)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(TRANSFER_JSON.formatted(TRANSFER_ID, IBAN, "REJECTED"),
                        MediaType.APPLICATION_JSON));

        assertThat(client.findTransfer(TRANSFER_ID))
                .isEqualTo(new BankCoreTransfer(TRANSFER_ID, BankCoreTransfer.Status.REJECTED));
    }

    @Test
    void shouldReportUnknownTransferAsUnavailable() {
        bankCore.expect(requestTo(TRANSFERS_URL + "/" + TRANSFER_ID)).andRespond(problem(404, "TRANSFER_NOT_FOUND"));

        assertThatThrownBy(() -> client.findTransfer(TRANSFER_ID))
                .isInstanceOf(BankCoreUnavailableException.class)
                .hasMessage("bank-core answered 404");
    }

    // bank-core's errors: RFC 9457 problem details with its own code.
    private static ResponseCreator problem(int status, String code) {
        return withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("""
                {"type": "urn:bank-core:error:refused", "title": "Refused", "status": %d, "detail": "Refused",
                 "code": "%s"}
                """.formatted(status, code));
    }
}
