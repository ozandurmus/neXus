package com.securityexpert.nexus.ui2.packetanalysis.numeric;

import java.util.List;

/**
 * CPython 3.12 {@code sum}, {@code min} and {@code max} over numbers.
 *
 * <p>{@code sum} of floats is <em>not</em> left-to-right addition since Python 3.12: it uses Neumaier compensated
 * summation ({@code builtin_sum_impl}), so a Java {@code for} loop or {@code DoubleStream.sum()} (Kahan with a
 * different correction) gives different bits. {@code min}/{@code max} keep the first extreme element and only
 * replace it on a strict {@code <}/{@code >}, so {@code max(0.0, -0.0)} is {@code 0.0}, {@code max(-0.0, 0.0)} is
 * {@code -0.0}, {@code min(nan, 1.0)} is NaN and {@code min(1.0, nan)} is {@code 1.0} — unlike {@link Math#min}.
 */
public final class PyBuiltins {

    private PyBuiltins() {
    }

    /** {@code sum(values)} for floats; an empty sum is Python's int 0, returned here as 0.0 (use the PyNum form). */
    public static double sum(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        // 0 + values[0] is computed by int.__add__ / float.__radd__: the float 0.0 + x (turns -0.0 into 0.0).
        double f = 0.0 + values[0];
        double c = 0.0;
        for (int i = 1; i < values.length; i++) {
            double x = values[i];
            double t = f + x;
            if (Math.abs(f) >= Math.abs(x)) {
                c += (f - t) + x;
            } else {
                c += (x - t) + f;
            }
            f = t;
        }
        return finish(f, c);
    }

    private static double finish(double f, double c) {
        // Avoid losing the sign on a negative result, and don't let the compensation turn inf into NaN.
        if (c != 0.0 && Double.isFinite(c)) {
            f += c;
        }
        return f;
    }

    /**
     * {@code sum(values)} for ints and floats: exact int accumulation until the first float, then the compensated
     * float loop, in which later int items are converted to float and added plainly (uncompensated), as in 3.12.
     */
    public static PyNum sum(List<? extends PyNum> values) {
        long acc = 0;
        int i = 0;
        int n = values.size();
        while (i < n && values.get(i) instanceof PyInt x) {
            acc = Math.addExact(acc, x.value());
            i++;
        }
        if (i == n) {
            return PyNum.of(acc);
        }
        double f = (double) acc + values.get(i).toDouble();
        double c = 0.0;
        for (i = i + 1; i < n; i++) {
            if (values.get(i) instanceof PyInt k) {
                // CPython 3.12 adds an int item in the float phase without touching the compensation term.
                f += (double) k.value();
                continue;
            }
            double x = values.get(i).toDouble();
            double t = f + x;
            if (Math.abs(f) >= Math.abs(x)) {
                c += (f - t) + x;
            } else {
                c += (x - t) + f;
            }
            f = t;
        }
        return PyNum.of(finish(f, c));
    }

    /** {@code min(values)} for floats. */
    public static double min(double... values) {
        requireNonEmpty(values.length, "min");
        double best = values[0];
        for (int i = 1; i < values.length; i++) {
            if (values[i] < best) {
                best = values[i];
            }
        }
        return best;
    }

    /** {@code max(values)} for floats. */
    public static double max(double... values) {
        requireNonEmpty(values.length, "max");
        double best = values[0];
        for (int i = 1; i < values.length; i++) {
            if (values[i] > best) {
                best = values[i];
            }
        }
        return best;
    }

    /** {@code min(values)} for ints and floats; the winning element keeps its type. */
    public static PyNum min(List<? extends PyNum> values) {
        requireNonEmpty(values.size(), "min");
        PyNum best = values.get(0);
        for (int i = 1; i < values.size(); i++) {
            if (PyArith.lt(values.get(i), best)) {
                best = values.get(i);
            }
        }
        return best;
    }

    /** {@code max(values)} for ints and floats; the winning element keeps its type. */
    public static PyNum max(List<? extends PyNum> values) {
        requireNonEmpty(values.size(), "max");
        PyNum best = values.get(0);
        for (int i = 1; i < values.size(); i++) {
            if (PyArith.gt(values.get(i), best)) {
                best = values.get(i);
            }
        }
        return best;
    }

    private static void requireNonEmpty(int size, String name) {
        if (size == 0) {
            throw new IllegalArgumentException(name + "() iterable argument is empty");
        }
    }
}
