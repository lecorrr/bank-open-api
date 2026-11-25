package dev.tkachuk.openbanking.consents;

import dev.tkachuk.openbanking.common.ResourceIds;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and ends consents, and decides whether a consent allows a read of account data. A provider's call names
 * the provider the request comes from: a consent exists only for the provider that asked for it. A customer's call
 * names the customer: they decide on a consent once, and later see and withdraw only those they approved.
 */
@Service
public class ConsentService {

    private static final Logger log = LoggerFactory.getLogger(ConsentService.class);
    private static final String ACCOUNT_LIST = "accounts";

    private final ConsentRepository repository;
    private final ConsentProperties properties;
    private final Clock clock;

    ConsentService(ConsentRepository repository, ConsentProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    // Package-private is enough: Spring 6 and later also apply @Transactional to non-public methods of
    // class-based proxies.
    @Transactional
    Consent create(NewConsent request, String providerId) {
        Consent consent = Consent.create(UUID.randomUUID(), providerId, request, today(),
                properties.maxValidityDays(), UUID::randomUUID, clock.instant());
        repository.insert(consent);
        log.info("Consent {} received from provider {}, accounts: {}, valid to {}",
                consent.id(), providerId, consent.accounts().size(), consent.validTo());
        return consent;
    }

    /** The consent a path names; an unknown one is answered with 403 CONSENT_UNKNOWN. */
    Consent get(String consentId, String providerId) {
        return load(consentId, providerId, ErrorCode.CONSENT_UNKNOWN);
    }

    ConsentStatus statusOf(Consent consent) {
        return consent.statusOn(today());
    }

    void terminate(String consentId, String providerId) {
        Consent consent = get(consentId, providerId);
        ConsentStatus statusAsRead = consent.storedStatus();
        if (!consent.terminateByTpp(today(), clock.instant())) {
            log.info("Consent {} had ended already, as {}", consent.id(), statusOf(consent).code());
            return;
        }
        save(consent, statusAsRead);
        log.info("Consent {} terminated by the provider", consent.id());
    }

    /** The accounts a provider may list under the consent in its Consent-ID header. */
    public ReadableAccounts accountsToList(String consentId, String providerId, boolean customerPresent) {
        Consent consent = load(consentId, providerId, ErrorCode.CONSENT_ID_UNKNOWN);
        List<ConsentAccount> accounts = consent.accountsToList(today());
        countRead(consent, ACCOUNT_LIST, customerPresent);
        return new ReadableAccounts(consent.customerId(), accounts);
    }

    /** The account behind a resource id, if the consent lets the provider read this data of it today. */
    public ReadableAccount accountToRead(String consentId, String providerId, String resourceId, AccessRight right,
            boolean customerPresent) {
        Consent consent = load(consentId, providerId, ErrorCode.CONSENT_ID_UNKNOWN);
        ConsentAccount account = consent.accountToRead(resourceId, right, today());
        countRead(consent, right.code() + ":" + account.resourceId(), customerPresent);
        return new ReadableAccount(consent.customerId(), account);
    }

    /**
     * A consent as any signed-in customer sees it from the link the provider sent them. Once decided, only the
     * customer who decided sees what it names; anyone else learns no more than that it is decided.
     */
    public CustomerConsent forCustomer(String consentId, String customerId) {
        Consent consent = find(consentId);
        CustomerConsent view = toView(consent);
        if (view.awaitingDecision() || consent.decidedBy(customerId)) {
            return view;
        }
        return new CustomerConsent(consent.id(), null, view.status(), false, List.of(), false, consent.validTo(),
                consent.frequencyPerDay(), null);
    }

    /** The customer approves; whether they hold every account it names is for the caller to check first. */
    @Transactional
    public CustomerConsent approve(String consentId, String customerId) {
        Consent consent = find(consentId);
        ConsentStatus statusAsRead = consent.storedStatus();
        consent.approve(customerId, today(), clock.instant());
        save(consent, statusAsRead);
        log.info("Consent {} approved by customer {} for provider {}", consent.id(), customerId,
                consent.providerId());
        return toView(consent);
    }

    /** The customer refuses the consent. */
    @Transactional
    public CustomerConsent reject(String consentId, String customerId) {
        Consent consent = find(consentId);
        ConsentStatus statusAsRead = consent.storedStatus();
        consent.reject(customerId, today(), clock.instant());
        save(consent, statusAsRead);
        log.info("Consent {} rejected by customer {}", consent.id(), customerId);
        return toView(consent);
    }

    /** The consents this customer approved that are still valid. */
    public List<CustomerConsent> validConsentsOf(String customerId) {
        return repository.findValidOf(customerId, today()).stream().map(this::toView).toList();
    }

    /** The customer withdraws a consent they approved; another customer's consent is unknown to them. */
    @Transactional
    public void revokeByCustomer(String consentId, String customerId) {
        Consent consent = find(consentId);
        if (!consent.decidedBy(customerId)) {
            throw new ApiException(ErrorCode.CONSENT_UNKNOWN, ErrorCode.CONSENT_UNKNOWN.text());
        }
        ConsentStatus statusAsRead = consent.storedStatus();
        consent.revokeByCustomer(customerId, today(), clock.instant());
        save(consent, statusAsRead);
        log.info("Consent {} withdrawn by customer {}", consent.id(), customerId);
    }

    // The contract limits reads without the customer (no PSU-IP-Address) to frequencyPerDay a day for each data
    // read; reads the customer starts are not counted.
    private void countRead(Consent consent, String target, boolean customerPresent) {
        if (!customerPresent && !repository.countRead(consent.id(), target, today(), consent.frequencyPerDay())) {
            throw new ApiException(ErrorCode.ACCESS_EXCEEDED, "The consent allows " + consent.frequencyPerDay()
                    + " reads a day of this data without the customer");
        }
    }

    private Consent load(String consentId, String providerId, ErrorCode whenUnknown) {
        Consent consent = find(consentId, whenUnknown);
        if (!consent.belongsTo(providerId)) {
            // Answered as unknown, so a provider cannot even learn that another provider's consent exists.
            log.warn("Provider {} asked for consent {}, which is not its own", providerId, consent.id());
            throw new ApiException(whenUnknown, whenUnknown.text());
        }
        return consent;
    }

    private Consent find(String consentId) {
        return find(consentId, ErrorCode.CONSENT_UNKNOWN);
    }

    private Consent find(String consentId, ErrorCode whenUnknown) {
        return ResourceIds.parse(consentId)
                .flatMap(repository::findById)
                .orElseThrow(() -> new ApiException(whenUnknown, whenUnknown.text()));
    }

    private void save(Consent consent, ConsentStatus statusAsRead) {
        if (!repository.updateStatus(consent, statusAsRead)) {
            throw new ApiException(ErrorCode.STATUS_INVALID,
                    "The consent changed at the same time; read its status and try again");
        }
    }

    private CustomerConsent toView(Consent consent) {
        ConsentStatus status = statusOf(consent);
        return new CustomerConsent(consent.id(), consent.providerId(), status.code(),
                status == ConsentStatus.RECEIVED, consent.accounts(), consent.recurring(), consent.validTo(),
                consent.frequencyPerDay(), consent.tppRedirectUri());
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
