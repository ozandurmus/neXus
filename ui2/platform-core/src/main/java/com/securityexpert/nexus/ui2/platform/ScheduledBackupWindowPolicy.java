package com.securityexpert.nexus.ui2.platform;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Set;

/** PO 2026-10-10: scheduled full backups have a daily window, separate from polling and manual jobs. */
public final class ScheduledBackupWindowPolicy {
    public static final String CRON = "0 * 2 * * *";
    public static final String ACTOR = "system:backup-scheduler";
    public static final String MDS_ACTOR = "system:mds-export-scheduler";
    public static final String MDS_EXPORT = "cp_mds_export";
    public static final Set<String> FLEET_JOB_TYPES = Set.of(
            "cp_gateway_backup", "cp_spark_sftp_backup", "pan_device_state_backup",
            "fgt_config_backup", "asa_config_backup", "https_vendor_backup", "rdw_cc_config_backup");

    private ScheduledBackupWindowPolicy() { }

    public static boolean isScheduledBackup(String jobType, String actor) {
        return ACTOR.equals(actor) && FLEET_JOB_TYPES.contains(jobType)
                || MDS_ACTOR.equals(actor) && MDS_EXPORT.equals(jobType);
    }

    public static ZonedDateTime start(Instant instant) {
        return instant.atZone(JobWindowPolicy.ZONE_ID).toLocalDate().atTime(2, 0).atZone(JobWindowPolicy.ZONE_ID);
    }

    public static boolean isOpen(Instant instant) {
        return !instant.isBefore(start(instant).toInstant())
                && instant.isBefore(start(instant).plusHours(1).toInstant());
    }

    public static ZonedDateTime nextStart(Instant instant) {
        var start = start(instant);
        return instant.isBefore(start.toInstant()) ? start : start.plusDays(1);
    }
}
