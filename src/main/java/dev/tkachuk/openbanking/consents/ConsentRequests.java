package dev.tkachuk.openbanking.consents;

import dev.tkachuk.openbanking.api.model.AccessRightsCodes;
import dev.tkachuk.openbanking.api.model.AccountAccess;
import dev.tkachuk.openbanking.api.model.AccountAccessConsent;
import dev.tkachuk.openbanking.api.model.AccountAccessRights;
import dev.tkachuk.openbanking.api.model.AccountReference;
import dev.tkachuk.openbanking.api.model.ConsentTypeCode;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Turns the contract's consent request into a NewConsent, refusing what this bank does not offer. */
final class ConsentRequests {

    private static final int MAX_REDIRECT_URI_LENGTH = 2048;

    private ConsentRequests() {
    }

    static NewConsent from(AccountAccessConsent body, @Nullable URI tppRedirectUri) {
        if (body.getConsentType() != ConsentTypeCode.DETAILED) {
            throw new ApiException(ErrorCode.CONSENT_TYPE_NOT_SUPPORTED, ErrorCode.CONSENT_TYPE_NOT_SUPPORTED.text());
        }
        AccountAccess access = body.getAccess();
        if (isSet(access.getCards()) || isSet(access.getSavings()) || isSet(access.getLoans())
                || isSet(access.getSecurities())) {
            throw new ApiException(ErrorCode.PARAMETER_NOT_SUPPORTED,
                    "Only access.payments is offered: the bank keeps current accounts only");
        }
        List<AccountAccessRights> payments = access.getPayments() == null ? List.of() : access.getPayments();
        List<NewConsent.RequestedAccount> accounts = new ArrayList<>();
        for (int i = 0; i < payments.size(); i++) {
            accounts.add(account(payments.get(i), "access.payments[" + i + "]"));
        }
        checkRedirectUri(tppRedirectUri);
        return new NewConsent(accounts, body.getRecurringIndicator(), body.getValidTo(), body.getFrequencyPerDay(),
                tppRedirectUri);
    }

    private static NewConsent.RequestedAccount account(AccountAccessRights entry, String field) {
        AccountReference reference = entry.getAccount();
        if (reference == null || reference.getIban() == null) {
            throw new ApiException(ErrorCode.PARAMETER_NOT_SUPPORTED, field + ".account must name the account by iban");
        }
        if (reference.getBban() != null || reference.getPan() != null || reference.getMaskedPan() != null
                || reference.getMsisdn() != null || reference.getOther() != null || reference.getProxy() != null) {
            throw new ApiException(ErrorCode.PARAMETER_NOT_SUPPORTED, field + ".account may name an iban only");
        }
        Set<AccessRight> rights = EnumSet.noneOf(AccessRight.class);
        for (AccessRightsCodes code : entry.getRights()) {
            rights.add(AccessRight.fromCode(code.getValue()).orElseThrow(() -> new ApiException(
                    ErrorCode.PARAMETER_NOT_SUPPORTED,
                    field + ".rights asks for a right this bank does not offer; offered: accountDetails, balances")));
        }
        return new NewConsent.RequestedAccount(reference.getIban(), reference.getCurrency(), rights);
    }

    // The customer is sent back here after approving, so it must be a complete web address the bank can store.
    private static void checkRedirectUri(@Nullable URI uri) {
        if (uri == null) {
            return;
        }
        boolean web = "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
        if (!web || uri.getHost() == null) {
            throw new ApiException(ErrorCode.FORMAT_ERROR, "TPP-Redirect-URI must be an absolute http or https URI");
        }
        if (uri.toString().length() > MAX_REDIRECT_URI_LENGTH) {
            throw new ApiException(ErrorCode.FORMAT_ERROR,
                    "TPP-Redirect-URI must be at most " + MAX_REDIRECT_URI_LENGTH + " characters");
        }
    }

    private static boolean isSet(@Nullable List<?> list) {
        return list != null && !list.isEmpty();
    }
}
