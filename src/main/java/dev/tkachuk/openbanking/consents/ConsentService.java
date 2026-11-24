package dev.tkachuk.openbanking.consents;

import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and ends consents, and decides whether a consent allows a read of account data. Each call names the
 * provider the request comes from: a consent exists only for the provider that asked for it.
 */
@Service
public class ConsentService {

    private static final Logger log = LoggerFactory.getLogger(ConsentService.class);
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
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
        if (!repository.updateStatus(consent, statusAsRead)) {
            throw new ApiException(ErrorCode.STATUS_INVALID,
                    "The consent changed at the same time; read its status and try again");
        }
        log.info("Consent {} terminated by the provider", consent.id());
    }

    /** The accounts a provider may list under the consent in its Consent-ID header. */
    public List<ConsentAccount> accountsToList(String consentId, String providerId, boolean customerPresent) {
        Consent consent = load(consentId, providerId, ErrorCode.CONSENT_ID_UNKNOWN);
        List<ConsentAccount> accounts = consent.accountsToList(today());
        countRead(consent, ACCOUNT_LIST, customerPresent);
        return accounts;
    }

    /** The account behind a resource id, if the consent lets the provider read this data of it today. */
    public ConsentAccount accountToRead(String consentId, String providerId, String resourceId, AccessRight right,
            boolean customerPresent) {
        Consent consent = load(consentId, providerId, ErrorCode.CONSENT_ID_UNKNOWN);
        ConsentAccount account = consent.accountToRead(resourceId, right, today());
        countRead(consent, right.code() + ":" + account.resourceId(), customerPresent);
        return account;
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
        if (!UUID_TEXT.matcher(consentId).matches()) {
            throw new ApiException(whenUnknown, whenUnknown.text());
        }
        Consent consent = repository.findById(UUID.fromString(consentId))
                .orElseThrow(() -> new ApiException(whenUnknown, whenUnknown.text()));
        if (!consent.belongsTo(providerId)) {
            // Answered as unknown, so a provider cannot even learn that another provider's consent exists.
            log.warn("Provider {} asked for consent {}, which is not its own", providerId, consent.id());
            throw new ApiException(whenUnknown, whenUnknown.text());
        }
        return consent;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
