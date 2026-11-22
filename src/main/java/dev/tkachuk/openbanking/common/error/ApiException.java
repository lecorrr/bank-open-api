package dev.tkachuk.openbanking.common.error;

import java.util.Objects;
import java.util.Optional;

/** A request failed for a reason the contract has a code for. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final boolean forProvider;

    /**
     * A problem with the provider's request. The detail tells the provider what to fix, so it names fields and
     * rules but never repeats a value the provider sent.
     */
    public ApiException(ErrorCode code, String detail) {
        super(detail);
        this.code = Objects.requireNonNull(code, "code");
        this.forProvider = true;
    }

    /** A problem inside the bank. The message may name internals, so the provider only gets the code's text. */
    public ApiException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.forProvider = false;
    }

    public ErrorCode code() {
        return code;
    }

    /** The detail for the provider, if this exception carries one. */
    public Optional<String> providerDetail() {
        return forProvider ? Optional.ofNullable(getMessage()) : Optional.empty();
    }
}
