package com.securityexpert.nexus.ui2.service.schedule;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ScheduledBackupWindowPolicy;

/**
 * The closed list of scheduled job families an administrator may switch on and off (V203,
 * docs/design/PO_DECISION_RECORD_2026_11_10_SCHEDULED_JOB_SWITCHES.md). Each names the system actors its scheduler submits
 * jobs as: they find the family's last run and the queued jobs to cancel when it is switched off.
 */
public enum ScheduledJobFamily {
    DISCOVERY_REFRESH("discovery_refresh", "Discovery refresh",
            "Re-reads every enrolled management server (Check Point MDS, Panorama) so new managed devices show up.",
            "Every 6 hours: 00:00, 06:00, 12:00, 18:00 GMT+3", List.of("system:discovery-refresh")),
    NIGHTLY_INVENTORY("nightly_inventory", "Inventory collection",
            "Collects inventory from every enrolled device.",
            "Every 6 hours: 00:00, 06:00, 12:00, 18:00 GMT+3", List.of("system:nightly-inventory")),
    CONFIGURATION_REFRESH("configuration_refresh", "Configuration refresh",
            "Reads every enrolled device's configuration once a day.",
            "Daily at 00:00 GMT+3", List.of("system:daily-configuration")),
    HA_READINESS("ha_readiness", "HA readiness",
            "Runs the read-only failover readiness checks for every Check Point and Palo Alto HA unit, one unit at a time.",
            "Every 6 hours: 00:00, 06:00, 12:00, 18:00 GMT+3", List.of("system:failover-readiness-scheduler")),
    SCHEDULED_BACKUP("scheduled_backup", "Scheduled backup",
            "The daily fleet backup, including the Check Point MDS export, and the credential canary that runs before it.",
            "Daily at 02:00 GMT+3 (when the backup policy schedule is on); canary at 00:45",
            List.of(ScheduledBackupWindowPolicy.ACTOR, ScheduledBackupWindowPolicy.MDS_ACTOR,
                    ScheduledBackupWindowPolicy.CANARY_ACTOR)),
    SYSTEM_LOG_COLLECTION("system_log_collection", "System log collection",
            "Collects new device system-log lines, spread over the first 40 minutes of each window.",
            "Every 6 hours: 00:00, 06:00, 12:00, 18:00 GMT+3", List.of("system:system-log-collection"));

    private final String key;
    private final String label;
    private final String description;
    private final String cadence;
    private final List<String> actors;

    ScheduledJobFamily(String key, String label, String description, String cadence, List<String> actors) {
        this.key = key;
        this.label = label;
        this.description = description;
        this.cadence = cadence;
        this.actors = actors;
    }

    public String key() { return key; }
    public String label() { return label; }
    public String description() { return description; }
    public String cadence() { return cadence; }
    public List<String> actors() { return actors; }

    public static Optional<ScheduledJobFamily> byKey(String key) {
        for (ScheduledJobFamily f : values()) if (f.key.equals(key)) return Optional.of(f);
        return Optional.empty();
    }

    /** Whether this family's window is open at {@code now} (the family may submit work now). */
    public boolean windowOpen(JobWindowPolicy windows, Instant now) {
        return switch (this) {
            case SCHEDULED_BACKUP -> ScheduledBackupWindowPolicy.isOpen(now);
            case CONFIGURATION_REFRESH -> windows.isOpen(now) && windows.slotStart(now).getHour() == 0;
            default -> windows.isOpen(now);
        };
    }

    /** The start of this family's next window after {@code now}. */
    public ZonedDateTime nextWindow(JobWindowPolicy windows, Instant now) {
        return switch (this) {
            case SCHEDULED_BACKUP -> ScheduledBackupWindowPolicy.nextStart(now);
            case CONFIGURATION_REFRESH -> now.atZone(JobWindowPolicy.ZONE_ID).toLocalDate().plusDays(1)
                    .atStartOfDay(JobWindowPolicy.ZONE_ID);
            default -> windows.nextStart(now);
        };
    }
}
