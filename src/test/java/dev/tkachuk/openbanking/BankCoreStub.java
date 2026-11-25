package dev.tkachuk.openbanking;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** WireMock in place of bank-core's REST API. */
@TestConfiguration(proxyBeanMethods = false)
public class BankCoreStub {

    @Bean(destroyMethod = "stop")
    WireMockServer bankCore() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    @Bean
    DynamicPropertyRegistrar bankCoreUrl(@Qualifier("bankCore") WireMockServer bankCore) {
        return registry -> registry.add("bank-open-api.bank-core.url", bankCore::baseUrl);
    }

    // The retry job would call this stub by itself in the middle of a test; a test that needs it runs it.
    @Bean
    DynamicPropertyRegistrar paymentRetryDelay() {
        return registry -> registry.add("bank-open-api.payments.retry-delay", () -> "1h");
    }
}
