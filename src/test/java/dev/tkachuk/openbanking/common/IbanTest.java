package dev.tkachuk.openbanking.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IbanTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "UA843999990000000000000000001",
            "UA213223130000026007233566001",
            "DE89370400440532013000",
            "GB29NWBK60161331926819",
            "NO9386011117947",
            "MT84MALT011000012345MTLCAST001S"})
    void shouldAcceptIbanWithRightCheckDigits(String iban) {
        assertThat(Iban.isValid(iban)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UA853999990000000000000000001",
            "UA843999990000000000000000002",
            // These two have right check digits but are shorter or longer than any IBAN.
            "NO938601111071",
            "UA743999990000000000000000000000001",
            "ua843999990000000000000000001",
            "UA84 3999 9900 0000 0000 0000 0000 1",
            ""})
    void shouldRefuseIbanWithWrongCheckDigitsOrForm(String iban) {
        assertThat(Iban.isValid(iban)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UA843999990000000000000000001", "UA213223130000026007233566001"})
    void shouldTakeIbanOfBankInUkraineAsUkrainian(String iban) {
        assertThat(Iban.isUkrainian(iban)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DE89370400440532013000",
            "UA84399999000000000000000001",
            "UA8439999900000000000000000011",
            "UA84399999000000000000000000A",
            "ua843999990000000000000000001"})
    void shouldNotTakeOtherIbanAsUkrainian(String iban) {
        assertThat(Iban.isUkrainian(iban)).isFalse();
    }
}
