package dev.tkachuk.openbanking.common;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two addresses this service is reached at through Traefik. bank-open-api.public-url is the bank's, where
 * customers' browsers open the pages; bank-open-api.api-url is the API's own host, where Traefik asks for the
 * provider's client certificate in the TLS handshake. Links in the API's answers, the customer's sign-in and the
 * protected resource metadata are built from them, not from the request, so a proxy in between cannot change them.
 */
@ConfigurationProperties("bank-open-api")
public record PublicUrlProperties(URI publicUrl, URI apiUrl) {

    public PublicUrlProperties {
        requireBareHttps("bank-open-api.public-url", publicUrl);
        requireBareHttps("bank-open-api.api-url", apiUrl);
        // One host cannot both ask every client for a certificate and serve browsers that have none.
        if (publicUrl.equals(apiUrl)) {
            throw new IllegalArgumentException(
                    "bank-open-api.api-url must differ from bank-open-api.public-url: the API has a host of its own");
        }
    }

    /** The address of one of the bank's pages, such as /customer/consents/42. */
    public String page(String path) {
        return publicUrl + requireAbsolutePath(path);
    }

    /** The address of a path of the API, such as /.well-known/oauth-protected-resource. */
    public String api(String path) {
        return apiUrl + requireAbsolutePath(path);
    }

    private static void requireBareHttps(String property, URI url) {
        boolean httpsHost = url != null && "https".equals(url.getScheme()) && url.getHost() != null;
        if (!httpsHost || !url.getRawPath().isEmpty() || url.getRawQuery() != null || url.getRawFragment() != null) {
            throw new IllegalArgumentException(property + " must be an https address without a path, got " + url);
        }
    }

    private static String requireAbsolutePath(String path) {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with /, got " + path);
        }
        return path;
    }
}
