package dev.tkachuk.openbanking.consents;

import dev.tkachuk.openbanking.api.ConsentsApi;
import dev.tkachuk.openbanking.api.model.AccessRightsCodes;
import dev.tkachuk.openbanking.api.model.AccountAccessConsent;
import dev.tkachuk.openbanking.api.model.AccountAccessConsentInformationResponse200Json;
import dev.tkachuk.openbanking.api.model.AccountAccessConsentResponse201;
import dev.tkachuk.openbanking.api.model.AccountAccessExtended;
import dev.tkachuk.openbanking.api.model.AccountAccessRightsExtended;
import dev.tkachuk.openbanking.api.model.AccountReference;
import dev.tkachuk.openbanking.api.model.ConsentStatusResponse200;
import dev.tkachuk.openbanking.api.model.ConsentTypeCode;
import dev.tkachuk.openbanking.api.model.HrefType;
import dev.tkachuk.openbanking.api.model.LinksAll;
import dev.tkachuk.openbanking.api.model.LinksConsents;
import dev.tkachuk.openbanking.api.model.LinksGetConsent;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import dev.tkachuk.openbanking.security.AuthenticatedProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The contract's consent endpoints: create, read, status and delete, each for the calling provider's consents. */
@RestController
class ConsentsController implements ConsentsApi {

    private static final String ACCOUNT_ACCESS = "account-access";
    private static final String CONSENTS = "/v2/consents/" + ACCOUNT_ACCESS + "/";
    private static final String ACCOUNTS = "/v2/accounts";

    private final ConsentService consents;
    private final AuthenticatedProvider provider;

    ConsentsController(ConsentService consents, AuthenticatedProvider provider) {
        this.consents = consents;
        this.provider = provider;
    }

    @Override
    public ResponseEntity<AccountAccessConsentResponse201> createAccountAccessConsent(UUID requestId,
            String psuIpAddress, @Nullable URI tppRedirectUri, @Nullable AccountAccessConsent body) {
        if (body == null) {
            throw new ApiException(ErrorCode.FORMAT_ERROR, "The request body is missing");
        }
        Consent consent = consents.create(ConsentRequests.from(body, tppRedirectUri), provider.id());
        String self = CONSENTS + consent.id();
        LinksConsents links = new LinksConsents().self(new HrefType(self)).status(new HrefType(self + "/status"));
        AccountAccessConsentResponse201 response =
                new AccountAccessConsentResponse201(contractStatus(consent), consent.id().toString(), links);
        // The customer approves the consent on the bank's own page: the Berlin Group's redirect approach.
        return ResponseEntity.created(URI.create(self)).header("ASPSP-SCA-Approach", "REDIRECT").body(response);
    }

    @Override
    public ResponseEntity<AccountAccessConsentInformationResponse200Json> getAccountAccessConsentInformation(
            String consentId, UUID requestId) {
        Consent consent = consents.get(consentId, provider.id());
        boolean valid = consents.statusOf(consent) == ConsentStatus.VALID;
        List<AccountAccessRightsExtended> payments = new ArrayList<>();
        for (ConsentAccount account : consent.accounts()) {
            payments.add(accessOf(account, valid));
        }
        AccountAccessConsentInformationResponse200Json response = new AccountAccessConsentInformationResponse200Json(
                new AccountAccessExtended().payments(payments), ConsentTypeCode.DETAILED, consent.recurring(),
                consent.validTo(), consent.frequencyPerDay(), contractStatus(consent));
        if (valid) {
            response.links(new LinksGetConsent().account(new HrefType(ACCOUNTS)));
        }
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<ConsentStatusResponse200> getConsentStatus(String consentId, String consentCategory,
            UUID requestId) {
        requireAccountAccess(consentCategory);
        return ResponseEntity.ok(new ConsentStatusResponse200(contractStatus(consents.get(consentId, provider.id()))));
    }

    @Override
    public ResponseEntity<Void> deleteConsent(String consentCategory, String consentId, UUID requestId) {
        requireAccountAccess(consentCategory);
        consents.terminate(consentId, provider.id());
        return ResponseEntity.noContent().build();
    }

    // Account access is the only consent category this bank keeps, so a consent of another one is unknown.
    private static void requireAccountAccess(String consentCategory) {
        if (!ACCOUNT_ACCESS.equals(consentCategory)) {
            throw new ApiException(ErrorCode.CONSENT_UNKNOWN, ErrorCode.CONSENT_UNKNOWN.text());
        }
    }

    // The account as the provider named it, its rights, and once the consent is valid the links to its data.
    private static AccountAccessRightsExtended accessOf(ConsentAccount account, boolean valid) {
        List<AccessRightsCodes> rights = new ArrayList<>();
        for (AccessRight right : AccessRight.values()) {
            if (account.allows(right)) {
                rights.add(AccessRightsCodes.fromValue(right.code()));
            }
        }
        AccountAccessRightsExtended access = new AccountAccessRightsExtended(rights)
                .account(new AccountReference().iban(account.iban()).currency(account.currency()));
        if (valid) {
            String path = ACCOUNTS + "/" + account.resourceId();
            LinksAll links = new LinksAll();
            if (account.allows(AccessRight.ACCOUNT_DETAILS)) {
                links.account(new HrefType(path));
            }
            if (account.allows(AccessRight.BALANCES)) {
                links.balances(new HrefType(path + "/balances"));
            }
            access.links(links);
        }
        return access;
    }

    private dev.tkachuk.openbanking.api.model.ConsentStatus contractStatus(Consent consent) {
        return dev.tkachuk.openbanking.api.model.ConsentStatus.fromValue(consents.statusOf(consent).code());
    }
}
