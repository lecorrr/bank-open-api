package dev.tkachuk.openbanking.approval;

import org.jspecify.annotations.Nullable;

/** One account a consent names, as its page lists it: what may be read, and whether the customer holds it. */
public record AccountOnPage(String iban, @Nullable String currency, boolean accountDetails, boolean balances,
        boolean held) {
}
