package dev.tkachuk.openbanking.bankcore;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The body of bank-core's POST /api/v1/transfers: from an account at this bank to any Ukrainian IBAN. bank-core
 * needs the payee's name for a transfer to another bank, and the description is the payment's purpose.
 */
public record TransferOrder(String fromIban, String toIban, String toName, BigDecimal amount, String currency,
        String description) {

    public TransferOrder {
        Objects.requireNonNull(fromIban, "fromIban");
        Objects.requireNonNull(toIban, "toIban");
        Objects.requireNonNull(toName, "toName");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(description, "description");
    }
}
