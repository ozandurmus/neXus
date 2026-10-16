package com.securityexpert.nexus.ui2.packetanalysis.ingest;

/** Capture timestamp validation: frames with an unusable timestamp go to {@code excluded_frames}. */
public final class TimestampRules {

    /** 2000-01-01T00:00:00Z. */
    public static final double MIN_TIMESTAMP = 946684800.0;

    private TimestampRules() {
    }

    /** The exclusion reason for an observed timestamp, or {@code null} when it is usable. */
    public static String reason(Double value, double upper) {
        if (value == null || !Double.isFinite(value)) {
            return "timestamp_missing_or_invalid";
        }
        if (value < MIN_TIMESTAMP) {
            return "timestamp_before_2000";
        }
        if (value > upper) {
            return "timestamp_after_now_plus_one_day";
        }
        return null;
    }
}
