package dev.tkachuk.openbanking.bankcore;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** bank-open-api.bank-core.url: the base URL of bank-core's REST API, checked at startup. */
@ConfigurationProperties("bank-open-api.bank-core")
public record BankCoreProperties(URI url) {

    public BankCoreProperties {
        if (url == null || url.getHost() == null
                || !("http".equals(url.getScheme()) || "https".equals(url.getScheme()))) {
            throw new IllegalArgumentException("bank-open-api.bank-core.url must be an http or https URL, got " + url);
        }
    }
}
