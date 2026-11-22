package dev.tkachuk.openbanking.consents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.openbanking.api.model.AccessRightsCodes;
import dev.tkachuk.openbanking.api.model.AccountAccess;
import dev.tkachuk.openbanking.api.model.AccountAccessConsent;
import dev.tkachuk.openbanking.api.model.AccountAccessRights;
import dev.tkachuk.openbanking.api.model.AccountReference;
import dev.tkachuk.openbanking.api.model.ConsentTypeCode;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConsentRequestsTest {

    private static final String IBAN_A = "UA843999990000000000000000001";
    private static final String IBAN_B = "UA033999990000000000000000004";
    private static final LocalDate VALID_TO = LocalDate.of(2027, 5, 21);
    private static final URI REDIRECT_URI = URI.create("https://tpp.example/consents/done");

    @Test
    void shouldMapDetailedConsentForCurrentAccounts() {
        AccountAccessConsent body = body(new AccountAccess().payments(List.of(
                access(new AccountReference().iban(IBAN_A).currency("UAH"),
                        AccessRightsCodes.ACCOUNT_DETAILS, AccessRightsCodes.BALANCES),
                access(new AccountReference().iban(IBAN_B), AccessRightsCodes.BALANCES))));

        NewConsent request = ConsentRequests.from(body, REDIRECT_URI);

        Set<AccessRight> both = Set.of(AccessRight.ACCOUNT_DETAILS, AccessRight.BALANCES);
        assertThat(request.accounts()).containsExactly(
                new NewConsent.RequestedAccount(IBAN_A, "UAH", both),
                new NewConsent.RequestedAccount(IBAN_B, null, Set.of(AccessRight.BALANCES)));
        assertThat(request.recurring()).isTrue();
        assertThat(request.validTo()).isEqualTo(VALID_TO);
        assertThat(request.frequencyPerDay()).isEqualTo(4);
        assertThat(request.tppRedirectUri()).isEqualTo(REDIRECT_URI);
    }

    // Consent rules, such as at least one account, are checked when the consent is created.
    @Test
    void shouldPassMissingPaymentsOnAsNoAccounts() {
        NewConsent request = ConsentRequests.from(body(new AccountAccess()), null);

        assertThat(request.accounts()).isEmpty();
        assertThat(request.tppRedirectUri()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = ConsentTypeCode.class, names = "DETAILED", mode = EnumSource.Mode.EXCLUDE)
    void shouldRefuseConsentTypeOtherThanDetailed(ConsentTypeCode type) {
        AccountAccessConsent body = body(currentAccount()).consentType(type);

        assertRefused(body, null, ErrorCode.CONSENT_TYPE_NOT_SUPPORTED);
    }

    @ParameterizedTest
    @MethodSource("accessBeyondCurrentAccounts")
    void shouldRefuseAccessBeyondCurrentAccounts(AccountAccess access) {
        assertRefused(body(access), null, ErrorCode.PARAMETER_NOT_SUPPORTED);
    }

    static Stream<Arguments> accessBeyondCurrentAccounts() {
        List<AccountAccessRights> one =
                List.of(access(new AccountReference().iban(IBAN_A), AccessRightsCodes.BALANCES));
        return Stream.of(
                Arguments.of(new AccountAccess().cards(one)),
                Arguments.of(new AccountAccess().savings(one)),
                Arguments.of(new AccountAccess().loans(one)),
                Arguments.of(new AccountAccess().securities(one)));
    }

    @ParameterizedTest
    @MethodSource("accountsNotNamedByIbanAlone")
    void shouldRefuseAccountNotNamedByIbanAlone(@Nullable AccountReference account) {
        AccountAccess access = new AccountAccess().payments(List.of(access(account, AccessRightsCodes.BALANCES)));

        assertRefused(body(access), null, ErrorCode.PARAMETER_NOT_SUPPORTED);
    }

    static Stream<Arguments> accountsNotNamedByIbanAlone() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(new AccountReference().bban("3999990000000000000000001")),
                Arguments.of(new AccountReference().iban(IBAN_A).bban("3999990000000000000000001")),
                Arguments.of(new AccountReference().iban(IBAN_A).msisdn("+380501234567")));
    }

    @ParameterizedTest
    @EnumSource(value = AccessRightsCodes.class, names = {"ACCOUNT_DETAILS", "BALANCES"},
            mode = EnumSource.Mode.EXCLUDE)
    void shouldRefuseRightTheBankDoesNotOffer(AccessRightsCodes right) {
        AccountAccess access = new AccountAccess().payments(List.of(
                access(new AccountReference().iban(IBAN_A), AccessRightsCodes.BALANCES, right)));

        assertRefused(body(access), null, ErrorCode.PARAMETER_NOT_SUPPORTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/consents/done", "ftp://tpp.example/done", "mailto:help@tpp.example", "https:/done"})
    void shouldRefuseRedirectUriThatIsNotWebAddress(String uri) {
        assertRefused(body(currentAccount()), URI.create(uri), ErrorCode.FORMAT_ERROR);
    }

    @Test
    void shouldRefuseRedirectUriLongerThanTheBankStores() {
        URI uri = URI.create("https://tpp.example/" + "a".repeat(2048));

        assertRefused(body(currentAccount()), uri, ErrorCode.FORMAT_ERROR);
    }

    private static void assertRefused(AccountAccessConsent body, @Nullable URI redirectUri, ErrorCode code) {
        assertThatThrownBy(() -> ConsentRequests.from(body, redirectUri))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private static AccountAccessConsent body(AccountAccess access) {
        return new AccountAccessConsent(access, ConsentTypeCode.DETAILED, true, VALID_TO, 4);
    }

    private static AccountAccess currentAccount() {
        return new AccountAccess().payments(List.of(
                access(new AccountReference().iban(IBAN_A), AccessRightsCodes.BALANCES)));
    }

    private static AccountAccessRights access(@Nullable AccountReference account, AccessRightsCodes... rights) {
        return new AccountAccessRights(List.of(rights)).account(account);
    }
}
