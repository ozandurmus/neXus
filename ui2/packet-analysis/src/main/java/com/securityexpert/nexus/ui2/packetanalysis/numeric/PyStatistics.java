package com.securityexpert.nexus.ui2.packetanalysis.numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * CPython 3.12 {@code statistics.median} and {@code statistics.quantiles}, the only {@code statistics} functions
 * PacketBreaker calls (NUMERIC_INVENTORY.md). The input is sorted with {@link PyTimSort} so NaN and -0.0 land where
 * CPython puts them, and the arithmetic follows the Python source operation by operation.
 *
 * <p>{@code StatisticsError} maps to {@link IllegalArgumentException}.
 */
public final class PyStatistics {

    /** {@code method=} of {@code statistics.quantiles}. */
    public enum Method {
        EXCLUSIVE, INCLUSIVE
    }

    private PyStatistics() {
    }

    /** {@code statistics.median(data)} for floats. */
    public static double median(double[] data) {
        double[] s = PyTimSort.sorted(data);
        int n = s.length;
        if (n == 0) {
            throw new IllegalArgumentException("no median for empty data");
        }
        if (n % 2 == 1) {
            return s[n / 2];
        }
        int i = n / 2;
        return (s[i - 1] + s[i]) / 2;
    }

    /**
     * {@code statistics.median(data)} for ints and floats: an odd-length median is the middle element itself (an
     * int stays an int); an even-length one is {@code (a + b) / 2}, always a float.
     */
    public static PyNum median(List<? extends PyNum> data) {
        List<PyNum> s = PyTimSort.sorted(data, false);
        int n = s.size();
        if (n == 0) {
            throw new IllegalArgumentException("no median for empty data");
        }
        if (n % 2 == 1) {
            return s.get(n / 2);
        }
        int i = n / 2;
        if (s.get(i - 1) instanceof PyInt x && s.get(i) instanceof PyInt y) {
            BigInteger total = BigInteger.valueOf(x.value()).add(BigInteger.valueOf(y.value()));
            return PyNum.of(PyMath.trueDivide(total, BigInteger.TWO));
        }
        return PyArith.trueDiv(PyArith.add(s.get(i - 1), s.get(i)), PyNum.of(2));
    }

    /** {@code statistics.quantiles(data, n=n, method=method)} for floats. */
    public static double[] quantiles(double[] data, int n, Method method) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be at least 1");
        }
        double[] s = PyTimSort.sorted(data);
        int ld = s.length;
        if (ld < 2) {
            throw new IllegalArgumentException("must have at least two data points");
        }
        double[] result = new double[n - 1];
        if (method == Method.INCLUSIVE) {
            long m = ld - 1;
            for (int i = 1; i < n; i++) {
                long im = i * m;
                int j = (int) (im / n);
                long delta = im % n;
                result[i - 1] = (s[j] * (double) (n - delta) + s[j + 1] * (double) delta) / n;
            }
        } else {
            long m = ld + 1;
            for (int i = 1; i < n; i++) {
                long j = i * m / n;
                j = j < 1 ? 1 : j > ld - 1 ? ld - 1 : j;
                long delta = i * m - j * n;
                result[i - 1] = (s[(int) j - 1] * (double) (n - delta) + s[(int) j] * (double) delta) / n;
            }
        }
        return result;
    }

    /**
     * {@code statistics.quantiles} for ints and floats. With all-int data the interpolation is exact integer
     * arithmetic followed by one correctly rounded true division, as in CPython.
     */
    public static List<PyNum> quantiles(List<? extends PyNum> data, int n, Method method) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be at least 1");
        }
        List<PyNum> s = PyTimSort.sorted(data, false);
        int ld = s.size();
        if (ld < 2) {
            throw new IllegalArgumentException("must have at least two data points");
        }
        List<PyNum> result = new ArrayList<>(n - 1);
        for (int i = 1; i < n; i++) {
            PyNum lo;
            PyNum hi;
            long delta;
            if (method == Method.INCLUSIVE) {
                long im = (long) i * (ld - 1);
                int j = (int) (im / n);
                delta = im % n;
                lo = s.get(j);
                hi = s.get(j + 1);
            } else {
                long m = ld + 1;
                long j = i * m / n;
                j = j < 1 ? 1 : j > ld - 1 ? ld - 1 : j;
                delta = i * m - j * n;
                lo = s.get((int) j - 1);
                hi = s.get((int) j);
            }
            // (lo * (n - delta) + hi * delta) / n with Python's unbounded ints: an int product stays exact, an
            // int meets a float only through a correctly rounded conversion.
            Object left = times(lo, n - delta);
            Object right = times(hi, delta);
            if (left instanceof BigInteger x && right instanceof BigInteger y) {
                result.add(PyNum.of(PyMath.trueDivide(x.add(y), BigInteger.valueOf(n))));
            } else {
                double sum = asDouble(left) + asDouble(right);
                result.add(PyNum.of(sum / n));
            }
        }
        return result;
    }

    /** {@code value * factor}: a BigInteger for an int value, a Double for a float value. */
    private static Object times(PyNum value, long factor) {
        if (value instanceof PyInt i) {
            return BigInteger.valueOf(i.value()).multiply(BigInteger.valueOf(factor));
        }
        return value.toDouble() * (double) factor;
    }

    private static double asDouble(Object term) {
        return term instanceof BigInteger b ? b.doubleValue() : (Double) term;
    }
}
