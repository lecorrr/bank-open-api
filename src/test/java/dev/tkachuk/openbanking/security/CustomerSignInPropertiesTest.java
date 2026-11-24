package dev.tkachuk.openbanking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CustomerSignInPropertiesTest {

    private static final URI ISSUER = URI.create("https://localhost:8443/realms/bank");
    private static final URI AUTHORIZATION =
            URI.create("https://localhost:8443/realms/bank/protocol/openid-connect/auth");
    private static final URI TOKEN = URI.create("http://keycloak:8080/realms/bank/protocol/openid-connect/token");
    private static final URI KEYS = URI.create("http://keycloak:8080/realms/bank/protocol/openid-connect/certs");

    @Test
    void shouldAcceptKeycloakInsideTheClusterForTokenAndKeys() {
        CustomerSignInProperties properties =
                new CustomerSignInProperties("bank-approval", "secret", ISSUER, AUTHORIZATION, TOKEN, KEYS);

        assertThat(properties.tokenUri()).isEqualTo(TOKEN);
        assertThat(properties.jwkSetUri()).isEqualTo(KEYS);
    }

    @ParameterizedTest(name = "{6}")
    @MethodSource("settingsBreakingARule")
    void shouldRefuseSettingBreakingARule(String clientId, String clientSecret, URI issuer, URI authorizationUri,
            URI tokenUri, URI jwkSetUri, String property) {
        assertThatThrownBy(() -> new CustomerSignInProperties(clientId, clientSecret, issuer, authorizationUri,
                tokenUri, jwkSetUri))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("bank-open-api.customer-sign-in." + property);
    }

    static Stream<Arguments> settingsBreakingARule() {
        URI httpIssuer = URI.create("http://localhost:8443/realms/bank");
        URI httpAuthorization = URI.create("http://localhost:8443/realms/bank/protocol/openid-connect/auth");
        return Stream.of(
                Arguments.of(" ", "secret", ISSUER, AUTHORIZATION, TOKEN, KEYS, "client-id"),
                Arguments.of("bank-approval", "", ISSUER, AUTHORIZATION, TOKEN, KEYS, "client-secret"),
                Arguments.of("bank-approval", null, ISSUER, AUTHORIZATION, TOKEN, KEYS, "client-secret"),
                // The customer's browser goes there, and the ID token names it: only over TLS.
                Arguments.of("bank-approval", "secret", httpIssuer, AUTHORIZATION, TOKEN, KEYS, "issuer"),
                Arguments.of("bank-approval", "secret", ISSUER, httpAuthorization, TOKEN, KEYS, "authorization-uri"),
                Arguments.of("bank-approval", "secret", ISSUER, AUTHORIZATION, URI.create("ftp://keycloak/token"), KEYS,
                        "token-uri"),
                Arguments.of("bank-approval", "secret", ISSUER, AUTHORIZATION, TOKEN, URI.create("/certs"),
                        "jwk-set-uri"));
    }

    @Test
    void shouldKeepTheSecretOutOfItsText() {
        CustomerSignInProperties properties =
                new CustomerSignInProperties("bank-approval", "s3cret-value", ISSUER, AUTHORIZATION, TOKEN, KEYS);

        assertThat(properties.toString()).contains("bank-approval").doesNotContain("s3cret-value");
    }
}
