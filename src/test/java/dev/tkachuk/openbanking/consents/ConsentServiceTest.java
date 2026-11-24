package dev.tkachuk.openbanking.consents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsentServiceTest {

    private static final UUID CONSENT_ID = UUID.fromString("3f2a6c1e-8b4d-4e7f-9a12-6c5d4b3a2f10");
    private static final UUID RESOURCE_ID = UUID.fromString("0f1b8a4e-3c55-4d6a-9a43-1c2e5b7d9f01");
    private static final String IBAN = "UA843999990000000000000000001";
    private static final String PROVIDER = "provider-a";
    private static final String OTHER_PROVIDER = "provider-b";
    private static final String CUSTOMER = "C1001";
    private static final String OTHER_CUSTOMER = "C1002";
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);
    private static final URI REDIRECT_URI = URI.create("https://tpp.example/consents/done");

    @Mock
    private ConsentRepository repository;

    private ConsentService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneId.of("Europe/Kyiv"));
        service = new ConsentService(repository, new ConsentProperties(180), clock);
    }

    @Test
    void shouldStoreReceivedConsent() {
        Consent consent = service.create(new NewConsent(
                List.of(new NewConsent.RequestedAccount(IBAN, "UAH", Set.of(AccessRight.BALANCES))),
                true, TODAY.plusDays(90), 4, null), PROVIDER);

        verify(repository).insert(consent);
        assertThat(consent.belongsTo(PROVIDER)).isTrue();
        assertThat(consent.statusOn(TODAY)).isEqualTo(ConsentStatus.RECEIVED);
        assertThat(consent.createdAt()).isEqualTo(NOW);
    }

    @Test
    void shouldStoreNothingWhenRequestBreaksARule() {
        NewConsent noAccounts = new NewConsent(List.of(), true, TODAY.plusDays(90), 4, null);

        assertCode(() -> service.create(noAccounts, PROVIDER), ErrorCode.FORMAT_ERROR);
        verifyNoInteractions(repository);
    }

    // The bank hands out consent ids as lower-case UUIDs, so anything else cannot name a consent.
    @ParameterizedTest
    @ValueSource(strings = {"not-a-consent", "3F2A6C1E-8B4D-4E7F-9A12-6C5D4B3A2F10", ""})
    void shouldAnswerMalformedConsentIdAsUnknown(String consentId) {
        assertCode(() -> service.get(consentId, PROVIDER), ErrorCode.CONSENT_UNKNOWN);
        assertCode(() -> service.accountsToList(consentId, PROVIDER, true), ErrorCode.CONSENT_ID_UNKNOWN);
        assertCode(() -> service.forCustomer(consentId, CUSTOMER), ErrorCode.CONSENT_UNKNOWN);
        assertCode(() -> service.approve(consentId, CUSTOMER), ErrorCode.CONSENT_UNKNOWN);
        verifyNoInteractions(repository);
    }

    @Test
    void shouldAnswerConsentInPathThatDoesNotExistWith403() {
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.empty());

        assertCode(() -> service.get(CONSENT_ID.toString(), PROVIDER), ErrorCode.CONSENT_UNKNOWN);
    }

    @Test
    void shouldAnswerConsentIdHeaderThatDoesNotExistWith400() {
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.empty());

        assertCode(() -> service.accountsToList(CONSENT_ID.toString(), PROVIDER, false),
                ErrorCode.CONSENT_ID_UNKNOWN);
    }

    // Another provider's consent answers exactly as one that does not exist, in a path and in Consent-ID.
    @Test
    void shouldAnswerAnotherProvidersConsentAsUnknown() {
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.of(consent(ConsentStatus.VALID, 4)));

        assertCode(() -> service.get(CONSENT_ID.toString(), OTHER_PROVIDER), ErrorCode.CONSENT_UNKNOWN);
        assertCode(() -> service.terminate(CONSENT_ID.toString(), OTHER_PROVIDER), ErrorCode.CONSENT_UNKNOWN);
        assertCode(() -> service.accountsToList(CONSENT_ID.toString(), OTHER_PROVIDER, true),
                ErrorCode.CONSENT_ID_UNKNOWN);
        assertCode(() -> service.accountToRead(CONSENT_ID.toString(), OTHER_PROVIDER, RESOURCE_ID.toString(),
                AccessRight.BALANCES, true), ErrorCode.CONSENT_ID_UNKNOWN);
        verify(repository, never()).updateStatus(any(), any());
        verify(repository, never()).countRead(any(), anyString(), any(), anyInt());
    }

    @Test
    void shouldCountListingWithoutTheCustomer() {
        Consent consent = consent(ConsentStatus.VALID, 2);
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.of(consent));
        when(repository.countRead(CONSENT_ID, "accounts", TODAY, 2)).thenReturn(true);

        assertThat(service.accountsToList(CONSENT_ID.toString(), PROVIDER, false))
                .isEqualTo(new ReadableAccounts(CUSTOMER, consent.accounts()));
    }

    @Test
    void shouldCountEachAccountAndRightOnItsOwn() {
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.of(consent(ConsentStatus.VALID, 4)));
        when(repository.countRead(CONSENT_ID, "balances:" + RESOURCE_ID, TODAY, 4)).thenReturn(true);

        ReadableAccount account = service.accountToRead(CONSENT_ID.toString(), PROVIDER, RESOURCE_ID.toString(),
                AccessRight.BALANCES, false);

        assertThat(account.account().iban()).isEqualTo(IBAN);
        assertThat(account.customerId()).isEqualTo(CUSTOMER);
    }

    @Test
    void shouldRefuseReadOverTheDailyLimit() {
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.of(consent(ConsentStatus.VALID, 4)));
        when(repository.countRead(CONSENT_ID, "accounts", TODAY, 4)).thenReturn(false);

        assertCode(() -> service.accountsToList(CONSENT_ID.toString(), PROVIDER, false), ErrorCode.ACCESS_EXCEEDED);
    }

    @Test
    void shouldNotCountReadsTheCustomerStarts() {
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.of(consent(ConsentStatus.VALID, 1)));

        service.accountsToList(CONSENT_ID.toString(), PROVIDER, true);

        verify(repository, never()).countRead(any(), anyString(), any(), anyInt());
    }

    @Test
    void shouldNotCountRefusedReads() {
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.of(consent(ConsentStatus.RECEIVED, 4)));

        assertCode(() -> service.accountsToList(CONSENT_ID.toString(), PROVIDER, false), ErrorCode.CONSENT_INVALID);
        verify(repository, never()).countRead(any(), anyString(), any(), anyInt());
    }

    @Test
    void shouldTerminateConsent() {
        Consent consent = consent(ConsentStatus.VALID, 4);
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.of(consent));
        when(repository.updateStatus(consent, ConsentStatus.VALID)).thenReturn(true);

        service.terminate(CONSENT_ID.toString(), PROVIDER);

        assertThat(consent.storedStatus()).isEqualTo(ConsentStatus.TERMINATED_BY_TPP);
        assertThat(consent.statusChangedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldReportStatusChangedAtTheSameTime() {
        Consent consent = consent(ConsentStatus.RECEIVED, 4);
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.of(consent));
        when(repository.updateStatus(consent, ConsentStatus.RECEIVED)).thenReturn(false);

        assertCode(() -> service.terminate(CONSENT_ID.toString(), PROVIDER), ErrorCode.STATUS_INVALID);
    }

    @Test
    void shouldLeaveEndedConsentAsItIs() {
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.of(consent(ConsentStatus.REJECTED, 4)));

        service.terminate(CONSENT_ID.toString(), PROVIDER);

        verify(repository, never()).updateStatus(any(), any());
    }

    @Test
    void shouldShowConsentWaitingForDecisionToAnySignedInCustomer() {
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.of(consent(ConsentStatus.RECEIVED, 4)));

        CustomerConsent view = service.forCustomer(CONSENT_ID.toString(), OTHER_CUSTOMER);

        assertThat(view.awaitingDecision()).isTrue();
        assertThat(view.status()).isEqualTo("received");
        assertThat(view.providerId()).isEqualTo(PROVIDER);
        assertThat(view.accounts()).extracting(ConsentAccount::iban).containsExactly(IBAN);
        assertThat(view.tppRedirectUri()).isEqualTo(REDIRECT_URI);
    }

    @Test
    void shouldShowDecidedConsentInFullOnlyToTheCustomerWhoDecided() {
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.of(consent(ConsentStatus.VALID, 4)));

        CustomerConsent own = service.forCustomer(CONSENT_ID.toString(), CUSTOMER);
        CustomerConsent someoneElses = service.forCustomer(CONSENT_ID.toString(), OTHER_CUSTOMER);

        assertThat(own.providerId()).isEqualTo(PROVIDER);
        assertThat(own.accounts()).hasSize(1);
        assertThat(someoneElses.status()).isEqualTo("valid");
        assertThat(someoneElses.awaitingDecision()).isFalse();
        assertThat(someoneElses.providerId()).isNull();
        assertThat(someoneElses.accounts()).isEmpty();
        assertThat(someoneElses.tppRedirectUri()).isNull();
    }

    @Test
    void shouldStoreTheApprovingCustomerWithTheNewStatus() {
        Consent consent = consent(ConsentStatus.RECEIVED, 4);
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.of(consent));
        when(repository.updateStatus(consent, ConsentStatus.RECEIVED)).thenReturn(true);

        CustomerConsent view = service.approve(CONSENT_ID.toString(), CUSTOMER);

        assertThat(view.status()).isEqualTo("valid");
        assertThat(view.awaitingDecision()).isFalse();
        assertThat(consent.customerId()).isEqualTo(CUSTOMER);
        assertThat(consent.statusChangedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldStoreTheRejectingCustomerWithTheNewStatus() {
        Consent consent = consent(ConsentStatus.RECEIVED, 4);
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.of(consent));
        when(repository.updateStatus(consent, ConsentStatus.RECEIVED)).thenReturn(true);

        CustomerConsent view = service.reject(CONSENT_ID.toString(), CUSTOMER);

        assertThat(view.status()).isEqualTo("rejected");
        assertThat(consent.customerId()).isEqualTo(CUSTOMER);
    }

    // Two tabs, or the customer and the provider's DELETE at the same moment: the second change is refused.
    @Test
    void shouldReportDecisionMadeAtTheSameTime() {
        Consent consent = consent(ConsentStatus.RECEIVED, 4);
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.of(consent));
        when(repository.updateStatus(consent, ConsentStatus.RECEIVED)).thenReturn(false);

        assertCode(() -> service.approve(CONSENT_ID.toString(), CUSTOMER), ErrorCode.STATUS_INVALID);
    }

    @Test
    void shouldRefuseDecisionOnConsentNoLongerWaiting() {
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.of(consent(ConsentStatus.VALID, 4)));

        assertCode(() -> service.approve(CONSENT_ID.toString(), CUSTOMER), ErrorCode.STATUS_INVALID);
        assertCode(() -> service.reject(CONSENT_ID.toString(), CUSTOMER), ErrorCode.STATUS_INVALID);
        verify(repository, never()).updateStatus(any(), any());
    }

    @Test
    void shouldListValidConsentsOfTheCustomer() {
        when(repository.findValidOf(CUSTOMER, TODAY)).thenReturn(List.of(consent(ConsentStatus.VALID, 4)));

        List<CustomerConsent> consents = service.validConsentsOf(CUSTOMER);

        assertThat(consents).extracting(CustomerConsent::id).containsExactly(CONSENT_ID);
        assertThat(consents).extracting(CustomerConsent::status).containsExactly("valid");
    }

    @Test
    void shouldLetTheApprovingCustomerWithdrawConsent() {
        Consent consent = consent(ConsentStatus.VALID, 4);
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.of(consent));
        when(repository.updateStatus(consent, ConsentStatus.VALID)).thenReturn(true);

        service.revokeByCustomer(CONSENT_ID.toString(), CUSTOMER);

        assertThat(consent.storedStatus()).isEqualTo(ConsentStatus.REVOKED_BY_PSU);
    }

    // Another customer's consent answers exactly as one that does not exist.
    @Test
    void shouldAnswerAnotherCustomersConsentAsUnknownOnWithdrawal() {
        when(repository.findById(CONSENT_ID)).thenReturn(Optional.of(consent(ConsentStatus.VALID, 4)));

        assertCode(() -> service.revokeByCustomer(CONSENT_ID.toString(), OTHER_CUSTOMER), ErrorCode.CONSENT_UNKNOWN);
        verify(repository, never()).updateStatus(any(), any());
    }

    // A received consent has no customer yet; any other status here was decided by CUSTOMER.
    private static Consent consent(ConsentStatus status, int frequencyPerDay) {
        ConsentAccount account = new ConsentAccount(RESOURCE_ID, IBAN, "UAH",
                Set.of(AccessRight.ACCOUNT_DETAILS, AccessRight.BALANCES));
        Instant created = NOW.minusSeconds(3600);
        String customer = status == ConsentStatus.RECEIVED ? null : CUSTOMER;
        return new Consent(CONSENT_ID, PROVIDER, customer, status, List.of(account), true, TODAY.plusDays(30),
                frequencyPerDay, REDIRECT_URI, created, created);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
