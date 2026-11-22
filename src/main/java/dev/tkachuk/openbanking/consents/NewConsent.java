package dev.tkachuk.openbanking.consents;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** A provider's request for a consent, before the consent rules have checked it. */
record NewConsent(
        List<RequestedAccount> accounts,
        boolean recurring,
        LocalDate validTo,
        int frequencyPerDay,
        @Nullable URI tppRedirectUri) {

    NewConsent {
        accounts = List.copyOf(accounts);
    }

    record RequestedAccount(String iban, @Nullable String currency, Set<AccessRight> rights) {

        RequestedAccount {
            rights = Set.copyOf(rights);
        }
    }
}
