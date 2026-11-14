package com.securityexpert.nexus.ui2.service.system;

import java.util.function.Consumer;

/**
 * Disk headroom thresholds (backlog "disk headroom alert"; the host disk filled on 2026-10-10). A volume is
 * {@code WARNING} from {@code warningPercent} used and {@code CRITICAL} from {@code criticalPercent} used. The alert
 * (not the status payload) additionally uses a hysteresis band: once raised, a level is only left when usage falls
 * {@code hysteresisPercent} points below its threshold, so a volume hovering on a threshold raises one alert, not many.
 * Configured through {@code ui2.disk-headroom.*} (environment {@code UI2_DISK_WARNING_PERCENT},
 * {@code UI2_DISK_CRITICAL_PERCENT}, {@code UI2_DISK_HYSTERESIS_PERCENT}); see {@code docs/operations/DISK_HEADROOM_ALERT.md}.
 */
public record DiskHeadroomPolicy(int warningPercent, int criticalPercent, int hysteresisPercent) {

    public static final DiskHeadroomPolicy DEFAULT = new DiskHeadroomPolicy(80, 90, 2);

    public DiskHeadroomPolicy {
        if (warningPercent < 1 || criticalPercent > 100 || warningPercent >= criticalPercent) {
            throw new IllegalArgumentException("disk thresholds must satisfy 1 <= warning < critical <= 100, got "
                    + warningPercent + "/" + criticalPercent);
        }
        if (hysteresisPercent < 0 || hysteresisPercent >= warningPercent) {
            throw new IllegalArgumentException("disk hysteresis must satisfy 0 <= hysteresis < warning, got " + hysteresisPercent);
        }
    }

    /** The configured policy, or the defaults (reported through {@code complain}) when the configured values are invalid. */
    public static DiskHeadroomPolicy orDefault(int warning, int critical, int hysteresis, Consumer<String> complain) {
        try {
            return new DiskHeadroomPolicy(warning, critical, hysteresis);
        } catch (IllegalArgumentException e) {
            complain.accept(e.getMessage() + "; using the defaults " + DEFAULT.warningPercent + "/" + DEFAULT.criticalPercent
                    + " with hysteresis " + DEFAULT.hysteresisPercent);
            return DEFAULT;
        }
    }

    /** The level of a usage percentage as it stands now, with no memory of an earlier level. */
    public DiskLevel classify(int usedPercent) {
        if (usedPercent >= criticalPercent) return DiskLevel.CRITICAL;
        if (usedPercent >= warningPercent) return DiskLevel.WARNING;
        return DiskLevel.OK;
    }

    /** The level once the hysteresis band is applied to the level the volume was last settled at ({@code null} = OK). */
    public DiskLevel settle(DiskLevel previous, int usedPercent) {
        DiskLevel raw = classify(usedPercent);
        if (previous == null || previous == DiskLevel.OK || previous == DiskLevel.UNKNOWN) return raw;
        if (raw == DiskLevel.CRITICAL) return DiskLevel.CRITICAL;
        if (previous == DiskLevel.CRITICAL && usedPercent >= criticalPercent - hysteresisPercent) return DiskLevel.CRITICAL;
        if (usedPercent >= warningPercent - hysteresisPercent) return DiskLevel.WARNING;
        return DiskLevel.OK;
    }
}
