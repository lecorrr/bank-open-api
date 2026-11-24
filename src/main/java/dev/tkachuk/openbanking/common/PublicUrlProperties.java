package dev.tkachuk.openbanking.common;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * bank-open-api.public-url: the address providers and customers reach this service at, through Traefik. Links in
 * the API's answers, the customer's sign-in and the protected resource metadata are built from it, not from the
 * request, so a proxy in between cannot change them.
 */
@ConfigurationProperties("bank-open-api")
public record PublicUrlProperties(URI publicUrl) {

    public PublicUrlProperties {
        boolean httpsHost = publicUrl != null && "https".equals(publicUrl.getScheme()) && publicUrl.getHost() != null;
        if (!httpsHost || !publicUrl.getRawPath().isEmpty() || publicUrl.getRawQuery() != null
                || publicUrl.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "bank-open-api.public-url must be an https address without a path, got " + publicUrl);
        }
    }

    /** The public address of a path of this service, such as /customer/consents. */
    public String of(String path) {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with /, got " + path);
        }
        return publicUrl + path;
    }
}
