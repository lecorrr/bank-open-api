package dev.tkachuk.openbanking.bankcore;

/** bank-core refused a transfer for what it says, so no money moved, and the same transfer would be refused again. */
public class TransferRefusedException extends RuntimeException {

    private final String code;

    public TransferRefusedException(String code) {
        super("bank-core refused the transfer with " + code);
        this.code = code;
    }

    /** bank-core's error code, such as INSUFFICIENT_FUNDS or ACCOUNT_NOT_FOUND. */
    public String code() {
        return code;
    }

    public boolean insufficientFunds() {
        return "INSUFFICIENT_FUNDS".equals(code);
    }
}
