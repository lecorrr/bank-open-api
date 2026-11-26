package dev.tkachuk.provider.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClientAssertionTest {

    private static final KeyPair KEYS = TestKeys.rsa();
    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");
    private static final String ISSUER = "https://localhost:8443/realms/bank";

    @Test
    void shouldSignWithPs256SoTheProvidersPublicKeyVerifiesIt() throws GeneralSecurityException {
        String jwt = ClientAssertion.sign("provider-app", ISSUER, "jti-1", NOW, KEYS.getPrivate());

        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        Signature verifier = Signature.getInstance("RSASSA-PSS");
        verifier.setParameter(ClientAssertion.PS256);
        verifier.initVerify(KEYS.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void shouldNameTheProviderTheIssuerAndAMinute() {
        String jwt = ClientAssertion.sign("provider-app", ISSUER, "jti-1", NOW, KEYS.getPrivate());

        assertThat(decode(jwt, 0)).isEqualTo("{\"alg\":\"PS256\",\"typ\":\"JWT\"}");
        assertThat(decode(jwt, 1)).isEqualTo("{\"iss\":\"provider-app\",\"sub\":\"provider-app\",\"aud\":\"" + ISSUER
                + "\",\"jti\":\"jti-1\",\"iat\":" + NOW.getEpochSecond() + ",\"exp\":" + (NOW.getEpochSecond() + 60)
                + "}");
    }

    @Test
    void shouldUseUnpaddedBase64Url() {
        String jwt = ClientAssertion.sign("provider-app", ISSUER, "jti-1", NOW, KEYS.getPrivate());

        assertThat(jwt).doesNotContain("=").doesNotContain("+").doesNotContain("/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider\"app", "provider\\app", "provider\napp"})
    void shouldRefuseAValueThatWouldNeedEscaping(String clientId) {
        assertThatThrownBy(() -> ClientAssertion.sign(clientId, ISSUER, "jti-1", NOW, KEYS.getPrivate()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String decode(String jwt, int part) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[part]), StandardCharsets.UTF_8);
    }
}
