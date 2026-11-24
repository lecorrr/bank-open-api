package dev.tkachuk.openbanking.security;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * bank-open-api.provider-tokens: the issuer every provider token must name, where its signing keys are read, and
 * the audience that marks a token as meant for this API.
 */
@ConfigurationProperties("bank-open-api.provider-tokens")
public record ProviderTokenProperties(URI issuer, URI jwkSetUri, String audience) {

    private static final String PREFIX = "bank-open-api.provider-tokens.";

    public ProviderTokenProperties {
        // FAPI 2.0 has the authorization server on TLS only, so its issuer is an https URL.
        if (issuer == null || issuer.getHost() == null || !"https".equals(issuer.getScheme())) {
            throw new IllegalArgumentException(PREFIX + "issuer must be an https URL, got " + issuer);
        }
        // Inside the cluster bank-open-api reads the keys from Keycloak directly, over plain HTTP.
        if (jwkSetUri == null || jwkSetUri.getHost() == null
                || !("http".equals(jwkSetUri.getScheme()) || "https".equals(jwkSetUri.getScheme()))) {
            throw new IllegalArgumentException(PREFIX + "jwk-set-uri must be an http or https URL, got " + jwkSetUri);
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException(PREFIX + "audience must not be empty");
        }
    }
}
