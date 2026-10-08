package com.securityexpert.nexus.ui2.platform;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/** Shared admission policy. Running jobs, including their pre/postchecks, never consult this gate. */
public final class JobWindowPolicy {
    public static final String ZONE = "Europe/Istanbul";
    public static final String CRON = "0 0 0,6,12,18 * * *";
    public static final String CODE = "OUTSIDE_JOB_WINDOW";
    public static final ZoneId ZONE_ID = ZoneId.of(ZONE);
    // These tasks only process stored data. No device capability is exempt; unknown capabilities fail closed.
    public static final Set<String> INTERNAL_TASKS = Set.of(
            "db_maintenance", "lease_reconciliation", "owner_heartbeat", "compliance_evaluation",
            "cluster_diff", "notification_forwarding");
    public static final JobWindowPolicy SYSTEM = new JobWindowPolicy(Clock.systemUTC(),
            Integer.parseInt(System.getenv().getOrDefault("UI2_JOB_WINDOW_MINUTES", "60")));

    private final Clock clock;
    private final int windowMinutes;

    public JobWindowPolicy(Clock clock, int windowMinutes) {
        if (windowMinutes < 1 || windowMinutes > 360)
            throw new IllegalArgumentException("UI2_JOB_WINDOW_MINUTES must be between 1 and 360");
        this.clock = java.util.Objects.requireNonNull(clock);
        this.windowMinutes = windowMinutes;
    }

    public int windowMinutes() { return windowMinutes; }
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
    public void requireOpen() { requireOpen(now()); }
    public void requireOpen(Instant instant) {
        if (!isOpen(instant)) throw new OutsideWindow(nextStart(instant));
    }
    public void logPolicy() {
        System.getLogger(JobWindowPolicy.class.getName()).log(System.Logger.Level.INFO,
                "Job windows: zone=" + ZONE + " slots=00:00,06:00,12:00,18:00 window_minutes=" + windowMinutes);
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
