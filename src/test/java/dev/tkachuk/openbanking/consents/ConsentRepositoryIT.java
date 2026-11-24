package dev.tkachuk.openbanking.consents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.openbanking.PostgresTestcontainer;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@JdbcTest
@Import({PostgresTestcontainer.class, ConsentRepository.class})
class ConsentRepositoryIT {

    private static final UUID CONSENT_ID = UUID.fromString("3f2a6c1e-8b4d-4e7f-9a12-6c5d4b3a2f10");
    private static final UUID RESOURCE_A = UUID.fromString("0f1b8a4e-3c55-4d6a-9a43-1c2e5b7d9f01");
    private static final UUID RESOURCE_B = UUID.fromString("5a7c9e1f-2b4d-4f68-8a0c-3e5f7a9b1d02");
    private static final String IBAN_A = "UA843999990000000000000000001";
    private static final String IBAN_B = "UA033999990000000000000000004";
    private static final String PROVIDER = "provider-a";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);
    // timestamptz keeps microseconds, which is why the application's clock ticks in microseconds.
    private static final Instant CREATED = Instant.parse("2026-11-22T08:00:00.123456Z");
    private static final Instant LATER = Instant.parse("2026-11-22T09:30:00.654321Z");

    @Autowired
    private ConsentRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void shouldReadBackStoredConsentWithAccountsInOrder() {
        // B before A, so the order cannot come from the ids.
        Set<AccessRight> both = Set.of(AccessRight.ACCOUNT_DETAILS, AccessRight.BALANCES);
        List<ConsentAccount> accounts = List.of(
                new ConsentAccount(RESOURCE_B, IBAN_B, "USD", Set.of(AccessRight.BALANCES)),
                new ConsentAccount(RESOURCE_A, IBAN_A, null, both));
        Consent consent = new Consent(CONSENT_ID, PROVIDER, ConsentStatus.RECEIVED, accounts, true,
                TODAY.plusDays(90), 3, URI.create("https://tpp.example/consents/done"), CREATED, CREATED);

        repository.insert(consent);

        Consent read = repository.findById(CONSENT_ID).orElseThrow();
        assertThat(read.providerId()).isEqualTo(PROVIDER);
        assertThat(read.storedStatus()).isEqualTo(ConsentStatus.RECEIVED);
        assertThat(read.accounts()).containsExactlyElementsOf(accounts);
        assertThat(read.recurring()).isTrue();
        assertThat(read.validTo()).isEqualTo(TODAY.plusDays(90));
        assertThat(read.frequencyPerDay()).isEqualTo(3);
        assertThat(read.tppRedirectUri()).isEqualTo(URI.create("https://tpp.example/consents/done"));
        assertThat(read.createdAt()).isEqualTo(CREATED);
        assertThat(read.statusChangedAt()).isEqualTo(CREATED);
    }

    @Test
    void shouldReadBackConsentWithoutRedirectUri() {
        repository.insert(consent(ConsentStatus.RECEIVED));

        assertThat(repository.findById(CONSENT_ID).orElseThrow().tppRedirectUri()).isNull();
    }

    // A consent from before providers signed in has no provider; V2 left the column empty for those.
    @Test
    void shouldReadBackConsentWithoutProvider() {
        repository.insert(new Consent(CONSENT_ID, null, ConsentStatus.VALID, List.of(), true, TODAY.plusDays(30), 4,
                null, CREATED, CREATED));

        assertThat(repository.findById(CONSENT_ID).orElseThrow().providerId()).isNull();
    }

    @Test
    void shouldFindNoConsentForUnknownId() {
        assertThat(repository.findById(CONSENT_ID)).isEmpty();
    }

    @Test
    void shouldSaveStatusChange() {
        Consent consent = consent(ConsentStatus.RECEIVED);
        repository.insert(consent);
        consent.approve(TODAY, LATER);

        assertThat(repository.updateStatus(consent, ConsentStatus.RECEIVED)).isTrue();

        Consent read = repository.findById(CONSENT_ID).orElseThrow();
        assertThat(read.storedStatus()).isEqualTo(ConsentStatus.VALID);
        assertThat(read.statusChangedAt()).isEqualTo(LATER);
    }

    @Test
    void shouldNotOverwriteStatusChangedMeanwhile() {
        repository.insert(consent(ConsentStatus.RECEIVED));
        Consent first = repository.findById(CONSENT_ID).orElseThrow();
        Consent second = repository.findById(CONSENT_ID).orElseThrow();
        first.approve(TODAY, LATER);
        second.terminateByTpp(TODAY, LATER);

        assertThat(repository.updateStatus(first, ConsentStatus.RECEIVED)).isTrue();
        assertThat(repository.updateStatus(second, ConsentStatus.RECEIVED)).isFalse();

        assertThat(repository.findById(CONSENT_ID).orElseThrow().storedStatus()).isEqualTo(ConsentStatus.VALID);
    }

    @Test
    void shouldCountReadsUpToTheDailyLimit() {
        repository.insert(consent(ConsentStatus.VALID));

        assertThat(repository.countRead(CONSENT_ID, "accounts", TODAY, 2)).isTrue();
        assertThat(repository.countRead(CONSENT_ID, "accounts", TODAY, 2)).isTrue();
        assertThat(repository.countRead(CONSENT_ID, "accounts", TODAY, 2)).isFalse();
        // Another day and another data read have counts of their own.
        assertThat(repository.countRead(CONSENT_ID, "accounts", TODAY.plusDays(1), 2)).isTrue();
        assertThat(repository.countRead(CONSENT_ID, "balances:" + RESOURCE_A, TODAY, 2)).isTrue();
    }

    @Test
    void shouldKeepExpiredOutOfTheStoredStatuses() {
        repository.insert(consent(ConsentStatus.VALID));

        assertThatThrownBy(() -> jdbc.sql("UPDATE consent SET status = 'expired' WHERE id = :id")
                .param("id", CONSENT_ID).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static Consent consent(ConsentStatus status) {
        ConsentAccount account = new ConsentAccount(RESOURCE_A, IBAN_A, "UAH", Set.of(AccessRight.BALANCES));
        return new Consent(CONSENT_ID, PROVIDER, status, List.of(account), true, TODAY.plusDays(30), 4, null, CREATED,
                CREATED);
    }
}
