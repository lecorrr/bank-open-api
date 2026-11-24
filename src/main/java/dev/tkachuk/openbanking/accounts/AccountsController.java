package dev.tkachuk.openbanking.accounts;

import dev.tkachuk.openbanking.api.AccountsApi;
import dev.tkachuk.openbanking.api.model.AccountDetails;
import dev.tkachuk.openbanking.api.model.AccountList;
import dev.tkachuk.openbanking.api.model.AccountReference;
import dev.tkachuk.openbanking.api.model.Amount;
import dev.tkachuk.openbanking.api.model.Balance;
import dev.tkachuk.openbanking.api.model.BalanceType;
import dev.tkachuk.openbanking.api.model.CashAccountType;
import dev.tkachuk.openbanking.api.model.HrefType;
import dev.tkachuk.openbanking.api.model.LinksAccountDetails;
import dev.tkachuk.openbanking.api.model.ReadAccountBalanceResponse200;
import dev.tkachuk.openbanking.api.model.ReadAccountDetails200Response;
import dev.tkachuk.openbanking.bankcore.BankCoreAccount;
import dev.tkachuk.openbanking.bankcore.BankCoreClient;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import dev.tkachuk.openbanking.consents.AccessRight;
import dev.tkachuk.openbanking.consents.ConsentAccount;
import dev.tkachuk.openbanking.consents.ConsentService;
import dev.tkachuk.openbanking.consents.ReadableAccount;
import dev.tkachuk.openbanking.consents.ReadableAccounts;
import dev.tkachuk.openbanking.security.AuthenticatedProvider;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The contract's account endpoints. Each read goes through ConsentService first, which decides from the
 * Consent-ID and the calling provider whether the provider may read this data, and only then asks bank-core. The
 * consent covers an account only while the customer who approved it holds it in bank-core.
 */
@RestController
class AccountsController implements AccountsApi {

    private static final Logger log = LoggerFactory.getLogger(AccountsController.class);
    private static final String ACCOUNTS = "/v2/accounts/";

    private final ConsentService consents;
    private final BankCoreClient bankCore;
    private final AuthenticatedProvider provider;
    private final Clock clock;

    AccountsController(ConsentService consents, BankCoreClient bankCore, AuthenticatedProvider provider,
            Clock clock) {
        this.consents = consents;
        this.bankCore = bankCore;
        this.provider = provider;
        this.clock = clock;
    }

    @Override
    public ResponseEntity<AccountList> getAccountList(UUID requestId, String consentId, @Nullable Boolean withBalance,
            @Nullable String psuIpAddress) {
        ReadableAccounts readable = consents.accountsToList(consentId, provider.id(), customerPresent(psuIpAddress));
        AccountList list = new AccountList(new ArrayList<>());
        for (ConsentAccount account : readable.accounts()) {
            // An account bank-core no longer has, in another currency than the consent named, or no longer held by
            // the customer who approved, is left out.
            bankCore.findAccount(account.iban())
                    .filter(data -> data.heldBy(readable.customerId(), account.currency()))
                    .ifPresent(data -> list.addAccountsItem(details(account, data, Boolean.TRUE.equals(withBalance))));
        }
        log.info("Account list read under consent {}: {} accounts", consentId, list.getAccounts().size());
        return ResponseEntity.ok(list);
    }

    @Override
    public ResponseEntity<ReadAccountDetails200Response> readAccountDetails(String accountId, UUID requestId,
            String consentId, @Nullable Boolean withBalance, @Nullable String psuIpAddress) {
        ReadableAccount readable = consents.accountToRead(consentId, provider.id(), accountId,
                AccessRight.ACCOUNT_DETAILS, customerPresent(psuIpAddress));
        AccountDetails details = details(readable.account(), load(readable), Boolean.TRUE.equals(withBalance));
        log.info("Account details read under consent {}", consentId);
        return ResponseEntity.ok(new ReadAccountDetails200Response(details));
    }

    @Override
    public ResponseEntity<ReadAccountBalanceResponse200> getBalances(String accountId, UUID requestId,
            String consentId, @Nullable String psuIpAddress) {
        ReadableAccount readable = consents.accountToRead(consentId, provider.id(), accountId, AccessRight.BALANCES,
                customerPresent(psuIpAddress));
        BankCoreAccount data = load(readable);
        log.info("Balances read under consent {}", consentId);
        return ResponseEntity.ok(new ReadAccountBalanceResponse200(
                new AccountReference().iban(data.iban()).currency(data.currency()), balances(data)));
    }

    // An account the consent no longer covers is answered like one the bank does not keep.
    private BankCoreAccount load(ReadableAccount readable) {
        return bankCore.findAccount(readable.account().iban())
                .filter(data -> data.heldBy(readable.customerId(), readable.account().currency()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_UNKNOWN, "The bank keeps no such account"));
    }

    private AccountDetails details(ConsentAccount account, BankCoreAccount data, boolean withBalance) {
        AccountDetails details = new AccountDetails(account.resourceId().toString(), data.currency())
                .iban(data.iban())
                .cashAccountType(CashAccountType.CACC);
        if (account.allows(AccessRight.BALANCES)) {
            details.links(new LinksAccountDetails()
                    .balances(new HrefType(ACCOUNTS + account.resourceId() + "/balances")));
            // The contract's withBalance may only add balances the consent covers.
            if (withBalance) {
                details.balances(balances(data));
            }
        }
        return details;
    }

    // bank-core has no holds or overdrafts, so its balance is what the customer can spend now.
    private List<Balance> balances(BankCoreAccount data) {
        int digits = Currency.getInstance(data.currency()).getDefaultFractionDigits();
        String amount = data.balance().setScale(digits, RoundingMode.UNNECESSARY).toPlainString();
        Balance balance = new Balance(new Amount(data.currency(), amount), BalanceType.INTERIM_AVAILABLE)
                .referenceDate(LocalDate.now(clock));
        return new ArrayList<>(List.of(balance));
    }

    // The contract: PSU-IP-Address is there if and only if the customer started this request.
    private static boolean customerPresent(@Nullable String psuIpAddress) {
        return psuIpAddress != null && !psuIpAddress.isBlank();
    }
}
