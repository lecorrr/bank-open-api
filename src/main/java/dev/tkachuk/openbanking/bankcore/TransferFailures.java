package dev.tkachuk.openbanking.bankcore;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What bank-core's error answers to a transfer mean. A 400, 404 or 422 with bank-core's code refuses the transfer's
 * content: nothing moved, and the same transfer would be refused again. A reused Idempotency-Key does not: bank-core
 * keeps a different transfer under the key, which only a person can sort out. Anything else (409 CONFLICT, a 5xx,
 * an answer from something in between) leaves the outcome unknown, and the same call may be made again.
 */
final class TransferFailures {

    private static final Set<Integer> REFUSALS = Set.of(400, 404, 422);
    private static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

    private TransferFailures() {
    }

    /** The exception for bank-core's answer: its HTTP status and the code of its problem details, if it sent one. */
    static RuntimeException of(int status, @Nullable String code, Throwable cause) {
        if (IDEMPOTENCY_KEY_REUSED.equals(code)) {
            return new IllegalStateException("bank-core keeps a different transfer under this Idempotency-Key", cause);
        }
        if (code != null && REFUSALS.contains(status)) {
            return new TransferRefusedException(code);
        }
        return new BankCoreUnavailableException("bank-core answered " + status + (code == null ? "" : " " + code),
                cause);
    }
}
