package dev.tkachuk.openbanking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AuthenticatedProviderTest {

    private final AuthenticatedProvider provider = new AuthenticatedProvider();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldNameTheProviderOfTheToken() {
        signIn(Jwt.withTokenValue("token").header("alg", "PS256").claim("azp", "provider-a").build());

        assertThat(provider.id()).isEqualTo("provider-a");
    }

    // The security chain lets neither through to a controller, so meeting one is a bug.
    @Test
    void shouldFailLoudlyWithoutProviderToken() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThatThrownBy(provider::id).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldFailLoudlyForTokenThatNamesNoProvider() {
        signIn(Jwt.withTokenValue("token").header("alg", "PS256").claim("sub", "someone").build());

        assertThatThrownBy(provider::id).isInstanceOf(IllegalStateException.class);
    }

    private static void signIn(Jwt token) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
    }
}
