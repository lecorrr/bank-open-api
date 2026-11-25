package dev.tkachuk.openbanking.bankcore;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TransferFailuresTest {

    private static final RuntimeException CAUSE = new RuntimeException("bank-core's answer");

    // Refused for its content: nothing moved, and the same transfer would be refused again.
    @ParameterizedTest
    @CsvSource({"422, INSUFFICIENT_FUNDS", "422, CURRENCY_MISMATCH", "404, ACCOUNT_NOT_FOUND",
            "400, VALIDATION_FAILED"})
    void shouldTakeRefusalWithBankCoresCodeAsRefused(int status, String code) {
        RuntimeException failure = TransferFailures.of(status, code, CAUSE);

        assertThat(failure).isInstanceOf(TransferRefusedException.class);
        assertThat(((TransferRefusedException) failure).code()).isEqualTo(code);
    }

    @Test
    void shouldTellInsufficientFundsFromOtherRefusals() {
        assertThat(new TransferRefusedException("INSUFFICIENT_FUNDS").insufficientFunds()).isTrue();
        assertThat(new TransferRefusedException("ACCOUNT_NOT_FOUND").insufficientFunds()).isFalse();
    }

    // The outcome is unknown: the same call, with the same key, may be made again.
    @ParameterizedTest
    @CsvSource({"409, CONFLICT", "500, INTERNAL_ERROR", "503, ", "502, ", "404, ", "422, "})
    void shouldTakeAnyOtherAnswerAsUnavailable(int status, @Nullable String code) {
        RuntimeException failure = TransferFailures.of(status, code, CAUSE);

        assertThat(failure).isInstanceOf(BankCoreUnavailableException.class);
        assertThat(failure.getMessage()).isEqualTo("bank-core answered " + status + (code == null ? "" : " " + code));
        assertThat(failure.getCause()).isSameAs(CAUSE);
    }

    // bank-core keeps a different transfer under the key: no retry fixes that, and it is no refusal either.
    @Test
    void shouldTakeReusedKeyAsBug() {
        RuntimeException failure = TransferFailures.of(422, "IDEMPOTENCY_KEY_REUSED", CAUSE);

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(failure.getCause()).isSameAs(CAUSE);
    }
}
