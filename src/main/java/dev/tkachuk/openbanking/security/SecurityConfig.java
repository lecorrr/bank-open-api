package dev.tkachuk.openbanking.security;

import dev.tkachuk.openbanking.common.PublicUrlProperties;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The security chain in front of the API. A provider calls with an access token from Keycloak's realm bank, over
 * mutual TLS with the certificate the token is bound to; Traefik ends the TLS and passes the certificate on.
 * Spring Security checks the token (PS256 signature with Keycloak's keys, then ProviderTokenValidator's rules),
 * CertificateBindingFilter the certificate, and the authorization rules the scope. Everything the customer pages'
 * chain (CustomerSignInConfig) does not take ends here, and what is not the API is refused.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ProviderTokenProperties.class, PublicUrlProperties.class})
public class SecurityConfig {

    /** What Keycloak's client scope "accounts" grants: consents and account information. */
    static final String ACCOUNTS_SCOPE = "SCOPE_accounts";

    /** RFC 9728: where a client finds out how to get a token for this API. */
    static final String RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource";

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtDecoder providerTokenDecoder, SecurityRefusals refusals,
            ProviderTokenProperties tokens, PublicUrlProperties publicUrl, Clock clock) {
        http.authorizeHttpRequests(requests -> requests
                        // Errors that escape Spring MVC end on /error; they keep their own answer.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/openapi/**").permitAll()
                        .requestMatchers("/v2/**").hasAuthority(ACCOUNTS_SCOPE)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(providerTokenDecoder))
                        .authenticationEntryPoint(refusals)
                        .accessDeniedHandler(refusals)
                        .withObjectPostProcessor(answerKeyFailuresThrough(refusals))
                        // Spring Security answers RFC 9728's metadata ahead of the rules above, for anyone: it says
                        // where tokens come from, nothing secret. Bearer header and bound tokens are its defaults.
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(
                                resource -> resource
                                        .resource(publicUrl.publicUrl().toString())
                                        .authorizationServer(tokens.issuer().toString())
                                        .scope("accounts")
                                        .resourceName("bank-open-api"))))
                .addFilterAfter(new CertificateBindingFilter(refusals, clock), BearerTokenAuthenticationFilter.class)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(refusals)
                        .accessDeniedHandler(refusals))
                // Providers send their token with every request: no sessions, and no cookies for CSRF to protect.
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                // No HSTS for localhost, as on the customer pages (CustomerSignInConfig).
                .headers(headers -> headers.httpStrictTransportSecurity(HeadersConfigurer.HstsConfig::disable));
        return http.build();
    }

    @Bean
    JwtDecoder providerTokenDecoder(ProviderTokenProperties properties, RestTemplateBuilder restTemplates,
            Clock clock) {
        // Keycloak's realm bank signs with PS256 only (FAPI 2.0), so a token in any other algorithm is refused. The
        // keys are read with Boot's HTTP client settings: a hung Keycloak times out as a hung bank-core does.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.PS256)
                .restOperations(restTemplates.build())
                .build();
        decoder.setJwtValidator(
                new ProviderTokenValidator(properties.issuer().toString(), properties.audience(), clock));
        return decoder;
    }

    @Bean
    SecurityRefusals securityRefusals(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors,
            PublicUrlProperties publicUrl) {
        return new SecurityRefusals(errors, publicUrl.of(RESOURCE_METADATA_PATH));
    }

    @Bean
    AuthenticatedProvider authenticatedProvider() {
        return new AuthenticatedProvider();
    }

    // The bearer token filter rethrows a failure to read Keycloak's keys (AuthenticationServiceException), which
    // would end as a 500. Handed to the entry point instead, it becomes 503 SERVICE_UNAVAILABLE.
    private static ObjectPostProcessor<BearerTokenAuthenticationFilter> answerKeyFailuresThrough(
            SecurityRefusals refusals) {
        return new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
            @Override
            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                filter.setAuthenticationFailureHandler(refusals::commence);
                return filter;
            }
        };
    }
}
