package dev.tkachuk.provider.bank;

/**
 * A credit transfer in hryvnias the customer asks the provider to start at the bank.
 *
 * @param amount the amount as the API takes it, such as 1.00
 */
public record PaymentOrder(String debtorIban, String creditorIban, String creditorName, String amount,
        String remittance) {
}
