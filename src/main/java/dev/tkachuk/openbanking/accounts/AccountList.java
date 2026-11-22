package dev.tkachuk.openbanking.accounts;

import dev.tkachuk.openbanking.bankcore.BankCoreAccount;
import java.util.List;

/** The contract's accountList: the accounts a provider may see. */
public record AccountList(List<AccountDetails> accounts) {

    static AccountList of(List<BankCoreAccount> accounts) {
        return new AccountList(accounts.stream().map(AccountDetails::of).toList());
    }
}
