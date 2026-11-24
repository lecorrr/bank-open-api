package dev.tkachuk.openbanking.consents;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A consent as the bank's customer pages show it: what the provider asks for and, through awaitingDecision,
 * whether the customer can still approve or reject it. status is the contract's code, expiry included.
 */
public record CustomerConsent(UUID id, @Nullable String providerId, String status, boolean awaitingDecision,
        List<ConsentAccount> accounts, boolean recurring, LocalDate validTo, int frequencyPerDay,
        @Nullable URI tppRedirectUri) {

    public CustomerConsent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        accounts = List.copyOf(accounts);
        Objects.requireNonNull(validTo, "validTo");
    }
}
