package dev.tkachuk.provider.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.OpenTelemetryServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationConvention;

/**
 * The spans of the pages the provider app serves carry OpenTelemetry's names (http.request.method, http.route,
 * http.response.status_code, url.path) instead of Spring's own, as the bank's services do: APM Server reads them as
 * HTTP, and the bank's trace collector keeps exactly those.
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfig {

    @Bean
    ServerRequestObservationConvention serverRequestObservationConvention() {
        return new OpenTelemetryServerRequestObservationConvention();
    }
}
