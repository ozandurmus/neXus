package com.securityexpert.nexus.ui2.packetanalysis.numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * CPython 3.12 {@code round()} ({@code float.__round__}, {@code int.__round__}).
 *
 * <p>Rounding is half-to-even on the <em>exact binary value</em>: {@code round(2.675, 2)} is {@code 2.67} because the
 * double nearest 2.675 is slightly below it, and {@code round(0.125, 2)} is {@code 0.12} (a true tie). Java's
 * {@link Math#round} rounds half up and {@code BigDecimal.valueOf(x)} starts from the shortest decimal string, so
 * neither is a substitute.
 */
public final class PyRound {

    /** {@code (DBL_MANT_DIG - DBL_MIN_EXP) * 0.30103}: for more digits a float always rounds to itself. */
    static final int NDIGITS_MAX = 323;
    /** {@code -(DBL_MAX_EXP + 1) * 0.30103}: for fewer digits a float always rounds to a signed zero. */
    static final int NDIGITS_MIN = -308;

    private PyRound() {
    }

    /** {@code round(x)} for a float: an int, ties to even; NaN raises ValueError, infinities OverflowError. */
    public static long round(double x) {
        return PyMath.toLong(Math.rint(x));
    }

    /** {@code round(x, ndigits)} for a float: a float; NaN and infinities round to themselves. */
    public static double round(double x, int ndigits) {
        if (!Double.isFinite(x)) {
            return x;
        }
        if (ndigits > NDIGITS_MAX) {
            return x;
        }
        if (ndigits < NDIGITS_MIN) {
            return 0.0 * x;
        }
        // double_round: dtoa mode 3 (correctly rounded, half-even) to a decimal string, then strtod back.
        double rounded = new BigDecimal(x).setScale(ndigits, RoundingMode.HALF_EVEN).doubleValue();
        if (Double.isInfinite(rounded)) {
            throw new ArithmeticException("rounded value too large to represent");
        }
        // The decimal string keeps the sign ("-0e-2"), so a value rounding to zero keeps the sign of x.
        return rounded == 0.0 ? Math.copySign(0.0, x) : rounded;
    }

    /** {@code round(i, ndigits)} for an int: unchanged for ndigits >= 0, else half-even to a multiple of 10^-ndigits. */
    public static long round(long i, int ndigits) {
        if (ndigits >= 0) {
            return i;
        }
        BigDecimal r = new BigDecimal(BigInteger.valueOf(i)).setScale(ndigits, RoundingMode.HALF_EVEN);
        try {
            return r.toBigIntegerExact().longValueExact();
        } catch (ArithmeticException e) {
            throw new ArithmeticException("int result wider than 64 bits");
        }
    }
}
