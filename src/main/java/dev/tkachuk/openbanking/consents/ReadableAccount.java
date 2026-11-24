package dev.tkachuk.openbanking.consents;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** One account a valid consent lets its provider read, and the customer who approved the consent. */
public record ReadableAccount(@Nullable String customerId, ConsentAccount account) {

    public ReadableAccount {
        Objects.requireNonNull(account, "account");
    }
}
