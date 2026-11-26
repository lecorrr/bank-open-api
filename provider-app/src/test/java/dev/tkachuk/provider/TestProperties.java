package dev.tkachuk.provider;

import java.net.URI;
import java.nio.file.Path;

/** The provider's settings as application.yml gives them, for tests without a Spring context. */
public final class TestProperties {

    public static final String ISSUER = "https://localhost:8443/realms/bank";
    public static final String API_URL = "https://api.localhost:8443";

    public static final ProviderProperties PROVIDER = new ProviderProperties("provider-app",
            URI.create("https://provider.localhost:8443"), Path.of("/etc/provider-app/tls"),
            new ProviderProperties.Bank(URI.create(ISSUER), URI.create(API_URL), null));

    private TestProperties() {
    }
}
