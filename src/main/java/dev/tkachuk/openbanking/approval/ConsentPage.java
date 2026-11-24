package dev.tkachuk.openbanking.approval;

import dev.tkachuk.openbanking.consents.CustomerConsent;
import java.util.List;
import java.util.Objects;

/** A consent on the customer's page, with each account marked as theirs or not. */
public record ConsentPage(CustomerConsent consent, List<AccountOnPage> accounts) {

    public ConsentPage {
        Objects.requireNonNull(consent, "consent");
        accounts = List.copyOf(accounts);
    }

    /** The customer may approve only a consent still waiting for them whose accounts all are theirs. */
    public boolean approvable() {
        return consent.awaitingDecision() && accounts.stream().allMatch(AccountOnPage::held);
    }

    /** What became of the consent, in the customer's words, from the contract's status codes. */
    public String outcome() {
        return switch (consent.status()) {
            case "received" -> "waiting for your decision";
            case "valid" -> "approved";
            case "rejected" -> "rejected";
            case "revokedByPsu" -> "withdrawn";
            case "terminatedByTpp" -> "ended by the provider";
            case "expired" -> "expired";
            default -> throw new IllegalStateException("Unknown consent status " + consent.status());
        };
    }
}
