package dev.tkachuk.openbanking.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TppRedirectUrisTest {

    @Test
    void shouldAcceptNoUri() {
        assertThatCode(() -> TppRedirectUris.check(null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://tpp.example/done", "HTTPS://tpp.example/payments/done?state=42"})
    void shouldAcceptAbsoluteHttpsUri(String uri) {
        assertThatCode(() -> TppRedirectUris.check(URI.create(uri))).doesNotThrowAnyException();
    }

    @Test
    void shouldAcceptUriOfTheLengthTheBankStores() {
        String start = "https://tpp.example/";
        URI uri = URI.create(start + "a".repeat(TppRedirectUris.MAX_LENGTH - start.length()));

        assertThatCode(() -> TppRedirectUris.check(uri)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/payments/done", "http://tpp.example/done", "ftp://tpp.example/done",
            "mailto:help@tpp.example", "https:/done"})
    void shouldRefuseUriThatIsNotHttpsAddress(String uri) {
        assertFormatError(URI.create(uri));
    }

    @Test
    void shouldRefuseUriLongerThanTheBankStores() {
        String start = "https://tpp.example/";
        assertFormatError(URI.create(start + "a".repeat(TppRedirectUris.MAX_LENGTH - start.length() + 1)));
    }

    private static void assertFormatError(URI uri) {
        assertThatThrownBy(() -> TppRedirectUris.check(uri))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORMAT_ERROR));
    }
}
