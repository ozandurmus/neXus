package com.securityexpert.nexus.ui2.service.schedule;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.securityexpert.nexus.ui2.persistence.schedule.ScheduledJobFamilyRepository;
import com.securityexpert.nexus.ui2.persistence.schedule.ScheduledJobFamilyRepository.Change;
import com.securityexpert.nexus.ui2.persistence.schedule.ScheduledJobFamilyRepository.Row;
import com.securityexpert.nexus.ui2.platform.BusinessHours;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

/**
 * Per-family on/off for scheduled jobs (V203; docs/design/PO_DECISION_RECORD_2026_11_10_SCHEDULED_JOB_SWITCHES.md).
 *
 * <p><b>Schedulers</b> ask {@link #isOn} at the start of every tick (and before each unit where a tick submits one unit
 * at a time). The answer is read from the database each time, so a change applies from the next tick without a
 * restart. A row that is missing or cannot be read answers off (fail closed) and is logged.</p>
 *
 * <p><b>Deployment locks.</b> {@code UI2_SCHEDULING_ENABLED=false} removes Spring scheduling altogether, so no family runs
 * whatever its row says; the system-log collection additionally needs {@code UI2_SYSTEM_LOG_SCHEDULED_COLLECTION_ENABLED}
 * and, with {@code UI2_SYSTEM_LOG_SCHEDULED_COLLECTION_INDEPENDENT=true}, runs even when the master switch is off. A
 * locked family cannot be changed here: the deployment decides.</p>
 */
public class ScheduledJobSwitches implements FamilySwitch {

    private static final Logger LOG = Logger.getLogger(ScheduledJobSwitches.class.getName());
    public static final String SWEEP_ACTOR = "system:scheduled-job-switch";
    public static final String MASTER_ENV = "UI2_SCHEDULING_ENABLED";
    public static final int REASON_MIN = 5;
    public static final int REASON_MAX = 500;

    /** The deployment switches that can lock families, as the service process sees them. */
    public record Deployment(boolean masterEnabled, boolean systemLogCollectionEnabled, boolean systemLogIndependent) {
        /** Why {@code family} runs regardless of its row being on, or empty when the row decides. */
        public Optional<String> lockReason(ScheduledJobFamily family) {
            if (family == ScheduledJobFamily.SYSTEM_LOG_COLLECTION) {
                if (!systemLogCollectionEnabled)
                    return Optional.of("Deployment switch UI2_SYSTEM_LOG_SCHEDULED_COLLECTION_ENABLED=false");
                if (!masterEnabled && !systemLogIndependent) return Optional.of("Deployment switch " + MASTER_ENV + "=false");
                return Optional.empty();
            }
            return masterEnabled ? Optional.empty() : Optional.of("Deployment switch " + MASTER_ENV + "=false");
        }
    }

    public sealed interface Outcome permits Outcome.Changed, Outcome.Refused {
        record Changed(Map<String, Object> family, int cancelledJobs, int closedRuns) implements Outcome {
        }

        record Refused(int status, String code, String message) implements Outcome {
        }
    }

    private final ScheduledJobFamilyRepository repository;
    private final Deployment deployment;
    private final JobWindowPolicy windows;
    private final BusinessHours businessHours;
    private final Clock clock;
    /** True when the scheduled backup policy's own schedule switch is on (Backups screen); empty when unknown. */
    private final Supplier<Optional<Boolean>> backupPolicySchedule;

    public ScheduledJobSwitches(ScheduledJobFamilyRepository repository, Deployment deployment, JobWindowPolicy windows,
            BusinessHours businessHours, Clock clock, Supplier<Optional<Boolean>> backupPolicySchedule) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.deployment = Objects.requireNonNull(deployment, "deployment");
        this.windows = Objects.requireNonNull(windows, "windows");
        this.businessHours = Objects.requireNonNull(businessHours, "businessHours");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.backupPolicySchedule = Objects.requireNonNull(backupPolicySchedule, "backupPolicySchedule");
    }

    @Override
    public boolean isOn(ScheduledJobFamily family) {
        try {
            Optional<Boolean> enabled = repository.enabled(family.key());
            if (enabled.isEmpty()) {
                LOG.warning("[SCHEDULED_JOBS] " + family.key() + ": no switch row; treated as OFF");
                return false;
            }
            return enabled.get();
        } catch (RuntimeException unreadable) {
            LOG.warning("[SCHEDULED_JOBS] " + family.key() + ": switch unreadable (" + unreadable.getClass().getSimpleName()
                    + "); treated as OFF");
            return false;
        }
    }

    public Deployment deployment() {
        return deployment;
    }

    public BusinessHours businessHours() {
        return businessHours;
    }

    /**
     * The screen's read model. {@code principal} turns a stored actor into what this caller may see (itself, or the
     * aiview user token); {@code masked} withholds the free-text reason.
     */
    public Map<String, Object> view(boolean canWrite, boolean masked, Function<String, String> principal) {
        Instant now = clock.instant();
        Map<String, Row> rows = repository.readAll();
        List<String> actors = new ArrayList<>();
        for (ScheduledJobFamily f : ScheduledJobFamily.values()) actors.addAll(f.actors());
        Map<String, Instant> last = repository.lastSubmitted(actors);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("master_enabled", deployment.masterEnabled());
        body.put("master_env", MASTER_ENV);
        body.put("can_write", canWrite);
        body.put("masked", masked);
        Map<String, Object> hours = new LinkedHashMap<>();
        hours.put("start", businessHours.start().toString());
        hours.put("end", businessHours.end().toString());
        hours.put("zone", JobWindowPolicy.ZONE);
        hours.put("inside_now", businessHours.inside(now));
        body.put("business_hours", hours);
        List<Map<String, Object>> families = new ArrayList<>();
        for (ScheduledJobFamily f : ScheduledJobFamily.values()) {
            families.add(familyBody(f, rows.get(f.key()), last, now, masked, principal));
        }
        body.put("families", families);
        return body;
    }

    /**
     * One administrator change. {@code acknowledged} is the business-hours acknowledgement of this request; inside
     * business hours without it nothing is stored and the caller answers the confirmation code.
     */
    public Outcome set(String actor, String familyKey, Boolean enabled, String reason, boolean acknowledged,
            Function<String, String> principal) {
        Optional<ScheduledJobFamily> family = ScheduledJobFamily.byKey(familyKey == null ? "" : familyKey);
        if (family.isEmpty()) return new Outcome.Refused(404, "UNKNOWN_FAMILY", "No scheduled job family by that name.");
        if (enabled == null) return new Outcome.Refused(400, "ENABLED_REQUIRED", "enabled (true|false) is required.");
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.length() < REASON_MIN || trimmed.length() > REASON_MAX)
            return new Outcome.Refused(400, "REASON_REQUIRED",
                    "A reason of " + REASON_MIN + " to " + REASON_MAX + " characters is required.");
        Optional<String> lock = deployment.lockReason(family.get());
        if (lock.isPresent())
            return new Outcome.Refused(409, "SCHEDULING_LOCKED_BY_DEPLOYMENT",
                    family.get().label() + " is locked by the deployment (" + lock.get().replace("Deployment switch ", "")
                            + "). Changing it needs a deployment change.");
        Instant now = clock.instant();
        if (!acknowledged && businessHours.inside(now))
            return new Outcome.Refused(409, CONFIRM_CODE, confirmMessage());
        Optional<Change> change = repository.set(family.get().key(), enabled, trimmed, actor, family.get().actors());
        if (change.isEmpty())
            return new Outcome.Refused(409, "FAMILY_NOT_SEEDED", "The switch row for this family is missing (migration V203).");
        LOG.info("[SCHEDULED_JOBS] " + family.get().key() + " switched " + (enabled ? "ON" : "OFF") + "; cancelled "
                + change.get().cancelledJobs() + " queued job(s), closed " + change.get().closedDiscoveryRuns() + " run(s)");
        Map<String, Object> body = familyBody(family.get(), change.get().row(),
                repository.lastSubmitted(family.get().actors()), now, false, principal);
        return new Outcome.Changed(body, change.get().cancelledJobs(), change.get().closedDiscoveryRuns());
    }

    /** Cancels queued jobs a switched-off family's scheduler submitted after the switch; see {@link ScheduledJobFamilyRepository#sweep}. */
    public int sweep() {
        Map<String, List<String>> off = new LinkedHashMap<>();
        Map<String, Row> rows = repository.readAll();
        for (ScheduledJobFamily f : ScheduledJobFamily.values()) {
            Row row = rows.get(f.key());
            // A missing row is OFF, as in isOn(); the repository re-checks each family under a row lock before cancelling.
            if (row == null || !row.enabled()) off.put(f.key(), f.actors());
        }
        Change swept = repository.sweep(SWEEP_ACTOR, off);
        if (swept.cancelledJobs() > 0)
            LOG.warning("[SCHEDULED_JOBS] sweep cancelled " + swept.cancelledJobs() + " queued job(s) of switched-off families, closed "
                    + swept.closedDiscoveryRuns() + " run(s)");
        return swept.cancelledJobs();
    }

    public static final String CONFIRM_CODE = "BUSINESS_HOURS_CONFIRM_REQUIRED";

    String confirmMessage() {
        return "Business hours (" + businessHours.start() + "–" + businessHours.end()
                + " GMT+3). This changes which scheduled jobs run. Continue?";
    }

    private Map<String, Object> familyBody(ScheduledJobFamily f, Row row, Map<String, Instant> last, Instant now,
            boolean masked, Function<String, String> principal) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("family", f.key());
        b.put("label", f.label());
        b.put("description", f.description());
        b.put("cadence", f.cadence());
        boolean enabled = row != null && row.enabled();
        Optional<String> lock = deployment.lockReason(f);
        b.put("enabled", enabled);
        b.put("state", lock.isPresent() ? "LOCKED" : enabled ? "ON" : "OFF");
        b.put("lock_reason", lock.orElse(null));
        String note = note(f, row);
        b.put("note", note);
        // ON and nothing else stops it; today only the backup policy's own schedule switch can (shown in the note).
        b.put("will_run", lock.isEmpty() && enabled && note == null);
        boolean open = f.windowOpen(windows, now);
        b.put("window_open_now", open);
        // Families that admit work all through their window: switched on while it is open, they may start right away.
        b.put("resumes_in_open_window", open && (f == ScheduledJobFamily.SYSTEM_LOG_COLLECTION
                || f == ScheduledJobFamily.SCHEDULED_BACKUP));
        b.put("next_window_at", f.nextWindow(windows, now).toOffsetDateTime().toString());
        Instant lastRun = null;
        for (String actor : f.actors()) {
            Instant t = last.get(actor);
            if (t != null && (lastRun == null || t.isAfter(lastRun))) lastRun = t;
        }
        b.put("last_run_at", lastRun == null ? null : lastRun.toString());
        b.put("changed_by", row == null ? null : principal.apply(row.changedBy()));
        b.put("changed_at", row == null || row.changedAt() == null ? null : row.changedAt().toString());
        b.put("reason", masked || row == null ? null : row.reason());
        return b;
    }

    private String note(ScheduledJobFamily f, Row row) {
        if (row == null) return "No switch row (migration V203 missing); treated as OFF.";
        if (f == ScheduledJobFamily.SCHEDULED_BACKUP) {
            Optional<Boolean> policy;
            try {
                policy = backupPolicySchedule.get();
            } catch (RuntimeException unreadable) {
                policy = Optional.empty();
            }
            if (policy.isEmpty()) return "Backup policy schedule: UNKNOWN (Backups screen).";
            if (!policy.get()) return "The backup policy schedule is off (Backups screen): nothing runs until it is on as well.";
        }
        return null;
    }
}
