package dev.tkachuk.openbanking.consents;

import java.util.regex.Pattern;

/** The IBAN check digits rule (ISO 13616): letters become numbers, and the whole must leave 1 modulo 97. */
final class Iban {

    // 15 to 34 characters: Norway has the shortest IBANs, and bank-core takes the same lengths.
    private static final Pattern FORM = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");

    private Iban() {
    }

    static boolean isValid(String iban) {
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
}
