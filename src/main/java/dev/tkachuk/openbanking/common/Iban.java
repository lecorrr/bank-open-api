package dev.tkachuk.openbanking.common;

import java.util.regex.Pattern;

/** The IBAN check digits rule (ISO 13616): letters become numbers, and the whole must leave 1 modulo 97. */
public final class Iban {

    // 15 to 34 characters: Norway has the shortest IBANs, and bank-core takes the same lengths.
    private static final Pattern FORM = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");
    // UA, the check digits, the bank's six-digit code and a 19-digit account number: 29 characters.
    private static final Pattern UKRAINIAN = Pattern.compile("UA[0-9]{27}");

    private Iban() {
    }

    public static boolean isValid(String iban) {
        if (!FORM.matcher(iban).matches()) {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        int remainder = 0;
        for (char c : rearranged.toCharArray()) {
            int value = Character.isDigit(c) ? c - '0' : c - 'A' + 10;
            remainder = (value > 9 ? remainder * 100 : remainder * 10) + value;
            remainder %= 97;
        }
        return remainder == 1;
    }

    /** Whether the IBAN has the form of an account at a bank in Ukraine; its check digits are isValid's to test. */
    public static boolean isUkrainian(String iban) {
        return UKRAINIAN.matcher(iban).matches();
    }

    /** The code of the bank (its MFO) in a Ukrainian IBAN: the six digits after the check digits. */
    public static String ukrainianBankCode(String iban) {
        if (!isUkrainian(iban)) {
            throw new IllegalArgumentException("Not a Ukrainian IBAN");
        }
        return iban.substring(4, 10);
    }
}
