package com.securityexpert.nexus.ui2.service.logdetect;

import java.util.Arrays;

/**
 * Median and median absolute deviation of a sparse count vector (design section 4.2 per-device baseline, section 4.4
 * robust scale): {@code buckets} buckets of which the ones not listed in {@code nonZero} hold 0. Exact, without
 * materialising the zeros.
 */
public final class LogRobustStats {
    /** The pair (median, MAD). */
    public record MedianMad(double median, double mad) {
    }

    private LogRobustStats() {
    }

    public static MedianMad of(long buckets, long[] nonZero) {
        if (buckets <= 0) return new MedianMad(0, 0);
        long[] values = nonZero.clone();
        Arrays.sort(values);
        long zeros = Math.max(0, buckets - values.length);
        double median = medianOf(values, 0, zeros);
        double[] dev = new double[values.length];
        for (int i = 0; i < values.length; i++) dev[i] = Math.abs(values[i] - median);
        Arrays.sort(dev);
        double mad = medianOf(dev, median, zeros);
        return new MedianMad(median, mad);
    }

    /** Median of the sorted values merged with {@code copies} copies of {@code constant}. */
    private static double medianOf(long[] sorted, double constant, long copies) {
        double[] d = new double[sorted.length];
        for (int i = 0; i < sorted.length; i++) d[i] = sorted[i];
        return medianOf(d, constant, copies);
    }

    private static double medianOf(double[] sorted, double constant, long copies) {
        long n = sorted.length + copies;
        if (n == 0) return 0;
        if (n % 2 == 1) return kth(sorted, constant, copies, n / 2);
        return (kth(sorted, constant, copies, n / 2 - 1) + kth(sorted, constant, copies, n / 2)) / 2.0;
    }

    private static double kth(double[] sorted, double constant, long copies, long k) {
        int below = 0;
        while (below < sorted.length && sorted[below] < constant) below++;
        if (k < below) return sorted[(int) k];
        if (k < below + copies) return constant;
        return sorted[(int) (k - copies)];
    }
}
