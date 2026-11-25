package dev.tkachuk.openbanking.approval;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import dev.tkachuk.openbanking.FixedClock;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import dev.tkachuk.openbanking.payments.AccountNotHeldException;
import dev.tkachuk.openbanking.payments.CustomerPayment;
import dev.tkachuk.openbanking.payments.PaymentOrder;
import dev.tkachuk.openbanking.payments.PaymentService;
import dev.tkachuk.openbanking.security.CustomerSignInConfig;
import dev.tkachuk.openbanking.security.SecurityConfig;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// The payment page behind both security chains, as CustomerConsentsControllerTest has the consent pages.
@WebMvcTest(CustomerPaymentsController.class)
@Import({CustomerSignInConfig.class, SecurityConfig.class, FixedClock.class})
@MockitoBean(types = {JwtDecoder.class, JwtDecoderFactory.class, OAuth2AccessTokenResponseClient.class})
class CustomerPaymentsControllerTest {

    private static final String PAYMENT_ID = "5b7e2c9a-1d4f-4a6b-8c3e-9f0a1b2c3d4e";
    private static final String PAGE = "/customer/payments/" + PAYMENT_ID;
    private static final String CUSTOMER = "C1001";
    private static final String OWN_IBAN = "UA843999990000000000000000001";
    private static final String PAYEE_IBAN = "UA573999990000000000000000002";
    private static final URI TPP_REDIRECT = URI.create("https://tpp.example/payments/done");
    private static final PaymentOrder ORDER = new PaymentOrder(OWN_IBAN, PAYEE_IBAN, "Olena Kovalenko",
            new BigDecimal("150.50"), "UAH", "Rent for November", null);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService payments;

    @Test
    void shouldSendCustomerWhoIsNotSignedInToSignIn() throws Exception {
        mockMvc.perform(get(PAGE))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/oauth2/authorization/keycloak"));

        verifyNoInteractions(payments);
    }

    @Test
    void shouldShowPaymentWaitingForTheCustomerWithItsForm() throws Exception {
        when(payments.forCustomer(PAYMENT_ID, CUSTOMER)).thenReturn(waiting(true));

        mockMvc.perform(get(PAGE).secure(true).with(customer()))
                .andExpect(status().isOk())
                .andExpect(view().name("customer/payment"))
                .andExpect(content().string(containsString("150.50 UAH")))
                .andExpect(content().string(containsString("Olena Kovalenko")))
                .andExpect(content().string(containsString(PAYEE_IBAN)))
                .andExpect(content().string(containsString("Rent for November")))
                .andExpect(content().string(containsString(OWN_IBAN)))
                .andExpect(content().string(containsString("provider-a")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("disabled"))))
                .andExpect(header().doesNotExist("Strict-Transport-Security"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")));
    }

    @Test
    void shouldOfferOnlyRejectionWhenTheAccountIsNotTheCustomers() throws Exception {
        when(payments.forCustomer(PAYMENT_ID, CUSTOMER)).thenReturn(waiting(false));

        mockMvc.perform(get(PAGE).with(customer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("not yours")))
                .andExpect(content().string(containsString("disabled")));
    }

    @Test
    void shouldShowOnlyWhatBecameOfDecidedPayment() throws Exception {
        when(payments.forCustomer(PAYMENT_ID, CUSTOMER))
                .thenReturn(new CustomerPayment(UUID.fromString(PAYMENT_ID), null, "ACCC", null, false, false, null,
                        null));

        mockMvc.perform(get(PAGE).with(customer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("This payment is paid.")))
                .andExpect(content().string(not(containsString("<form"))));
    }

    @Test
    void shouldSendTheCustomerBackToTheProviderAfterTheDecision() throws Exception {
        when(payments.approve(PAYMENT_ID, CUSTOMER)).thenReturn(decided("ACCC", null, TPP_REDIRECT));

        mockMvc.perform(post(PAGE).param("decision", "approve").with(customer()).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(TPP_REDIRECT.toString()));
    }

    @Test
    void shouldTellWhatTheApprovalDidWhenTheProviderGaveNoRedirectAddress() throws Exception {
        when(payments.approve(PAYMENT_ID, CUSTOMER)).thenReturn(decided("RJCT", "AM04", null));

        mockMvc.perform(post(PAGE).param("decision", "approve").with(customer()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("customer/message"))
                .andExpect(content().string(containsString("Payment refused")))
                .andExpect(content().string(containsString("does not have enough money")));
    }

    @Test
    void shouldTellTheCustomerTheyRejectedThePayment() throws Exception {
        when(payments.reject(PAYMENT_ID, CUSTOMER)).thenReturn(decided("RJCT", null, null));

        mockMvc.perform(post(PAGE).param("decision", "reject").with(customer()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Payment rejected")));
    }

    @Test
    void shouldRefuseDecisionWithoutCsrfToken() throws Exception {
        mockMvc.perform(post(PAGE).param("decision", "approve").with(customer()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(payments);
    }

    @Test
    void shouldRefuseDecisionItDoesNotKnow() throws Exception {
        mockMvc.perform(post(PAGE).param("decision", "pay twice").with(customer()).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("customer/message"));

        verifyNoInteractions(payments);
    }

    @Test
    void shouldRefuseApprovalFromAccountOfSomeoneElseWithPage() throws Exception {
        when(payments.approve(PAYMENT_ID, CUSTOMER)).thenThrow(new AccountNotHeldException("not held"));

        mockMvc.perform(post(PAGE).param("decision", "approve").with(customer()).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(view().name("customer/message"))
                .andExpect(content().string(containsString("Not your account")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusalsAndTheirPages")
    void shouldAnswerRefusalWithPageAndStatus(ErrorCode code, int status, String title) throws Exception {
        when(payments.approve(PAYMENT_ID, CUSTOMER)).thenThrow(new ApiException(code, "refused"));

        mockMvc.perform(post(PAGE).param("decision", "approve").with(customer()).with(csrf()))
                .andExpect(status().is(status))
                .andExpect(view().name("customer/message"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString(title)));
    }

    static Stream<Arguments> refusalsAndTheirPages() {
        return Stream.of(
                Arguments.of(ErrorCode.PAYMENT_UNKNOWN, 404, "No such payment"),
                Arguments.of(ErrorCode.STATUS_INVALID, 409, "Already decided"),
                Arguments.of(ErrorCode.SERVICE_UNAVAILABLE, 503, "Try again later"));
    }

    @Test
    void shouldAnswerUnexpectedFailureWithPageThatHidesIt() throws Exception {
        when(payments.approve(PAYMENT_ID, CUSTOMER))
                .thenThrow(new IllegalStateException("bank-core keeps a different transfer under this key"));

        mockMvc.perform(post(PAGE).param("decision", "approve").with(customer()).with(csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(containsString("Something went wrong")))
                .andExpect(content().string(not(containsString("bank-core"))));
    }

    private static RequestPostProcessor customer() {
        return oidcLogin().idToken(token -> token.claim("customer_id", CUSTOMER).claim("name", "Artem Demo"));
    }

    private static CustomerPayment waiting(boolean held) {
        return new CustomerPayment(UUID.fromString(PAYMENT_ID), "provider-a", "RCVD", null, true, held, ORDER,
                TPP_REDIRECT);
    }

    private static CustomerPayment decided(String status, @Nullable String reasonCode, @Nullable URI tppRedirectUri) {
        return new CustomerPayment(UUID.fromString(PAYMENT_ID), "provider-a", status, reasonCode, false, false, ORDER,
                tppRedirectUri);
    }
}
