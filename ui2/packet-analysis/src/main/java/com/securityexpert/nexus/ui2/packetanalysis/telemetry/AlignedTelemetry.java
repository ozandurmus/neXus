package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

import java.util.Arrays;
import java.util.TreeMap;

/**
 * A parsed file together with the alignment that places its samples on the capture clock, and the placement of each
 * series on the report's second grid.
 *
 * <p>Placement rules (deterministic: samples are ordered by time, ties by file order): a gauge shows the last sample of
 * the bucket; a cumulative counter shows its increase since the previous sample, put in the bucket of the later sample
 * (a counter that goes down is a reset and counts as no increase); a count shows the sum. A bucket with no sample is
 * empty.</p>
 */
public final class AlignedTelemetry {
    private final ParsedTelemetry parsed;
    private final Alignment alignment;
    private final String unusable;
    private final long baseWall;
    private final TreeMap<String, Long> resets = new TreeMap<>();
    private Double first;
    private Double last;

    private AlignedTelemetry(ParsedTelemetry parsed, Alignment alignment, String unusable, long baseWall) {
        this.parsed = parsed;
        this.alignment = alignment;
        this.unusable = unusable;
        this.baseWall = baseWall;
    }

    public static AlignedTelemetry align(ParsedTelemetry parsed, Alignment alignment) {
        String unusable = null;
        long base = 0;
        if (!parsed.recognised()) {
            unusable = parsed.reason();
        } else if (parsed.reason() != null) {
            unusable = parsed.reason();
        } else if (parsed.base() == ParsedTelemetry.TimeBase.DATE_RELATIVE) {
            if (alignment.anchorLocal() == null) {
                unusable = "NEEDS_ANCHOR_DATE";
            } else {
                base = Math.floorDiv(Alignment.anchorMillis(alignment.anchorLocal()), 86_400_000L) * 86_400_000L;
            }
        } else if (parsed.base() == ParsedTelemetry.TimeBase.START_RELATIVE) {
            if (alignment.anchorLocal() == null) {
                unusable = "NEEDS_ANCHOR_TIME";
            } else {
                base = Alignment.anchorMillis(alignment.anchorLocal());
            }
        }
        AlignedTelemetry out = new AlignedTelemetry(parsed, alignment, unusable, base);
        if (unusable == null) {
            out.span();
        }
        return out;
    }

    public ParsedTelemetry parsed() {
        return parsed;
    }

    public Alignment alignment() {
        return alignment;
    }

    /** Closed code when the samples cannot be placed in time, else {@code null}. */
    public String unusableReason() {
        return unusable;
    }

    /** Epoch seconds of the first and last sample over all series, or {@code null}. */
    public Double firstEpoch() {
        return first;
    }

    public Double lastEpoch() {
        return last;
    }

    /** Counter resets met while placing each series (key to count). */
    public java.util.Map<String, Long> resets() {
        return resets;
    }

    public long epochMillis(Series series, int i) {
        long wall = series.wall(i) + baseWall;
        int offset = series.offset(i);
        if (offset == Stamp.NO_OFFSET) {
            offset = alignment.zoneOffsetSeconds(wall);
        }
        return wall - offset * 1000L + alignment.offsetSeconds() * 1000L;
    }

    private void span() {
        long lo = Long.MAX_VALUE;
        long hi = Long.MIN_VALUE;
        for (Series s : parsed.series().values()) {
            for (int i = 0; i < s.size(); i++) {
                long ms = epochMillis(s, i);
                lo = Math.min(lo, ms);
                hi = Math.max(hi, ms);
            }
        }
        if (lo != Long.MAX_VALUE) {
            first = lo / 1000.0;
            last = hi / 1000.0;
        }
    }

    /**
     * The series on the grid {@code origin + i * width} (epoch seconds), {@code i} in {@code [0, count)}; {@code null}
     * where no sample falls.
     */
    public Double[] place(Series series, double origin, double width, int count) {
        Double[] out = new Double[count];
        if (unusable != null) {
            return out;
        }
        int n = series.size();
        long[] ms = new long[n];
        boolean sorted = true;
        for (int i = 0; i < n; i++) {
            ms[i] = epochMillis(series, i);
            if (i > 0 && ms[i] < ms[i - 1]) {
                sorted = false;
            }
        }
        int[] order = new int[n];
        if (sorted) {
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
        } else {
            Integer[] boxed = new Integer[n];
            for (int i = 0; i < n; i++) {
                boxed[i] = i;
            }
            Arrays.sort(boxed, (a, b) -> ms[a] != ms[b] ? Long.compare(ms[a], ms[b]) : Integer.compare(a, b));
            for (int i = 0; i < n; i++) {
                order[i] = boxed[i];
            }
        }
        double previous = Double.NaN;
        long resetCount = 0;
        for (int k = 0; k < n; k++) {
            int i = order[k];
            double v = series.value(i);
            int bucket = (int) Math.floor((ms[i] / 1000.0 - origin) / width);
            boolean inside = bucket >= 0 && bucket < count;
            switch (series.kind()) {
                case GAUGE -> {
                    if (inside) {
                        out[bucket] = v;
                    }
                }
                case COUNT -> {
                    if (inside) {
                        out[bucket] = (out[bucket] == null ? 0.0 : out[bucket]) + v;
                    }
                }
                case COUNTER -> {
                    if (!Double.isNaN(previous)) {
                        double delta = v - previous;
                        if (delta < 0) {
                            resetCount++;
                            delta = 0;
                        }
                        if (inside) {
                            out[bucket] = (out[bucket] == null ? 0.0 : out[bucket]) + delta;
                        }
                    }
                    previous = v;
                }
            }
        }
        if (resetCount > 0) {
            resets.put(series.key(), resetCount);
        }
        return out;
    }
}
