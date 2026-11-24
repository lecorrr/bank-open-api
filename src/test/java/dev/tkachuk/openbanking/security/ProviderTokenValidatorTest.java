package dev.tkachuk.openbanking.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class ProviderTokenValidatorTest {

    private static final String ISSUER = "https://localhost:8443/realms/bank";
    private static final String AUDIENCE = "bank-open-api";
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    private final ProviderTokenValidator validator =
            new ProviderTokenValidator(ISSUER, AUDIENCE, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void shouldAcceptTokenAsKeycloakIssuesIt() {
        assertThat(validator.validate(token(change -> { })).hasErrors()).isFalse();
    }

    @Test
    void shouldAcceptAccessTokenTypeInItsLongForm() {
        assertThat(validator.validate(token(jwt -> jwt.header("typ", "application/at+jwt"))).hasErrors()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("brokenRules")
    void shouldRefuseTokenThatBreaksARule(String rule, Consumer<Jwt.Builder> change, ErrorCode code) {
        OAuth2TokenValidatorResult result = validator.validate(token(change));

        assertThat(result.getErrors()).as(rule).hasSize(1);
        OAuth2Error error = result.getErrors().iterator().next();
        assertThat(error.getErrorCode()).isEqualTo("invalid_token");
        assertThat(((TokenRefusal) error).code()).as(rule).isEqualTo(code);
    }

    // Each change breaks one rule of an otherwise good token.
    static Stream<Arguments> brokenRules() {
        return Stream.of(
                broken("ID token type", jwt -> jwt.header("typ", "JWT"), ErrorCode.TOKEN_INVALID),
                broken("no type", jwt -> jwt.headers(headers -> headers.remove("typ")), ErrorCode.TOKEN_INVALID),
                broken("another issuer", jwt -> jwt.issuer("https://localhost:8443/realms/master"),
                        ErrorCode.TOKEN_INVALID),
                broken("another audience", jwt -> jwt.audience(List.of("account")), ErrorCode.TOKEN_INVALID),
                broken("no audience", jwt -> jwt.claims(claims -> claims.remove("aud")), ErrorCode.TOKEN_INVALID),
                broken("no expiry", jwt -> jwt.claims(claims -> claims.remove("exp")), ErrorCode.TOKEN_INVALID),
                broken("expired beyond the clock skew", jwt -> jwt.expiresAt(NOW.minusSeconds(31)),
                        ErrorCode.TOKEN_EXPIRED),
                broken("valid only later", jwt -> jwt.notBefore(NOW.plusSeconds(31)), ErrorCode.TOKEN_INVALID),
                broken("no provider", jwt -> jwt.claims(claims -> claims.remove("azp")), ErrorCode.TOKEN_INVALID),
                broken("not bound to a certificate", jwt -> jwt.claims(claims -> claims.remove("cnf")),
                        ErrorCode.TOKEN_INVALID),
                broken("confirmation of another kind", jwt -> jwt.claim("cnf", Map.of("jkt", "abc")),
                        ErrorCode.TOKEN_INVALID),
                broken("confirmation not an object", jwt -> jwt.claim("cnf", "abc"), ErrorCode.TOKEN_INVALID));
    }

    @Test
    void shouldAcceptTokenExpiredWithinTheClockSkew() {
        assertThat(validator.validate(token(jwt -> jwt.expiresAt(NOW.minusSeconds(29)))).hasErrors()).isFalse();
    }

    @Test
    void shouldTellTheProviderWhatIsWrongWithoutRepeatingTheToken() {
        OAuth2Error error = validator.validate(token(jwt -> jwt.audience(List.of("account")))).getErrors()
                .iterator().next();

        assertThat(error.getDescription()).isEqualTo("The access token is not meant for this API");
    }

    @Test
    void shouldReadTheThumbprintOfTheBoundCertificate() {
        assertThat(ProviderTokenValidator.certificateThumbprint(token(change -> { }))).isEqualTo("thumbprint-a");
        assertThat(ProviderTokenValidator.certificateThumbprint(token(jwt -> jwt.claim("cnf", "abc")))).isNull();
    }

    private static Arguments broken(String rule, Consumer<Jwt.Builder> change, ErrorCode code) {
        return Arguments.of(rule, change, code);
    }

    // A token as Keycloak's realm bank issues it to provider A, with one change applied.
    private static Jwt token(Consumer<Jwt.Builder> change) {
        Jwt.Builder jwt = Jwt.withTokenValue("token")
                .header("alg", "PS256")
                .header("typ", "at+jwt")
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject("1f0e7a64-2c1b-4c55-9d1e-7b0c3a8f2e19")
                .claim("azp", "provider-a")
                .claim("scope", "accounts")
                .issuedAt(NOW.minusSeconds(60))
                .expiresAt(NOW.plusSeconds(240))
                .claim("cnf", Map.of("x5t#S256", "thumbprint-a"));
        change.accept(jwt);
        return jwt.build();
    }
}
