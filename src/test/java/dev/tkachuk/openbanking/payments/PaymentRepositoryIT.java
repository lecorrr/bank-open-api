package dev.tkachuk.openbanking.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.openbanking.PostgresTestcontainer;
import dev.tkachuk.openbanking.bankcore.BankCoreTransfer;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@JdbcTest
@Import({PostgresTestcontainer.class, PaymentRepository.class})
class PaymentRepositoryIT {

    private static final UUID PAYMENT_ID = UUID.fromString("5b7e2c9a-1d4f-4a6b-8c3e-9f0a1b2c3d4e");
    private static final UUID SECOND_ID = UUID.fromString("6c8f3d0b-2e5a-4b7c-9d4f-0a1b2c3d4e5f");
    private static final UUID THIRD_ID = UUID.fromString("7d9a4e1c-3f6b-4c8d-8e5a-1b2c3d4e5f60");
    private static final UUID TRANSFER_ID = UUID.fromString("8d2f4b6a-0c1e-4f3a-9b5d-7e9f1a3c5b70");
    private static final String PROVIDER = "provider-a";
    private static final String CUSTOMER = "C1001";
    private static final PaymentOrder ORDER = new PaymentOrder("UA843999990000000000000000001",
            "UA573999990000000000000000002", "Olena Kovalenko", new BigDecimal("150.50"), "UAH", "Rent for November",
            "E2E-42");
    // timestamptz keeps microseconds, which is why the application's clock ticks in microseconds.
    private static final Instant CREATED = Instant.parse("2026-11-25T10:00:00.123456Z");
    private static final Instant APPROVED = Instant.parse("2026-11-25T10:02:00.654321Z");
    private static final Instant LATER = Instant.parse("2026-11-25T10:05:00Z");

    @Autowired
    private PaymentRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void shouldReadBackStoredPayment() {
        repository.insert(received(PAYMENT_ID, URI.create("https://tpp.example/payments/done")));

        Payment read = repository.findById(PAYMENT_ID).orElseThrow();
        assertThat(read.providerId()).isEqualTo(PROVIDER);
        assertThat(read.order()).isEqualTo(ORDER);
        assertThat(read.tppRedirectUri()).isEqualTo(URI.create("https://tpp.example/payments/done"));
        assertThat(read.createdAt()).isEqualTo(CREATED);
        assertThat(read.status()).isEqualTo(PaymentStatus.RCVD);
        assertThat(read.reason()).isNull();
        assertThat(read.customerId()).isNull();
        assertThat(read.transferId()).isNull();
        assertThat(read.statusChangedAt()).isEqualTo(CREATED);
        assertThat(read.attemptedAt()).isNull();
    }

    @Test
    void shouldFindNothingForUnknownId() {
        assertThat(repository.findById(PAYMENT_ID)).isEmpty();
    }

    @Test
    void shouldSaveDecisionAndTransferWithTheStatus() {
        Payment payment = received(PAYMENT_ID, null);
        repository.insert(payment);
        payment.approve(CUSTOMER, APPROVED);
        assertThat(repository.updateStatus(payment, PaymentStatus.RCVD)).isTrue();
        payment.transferRefused(true, LATER);

        assertThat(repository.updateStatus(payment, PaymentStatus.ACTC)).isTrue();

        Payment read = repository.findById(PAYMENT_ID).orElseThrow();
        assertThat(read.status()).isEqualTo(PaymentStatus.RJCT);
        assertThat(read.reason()).isEqualTo(RejectionReason.INSUFFICIENT_FUNDS);
        assertThat(read.customerId()).isEqualTo(CUSTOMER);
        assertThat(read.statusChangedAt()).isEqualTo(LATER);
        assertThat(read.attemptedAt()).isEqualTo(APPROVED);
    }

    // Two attempts read the same status; the second one's save finds it changed and stores nothing.
    @Test
    void shouldSaveNothingWhenTheStatusChangedSinceItWasRead() {
        Payment payment = received(PAYMENT_ID, null);
        repository.insert(payment);
        payment.cancelByProvider(LATER);
        assertThat(repository.updateStatus(payment, PaymentStatus.RCVD)).isTrue();

        Payment stale = received(PAYMENT_ID, null);
        stale.approve(CUSTOMER, LATER);

        assertThat(repository.updateStatus(stale, PaymentStatus.RCVD)).isFalse();
        assertThat(repository.findById(PAYMENT_ID).orElseThrow().status()).isEqualTo(PaymentStatus.CANC);
    }

    @Test
    void shouldFindApprovedPaymentsWhoseLastAttemptStartedBeforeGivenTime() {
        repository.insert(approved(PAYMENT_ID, APPROVED));
        repository.insert(approved(SECOND_ID, CREATED));
        // Approved later than the time asked for: its attempt may still run.
        repository.insert(approved(THIRD_ID, LATER));

        assertThat(repository.findWaitingForTransfer(LATER, 10)).extracting(Payment::id)
                .containsExactly(SECOND_ID, PAYMENT_ID);
        assertThat(repository.findWaitingForTransfer(LATER, 1)).extracting(Payment::id).containsExactly(SECOND_ID);
    }

    @Test
    void shouldFindOnlyPaymentsStillWaitingForTheirTransfer() {
        repository.insert(received(PAYMENT_ID, null));
        Payment made = approved(SECOND_ID, CREATED);
        made.transferMade(new BankCoreTransfer(TRANSFER_ID, BankCoreTransfer.Status.COMPLETED), APPROVED);
        repository.insert(made);

        assertThat(repository.findWaitingForTransfer(LATER, 10)).isEmpty();
    }

    // Of two jobs that found the same payment, only the first gets it; the second's time is past the claim.
    @Test
    void shouldLetOnlyOneAttemptClaimThePayment() {
        repository.insert(approved(PAYMENT_ID, CREATED));

        assertThat(repository.claimAttempt(PAYMENT_ID, APPROVED, LATER)).isTrue();
        assertThat(repository.claimAttempt(PAYMENT_ID, APPROVED, LATER)).isFalse();
        assertThat(repository.findById(PAYMENT_ID).orElseThrow().attemptedAt()).isEqualTo(LATER);
    }

    @Test
    void shouldNotClaimPaymentNoLongerApproved() {
        repository.insert(received(PAYMENT_ID, null));

        assertThat(repository.claimAttempt(PAYMENT_ID, LATER, LATER)).isFalse();
    }

    // The table's own rules, so a bug cannot store a payment the service could never have made. Each test makes
    // one refused statement: after an error PostgreSQL refuses everything else in the test's transaction.
    @Test
    void shouldRefuseApprovedPaymentWithoutCustomer() {
        assertThatThrownBy(() -> repository.insert(new Payment(PAYMENT_ID, PROVIDER, ORDER, null, CREATED,
                PaymentStatus.ACTC, null, null, null, CREATED, CREATED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRefuseApprovedPaymentWithoutAttempt() {
        assertThatThrownBy(() -> repository.insert(new Payment(PAYMENT_ID, PROVIDER, ORDER, null, CREATED,
                PaymentStatus.ACTC, null, CUSTOMER, null, CREATED, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRefusePaidPaymentWithoutTransfer() {
        assertThatThrownBy(() -> repository.insert(new Payment(PAYMENT_ID, PROVIDER, ORDER, null, CREATED,
                PaymentStatus.ACCC, null, CUSTOMER, null, CREATED, CREATED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRefuseReasonForPaymentThatIsNotRejected() {
        assertThatThrownBy(() -> repository.insert(new Payment(PAYMENT_ID, PROVIDER, ORDER, null, CREATED,
                PaymentStatus.ACCC, RejectionReason.ORDER_REJECTED, CUSTOMER, TRANSFER_ID, CREATED, CREATED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRefuseStatusTheBankDoesNotUse() {
        repository.insert(received(PAYMENT_ID, null));

        assertThatThrownBy(() -> jdbc.sql("UPDATE payment SET status = 'PATC' WHERE id = :id")
                .param("id", PAYMENT_ID).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static Payment received(UUID id, @Nullable URI tppRedirectUri) {
        return new Payment(id, PROVIDER, ORDER, tppRedirectUri, CREATED, PaymentStatus.RCVD, null, null, null, CREATED,
                null);
    }

    private static Payment approved(UUID id, Instant attemptedAt) {
        return new Payment(id, PROVIDER, ORDER, null, CREATED, PaymentStatus.ACTC, null, CUSTOMER, null, attemptedAt,
                attemptedAt);
    }
}
