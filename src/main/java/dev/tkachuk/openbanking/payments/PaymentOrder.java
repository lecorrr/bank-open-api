package dev.tkachuk.openbanking.payments;

import dev.tkachuk.openbanking.common.Iban;
import java.math.BigDecimal;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a payment pays: from which account, to whom, how much and what for. endToEndId is the provider's own id of
 * the payment, kept to return it.
 */
public record PaymentOrder(String debtorIban, String creditorIban, String creditorName, BigDecimal amount,
        String currency, String remittance, @Nullable String endToEndId) {

    public PaymentOrder {
        Objects.requireNonNull(debtorIban, "debtorIban");
        Objects.requireNonNull(creditorIban, "creditorIban");
        Objects.requireNonNull(creditorName, "creditorName");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(remittance, "remittance");
    }

    /** Whether the payee's account is at the payer's bank, this one: both IBANs carry the same bank code. */
    boolean toSameBank() {
        return Iban.ukrainianBankCode(debtorIban).equals(Iban.ukrainianBankCode(creditorIban));
    }
}
