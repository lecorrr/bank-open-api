package dev.tkachuk.openbanking.bankcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class BankCorePropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {"http://bank-core:8080", "https://bank-core.bank.svc.cluster.local"})
    void shouldAcceptHttpUrl(String url) {
        assertThat(new BankCoreProperties(URI.create(url)).url()).hasToString(url);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"bank-core:8080", "/api/v1", "ftp://bank-core", "http:///api"})
    void shouldRefuseAnythingButAnHttpUrl(String url) {
        URI uri = url == null ? null : URI.create(url);

        assertThatThrownBy(() -> new BankCoreProperties(uri))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("bank-open-api.bank-core.url must be an http or https URL");
    }
}
