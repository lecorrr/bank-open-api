package dev.tkachuk.openbanking.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentPropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {"PT30S", "PT1H"})
    void shouldTakeRetryDelayOfHalfAMinuteOrMore(String delay) {
        assertThat(new PaymentProperties(Duration.parse(delay)).retryDelay()).isEqualTo(Duration.parse(delay));
    }

    // An attempt holds its payment for the delay, so a shorter one could let two attempts cross.
    @ParameterizedTest
    @ValueSource(strings = {"PT29S", "PT0S", "PT-30S"})
    void shouldRefuseRetryDelayShorterThanACallToBankCoreMayTake(String delay) {
        assertThatThrownBy(() -> new PaymentProperties(Duration.parse(delay)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bank-open-api.payments.retry-delay");
    }

    @Test
    void shouldRefuseMissingRetryDelay() {
        assertThatThrownBy(() -> new PaymentProperties(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bank-open-api.payments.retry-delay");
    }
}
