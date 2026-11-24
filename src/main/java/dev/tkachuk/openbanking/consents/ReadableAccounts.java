package dev.tkachuk.openbanking.consents;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The accounts a valid consent lets its provider list, and the customer who approved it: the consent covers an
 * account only while that customer holds it. customerId is null only for consents made valid before 0.4.0.
 */
public record ReadableAccounts(@Nullable String customerId, List<ConsentAccount> accounts) {

    public ReadableAccounts {
        accounts = List.copyOf(accounts);
    }
}
