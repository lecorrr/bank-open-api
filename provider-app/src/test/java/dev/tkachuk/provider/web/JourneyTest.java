package dev.tkachuk.provider.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class JourneyTest {

    private static final String IBAN = "UA843999990000000000000000001";

    @Test
    void shouldKeepOneJourneyPerSession() {
        MockHttpSession session = new MockHttpSession();

        assertThat(Journey.of(session)).isSameAs(Journey.of(session));
        assertThat(Journey.of(new MockHttpSession())).isNotSameAs(Journey.of(session));
    }

    @Test
    void shouldTakeAReturnFromTheBankOnlyWithTheTripsState() {
        Journey journey = new Journey();
        String state = Journey.newState();
        journey.consentStarted(IBAN, "consent-1", state);

        assertThat(journey.returnsFromConsent(state)).isTrue();
        assertThat(journey.returnsFromConsent(Journey.newState())).isFalse();
        assertThat(journey.returnsFromConsent(null)).isFalse();
        assertThat(journey.returnsFromConsent("")).isFalse();
        assertThat(journey.returnsFromPayment(state)).isFalse();
    }

    @Test
    void shouldTakeAReturnOnlyOnce() {
        Journey journey = new Journey();
        String state = Journey.newState();
        journey.consentStarted(IBAN, "consent-1", state);
        journey.consentDecided(true);

        assertThat(journey.hasValidConsent()).isTrue();
        assertThat(journey.returnsFromConsent(state)).isFalse();
    }

    @Test
    void shouldForgetAConsentTheCustomerDidNotApprove() {
        Journey journey = new Journey();
        journey.consentStarted(IBAN, "consent-1", Journey.newState());
        journey.consentDecided(false);

        assertThat(journey.hasValidConsent()).isFalse();
        assertThat(journey.consentId()).isNull();
        assertThat(journey.iban()).isEqualTo(IBAN);
    }

    @Test
    void shouldForgetAnEndedConsent() {
        Journey journey = new Journey();
        journey.consentStarted(IBAN, "consent-1", Journey.newState());
        journey.consentDecided(true);
        journey.consentEnded();

        assertThat(journey.hasValidConsent()).isFalse();
        assertThat(journey.consentId()).isNull();
    }

    @Test
    void shouldTakeAPaymentsReturnWithItsOwnState() {
        Journey journey = new Journey();
        String state = Journey.newState();
        journey.paymentStarted("payment-1", state);

        assertThat(journey.returnsFromPayment(state)).isTrue();
        journey.paymentDecided();
        assertThat(journey.returnsFromPayment(state)).isFalse();
        assertThat(journey.paymentId()).isEqualTo("payment-1");
    }

    @Test
    void shouldMakeStatesOf128RandomBits() {
        String state = Journey.newState();

        assertThat(state).matches("[0-9a-f]{32}").isNotEqualTo(Journey.newState());
    }
}
