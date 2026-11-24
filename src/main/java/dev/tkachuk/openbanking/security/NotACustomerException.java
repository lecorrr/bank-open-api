package dev.tkachuk.openbanking.security;

/** The person signed in on the bank's pages is not one of its customers, or nobody is signed in. */
public class NotACustomerException extends RuntimeException {

    NotACustomerException(String message) {
        super(message);
    }
}
