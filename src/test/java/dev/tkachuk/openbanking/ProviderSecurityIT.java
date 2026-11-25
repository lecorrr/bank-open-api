package dev.tkachuk.openbanking;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static dev.tkachuk.openbanking.TestProviders.PROVIDER_A;
import static dev.tkachuk.openbanking.TestProviders.PROVIDER_B;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
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
 * The security chain with signed tokens, checked against the keys KeycloakStub serves: who gets in, and with which
 * code the others are refused. Same context as BankOpenApiIT, so Spring starts it once for both.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, BankCoreStub.class, KeycloakStub.class})
class ProviderSecurityIT {

    private static final String REQUEST_ID = "5b0c3d2e-7f41-4a9b-8c6d-2e1f0a9b8c7d";
    // application.yml's default for bank-open-api.api-url: the API's own host.
    private static final String API_URL = "https://api.localhost:8443";
    private static final String CONSENT_BODY = """
            {"access": {"payments": [{"account": {"iban": "UA843999990000000000000000001"},
                                      "rights": ["balances"]}]},
             "consentType": "detailed", "recurringIndicator": true, "validTo": "9999-12-31", "frequencyPerDay": 4}
            """;

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("keycloak")
    private WireMockServer keycloak;

    private RestClient api;

    @BeforeEach
    void setUp() {
        api = RestClient.builder().baseUrl("http://localhost:" + port).defaultHeader("X-Request-ID", REQUEST_ID)
                .build();
    }

    @AfterEach
    void restoreKeycloak() {
        keycloak.resetAll();
        KeycloakStub.serveSigningKeys(keycloak);
    }

    @Test
    void shouldLetInTokenWithTheCertificateItIsBoundTo() {
        ResponseEntity<String> created = createConsent(TestTokens.tokenFor(PROVIDER_A), PROVIDER_A);

        assertThat(created.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void shouldRefuseRequestWithoutToken() {
        ResponseEntity<String> answer = call(api.get().uri("/v2/accounts").header("Consent-ID", REQUEST_ID)
                .header(TestProviders.CERTIFICATE_HEADER, TestProviders.certificateHeader(PROVIDER_A)));

        assertRefused(answer, 401, "TOKEN_UNKNOWN");
        assertThat(answer.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer resource_metadata=\"" + API_URL + "/.well-known/oauth-protected-resource\"");
        assertThat(answer.getHeaders().getFirst("X-Request-ID")).isEqualTo(REQUEST_ID);
    }

    // Provider B's claims under provider A's signature: the signature no longer fits.
    @Test
    void shouldRefuseTokenWhoseClaimsWereChanged() {
        String[] tokenA = TestTokens.tokenFor(PROVIDER_A).split("\\.");
        String[] tokenB = TestTokens.tokenFor(PROVIDER_B).split("\\.");
        String forged = tokenA[0] + "." + tokenB[1] + "." + tokenA[2];

        assertRefused(createConsent(forged, PROVIDER_B), 401, "TOKEN_UNKNOWN");
    }

    @Test
    void shouldRefuseExpiredToken() {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = TestTokens.claimsFor(PROVIDER_A);
        claims.put("iat", now - 900);
        claims.put("exp", now - 600);

        assertRefused(createConsent(TestTokens.sign(claims), PROVIDER_A), 401, "TOKEN_EXPIRED");
    }

    @Test
    void shouldRefuseTokenWithoutTheCertificateItIsBoundTo() {
        ResponseEntity<String> answer = call(api.post().uri("/v2/consents/account-access")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.tokenFor(PROVIDER_A))
                .header("PSU-IP-Address", "192.0.2.10").contentType(MediaType.APPLICATION_JSON).body(CONSENT_BODY));

        assertRefused(answer, 401, "CERTIFICATE_MISSING");
    }

    // A stolen token is of no use with the thief's own certificate.
    @Test
    void shouldRefuseTokenWithAnotherProvidersCertificate() {
        assertRefused(createConsent(TestTokens.tokenFor(PROVIDER_A), PROVIDER_B), 401, "TOKEN_INVALID");
    }

    @Test
    void shouldKeepEachProvidersConsentsFromTheOthers() {
        ResponseEntity<String> created = createConsent(TestTokens.tokenFor(PROVIDER_A), PROVIDER_A);
        String consentId = JsonPath.read(created.getBody(), "$.consentId");

        assertRefused(statusAs(PROVIDER_B, consentId), 403, "CONSENT_UNKNOWN");
        assertRefused(accountsAs(PROVIDER_B, consentId), 400, "CONSENT_UNKNOWN");
        assertThat(statusAs(PROVIDER_A, consentId).getStatusCode().value()).isEqualTo(200);
    }

    // RFC 9728: what a provider needs to know to get a token for this API, open to anyone. The resource is the
    // API's own host, not the bank's.
    @Test
    void shouldDescribeTheApiAsProtectedResource() {
        String metadata = api.get().uri("/.well-known/oauth-protected-resource").retrieve().body(String.class);

        String resource = JsonPath.read(metadata, "$.resource");
        List<String> authorizationServers = JsonPath.read(metadata, "$.authorization_servers");
        List<String> scopes = JsonPath.read(metadata, "$.scopes_supported");
        List<String> bearerMethods = JsonPath.read(metadata, "$.bearer_methods_supported");
        Boolean boundTokens = JsonPath.read(metadata, "$.tls_client_certificate_bound_access_tokens");
        assertThat(resource).isEqualTo(API_URL);
        assertThat(authorizationServers).containsExactly(TestTokens.ISSUER);
        assertThat(scopes).containsExactly("accounts", "payments");
        assertThat(bearerMethods).containsExactly("header");
        assertThat(boundTokens).isTrue();
    }

    // A key id the decoder has not seen makes it read Keycloak's keys again, which fails here.
    @Test
    void shouldAnswer503WhenKeycloaksKeysCannotBeRead() {
        keycloak.stubFor(get(urlEqualTo(KeycloakStub.CERTS_PATH)).willReturn(serverError()));
        String token = TestTokens.sign("rotated-key", TestTokens.claimsFor(PROVIDER_A));

        assertRefused(createConsent(token, PROVIDER_A), 503, "SERVICE_UNAVAILABLE");
    }

    private ResponseEntity<String> createConsent(String token, String certificateOf) {
        return call(api.post().uri("/v2/consents/account-access")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(TestProviders.CERTIFICATE_HEADER, TestProviders.certificateHeader(certificateOf))
                .header("PSU-IP-Address", "192.0.2.10").contentType(MediaType.APPLICATION_JSON).body(CONSENT_BODY));
    }

    private ResponseEntity<String> statusAs(String provider, String consentId) {
        return call(api.get().uri("/v2/consents/account-access/{id}/status", consentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.tokenFor(provider))
                .header(TestProviders.CERTIFICATE_HEADER, TestProviders.certificateHeader(provider)));
    }

    private ResponseEntity<String> accountsAs(String provider, String consentId) {
        return call(api.get().uri("/v2/accounts").header("Consent-ID", consentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.tokenFor(provider))
                .header(TestProviders.CERTIFICATE_HEADER, TestProviders.certificateHeader(provider)));
    }

    private static ResponseEntity<String> call(RestClient.RequestHeadersSpec<?> request) {
        return request.retrieve().onStatus(HttpStatusCode::isError, (req, response) -> { }).toEntity(String.class);
    }

    private static void assertRefused(ResponseEntity<String> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        String answeredCode = JsonPath.read(response.getBody(), "$.code");
        assertThat(answeredCode).isEqualTo(code);
    }
}
