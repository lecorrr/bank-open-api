package dev.tkachuk.openbanking.consents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConsentTest {

    private static final UUID CONSENT_ID = UUID.fromString("3f2a6c1e-8b4d-4e7f-9a12-6c5d4b3a2f10");
    private static final String PROVIDER = "provider-a";
    private static final String CUSTOMER = "C1001";
    private static final UUID RESOURCE_A = UUID.fromString("0f1b8a4e-3c55-4d6a-9a43-1c2e5b7d9f01");
    private static final UUID RESOURCE_B = UUID.fromString("5a7c9e1f-2b4d-4f68-8a0c-3e5f7a9b1d02");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);
    private static final Instant NOW = Instant.parse("2026-11-22T08:00:00Z");
    private static final Instant LATER = Instant.parse("2026-11-22T09:30:00Z");
    private static final int MAX_VALIDITY_DAYS = 180;
    private static final String IBAN_A = "UA843999990000000000000000001";
    private static final String IBAN_B = "UA033999990000000000000000004";
    private static final Set<AccessRight> BOTH = Set.of(AccessRight.ACCOUNT_DETAILS, AccessRight.BALANCES);
    private static final Set<AccessRight> BALANCES = Set.of(AccessRight.BALANCES);

    @Test
    void shouldCreateReceivedConsentWithResourceIdForEachAccount() {
        Consent consent = create(request(List.of(account(IBAN_A, "UAH", BOTH), account(IBAN_B, null, BALANCES))));

        assertThat(consent.statusOn(TODAY)).isEqualTo(ConsentStatus.RECEIVED);
        assertThat(consent.accounts()).containsExactly(
                new ConsentAccount(RESOURCE_A, IBAN_A, "UAH", BOTH),
                new ConsentAccount(RESOURCE_B, IBAN_B, null, BALANCES));
        assertThat(consent.createdAt()).isEqualTo(NOW);
    }

    @Test
    void shouldStoreIbanInCapitals() {
        Consent consent = create(request(List.of(account("GB29nwbk60161331926819", null, BOTH))));

        assertThat(consent.accounts().get(0).iban()).isEqualTo("GB29NWBK60161331926819");
    }

    @Test
    void shouldCutValidToBackToLastDayTheBankAllows() {
        Consent consent = create(request(true, LocalDate.of(9999, 12, 31), 4));

        assertThat(consent.validTo()).isEqualTo(TODAY.plusDays(MAX_VALIDITY_DAYS));
    }

    @ParameterizedTest
    @MethodSource("validToTheBankAllows")
    void shouldKeepValidToTheBankAllows(LocalDate validTo) {
        Consent consent = create(request(true, validTo, 4));

        assertThat(consent.validTo()).isEqualTo(validTo);
    }

    static Stream<Arguments> validToTheBankAllows() {
        return Stream.of(Arguments.of(TODAY), Arguments.of(TODAY.plusDays(MAX_VALIDITY_DAYS)));
    }

    @ParameterizedTest
    @MethodSource("requestsBreakingARule")
    void shouldRefuseRequestBreakingARule(NewConsent request, String field) {
        assertThatThrownBy(() -> create(request))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORMAT_ERROR))
                .hasMessageStartingWith(field);
    }

    static Stream<Arguments> requestsBreakingARule() {
        return Stream.of(
                Arguments.of(request(List.of()), "access.payments"),
                Arguments.of(request(true, TODAY.plusDays(1), 0), "frequencyPerDay"),
                Arguments.of(request(true, TODAY.plusDays(1), 5), "frequencyPerDay"),
                Arguments.of(request(false, TODAY.plusDays(1), 2), "frequencyPerDay"),
                Arguments.of(request(true, TODAY.minusDays(1), 4), "validTo"),
                Arguments.of(request(List.of(account("UA853999990000000000000000001", null, BOTH))),
                        "access.payments[0].account.iban"),
                // The same account in small letters is still the same account.
                Arguments.of(request(List.of(account(IBAN_A, null, BOTH),
                        account(IBAN_A.toLowerCase(Locale.ROOT), null, BOTH))), "access.payments[1]"),
                Arguments.of(request(List.of(account(IBAN_A, null, Set.of()))), "access.payments[0].rights"));
    }

    @Test
    void shouldExpireTheDayAfterValidTo() {
        Consent consent = approved(TODAY.plusDays(1));

        assertThat(consent.statusOn(TODAY.plusDays(1))).isEqualTo(ConsentStatus.VALID);
        assertThat(consent.statusOn(TODAY.plusDays(2))).isEqualTo(ConsentStatus.EXPIRED);
    }

    @Test
    void shouldKeepFinalStatusAfterValidTo() {
        Consent consent = approved(TODAY.plusDays(1));
        consent.terminateByTpp(TODAY, LATER);

        assertThat(consent.statusOn(TODAY.plusDays(2))).isEqualTo(ConsentStatus.TERMINATED_BY_TPP);
    }

    @Test
    void shouldApproveReceivedConsent() {
        Consent consent = create(request(List.of(account(IBAN_A, null, BOTH))));

        consent.approve(CUSTOMER, TODAY, LATER);

        assertThat(consent.statusOn(TODAY)).isEqualTo(ConsentStatus.VALID);
        assertThat(consent.statusChangedAt()).isEqualTo(LATER);
        assertThat(consent.customerId()).isEqualTo(CUSTOMER);
        assertThat(consent.decidedBy(CUSTOMER)).isTrue();
        assertThat(consent.decidedBy("C1002")).isFalse();
    }

    @Test
    void shouldRejectReceivedConsent() {
        Consent consent = create(request(List.of(account(IBAN_A, null, BOTH))));

        consent.reject(CUSTOMER, TODAY, LATER);

        assertThat(consent.statusOn(TODAY)).isEqualTo(ConsentStatus.REJECTED);
        assertThat(consent.decidedBy(CUSTOMER)).isTrue();
    }

    @Test
    void shouldBelongToNoCustomerUntilOneDecides() {
        Consent consent = create(request(List.of(account(IBAN_A, null, BOTH))));

        assertThat(consent.customerId()).isNull();
        assertThat(consent.decidedBy(CUSTOMER)).isFalse();
    }

    @Test
    void shouldApproveOrRejectOnlyReceivedConsent() {
        Consent consent = approved(TODAY.plusDays(30));

        assertThatThrownBy(() -> consent.reject(CUSTOMER, TODAY, LATER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.STATUS_INVALID));
        assertThatThrownBy(() -> consent.approve(CUSTOMER, TODAY, LATER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.STATUS_INVALID));
        assertThat(consent.statusOn(TODAY)).isEqualTo(ConsentStatus.VALID);
    }

    @Test
    void shouldLetTheApprovingCustomerWithdrawConsent() {
        Consent consent = approved(TODAY.plusDays(30));

        consent.revokeByCustomer(CUSTOMER, TODAY, LATER);

        assertThat(consent.statusOn(TODAY)).isEqualTo(ConsentStatus.REVOKED_BY_PSU);
        assertThat(consent.statusChangedAt()).isEqualTo(LATER);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("withdrawalsTheBankRefuses")
    void shouldRefuseWithdrawalOfConsentNotValidOrNotTheCustomers(String reason, Consent consent, LocalDate day) {
        ConsentStatus before = consent.storedStatus();

        assertThatThrownBy(() -> consent.revokeByCustomer(CUSTOMER, day, LATER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.STATUS_INVALID));
        assertThat(consent.storedStatus()).isEqualTo(before);
    }

    static Stream<Arguments> withdrawalsTheBankRefuses() {
        Consent byOtherCustomer = create(request(List.of(account(IBAN_A, null, BOTH))));
        byOtherCustomer.approve("C1002", TODAY, NOW);
        Consent rejected = create(request(List.of(account(IBAN_A, null, BOTH))));
        rejected.reject(CUSTOMER, TODAY, NOW);
        return Stream.of(
                Arguments.of("approved by another customer", byOtherCustomer, TODAY),
                Arguments.of("still waiting for a decision", create(request(List.of(account(IBAN_A, null, BOTH)))),
                        TODAY),
                Arguments.of("rejected", rejected, TODAY),
                Arguments.of("expired", approved(TODAY.plusDays(1)), TODAY.plusDays(2)));
    }

    @Test
    void shouldTerminateConsentOnlyOnce() {
        Consent consent = approved(TODAY.plusDays(30));

        assertThat(consent.terminateByTpp(TODAY, LATER)).isTrue();
        assertThat(consent.terminateByTpp(TODAY, LATER.plusSeconds(60))).isFalse();
        assertThat(consent.statusOn(TODAY)).isEqualTo(ConsentStatus.TERMINATED_BY_TPP);
        assertThat(consent.statusChangedAt()).isEqualTo(LATER);
    }

    @Test
    void shouldLeaveExpiredConsentAsItIs() {
        Consent consent = approved(TODAY.plusDays(1));

        assertThat(consent.terminateByTpp(TODAY.plusDays(2), LATER)).isFalse();
        assertThat(consent.storedStatus()).isEqualTo(ConsentStatus.VALID);
    }

    @Test
    void shouldListAccountsOfValidConsent() {
        Consent consent = approved(TODAY.plusDays(30));

        assertThat(consent.accountsToList(TODAY)).extracting(ConsentAccount::resourceId).containsExactly(RESOURCE_A);
    }

    @Test
    void shouldAllowNoReadsBeforeTheCustomerApproves() {
        Consent consent = create(request(List.of(account(IBAN_A, null, BOTH))));

        assertThatThrownBy(() -> consent.accountsToList(TODAY))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CONSENT_INVALID))
                .hasMessage("The consent is received, not valid");
    }

    @Test
    void shouldAllowNoReadsAfterValidTo() {
        Consent consent = approved(TODAY.plusDays(1));

        assertThatThrownBy(() -> consent.accountToRead(RESOURCE_A.toString(), AccessRight.BALANCES, TODAY.plusDays(2)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CONSENT_EXPIRED));
    }

    @Test
    void shouldReadAccountWithTheRightForIt() {
        Consent consent = approved(TODAY.plusDays(30));

        ConsentAccount account = consent.accountToRead(RESOURCE_A.toString(), AccessRight.BALANCES, TODAY);

        assertThat(account.iban()).isEqualTo(IBAN_A);
    }

    @Test
    void shouldRefuseReadOfDataTheConsentDoesNotInclude() {
        Consent consent = create(request(List.of(account(IBAN_A, null, Set.of(AccessRight.ACCOUNT_DETAILS)))));
        consent.approve(CUSTOMER, TODAY, LATER);

        assertThatThrownBy(() -> consent.accountToRead(RESOURCE_A.toString(), AccessRight.BALANCES, TODAY))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CONSENT_INVALID))
                .hasMessage("The consent does not include balances for this account");
    }

    @Test
    void shouldRefuseReadOfAccountOutsideTheConsent() {
        Consent consent = approved(TODAY.plusDays(30));

        assertThatThrownBy(() -> consent.accountToRead(RESOURCE_B.toString(), AccessRight.BALANCES, TODAY))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.RESOURCE_UNKNOWN));
    }

    @Test
    void shouldRefuseToStoreExpiredAsStatus() {
        assertThatThrownBy(() -> new Consent(CONSENT_ID, PROVIDER, CUSTOMER, ConsentStatus.EXPIRED, List.of(), true,
                TODAY, 4, null, NOW, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldBelongOnlyToTheProviderThatAskedForIt() {
        Consent consent = create(request(List.of(account(IBAN_A, null, BOTH))));

        assertThat(consent.providerId()).isEqualTo(PROVIDER);
        assertThat(consent.belongsTo(PROVIDER)).isTrue();
        assertThat(consent.belongsTo("provider-b")).isFalse();
    }

    @Test
    void shouldBelongToNoProviderWhenMadeBeforeProvidersSignedIn() {
        Consent consent = new Consent(CONSENT_ID, null, CUSTOMER, ConsentStatus.VALID, List.of(), true, TODAY, 4, null,
                NOW, NOW);

        assertThat(consent.belongsTo(PROVIDER)).isFalse();
    }

    private static Consent approved(LocalDate validTo) {
        Consent consent = create(new NewConsent(List.of(account(IBAN_A, null, BOTH)), true, validTo, 4, null));
        consent.approve(CUSTOMER, TODAY, NOW);
        return consent;
    }

    private static Consent create(NewConsent request) {
        Deque<UUID> resourceIds = new ArrayDeque<>(List.of(RESOURCE_A, RESOURCE_B));
        return Consent.create(CONSENT_ID, PROVIDER, request, TODAY, MAX_VALIDITY_DAYS, resourceIds::pop, NOW);
    }

    private static NewConsent request(List<NewConsent.RequestedAccount> accounts) {
        return new NewConsent(accounts, true, TODAY.plusDays(90), 4, null);
    }

    private static NewConsent request(boolean recurring, LocalDate validTo, int frequencyPerDay) {
        return new NewConsent(List.of(account(IBAN_A, null, BOTH)), recurring, validTo, frequencyPerDay, null);
    }

    private static NewConsent.RequestedAccount account(String iban, @Nullable String currency,
            Set<AccessRight> rights) {
        return new NewConsent.RequestedAccount(iban, currency, rights);
    }
}
