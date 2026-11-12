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
    /**
     * The nightly credential canary (V207) is submitted as this actor, as an ordinary class 0 device job of the regular
     * 00:00 job window: 01:45 (15 minutes before the 02:00 slot) lies in neither the 00:00 window (00:00-00:59) nor the
     * backup window, so the canary runs at 00:45, the nearest minutes before the slot that admission and claim both allow
     * (a claim is refused after 00:59, so the last minute itself would leave no time to start).
     */
    public static final String CANARY_ACTOR = "system:credential-canary";
    public static final String CANARY_CRON = "0 45-59 0 * * *";
    public static final int CANARY_START_MINUTE = 45;
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

    /** 00:45 local on the day of {@code instant}. */
    public static ZonedDateTime canaryStart(Instant instant) {
        return instant.atZone(JobWindowPolicy.ZONE_ID).toLocalDate().atTime(0, CANARY_START_MINUTE).atZone(JobWindowPolicy.ZONE_ID);
    }

    /** 00:45-00:59 local: inside the 00:00 job window (default 60 minutes), after the canary start. */
    public static boolean isCanaryTime(Instant instant) {
        return !instant.isBefore(canaryStart(instant).toInstant())
                && instant.isBefore(canaryStart(instant).plusMinutes(60 - CANARY_START_MINUTE).toInstant());
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
