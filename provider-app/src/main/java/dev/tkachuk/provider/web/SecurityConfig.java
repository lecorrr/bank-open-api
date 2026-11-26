package dev.tkachuk.provider.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Nobody signs in to the provider's pages, so every page is open. Spring Security still guards the forms: each
 * carries the session's CSRF token (Thymeleaf's th:action adds it), and a form posted from another site has none.
 * Starting a consent or a payment from a stranger's page would otherwise send a visitor to the bank with an account
 * or payee they did not choose. Spring Security's headers come along: no framing, no sniffing, HSTS.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain pages(HttpSecurity http) {
        http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        return http.build();
    }
}
