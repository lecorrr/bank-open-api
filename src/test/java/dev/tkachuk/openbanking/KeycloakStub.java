package dev.tkachuk.openbanking;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * WireMock in place of Keycloak inside the cluster: the certs endpoint, serving the public half of TestTokens'
 * signing key, and the token endpoint a customer's sign-in ends at.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KeycloakStub {

    public static final String CERTS_PATH = "/realms/bank/protocol/openid-connect/certs";
    public static final String TOKEN_PATH = "/realms/bank/protocol/openid-connect/token";
    /** The code Keycloak's login hands the browser on its way back to the bank. */
    public static final String CODE = "test-authorization-code";
    public static final String CLIENT_SECRET = "test-approval-secret";

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
            registry.add("bank-open-api.customer-sign-in.token-uri", () -> keycloak.baseUrl() + TOKEN_PATH);
            registry.add("bank-open-api.customer-sign-in.client-secret", () -> CLIENT_SECRET);
        };
    }

    /**
     * What Keycloak answers when the bank redeems the customer's code: tokens with these ID token claims, for the
     * customer pages' client with its secret only.
     */
    public static void signsIn(WireMockServer keycloak, Map<String, Object> idTokenClaims) {
        keycloak.stubFor(post(urlEqualTo(TOKEN_PATH))
                .withBasicAuth(TestTokens.CUSTOMER_CLIENT, CLIENT_SECRET)
                .withRequestBody(containing("code=" + CODE))
                .willReturn(okJson("""
                        {"access_token": "customer-access-token", "token_type": "Bearer", "expires_in": 300,
                         "scope": "openid profile", "id_token": "%s"}
                        """.formatted(TestTokens.idToken(idTokenClaims)))));
    }

    /** What Keycloak answers when it works; a test that breaks it puts this back. */
    public static void serveSigningKeys(WireMockServer keycloak) {
        keycloak.stubFor(get(urlEqualTo(CERTS_PATH)).willReturn(okJson(TestTokens.jwkSet())));
    }
}
