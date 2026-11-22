package dev.tkachuk.openbanking.accounts;

import dev.tkachuk.openbanking.bankcore.BankCoreAccount;

/**
 * The contract's accountDetails, with the fields filled so far. Until consents arrive, the IBAN is also the
 * resourceId a provider puts into the account paths.
 */
public record AccountDetails(String resourceId, String iban, String currency) {

    static AccountDetails of(BankCoreAccount account) {
        return new AccountDetails(account.iban(), account.iban(), account.currency());
    }
}
