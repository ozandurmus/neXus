package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

import java.util.Arrays;

/**
 * One metric of one telemetry file: samples in file order. {@link Kind} says how samples are placed on the report's
 * second grid ({@code GAUGE}: the last sample of a bucket; {@code COUNTER}: cumulative counter, bucket value is the
 * increase; {@code COUNT}: the bucket value is the sum).
 */
public final class Series {
    public enum Kind { GAUGE, COUNTER, COUNT }

    private final String key;
    private final Kind kind;
    private long[] wall = new long[16];
    private int[] offset = new int[16];
    private double[] value = new double[16];
    private int size;
    private final long[] budget;

    public Series(String key, Kind kind) {
        this(key, kind, null);
    }

    /** {@code budget[0]} counts the samples of every series of one file (shared), so a file can be capped as a whole. */
    Series(String key, Kind kind, long[] budget) {
        this.key = key;
        this.kind = kind;
        this.budget = budget;
    }

    public String key() {
        return key;
    }

    public Kind kind() {
        return kind;
    }

    public int size() {
        return size;
    }

    public void add(Stamp stamp, double v) {
        add(stamp.wallMillis(), stamp.offsetSeconds(), v);
    }

    public void add(long wallMillis, int offsetSeconds, double v) {
        if (size == wall.length) {
            int cap = (int) Math.min(Integer.MAX_VALUE - 8L, size * 2L);
            wall = Arrays.copyOf(wall, cap);
            offset = Arrays.copyOf(offset, cap);
            value = Arrays.copyOf(value, cap);
        }
        if (budget != null) {
            budget[0]++;
        }
        wall[size] = wallMillis;
        offset[size] = offsetSeconds;
        value[size] = v;
        size++;
    }

    public long wall(int i) {
        return wall[i];
    }

    public int offset(int i) {
        return offset[i];
    }

    public double value(int i) {
        return value[i];
    }
}
