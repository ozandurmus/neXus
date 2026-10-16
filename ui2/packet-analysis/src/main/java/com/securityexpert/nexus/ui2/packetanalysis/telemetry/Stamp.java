package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

/**
 * A timestamp as written in a telemetry file: the wall-clock reading as milliseconds since 1970-01-01T00:00 taken as if it
 * were UTC, plus the UTC offset (seconds) when the text itself carried one. Without an explicit offset the zone chosen in
 * the alignment decides what the wall clock means.
 */
public record Stamp(long wallMillis, int offsetSeconds) {
    public static final int NO_OFFSET = Integer.MIN_VALUE;

    public static Stamp local(long wallMillis) {
        return new Stamp(wallMillis, NO_OFFSET);
    }

    public boolean explicit() {
        return offsetSeconds != NO_OFFSET;
    }
}
