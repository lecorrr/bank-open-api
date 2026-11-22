package dev.tkachuk.openbanking.consents;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One account of a consent: its id in the API's paths, the IBAN and currency the provider named, and what may be
 * read.
 */
public record ConsentAccount(UUID resourceId, String iban, @Nullable String currency, Set<AccessRight> rights) {

    public ConsentAccount {
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(iban, "iban");
        rights = Set.copyOf(rights);
    }

    public boolean allows(AccessRight right) {
        return rights.contains(right);
    }
}
