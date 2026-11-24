package dev.tkachuk.openbanking.security;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestOperations;

/**
 * Decodes the ID token a customer's sign-in brings back. Keycloak's realm bank signs with PS256 only, where Spring
 * Security's own factory expects RS256, and the keys are read with Boot's HTTP client timeouts. The checks are the
 * ones Spring Security makes on ID tokens: type JWT, expiry, then OidcIdTokenValidator (issuer, audience,
 * authorized party, issue time).
 */
final class IdTokenDecoders implements JwtDecoderFactory<ClientRegistration> {

    private final RestOperations http;
    // One decoder per registration: each keeps its own cache of Keycloak's keys.
    private final Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();

    IdTokenDecoders(RestOperations http) {
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public JwtDecoder createDecoder(ClientRegistration registration) {
        return decoders.computeIfAbsent(registration.getRegistrationId(), id -> build(registration));
    }

    private JwtDecoder build(ClientRegistration registration) {
        String jwkSetUri = registration.getProviderDetails().getJwkSetUri();
        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            throw new IllegalStateException("Client registration " + registration.getRegistrationId()
                    + " has no JWK set URI to check ID tokens with");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.PS256)
                .restOperations(http)
                .build();
        List<OAuth2TokenValidator<Jwt>> oidcRules = List.of(new OidcIdTokenValidator(registration));
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(oidcRules));
        return decoder;
    }
}
