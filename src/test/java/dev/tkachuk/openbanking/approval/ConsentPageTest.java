package dev.tkachuk.openbanking.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.openbanking.consents.CustomerConsent;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConsentPageTest {

    private static final AccountOnPage HELD = new AccountOnPage("UA843999990000000000000000001", "UAH", true, true,
            true);
    private static final AccountOnPage NOT_HELD = new AccountOnPage("UA573999990000000000000000002", null, true,
            false, false);

    @Test
    void shouldBeApprovableWhenWaitingAndEveryAccountIsTheCustomers() {
        assertThat(new ConsentPage(consent("received", true), List.of(HELD)).approvable()).isTrue();
    }

    @Test
    void shouldNotBeApprovableWithAnAccountOfSomeoneElse() {
        assertThat(new ConsentPage(consent("received", true), List.of(HELD, NOT_HELD)).approvable()).isFalse();
    }

    @Test
    void shouldNotBeApprovableOnceDecided() {
        assertThat(new ConsentPage(consent("valid", false), List.of(HELD)).approvable()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "received, waiting for your decision",
            "valid, approved",
            "rejected, rejected",
            "revokedByPsu, withdrawn",
            "terminatedByTpp, ended by the provider",
            "expired, expired"})
    void shouldNameWhatBecameOfTheConsentInPlainWords(String status, String outcome) {
        assertThat(new ConsentPage(consent(status, false), List.of()).outcome()).isEqualTo(outcome);
    }

    @Test
    void shouldFailLoudlyOnStatusItDoesNotKnow() {
        ConsentPage page = new ConsentPage(consent("partiallyAuthorised", false), List.of());

        assertThatThrownBy(page::outcome).isInstanceOf(IllegalStateException.class);
    }

    private static CustomerConsent consent(String status, boolean awaitingDecision) {
        return new CustomerConsent(UUID.fromString("3f2a6c1e-8b4d-4e7f-9a12-6c5d4b3a2f10"), "provider-a", status,
                awaitingDecision, List.of(), true, LocalDate.of(2027, 5, 21), 4, null);
    }
}
