package dev.tkachuk.openbanking;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Access tokens as Keycloak's realm bank issues them to a provider: JWTs of type at+jwt, signed with PS256 and
 * bound to the provider's certificate. The key is made for each test run; KeycloakStub serves its public half.
 */
public final class TestTokens {

    public static final String ISSUER = "https://keycloak.test/realms/bank";
    public static final String KEY_ID = "test-key";

    private static final KeyPair KEYS = newKeyPair();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TestTokens() {
    }

    /** The JWK set Keycloak's certs endpoint answers with: the public half of the signing key. */
    public static String jwkSet() {
        RSAPublicKey key = (RSAPublicKey) KEYS.getPublic();
        Map<String, Object> jwk = Map.of("kty", "RSA", "kid", KEY_ID, "use", "sig", "alg", "PS256",
                "n", base64Url(unsigned(key.getModulus())), "e", base64Url(unsigned(key.getPublicExponent())));
        return JSON.writeValueAsString(Map.of("keys", List.of(jwk)));
    }

    /** The provider's token for scope accounts, valid for five minutes. */
    public static String tokenFor(String provider) {
        return sign(claimsFor(provider));
    }

    /** The claims Keycloak puts into a provider's token, to be changed by a test before signing. */
    public static Map<String, Object> claimsFor(String provider) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", "bank-open-api");
        claims.put("sub", UUID.nameUUIDFromBytes(provider.getBytes(StandardCharsets.UTF_8)).toString());
        claims.put("azp", provider);
        claims.put("scope", "accounts");
        claims.put("iat", now);
        claims.put("exp", now + 300);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("cnf", Map.of("x5t#S256", TestProviders.thumbprint(provider)));
        return claims;
    }

    public static String sign(Map<String, Object> claims) {
        return sign(KEY_ID, claims);
    }

    /** Signs with the test key, naming the key id given in the header. */
    public static String sign(String keyId, Map<String, Object> claims) {
        String header = base64Url(JSON.writeValueAsBytes(Map.of("alg", "PS256", "typ", "at+jwt", "kid", keyId)));
        String signingInput = header + "." + base64Url(JSON.writeValueAsBytes(claims));
        try {
            // PS256 as RFC 7518 defines it: RSASSA-PSS with SHA-256, MGF1 with SHA-256 and a 32-byte salt.
            Signature signature = Signature.getInstance("RSASSA-PSS");
            signature.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
            signature.initSign(KEYS.getPrivate());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + base64Url(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign a test token", e);
        }
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot make the test signing key", e);
        }
    }

    // A JWK holds the modulus and exponent as unsigned big-endian bytes; BigInteger may add a leading zero byte.
    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        return bytes.length > 1 && bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
