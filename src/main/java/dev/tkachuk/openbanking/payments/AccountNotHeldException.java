package dev.tkachuk.openbanking.payments;

/** The signed-in customer does not hold the account a payment is to be made from, so they cannot approve it. */
public class AccountNotHeldException extends RuntimeException {

    public AccountNotHeldException(String message) {
        super(message);
    }
}
