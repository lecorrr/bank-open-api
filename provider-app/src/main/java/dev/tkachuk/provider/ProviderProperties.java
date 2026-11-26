package dev.tkachuk.provider;

import java.net.URI;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who the provider is at the bank and where it finds the bank.
 *
 * @param clientId the provider's client at the bank's Keycloak, which its certificate belongs to
 * @param publicUrl the provider's own address, where the bank sends the customer's browser back
 * @param tlsDirectory the provider's key and certificate from the bank's CA, and the CA itself: tls.key, tls.crt
 *     and ca.crt. The key signs the provider's sign-in and its certificate goes into every TLS handshake with the
 *     bank (application.yml's SSL bundle reads the same files).
 * @param bank the bank's addresses
 */
@ConfigurationProperties("provider-app")
public record ProviderProperties(String clientId, URI publicUrl, Path tlsDirectory, Bank bank) {

    // The client id goes into a JWT as it is, so it is kept to characters that need no escaping.
    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    // A host and a port, as curl's --connect-to takes them.
    private static final Pattern HOST_AND_PORT = Pattern.compile("[A-Za-z0-9.-]+:[0-9]{1,5}");

    public ProviderProperties {
        if (clientId == null || !CLIENT_ID.matcher(clientId).matches()) {
            throw new IllegalArgumentException(
                    "provider-app.client-id must be 1 to 64 letters, digits, '.', '_' or '-', got " + clientId);
        }
        requireBareHttps("provider-app.public-url", publicUrl);
        if (tlsDirectory == null) {
            throw new IllegalArgumentException("provider-app.tls-directory must name the folder of tls.key");
        }
        if (bank == null) {
            throw new IllegalArgumentException("provider-app.bank must give the bank's addresses");
        }
    }

    /** The provider's private key, which signs its JWTs. */
    public Path privateKey() {
        return tlsDirectory.resolve("tls.key");
    }

    /** An address of the provider's own, such as /consents/done, for the bank to send the customer back to. */
    public URI page(String path) {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with /, got " + path);
        }
        return URI.create(publicUrl + path);
    }

    /**
     * The bank's addresses, as a provider outside the bank uses them.
     *
     * @param issuer the bank's authorization server (Keycloak's realm), whose discovery document names its token
     *     endpoint
     * @param apiUrl the bank's API, on its own host, where Traefik requires the provider's certificate
     * @param connectTo where a connection to the issuer's or the API's address goes instead, as host:port; empty to
     *     look the names up as usual. In the cluster the bank's names, localhost and api.localhost, would mean the
     *     provider's own pod, so its connections go to Traefik's Service; the names stay those of the bank, in the
     *     TLS handshake, the certificate check and the Host header.
     */
    public record Bank(URI issuer, URI apiUrl, @Nullable String connectTo) {

        public Bank {
            requireHttpsHost("provider-app.bank.issuer", issuer);
            requireBareHttps("provider-app.bank.api-url", apiUrl);
            if (connectTo != null && connectTo.isBlank()) {
                connectTo = null;
            }
            if (connectTo != null && !HOST_AND_PORT.matcher(connectTo).matches()) {
                throw new IllegalArgumentException(
                        "provider-app.bank.connect-to must be host:port, got " + connectTo);
            }
        }
    }

    private static void requireHttpsHost(String property, @Nullable URI url) {
        if (url == null || !"https".equals(url.getScheme()) || url.getHost() == null || url.getRawQuery() != null
                || url.getRawFragment() != null) {
            throw new IllegalArgumentException(property + " must be an https address, got " + url);
        }
    }

    private static void requireBareHttps(String property, @Nullable URI url) {
        requireHttpsHost(property, url);
        if (!url.getRawPath().isEmpty()) {
            throw new IllegalArgumentException(property + " must be an https address without a path, got " + url);
        }
    }
}
