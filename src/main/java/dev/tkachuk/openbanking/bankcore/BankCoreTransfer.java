package dev.tkachuk.openbanking.bankcore;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/** A transfer from bank-core's answers, with only the fields this service uses. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BankCoreTransfer(UUID id, Status status) {

    /** COMPLETED within this bank at once; to another bank PENDING until that bank accepts or refuses it. */
    public enum Status {
        PENDING, COMPLETED, REJECTED
    }
}
