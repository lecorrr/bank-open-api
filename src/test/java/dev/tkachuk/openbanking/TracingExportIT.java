package dev.tkachuk.openbanking;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * With an OTLP endpoint, as the Helm chart gives one, the service sends its spans there: a request's span arrives
 * under the service's name and in OpenTelemetry's HTTP names, the ones the trace collector keeps. WireMock stands in
 * for the collector. Its own context, closed after the class, so no exporter is left sending to a stopped stub.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, BankCoreStub.class, KeycloakStub.class})
@DirtiesContext
class TracingExportIT {

    private static final WireMockServer COLLECTOR =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        COLLECTOR.start();
        COLLECTOR.stubFor(post(urlEqualTo("/v1/traces")).willReturn(ok()));
    }

    // Before the context starts: Spring Boot decides on the exporter while it reads the configuration, which a
    // DynamicPropertyRegistrar bean would be too late for.
    @DynamicPropertySource
    static void collector(DynamicPropertyRegistry registry) {
        registry.add("management.opentelemetry.tracing.export.otlp.endpoint", () -> COLLECTOR.baseUrl() + "/v1/traces");
        // A batch every 100 ms rather than every 5 s.
        registry.add("management.opentelemetry.tracing.export.schedule-delay", () -> "100ms");
    }

    @AfterAll
    static void stopCollector() {
        COLLECTOR.stop();
    }

    @LocalServerPort
    private int port;

    @Test
    void shouldSendARequestsSpanToTheCollectorInOpenTelemetrysNames() {
        RestClient.create("http://localhost:" + port).get().uri("/actuator/health/liveness").retrieve()
                .toBodilessEntity();

        // OTLP/HTTP sends protobuf, in which the names and values are plain UTF-8.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> COLLECTOR.verify(postRequestedFor(urlEqualTo(
                "/v1/traces"))
                .withRequestBody(containing("bank-open-api"))
                .withRequestBody(containing("http.route"))
                .withRequestBody(containing("http.response.status_code"))));
    }
}
