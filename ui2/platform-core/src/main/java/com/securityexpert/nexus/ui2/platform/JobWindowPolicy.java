package com.securityexpert.nexus.ui2.platform;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * Shared admission policy. Running jobs, including their pre/postchecks, never consult this gate.
 *
 * <p>PO decision 2026-10-20: an administrator's manual submission (a {@link ManualAdmission.Grant} open on the
 * request thread) is never refused for the window; inside {@link BusinessHours} it needs the acknowledgement
 * instead. {@link #requireOpen(Instant)} (an explicit time, e.g. a scheduled failover's start) and every
 * {@link #isOpen} read used by the schedulers ignore grants: scheduled and automatic work keeps the windows.</p>
 */
public final class JobWindowPolicy {
    public static final String ZONE = "Europe/Istanbul";
    public static final String CRON = "0 0 0,6,12,18 * * *";
    public static final String CODE = "OUTSIDE_JOB_WINDOW";
    public static final ZoneId ZONE_ID = ZoneId.of(ZONE);
    // Stored-data exemptions; device backup admission uses the separate, named ScheduledBackupWindowPolicy.
    public static final Set<String> INTERNAL_TASKS = Set.of(
            "db_maintenance", "lease_reconciliation", "owner_heartbeat", "compliance_evaluation",
            "cluster_diff", "notification_forwarding");
    /**
     * Job types that touch no device: they analyse data an operator already uploaded. The windows exist for device
     * polling (PO amendment 2026-10-10), so a claim whose whole eligible set is stored-data work is not held to them.
     */
    public static final Set<String> STORED_DATA_JOB_TYPES = Set.of(PacketAnalysisJob.CAPABILITY);
    public static boolean storedDataOnly(java.util.Collection<String> capabilityIds) {
        return !capabilityIds.isEmpty() && STORED_DATA_JOB_TYPES.containsAll(capabilityIds);
    }
    public static final JobWindowPolicy SYSTEM = new JobWindowPolicy(Clock.systemUTC(),
            Integer.parseInt(System.getenv().getOrDefault("UI2_JOB_WINDOW_MINUTES", "60")));

    private final Clock clock;
    private final int windowMinutes;
    private final BusinessHours businessHours;

    public JobWindowPolicy(Clock clock, int windowMinutes) {
        this(clock, windowMinutes, BusinessHours.SYSTEM);
    }

    public JobWindowPolicy(Clock clock, int windowMinutes, BusinessHours businessHours) {
        if (windowMinutes < 1 || windowMinutes > 360)
            throw new IllegalArgumentException("UI2_JOB_WINDOW_MINUTES must be between 1 and 360");
        this.clock = java.util.Objects.requireNonNull(clock);
        this.windowMinutes = windowMinutes;
        this.businessHours = java.util.Objects.requireNonNull(businessHours);
    }

    public int windowMinutes() { return windowMinutes; }
    public BusinessHours businessHours() { return businessHours; }
    public boolean insideBusinessHours() { return businessHours.inside(now()); }
    public Instant now() { return clock.instant(); }
    public ZonedDateTime slotStart(Instant instant) {
        var local = instant.atZone(ZONE_ID);
        return local.toLocalDate().atTime((local.getHour() / 6) * 6, 0).atZone(ZONE_ID);
    }
    public ZonedDateTime nextStart(Instant instant) {
        var slot = slotStart(instant);
        return slot.toLocalDateTime().plusHours(6).atZone(ZONE_ID);
    }
    public boolean isOpen(Instant instant) {
        return instant.isBefore(slotStart(instant).plusMinutes(windowMinutes).toInstant());
    }
    public boolean isOpen() { return isOpen(now()); }
    public boolean isSlotMinute(Instant instant) {
        return instant.isBefore(slotStart(instant).plusMinutes(1).toInstant());
    }
    public boolean permitsInternalTask(String task, Instant instant) {
        return INTERNAL_TASKS.contains(task) || isOpen(instant);
    }
    /**
     * Manual device-job admission now. With an administrator's grant open on this thread: never a window refusal, but
     * {@link ManualAdmission.BusinessHoursConfirmRequired} inside business hours without the acknowledgement.
     * Without one (operator, aiview, every scheduler and worker): {@link OutsideWindow} outside a window, as before.
     */
    public void requireOpen() {
        var grant = ManualAdmission.current();
        if (grant.isPresent()) requireAcknowledged(grant.get());
        else requireOpen(now());
    }
    /** {@link #requireOpen()}, but only a grant held by {@code actor} counts: a job submitted for another actor never rides on it. */
    public void requireOpenFor(String actor) {
        var grant = ManualAdmission.currentFor(actor);
        if (grant.isPresent()) requireAcknowledged(grant.get());
        else requireOpen(now());
    }
    /** A step an administrator takes that sends nothing to a device (a failover approval request): window only for others. */
    public void requireOpenUnlessAdmin(String actor) {
        if (ManualAdmission.currentFor(actor).isEmpty()) requireOpen(now());
    }
    /** An explicit time (a scheduled start): always the window, grant or not. */
    public void requireOpen(Instant instant) {
        if (!isOpen(instant)) throw new OutsideWindow(nextStart(instant));
    }
    private void requireAcknowledged(ManualAdmission.Grant grant) {
        if (!grant.acknowledged() && businessHours.inside(now()))
            throw new ManualAdmission.BusinessHoursConfirmRequired(businessHours);
    }
    public void requireDeviceJobOpen(String capabilityId, String jobType, String actor) {
        var grant = ManualAdmission.currentFor(actor);
        if (grant.isPresent()) {
            requireAcknowledged(grant.get());
            return;
        }
        if (capabilityId.equals(jobType) && ScheduledBackupWindowPolicy.isScheduledBackup(jobType, actor)) {
            if (!ScheduledBackupWindowPolicy.isOpen(now()))
                throw new OutsideWindow(ScheduledBackupWindowPolicy.nextStart(now()));
        } else {
            requireOpen(now());
        }
    }
    public void logPolicy() {
        System.getLogger(JobWindowPolicy.class.getName()).log(System.Logger.Level.INFO,
                "Job windows: zone=" + ZONE + " slots=00:00,06:00,12:00,18:00 window_minutes=" + windowMinutes
                        + " scheduled_backup_window=02:00-02:59 business_hours=" + businessHours.start() + "-"
                        + businessHours.end() + " admin_manual_window_bypass=security_admin,operation_admin");
    }
    public static final class OutsideWindow extends RuntimeException {
        private final ZonedDateTime nextWindowStart;
        public OutsideWindow(ZonedDateTime nextWindowStart) {
            super("Device jobs are available during scheduled windows. Next window: " + nextWindowStart);
            this.nextWindowStart = nextWindowStart;
        }
        public ZonedDateTime nextWindowStart() { return nextWindowStart; }
    }
}
