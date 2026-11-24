package dev.tkachuk.openbanking.bankcore;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class BankCoreAccountTest {

    private static final BankCoreAccount ACCOUNT =
            new BankCoreAccount("UA843999990000000000000000001", "UAH", BigDecimal.TEN, "C1001");

    // A consent covers an account while the customer who approved it holds it, in the currency it names if any.
    // An empty value is null.
    @ParameterizedTest(name = "customer {0}, currency {1}: {2}")
    @CsvSource({
            "C1001, , true",
            "C1001, UAH, true",
            "C1001, USD, false",
            "C1002, , false",
            ", , false"})
    void shouldBeHeldOnlyByItsCustomerInItsCurrency(@Nullable String customer, @Nullable String currency,
            boolean held) {
        assertThat(ACCOUNT.heldBy(customer, currency)).isEqualTo(held);
    }

    @ParameterizedTest(name = "customer {0}")
    @NullSource
    @ValueSource(strings = "C1001")
    void shouldBeHeldByNobodyWhenBankCoreHasNoCustomerForIt(@Nullable String customer) {
        BankCoreAccount banksOwn = new BankCoreAccount("UA303999990000000000000000003", "UAH", BigDecimal.ONE, null);

        assertThat(banksOwn.heldBy(customer)).isFalse();
    }
}
