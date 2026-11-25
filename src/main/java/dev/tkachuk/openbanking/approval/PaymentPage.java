package dev.tkachuk.openbanking.approval;

import dev.tkachuk.openbanking.payments.CustomerPayment;
import java.util.Objects;

/** A payment on the customer's page, and what the customer is told about it in their words. */
public record PaymentPage(CustomerPayment payment) {

    private static final String INSUFFICIENT_FUNDS = "AM04";

    public PaymentPage {
        Objects.requireNonNull(payment, "payment");
    }

    /** The customer may approve only a payment still waiting for them, from an account of theirs. */
    public boolean approvable() {
        return payment.awaitingDecision() && payment.debtorAccountHeld();
    }

    /** What became of the payment, from the contract's status codes. */
    public String outcome() {
        return switch (payment.status()) {
            case "RCVD" -> "waiting for your decision";
            case "CANC" -> "cancelled by the provider";
            case "RJCT" -> payment.reasonCode() == null ? "rejected" : "refused by the bank";
            case "ACTC" -> "approved, and the bank is making it";
            case "ACCC" -> "paid";
            case "ACSP" -> "sent to the payee's bank";
            case "ACSC" -> "accepted by the payee's bank";
            default -> throw new IllegalStateException("Unknown payment status " + payment.status());
        };
    }

    /** The title of the page a customer sees after deciding, when no provider waits for them. */
    public String decidedTitle() {
        return switch (payment.status()) {
            case "RJCT" -> payment.reasonCode() == null ? "Payment rejected" : "Payment refused";
            case "ACTC" -> "Payment approved";
            case "ACSP" -> "Payment sent";
            case "ACCC", "ACSC" -> "Payment made";
            default -> throw new IllegalStateException("No decision leaves a payment " + payment.status());
        };
    }

    /** What a customer is told after deciding. */
    public String decidedText() {
        return switch (payment.status()) {
            case "RJCT" -> rejectedText();
            case "ACTC" -> "The bank is making the payment; the app that sent you here shows when it is done.";
            case "ACSP" -> "The money is on its way to the payee's bank. You can close this page.";
            case "ACCC" -> "The money is on the payee's account. You can close this page.";
            case "ACSC" -> "The payee's bank has accepted the payment. You can close this page.";
            default -> throw new IllegalStateException("No decision leaves a payment " + payment.status());
        };
    }

    private String rejectedText() {
        if (payment.reasonCode() == null) {
            return "The payment will not be made. You can close this page.";
        }
        return INSUFFICIENT_FUNDS.equals(payment.reasonCode())
                ? "The account does not have enough money for this payment. Nothing was paid."
                : "The bank could not make this payment. Nothing was paid.";
    }
}
