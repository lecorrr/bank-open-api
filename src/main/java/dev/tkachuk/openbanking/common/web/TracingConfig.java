package dev.tkachuk.openbanking.common.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.OpenTelemetryServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationConvention;

/**
 * The spans of the requests this service serves carry OpenTelemetry's names (http.request.method, http.route,
 * http.response.status_code, url.path) instead of Spring's own (method, uri, status). APM Server reads those as HTTP,
 * so Kibana shows each request's method, route and status, and the trace collector lets exactly those through.
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfig {

    @Bean
    ServerRequestObservationConvention serverRequestObservationConvention() {
        return new OpenTelemetryServerRequestObservationConvention();
    }
}
