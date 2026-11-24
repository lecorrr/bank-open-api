package dev.tkachuk.openbanking.security;

import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * A rule a provider's token breaks, with the contract's code for it. To OAuth it is "invalid_token"; the
 * description is written for the provider and never repeats what the token holds.
 */
final class TokenRefusal extends OAuth2Error {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    TokenRefusal(ErrorCode code, String description) {
        super("invalid_token", Objects.requireNonNull(description, "description"), null);
        this.code = Objects.requireNonNull(code, "code");
    }

    ErrorCode code() {
        return code;
    }

    /** The refusal as the provider gets it. */
    ApiException toApiException() {
        return new ApiException(code, Objects.requireNonNull(getDescription(), "description"));
    }
}
