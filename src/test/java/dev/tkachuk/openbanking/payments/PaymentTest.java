package dev.tkachuk.openbanking.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.openbanking.bankcore.BankCoreTransfer;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentTest {

    private static final UUID PAYMENT_ID = UUID.fromString("5b7e2c9a-1d4f-4a6b-8c3e-9f0a1b2c3d4e");
    private static final UUID TRANSFER_ID = UUID.fromString("8d2f4b6a-0c1e-4f3a-9b5d-7e9f1a3c5b70");
    private static final String PROVIDER = "provider-a";
    private static final String CUSTOMER = "C1001";
    private static final String OWN_IBAN = "UA843999990000000000000000001";
    private static final String PAYEE_IBAN = "UA573999990000000000000000002";
    private static final String OTHER_BANK_IBAN = "UA213223130000026007233566001";
    private static final URI TPP_REDIRECT = URI.create("https://tpp.example/payments/done");
    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-11-25T10:01:00Z");

    @Test
    void shouldCreateReceivedPaymentFromInitiation() {
        Request request = new Request();
        request.amount = "150.5";
        request.endToEndId = "E2E-42";
        request.tppRedirectUri = TPP_REDIRECT;

        Payment payment = Payment.create(PAYMENT_ID, PROVIDER, request.build(), NOW);

        assertThat(payment.status()).isEqualTo(PaymentStatus.RCVD);
        assertThat(payment.order()).isEqualTo(new PaymentOrder(OWN_IBAN, PAYEE_IBAN, "Olena Kovalenko",
                new BigDecimal("150.50"), "UAH", "Rent for November", "E2E-42"));
        assertThat(payment.tppRedirectUri()).isEqualTo(TPP_REDIRECT);
        assertThat(payment.createdAt()).isEqualTo(NOW);
        assertThat(payment.customerId()).isNull();
        assertThat(payment.attemptedAt()).isNull();
    }

    @Test
    void shouldStoreIbansInCapitals() {
        Request request = new Request();
        request.creditorIban = "ua573999990000000000000000002";

        assertThat(Payment.create(PAYMENT_ID, PROVIDER, request.build(), NOW).order().creditorIban())
                .isEqualTo(PAYEE_IBAN);
    }

    @Test
    void shouldAcceptLargestAmountAndLongestTexts() {
        Request request = new Request();
        request.amount = "1000000000.00";
        request.creditorName = "N".repeat(140);
        request.remittance = "R".repeat(140);
        request.endToEndId = "E".repeat(35);

        Payment payment = Payment.create(PAYMENT_ID, PROVIDER, request.build(), NOW);

        assertThat(payment.order().amount()).isEqualByComparingTo("1000000000");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("initiationsBreakingARule")
    void shouldRefuseInitiationBreakingARule(String what, NewPayment request, String field) {
        assertThatThrownBy(() -> Payment.create(PAYMENT_ID, PROVIDER, request, NOW))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORMAT_ERROR))
                .hasMessageStartingWith(field);
    }

    static Stream<Arguments> initiationsBreakingARule() {
        return Stream.of(
                broken("debtor IBAN with wrong check digits",
                        r -> r.debtorIban = "UA853999990000000000000000001", "debtorAccount.iban"),
                broken("creditor IBAN of a bank abroad", r -> r.creditorIban = "DE89370400440532013000",
                        "creditorAccount.iban"),
                broken("the same account twice", r -> r.creditorIban = OWN_IBAN, "creditorAccount.iban"),
                broken("debtor account in dollars", r -> r.debtorCurrency = "USD", "debtorAccount.currency"),
                broken("creditor account in euros", r -> r.creditorCurrency = "EUR", "creditorAccount.currency"),
                broken("amount in dollars", r -> r.currency = "USD", "instructedAmount.currency"),
                broken("amount with a comma", r -> r.amount = "150,50", "instructedAmount.amount"),
                broken("amount with three decimals", r -> r.amount = "1.005", "instructedAmount.amount"),
                broken("negative amount", r -> r.amount = "-1.00", "instructedAmount.amount"),
                broken("amount with an exponent", r -> r.amount = "1E3", "instructedAmount.amount"),
                broken("amount without digits before the dot", r -> r.amount = ".50", "instructedAmount.amount"),
                broken("no amount", r -> r.amount = "", "instructedAmount.amount"),
                broken("amount of nothing", r -> r.amount = "0.00", "instructedAmount.amount"),
                broken("amount over bank-core's limit", r -> r.amount = "1000000000.01", "instructedAmount.amount"),
                broken("no creditor name", r -> r.creditorName = null, "creditor.name"),
                broken("blank creditor name", r -> r.creditorName = "   ", "creditor.name"),
                broken("creditor name on two lines", r -> r.creditorName = "Olena\nKovalenko", "creditor.name"),
                broken("creditor name too long", r -> r.creditorName = "N".repeat(141), "creditor.name"),
                broken("no purpose", r -> r.remittance = null, "remittanceInformationUnstructured"),
                broken("blank purpose", r -> r.remittance = "\t", "remittanceInformationUnstructured"),
                broken("purpose with a line separator", r -> r.remittance = "Rent November",
                        "remittanceInformationUnstructured"),
                broken("purpose too long", r -> r.remittance = "R".repeat(141), "remittanceInformationUnstructured"),
                broken("blank end-to-end id", r -> r.endToEndId = " ", "paymentIdentification.endToEndId"),
                broken("end-to-end id too long", r -> r.endToEndId = "E".repeat(36),
                        "paymentIdentification.endToEndId"));
    }

    @Test
    void shouldBelongOnlyToTheProviderThatInitiatedIt() {
        Payment payment = received();

        assertThat(payment.belongsTo(PROVIDER)).isTrue();
        assertThat(payment.belongsTo("provider-b")).isFalse();
    }

    @Test
    void shouldStartFirstAttemptAtTransferWhenCustomerApproves() {
        Payment payment = received();

        payment.approve(CUSTOMER, LATER);

        assertThat(payment.status()).isEqualTo(PaymentStatus.ACTC);
        assertThat(payment.decidedBy(CUSTOMER)).isTrue();
        assertThat(payment.statusChangedAt()).isEqualTo(LATER);
        assertThat(payment.attemptedAt()).isEqualTo(LATER);
    }

    @Test
    void shouldRejectWithoutReasonWhenCustomerRejects() {
        Payment payment = received();

        payment.reject(CUSTOMER, LATER);

        assertThat(payment.status()).isEqualTo(PaymentStatus.RJCT);
        assertThat(payment.reason()).isNull();
        assertThat(payment.decidedBy(CUSTOMER)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = "RCVD", mode = EnumSource.Mode.EXCLUDE)
    void shouldRefuseDecisionOnPaymentNoLongerWaiting(PaymentStatus status) {
        Payment payment = in(status);

        assertStatusInvalid(() -> payment.approve(CUSTOMER, LATER));
        assertStatusInvalid(() -> payment.reject(CUSTOMER, LATER));
        assertThat(payment.status()).isEqualTo(status);
    }

    @Test
    void shouldCancelPaymentWaitingForCustomer() {
        Payment payment = received();

        assertThat(payment.cancelByProvider(LATER)).isTrue();
        assertThat(payment.status()).isEqualTo(PaymentStatus.CANC);
        assertThat(payment.statusChangedAt()).isEqualTo(LATER);
    }

    @Test
    void shouldLeaveCancelledPaymentAsItWas() {
        Payment payment = in(PaymentStatus.CANC);

        assertThat(payment.cancelByProvider(LATER)).isFalse();
        assertThat(payment.statusChangedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"RCVD", "CANC"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRefuseCancellationOnceCustomerDecided(PaymentStatus status) {
        Payment payment = in(status);

        assertThatThrownBy(() -> payment.cancelByProvider(LATER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CANCELLATION_INVALID));
        assertThat(payment.status()).isEqualTo(status);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transfersMade")
    void shouldTakeStatusFromTransferBankCoreMade(String what, String creditorIban, BankCoreTransfer.Status transfer,
            PaymentStatus expected) {
        Payment payment = approved(creditorIban);

        payment.transferMade(new BankCoreTransfer(TRANSFER_ID, transfer), LATER);

        assertThat(payment.status()).isEqualTo(expected);
        assertThat(payment.transferId()).isEqualTo(TRANSFER_ID);
        assertThat(payment.statusChangedAt()).isEqualTo(LATER);
    }

    static Stream<Arguments> transfersMade() {
        return Stream.of(
                Arguments.of("within this bank", PAYEE_IBAN, BankCoreTransfer.Status.COMPLETED, PaymentStatus.ACCC),
                Arguments.of("to another bank", OTHER_BANK_IBAN, BankCoreTransfer.Status.PENDING, PaymentStatus.ACSP),
                // A repeat finds what the other bank answered since.
                Arguments.of("to another bank that accepted it", OTHER_BANK_IBAN, BankCoreTransfer.Status.COMPLETED,
                        PaymentStatus.ACSC),
                Arguments.of("to another bank that refused it", OTHER_BANK_IBAN, BankCoreTransfer.Status.REJECTED,
                        PaymentStatus.RJCT));
    }

    @Test
    void shouldGiveReasonWhenAnotherBankRefusedTheTransfer() {
        Payment payment = approved(OTHER_BANK_IBAN);

        payment.transferMade(new BankCoreTransfer(TRANSFER_ID, BankCoreTransfer.Status.REJECTED), LATER);

        assertThat(payment.reason()).isEqualTo(RejectionReason.ORDER_REJECTED);
    }

    @Test
    void shouldRejectForInsufficientFundsWhenBankCoreSaysSo() {
        Payment payment = approved(PAYEE_IBAN);

        payment.transferRefused(true, LATER);

        assertThat(payment.status()).isEqualTo(PaymentStatus.RJCT);
        assertThat(payment.reason()).isEqualTo(RejectionReason.INSUFFICIENT_FUNDS);
        assertThat(payment.transferId()).isNull();
    }

    @Test
    void shouldRejectAsOrderRejectedForAnyOtherRefusal() {
        Payment payment = approved(PAYEE_IBAN);

        payment.transferRefused(false, LATER);

        assertThat(payment.reason()).isEqualTo(RejectionReason.ORDER_REJECTED);
    }

    @Test
    void shouldCompleteWhenThePayeesBankAccepted() {
        Payment payment = sent();

        assertThat(payment.settle(BankCoreTransfer.Status.COMPLETED, LATER)).isTrue();
        assertThat(payment.status()).isEqualTo(PaymentStatus.ACSC);
    }

    @Test
    void shouldRejectWhenThePayeesBankRefused() {
        Payment payment = sent();

        assertThat(payment.settle(BankCoreTransfer.Status.REJECTED, LATER)).isTrue();
        assertThat(payment.status()).isEqualTo(PaymentStatus.RJCT);
        assertThat(payment.reason()).isEqualTo(RejectionReason.ORDER_REJECTED);
    }

    @Test
    void shouldStaySentWhileThePayeesBankHasNotAnswered() {
        Payment payment = sent();

        assertThat(payment.settle(BankCoreTransfer.Status.PENDING, LATER)).isFalse();
        assertThat(payment.status()).isEqualTo(PaymentStatus.ACSP);
        assertThat(payment.statusChangedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldStartAnotherAttemptOnlyWhileApproved() {
        Payment payment = approved(PAYEE_IBAN);

        payment.attemptStarted(LATER);

        assertThat(payment.attemptedAt()).isEqualTo(LATER);
        assertThatThrownBy(() -> received().attemptStarted(LATER)).isInstanceOf(IllegalStateException.class);
    }

    // The service calls these steps only in their status; anything else is a bug, not a provider's error.
    @Test
    void shouldRefuseTransferStepsOutsideTheirStatus() {
        BankCoreTransfer transfer = new BankCoreTransfer(TRANSFER_ID, BankCoreTransfer.Status.COMPLETED);

        assertThatThrownBy(() -> received().transferMade(transfer, LATER)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sent().transferRefused(true, LATER)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approved(OTHER_BANK_IBAN).settle(BankCoreTransfer.Status.COMPLETED, LATER))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Payment received() {
        return Payment.create(PAYMENT_ID, PROVIDER, new Request().build(), NOW);
    }

    private static Payment approved(String creditorIban) {
        Request request = new Request();
        request.creditorIban = creditorIban;
        Payment payment = Payment.create(PAYMENT_ID, PROVIDER, request.build(), NOW);
        payment.approve(CUSTOMER, NOW);
        return payment;
    }

    private static Payment sent() {
        Payment payment = approved(OTHER_BANK_IBAN);
        payment.transferMade(new BankCoreTransfer(TRANSFER_ID, BankCoreTransfer.Status.PENDING), NOW);
        return payment;
    }

    // A payment as the repository would read it in the given status.
    private static Payment in(PaymentStatus status) {
        boolean decided = status != PaymentStatus.RCVD && status != PaymentStatus.CANC;
        boolean made = status == PaymentStatus.ACCC || status == PaymentStatus.ACSP || status == PaymentStatus.ACSC;
        return new Payment(PAYMENT_ID, PROVIDER, received().order(), null, NOW, status, null,
                decided ? CUSTOMER : null, made ? TRANSFER_ID : null, NOW, decided ? NOW : null);
    }

    private static void assertStatusInvalid(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.STATUS_INVALID));
    }

    private static Arguments broken(String what, Consumer<Request> change, String field) {
        Request request = new Request();
        change.accept(request);
        return Arguments.of(what, request.build(), field);
    }

    // The fields of a provider's initiation, each set to a value the rules accept; a test changes one.
    private static final class Request {
        String debtorIban = OWN_IBAN;
        @Nullable String debtorCurrency;
        String creditorIban = PAYEE_IBAN;
        @Nullable String creditorCurrency;
        @Nullable String creditorName = "Olena Kovalenko";
        String amount = "150.50";
        String currency = "UAH";
        @Nullable String remittance = "Rent for November";
        @Nullable String endToEndId;
        @Nullable URI tppRedirectUri;

        NewPayment build() {
            return new NewPayment(debtorIban, debtorCurrency, creditorIban, creditorCurrency, creditorName, amount,
                    currency, remittance, endToEndId, tppRedirectUri);
        }
    }
}
