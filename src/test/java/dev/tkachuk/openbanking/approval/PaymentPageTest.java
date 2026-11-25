package dev.tkachuk.openbanking.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.openbanking.payments.CustomerPayment;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentPageTest {

    @Test
    void shouldBeApprovableWhenWaitingAndFromTheCustomersAccount() {
        assertThat(page("RCVD", null, true, true).approvable()).isTrue();
    }

    @Test
    void shouldNotBeApprovableFromAnAccountOfSomeoneElse() {
        assertThat(page("RCVD", null, true, false).approvable()).isFalse();
    }

    @Test
    void shouldNotBeApprovableOnceDecided() {
        assertThat(page("ACTC", null, false, true).approvable()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "RCVD, , waiting for your decision",
            "CANC, , cancelled by the provider",
            "RJCT, , rejected",
            "RJCT, AM04, refused by the bank",
            "ACTC, , 'approved, and the bank is making it'",
            "ACCC, , paid",
            "ACSP, , 'sent to the payee''s bank'",
            "ACSC, , 'accepted by the payee''s bank'"})
    void shouldNameWhatBecameOfThePaymentInPlainWords(String status, @Nullable String reasonCode, String outcome) {
        assertThat(page(status, reasonCode, false, false).outcome()).isEqualTo(outcome);
    }

    @ParameterizedTest
    @CsvSource({
            "RJCT, , Payment rejected, The payment will not be made",
            "RJCT, AM04, Payment refused, does not have enough money",
            "RJCT, DS04, Payment refused, could not make this payment",
            "ACTC, , Payment approved, The bank is making the payment",
            "ACSP, , Payment sent, 'on its way to the payee''s bank'",
            "ACCC, , Payment made, 'on the payee''s account'",
            "ACSC, , Payment made, 'The payee''s bank has accepted'"})
    void shouldTellTheCustomerWhatTheirDecisionDid(String status, @Nullable String reasonCode, String title,
            String text) {
        PaymentPage page = page(status, reasonCode, false, false);

        assertThat(page.decidedTitle()).isEqualTo(title);
        assertThat(page.decidedText()).contains(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RCVD", "CANC"})
    void shouldFailLoudlyForStatusNoDecisionLeaves(String status) {
        PaymentPage page = page(status, null, false, false);

        assertThatThrownBy(page::decidedTitle).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(page::decidedText).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldFailLoudlyOnStatusItDoesNotKnow() {
        PaymentPage page = page("PATC", null, false, false);

        assertThatThrownBy(page::outcome).isInstanceOf(IllegalStateException.class);
    }

    private static PaymentPage page(String status, @Nullable String reasonCode, boolean awaitingDecision,
            boolean debtorAccountHeld) {
        return new PaymentPage(new CustomerPayment(UUID.fromString("5b7e2c9a-1d4f-4a6b-8c3e-9f0a1b2c3d4e"),
                "provider-a", status, reasonCode, awaitingDecision, debtorAccountHeld, null, null));
    }
}
