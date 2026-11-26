package dev.tkachuk.provider.bank;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An account the customer let the provider read, with its balances.
 *
 * @param resourceId the bank's id of the account in the API, which is not its IBAN
 */
public record Account(String resourceId, @Nullable String iban, String currency, List<Balance> balances) {

    /** One of the account's balances, such as the booked one, as the bank reports it. */
    public record Balance(String type, String amount, String currency) {
    }
}
