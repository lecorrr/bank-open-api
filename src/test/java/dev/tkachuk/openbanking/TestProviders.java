package dev.tkachuk.openbanking;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Two providers for the tests, A and B, as the security chain sees them: a client certificate each and tokens
 * bound to it. The certificates in src/test/resources/certificates are self-signed and made for these tests; their
 * keys were not kept, as only the certificate travels in Traefik's header.
 */
public final class TestProviders {

    public static final String PROVIDER_A = "provider-a";
    public static final String PROVIDER_B = "provider-b";
    public static final String CERTIFICATE_HEADER = "X-Forwarded-Tls-Client-Cert";

    private TestProviders() {
    }

    /** The header value Traefik passes on for the provider's certificate: the PEM body on one line. */
    public static String certificateHeader(String provider) {
        String resource = "/certificates/" + provider + ".crt";
        try (InputStream in = TestProviders.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No test certificate " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII)
                    .replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replaceAll("\\s", "");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
    }

    /** The SHA-256 thumbprint of the provider's certificate, base64url, as a token's cnf.x5t#S256 carries it. */
    public static String thumbprint(String provider) {
        try {
            byte[] der = Base64.getDecoder().decode(certificateHeader(provider));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * For MockMvc: the request comes from the provider, with its certificate and a token Spring Security takes as
     * checked already (scope accounts, bound to that certificate).
     */
    public static RequestPostProcessor provider(String provider) {
        return provider(provider, "accounts");
    }

    /** As provider(String), with the scope given instead of accounts. */
    public static RequestPostProcessor provider(String provider, String scope) {
        return request -> {
            request.addHeader(CERTIFICATE_HEADER, certificateHeader(provider));
            return jwt().jwt(token -> token.claim("azp", provider)
                            .claim("scope", scope)
                            .claim("cnf", Map.of("x5t#S256", thumbprint(provider))))
                    .postProcessRequest(request);
        };
    }
}
