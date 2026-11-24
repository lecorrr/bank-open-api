package dev.tkachuk.openbanking.security;

import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Answers every request the security chain refuses in the contract's error form. The answer goes through Spring
 * MVC's exception handling, so ApiExceptionHandler writes and logs it like any other error; this class adds the
 * WWW-Authenticate header of RFC 6750, with RFC 9728's link to the protected resource metadata.
 */
final class SecurityRefusals implements AuthenticationEntryPoint, AccessDeniedHandler {

    /** The challenge's error when the request had no token at all: RFC 6750 asks for none then. */
    static final String NO_ERROR = "";

    private final HandlerExceptionResolver errors;
    private final String resourceMetadata;

    SecurityRefusals(HandlerExceptionResolver errors, String resourceMetadata) {
        this.errors = Objects.requireNonNull(errors, "errors");
        this.resourceMetadata = Objects.requireNonNull(resourceMetadata, "resourceMetadata");
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) {
        // RFC 6750: a request without any token gets the challenge without an error, a refused token the error code.
        String error = exception instanceof InsufficientAuthenticationException ? NO_ERROR : null;
        refuse(request, response, refusalFor(exception), error);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) {
        // A valid token without the scope: in the contract's words the provider lacks the role for this service.
        refuse(request, response, new ApiException(ErrorCode.ROLE_INVALID, ErrorCode.ROLE_INVALID.text()), null);
    }

    /**
     * Answers with the refusal. error is RFC 6750's error code for the challenge: null for the usual one of the
     * refusal's code, NO_ERROR for a challenge without one.
     */
    void refuse(HttpServletRequest request, HttpServletResponse response, ApiException refusal,
            @Nullable String error) {
        String challengeError = error != null ? error : errorFor(refusal.code());
        if (challengeError != null) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge(challengeError));
        }
        if (errors.resolveException(request, response, null, refusal) == null) {
            throw new IllegalStateException("No exception handler answered " + refusal.code().code());
        }
    }

    /** The contract's code for a failed authentication. */
    static ApiException refusalFor(AuthenticationException exception) {
        if (exception instanceof AuthenticationServiceException) {
            // Keycloak's signing keys could not be read: the provider's request may well be fine, so 503, not 401.
            return new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Keycloak's signing keys could not be read: " + exception.getMessage(), exception);
        }
        if (exception instanceof InsufficientAuthenticationException) {
            return new ApiException(ErrorCode.TOKEN_UNKNOWN, "The request has no access token");
        }
        if (exception.getCause() instanceof JwtValidationException invalid) {
            return invalid.getErrors().stream()
                    .filter(TokenRefusal.class::isInstance)
                    .map(error -> ((TokenRefusal) error).toApiException())
                    .findFirst()
                    .orElseGet(() -> new ApiException(ErrorCode.TOKEN_INVALID, ErrorCode.TOKEN_INVALID.text()));
        }
        // Not readable as a token of the bank: malformed, signed with another key, or not from Keycloak at all.
        return new ApiException(ErrorCode.TOKEN_UNKNOWN, ErrorCode.TOKEN_UNKNOWN.text());
    }

    private String challenge(String error) {
        String metadata = "resource_metadata=\"" + resourceMetadata + "\"";
        return error.isEmpty() ? "Bearer " + metadata : "Bearer error=\"" + error + "\", " + metadata;
    }

    private static @Nullable String errorFor(ErrorCode code) {
        return switch (code) {
            case TOKEN_UNKNOWN, TOKEN_INVALID, TOKEN_EXPIRED, CERTIFICATE_MISSING, CERTIFICATE_INVALID,
                    CERTIFICATE_EXPIRED -> "invalid_token";
            case ROLE_INVALID -> "insufficient_scope";
            default -> null;
        };
    }
}
