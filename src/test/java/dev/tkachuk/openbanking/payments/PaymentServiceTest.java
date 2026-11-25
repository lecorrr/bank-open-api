package dev.tkachuk.openbanking.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.tkachuk.openbanking.bankcore.BankCoreAccount;
import dev.tkachuk.openbanking.bankcore.BankCoreClient;
import dev.tkachuk.openbanking.bankcore.BankCoreTransfer;
import dev.tkachuk.openbanking.bankcore.BankCoreUnavailableException;
import dev.tkachuk.openbanking.bankcore.TransferOrder;
import dev.tkachuk.openbanking.bankcore.TransferRefusedException;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final UUID PAYMENT_ID = UUID.fromString("5b7e2c9a-1d4f-4a6b-8c3e-9f0a1b2c3d4e");
    private static final String PAYMENT = PAYMENT_ID.toString();
    private static final UUID SECOND_ID = UUID.fromString("6c8f3d0b-2e5a-4b7c-9d4f-0a1b2c3d4e5f");
    private static final UUID TRANSFER_ID = UUID.fromString("8d2f4b6a-0c1e-4f3a-9b5d-7e9f1a3c5b70");
    private static final String PROVIDER = "provider-a";
    private static final String CUSTOMER = "C1001";
    private static final String OTHER_CUSTOMER = "C1002";
    private static final String OWN_IBAN = "UA843999990000000000000000001";
    private static final String PAYEE_IBAN = "UA573999990000000000000000002";
    private static final String OTHER_BANK_IBAN = "UA213223130000026007233566001";
    private static final URI TPP_REDIRECT = URI.create("https://tpp.example/payments/done");
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);
    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");
    private static final Instant LAST_ATTEMPT_BEFORE = NOW.minus(RETRY_DELAY);
    private static final Instant EARLIER = NOW.minus(Duration.ofMinutes(5));
    private static final PaymentOrder ORDER = new PaymentOrder(OWN_IBAN, PAYEE_IBAN, "Olena Kovalenko",
            new BigDecimal("150.50"), "UAH", "Rent for November", null);

    @Mock
    private PaymentRepository repository;

    @Mock
    private BankCoreClient bankCore;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneId.of("Europe/Kyiv"));
        service = new PaymentService(repository, bankCore, new PaymentProperties(RETRY_DELAY), clock);
    }

    @Test
    void shouldStoreReceivedPayment() {
        Payment payment = service.create(new NewPayment(OWN_IBAN, null, PAYEE_IBAN, null, "Olena Kovalenko",
                "150.50", "UAH", "Rent for November", null, TPP_REDIRECT), PROVIDER);

        verify(repository).insert(payment);
        assertThat(payment.status()).isEqualTo(PaymentStatus.RCVD);
        assertThat(payment.belongsTo(PROVIDER)).isTrue();
        assertThat(payment.createdAt()).isEqualTo(NOW);
    }

    @Test
    void shouldStoreNothingWhenInitiationBreaksARule() {
        NewPayment nothingToPay = new NewPayment(OWN_IBAN, null, PAYEE_IBAN, null, "Olena Kovalenko", "0.00", "UAH",
                "Rent for November", null, null);

        assertCode(() -> service.create(nothingToPay, PROVIDER), ErrorCode.FORMAT_ERROR);
        verifyNoInteractions(repository);
    }

    // Upper case and other forms of the same UUID are not ids the bank hands out; the database is not asked.
    @ParameterizedTest
    @ValueSource(strings = {"5B7E2C9A-1D4F-4A6B-8C3E-9F0A1B2C3D4E", "not-a-payment", ""})
    void shouldAnswerUnknownForIdTheBankNeverHandsOut(String paymentId) {
        assertCode(() -> service.get(paymentId, PROVIDER), ErrorCode.PAYMENT_UNKNOWN);
        verifyNoInteractions(repository);
    }

    @Test
    void shouldAnswerUnknownForPaymentTheBankDoesNotHave() {
        when(repository.findById(PAYMENT_ID)).thenReturn(Optional.empty());

        assertCode(() -> service.get(PAYMENT, PROVIDER), ErrorCode.PAYMENT_UNKNOWN);
    }

    @Test
    void shouldAnswerUnknownForAnotherProvidersPayment() {
        stored(in(PaymentStatus.RCVD));

        assertCode(() -> service.get(PAYMENT, "provider-b"), ErrorCode.PAYMENT_UNKNOWN);
        assertCode(() -> service.cancel(PAYMENT, "provider-b"), ErrorCode.PAYMENT_UNKNOWN);
    }

    @Test
    void shouldNotAskBankCoreForPaymentThatIsNotOnItsWayToAnotherBank() {
        stored(in(PaymentStatus.ACCC));

        assertThat(service.get(PAYMENT, PROVIDER).status()).isEqualTo(PaymentStatus.ACCC);
        verifyNoInteractions(bankCore);
    }

    @Test
    void shouldLearnThatThePayeesBankAccepted() {
        Payment payment = stored(in(PaymentStatus.ACSP));
        when(bankCore.findTransfer(TRANSFER_ID))
                .thenReturn(new BankCoreTransfer(TRANSFER_ID, BankCoreTransfer.Status.COMPLETED));
        when(repository.updateStatus(payment, PaymentStatus.ACSP)).thenReturn(true);

        assertThat(service.get(PAYMENT, PROVIDER).status()).isEqualTo(PaymentStatus.ACSC);
    }

    @Test
    void shouldStaySentWhileThePayeesBankHasNotAnswered() {
        stored(in(PaymentStatus.ACSP));
        when(bankCore.findTransfer(TRANSFER_ID))
                .thenReturn(new BankCoreTransfer(TRANSFER_ID, BankCoreTransfer.Status.PENDING));

        assertThat(service.get(PAYMENT, PROVIDER).status()).isEqualTo(PaymentStatus.ACSP);
        verify(repository, never()).updateStatus(any(), any());
    }

    @Test
    void shouldAnswerStoredStatusWhenBankCoreCannotBeAsked() {
        stored(in(PaymentStatus.ACSP));
        when(bankCore.findTransfer(TRANSFER_ID)).thenThrow(unavailable());

        assertThat(service.get(PAYMENT, PROVIDER).status()).isEqualTo(PaymentStatus.ACSP);
        verify(repository, never()).updateStatus(any(), any());
    }

    @Test
    void shouldCancelPaymentWaitingForCustomer() {
        Payment payment = stored(in(PaymentStatus.RCVD));
        when(repository.updateStatus(payment, PaymentStatus.RCVD)).thenReturn(true);

        service.cancel(PAYMENT, PROVIDER);

        assertThat(payment.status()).isEqualTo(PaymentStatus.CANC);
    }

    @Test
    void shouldLeaveCancelledPaymentAsItWas() {
        stored(in(PaymentStatus.CANC));

        service.cancel(PAYMENT, PROVIDER);

        verify(repository, never()).updateStatus(any(), any());
    }

    @Test
    void shouldRefuseCancellationOfPaymentChangedAtTheSameTime() {
        Payment payment = stored(in(PaymentStatus.RCVD));
        when(repository.updateStatus(payment, PaymentStatus.RCVD)).thenReturn(false);

        assertCode(() -> service.cancel(PAYMENT, PROVIDER), ErrorCode.STATUS_INVALID);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("debtorAccountsAtBankCore")
    void shouldShowWaitingPaymentWithWhetherTheCustomerHoldsItsAccount(String what,
            @Nullable BankCoreAccount atBankCore, boolean held) {
        stored(in(PaymentStatus.RCVD));
        when(bankCore.findAccount(OWN_IBAN)).thenReturn(Optional.ofNullable(atBankCore));

        CustomerPayment payment = service.forCustomer(PAYMENT, CUSTOMER);

        assertThat(payment.awaitingDecision()).isTrue();
        assertThat(payment.debtorAccountHeld()).isEqualTo(held);
        assertThat(payment.order()).isEqualTo(ORDER);
        assertThat(payment.providerId()).isEqualTo(PROVIDER);
    }

    static Stream<Arguments> debtorAccountsAtBankCore() {
        return Stream.of(
                Arguments.of("the customer's account", account(CUSTOMER, "UAH"), true),
                Arguments.of("another customer's account", account(OTHER_CUSTOMER, "UAH"), false),
                Arguments.of("the customer's account in dollars", account(CUSTOMER, "USD"), false),
                Arguments.of("an account bank-core does not keep", null, false));
    }

    @Test
    void shouldShowDecidedPaymentToTheCustomerWhoDecidedWithoutAskingBankCore() {
        stored(in(PaymentStatus.ACCC));

        CustomerPayment payment = service.forCustomer(PAYMENT, CUSTOMER);

        assertThat(payment.status()).isEqualTo("ACCC");
        assertThat(payment.order()).isEqualTo(ORDER);
        verifyNoInteractions(bankCore);
    }

    @Test
    void shouldShowOnlyTheStatusOfPaymentAnotherCustomerDecided() {
        stored(new Payment(PAYMENT_ID, PROVIDER, ORDER, TPP_REDIRECT, EARLIER, PaymentStatus.RJCT,
                RejectionReason.INSUFFICIENT_FUNDS, CUSTOMER, null, EARLIER, EARLIER));

        CustomerPayment payment = service.forCustomer(PAYMENT, OTHER_CUSTOMER);

        assertThat(payment).isEqualTo(
                new CustomerPayment(PAYMENT_ID, null, "RJCT", null, false, false, null, null));
        verifyNoInteractions(bankCore);
    }

    @Test
    void shouldMakeTheTransferWhenTheCustomerApproves() {
        Payment payment = stored(in(PaymentStatus.RCVD));
        when(bankCore.findAccount(OWN_IBAN)).thenReturn(Optional.of(account(CUSTOMER, "UAH")));
        when(repository.updateStatus(payment, PaymentStatus.RCVD)).thenReturn(true);
        when(bankCore.transfer(any(), eq(PAYMENT)))
                .thenReturn(new BankCoreTransfer(TRANSFER_ID, BankCoreTransfer.Status.COMPLETED));
        when(repository.updateStatus(payment, PaymentStatus.ACTC)).thenReturn(true);

        CustomerPayment approved = service.approve(PAYMENT, CUSTOMER);

        assertThat(approved.status()).isEqualTo("ACCC");
        assertThat(approved.tppRedirectUri()).isEqualTo(TPP_REDIRECT);
        assertThat(payment.decidedBy(CUSTOMER)).isTrue();
        assertThat(payment.transferId()).isEqualTo(TRANSFER_ID);
        // The payment's id is the Idempotency-Key, and the order is the payment's, so a repeat cannot pay twice.
        verify(bankCore).transfer(new TransferOrder(OWN_IBAN, PAYEE_IBAN, "Olena Kovalenko",
                new BigDecimal("150.50"), "UAH", "Rent for November"), PAYMENT);
    }

    @Test
    void shouldRefuseApprovalFromCustomerWhoDoesNotHoldTheAccount() {
        stored(in(PaymentStatus.RCVD));
        when(bankCore.findAccount(OWN_IBAN)).thenReturn(Optional.of(account(OTHER_CUSTOMER, "UAH")));

        assertThatThrownBy(() -> service.approve(PAYMENT, CUSTOMER)).isInstanceOf(AccountNotHeldException.class);
        verify(repository, never()).updateStatus(any(), any());
        verify(bankCore, never()).transfer(any(), any());
    }

    @ParameterizedTest
    @CsvSource({"INSUFFICIENT_FUNDS, AM04", "ACCOUNT_NOT_FOUND, DS04", "CURRENCY_MISMATCH, DS04"})
    void shouldRejectPaymentWhoseTransferBankCoreRefused(String bankCoreCode, String reasonCode) {
        Payment payment = approvable();
        when(bankCore.transfer(any(), eq(PAYMENT))).thenThrow(new TransferRefusedException(bankCoreCode));
        when(repository.updateStatus(payment, PaymentStatus.ACTC)).thenReturn(true);

        CustomerPayment approved = service.approve(PAYMENT, CUSTOMER);

        assertThat(approved.status()).isEqualTo("RJCT");
        assertThat(approved.reasonCode()).isEqualTo(reasonCode);
    }

    @Test
    void shouldLeavePaymentApprovedWhenBankCoreCannotBeAsked() {
        approvable();
        when(bankCore.transfer(any(), eq(PAYMENT))).thenThrow(unavailable());

        CustomerPayment approved = service.approve(PAYMENT, CUSTOMER);

        assertThat(approved.status()).isEqualTo("ACTC");
        verify(repository, never()).updateStatus(any(), eq(PaymentStatus.ACTC));
    }

    @Test
    void shouldMakeNoTransferForPaymentChangedAtTheSameTime() {
        Payment payment = stored(in(PaymentStatus.RCVD));
        when(bankCore.findAccount(OWN_IBAN)).thenReturn(Optional.of(account(CUSTOMER, "UAH")));
        when(repository.updateStatus(payment, PaymentStatus.RCVD)).thenReturn(false);

        assertCode(() -> service.approve(PAYMENT, CUSTOMER), ErrorCode.STATUS_INVALID);
        verify(bankCore, never()).transfer(any(), any());
    }

    @Test
    void shouldRefuseApprovalOfPaymentNoLongerWaiting() {
        stored(in(PaymentStatus.CANC));

        assertCode(() -> service.approve(PAYMENT, CUSTOMER), ErrorCode.STATUS_INVALID);
        verifyNoInteractions(bankCore);
    }

    // Rejecting needs no account: anyone the provider sent the link to may stop the payment.
    @Test
    void shouldRejectWithoutAskingBankCore() {
        Payment payment = stored(in(PaymentStatus.RCVD));
        when(repository.updateStatus(payment, PaymentStatus.RCVD)).thenReturn(true);

        CustomerPayment rejected = service.reject(PAYMENT, OTHER_CUSTOMER);

        assertThat(rejected.status()).isEqualTo("RJCT");
        assertThat(rejected.reasonCode()).isNull();
        verifyNoInteractions(bankCore);
    }

    @Test
    void shouldTryAgainPaymentWhoseLastAttemptIsARetryDelayAgo() {
        Payment payment = waiting(PAYMENT_ID);
        when(repository.findWaitingForTransfer(LAST_ATTEMPT_BEFORE, PaymentService.RETRY_BATCH))
                .thenReturn(List.of(payment));
        when(repository.claimAttempt(PAYMENT_ID, LAST_ATTEMPT_BEFORE, NOW)).thenReturn(true);
        when(bankCore.transfer(any(), eq(PAYMENT)))
                .thenReturn(new BankCoreTransfer(TRANSFER_ID, BankCoreTransfer.Status.PENDING));
        when(repository.updateStatus(payment, PaymentStatus.ACTC)).thenReturn(true);

        service.retryApproved();

        assertThat(payment.status()).isEqualTo(PaymentStatus.ACSP);
        assertThat(payment.attemptedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldLeavePaymentTheJobOnAnotherInstanceTook() {
        when(repository.findWaitingForTransfer(LAST_ATTEMPT_BEFORE, PaymentService.RETRY_BATCH))
                .thenReturn(List.of(waiting(PAYMENT_ID)));
        when(repository.claimAttempt(PAYMENT_ID, LAST_ATTEMPT_BEFORE, NOW)).thenReturn(false);

        service.retryApproved();

        verifyNoInteractions(bankCore);
    }

    @Test
    void shouldStopAtFirstPaymentBankCoreCannotBeAskedFor() {
        when(repository.findWaitingForTransfer(LAST_ATTEMPT_BEFORE, PaymentService.RETRY_BATCH))
                .thenReturn(List.of(waiting(PAYMENT_ID), waiting(SECOND_ID)));
        when(repository.claimAttempt(PAYMENT_ID, LAST_ATTEMPT_BEFORE, NOW)).thenReturn(true);
        when(bankCore.transfer(any(), eq(PAYMENT))).thenThrow(unavailable());

        service.retryApproved();

        verify(repository, never()).claimAttempt(eq(SECOND_ID), any(), any());
    }

    @Test
    void shouldGoOnWithOtherPaymentsAfterOneFailed() {
        Payment second = waiting(SECOND_ID);
        when(repository.findWaitingForTransfer(LAST_ATTEMPT_BEFORE, PaymentService.RETRY_BATCH))
                .thenReturn(List.of(waiting(PAYMENT_ID), second));
        when(repository.claimAttempt(PAYMENT_ID, LAST_ATTEMPT_BEFORE, NOW)).thenReturn(true);
        when(repository.claimAttempt(SECOND_ID, LAST_ATTEMPT_BEFORE, NOW)).thenReturn(true);
        when(bankCore.transfer(any(), eq(PAYMENT)))
                .thenThrow(new IllegalStateException("bank-core keeps a different transfer under this key"));
        when(bankCore.transfer(any(), eq(SECOND_ID.toString())))
                .thenReturn(new BankCoreTransfer(TRANSFER_ID, BankCoreTransfer.Status.COMPLETED));
        when(repository.updateStatus(second, PaymentStatus.ACTC)).thenReturn(true);

        service.retryApproved();

        assertThat(second.status()).isEqualTo(PaymentStatus.ACCC);
    }

    private Payment stored(Payment payment) {
        when(repository.findById(payment.id())).thenReturn(Optional.of(payment));
        return payment;
    }

    // A waiting payment the customer may approve; the approval itself is stored.
    private Payment approvable() {
        Payment payment = stored(in(PaymentStatus.RCVD));
        when(bankCore.findAccount(OWN_IBAN)).thenReturn(Optional.of(account(CUSTOMER, "UAH")));
        when(repository.updateStatus(payment, PaymentStatus.RCVD)).thenReturn(true);
        return payment;
    }

    // A payment as the repository would read it in the given status, decided by CUSTOMER if decided.
    private static Payment in(PaymentStatus status) {
        boolean decided = status != PaymentStatus.RCVD && status != PaymentStatus.CANC;
        boolean made = status == PaymentStatus.ACCC || status == PaymentStatus.ACSP || status == PaymentStatus.ACSC;
        PaymentOrder order = status == PaymentStatus.ACSP
                ? new PaymentOrder(OWN_IBAN, OTHER_BANK_IBAN, "Olena Kovalenko", new BigDecimal("150.50"), "UAH",
                        "Rent for November", null)
                : ORDER;
        return new Payment(PAYMENT_ID, PROVIDER, order, TPP_REDIRECT, EARLIER, status, null,
                decided ? CUSTOMER : null, made ? TRANSFER_ID : null, EARLIER, decided ? EARLIER : null);
    }

    private static Payment waiting(UUID id) {
        return new Payment(id, PROVIDER, ORDER, null, EARLIER, PaymentStatus.ACTC, null, CUSTOMER, null, EARLIER,
                EARLIER);
    }

    private static BankCoreAccount account(String customer, String currency) {
        return new BankCoreAccount(OWN_IBAN, currency, new BigDecimal("850.00"), customer);
    }

    private static BankCoreUnavailableException unavailable() {
        return new BankCoreUnavailableException("bank-core answered 503", null);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
