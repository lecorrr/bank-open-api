package dev.tkachuk.openbanking.security;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * bank-open-api.customer-sign-in: Keycloak's client through which a customer signs in to the bank's pages. The
 * browser goes to the authorization address, which is public; bank-open-api calls the token address and reads the
 * keys itself, inside the cluster. The issuer is the realm's public address, which the ID token names.
 */
@ConfigurationProperties("bank-open-api.customer-sign-in")
public record CustomerSignInProperties(String clientId, String clientSecret, URI issuer, URI authorizationUri,
        URI tokenUri, URI jwkSetUri) {

    private static final String PREFIX = "bank-open-api.customer-sign-in.";

    public CustomerSignInProperties {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException(PREFIX + "client-id must not be empty");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException(PREFIX + "client-secret must not be empty");
        }
        requireHttps("issuer", issuer);
        requireHttps("authorization-uri", authorizationUri);
        requireHttpOrHttps("token-uri", tokenUri);
        requireHttpOrHttps("jwk-set-uri", jwkSetUri);
    }

    // The customer's browser must reach Keycloak over TLS; the issuer is the same public address.
    private static void requireHttps(String name, URI uri) {
        if (uri == null || uri.getHost() == null || !"https".equals(uri.getScheme())) {
            throw new IllegalArgumentException(PREFIX + name + " must be an https URL, got " + uri);
        }
    }

    // Inside the cluster bank-open-api calls Keycloak directly, over plain HTTP.
    private static void requireHttpOrHttps(String name, URI uri) {
        boolean web = uri != null && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()));
        if (!web || uri.getHost() == null) {
            throw new IllegalArgumentException(PREFIX + name + " must be an http or https URL, got " + uri);
        }
    }

    @Override
    public String toString() {
        // A record prints every component; the secret stays out of logs and failure reports.
        return "CustomerSignInProperties[clientId=" + clientId + ", issuer=" + issuer + "]";
    }
}
