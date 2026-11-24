package dev.tkachuk.openbanking.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicUrlPropertiesTest {

    @Test
    void shouldBuildPublicAddressOfPath() {
        PublicUrlProperties properties = new PublicUrlProperties(URI.create("https://localhost:8443"));

        assertThat(properties.of("/customer/consents/42")).isEqualTo("https://localhost:8443/customer/consents/42");
    }

    // Links built from it go to customers' browsers and providers: only a bare https address will do.
    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8443", "https://localhost:8443/", "https://localhost:8443/bank",
            "https://localhost:8443?x=1", "https://localhost:8443#top", "localhost:8443", "https:///"})
    void shouldRefuseAddressThatIsNotBareHttps(String url) {
        assertThatThrownBy(() -> new PublicUrlProperties(URI.create(url)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bank-open-api.public-url");
    }

    @Test
    void shouldRefuseMissingAddress() {
        assertThatThrownBy(() -> new PublicUrlProperties(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRefusePathWithoutLeadingSlash() {
        PublicUrlProperties properties = new PublicUrlProperties(URI.create("https://localhost:8443"));

        assertThatThrownBy(() -> properties.of("customer")).isInstanceOf(IllegalArgumentException.class);
    }
}
