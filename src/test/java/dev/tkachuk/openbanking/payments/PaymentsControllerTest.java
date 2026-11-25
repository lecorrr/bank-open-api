package dev.tkachuk.openbanking.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tkachuk.openbanking.FixedClock;
import dev.tkachuk.openbanking.TestProviders;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import dev.tkachuk.openbanking.security.SecurityConfig;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// With the API's security chain; see AccountsControllerTest for how requests get their provider token.
@WebMvcTest(PaymentsController.class)
@Import({SecurityConfig.class, FixedClock.class})
@MockitoBean(types = JwtDecoder.class)
class PaymentsControllerTest {

    private static final String REQUEST_ID = "99391c7e-ad88-49ec-a2ad-99ddcb1f7721";
    private static final String PROVIDER = TestProviders.PROVIDER_A;
    private static final UUID PAYMENT_ID = UUID.fromString("5b7e2c9a-1d4f-4a6b-8c3e-9f0a1b2c3d4e");
    private static final UUID TRANSFER_ID = UUID.fromString("8d2f4b6a-0c1e-4f3a-9b5d-7e9f1a3c5b70");
    private static final String PAYMENTS = "/v2/payments/domestic-credit-transfers";
    private static final String PAYMENT_PATH = PAYMENTS + "/" + PAYMENT_ID;
    private static final String OWN_IBAN = "UA843999990000000000000000001";
    private static final String PAYEE_IBAN = "UA573999990000000000000000002";
    private static final String BODY = """
            {"debtorAccount": {"iban": "%s", "currency": "UAH"},
             "instructedAmount": {"currency": "UAH", "amount": "150.50"},
             "creditorAccount": {"iban": "%s"},
             "creditor": {"name": "Olena Kovalenko"},
             "remittanceInformationUnstructured": "Rent for November",
             "paymentIdentification": {"endToEndId": "E2E-42"}}
            """.formatted(OWN_IBAN, PAYEE_IBAN);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService payments;

    @Test
    void shouldInitiatePaymentAndSendCustomerToTheBanksPage() throws Exception {
        when(payments.create(any(), eq(PROVIDER))).thenReturn(payment(PaymentStatus.RCVD, null));

        mockMvc.perform(initiate(BODY).header("TPP-Redirect-URI", "https://tpp.example/payments/done"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", PAYMENT_PATH))
                .andExpect(header().string("ASPSP-SCA-Approach", "REDIRECT"))
                .andExpect(header().string("X-Request-ID", REQUEST_ID))
                .andExpect(jsonPath("$.transactionStatus").value("RCVD"))
                .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID.toString()))
                .andExpect(jsonPath("$._links.self.href").value(PAYMENT_PATH))
                .andExpect(jsonPath("$._links.status.href").value(PAYMENT_PATH + "/status"))
                .andExpect(jsonPath("$._links.scaRedirect.href")
                        .value("https://localhost:8443/customer/payments/" + PAYMENT_ID));

        ArgumentCaptor<NewPayment> request = ArgumentCaptor.forClass(NewPayment.class);
        verify(payments).create(request.capture(), eq(PROVIDER));
        assertThat(request.getValue()).isEqualTo(new NewPayment(OWN_IBAN, "UAH", PAYEE_IBAN, null,
                "Olena Kovalenko", "150.50", "UAH", "Rent for November", "E2E-42",
                URI.create("https://tpp.example/payments/done")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sepa-credit-transfers", "instant-sepa-credit-transfers", "cross-border-credit-transfers"})
    void shouldAnswerProductTheBankDoesNotOfferAsUnknown(String product) throws Exception {
        mockMvc.perform(post("/v2/payments/" + product).header("X-Request-ID", REQUEST_ID)
                        .header("PSU-IP-Address", "192.0.2.10").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(TestProviders.provider(PROVIDER, "payments")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_UNKNOWN"));
        mockMvc.perform(get("/v2/payments/" + product + "/" + PAYMENT_ID + "/status")
                        .header("X-Request-ID", REQUEST_ID).with(TestProviders.provider(PROVIDER, "payments")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_UNKNOWN"));

        verifyNoInteractions(payments);
    }

    // Bulk and periodic payments are no paths of this API at all.
    @ParameterizedTest
    @ValueSource(strings = {"bulk-payments", "periodic-payments"})
    void shouldOfferSinglePaymentsOnly(String paymentService) throws Exception {
        mockMvc.perform(get("/v2/" + paymentService + "/domestic-credit-transfers/" + PAYMENT_ID + "/status")
                        .header("X-Request-ID", REQUEST_ID).with(TestProviders.provider(PROVIDER, "accounts payments")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_UNKNOWN"));

        verifyNoInteractions(payments);
    }

    @Test
    void shouldRefuseInitiationWithoutPsuIpAddress() throws Exception {
        mockMvc.perform(post(PAYMENTS).header("X-Request-ID", REQUEST_ID).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY).with(TestProviders.provider(PROVIDER, "payments")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORMAT_ERROR"))
                .andExpect(jsonPath("$.detail").value("Header PSU-IP-Address is missing"));

        verifyNoInteractions(payments);
    }

    @ParameterizedTest
    @MethodSource("bodiesNotMatchingTheContract")
    void shouldRefuseBodyThatDoesNotMatchTheContract(String body, String detailStart) throws Exception {
        mockMvc.perform(initiate(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORMAT_ERROR"))
                .andExpect(jsonPath("$.detail", startsWith(detailStart)));

        verifyNoInteractions(payments);
    }

    // Each body breaks one rule of the contract's schema, so the detail can only name that one.
    static Stream<Arguments> bodiesNotMatchingTheContract() {
        String notJson = "The request body is not JSON";
        return Stream.of(
                Arguments.of(BODY.replace("\"creditorAccount\": {\"iban\": \"%s\"},".formatted(PAYEE_IBAN), ""),
                        "creditorAccount"),
                Arguments.of(BODY.replace("Rent for November", "R".repeat(141)), "remittanceInformationUnstructured"),
                Arguments.of(BODY.replace("E2E-42", "E".repeat(36)), "paymentIdentification.endToEndId"),
                Arguments.of(BODY.replace("{\"currency\": \"UAH\", \"amount\"", "{\"currency\": \"uah\", \"amount\""),
                        "instructedAmount.currency"),
                Arguments.of(BODY.replace("\"Rent for November\"", "42, \"extra\": ["), notJson));
    }

    @Test
    void shouldRefuseInitiationWithoutBody() throws Exception {
        mockMvc.perform(initiate(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORMAT_ERROR"));

        verifyNoInteractions(payments);
    }

    // The contract's ultimateCreditor takes any of three forms; whichever it is, the bank refuses it by name.
    @Test
    void shouldRefuseUltimateCreditorInAnyForm() throws Exception {
        String body = BODY.replace("\"creditor\":", "\"ultimateCreditor\": {\"identification\": {}}, \"creditor\":");

        mockMvc.perform(initiate(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETER_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.detail", startsWith("ultimateCreditor")));

        verifyNoInteractions(payments);
    }

    // A provider with only the scope accounts may read accounts, not pay from them.
    @Test
    void shouldRefuseProviderWithoutScopePayments() throws Exception {
        mockMvc.perform(post(PAYMENTS).header("X-Request-ID", REQUEST_ID).header("PSU-IP-Address", "192.0.2.10")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(TestProviders.provider(PROVIDER, "accounts")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ROLE_INVALID"));

        verifyNoInteractions(payments);
    }

    @Test
    void shouldShowPaymentAsTheProviderInitiatedIt() throws Exception {
        when(payments.get(PAYMENT_ID.toString(), PROVIDER)).thenReturn(payment(PaymentStatus.ACCC, null));

        mockMvc.perform(read(PAYMENT_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debtorAccount.iban").value(OWN_IBAN))
                .andExpect(jsonPath("$.creditorAccount.iban").value(PAYEE_IBAN))
                .andExpect(jsonPath("$.creditor.name").value("Olena Kovalenko"))
                .andExpect(jsonPath("$.instructedAmount.currency").value("UAH"))
                .andExpect(jsonPath("$.instructedAmount.amount").value("150.50"))
                .andExpect(jsonPath("$.remittanceInformationUnstructured").value("Rent for November"))
                .andExpect(jsonPath("$.paymentIdentification.endToEndId").value("E2E-42"))
                .andExpect(jsonPath("$.transactionStatus").value("ACCC"));
    }

    @Test
    void shouldAnswerStatusWithReasonOfRejection() throws Exception {
        when(payments.get(PAYMENT_ID.toString(), PROVIDER))
                .thenReturn(payment(PaymentStatus.RJCT, RejectionReason.INSUFFICIENT_FUNDS));

        mockMvc.perform(read(PAYMENT_PATH + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionStatus").value("RJCT"))
                .andExpect(jsonPath("$.reasonCode").value("AM04"));
    }

    @Test
    void shouldAnswerStatusWithoutReasonWhileThereIsNone() throws Exception {
        when(payments.get(PAYMENT_ID.toString(), PROVIDER)).thenReturn(payment(PaymentStatus.ACSP, null));

        mockMvc.perform(read(PAYMENT_PATH + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionStatus").value("ACSP"))
                .andExpect(jsonPath("$.reasonCode").doesNotExist());
    }

    @Test
    void shouldAnswerUnknownPaymentWithForbidden() throws Exception {
        when(payments.get(PAYMENT_ID.toString(), PROVIDER))
                .thenThrow(new ApiException(ErrorCode.PAYMENT_UNKNOWN, ErrorCode.PAYMENT_UNKNOWN.text()));

        mockMvc.perform(read(PAYMENT_PATH))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RESOURCE_UNKNOWN"));
    }

    @Test
    void shouldCancelPayment() throws Exception {
        mockMvc.perform(delete(PAYMENT_PATH).header("X-Request-ID", REQUEST_ID)
                        .with(TestProviders.provider(PROVIDER, "payments")))
                .andExpect(status().isNoContent());

        verify(payments).cancel(PAYMENT_ID.toString(), PROVIDER);
    }

    @Test
    void shouldRefuseCancellationOnceTheCustomerDecided() throws Exception {
        doThrow(new ApiException(ErrorCode.CANCELLATION_INVALID, ErrorCode.CANCELLATION_INVALID.text()))
                .when(payments).cancel(PAYMENT_ID.toString(), PROVIDER);

        mockMvc.perform(delete(PAYMENT_PATH).header("X-Request-ID", REQUEST_ID)
                        .with(TestProviders.provider(PROVIDER, "payments")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("CANCELLATION_INVALID"));
    }

    private static MockHttpServletRequestBuilder initiate(String body) {
        return post(PAYMENTS).header("X-Request-ID", REQUEST_ID).header("PSU-IP-Address", "192.0.2.10")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .with(TestProviders.provider(PROVIDER, "payments"));
    }

    private static MockHttpServletRequestBuilder read(String path) {
        return get(path).header("X-Request-ID", REQUEST_ID).with(TestProviders.provider(PROVIDER, "payments"));
    }

    private static Payment payment(PaymentStatus status, @Nullable RejectionReason reason) {
        PaymentOrder order = new PaymentOrder(OWN_IBAN, PAYEE_IBAN, "Olena Kovalenko", new BigDecimal("150.50"),
                "UAH", "Rent for November", "E2E-42");
        boolean decided = status != PaymentStatus.RCVD && status != PaymentStatus.CANC;
        boolean made = status == PaymentStatus.ACCC || status == PaymentStatus.ACSP || status == PaymentStatus.ACSC;
        Instant created = Instant.parse("2026-11-22T08:00:00Z");
        return new Payment(PAYMENT_ID, PROVIDER, order, null, created, status, reason, decided ? "C1001" : null,
                made ? TRANSFER_ID : null, created, decided ? created : null);
    }
}
