package dev.tkachuk.openbanking;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.client.RestClient;

/**
 * Step 4 end to end: provider A asks for a consent, the customer signs in on the bank's page (KeycloakStub in
 * place of Keycloak's login and token endpoint), decides, and is sent back to the provider. bank-core's WireMock
 * says who holds each account. Same context as BankOpenApiIT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, BankCoreStub.class, KeycloakStub.class})
class CustomerApprovalIT {

    private static final String REQUEST_ID = "2d9e4c1a-6b3f-4e8d-9a7c-5f1b0e2d4c6a";
    private static final String CUSTOMER = TestBrowser.CUSTOMER;
    private static final String IBAN_OWN = "UA843999990000000000000000001";
    private static final String IBAN_OTHERS = "UA573999990000000000000000002";
    private static final String TPP_REDIRECT = "https://tpp.example/consents/done";

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("bankCore")
    private WireMockServer bankCore;

    @Autowired
    @Qualifier("keycloak")
    private WireMockServer keycloak;

    private RestClient api;

    @BeforeEach
    void setUp() {
        bankCore.resetAll();
        bankCoreKeeps(IBAN_OWN, CUSTOMER);
        bankCoreKeeps(IBAN_OTHERS, "C1002");
        keycloak.resetAll();
        KeycloakStub.serveSigningKeys(keycloak);
        api = RestClient.builder().baseUrl("http://localhost:" + port).defaultHeader("X-Request-ID", REQUEST_ID)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.tokenFor(TestProviders.PROVIDER_A))
                .defaultHeader(TestProviders.CERTIFICATE_HEADER,
                        TestProviders.certificateHeader(TestProviders.PROVIDER_A))
                .build();
    }

    @Test
    void shouldSignCustomerInWithPkceAndSendThemBackToTheProviderAfterApproval() {
        String consentId = createConsent(IBAN_OWN);
        String page = "/customer/consents/" + consentId;
        TestBrowser browser = new TestBrowser(port);

        HttpResponse<String> toSignIn = browser.get(page);
        assertThat(toSignIn.statusCode()).isEqualTo(302);
        assertThat(TestBrowser.location(toSignIn)).endsWith("/oauth2/authorization/keycloak");
        // The session cookie goes only over HTTPS, never to scripts, and not with other sites' form posts.
        assertThat(toSignIn.headers().firstValue("Set-Cookie"))
                .hasValueSatisfying(cookie -> assertThat(cookie).contains("Secure", "HttpOnly", "SameSite=Lax"));

        String authorizationUrl = TestBrowser.location(browser.get(TestBrowser.location(toSignIn)));
        Map<String, String> authorization = TestBrowser.parameters(URI.create(authorizationUrl).getRawQuery());
        assertThat(authorizationUrl).startsWith(TestTokens.ISSUER + "/protocol/openid-connect/auth?");
        assertThat(authorization)
                .containsEntry("response_type", "code")
                .containsEntry("client_id", TestTokens.CUSTOMER_CLIENT)
                .containsEntry("redirect_uri", "https://localhost:8443/login/oauth2/code/keycloak")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("prompt", "login")
                .containsKeys("state", "nonce", "code_challenge");
        assertThat(authorization.get("scope")).contains("openid");

        KeycloakStub.signsIn(keycloak, TestTokens.idTokenClaims(CUSTOMER, authorization.get("nonce")));
        HttpResponse<String> back = browser.get("/login/oauth2/code/keycloak?code=" + KeycloakStub.CODE + "&state="
                + URLEncoder.encode(authorization.get("state"), StandardCharsets.UTF_8));
        assertThat(TestBrowser.location(back)).contains(page);
        // PKCE: only who started the sign-in knows the verifier behind the challenge.
        List<LoggedRequest> tokenRequests = keycloak.findAll(postRequestedFor(urlEqualTo(KeycloakStub.TOKEN_PATH)));
        assertThat(tokenRequests).hasSize(1);
        Map<String, String> tokenRequest = TestBrowser.parameters(tokenRequests.get(0).getBodyAsString());
        assertThat(tokenRequest).containsEntry("grant_type", "authorization_code");
        assertThat(s256(tokenRequest.get("code_verifier"))).isEqualTo(authorization.get("code_challenge"));

        HttpResponse<String> consent = browser.get(TestBrowser.location(back));
        assertThat(consent.statusCode()).isEqualTo(200);
        assertThat(consent.body()).contains(IBAN_OWN, TestProviders.PROVIDER_A, "Approve");

        HttpResponse<String> decided = browser.submit(page, consent, Map.of("decision", "approve"));

        assertThat(decided.statusCode()).isEqualTo(302);
        assertThat(TestBrowser.location(decided)).isEqualTo(TPP_REDIRECT);
        assertThat(consentStatus(consentId)).isEqualTo("valid");
        ResponseEntity<String> accounts = read("/v2/accounts", consentId);
        assertThat(accounts.getStatusCode().value()).isEqualTo(200);
        List<String> ibans = JsonPath.read(accounts.getBody(), "$.accounts[*].iban");
        assertThat(ibans).containsExactly(IBAN_OWN);
        // The decision ended the session: the next page asks to sign in again.
        assertThat(browser.get("/customer/consents").statusCode()).isEqualTo(302);
    }

    @Test
    void shouldLetCustomerOnlyRejectConsentNamingSomeoneElsesAccount() {
        String consentId = createConsent(IBAN_OWN, IBAN_OTHERS);
        String page = "/customer/consents/" + consentId;
        TestBrowser browser = new TestBrowser(port);
        HttpResponse<String> consent = browser.openSignedIn(page, keycloak, CUSTOMER);
        assertThat(consent.body()).contains("not yours");

        HttpResponse<String> approval = browser.submit(page, consent, Map.of("decision", "approve"));
        assertThat(approval.statusCode()).isEqualTo(403);
        assertThat(consentStatus(consentId)).isEqualTo("received");

        HttpResponse<String> rejection = browser.submit(page, consent, Map.of("decision", "reject"));
        assertThat(TestBrowser.location(rejection)).isEqualTo(TPP_REDIRECT);
        assertThat(consentStatus(consentId)).isEqualTo("rejected");
        assertRefused(read("/v2/accounts", consentId), 401, "CONSENT_INVALID");
    }

    @Test
    void shouldLetCustomerWithdrawConsentTheyApproved() {
        String consentId = createConsent(IBAN_OWN);
        String page = "/customer/consents/" + consentId;
        TestBrowser approving = new TestBrowser(port);
        approving.submit(page, approving.openSignedIn(page, keycloak, CUSTOMER), Map.of("decision", "approve"));
        assertThat(consentStatus(consentId)).isEqualTo("valid");

        TestBrowser browser = new TestBrowser(port);
        HttpResponse<String> list = browser.openSignedIn("/customer/consents", keycloak, CUSTOMER);
        assertThat(list.body()).contains(TestProviders.PROVIDER_A, IBAN_OWN, "Withdraw");

        HttpResponse<String> withdrawn = browser.submit(page + "/withdraw", list, Map.of());

        assertThat(TestBrowser.location(withdrawn)).endsWith("/customer/consents");
        assertThat(browser.get("/customer/consents").body()).contains("The consent is withdrawn");
        assertThat(consentStatus(consentId)).isEqualTo("revokedByPsu");
        assertRefused(read("/v2/accounts", consentId), 401, "CONSENT_INVALID");
    }

    @Test
    void shouldRefuseDecisionWithoutTheFormsCsrfToken() {
        String consentId = createConsent(IBAN_OWN);
        String page = "/customer/consents/" + consentId;
        TestBrowser browser = new TestBrowser(port);
        browser.openSignedIn(page, keycloak, CUSTOMER);

        HttpResponse<String> forged = browser.post(page, Map.of("decision", "approve"));

        assertThat(forged.statusCode()).isEqualTo(403);
        assertThat(consentStatus(consentId)).isEqualTo("received");
    }

    // A state the bank never sent: an old link, a second tab, or someone else's code.
    @Test
    void shouldShowTheBanksPageWhenSignInFails() {
        String consentId = createConsent(IBAN_OWN);
        TestBrowser browser = new TestBrowser(port);
        browser.get(TestBrowser.location(browser.get("/customer/consents/" + consentId)));

        HttpResponse<String> back = browser.get("/login/oauth2/code/keycloak?code=" + KeycloakStub.CODE
                + "&state=not-the-banks");

        assertThat(TestBrowser.location(back)).endsWith("/customer/sign-in-failed");
        HttpResponse<String> failed = browser.get(TestBrowser.location(back));
        assertThat(failed.statusCode()).isEqualTo(200);
        assertThat(failed.body()).contains("Sign-in did not finish");
        assertThat(keycloak.findAll(postRequestedFor(urlEqualTo(KeycloakStub.TOKEN_PATH)))).isEmpty();
    }

    private String createConsent(String... ibans) {
        StringBuilder accounts = new StringBuilder();
        for (String iban : ibans) {
            accounts.append(accounts.isEmpty() ? "" : ", ")
                    .append("{\"account\": {\"iban\": \"").append(iban).append("\"}, \"rights\": [\"balances\"]}");
        }
        String body = """
                {"access": {"payments": [%s]}, "consentType": "detailed", "recurringIndicator": true,
                 "validTo": "9999-12-31", "frequencyPerDay": 4}
                """.formatted(accounts);
        ResponseEntity<String> response = api.post().uri("/v2/consents/account-access")
                .header("PSU-IP-Address", "192.0.2.10").header("TPP-Redirect-URI", TPP_REDIRECT)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        String consentId = JsonPath.read(response.getBody(), "$.consentId");
        String scaRedirect = JsonPath.read(response.getBody(), "$._links.scaRedirect.href");
        assertThat(scaRedirect).isEqualTo("https://localhost:8443/customer/consents/" + consentId);
        return consentId;
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

    private void bankCoreKeeps(String iban, String customerId) {
        bankCore.stubFor(get(urlEqualTo("/api/v1/accounts/" + iban)).willReturn(okJson("""
                {"iban": "%s", "holderName": "Test Holder", "currency": "UAH", "balance": 100.00,
                 "customerId": "%s", "createdAt": "2026-11-20T08:00:00Z"}
                """.formatted(iban, customerId))));
    }

    // RFC 7636's S256: BASE64URL(SHA-256(code_verifier)).
    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from the JDK", e);
        }
    }
}
