package dev.tkachuk.openbanking.consents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import dev.tkachuk.openbanking.security.SecurityConfig;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
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
@WebMvcTest(ConsentsController.class)
@Import({SecurityConfig.class, FixedClock.class})
@MockitoBean(types = JwtDecoder.class)
class ConsentsControllerTest {

    private static final String REQUEST_ID = "99391c7e-ad88-49ec-a2ad-99ddcb1f7721";
    private static final String PROVIDER = TestProviders.PROVIDER_A;
    private static final UUID CONSENT_ID = UUID.fromString("3f2a6c1e-8b4d-4e7f-9a12-6c5d4b3a2f10");
    private static final UUID RESOURCE_ID = UUID.fromString("0f1b8a4e-3c55-4d6a-9a43-1c2e5b7d9f01");
    private static final String IBAN = "UA843999990000000000000000001";
    private static final String CONSENT_PATH = "/v2/consents/account-access/" + CONSENT_ID;
    private static final LocalDate VALID_TO = LocalDate.of(2027, 5, 21);
    private static final String BODY = """
            {"access": {"payments": [{"account": {"iban": "%s", "currency": "UAH"},
                                      "rights": ["accountDetails", "balances"]}]},
             "consentType": "detailed", "recurringIndicator": true, "validTo": "2027-05-21", "frequencyPerDay": 4}
            """.formatted(IBAN);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConsentService consents;

    @Test
    void shouldCreateConsentAndPointToItsStatus() throws Exception {
        Consent consent = consent(ConsentStatus.RECEIVED);
        when(consents.create(any(), eq(PROVIDER))).thenReturn(consent);
        when(consents.statusOf(consent)).thenReturn(ConsentStatus.RECEIVED);

        mockMvc.perform(create(BODY).header("TPP-Redirect-URI", "https://tpp.example/consents/done"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", CONSENT_PATH))
                .andExpect(header().string("ASPSP-SCA-Approach", "REDIRECT"))
                .andExpect(header().string("X-Request-ID", REQUEST_ID))
                .andExpect(jsonPath("$.consentStatus").value("received"))
                .andExpect(jsonPath("$.consentId").value(CONSENT_ID.toString()))
                .andExpect(jsonPath("$._links.self.href").value(CONSENT_PATH))
                .andExpect(jsonPath("$._links.status.href").value(CONSENT_PATH + "/status"))
                // Where the provider sends the customer's browser: the bank's page, at its public address.
                .andExpect(jsonPath("$._links.scaRedirect.href")
                        .value("https://localhost:8443/customer/consents/" + CONSENT_ID));

        ArgumentCaptor<NewConsent> request = ArgumentCaptor.forClass(NewConsent.class);
        verify(consents).create(request.capture(), eq(PROVIDER));
        assertThat(request.getValue().accounts()).containsExactly(new NewConsent.RequestedAccount(IBAN, "UAH",
                Set.of(AccessRight.ACCOUNT_DETAILS, AccessRight.BALANCES)));
        assertThat(request.getValue().validTo()).isEqualTo(VALID_TO);
        assertThat(request.getValue().tppRedirectUri()).isEqualTo(URI.create("https://tpp.example/consents/done"));
    }

    @Test
    void shouldRefuseConsentRequestWithoutPsuIpAddress() throws Exception {
        mockMvc.perform(post("/v2/consents/account-access").header("X-Request-ID", REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY).with(TestProviders.provider(PROVIDER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORMAT_ERROR"))
                .andExpect(jsonPath("$.detail").value("Header PSU-IP-Address is missing"));

        verifyNoInteractions(consents);
    }

    @ParameterizedTest
    @MethodSource("bodiesNotMatchingTheContract")
    void shouldRefuseBodyThatDoesNotMatchTheContract(String body, String detailStart) throws Exception {
        mockMvc.perform(create(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORMAT_ERROR"))
                .andExpect(jsonPath("$.detail", startsWith(detailStart)));

        verifyNoInteractions(consents);
    }

    // Each body breaks one rule, so the detail can only name that one.
    static Stream<Arguments> bodiesNotMatchingTheContract() {
        String notJson = "The request body is not JSON";
        return Stream.of(
                Arguments.of(BODY.replace(", \"frequencyPerDay\": 4", ""), "frequencyPerDay"),
                Arguments.of(BODY.replace("\"frequencyPerDay\": 4", "\"frequencyPerDay\": 0"), "frequencyPerDay"),
                Arguments.of(BODY.replace("\"UAH\"", "\"uah\""), "access.payments[0].account.currency"),
                Arguments.of(BODY.replace("\"detailed\"", "\"weekly\""), notJson),
                Arguments.of(BODY.replace("\"balances\"", "\"everything\""), notJson),
                Arguments.of(BODY.replace("2027-05-21", "21.05.2027"), notJson),
                Arguments.of("{\"access\": {\"payments\": [}", notJson));
    }

    @Test
    void shouldRefuseConsentRequestWithoutBody() throws Exception {
        mockMvc.perform(create(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORMAT_ERROR"))
                .andExpect(jsonPath("$.detail").value("The request body is missing"));

        verifyNoInteractions(consents);
    }

    @Test
    void shouldRefuseBodyInOtherFormatThanJson() throws Exception {
        mockMvc.perform(create(BODY).contentType(MediaType.TEXT_PLAIN))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("FORMAT_ERROR"))
                .andExpect(jsonPath("$.detail").value("The API reads JSON only"));

        verifyNoInteractions(consents);
    }

    @Test
    void shouldRefuseConsentTypeTheBankDoesNotOffer() throws Exception {
        mockMvc.perform(create(BODY.replace("\"detailed\"", "\"global\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSENT_TYPE_NOT_SUPPORTED"));

        verifyNoInteractions(consents);
    }

    @Test
    void shouldShowValidConsentWithLinksToTheData() throws Exception {
        Consent consent = consent(ConsentStatus.VALID);
        when(consents.get(CONSENT_ID.toString(), PROVIDER)).thenReturn(consent);
        when(consents.statusOf(consent)).thenReturn(ConsentStatus.VALID);

        mockMvc.perform(get(CONSENT_PATH).header("X-Request-ID", REQUEST_ID).with(TestProviders.provider(PROVIDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consentStatus").value("valid"))
                .andExpect(jsonPath("$.consentType").value("detailed"))
                .andExpect(jsonPath("$.recurringIndicator").value(true))
                .andExpect(jsonPath("$.validTo").value("2027-05-21"))
                .andExpect(jsonPath("$.frequencyPerDay").value(4))
                .andExpect(jsonPath("$.access.payments", hasSize(1)))
                .andExpect(jsonPath("$.access.payments[0].account.iban").value(IBAN))
                .andExpect(jsonPath("$.access.payments[0].rights", hasSize(1)))
                .andExpect(jsonPath("$.access.payments[0].rights[0]").value("balances"))
                .andExpect(jsonPath("$.access.payments[0]._links.balances.href")
                        .value("/v2/accounts/" + RESOURCE_ID + "/balances"))
                .andExpect(jsonPath("$.access.payments[0]._links.account").doesNotExist())
                .andExpect(jsonPath("$._links.account.href").value("/v2/accounts"));
    }

    @Test
    void shouldShowNoLinksBeforeTheConsentIsValid() throws Exception {
        Consent consent = consent(ConsentStatus.RECEIVED);
        when(consents.get(CONSENT_ID.toString(), PROVIDER)).thenReturn(consent);
        when(consents.statusOf(consent)).thenReturn(ConsentStatus.RECEIVED);

        mockMvc.perform(get(CONSENT_PATH).header("X-Request-ID", REQUEST_ID).with(TestProviders.provider(PROVIDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consentStatus").value("received"))
                .andExpect(jsonPath("$.access.payments[0]._links").doesNotExist())
                .andExpect(jsonPath("$._links").doesNotExist());
    }

    @Test
    void shouldAnswerConsentStatus() throws Exception {
        Consent consent = consent(ConsentStatus.VALID);
        when(consents.get(CONSENT_ID.toString(), PROVIDER)).thenReturn(consent);
        when(consents.statusOf(consent)).thenReturn(ConsentStatus.EXPIRED);

        mockMvc.perform(get(CONSENT_PATH + "/status").header("X-Request-ID", REQUEST_ID)
                        .with(TestProviders.provider(PROVIDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consentStatus").value("expired"));
    }

    @Test
    void shouldDeleteConsent() throws Exception {
        mockMvc.perform(delete(CONSENT_PATH).header("X-Request-ID", REQUEST_ID).with(TestProviders.provider(PROVIDER)))
                .andExpect(status().isNoContent());

        verify(consents).terminate(CONSENT_ID.toString(), PROVIDER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment", "funds-confirmation"})
    void shouldAnswerOtherConsentCategoryAsUnknown(String category) throws Exception {
        mockMvc.perform(delete("/v2/consents/" + category + "/" + CONSENT_ID).header("X-Request-ID", REQUEST_ID)
                        .with(TestProviders.provider(PROVIDER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSENT_UNKNOWN"));
        mockMvc.perform(get("/v2/consents/" + category + "/" + CONSENT_ID + "/status")
                        .header("X-Request-ID", REQUEST_ID).with(TestProviders.provider(PROVIDER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSENT_UNKNOWN"));

        verifyNoInteractions(consents);
    }

    private static MockHttpServletRequestBuilder create(String body) {
        return post("/v2/consents/account-access").header("X-Request-ID", REQUEST_ID)
                .header("PSU-IP-Address", "192.0.2.10").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(TestProviders.provider(PROVIDER));
    }

    private static Consent consent(ConsentStatus status) {
        ConsentAccount account = new ConsentAccount(RESOURCE_ID, IBAN, null, Set.of(AccessRight.BALANCES));
        Instant created = Instant.parse("2026-11-22T08:00:00Z");
        String customer = status == ConsentStatus.RECEIVED ? null : "C1001";
        return new Consent(CONSENT_ID, PROVIDER, customer, status, List.of(account), true, VALID_TO, 4, null, created,
                created);
    }
}
