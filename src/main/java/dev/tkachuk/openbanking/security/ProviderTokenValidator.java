package dev.tkachuk.openbanking.security;

import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The rules a provider's access token must meet once its PS256 signature checks out, in the order they are
 * checked; the first one broken is the answer. That the request comes with the certificate the token is bound to
 * is checked later, by CertificateBindingFilter, which has the request.
 */
final class ProviderTokenValidator implements OAuth2TokenValidator<Jwt> {

    /** Keycloak and bank-open-api run on one machine, so their clocks hardly differ. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private final String issuer;
    private final String audience;
    private final Clock clock;

    ProviderTokenValidator(String issuer, String audience, Clock clock) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.audience = Objects.requireNonNull(audience, "audience");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        TokenRefusal refusal = firstBrokenRule(token);
        return refusal == null ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(refusal);
    }

    /** The SHA-256 thumbprint of the certificate the token is bound to (RFC 8705 cnf.x5t#S256), if it has one. */
    static @Nullable String certificateThumbprint(Jwt token) {
        try {
            Map<String, Object> confirmation = token.getClaimAsMap("cnf");
            Object thumbprint = confirmation == null ? null : confirmation.get("x5t#S256");
            return thumbprint instanceof String text && !text.isBlank() ? text : null;
        } catch (IllegalArgumentException notAnObject) {
            return null;
        }
    }

    private @Nullable TokenRefusal firstBrokenRule(Jwt token) {
        // RFC 9068: an access token says so in its type, so an ID token of the same issuer is not taken for one.
        if (!isAccessTokenType(token.getHeaders().get("typ"))) {
            return invalid("The token is not an access token");
        }
        if (!issuer.equals(token.getClaimAsString("iss"))) {
            return invalid("The access token comes from another issuer");
        }
        List<String> audiences = token.getAudience();
        if (audiences == null || !audiences.contains(audience)) {
            return invalid("The access token is not meant for this API");
        }
        Instant now = clock.instant();
        Instant expiresAt = token.getExpiresAt();
        if (expiresAt == null) {
            return invalid("The access token has no expiry");
        }
        if (!now.minus(CLOCK_SKEW).isBefore(expiresAt)) {
            return new TokenRefusal(ErrorCode.TOKEN_EXPIRED, ErrorCode.TOKEN_EXPIRED.text());
        }
        Instant notBefore = token.getNotBefore();
        if (notBefore != null && now.plus(CLOCK_SKEW).isBefore(notBefore)) {
            return invalid("The access token is not valid yet");
        }
        String provider = token.getClaimAsString("azp");
        if (provider == null || provider.isBlank()) {
            return invalid("The access token does not name the provider");
        }
        if (certificateThumbprint(token) == null) {
            return invalid("The access token is not bound to a certificate");
        }
        return null;
    }

    private static boolean isAccessTokenType(@Nullable Object type) {
        return type instanceof String text
                && ("at+jwt".equalsIgnoreCase(text) || "application/at+jwt".equalsIgnoreCase(text));
    }

    private static TokenRefusal invalid(String description) {
        return new TokenRefusal(ErrorCode.TOKEN_INVALID, description);
    }
}
