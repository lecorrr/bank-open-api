package dev.tkachuk.openbanking.payments;

/** Where a payment is in its life, as the contract's ISO 20022 transaction status codes; the ones this bank uses. */
enum PaymentStatus {
    /** Received: waiting for the customer's decision at the bank. */
    RCVD,
    /** Cancelled by the provider before the customer decided. */
    CANC,
    /** Rejected by the customer, by bank-core or by the payee's bank; the reason code says which of the last two. */
    RJCT,
    /** Approved by the customer; bank-core has not confirmed the transfer yet. */
    ACTC,
    /** Paid within this bank: bank-core credited the payee's account at once. */
    ACCC,
    /** Sent to the payee's bank, whose answer bank-core waits for. */
    ACSP,
    /** Accepted by the payee's bank. */
    ACSC;

    String code() {
        return name();
    }

    /** Ended for good: nothing changes its status any more. */
    boolean isFinal() {
        return this == CANC || this == RJCT || this == ACCC || this == ACSC;
    }

    static PaymentStatus fromCode(String code) {
        for (PaymentStatus status : values()) {
            if (status.code().equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown payment status in the database: " + code);
    }
}
