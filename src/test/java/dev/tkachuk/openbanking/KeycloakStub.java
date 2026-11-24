package dev.tkachuk.openbanking;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** WireMock in place of Keycloak's certs endpoint, serving the public half of TestTokens' signing key. */
@TestConfiguration(proxyBeanMethods = false)
public class KeycloakStub {

    public static final String CERTS_PATH = "/realms/bank/protocol/openid-connect/certs";

    @Bean(destroyMethod = "stop")
    WireMockServer keycloak() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        serveSigningKeys(server);
        return server;
    }

    @Bean
    DynamicPropertyRegistrar keycloakProperties(@Qualifier("keycloak") WireMockServer keycloak) {
        return registry -> {
            registry.add("bank-open-api.provider-tokens.issuer", () -> TestTokens.ISSUER);
            registry.add("bank-open-api.provider-tokens.jwk-set-uri", () -> keycloak.baseUrl() + CERTS_PATH);
        };
    }

    /** What Keycloak answers when it works; a test that breaks it puts this back. */
    public static void serveSigningKeys(WireMockServer keycloak) {
        keycloak.stubFor(get(urlEqualTo(CERTS_PATH)).willReturn(okJson(TestTokens.jwkSet())));
    }
}
