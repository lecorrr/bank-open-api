package dev.tkachuk.openbanking.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The provider the current request comes from: the client id Keycloak put into its token as "azp". Only requests
 * whose token and certificate the security chain accepted reach the controllers, so there always is one there.
 */
public class AuthenticatedProvider {

    public String id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            String provider = token.getToken().getClaimAsString("azp");
            if (provider != null && !provider.isBlank()) {
                return provider;
            }
        }
        throw new IllegalStateException("The request has no authenticated provider");
    }
}
