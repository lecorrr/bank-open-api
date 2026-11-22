package dev.tkachuk.openbanking.common.error;

import java.util.Objects;

/** A request failed for a reason the contract has a code for. The message is for the log, not for the provider. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ErrorCode code() {
        return code;
    }
}
