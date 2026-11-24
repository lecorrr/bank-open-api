package dev.tkachuk.openbanking.security;

import dev.tkachuk.openbanking.common.PublicUrlProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.web.client.RestClient;

/**
 * The chain in front of the bank's pages for customers: approving, rejecting and withdrawing consents. A customer
 * signs in at Keycloak (client bank-approval, authorization code with PKCE); the session then holds their sign-in.
 * The pages are forms, so this chain keeps sessions and CSRF protection, which the API's chain switches off.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CustomerSignInProperties.class, PublicUrlProperties.class})
public class CustomerSignInConfig {

    /** The id in Spring Security's paths: /oauth2/authorization/keycloak and /login/oauth2/code/keycloak. */
    static final String REGISTRATION_ID = "keycloak";

    public static final String SIGN_IN_FAILED_PATH = "/customer/sign-in-failed";
    public static final String SIGN_OUT_PATH = "/customer/sign-out";
    public static final String SIGNED_OUT_PATH = "/customer/signed-out";
    public static final String STYLESHEET_PATH = "/customer/bank.css";

    // No form-action: browsers apply it to the redirect after a form too, and a decision redirects to the provider.
    static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'";

    private static final Logger log = LoggerFactory.getLogger(CustomerSignInConfig.class);

    // The API's chain (SecurityConfig) has no matcher and takes every other request, so it comes second.
    @Bean
    @Order(1)
    SecurityFilterChain customerPages(HttpSecurity http, ClientRegistrationRepository registrations) {
        http.securityMatcher("/customer/**", "/oauth2/authorization/**", "/login/oauth2/code/**")
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(SIGN_IN_FAILED_PATH, SIGNED_OUT_PATH, STYLESHEET_PATH).permitAll()
                        .anyRequest().authenticated())
                // One registration, so a customer who is not signed in goes straight to Keycloak.
                .oauth2Login(login -> login
                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestResolver(signInRequests(registrations)))
                        .failureHandler(signInFailed()))
                .logout(logout -> logout.logoutUrl(SIGN_OUT_PATH).logoutSuccessUrl(SIGNED_OUT_PATH))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        // The public address is localhost here, and browsers apply HSTS to every port of a host:
                        // Jenkins on http://localhost:8081 would stop working. On the bank's domain the edge sets it.
                        .httpStrictTransportSecurity(HeadersConfigurer.HstsConfig::disable));
        return http.build();
    }

    @Bean
    ClientRegistrationRepository clientRegistrations(CustomerSignInProperties signIn, PublicUrlProperties publicUrl) {
        // Built here rather than from Keycloak's discovery document: that is at the public address, which the pod
        // cannot reach, and the start must not wait for Keycloak.
        ClientRegistration keycloak = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(signIn.clientId())
                .clientSecret(signIn.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                // Fixed rather than taken from the request, and registered in Keycloak exactly so.
                .redirectUri(publicUrl.of("/login/oauth2/code/" + REGISTRATION_ID))
                .scope("openid", "profile")
                .issuerUri(signIn.issuer().toString())
                .authorizationUri(signIn.authorizationUri().toString())
                .tokenUri(signIn.tokenUri().toString())
                .jwkSetUri(signIn.jwkSetUri().toString())
                .userNameAttributeName(IdTokenClaimNames.SUB)
                // PKCE for a confidential client too, as OAuth 2.1 asks; Keycloak's client requires S256.
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                .build();
        return new InMemoryClientRegistrationRepository(keycloak);
    }

    @Bean
    JwtDecoderFactory<ClientRegistration> idTokenDecoders(RestTemplateBuilder restTemplates) {
        return new IdTokenDecoders(restTemplates.build());
    }

    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> customerTokenClient(
            RestClient.Builder restClients) {
        // Spring Security's client for the token endpoint, with Boot's HTTP client timeouts: a hung Keycloak fails
        // the sign-in instead of holding it. The converters and error handler are the ones it sets up itself.
        RestClient restClient = restClients
                .configureMessageConverters(converters -> {
                    converters.addCustomConverter(new FormHttpMessageConverter());
                    converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(restClient);
        return client;
    }

    // The pages never call anything with the customer's access token, so it lives only as long as their session.
    @Bean
    OAuth2AuthorizedClientRepository authorizedClients() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    @Bean
    SignedInCustomer signedInCustomer() {
        return new SignedInCustomer();
    }

    // Every approval asks for the password again (prompt=login): a Keycloak session left in a shared browser must
    // not let the next person approve consents without signing in. Spring Security adds PKCE and the nonce itself.
    private static OAuth2AuthorizationRequestResolver signInRequests(ClientRegistrationRepository registrations) {
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(registrations);
        resolver.setAuthorizationRequestCustomizer(request -> request
                .additionalParameters(parameters -> parameters.put("prompt", "login")));
        return resolver;
    }

    // Spring Security's own failure page is /login?error, which this chain does not serve: a customer whose
    // sign-in fails (an expired link, the back button at Keycloak) gets the bank's page instead.
    private static AuthenticationFailureHandler signInFailed() {
        SimpleUrlAuthenticationFailureHandler redirect = new SimpleUrlAuthenticationFailureHandler(SIGN_IN_FAILED_PATH);
        return (request, response, exception) -> {
            log.info("Customer sign-in failed: {}", reasonOf(exception));
            redirect.onAuthenticationFailure(request, response, exception);
        };
    }

    // The OAuth 2.0 error code only: the descriptions may quote the token's claims.
    private static String reasonOf(AuthenticationException exception) {
        return exception instanceof OAuth2AuthenticationException oauth2
                ? oauth2.getError().getErrorCode()
                : exception.getClass().getSimpleName();
    }
}
