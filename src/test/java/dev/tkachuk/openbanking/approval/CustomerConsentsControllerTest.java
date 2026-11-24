package dev.tkachuk.openbanking.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import dev.tkachuk.openbanking.FixedClock;
import dev.tkachuk.openbanking.TestBrowser;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import dev.tkachuk.openbanking.consents.AccessRight;
import dev.tkachuk.openbanking.consents.ConsentAccount;
import dev.tkachuk.openbanking.consents.CustomerConsent;
import dev.tkachuk.openbanking.security.CustomerSignInConfig;
import dev.tkachuk.openbanking.security.SecurityConfig;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
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

// The customer pages behind both security chains, as the service runs them. A signed-in customer comes from
// oidcLogin() with the customer_id claim Keycloak's mapper adds; the beans that would call Keycloak are mocks.
@WebMvcTest(CustomerConsentsController.class)
@Import({CustomerSignInConfig.class, SecurityConfig.class, FixedClock.class})
@MockitoBean(types = {JwtDecoder.class, JwtDecoderFactory.class, OAuth2AccessTokenResponseClient.class})
class CustomerConsentsControllerTest {

    private static final String CONSENT_ID = "3f2a6c1e-8b4d-4e7f-9a12-6c5d4b3a2f10";
    private static final String PAGE = "/customer/consents/" + CONSENT_ID;
    private static final String CUSTOMER = "C1001";
    private static final String IBAN = "UA843999990000000000000000001";
    private static final URI TPP_REDIRECT = URI.create("https://tpp.example/consents/done");
    private static final ConsentAccount ACCOUNT = new ConsentAccount(
            UUID.fromString("0f1b8a4e-3c55-4d6a-9a43-1c2e5b7d9f01"), IBAN, "UAH", Set.of(AccessRight.BALANCES));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApprovalService approvals;

    @Test
    void shouldSendCustomerWhoIsNotSignedInToSignIn() throws Exception {
        mockMvc.perform(get(PAGE))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/oauth2/authorization/keycloak"));

        verifyNoInteractions(approvals);
    }

    @Test
    void shouldStartSignInAtKeycloakWithPkceAndFreshLogin() throws Exception {
        String location = mockMvc.perform(get("/oauth2/authorization/keycloak"))
                .andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location).startsWith("https://localhost:8443/realms/bank/protocol/openid-connect/auth?");
        assertThat(TestBrowser.parameters(URI.create(location).getRawQuery()))
                .containsEntry("client_id", "bank-approval")
                .containsEntry("redirect_uri", "https://localhost:8443/login/oauth2/code/keycloak")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("prompt", "login")
                .containsKeys("code_challenge", "nonce", "state");
    }

    @Test
    void shouldShowConsentWaitingForTheCustomerWithItsForm() throws Exception {
        when(approvals.page(CONSENT_ID, CUSTOMER)).thenReturn(page(true));

        mockMvc.perform(get(PAGE).secure(true).with(customer()))
                .andExpect(status().isOk())
                .andExpect(view().name("customer/consent"))
                .andExpect(content().string(containsString(IBAN)))
                .andExpect(content().string(containsString("provider-a")))
                .andExpect(content().string(containsString("Signed in as Artem Demo")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("disabled"))))
                // No other site may frame the Approve button, the page loads nothing but its stylesheet, and over
                // HTTPS it still sends no HSTS, which browsers would apply to every localhost port.
                .andExpect(header().doesNotExist("Strict-Transport-Security"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")));
    }

    @Test
    void shouldOfferOnlyRejectionWhenAnAccountIsNotTheCustomers() throws Exception {
        when(approvals.page(CONSENT_ID, CUSTOMER)).thenReturn(page(false));

        mockMvc.perform(get(PAGE).with(customer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("not yours")))
                .andExpect(content().string(containsString("disabled")));
    }

    @Test
    void shouldSendTheCustomerBackToTheProviderAfterTheDecision() throws Exception {
        when(approvals.approve(CONSENT_ID, CUSTOMER)).thenReturn(decided("valid", TPP_REDIRECT));

        mockMvc.perform(post(PAGE).param("decision", "approve").with(customer()).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(TPP_REDIRECT.toString()));
    }

    @Test
    void shouldTellTheOutcomeWhenTheProviderGaveNoRedirectAddress() throws Exception {
        when(approvals.reject(CONSENT_ID, CUSTOMER)).thenReturn(decided("rejected", null));

        mockMvc.perform(post(PAGE).param("decision", "reject").with(customer()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("customer/message"))
                .andExpect(content().string(containsString("Consent rejected")));
    }

    @Test
    void shouldRefuseDecisionWithoutCsrfToken() throws Exception {
        mockMvc.perform(post(PAGE).param("decision", "approve").with(customer()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(approvals);
    }

    @Test
    void shouldRefuseDecisionItDoesNotKnow() throws Exception {
        mockMvc.perform(post(PAGE).param("decision", "maybe").with(customer()).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("customer/message"));

        verifyNoInteractions(approvals);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusalsAndTheirPages")
    void shouldAnswerRefusalWithPageAndStatus(ErrorCode code, int status, String title) throws Exception {
        when(approvals.approve(CONSENT_ID, CUSTOMER)).thenThrow(new ApiException(code, "refused"));

        mockMvc.perform(post(PAGE).param("decision", "approve").with(customer()).with(csrf()))
                .andExpect(status().is(status))
                .andExpect(view().name("customer/message"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString(title)));
    }

    static Stream<Arguments> refusalsAndTheirPages() {
        return Stream.of(
                Arguments.of(ErrorCode.CONSENT_UNKNOWN, 404, "No such consent"),
                Arguments.of(ErrorCode.STATUS_INVALID, 409, "Already decided"),
                Arguments.of(ErrorCode.CONSENT_INVALID, 403, "Not your accounts"),
                Arguments.of(ErrorCode.SERVICE_UNAVAILABLE, 503, "Try again later"));
    }

    @Test
    void shouldAnswerUnexpectedFailureWithPageThatHidesIt() throws Exception {
        when(approvals.page(CONSENT_ID, CUSTOMER)).thenThrow(new IllegalStateException("a bug"));

        mockMvc.perform(get(PAGE).with(customer()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(containsString("Something went wrong")))
                .andExpect(content().string(not(containsString("a bug"))));
    }

    @Test
    void shouldRefuseSignedInUserWithoutCustomerNumber() throws Exception {
        mockMvc.perform(get(PAGE).with(oidcLogin()))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Not a customer")));

        verifyNoInteractions(approvals);
    }

    @Test
    void shouldListTheCustomersConsentsWithWayToWithdrawEach() throws Exception {
        when(approvals.validConsentsOf(CUSTOMER)).thenReturn(List.of(decided("valid", null)));

        mockMvc.perform(get("/customer/consents").with(customer()))
                .andExpect(status().isOk())
                .andExpect(view().name("customer/consents"))
                .andExpect(content().string(containsString("provider-a")))
                .andExpect(content().string(containsString(IBAN)))
                .andExpect(content().string(containsString(PAGE + "/withdraw")));
    }

    @Test
    void shouldWithdrawConsentAndSayItIsWithdrawn() throws Exception {
        mockMvc.perform(post(PAGE + "/withdraw").with(customer()).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/customer/consents"))
                .andExpect(flash().attributeExists("notice"));

        verify(approvals).revoke(CONSENT_ID, CUSTOMER);
    }

    @Test
    void shouldSignOutToThePageSayingSo() throws Exception {
        mockMvc.perform(post("/customer/sign-out").with(customer()).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/customer/signed-out"));
        mockMvc.perform(get("/customer/signed-out"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Signed out")));
    }

    // Spring Security's own failure page is not served here; the bank's page needs no sign-in.
    @Test
    void shouldShowFailedSignInPageWithoutSignIn() throws Exception {
        mockMvc.perform(get("/customer/sign-in-failed"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign-in did not finish")));
    }

    @Test
    void shouldServeTheStylesheetWithoutSignIn() throws Exception {
        mockMvc.perform(get("/customer/bank.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));
    }

    private static RequestPostProcessor customer() {
        return oidcLogin().idToken(token -> token.claim("customer_id", CUSTOMER).claim("name", "Artem Demo"));
    }

    private static ConsentPage page(boolean held) {
        CustomerConsent consent = new CustomerConsent(UUID.fromString(CONSENT_ID), "provider-a", "received", true,
                List.of(ACCOUNT), true, LocalDate.of(2027, 5, 21), 4, TPP_REDIRECT);
        return new ConsentPage(consent, List.of(new AccountOnPage(IBAN, "UAH", false, true, held)));
    }

    private static CustomerConsent decided(String status, URI tppRedirectUri) {
        return new CustomerConsent(UUID.fromString(CONSENT_ID), "provider-a", status, false, List.of(ACCOUNT), true,
                LocalDate.of(2027, 5, 21), 4, tppRedirectUri);
    }
}
