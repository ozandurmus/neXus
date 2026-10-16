package com.securityexpert.nexus.ui2.packetanalysis.numeric;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * CPython 3.12 integer/float division, modulo, floor/ceil and truncation on primitives.
 *
 * <p>Error mapping used throughout this package: Python {@code ZeroDivisionError} and {@code OverflowError} become
 * {@link ArithmeticException}; {@code ValueError} becomes {@link IllegalArgumentException}. A result that CPython
 * would return as an int wider than 64 bits also throws {@link ArithmeticException}.
 */
public final class PyMath {

    private static final long TWO_53 = 1L << 53;

    private PyMath() {
    }

    /** {@code a // b} on ints: floor division (Java {@code /} truncates toward zero). */
    public static long floorDiv(long a, long b) {
        if (b == 0) {
            throw new ArithmeticException("integer division or modulo by zero");
        }
        if (a == Long.MIN_VALUE && b == -1) {
            throw new ArithmeticException("int result wider than 64 bits");
        }
        return Math.floorDiv(a, b);
    }

    /** {@code a % b} on ints: the result takes the sign of {@code b} ({@code -5 % 4 == 3}; Java gives -1). */
    public static long mod(long a, long b) {
        if (b == 0) {
            throw new ArithmeticException("integer division or modulo by zero");
        }
        return Math.floorMod(a, b);
    }

    /** {@code divmod(a, b)} on ints as {@code [a // b, a % b]}. */
    public static long[] divmod(long a, long b) {
        return new long[] {floorDiv(a, b), mod(a, b)};
    }

    /** {@code a // b} on floats ({@code float_floor_div}, including the snap-to-integral and signed-zero rules). */
    public static double floorDiv(double a, double b) {
        return floatDivMod(a, b)[0];
    }

    /** {@code a % b} on floats ({@code float_rem}): the result takes the sign of {@code b}. */
    public static double mod(double a, double b) {
        if (b == 0.0) {
            throw new ArithmeticException("float modulo");
        }
        double mod = a % b; // Java's floating-point remainder is C fmod
        if (mod != 0.0) {
            if ((b < 0) != (mod < 0)) {
                mod += b;
            }
        } else {
            mod = Math.copySign(0.0, b);
        }
        return mod;
    }

    /** {@code divmod(a, b)} on floats as {@code [a // b, a % b]}. */
    public static double[] floatDivMod(double a, double b) {
        if (b == 0.0) {
            throw new ArithmeticException("float divmod()");
        }
        double mod = a % b;
        double div = (a - mod) / b;
        if (mod != 0.0) {
            if ((b < 0) != (mod < 0)) {
                mod += b;
                div -= 1.0;
            }
        } else {
            mod = Math.copySign(0.0, b);
        }
        double floordiv;
        if (div != 0.0) {
            floordiv = Math.floor(div);
            if (div - floordiv > 0.5) {
                floordiv += 1.0;
            }
        } else {
            floordiv = Math.copySign(0.0, a / b);
        }
        return new double[] {floordiv, mod};
    }

    /**
     * {@code a / b} on ints: Python true division is correctly rounded (half-even) from the exact rational, which
     * {@code (double) a / b} is not once an operand exceeds 2^53.
     */
    public static double trueDivide(long a, long b) {
        if (b == 0) {
            throw new ArithmeticException("division by zero");
        }
        if (Math.abs(a) <= TWO_53 && Math.abs(b) <= TWO_53 && a != Long.MIN_VALUE && b != Long.MIN_VALUE) {
            return (double) a / (double) b;
        }
        return trueDivide(BigInteger.valueOf(a), BigInteger.valueOf(b));
    }

    /** {@code a / b} on arbitrary ints, correctly rounded (results stay far from the subnormal/overflow range). */
    public static double trueDivide(BigInteger a, BigInteger b) {
        if (b.signum() == 0) {
            throw new ArithmeticException("division by zero");
        }
        if (a.signum() == 0) {
            return b.signum() < 0 ? -0.0 : 0.0;
        }
        BigInteger num = a.abs();
        BigInteger den = b.abs();
        // Scale so the integer quotient carries at least 55 significant bits (53 + guard + sticky).
        int shift = 55 + den.bitLength() - num.bitLength();
        if (shift > 0) {
            num = num.shiftLeft(shift);
        } else {
            den = den.shiftLeft(-shift);
        }
        BigInteger[] qr = num.divideAndRemainder(den);
        BigInteger q = qr[0];
        if (qr[1].signum() != 0) {
            q = q.setBit(0); // sticky bit: never a rounding tie when the division is inexact
        }
        double magnitude = Math.scalb(q.doubleValue(), -shift);
        return (a.signum() != b.signum()) ? -magnitude : magnitude;
    }

    /** {@code a / b} on floats: like Java except division by (signed) zero raises. */
    public static double trueDivide(double a, double b) {
        if (b == 0.0) {
            throw new ArithmeticException("float division by zero");
        }
        return a / b;
    }

    /** {@code math.floor(x)} for a float: returns an int; NaN raises ValueError, infinities OverflowError. */
    public static long floor(double x) {
        return toLong(Math.floor(x));
    }

    /** {@code math.ceil(x)} for a float. */
    public static long ceil(double x) {
        return toLong(Math.ceil(x));
    }

    /** {@code int(x)} for a float: truncation toward zero, with Python's NaN/infinity errors. */
    public static long trunc(double x) {
        return toLong(x < 0 ? Math.ceil(x) : Math.floor(x));
    }

    /** {@code math.isfinite(x)}. */
    public static boolean isFinite(double x) {
        return Double.isFinite(x);
    }

    static long toLong(double integral) {
        if (Double.isNaN(integral)) {
            throw new IllegalArgumentException("cannot convert float NaN to integer");
        }
        if (Double.isInfinite(integral)) {
            throw new ArithmeticException("cannot convert float infinity to integer");
        }
        // [-2^63, 2^63) is exactly representable at both ends as doubles.
        if (integral < -0x1p63 || integral >= 0x1p63) {
            throw new ArithmeticException("int result wider than 64 bits");
        }
        return (long) integral;
    }

    /**
     * Exact comparison of a Python int with a Python float ({@code float_richcompare}). Returns -1, 0 or 1, or
     * {@link #UNORDERED} when {@code d} is NaN.
     */
    public static int compare(long i, double d) {
        if (Double.isNaN(d)) {
            return UNORDERED;
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? -1 : 1;
        }
        if (Math.abs(i) <= TWO_53 && i != Long.MIN_VALUE) {
            double di = (double) i;
            return di < d ? -1 : di > d ? 1 : 0;
        }
        return BigDecimal.valueOf(i).compareTo(new BigDecimal(d));
    }

    /** Marker returned by comparisons involving NaN. */
    public static final int UNORDERED = 2;
}
