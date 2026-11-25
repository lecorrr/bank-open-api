package dev.tkachuk.openbanking.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicUrlPropertiesTest {

    private static final URI PUBLIC_URL = URI.create("https://localhost:8443");
    private static final URI API_URL = URI.create("https://api.localhost:8443");

    @Test
    void shouldBuildAddressOfPage() {
        PublicUrlProperties properties = new PublicUrlProperties(PUBLIC_URL, API_URL);

        assertThat(properties.page("/customer/consents/42")).isEqualTo("https://localhost:8443/customer/consents/42");
    }

    @Test
    void shouldBuildAddressOfApiPath() {
        PublicUrlProperties properties = new PublicUrlProperties(PUBLIC_URL, API_URL);

        assertThat(properties.api("/.well-known/oauth-protected-resource"))
                .isEqualTo("https://api.localhost:8443/.well-known/oauth-protected-resource");
    }

    // Links built from them go to customers' browsers and providers: only a bare https address will do.
    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8443", "https://localhost:8443/", "https://localhost:8443/bank",
            "https://localhost:8443?x=1", "https://localhost:8443#top", "localhost:8443", "https:///"})
    void shouldRefusePublicUrlThatIsNotBareHttps(String url) {
        assertThatThrownBy(() -> new PublicUrlProperties(URI.create(url), API_URL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bank-open-api.public-url");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://api.localhost:8443", "https://api.localhost:8443/v2", "api.localhost:8443"})
    void shouldRefuseApiUrlThatIsNotBareHttps(String url) {
        assertThatThrownBy(() -> new PublicUrlProperties(PUBLIC_URL, URI.create(url)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bank-open-api.api-url");
    }

    @Test
    void shouldRefuseMissingAddresses() {
        assertThatThrownBy(() -> new PublicUrlProperties(null, API_URL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublicUrlProperties(PUBLIC_URL, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Traefik asks every client of the API's host for a certificate; browsers on the bank's pages have none.
    @Test
    void shouldRefuseApiOnBankAddress() {
        assertThatThrownBy(() -> new PublicUrlProperties(PUBLIC_URL, URI.create("https://LOCALHOST:8443")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bank-open-api.api-url must differ");
    }

    @Test
    void shouldRefusePathWithoutLeadingSlash() {
        PublicUrlProperties properties = new PublicUrlProperties(PUBLIC_URL, API_URL);

        assertThatThrownBy(() -> properties.page("customer")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.api("v2")).isInstanceOf(IllegalArgumentException.class);
    }
}
