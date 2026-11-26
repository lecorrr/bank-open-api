package dev.tkachuk.provider.bank;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * The client assertion of RFC 7523 that the provider signs in with (private_key_jwt): who the provider is, for which
 * authorization server, once, for a minute. Signed with RSASSA-PSS, SHA-256 and a 32-byte salt: PS256, the one RSA
 * algorithm FAPI 2.0 and the bank's realm accept. The same JWT as scripts/smoke-test.sh signs with openssl.
 */
final class ClientAssertion {

    /** How long Keycloak takes the assertion: long enough for the request, short enough to be no use later. */
    static final Duration LIFETIME = Duration.ofSeconds(60);

    /** PS256 as RFC 7518 defines it: SHA-256, MGF1 with SHA-256, a salt as long as the hash. */
    static final PSSParameterSpec PS256 = new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1);

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private static final String HEADER = "{\"alg\":\"PS256\",\"typ\":\"JWT\"}";
    private static final String CLAIMS =
            "{\"iss\":\"%s\",\"sub\":\"%s\",\"aud\":\"%s\",\"jti\":\"%s\",\"iat\":%d,\"exp\":%d}";

    private ClientAssertion() {
    }

    /**
     * The signed JWT, compact form.
     *
     * @param clientId the provider's client id: the assertion's issuer and subject
     * @param audience the authorization server's issuer, which Keycloak checks the assertion is meant for
     * @param jti an id used once, so the bank can refuse the same assertion twice
     */
    static String sign(String clientId, String audience, String jti, Instant now, PrivateKey key) {
        // The values go into the JSON as they are: none of them may need escaping.
        for (String value : new String[] {clientId, audience, jti}) {
            if (value.chars().anyMatch(c -> c == '"' || c == '\\' || c < 0x20)) {
                throw new IllegalArgumentException("a claim of the client assertion needs escaping: " + value);
            }
        }
        long issuedAt = now.getEpochSecond();
        String claims = CLAIMS.formatted(clientId, clientId, audience, jti, issuedAt, issuedAt + LIFETIME.toSeconds());
        String signingInput = encode(HEADER) + "." + encode(claims);
        return signingInput + "." + BASE64URL.encodeToString(signPs256(signingInput, key));
    }

    private static byte[] signPs256(String signingInput, PrivateKey key) {
        try {
            Signature signature = Signature.getInstance("RSASSA-PSS");
            signature.setParameter(PS256);
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not sign the client assertion with PS256", e);
        }
    }

    private static String encode(String json) {
        return BASE64URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
