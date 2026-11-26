package dev.tkachuk.provider.bank;

import org.jspecify.annotations.Nullable;
import org.springframework.web.client.RestClientException;

/** The bank refused a call, answered in a way the provider cannot use, or did not answer. */
public class BankApiException extends RuntimeException {

    private final int status;
    private final @Nullable String code;
    private final @Nullable String detail;

    /**
     * @param status the HTTP status of the bank's answer; 0 when there was none
     * @param code the bank's error code, such as CONSENT_INVALID, when it gave one
     * @param detail the bank's explanation, when it gave one
     */
    public BankApiException(String message, int status, @Nullable String code, @Nullable String detail) {
        super(message);
        this.status = status;
        this.code = code;
        this.detail = detail;
    }

    private BankApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.code = null;
        this.detail = null;
    }

    static BankApiException unreachable(String what, RestClientException cause) {
        return new BankApiException(what + " could not be reached (" + cause.getClass().getSimpleName() + ")", cause);
    }

    public int status() {
        return status;
    }

    public @Nullable String code() {
        return code;
    }

    public @Nullable String detail() {
        return detail;
    }
}
