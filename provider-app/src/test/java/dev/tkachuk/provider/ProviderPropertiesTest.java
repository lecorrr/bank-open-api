package dev.tkachuk.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProviderPropertiesTest {

    private static final ProviderProperties.Bank BANK = new ProviderProperties.Bank(
            URI.create("https://localhost:8443/realms/bank"), URI.create("https://api.localhost:8443"), null);

    @Test
    void shouldBuildTheProvidersOwnAddressesForTheBankToSendTheCustomerBack() {
        ProviderProperties properties = properties("provider-app", "https://provider.localhost:8443");

        assertThat(properties.page("/consents/done"))
                .isEqualTo(URI.create("https://provider.localhost:8443/consents/done"));
        assertThat(properties.privateKey()).isEqualTo(Path.of("/etc/provider-app/tls/tls.key"));
        assertThatThrownBy(() -> properties.page("consents/done")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "provider app", "provider\"app",
            "a-client-id-of-sixty-five-characters-is-one-more-than-allowed-xyz"})
    void shouldRefuseAClientIdAJwtCannotCarryAsItIs(String clientId) {
        assertThatThrownBy(() -> properties(clientId, "https://provider.localhost:8443"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://provider.localhost:8443", "https://provider.localhost:8443/pages",
            "https://provider.localhost:8443?x=1", "provider.localhost"})
    void shouldRefuseAPublicUrlThatIsNotABareHttpsAddress(String publicUrl) {
        assertThatThrownBy(() -> properties("provider-app", publicUrl)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldTakeAnIssuerWithAPathButAnApiUrlWithout() {
        assertThatThrownBy(() -> new ProviderProperties.Bank(URI.create("http://localhost:8443/realms/bank"),
                URI.create("https://api.localhost:8443"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderProperties.Bank(URI.create("https://localhost:8443/realms/bank"),
                URI.create("https://api.localhost:8443/v2"), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldTakeAnEmptyConnectToAsNone() {
        ProviderProperties.Bank bank = new ProviderProperties.Bank(URI.create("https://localhost:8443/realms/bank"),
                URI.create("https://api.localhost:8443"), " ");

        assertThat(bank.connectTo()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"traefik", "traefik:443/x", "http://traefik:443"})
    void shouldRefuseAConnectToThatIsNotHostAndPort(String connectTo) {
        assertThatThrownBy(() -> new ProviderProperties.Bank(URI.create("https://localhost:8443/realms/bank"),
                URI.create("https://api.localhost:8443"), connectTo)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ProviderProperties properties(String clientId, String publicUrl) {
        return new ProviderProperties(clientId, URI.create(publicUrl), Path.of("/etc/provider-app/tls"), BANK);
    }
}
