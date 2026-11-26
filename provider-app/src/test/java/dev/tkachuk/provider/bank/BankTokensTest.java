package dev.tkachuk.provider.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.tkachuk.provider.MutableClock;
import dev.tkachuk.provider.TestProperties;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

class BankTokensTest {

    private static final KeyPair KEYS = TestKeys.rsa();
    private static final String DISCOVERY = TestProperties.ISSUER + "/.well-known/openid-configuration";
    private static final String TOKEN_ENDPOINT = TestProperties.ISSUER + "/protocol/openid-connect/token";
    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer bank = MockRestServiceServer.bindTo(builder).build();
    private final BankTokens tokens = new BankTokens(builder.build(), TestProperties.PROVIDER, KEYS.getPrivate(),
            clock);

    @Test
    void shouldSignInAtTheTokenEndpointTheIssuersDiscoveryDocumentNames() {
        expectDiscovery(TestProperties.ISSUER);
        bank.expect(requestTo(TOKEN_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(Map.of("grant_type", "client_credentials",
                        "client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")))
                .andExpect(signedClientAssertion())
                .andRespond(withSuccess(token("token-1", 300), MediaType.APPLICATION_JSON));

        assertThat(tokens.accessToken()).isEqualTo("token-1");
        bank.verify();
    }

    @Test
    void shouldKeepATokenUntilShortlyBeforeItExpires() {
        expectDiscovery(TestProperties.ISSUER);
        expectToken("token-1");
        assertThat(tokens.accessToken()).isEqualTo("token-1");

        clock.advance(Duration.ofSeconds(300).minus(BankTokens.RENEW_BEFORE).minusSeconds(1));
        assertThat(tokens.accessToken()).isEqualTo("token-1");
        bank.verify();

        // The discovery document is read once; a new token needs only the token endpoint.
        bank.reset();
        expectToken("token-2");
        clock.advance(Duration.ofSeconds(1));
        assertThat(tokens.accessToken()).isEqualTo("token-2");
        bank.verify();
    }

    @Test
    void shouldRefuseADiscoveryDocumentOfAnotherIssuer() {
        expectDiscovery("https://evil.example/realms/bank");

        assertThatThrownBy(tokens::accessToken).isInstanceOf(BankApiException.class)
                .hasMessageContaining("another issuer");
    }

    @Test
    void shouldReportWhatTheTokenEndpointRefused() {
        expectDiscovery(TestProperties.ISSUER);
        bank.expect(requestTo(TOKEN_ENDPOINT)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_client\",\"error_description\":\"Invalid client credentials\"}"));

        assertThatThrownBy(tokens::accessToken).isInstanceOfSatisfying(BankApiException.class, refused -> {
            assertThat(refused.status()).isEqualTo(401);
            assertThat(refused.code()).isEqualTo("invalid_client");
        });
    }

    @Test
    void shouldRefuseAnAnswerWithoutABearerToken() {
        expectDiscovery(TestProperties.ISSUER);
        bank.expect(requestTo(TOKEN_ENDPOINT)).andRespond(withSuccess(
                "{\"access_token\":\"token-1\",\"token_type\":\"DPoP\",\"expires_in\":300}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(tokens::accessToken).isInstanceOf(BankApiException.class);
    }

    private void expectDiscovery(String issuer) {
        bank.expect(requestTo(DISCOVERY)).andExpect(method(HttpMethod.GET)).andRespond(withSuccess(
                "{\"issuer\":\"" + issuer + "\",\"token_endpoint\":\"" + TOKEN_ENDPOINT + "\"}",
                MediaType.APPLICATION_JSON));
    }

    private void expectToken(String value) {
        bank.expect(requestTo(TOKEN_ENDPOINT)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(token(value, 300), MediaType.APPLICATION_JSON));
    }

    private static String token(String value, int expiresIn) {
        return "{\"access_token\":\"" + value + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn
                + ",\"scope\":\"accounts payments\"}";
    }

    // The client assertion in the form: the provider's PS256 signature, for the bank's issuer, for a minute.
    private static RequestMatcher signedClientAssertion() {
        return request -> {
            String form = ((MockClientHttpRequest) request).getBodyAsString();
            String jwt = Arrays.stream(form.split("&")).filter(field -> field.startsWith("client_assertion="))
                    .map(field -> URLDecoder.decode(field.substring("client_assertion=".length()),
                            StandardCharsets.UTF_8))
                    .findFirst().orElseThrow(() -> new AssertionError("no client_assertion in " + form));
            String[] parts = jwt.split("\\.");
            String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            assertThat(claims).contains("\"iss\":\"provider-app\"", "\"sub\":\"provider-app\"",
                    "\"aud\":\"" + TestProperties.ISSUER + "\"", "\"exp\":" + (NOW.getEpochSecond() + 60));
            try {
                Signature verifier = Signature.getInstance("RSASSA-PSS");
                verifier.setParameter(ClientAssertion.PS256);
                verifier.initVerify(KEYS.getPublic());
                verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
            } catch (GeneralSecurityException e) {
                throw new AssertionError(e);
            }
        };
    }
}
