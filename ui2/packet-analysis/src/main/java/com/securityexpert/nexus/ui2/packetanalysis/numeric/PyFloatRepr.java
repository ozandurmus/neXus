package com.securityexpert.nexus.ui2.packetanalysis.numeric;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * CPython {@code repr(float)} / {@code str(float)} and the {@code json.dumps} text of a float.
 *
 * <p>Python prints the shortest decimal that round-trips (closest to the exact value among the shortest, dtoa mode
 * 0) and lays it out as {@code 100.0}, {@code 0.0001}, {@code 1e-05}, {@code 1e+16}, {@code 1.5e+300}: positional
 * for decimal exponents -4..15, otherwise scientific with a signed, at least two-digit exponent and no {@code .0}.
 * Java's {@link Double#toString} differs in layout ({@code 1.0E-5}) and, when one digit would do, may pick a closer
 * two-digit decimal ({@code 4.9E-324} where Python prints {@code 5e-324}).
 */
public final class PyFloatRepr {

    private PyFloatRepr() {
    }

    /** {@code repr(x)}. */
    public static String repr(double x) {
        if (Double.isNaN(x)) {
            return "nan";
        }
        if (Double.isInfinite(x)) {
            return x > 0 ? "inf" : "-inf";
        }
        if (x == 0.0) {
            return (Double.doubleToRawLongBits(x) < 0) ? "-0.0" : "0.0";
        }
        Decimal d = shortest(x);
        return layout(x < 0, d.digits, d.decpt, 16, true);
    }

    /** {@code json.dumps(x)} for a float with the default {@code allow_nan=True}: NaN, Infinity, -Infinity. */
    public static String json(double x) {
        if (Double.isNaN(x)) {
            return "NaN";
        }
        if (Double.isInfinite(x)) {
            return x > 0 ? "Infinity" : "-Infinity";
        }
        return repr(x);
    }

    /** {@code json.dumps(x, allow_nan=False)}: non-finite values raise ValueError. */
    public static String jsonStrict(double x) {
        if (!Double.isFinite(x)) {
            throw new IllegalArgumentException("Out of range float values are not JSON compliant");
        }
        return repr(x);
    }

    /** Significant digits (no leading/trailing zeros) and decpt, where value = 0.DIGITS * 10^decpt. */
    record Decimal(String digits, int decpt) {
    }

    /** The dtoa mode 0 result for a finite, non-zero x (sign ignored). */
    static Decimal shortest(double x) {
        double ax = Math.abs(x);
        // JDK 19+ Double.toString is the shortest round-tripping decimal (closest, ties to even) whenever that has
        // at least two digits; when one digit suffices it may return a closer two-digit decimal instead.
        Decimal java = fromBigDecimal(new BigDecimal(Double.toString(ax)));
        if (java.digits.length() > 2) {
            return java;
        }
        Decimal one = oneDigit(ax);
        return one != null ? one : java;
    }

    /** The closest one-significant-digit decimal that reads back as ax, or null if none does. */
    private static Decimal oneDigit(double ax) {
        BigDecimal exact = new BigDecimal(ax);
        // The one-digit decimals just below and just above the exact value; any farther one that reads back as
        // ax would imply the nearer one on its side does too (the rounding interval is convex).
        BigDecimal down = exact.round(new MathContext(1, RoundingMode.FLOOR));
        BigDecimal up = exact.round(new MathContext(1, RoundingMode.CEILING));
        BigDecimal best = null;
        for (BigDecimal candidate : new BigDecimal[] {down, up}) {
            if (candidate.signum() <= 0) {
                continue;
            }
            if (Double.parseDouble(candidate.toString()) != ax) {
                continue;
            }
            if (best == null) {
                best = candidate;
                continue;
            }
            int cmp = candidate.subtract(exact).abs().compareTo(best.subtract(exact).abs());
            if (cmp < 0 || (cmp == 0 && lastDigitEven(candidate))) {
                best = candidate;
            }
        }
        return best == null ? null : fromBigDecimal(best);
    }

    private static boolean lastDigitEven(BigDecimal d) {
        return d.stripTrailingZeros().unscaledValue().testBit(0) == false;
    }

    static Decimal fromBigDecimal(BigDecimal value) {
        BigDecimal s = value.stripTrailingZeros();
        String digits = s.unscaledValue().abs().toString();
        return new Decimal(digits, digits.length() - s.scale());
    }

    /**
     * PyOS_double_to_string layout: scientific when {@code decpt <= -4 || decpt > expThreshold}, else positional;
     * {@code addDot0} appends ".0" to an integral positional value.
     */
    static String layout(boolean negative, String digits, int decpt, int expThreshold, boolean addDot0) {
        StringBuilder sb = new StringBuilder(digits.length() + 8);
        if (negative) {
            sb.append('-');
        }
        if (decpt <= -4 || decpt > expThreshold) {
            sb.append(digits.charAt(0));
            if (digits.length() > 1) {
                sb.append('.').append(digits, 1, digits.length());
            }
            appendExponent(sb, decpt - 1);
        } else if (decpt <= 0) {
            sb.append("0.");
            sb.append("0".repeat(-decpt));
            sb.append(digits);
        } else if (decpt >= digits.length()) {
            sb.append(digits);
            sb.append("0".repeat(decpt - digits.length()));
            if (addDot0) {
                sb.append(".0");
            }
        } else {
            sb.append(digits, 0, decpt).append('.').append(digits, decpt, digits.length());
        }
        return sb.toString();
    }

    static void appendExponent(StringBuilder sb, int exp) {
        sb.append('e').append(exp < 0 ? '-' : '+');
        int a = Math.abs(exp);
        if (a < 10) {
            sb.append('0');
        }
        sb.append(a);
    }
}
