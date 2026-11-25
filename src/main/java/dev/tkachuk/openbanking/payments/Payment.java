package dev.tkachuk.openbanking.payments;

import dev.tkachuk.openbanking.bankcore.BankCoreTransfer;
import dev.tkachuk.openbanking.common.Iban;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A domestic credit transfer a provider initiates and the customer decides on at the bank: what it pays, and where
 * it is in its life. The rules for creating it and its status changes live here.
 */
final class Payment {

    /** The product's currency: hryvnias, between accounts at banks in Ukraine. */
    static final String CURRENCY = "UAH";
    /** bank-core's limit for one transfer. */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");
    /** The longest name and purpose bank-core takes, as ISO 20022's Max140Text. */
    static final int MAX_TEXT_LENGTH = 140;
    static final int MAX_END_TO_END_ID_LENGTH = 35;

    // Digits with at most two decimals after a dot (kopiykas); no sign, no exponent, no thousands separator.
    private static final Pattern AMOUNT = Pattern.compile("[0-9]{1,10}(\\.[0-9]{1,2})?");
    // Line breaks and other control characters: bank-core's texts are one line.
    private static final Pattern NOT_ONE_LINE = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]");

    private final UUID id;
    private final String providerId;
    private final PaymentOrder order;
    private final @Nullable URI tppRedirectUri;
    private final Instant createdAt;
    private PaymentStatus status;
    private @Nullable RejectionReason reason;
    private @Nullable String customerId;
    private @Nullable UUID transferId;
    private Instant statusChangedAt;
    private @Nullable Instant attemptedAt;

    /**
     * customerId is null until the customer decides; transferId until bank-core makes the transfer. attemptedAt is
     * when the bank last started making the transfer: an attempt holds the payment for the retry delay.
     */
    Payment(UUID id, String providerId, PaymentOrder order, @Nullable URI tppRedirectUri, Instant createdAt,
            PaymentStatus status, @Nullable RejectionReason reason, @Nullable String customerId,
            @Nullable UUID transferId, Instant statusChangedAt, @Nullable Instant attemptedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.order = Objects.requireNonNull(order, "order");
        this.tppRedirectUri = tppRedirectUri;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.status = Objects.requireNonNull(status, "status");
        this.reason = reason;
        this.customerId = customerId;
        this.transferId = transferId;
        this.statusChangedAt = Objects.requireNonNull(statusChangedAt, "statusChangedAt");
        this.attemptedAt = attemptedAt;
    }

    /**
     * Checks a provider's initiation against the rules of domestic-credit-transfers and makes a received payment of
     * it. Whether the debtor account exists is not checked here: a provider must not learn which accounts do.
     */
    static Payment create(UUID id, String providerId, NewPayment request, Instant now) {
        String debtorIban = ukrainianIban("debtorAccount.iban", request.debtorIban());
        String creditorIban = ukrainianIban("creditorAccount.iban", request.creditorIban());
        if (debtorIban.equals(creditorIban)) {
            throw formatError("creditorAccount.iban must differ from debtorAccount.iban");
        }
        requireHryvnias("debtorAccount.currency", request.debtorCurrency());
        requireHryvnias("creditorAccount.currency", request.creditorCurrency());
        requireHryvnias("instructedAmount.currency", request.currency());
        PaymentOrder order = new PaymentOrder(debtorIban, creditorIban,
                oneLine("creditor.name", request.creditorName(), MAX_TEXT_LENGTH),
                amount(request.amount()),
                CURRENCY,
                oneLine("remittanceInformationUnstructured", request.remittance(), MAX_TEXT_LENGTH),
                request.endToEndId() == null ? null
                        : oneLine("paymentIdentification.endToEndId", request.endToEndId(), MAX_END_TO_END_ID_LENGTH));
        return new Payment(id, providerId, order, request.tppRedirectUri(), now, PaymentStatus.RCVD, null, null, null,
                now, null);
    }

    /** Whether the provider initiated this payment; for any other provider it does not exist. */
    boolean belongsTo(String provider) {
        return provider.equals(providerId);
    }

    /** Whether this customer decided on the payment; only they see what a decided payment pays. */
    boolean decidedBy(String customer) {
        return customer.equals(customerId);
    }

    boolean awaitingDecision() {
        return status == PaymentStatus.RCVD;
    }

    /**
     * The provider cancels the payment. False when it was cancelled already, which leaves it as it was; refused
     * once the customer has decided, as an approved payment may be paid already.
     */
    boolean cancelByProvider(Instant now) {
        if (status == PaymentStatus.CANC) {
            return false;
        }
        if (status != PaymentStatus.RCVD) {
            throw new ApiException(ErrorCode.CANCELLATION_INVALID, ErrorCode.CANCELLATION_INVALID.text());
        }
        changeTo(PaymentStatus.CANC, now);
        return true;
    }

    /**
     * The customer approves the payment; whether they hold the debtor account is for the caller to check first.
     * The approval starts the first attempt at the transfer.
     */
    void approve(String customer, Instant now) {
        requireReceived();
        customerId = Objects.requireNonNull(customer, "customer");
        changeTo(PaymentStatus.ACTC, now);
        attemptedAt = now;
    }

    /** The customer refuses the payment. */
    void reject(String customer, Instant now) {
        requireReceived();
        customerId = Objects.requireNonNull(customer, "customer");
        changeTo(PaymentStatus.RJCT, now);
    }

    /** Another attempt at the transfer of an approved payment starts. */
    void attemptStarted(Instant now) {
        requireStatus(PaymentStatus.ACTC);
        attemptedAt = now;
    }

    /**
     * bank-core made the transfer, or answered a repeat with the transfer it made before. Within this bank it is
     * completed at once; a repeat can find one to another bank completed or refused by that bank since.
     */
    void transferMade(BankCoreTransfer transfer, Instant now) {
        requireStatus(PaymentStatus.ACTC);
        transferId = transfer.id();
        switch (transfer.status()) {
            case COMPLETED -> changeTo(order.toSameBank() ? PaymentStatus.ACCC : PaymentStatus.ACSC, now);
            case PENDING -> changeTo(PaymentStatus.ACSP, now);
            case REJECTED -> rejectFor(RejectionReason.ORDER_REJECTED, now);
        }
    }

    /** bank-core refused the transfer, so no money moved. */
    void transferRefused(boolean insufficientFunds, Instant now) {
        requireStatus(PaymentStatus.ACTC);
        rejectFor(insufficientFunds ? RejectionReason.INSUFFICIENT_FUNDS : RejectionReason.ORDER_REJECTED, now);
    }

    /** What bank-core now says of a transfer sent to another bank. False while that bank has not answered. */
    boolean settle(BankCoreTransfer.Status transferStatus, Instant now) {
        requireStatus(PaymentStatus.ACSP);
        switch (transferStatus) {
            case COMPLETED -> changeTo(PaymentStatus.ACSC, now);
            case REJECTED -> rejectFor(RejectionReason.ORDER_REJECTED, now);
            case PENDING -> {
                return false;
            }
        }
        return true;
    }

    UUID id() {
        return id;
    }

    String providerId() {
        return providerId;
    }

    PaymentOrder order() {
        return order;
    }

    @Nullable URI tppRedirectUri() {
        return tppRedirectUri;
    }

    Instant createdAt() {
        return createdAt;
    }

    PaymentStatus status() {
        return status;
    }

    @Nullable RejectionReason reason() {
        return reason;
    }

    @Nullable String customerId() {
        return customerId;
    }

    @Nullable UUID transferId() {
        return transferId;
    }

    Instant statusChangedAt() {
        return statusChangedAt;
    }

    @Nullable Instant attemptedAt() {
        return attemptedAt;
    }

    private void requireReceived() {
        if (status != PaymentStatus.RCVD) {
            throw new ApiException(ErrorCode.STATUS_INVALID, "The payment is no longer waiting for a decision");
        }
    }

    // The service calls these steps only in the status they belong to; another one is a bug.
    private void requireStatus(PaymentStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Payment " + id + " is " + status + ", not " + expected);
        }
    }

    private void rejectFor(RejectionReason rejectionReason, Instant now) {
        reason = rejectionReason;
        changeTo(PaymentStatus.RJCT, now);
    }

    private void changeTo(PaymentStatus next, Instant now) {
        status = next;
        statusChangedAt = now;
    }

    private static String ukrainianIban(String field, String text) {
        String iban = text.toUpperCase(Locale.ROOT);
        if (!Iban.isValid(iban)) {
            throw formatError(field + " is not a valid IBAN");
        }
        if (!Iban.isUkrainian(iban)) {
            throw formatError(field + " must be the IBAN of an account at a bank in Ukraine");
        }
        return iban;
    }

    private static void requireHryvnias(String field, @Nullable String currency) {
        if (currency != null && !CURRENCY.equals(currency)) {
            throw formatError(field + " must be " + CURRENCY + ": domestic-credit-transfers are in hryvnias");
        }
    }

    private static BigDecimal amount(String text) {
        if (!AMOUNT.matcher(text).matches()) {
            throw formatError("instructedAmount.amount must be digits with at most two decimals after a dot");
        }
        // Two decimals, as the bank stores amounts; the pattern leaves nothing to round.
        BigDecimal amount = new BigDecimal(text).setScale(2);
        if (amount.signum() <= 0) {
            throw formatError("instructedAmount.amount must be more than 0");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw formatError("instructedAmount.amount must be at most " + MAX_AMOUNT.toPlainString());
        }
        return amount;
    }

    // A text bank-core passes on: present, not blank, one line, and within its length.
    private static String oneLine(String field, @Nullable String text, int maxLength) {
        if (text == null) {
            throw formatError(field + " is required for domestic-credit-transfers");
        }
        if (text.isBlank()) {
            throw formatError(field + " must not be blank");
        }
        if (NOT_ONE_LINE.matcher(text).find()) {
            throw formatError(field + " must be one line, without control characters");
        }
        if (text.length() > maxLength) {
            throw formatError(field + " must be at most " + maxLength + " characters");
        }
        return text;
    }

    private static ApiException formatError(String detail) {
        return new ApiException(ErrorCode.FORMAT_ERROR, detail);
    }
}
