package dev.tkachuk.openbanking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.tkachuk.openbanking.TestTokens;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class IdTokenDecodersTest {

    private static final String JWK_SET_URI = "http://keycloak.test/realms/bank/protocol/openid-connect/certs";

    private final RestTemplate http = new RestTemplate();
    private final MockRestServiceServer keycloak = MockRestServiceServer.bindTo(http).build();
    private final IdTokenDecoders decoders = new IdTokenDecoders(http);

    @BeforeEach
    void serveKeycloaksKeys() {
        keycloak.expect(ExpectedCount.manyTimes(), requestTo(JWK_SET_URI))
                .andRespond(withSuccess(TestTokens.jwkSet(), MediaType.APPLICATION_JSON));
    }

    @Test
    void shouldDecodePs256IdTokenOfThisClient() {
        String idToken = TestTokens.idToken(TestTokens.idTokenClaims("C1001", "nonce-hash"));

        Jwt decoded = decoders.createDecoder(registration()).decode(idToken);

        assertThat(decoded.getClaimAsString("customer_id")).isEqualTo("C1001");
    }

    // Spring Security's own factory would expect RS256; the realm bank signs with PS256 only.
    @Test
    void shouldRefuseIdTokenSignedWithAnotherAlgorithm() {
        String idToken = TestTokens.rs256IdToken(TestTokens.idTokenClaims("C1001", "nonce-hash"));

        // Refused when checking the signature, before any claim is looked at.
        assertThatThrownBy(() -> decoders.createDecoder(registration()).decode(idToken))
                .isExactlyInstanceOf(BadJwtException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("idTokensNotForThisSignIn")
    void shouldRefuseIdTokenNotIssuedToThisClientByThisRealm(String what, Map<String, Object> changedClaims) {
        Map<String, Object> claims = TestTokens.idTokenClaims("C1001", "nonce-hash");
        claims.putAll(changedClaims);
        String idToken = TestTokens.idToken(claims);

        assertThatThrownBy(() -> decoders.createDecoder(registration()).decode(idToken))
                .isInstanceOf(JwtValidationException.class);
    }

    static Stream<Arguments> idTokensNotForThisSignIn() {
        long now = Instant.now().getEpochSecond();
        return Stream.of(
                Arguments.of("another realm's issuer", Map.of("iss", "https://keycloak.test/realms/master")),
                Arguments.of("another audience", Map.of("aud", "smoke-test")),
                Arguments.of("another authorized party", Map.of("azp", "smoke-test")),
                // Issued before it expired, as any token is: a token whose exp comes before its iat is refused as
                // malformed (BadJwtException), before the validators run.
                Arguments.of("expired ten minutes ago", Map.of("iat", now - 900, "exp", now - 600)));
    }

    // Each decoder keeps Keycloak's keys, so one per client registration is enough.
    @Test
    void shouldMakeOneDecoderPerRegistration() {
        assertThat(decoders.createDecoder(registration())).isSameAs(decoders.createDecoder(registration()));
    }

    private static ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("keycloak")
                .clientId(TestTokens.CUSTOMER_CLIENT)
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://localhost:8443/login/oauth2/code/keycloak")
                .authorizationUri(TestTokens.ISSUER + "/protocol/openid-connect/auth")
                .tokenUri(TestTokens.ISSUER + "/protocol/openid-connect/token")
                .jwkSetUri(JWK_SET_URI)
                .issuerUri(TestTokens.ISSUER)
                .build();
    }
}
