package dev.tkachuk.openbanking.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.tkachuk.openbanking.bankcore.BankCoreAccount;
import dev.tkachuk.openbanking.bankcore.BankCoreClient;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import dev.tkachuk.openbanking.consents.AccessRight;
import dev.tkachuk.openbanking.consents.ConsentAccount;
import dev.tkachuk.openbanking.consents.ConsentService;
import dev.tkachuk.openbanking.consents.CustomerConsent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {

    private static final String CONSENT_ID = "3f2a6c1e-8b4d-4e7f-9a12-6c5d4b3a2f10";
    private static final String CUSTOMER = "C1001";
    private static final String IBAN_A = "UA843999990000000000000000001";
    private static final String IBAN_B = "UA573999990000000000000000002";
    private static final ConsentAccount ACCOUNT_A = new ConsentAccount(UUID.randomUUID(), IBAN_A, "UAH",
            Set.of(AccessRight.ACCOUNT_DETAILS, AccessRight.BALANCES));
    private static final ConsentAccount ACCOUNT_B = new ConsentAccount(UUID.randomUUID(), IBAN_B, null,
            Set.of(AccessRight.BALANCES));

    @Mock
    private ConsentService consents;

    @Mock
    private BankCoreClient bankCore;

    private ApprovalService approvals;

    @BeforeEach
    void setUp() {
        approvals = new ApprovalService(consents, bankCore);
    }

    @Test
    void shouldMarkEachAccountAsTheCustomersOrNot() {
        when(consents.forCustomer(CONSENT_ID, CUSTOMER)).thenReturn(waiting(ACCOUNT_A, ACCOUNT_B));
        when(bankCore.findAccount(IBAN_A)).thenReturn(Optional.of(account(IBAN_A, "UAH", CUSTOMER)));
        when(bankCore.findAccount(IBAN_B)).thenReturn(Optional.of(account(IBAN_B, "UAH", "C1002")));

        ConsentPage page = approvals.page(CONSENT_ID, CUSTOMER);

        assertThat(page.accounts()).extracting(AccountOnPage::held).containsExactly(true, false);
        assertThat(page.accounts().get(0).accountDetails()).isTrue();
        assertThat(page.accounts().get(1).balances()).isTrue();
        assertThat(page.approvable()).isFalse();
    }

    @Test
    void shouldAskBankCoreNothingForConsentAlreadyDecided() {
        CustomerConsent decided = new CustomerConsent(UUID.fromString(CONSENT_ID), "provider-a", "valid", false,
                List.of(ACCOUNT_A), true, LocalDate.of(2027, 5, 21), 4, null);
        when(consents.forCustomer(CONSENT_ID, CUSTOMER)).thenReturn(decided);

        approvals.page(CONSENT_ID, CUSTOMER);

        verifyNoInteractions(bankCore);
    }

    @Test
    void shouldApproveWhenTheCustomerHoldsEveryAccount() {
        CustomerConsent waiting = waiting(ACCOUNT_A, ACCOUNT_B);
        when(consents.forCustomer(CONSENT_ID, CUSTOMER)).thenReturn(waiting);
        when(bankCore.findAccount(IBAN_A)).thenReturn(Optional.of(account(IBAN_A, "UAH", CUSTOMER)));
        when(bankCore.findAccount(IBAN_B)).thenReturn(Optional.of(account(IBAN_B, "EUR", CUSTOMER)));
        when(consents.approve(CONSENT_ID, CUSTOMER)).thenReturn(waiting);

        approvals.approve(CONSENT_ID, CUSTOMER);

        verify(consents).approve(CONSENT_ID, CUSTOMER);
    }

    // One account of the consent is the customer's; the second is not, as bank-core keeps it.
    @ParameterizedTest(name = "{0}")
    @MethodSource("accountsNotHeldAsNamed")
    void shouldRefuseApprovalOfConsentNamingAnAccountTheCustomerDoesNotHold(String what,
            @Nullable BankCoreAccount inBankCore) {
        ConsentAccount inHryvnia = new ConsentAccount(UUID.randomUUID(), IBAN_B, "UAH", Set.of(AccessRight.BALANCES));
        when(consents.forCustomer(CONSENT_ID, CUSTOMER)).thenReturn(waiting(ACCOUNT_A, inHryvnia));
        when(bankCore.findAccount(IBAN_A)).thenReturn(Optional.of(account(IBAN_A, "UAH", CUSTOMER)));
        when(bankCore.findAccount(IBAN_B)).thenReturn(Optional.ofNullable(inBankCore));

        assertThatThrownBy(() -> approvals.approve(CONSENT_ID, CUSTOMER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CONSENT_INVALID));
        verify(consents, never()).approve(anyString(), anyString());
    }

    // ConsentService refuses the decision with STATUS_INVALID; bank-core has nothing to say about it.
    @Test
    void shouldLeaveConsentNoLongerWaitingToConsentService() {
        CustomerConsent decided = new CustomerConsent(UUID.fromString(CONSENT_ID), null, "rejected", false,
                List.of(), true, LocalDate.of(2027, 5, 21), 4, null);
        when(consents.forCustomer(CONSENT_ID, CUSTOMER)).thenReturn(decided);
        when(consents.approve(CONSENT_ID, CUSTOMER))
                .thenThrow(new ApiException(ErrorCode.STATUS_INVALID, "The consent is rejected, not received"));

        assertThatThrownBy(() -> approvals.approve(CONSENT_ID, CUSTOMER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.STATUS_INVALID));
        verifyNoInteractions(bankCore);
    }

    @Test
    void shouldRejectWithoutAskingBankCore() {
        when(consents.reject(CONSENT_ID, CUSTOMER)).thenReturn(waiting(ACCOUNT_A));

        approvals.reject(CONSENT_ID, CUSTOMER);

        verify(consents).reject(CONSENT_ID, CUSTOMER);
        verifyNoInteractions(bankCore);
    }

    static Stream<Arguments> accountsNotHeldAsNamed() {
        return Stream.of(
                Arguments.of("another customer's account", account(IBAN_B, "UAH", "C1002")),
                Arguments.of("an account bank-core does not keep", null),
                Arguments.of("an account in another currency than named", account(IBAN_B, "USD", CUSTOMER)));
    }

    private static CustomerConsent waiting(ConsentAccount... accounts) {
        return new CustomerConsent(UUID.fromString(CONSENT_ID), "provider-a", "received", true, List.of(accounts),
                true, LocalDate.of(2027, 5, 21), 4, null);
    }

    private static BankCoreAccount account(String iban, String currency, String customer) {
        return new BankCoreAccount(iban, currency, BigDecimal.TEN, customer);
    }
}
