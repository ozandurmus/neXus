package com.securityexpert.nexus.ui2.packetanalysis.numeric;

/**
 * The float-timestamp step of CPython 3.12 {@code datetime.fromtimestamp(ts, tz)}: the fraction is scaled to
 * microseconds and rounded half-to-even ({@code _PyTime_DoubleToDenominator}, {@code _PyTime_ROUND_HALF_EVEN}).
 * Truncating {@code ts * 1e6}, or {@code Instant.ofEpochSecond} with nanos, differs at microsecond ties and for
 * negative timestamps. Time-zone rendering ({@code isoformat}, +03:00) is a later port stage and uses java.time.
 */
public final class PyTime {

    private PyTime() {
    }

    /** Epoch microseconds of {@code datetime.fromtimestamp(ts, timezone.utc)}. */
    public static long fromTimestampMicros(double ts) {
        if (!Double.isFinite(ts)) {
            throw new IllegalArgumentException(Double.isNaN(ts) ? "Invalid value NaN (not a number)"
                    : "cannot convert float infinity to integer");
        }
        double intpart = ts < 0 ? Math.ceil(ts) : Math.floor(ts); // modf
        double floatpart = ts - intpart;
        floatpart *= 1e6;
        floatpart = Math.rint(floatpart);
        if (floatpart >= 1e6) {
            floatpart -= 1e6;
            intpart += 1.0;
        } else if (floatpart < 0) {
            floatpart += 1e6;
            intpart -= 1.0;
        }
        long sec = PyMath.toLong(intpart);
        return Math.addExact(Math.multiplyExact(sec, 1_000_000L), (long) floatpart);
    }
}
