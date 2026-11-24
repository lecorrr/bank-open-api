package dev.tkachuk.openbanking.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

/**
 * The customer signed in on the bank's pages, as Keycloak's ID token names them: customer_id is their number in
 * the bank's records (bank-core's accounts carry the same number), mapped from an attribute of their user.
 */
public class SignedInCustomer {

    static final String CUSTOMER_ID_CLAIM = "customer_id";

    /** The customer's number; NotACustomerException when the sign-in carries none. */
    public String id() {
        String id = currentUser().getClaimAsString(CUSTOMER_ID_CLAIM);
        if (id == null || id.isBlank()) {
            throw new NotACustomerException("The signed-in user has no customer number");
        }
        return id;
    }

    /** The name to greet the customer with; empty when the ID token has none. */
    public String name() {
        String name = currentUser().getFullName();
        return name == null ? "" : name;
    }

    /** Ends the sign-in on the bank's pages: the session goes, and the next page asks for the password again. */
    public void signOut(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }

    private static OidcUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser user) {
            return user;
        }
        throw new NotACustomerException("No customer is signed in");
    }
}
