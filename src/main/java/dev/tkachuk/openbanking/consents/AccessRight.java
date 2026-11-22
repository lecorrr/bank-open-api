package dev.tkachuk.openbanking.consents;

import java.util.Optional;

/** What a consent lets a provider read from one account, with the contract's codes. */
public enum AccessRight {
    ACCOUNT_DETAILS("accountDetails"),
    BALANCES("balances");

    private final String code;

    AccessRight(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    static Optional<AccessRight> fromCode(String code) {
        for (AccessRight right : values()) {
            if (right.code.equals(code)) {
                return Optional.of(right);
            }
        }
        return Optional.empty();
    }
}
