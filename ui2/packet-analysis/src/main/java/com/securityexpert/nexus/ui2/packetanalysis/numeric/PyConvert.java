package com.securityexpert.nexus.ui2.packetanalysis.numeric;

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * CPython 3.12 {@code float(str)}, {@code int(str[, base])} and {@code int(float)}.
 *
 * <p>{@link Double#parseDouble} is correctly rounded like CPython but accepts a different language (hex floats,
 * {@code 1d}, {@code 1f}, only {@code Infinity}/{@code NaN} spelled exactly), and {@link Long#parseLong} rejects
 * Python's {@code 0x} prefixes, underscores and surrounding whitespace. Both are wrapped here behind Python's
 * grammar. ASCII only: CPython also accepts non-ASCII Unicode digits and spaces, which tshark never prints.
 */
public final class PyConvert {

    private static final Pattern DECIMAL = Pattern.compile(
            "[+-]?(?:\\d(?:_?\\d)*(?:\\.(?:\\d(?:_?\\d)*)?)?|\\.\\d(?:_?\\d)*)(?:[eE][+-]?\\d(?:_?\\d)*)?");
    private static final Pattern SPECIAL = Pattern.compile("[+-]?(?:inf|infinity|nan)", Pattern.CASE_INSENSITIVE);

    private PyConvert() {
    }

    /** {@code float(s)}; a malformed string raises ValueError. Out-of-range values become inf or 0.0, no error. */
    public static double parseFloat(String s) {
        String t = strip(s);
        if (SPECIAL.matcher(t).matches()) {
            boolean negative = t.charAt(0) == '-';
            String word = t.replaceFirst("^[+-]", "").toLowerCase(Locale.ROOT);
            if (word.equals("nan")) {
                // CPython builds -nan with the sign bit set (copysign(nan, -1.0)).
                return negative ? Double.longBitsToDouble(0xfff8000000000000L) : Double.NaN;
            }
            return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (!DECIMAL.matcher(t).matches()) {
            throw new IllegalArgumentException("could not convert string to float: '" + s + "'");
        }
        return Double.parseDouble(t.replace("_", ""));
    }

    /** {@code int(s)}. */
    public static long parseInt(String s) {
        return parseInt(s, 10);
    }

    /** {@code int(s, base)} with base 0 (prefix-detected) or 2..36. */
    public static long parseInt(String s, int base) {
        if (base != 0 && (base < 2 || base > 36)) {
            throw new IllegalArgumentException("int() base must be >= 2 and <= 36, or 0");
        }
        String t = strip(s);
        int i = 0;
        boolean negative = false;
        if (i < t.length() && (t.charAt(i) == '+' || t.charAt(i) == '-')) {
            negative = t.charAt(i) == '-';
            i++;
        }
        boolean prefixed = false;
        if (i + 1 < t.length() && t.charAt(i) == '0') {
            char p = Character.toLowerCase(t.charAt(i + 1));
            int prefixBase = p == 'x' ? 16 : p == 'o' ? 8 : p == 'b' ? 2 : -1;
            if (prefixBase > 0 && (base == 0 || base == prefixBase)) {
                base = prefixBase;
                i += 2;
                prefixed = true;
            }
        }
        boolean leadingZeroRule = base == 0;
        if (base == 0) {
            base = 10;
        }
        String body = t.substring(i);
        // After a prefix one underscore may precede the first digit ("0x_1f").
        if (prefixed && body.startsWith("_")) {
            body = body.substring(1);
        }
        if (body.isEmpty() || body.startsWith("_") || body.endsWith("_") || body.contains("__")) {
            throw invalid(s, base);
        }
        String digits = body.replace("_", "");
        for (int k = 0; k < digits.length(); k++) {
            if (Character.digit(digits.charAt(k), base) < 0 || digits.charAt(k) > 0x7f) {
                throw invalid(s, base);
            }
        }
        if (leadingZeroRule && !prefixed && digits.length() > 1 && digits.charAt(0) == '0'
                && !digits.chars().allMatch(c -> c == '0')) {
            throw invalid(s, 0);
        }
        BigInteger v = new BigInteger(digits, base);
        if (negative) {
            v = v.negate();
        }
        try {
            return v.longValueExact();
        } catch (ArithmeticException e) {
            throw new ArithmeticException("int result wider than 64 bits");
        }
    }

    /** {@code int(x)} for a float: truncation toward zero; NaN raises ValueError, infinities OverflowError. */
    public static long toInt(double x) {
        return PyMath.trunc(x);
    }

    private static IllegalArgumentException invalid(String s, int base) {
        return new IllegalArgumentException("invalid literal for int() with base " + base + ": '" + s + "'");
    }

    /** {@code str.strip()} for the ASCII whitespace CPython recognises (including \x1c-\x1f). */
    private static String strip(String s) {
        int lo = 0;
        int hi = s.length();
        while (lo < hi && isSpace(s.charAt(lo))) {
            lo++;
        }
        while (hi > lo && isSpace(s.charAt(hi - 1))) {
            hi--;
        }
        return s.substring(lo, hi);
    }

    private static boolean isSpace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r') || (c >= 0x1c && c <= 0x1f);
    }
}
