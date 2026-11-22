package dev.tkachuk.openbanking.bankcore;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** An account from bank-core's GET /api/v1/accounts, with only the fields this service passes on. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BankCoreAccount(String iban, String currency) {
}
