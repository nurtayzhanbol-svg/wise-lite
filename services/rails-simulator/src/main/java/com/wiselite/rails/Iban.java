package com.wiselite.rails;

import java.math.BigInteger;

/** ISO 13616 IBAN check: move the first 4 chars to the end, letters to numbers, mod 97 == 1. */
public final class Iban {

    private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

    private Iban() {}

    public static boolean isValid(String raw) {
        if (raw == null) {
            return false;
        }
        var iban = raw.replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        if (iban.length() < 15 || iban.length() > 34 || !iban.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]+")) {
            return false;
        }
        var rearranged = iban.substring(4) + iban.substring(0, 4);
        var digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new BigInteger(digits.toString()).mod(NINETY_SEVEN).intValue() == 1;
    }
}
