package dev.tkachuk.openbanking.bankcore;

import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;

/** bank-core did not answer, or answered with an error. The provider gets 503 and may retry. */
public class BankCoreUnavailableException extends ApiException {

    public BankCoreUnavailableException(String message, Throwable cause) {
        super(ErrorCode.SERVICE_UNAVAILABLE, message, cause);
    }
}
