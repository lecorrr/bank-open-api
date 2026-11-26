package dev.tkachuk.provider.bank;

import dev.tkachuk.provider.ProviderProperties;
import java.io.IOException;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.pem.PemContent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The provider's way to the bank. Every call, the sign-in included, goes over mutual TLS: the provider's
 * certificate from the SSL bundle "bank" (application.yml), and the bank's certificate checked against the bank's
 * CA. In the cluster the bank's addresses go to its edge (BankEdgeResolver). The RestClient comes from Spring Boot's
 * builder, so each call carries the trace.
 */
@Configuration(proxyBeanMethods = false)
class BankConnection {

    // A slow bank must not hold the customer's page for long; they get an error page and can try again.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    RestClient bankRestClient(RestClient.Builder builder, SslBundles sslBundles, ProviderProperties properties) {
        ProviderProperties.Bank bank = properties.bank();
        HttpComponentsClientHttpRequestFactoryBuilder requestFactory = ClientHttpRequestFactoryBuilder.httpComponents();
        if (bank.connectTo() != null) {
            requestFactory = requestFactory.withDnsResolver(
                    new BankEdgeResolver(List.of(bank.issuer(), bank.apiUrl()), bank.connectTo()));
        }
        // The bank's answers are read as they come: a redirect from the API or the token endpoint is an error.
        HttpClientSettings settings = HttpClientSettings.ofSslBundle(sslBundles.getBundle("bank"))
                .withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT)
                .withRedirects(HttpRedirects.DONT_FOLLOW);
        return builder.requestFactory(requestFactory.build(settings)).build();
    }

    /** The key of the provider's certificate, which also signs its client assertions. */
    @Bean
    PrivateKey providerKey(ProviderProperties properties) throws IOException {
        PrivateKey key = PemContent.load(properties.privateKey()).getPrivateKey();
        if (key == null) {
            throw new IllegalStateException(properties.privateKey() + " holds no private key");
        }
        return key;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
