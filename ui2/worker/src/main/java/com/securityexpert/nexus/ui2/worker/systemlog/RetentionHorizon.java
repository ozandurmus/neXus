package com.securityexpert.nexus.ui2.worker.systemlog;

/**
 * Retention horizon of a source (contract section 4.6): {@code rotate_count x rotation_size / growth_rate}. Below the
 * configured threshold (9 h: the 6 h interval plus a 50 % margin) the source is flagged {@code FAST_ROTATING} for
 * that device. The inputs come from the measurement and the stored snapshots, not from the planner.
 */
public final class RetentionHorizon {

    private RetentionHorizon() {
    }

    /** Hours of log the rotation chain holds; infinity when the source does not grow. */
    public static double horizonHours(int rotateCount, long rotationSizeBytes, double growthBytesPerHour) {
        if (rotateCount < 0 || rotationSizeBytes < 0 || growthBytesPerHour < 0 || Double.isNaN(growthBytesPerHour)) {
            throw new IllegalArgumentException("horizon inputs must be non-negative");
        }
        if (growthBytesPerHour == 0) {
            return Double.POSITIVE_INFINITY;
        }
        return (double) rotateCount * (double) rotationSizeBytes / growthBytesPerHour;
    }

    public static boolean isFastRotating(double horizonHours, PlannerConfig config) {
        return horizonHours < config.fastRotatingHorizonHours();
    }
}
