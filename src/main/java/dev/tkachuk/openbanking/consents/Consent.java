package dev.tkachuk.openbanking.consents;

import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A customer's consent for one provider to read named accounts: which data, until which day and how often a day
 * without the customer. The rules for creating it, its status changes and the reads it allows live here.
 */
final class Consent {

    /** The contract's limit, "if not otherwise agreed bilaterally between TPP and ASPSP". */
    static final int MAX_FREQUENCY_PER_DAY = 4;

    private final UUID id;
    private final @Nullable String providerId;
    private final List<ConsentAccount> accounts;
    private final boolean recurring;
    private final LocalDate validTo;
    private final int frequencyPerDay;
    private final @Nullable URI tppRedirectUri;
    private final Instant createdAt;
    private ConsentStatus status;
    private Instant statusChangedAt;

    /** providerId is null only for consents made before providers signed in (0.2.x); no provider owns those. */
    Consent(UUID id, @Nullable String providerId, ConsentStatus status, List<ConsentAccount> accounts,
            boolean recurring, LocalDate validTo, int frequencyPerDay, @Nullable URI tppRedirectUri, Instant createdAt,
            Instant statusChangedAt) {
        if (status == ConsentStatus.EXPIRED) {
            throw new IllegalArgumentException("Expiry follows from validTo and is never stored");
        }
        this.id = Objects.requireNonNull(id, "id");
        this.providerId = providerId;
        this.status = status;
        this.accounts = List.copyOf(accounts);
        this.recurring = recurring;
        this.validTo = Objects.requireNonNull(validTo, "validTo");
        this.frequencyPerDay = frequencyPerDay;
        this.tppRedirectUri = tppRedirectUri;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.statusChangedAt = Objects.requireNonNull(statusChangedAt, "statusChangedAt");
    }

    /**
     * Checks a provider's request against the rules and makes a received consent of it. A validTo later than the
     * bank allows is cut back to its last allowed day, as the contract foresees.
     */
    static Consent create(UUID id, String providerId, NewConsent request, LocalDate today, int maxValidityDays,
            Supplier<UUID> newResourceId, Instant now) {
        Objects.requireNonNull(providerId, "providerId");
        if (request.accounts().isEmpty()) {
            throw formatError("access.payments must name at least one account");
        }
        if (request.frequencyPerDay() < 1 || request.frequencyPerDay() > MAX_FREQUENCY_PER_DAY) {
            throw formatError("frequencyPerDay must be 1 to " + MAX_FREQUENCY_PER_DAY);
        }
        if (!request.recurring() && request.frequencyPerDay() != 1) {
            throw formatError("frequencyPerDay must be 1 for a one-off consent");
        }
        if (request.validTo().isBefore(today)) {
            throw formatError("validTo is in the past");
        }
        LocalDate lastAllowedDay = today.plusDays(maxValidityDays);
        LocalDate validTo = request.validTo().isAfter(lastAllowedDay) ? lastAllowedDay : request.validTo();

        List<ConsentAccount> accounts = new ArrayList<>();
        Set<String> ibans = new HashSet<>();
        for (int i = 0; i < request.accounts().size(); i++) {
            NewConsent.RequestedAccount account = request.accounts().get(i);
            String field = "access.payments[" + i + "]";
            String iban = account.iban().toUpperCase(Locale.ROOT);
            if (!Iban.isValid(iban)) {
                throw formatError(field + ".account.iban is not a valid IBAN");
            }
            if (!ibans.add(iban)) {
                throw formatError(field + " names an account a second time");
            }
            if (account.rights().isEmpty()) {
                throw formatError(field + ".rights must name at least one right");
            }
            accounts.add(new ConsentAccount(newResourceId.get(), iban, account.currency(), account.rights()));
        }
        return new Consent(id, providerId, ConsentStatus.RECEIVED, accounts, request.recurring(), validTo,
                request.frequencyPerDay(), request.tppRedirectUri(), now, now);
    }

    /** Whether the provider asked for this consent; for any other provider it does not exist. */
    boolean belongsTo(String provider) {
        return provider.equals(providerId);
    }

    /** The status as the provider sees it on the given day: received and valid consents expire after validTo. */
    ConsentStatus statusOn(LocalDate today) {
        return !status.isFinal() && today.isAfter(validTo) ? ConsentStatus.EXPIRED : status;
    }

    /** The provider ends the consent. False when it had ended already, which leaves its status as it was. */
    boolean terminateByTpp(LocalDate today, Instant now) {
        if (statusOn(today).isFinal()) {
            return false;
        }
        changeTo(ConsentStatus.TERMINATED_BY_TPP, now);
        return true;
    }

    /** The customer approves the consent; from then on it allows the reads it names. */
    void approve(LocalDate today, Instant now) {
        requireReceived(today);
        changeTo(ConsentStatus.VALID, now);
    }

    /** The customer refuses the consent. */
    void reject(LocalDate today, Instant now) {
        requireReceived(today);
        changeTo(ConsentStatus.REJECTED, now);
    }

    /** The accounts a valid consent lists for the provider. */
    List<ConsentAccount> accountsToList(LocalDate today) {
        requireUsable(today);
        return accounts;
    }

    /** The account with the given resource id, if the consent is valid and lets the provider read this data of it. */
    ConsentAccount accountToRead(String resourceId, AccessRight right, LocalDate today) {
        requireUsable(today);
        ConsentAccount account = accounts.stream()
                .filter(candidate -> candidate.resourceId().toString().equals(resourceId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_UNKNOWN, ErrorCode.RESOURCE_UNKNOWN.text()));
        if (!account.allows(right)) {
            throw new ApiException(ErrorCode.CONSENT_INVALID,
                    "The consent does not include " + right.code() + " for this account");
        }
        return account;
    }

    private void requireUsable(LocalDate today) {
        ConsentStatus current = statusOn(today);
        if (current == ConsentStatus.EXPIRED) {
            throw new ApiException(ErrorCode.CONSENT_EXPIRED, "The consent was valid to " + validTo);
        }
        if (current != ConsentStatus.VALID) {
            throw new ApiException(ErrorCode.CONSENT_INVALID, "The consent is " + current.code() + ", not valid");
        }
    }

    private void requireReceived(LocalDate today) {
        ConsentStatus current = statusOn(today);
        if (current != ConsentStatus.RECEIVED) {
            throw new ApiException(ErrorCode.STATUS_INVALID,
                    "The consent is " + current.code() + "; only a received consent can be approved or rejected");
        }
    }

    private void changeTo(ConsentStatus next, Instant now) {
        status = next;
        statusChangedAt = now;
    }

    private static ApiException formatError(String detail) {
        return new ApiException(ErrorCode.FORMAT_ERROR, detail);
    }

    UUID id() {
        return id;
    }

    @Nullable String providerId() {
        return providerId;
    }

    /** The status as stored, without expiry; statusOn is what the provider sees. */
    ConsentStatus storedStatus() {
        return status;
    }

    List<ConsentAccount> accounts() {
        return accounts;
    }

    boolean recurring() {
        return recurring;
    }

    LocalDate validTo() {
        return validTo;
    }

    int frequencyPerDay() {
        return frequencyPerDay;
    }

    @Nullable URI tppRedirectUri() {
        return tppRedirectUri;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant statusChangedAt() {
        return statusChangedAt;
    }
}
