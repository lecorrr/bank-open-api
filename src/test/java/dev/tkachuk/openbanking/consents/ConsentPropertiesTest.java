package dev.tkachuk.openbanking.consents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConsentPropertiesTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 180, 3650})
    void shouldAcceptMaxValidityWithinTenYears(int days) {
        assertThat(new ConsentProperties(days).maxValidityDays()).isEqualTo(days);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 3651})
    void shouldRefuseMaxValidityOutsideTenYears(int days) {
        assertThatThrownBy(() -> new ConsentProperties(days))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bank-open-api.consents.max-validity-days");
    }
}
