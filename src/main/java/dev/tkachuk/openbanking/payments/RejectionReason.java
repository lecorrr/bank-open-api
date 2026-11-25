package dev.tkachuk.openbanking.payments;

/**
 * Why the bank rejected a payment the customer had approved, as the ISO 20022 status reason codes the contract
 * lists. A payment the customer rejected has none.
 */
enum RejectionReason {
    /** AM04: the account does not have the money. */
    INSUFFICIENT_FUNDS("AM04"),
    /** DS04: anything else bank-core or the payee's bank refused, such as an account it does not keep. */
    ORDER_REJECTED("DS04");

    private final String code;

    RejectionReason(String code) {
        this.code = code;
    }

    String code() {
        return code;
    }

    static RejectionReason fromCode(String code) {
        for (RejectionReason reason : values()) {
            if (reason.code.equals(code)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("Unknown rejection reason in the database: " + code);
    }
}
