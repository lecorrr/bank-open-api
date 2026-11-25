package dev.tkachuk.openbanking.payments;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A payment as the bank's customer pages show it. order is null for a customer who did not decide on a payment
 * that is decided: they learn no more than its status. reasonCode is the ISO 20022 reason of a rejection by the
 * bank. debtorAccountHeld is whether the customer holds the account a payment waiting for them is to be made from,
 * so may approve it; false once the payment is decided, when bank-core is not asked.
 */
public record CustomerPayment(UUID id, @Nullable String providerId, String status, @Nullable String reasonCode,
        boolean awaitingDecision, boolean debtorAccountHeld, @Nullable PaymentOrder order,
        @Nullable URI tppRedirectUri) {

    public CustomerPayment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
    }
}
