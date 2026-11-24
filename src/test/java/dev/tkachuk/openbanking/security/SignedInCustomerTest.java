package dev.tkachuk.openbanking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class SignedInCustomerTest {

    private final SignedInCustomer customer = new SignedInCustomer();

    @AfterEach
    void forgetTheSignIn() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldReadCustomerNumberAndNameKeycloakPutsIntoTheIdToken() {
        signIn(Map.of("customer_id", "C1001", "name", "Artem Demo"));

        assertThat(customer.id()).isEqualTo("C1001");
        assertThat(customer.name()).isEqualTo("Artem Demo");
    }

    @Test
    void shouldGreetNobodyByNameWhenTheIdTokenHasNone() {
        signIn(Map.of("customer_id", "C1001"));

        assertThat(customer.name()).isEqualTo("");
    }

    // A Keycloak user without the attribute, such as an administrator, is no customer of the bank.
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void shouldRefuseSignInWithoutCustomerNumber(String customerId) {
        Map<String, Object> claims = new HashMap<>();
        if (customerId != null) {
            claims.put("customer_id", customerId);
        }
        signIn(claims);

        assertThatThrownBy(customer::id).isInstanceOf(NotACustomerException.class);
    }

    @Test
    void shouldRefuseWhenNobodyIsSignedIn() {
        assertThatThrownBy(customer::id).isInstanceOf(NotACustomerException.class);
    }

    @Test
    void shouldEndSessionAndSignInOnSignOut() {
        signIn(Map.of("customer_id", "C1001"));
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);

        customer.signOut(request, new MockHttpServletResponse());

        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static void signIn(Map<String, Object> claims) {
        Map<String, Object> idTokenClaims = new HashMap<>(claims);
        idTokenClaims.put("sub", "5c1f8a2e-0d4b-4c7e-9f3a-1b2c3d4e5f60");
        OidcIdToken idToken = new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(300), idTokenClaims);
        DefaultOidcUser user = new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(user, null, "OIDC_USER"));
    }
}
