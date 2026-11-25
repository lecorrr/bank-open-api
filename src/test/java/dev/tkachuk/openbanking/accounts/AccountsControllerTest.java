package dev.tkachuk.openbanking.accounts;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tkachuk.openbanking.FixedClock;
import dev.tkachuk.openbanking.TestProviders;
import dev.tkachuk.openbanking.bankcore.BankCoreAccount;
import dev.tkachuk.openbanking.bankcore.BankCoreClient;
import dev.tkachuk.openbanking.bankcore.BankCoreUnavailableException;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import dev.tkachuk.openbanking.consents.AccessRight;
import dev.tkachuk.openbanking.consents.ConsentAccount;
import dev.tkachuk.openbanking.consents.ConsentService;
import dev.tkachuk.openbanking.consents.ReadableAccount;
import dev.tkachuk.openbanking.consents.ReadableAccounts;
import dev.tkachuk.openbanking.security.SecurityConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// The API's security chain is part of the slice. Requests come with TestProviders.provider, a token Spring Security
// takes as checked, so the decoder of real tokens is a mock that is never called.
@WebMvcTest(AccountsController.class)
@Import({SecurityConfig.class, FixedClock.class})
@MockitoBean(types = JwtDecoder.class)
class AccountsControllerTest {

    private static final String REQUEST_ID = "99391c7e-ad88-49ec-a2ad-99ddcb1f7721";
    private static final String CONSENT_ID = "3f2a6c1e-8b4d-4e7f-9a12-6c5d4b3a2f10";
    private static final String PROVIDER = TestProviders.PROVIDER_A;
    private static final UUID RESOURCE_A = UUID.fromString("0f1b8a4e-3c55-4d6a-9a43-1c2e5b7d9f01");
    private static final UUID RESOURCE_B = UUID.fromString("5a7c9e1f-2b4d-4f68-8a0c-3e5f7a9b1d02");
    private static final String IBAN_A = "UA843999990000000000000000001";
    private static final String IBAN_B = "UA033999990000000000000000004";
    private static final ConsentAccount ACCOUNT_A = new ConsentAccount(RESOURCE_A, IBAN_A, "UAH",
            Set.of(AccessRight.ACCOUNT_DETAILS, AccessRight.BALANCES));
    private static final ConsentAccount ACCOUNT_B = new ConsentAccount(RESOURCE_B, IBAN_B, null,
            Set.of(AccessRight.ACCOUNT_DETAILS));
    private static final String CUSTOMER = "C1001";
    private static final BankCoreAccount BANK_CORE_A =
            new BankCoreAccount(IBAN_A, "UAH", new BigDecimal("850.00"), CUSTOMER);
    private static final BankCoreAccount BANK_CORE_B =
            new BankCoreAccount(IBAN_B, "USD", new BigDecimal("10.00"), CUSTOMER);
    private static final String RESOURCE_METADATA =
            "resource_metadata=\"https://api.localhost:8443/.well-known/oauth-protected-resource\"";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConsentService consents;

    @MockitoBean
    private BankCoreClient bankCore;

    @Test
    void shouldListConsentAccountsUnderTheirResourceIds() throws Exception {
        when(consents.accountsToList(CONSENT_ID, PROVIDER, false)).thenReturn(readable(ACCOUNT_A, ACCOUNT_B));
        when(bankCore.findAccount(IBAN_A)).thenReturn(Optional.of(BANK_CORE_A));
        when(bankCore.findAccount(IBAN_B)).thenReturn(Optional.of(BANK_CORE_B));

        mockMvc.perform(read("/v2/accounts"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", REQUEST_ID))
                .andExpect(jsonPath("$.accounts", hasSize(2)))
                .andExpect(jsonPath("$.accounts[0].resourceId").value(RESOURCE_A.toString()))
                .andExpect(jsonPath("$.accounts[0].iban").value(IBAN_A))
                .andExpect(jsonPath("$.accounts[0].currency").value("UAH"))
                .andExpect(jsonPath("$.accounts[0].cashAccountType").value("CACC"))
                .andExpect(jsonPath("$.accounts[0]._links.balances.href")
                        .value("/v2/accounts/" + RESOURCE_A + "/balances"))
                .andExpect(jsonPath("$.accounts[0].balances").doesNotExist())
                .andExpect(jsonPath("$.accounts[1].currency").value("USD"))
                .andExpect(jsonPath("$.accounts[1]._links").doesNotExist());
    }

    @Test
    void shouldAddBalancesOnlyWhereTheConsentIncludesThem() throws Exception {
        when(consents.accountsToList(CONSENT_ID, PROVIDER, false)).thenReturn(readable(ACCOUNT_A, ACCOUNT_B));
        when(bankCore.findAccount(IBAN_A)).thenReturn(Optional.of(BANK_CORE_A));
        when(bankCore.findAccount(IBAN_B)).thenReturn(Optional.of(BANK_CORE_B));

        mockMvc.perform(read("/v2/accounts").param("withBalance", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts[0].balances[0].balanceAmount.amount").value("850.00"))
                .andExpect(jsonPath("$.accounts[0].balances[0].balanceAmount.currency").value("UAH"))
                .andExpect(jsonPath("$.accounts[0].balances[0].balanceType").value("interimAvailable"))
                .andExpect(jsonPath("$.accounts[1].balances").doesNotExist());
    }

    @Test
    void shouldLeaveOutAccountsBankCoreDoesNotKeepAsNamed() throws Exception {
        ConsentAccount inEuro = new ConsentAccount(RESOURCE_B, IBAN_B, "EUR", Set.of(AccessRight.BALANCES));
        when(consents.accountsToList(CONSENT_ID, PROVIDER, false)).thenReturn(readable(ACCOUNT_A, inEuro));
        when(bankCore.findAccount(IBAN_A)).thenReturn(Optional.empty());
        when(bankCore.findAccount(IBAN_B)).thenReturn(Optional.of(BANK_CORE_B));

        mockMvc.perform(read("/v2/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts", hasSize(0)));
    }

    // The consent covers an account only while bank-core says the customer who approved it holds it.
    @ParameterizedTest(name = "consent of {0}, account of {1}")
    @MethodSource("accountsNotHeldByTheConsentsCustomer")
    void shouldLeaveOutAccountsTheConsentsCustomerDoesNotHold(String consentCustomer, String accountCustomer)
            throws Exception {
        when(consents.accountsToList(CONSENT_ID, PROVIDER, false))
                .thenReturn(new ReadableAccounts(consentCustomer, List.of(ACCOUNT_A, ACCOUNT_B)));
        when(bankCore.findAccount(IBAN_A)).thenReturn(Optional.of(BANK_CORE_A));
        when(bankCore.findAccount(IBAN_B))
                .thenReturn(Optional.of(new BankCoreAccount(IBAN_B, "USD", BigDecimal.TEN, accountCustomer)));

        mockMvc.perform(read("/v2/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts", hasSize(CUSTOMER.equals(consentCustomer) ? 1 : 0)));
    }

    static Stream<Arguments> accountsNotHeldByTheConsentsCustomer() {
        return Stream.of(
                Arguments.of(CUSTOMER, "C1002"),
                // The bank's own accounts belong to no customer.
                Arguments.of(CUSTOMER, null),
                // A consent valid since 0.3.0 or before was approved by nobody the bank knows.
                Arguments.of(null, CUSTOMER));
    }

    @Test
    void shouldAnswerResourceUnknownForAccountOfAnotherCustomer() throws Exception {
        when(consents.accountToRead(CONSENT_ID, PROVIDER, RESOURCE_A.toString(), AccessRight.BALANCES, false))
                .thenReturn(new ReadableAccount(CUSTOMER, ACCOUNT_A));
        when(bankCore.findAccount(IBAN_A))
                .thenReturn(Optional.of(new BankCoreAccount(IBAN_A, "UAH", BigDecimal.TEN, "C1002")));

        mockMvc.perform(read("/v2/accounts/" + RESOURCE_A + "/balances"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_UNKNOWN"));
    }

    @Test
    void shouldTellConsentsWhenTheCustomerStartedTheRead() throws Exception {
        when(consents.accountsToList(CONSENT_ID, PROVIDER, true)).thenReturn(readable());

        mockMvc.perform(read("/v2/accounts").header("PSU-IP-Address", "192.0.2.10"))
                .andExpect(status().isOk());

        verify(consents).accountsToList(CONSENT_ID, PROVIDER, true);
    }

    @Test
    void shouldReadAccountDetails() throws Exception {
        when(consents.accountToRead(CONSENT_ID, PROVIDER, RESOURCE_A.toString(), AccessRight.ACCOUNT_DETAILS, false))
                .thenReturn(new ReadableAccount(CUSTOMER, ACCOUNT_A));
        when(bankCore.findAccount(IBAN_A)).thenReturn(Optional.of(BANK_CORE_A));

        mockMvc.perform(read("/v2/accounts/" + RESOURCE_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.resourceId").value(RESOURCE_A.toString()))
                .andExpect(jsonPath("$.account.iban").value(IBAN_A))
                .andExpect(jsonPath("$.account.currency").value("UAH"));
    }

    @Test
    void shouldReadBalanceWithTheCurrencysDecimals() throws Exception {
        when(consents.accountToRead(CONSENT_ID, PROVIDER, RESOURCE_A.toString(), AccessRight.BALANCES, false))
                .thenReturn(new ReadableAccount(CUSTOMER, ACCOUNT_A));
        when(bankCore.findAccount(IBAN_A))
                .thenReturn(Optional.of(new BankCoreAccount(IBAN_A, "UAH", BigDecimal.TEN, CUSTOMER)));

        mockMvc.perform(read("/v2/accounts/" + RESOURCE_A + "/balances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.iban").value(IBAN_A))
                .andExpect(jsonPath("$.balances", hasSize(1)))
                .andExpect(jsonPath("$.balances[0].balanceAmount.amount").value("10.00"))
                .andExpect(jsonPath("$.balances[0].balanceType").value("interimAvailable"))
                .andExpect(jsonPath("$.balances[0].referenceDate").value("2026-11-22"));
    }

    @Test
    void shouldAnswerResourceUnknownWhenBankCoreNoLongerKeepsTheAccount() throws Exception {
        when(consents.accountToRead(CONSENT_ID, PROVIDER, RESOURCE_A.toString(), AccessRight.BALANCES, false))
                .thenReturn(new ReadableAccount(CUSTOMER, ACCOUNT_A));
        when(bankCore.findAccount(IBAN_A)).thenReturn(Optional.empty());

        mockMvc.perform(read("/v2/accounts/" + RESOURCE_A + "/balances"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_UNKNOWN"));
    }

    @Test
    void shouldAnswerConsentRefusalWithItsCode() throws Exception {
        when(consents.accountsToList(CONSENT_ID, PROVIDER, false))
                .thenThrow(new ApiException(ErrorCode.CONSENT_INVALID, "The consent is received, not valid"));

        mockMvc.perform(read("/v2/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("CONSENT_INVALID"))
                .andExpect(jsonPath("$.type").value("urn:bank-open-api:error:consent-invalid"))
                .andExpect(jsonPath("$.detail").value("The consent is received, not valid"));

        verifyNoInteractions(bankCore);
    }

    @Test
    void shouldRefuseReadWithoutConsentId() throws Exception {
        mockMvc.perform(get("/v2/accounts").header("X-Request-ID", REQUEST_ID).with(TestProviders.provider(PROVIDER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORMAT_ERROR"))
                .andExpect(jsonPath("$.detail").value("Header Consent-ID is missing"));

        verifyNoInteractions(consents, bankCore);
    }

    @Test
    void shouldRefuseReadWithoutRequestId() throws Exception {
        mockMvc.perform(get("/v2/accounts").header("Consent-ID", CONSENT_ID).with(TestProviders.provider(PROVIDER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORMAT_ERROR"))
                .andExpect(jsonPath("$.detail").value("Header X-Request-ID is missing"));

        verifyNoInteractions(consents, bankCore);
    }

    @Test
    void shouldRefuseRequestIdThatIsNotUuid() throws Exception {
        mockMvc.perform(get("/v2/accounts").header("X-Request-ID", "not-a-uuid").header("Consent-ID", CONSENT_ID)
                        .with(TestProviders.provider(PROVIDER)))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("X-Request-ID"))
                .andExpect(jsonPath("$.code").value("FORMAT_ERROR"))
                .andExpect(jsonPath("$.detail").value("X-Request-ID has the wrong format"));

        verifyNoInteractions(consents, bankCore);
    }

    @Test
    void shouldRefuseWithBalanceThatIsNotTrueOrFalse() throws Exception {
        mockMvc.perform(read("/v2/accounts").param("withBalance", "sometimes"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("withBalance has the wrong format"));

        verifyNoInteractions(consents, bankCore);
    }

    @Test
    void shouldAnswer503WhenBankCoreIsUnavailable() throws Exception {
        when(consents.accountsToList(CONSENT_ID, PROVIDER, false)).thenReturn(readable(ACCOUNT_A));
        when(bankCore.findAccount(IBAN_A)).thenThrow(new BankCoreUnavailableException("bank-core answered 500", null));

        mockMvc.perform(read("/v2/accounts"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Request-ID", REQUEST_ID))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.title").value("Service unavailable"))
                .andExpect(jsonPath("$.detail", not(containsString("bank-core"))));
    }

    @Test
    void shouldAnswer500ForUnexpectedError() throws Exception {
        when(consents.accountsToList(CONSENT_ID, PROVIDER, false)).thenThrow(new IllegalStateException("a bug"));

        mockMvc.perform(read("/v2/accounts"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail", not(containsString("a bug"))));
    }

    @Test
    void shouldAnswerUnknownPathWithResourceUnknown() throws Exception {
        mockMvc.perform(read("/v2/cards"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_UNKNOWN"))
                .andExpect(jsonPath("$.detail").value("There is no API resource at this path"));
    }

    @Test
    void shouldAnswerMethodTheApiDoesNotOfferWithServiceInvalid() throws Exception {
        mockMvc.perform(post("/v2/accounts").header("X-Request-ID", REQUEST_ID).header("Consent-ID", CONSENT_ID)
                        .with(TestProviders.provider(PROVIDER)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("SERVICE_INVALID"));

        verifyNoInteractions(consents, bankCore);
    }

    @Test
    void shouldAnswerRequestForOtherFormatThanJsonWithRequestedFormatsInvalid() throws Exception {
        mockMvc.perform(read("/v2/accounts").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("REQUESTED_FORMATS_INVALID"));

        verifyNoInteractions(consents, bankCore);
    }

    @Test
    void shouldRefuseReadWithoutProviderToken() throws Exception {
        mockMvc.perform(get("/v2/accounts").secure(true).header("X-Request-ID", REQUEST_ID)
                        .header("Consent-ID", CONSENT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer " + RESOURCE_METADATA))
                // A browser may open the API's addresses too: no HSTS for localhost there either.
                .andExpect(header().doesNotExist("Strict-Transport-Security"))
                .andExpect(header().string("X-Request-ID", REQUEST_ID))
                .andExpect(jsonPath("$.code").value("TOKEN_UNKNOWN"));

        verifyNoInteractions(consents, bankCore);
    }

    @Test
    void shouldRefuseTokenWithoutTheAccountsScopeAsRoleInvalid() throws Exception {
        mockMvc.perform(get("/v2/accounts").header("X-Request-ID", REQUEST_ID).header("Consent-ID", CONSENT_ID)
                        .with(TestProviders.provider(PROVIDER, "payments")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        "Bearer error=\"insufficient_scope\", " + RESOURCE_METADATA))
                .andExpect(jsonPath("$.code").value("ROLE_INVALID"));

        verifyNoInteractions(consents, bankCore);
    }

    // The token is bound to provider A's certificate, but the request comes without any.
    @Test
    void shouldRefuseTokenWithoutTheCertificateItIsBoundTo() throws Exception {
        mockMvc.perform(get("/v2/accounts").header("X-Request-ID", REQUEST_ID).header("Consent-ID", CONSENT_ID)
                        .with(jwt().jwt(token -> token.claim("azp", PROVIDER).claim("scope", "accounts")
                                .claim("cnf", Map.of("x5t#S256", TestProviders.thumbprint(PROVIDER))))))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        "Bearer error=\"invalid_token\", " + RESOURCE_METADATA))
                .andExpect(jsonPath("$.code").value("CERTIFICATE_MISSING"));

        verifyNoInteractions(consents, bankCore);
    }

    private static ReadableAccounts readable(ConsentAccount... accounts) {
        return new ReadableAccounts(CUSTOMER, List.of(accounts));
    }

    private static MockHttpServletRequestBuilder read(String path) {
        return get(path).header("X-Request-ID", REQUEST_ID).header("Consent-ID", CONSENT_ID)
                .with(TestProviders.provider(PROVIDER));
    }
}
