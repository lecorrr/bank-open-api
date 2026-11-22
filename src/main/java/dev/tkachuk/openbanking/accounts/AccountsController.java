package dev.tkachuk.openbanking.accounts;

import dev.tkachuk.openbanking.bankcore.BankCoreClient;
import dev.tkachuk.openbanking.common.web.RequestIdFilter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account information for providers. The walking skeleton of step 1: no consent check yet, so it lists every
 * customer account of bank-core. Step 2 adds consents, step 4 the check on every call.
 */
@RestController
class AccountsController {

    private static final Logger log = LoggerFactory.getLogger(AccountsController.class);

    private final BankCoreClient bankCore;

    AccountsController(BankCoreClient bankCore) {
        this.bankCore = bankCore;
    }

    // X-Request-ID is required by the contract; RequestIdFilter echoes it and logs under it.
    @GetMapping(path = "/v2/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
    AccountList listAccounts(@RequestHeader(RequestIdFilter.HEADER) UUID requestId) {
        AccountList accounts = AccountList.of(bankCore.listAccounts());
        // Every read of account data is logged, under the provider's request id and the trace: the audit trail.
        log.info("Account list read: {} accounts", accounts.accounts().size());
        return accounts;
    }
}
