package dev.tkachuk.openbanking.payments;

import dev.tkachuk.openbanking.bankcore.BankCoreClient;
import dev.tkachuk.openbanking.bankcore.BankCoreTransfer;
import dev.tkachuk.openbanking.bankcore.BankCoreUnavailableException;
import dev.tkachuk.openbanking.bankcore.TransferOrder;
import dev.tkachuk.openbanking.bankcore.TransferRefusedException;
import dev.tkachuk.openbanking.common.ResourceIds;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Initiates, cancels and makes payments. A provider's call names the provider: a payment exists only for the
 * provider that initiated it. A customer's call names the customer: only one who holds the debtor account at
 * bank-core may approve, and the approval makes the transfer there, with the payment's id as its Idempotency-Key.
 */
@Service
public class PaymentService {

    /** How many waiting payments one run of the retry job takes on; the rest wait for the next run. */
    static final int RETRY_BATCH = 20;

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository repository;
    private final BankCoreClient bankCore;
    private final PaymentProperties properties;
    private final Clock clock;

    PaymentService(PaymentRepository repository, BankCoreClient bankCore, PaymentProperties properties, Clock clock) {
        this.repository = repository;
        this.bankCore = bankCore;
        this.properties = properties;
        this.clock = clock;
    }

    Payment create(NewPayment request, String providerId) {
        Payment payment = Payment.create(UUID.randomUUID(), providerId, request, clock.instant());
        repository.insert(payment);
        log.info("Payment {} received from provider {}", payment.id(), providerId);
        return payment;
    }

    /**
     * The payment a path names, for the provider that initiated it; an unknown one is answered with 403
     * RESOURCE_UNKNOWN. For a payment sent to another bank, bank-core is asked first whether that bank answered.
     */
    Payment get(String paymentId, String providerId) {
        Payment payment = ownPayment(paymentId, providerId);
        if (payment.status() == PaymentStatus.ACSP) {
            settle(payment);
        }
        return payment;
    }

    void cancel(String paymentId, String providerId) {
        Payment payment = ownPayment(paymentId, providerId);
        PaymentStatus statusAsRead = payment.status();
        if (!payment.cancelByProvider(clock.instant())) {
            log.info("Payment {} was cancelled already", payment.id());
            return;
        }
        save(payment, statusAsRead);
        log.info("Payment {} cancelled by the provider", payment.id());
    }

    /**
     * A payment as any signed-in customer sees it from the link the provider sent them. Once decided, only the
     * customer who decided sees what it pays; anyone else learns no more than its status.
     */
    public CustomerPayment forCustomer(String paymentId, String customerId) {
        Payment payment = find(paymentId);
        if (payment.awaitingDecision()) {
            return view(payment, holdsDebtorAccount(customerId, payment));
        }
        // Once decided, the page only reports the outcome, so bank-core is not asked.
        if (payment.decidedBy(customerId)) {
            return view(payment, false);
        }
        return new CustomerPayment(payment.id(), null, payment.status().code(), null, false, false, null, null);
    }

    /**
     * The customer approves the payment, and the bank makes its transfer at once. Only a customer who holds the
     * debtor account may: nothing checks it again before the money moves.
     */
    public CustomerPayment approve(String paymentId, String customerId) {
        Payment payment = find(paymentId);
        if (payment.awaitingDecision() && !holdsDebtorAccount(customerId, payment)) {
            throw new AccountNotHeldException(
                    "The customer does not hold the account payment " + payment.id() + " is to be made from");
        }
        PaymentStatus statusAsRead = payment.status();
        // A payment no longer waiting is refused here, with STATUS_INVALID.
        payment.approve(customerId, clock.instant());
        save(payment, statusAsRead);
        log.info("Payment {} approved by customer {} for provider {}", payment.id(), customerId,
                payment.providerId());
        makeTransfer(payment);
        return view(payment, false);
    }

    /** The customer refuses the payment; they need not hold its account to refuse it. */
    public CustomerPayment reject(String paymentId, String customerId) {
        Payment payment = find(paymentId);
        PaymentStatus statusAsRead = payment.status();
        payment.reject(customerId, clock.instant());
        save(payment, statusAsRead);
        log.info("Payment {} rejected by customer {}", payment.id(), customerId);
        return view(payment, false);
    }

    /**
     * Makes the transfers of approved payments bank-core did not confirm, as it could not be asked: those whose last
     * attempt started a retry delay ago. Stops at the first payment bank-core cannot be asked for.
     */
    void retryApproved() {
        Instant now = clock.instant();
        Instant lastAttemptBefore = now.minus(properties.retryDelay());
        for (Payment payment : repository.findWaitingForTransfer(lastAttemptBefore, RETRY_BATCH)) {
            if (!repository.claimAttempt(payment.id(), lastAttemptBefore, now)) {
                // The job on another instance took it.
                continue;
            }
            payment.attemptStarted(now);
            try {
                if (!makeTransfer(payment)) {
                    return;
                }
            } catch (RuntimeException e) {
                // One payment's failure must not stop the others; it is tried again after the retry delay.
                log.error("Payment {}: the transfer could not be made", payment.id(), e);
            }
        }
    }

    // The order never changes and the payment's id is the Idempotency-Key, so a repeat cannot pay twice. False when
    // bank-core could not be asked: the payment stays ACTC for the retry job.
    private boolean makeTransfer(Payment payment) {
        PaymentStatus statusAsRead = payment.status();
        try {
            BankCoreTransfer transfer = bankCore.transfer(transferOrderOf(payment.order()), payment.id().toString());
            payment.transferMade(transfer, clock.instant());
        } catch (TransferRefusedException e) {
            payment.transferRefused(e.insufficientFunds(), clock.instant());
            log.info("Payment {}: bank-core refused the transfer with {}", payment.id(), e.code());
        } catch (BankCoreUnavailableException e) {
            log.warn("Payment {} waits for bank-core: {}", payment.id(), e.getMessage());
            return false;
        }
        if (repository.updateStatus(payment, statusAsRead)) {
            log.info("Payment {} is {}", payment.id(), payment.status().code());
        } else {
            log.info("Payment {}: another attempt stored the outcome first", payment.id());
        }
        return true;
    }

    // bank-core down leaves the payment as it was; the provider reads its status again later.
    private void settle(Payment payment) {
        BankCoreTransfer transfer;
        try {
            transfer = bankCore.findTransfer(Objects.requireNonNull(payment.transferId(), "transferId"));
        } catch (BankCoreUnavailableException e) {
            log.warn("Payment {}: bank-core could not say whether the payee's bank answered: {}", payment.id(),
                    e.getMessage());
            return;
        }
        PaymentStatus statusAsRead = payment.status();
        if (payment.settle(transfer.status(), clock.instant()) && repository.updateStatus(payment, statusAsRead)) {
            log.info("Payment {} is {}", payment.id(), payment.status().code());
        }
    }

    // bank-core says who holds an account. The payment is in hryvnias, so the account must be too.
    private boolean holdsDebtorAccount(String customerId, Payment payment) {
        return bankCore.findAccount(payment.order().debtorIban())
                .map(account -> account.heldBy(customerId, payment.order().currency()))
                .orElse(false);
    }

    private Payment ownPayment(String paymentId, String providerId) {
        Payment payment = find(paymentId);
        if (!payment.belongsTo(providerId)) {
            // Answered as unknown, so a provider cannot even learn that another provider's payment exists.
            log.warn("Provider {} asked for payment {}, which is not its own", providerId, payment.id());
            throw new ApiException(ErrorCode.PAYMENT_UNKNOWN, ErrorCode.PAYMENT_UNKNOWN.text());
        }
        return payment;
    }

    private Payment find(String paymentId) {
        return ResourceIds.parse(paymentId)
                .flatMap(repository::findById)
                .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_UNKNOWN, ErrorCode.PAYMENT_UNKNOWN.text()));
    }

    private void save(Payment payment, PaymentStatus statusAsRead) {
        if (!repository.updateStatus(payment, statusAsRead)) {
            throw new ApiException(ErrorCode.STATUS_INVALID,
                    "The payment changed at the same time; read its status and try again");
        }
    }

    private static TransferOrder transferOrderOf(PaymentOrder order) {
        return new TransferOrder(order.debtorIban(), order.creditorIban(), order.creditorName(), order.amount(),
                order.currency(), order.remittance());
    }

    private static CustomerPayment view(Payment payment, boolean debtorAccountHeld) {
        return new CustomerPayment(payment.id(), payment.providerId(), payment.status().code(),
                payment.reason() == null ? null : payment.reason().code(), payment.awaitingDecision(),
                debtorAccountHeld, payment.order(), payment.tppRedirectUri());
    }
}
