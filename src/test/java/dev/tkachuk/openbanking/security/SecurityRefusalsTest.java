package dev.tkachuk.openbanking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.web.servlet.ModelAndView;

class SecurityRefusalsTest {

    private final List<Exception> answered = new ArrayList<>();
    private final SecurityRefusals refusals = new SecurityRefusals((request, response, handler, exception) -> {
        answered.add(exception);
        return new ModelAndView();
    });
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v2/accounts");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void shouldAnswerRequestWithoutTokenWithTheBareChallenge() {
        refusals.commence(request, response, new InsufficientAuthenticationException("Full authentication"));

        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(answeredCode()).isEqualTo(ErrorCode.TOKEN_UNKNOWN);
    }

    @Test
    void shouldAnswerBrokenTokenRuleWithItsCodeAndDescription() {
        TokenRefusal expired = new TokenRefusal(ErrorCode.TOKEN_EXPIRED, "The access token has expired; get a new one");

        refusals.commence(request, response, invalidToken(expired));

        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer error=\"invalid_token\"");
        ApiException refusal = (ApiException) answered.get(0);
        assertThat(refusal.code()).isEqualTo(ErrorCode.TOKEN_EXPIRED);
        assertThat(refusal.providerDetail()).contains("The access token has expired; get a new one");
    }

    @Test
    void shouldAnswerValidationErrorOfAnotherKindAsInvalidToken() {
        refusals.commence(request, response, invalidToken(new OAuth2Error("invalid_token", "Jwt expired", null)));

        assertThat(answeredCode()).isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    // A bad signature or a token that is no JWT at all never reaches the validator.
    @Test
    void shouldAnswerUnreadableTokenAsUnknown() {
        refusals.commence(request, response, new InvalidBearerTokenException("Signed JWT rejected: Invalid signature"));

        assertThat(answeredCode()).isEqualTo(ErrorCode.TOKEN_UNKNOWN);
    }

    @Test
    void shouldAnswerKeycloakOutageWith503AndNoChallenge() {
        refusals.commence(request, response, new AuthenticationServiceException("Couldn't retrieve remote JWK set"));

        assertThat(response.getHeader("WWW-Authenticate")).isNull();
        ApiException refusal = (ApiException) answered.get(0);
        assertThat(refusal.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        // The cause names Keycloak's address, so the provider only gets the code's text.
        assertThat(refusal.providerDetail()).isEmpty();
    }

    @Test
    void shouldAnswerMissingScopeAsRoleInvalid() {
        refusals.handle(request, response, new AccessDeniedException("Access Denied"));

        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer error=\"insufficient_scope\"");
        assertThat(answeredCode()).isEqualTo(ErrorCode.ROLE_INVALID);
    }

    @Test
    void shouldFailLoudlyWhenNoExceptionHandlerAnswers() {
        SecurityRefusals unanswered = new SecurityRefusals((req, res, handler, exception) -> null);

        assertThatThrownBy(() -> unanswered.handle(request, response, new AccessDeniedException("Access Denied")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROLE_INVALID");
    }

    private ErrorCode answeredCode() {
        assertThat(answered).hasSize(1);
        return ((ApiException) answered.get(0)).code();
    }

    // What JwtAuthenticationProvider throws when the decoder's validator refuses a token.
    private static InvalidBearerTokenException invalidToken(OAuth2Error error) {
        return new InvalidBearerTokenException("An error occurred while attempting to decode the Jwt",
                new JwtValidationException("Jwt validation failed", List.of(error)));
    }
}
