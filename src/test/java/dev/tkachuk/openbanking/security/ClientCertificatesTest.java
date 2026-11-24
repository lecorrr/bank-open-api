package dev.tkachuk.openbanking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.openbanking.TestProviders;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClientCertificatesTest {

    // Computed with openssl: x509 -outform DER | dgst -sha256 -binary | base64, made URL-safe without padding.
    private static final String THUMBPRINT_A = "qaZndu927UmLVwSWSlqFA9-vkgnp_6WUK03ngSmGtwQ";
    private static final String THUMBPRINT_B = "ohfC_W1lGS09E1IxuQs-VZBwigwk5cS7CeA2ARTGKmo";

    @Test
    void shouldReadTheCertificateTraefikPassesOn() {
        X509Certificate certificate = ClientCertificates.parse(TestProviders.certificateHeader("provider-a"));

        assertThat(certificate.getSubjectX500Principal().getName()).contains("CN=Test provider A");
    }

    // Traefik puts the provider's own certificate first and separates the rest of the chain with commas.
    @Test
    void shouldTakeTheFirstCertificateOfAChain() {
        String chain = TestProviders.certificateHeader("provider-b") + ","
                + TestProviders.certificateHeader("provider-a");

        assertThat(ClientCertificates.thumbprint(ClientCertificates.parse(chain))).isEqualTo(THUMBPRINT_B);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ",", "not base64!", "bm90IGEgY2VydGlmaWNhdGU="})
    void shouldRefuseHeaderWithoutReadableCertificate(String header) {
        assertThatThrownBy(() -> ClientCertificates.parse(header)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldComputeTheThumbprintAsRfc8705Has() {
        X509Certificate certificate = ClientCertificates.parse(TestProviders.certificateHeader("provider-a"));

        assertThat(ClientCertificates.thumbprint(certificate)).isEqualTo(THUMBPRINT_A);
        assertThat(TestProviders.thumbprint("provider-a")).isEqualTo(THUMBPRINT_A);
    }
}
