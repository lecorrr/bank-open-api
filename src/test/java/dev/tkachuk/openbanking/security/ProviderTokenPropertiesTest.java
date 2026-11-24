package dev.tkachuk.openbanking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProviderTokenPropertiesTest {

    private static final URI ISSUER = URI.create("https://localhost:8443/realms/bank");
    private static final URI KEYS = URI.create("http://keycloak:8080/realms/bank/protocol/openid-connect/certs");

    @Test
    void shouldAcceptTheClustersSettings() {
        ProviderTokenProperties properties = new ProviderTokenProperties(ISSUER, KEYS, "bank-open-api");

        assertThat(properties.issuer()).isEqualTo(ISSUER);
        assertThat(properties.jwkSetUri()).isEqualTo(KEYS);
    }

    @ParameterizedTest
    @MethodSource("brokenSettings")
    void shouldRefuseBrokenSetting(URI issuer, URI jwkSetUri, String audience, String messageStart) {
        assertThatThrownBy(() -> new ProviderTokenProperties(issuer, jwkSetUri, audience))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(messageStart);
    }

    static Stream<Arguments> brokenSettings() {
        String issuerMessage = "bank-open-api.provider-tokens.issuer must be an https URL";
        String keysMessage = "bank-open-api.provider-tokens.jwk-set-uri must be an http or https URL";
        return Stream.of(
                Arguments.of(null, KEYS, "bank-open-api", issuerMessage),
                Arguments.of(URI.create("http://localhost:8443/realms/bank"), KEYS, "bank-open-api", issuerMessage),
                Arguments.of(URI.create("/realms/bank"), KEYS, "bank-open-api", issuerMessage),
                Arguments.of(ISSUER, null, "bank-open-api", keysMessage),
                Arguments.of(ISSUER, URI.create("ftp://keycloak/certs"), "bank-open-api", keysMessage),
                Arguments.of(ISSUER, KEYS, " ", "bank-open-api.provider-tokens.audience must not be empty"));
    }
}
