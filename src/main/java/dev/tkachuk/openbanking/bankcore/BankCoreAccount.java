package dev.tkachuk.openbanking.bankcore;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * An account from bank-core's GET /api/v1/accounts/{iban}, with only the fields this service uses. customerId is
 * the customer who holds it; null for accounts bank-core has no customer for.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BankCoreAccount(String iban, String currency, BigDecimal balance, @Nullable String customerId) {

    /** Whether this customer holds the account; never for an account without a customer. */
    public boolean heldBy(@Nullable String customer) {
        return customer != null && customer.equals(customerId);
    }

    /** Whether this customer holds the account in the currency named; a null currency names none. */
    public boolean heldBy(@Nullable String customer, @Nullable String currency) {
        return heldBy(customer) && (currency == null || currency.equals(this.currency));
    }
}
