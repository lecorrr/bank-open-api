package dev.tkachuk.openbanking.approval;

import dev.tkachuk.openbanking.bankcore.BankCoreClient;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import dev.tkachuk.openbanking.consents.AccessRight;
import dev.tkachuk.openbanking.consents.ConsentAccount;
import dev.tkachuk.openbanking.consents.ConsentService;
import dev.tkachuk.openbanking.consents.CustomerConsent;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The customer's side of a consent: the page, the decision and the withdrawal. bank-core says who holds each
 * account, and a customer may approve only a consent whose accounts all are theirs.
 */
@Service
class ApprovalService {

    private final ConsentService consents;
    private final BankCoreClient bankCore;

    ApprovalService(ConsentService consents, BankCoreClient bankCore) {
        this.consents = consents;
        this.bankCore = bankCore;
    }

    ConsentPage page(String consentId, String customerId) {
        CustomerConsent consent = consents.forCustomer(consentId, customerId);
        // Once decided, the page only reports the outcome, so bank-core is not asked.
        List<AccountOnPage> accounts = consent.accounts().stream()
                .map(account -> onPage(account, !consent.awaitingDecision() || holds(customerId, account)))
                .toList();
        return new ConsentPage(consent, accounts);
    }

    CustomerConsent approve(String consentId, String customerId) {
        CustomerConsent consent = consents.forCustomer(consentId, customerId);
        if (consent.awaitingDecision() && !holdsAll(customerId, consent)) {
            throw new ApiException(ErrorCode.CONSENT_INVALID,
                    "The consent names an account the customer does not hold; it can only be rejected");
        }
        // A consent no longer waiting is refused there, with STATUS_INVALID.
        return consents.approve(consentId, customerId);
    }

    CustomerConsent reject(String consentId, String customerId) {
        return consents.reject(consentId, customerId);
    }

    List<CustomerConsent> validConsentsOf(String customerId) {
        return consents.validConsentsOf(customerId);
    }

    void revoke(String consentId, String customerId) {
        consents.revokeByCustomer(consentId, customerId);
    }

    private boolean holdsAll(String customerId, CustomerConsent consent) {
        return consent.accounts().stream().allMatch(account -> holds(customerId, account));
    }

    // The same rule the reads apply: an account in another currency than named would never be readable.
    private boolean holds(String customerId, ConsentAccount account) {
        return bankCore.findAccount(account.iban()).map(data -> data.heldBy(customerId, account.currency()))
                .orElse(false);
    }

    private static AccountOnPage onPage(ConsentAccount account, boolean held) {
        return new AccountOnPage(account.iban(), account.currency(), account.allows(AccessRight.ACCOUNT_DETAILS),
                account.allows(AccessRight.BALANCES), held);
    }
}
