package dev.tkachuk.openbanking.consents;

/** Where a consent is in its life, with the contract's names. Only valid consents allow reads. */
enum ConsentStatus {
    RECEIVED("received"),
    VALID("valid"),
    REJECTED("rejected"),
    REVOKED_BY_PSU("revokedByPsu"),
    TERMINATED_BY_TPP("terminatedByTpp"),
    // Never stored: a received or valid consent is expired once its validTo has passed.
    EXPIRED("expired");

    private final String code;

    ConsentStatus(String code) {
        this.code = code;
    }

    String code() {
        return code;
    }

    /** Ended for good: nothing changes its status any more. */
    boolean isFinal() {
        return this != RECEIVED && this != VALID;
    }

    static ConsentStatus fromCode(String code) {
        for (ConsentStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown consent status in the database: " + code);
    }
}
