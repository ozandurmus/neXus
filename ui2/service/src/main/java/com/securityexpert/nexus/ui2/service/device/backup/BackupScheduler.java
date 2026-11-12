package com.securityexpert.nexus.ui2.service.device.backup;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository.BackupPolicy;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ScheduledBackupWindowPolicy;
import com.securityexpert.nexus.ui2.service.schedule.FamilySwitch;
import com.securityexpert.nexus.ui2.service.schedule.ScheduledJobFamily;

/**
 * Evaluates daily_backup_cron in Europe/Istanbul, at most once per local day.
 * Starts only at 02:00–02:59; missed days expire instead of becoming a backlog.
 */
public final class BackupScheduler {

    private static final Logger LOG = Logger.getLogger(BackupScheduler.class.getName());
    public static final String ACTOR = ScheduledBackupWindowPolicy.ACTOR;
    public static final String ACTION_SLOT_CLAIMED = "backup_schedule_slot_claimed";

    private final BackupPolicyRepository policyRepository;
    private final BackupCollectService collectService;
    private final FamilySwitch switches;

    public BackupScheduler(BackupPolicyRepository policyRepository, BackupCollectService collectService) {
        this(policyRepository, collectService, FamilySwitch.ALWAYS_ON);
    }

    public BackupScheduler(BackupPolicyRepository policyRepository, BackupCollectService collectService, FamilySwitch switches) {
        this.policyRepository = Objects.requireNonNull(policyRepository, "policyRepository");
        this.collectService = Objects.requireNonNull(collectService, "collectService");
        this.switches = Objects.requireNonNull(switches, "switches");
    }

    private volatile java.time.LocalDate canaryDay;

    /**
     * The nightly credential canary (V207): from 00:45 to 00:59 local, while the schedule and the scheduled-backup family
     * are on, admits one read-only {@code credential_canary} per backup credential (Check Point only in this release).
     * The key is per device and day, so every minute of the span after the first admits nothing new.
     *
     * @return the number of canary jobs admitted or already present
     */
    public int canaryTick(Instant now) {
        Optional<BackupPolicy> policy = policyRepository.find();
        if (policy.isEmpty() || !policy.get().scheduleEnabled()) return 0;
        if (!ScheduledBackupWindowPolicy.isCanaryTime(now)) return 0;
        if (!switches.isOn(ScheduledJobFamily.SCHEDULED_BACKUP)) return 0;
        java.time.LocalDate day = now.atZone(JobWindowPolicy.ZONE_ID).toLocalDate();
        if (day.equals(canaryDay)) return 0;
        int admitted = collectService.requestCredentialCanaries(ScheduledBackupWindowPolicy.CANARY_ACTOR, day);
        if (admitted > 0) {
            canaryDay = day;
            LOG.info("[BACKUP_SCHEDULE] credential canary submitted for " + day + ": jobs=" + admitted);
        }
        return admitted;
    }

    /** @return the slot that was run, if one was due and claimed. */
    public Optional<Instant> tick(Instant now) {
        Optional<BackupPolicy> policy = policyRepository.find();
        if (policy.isEmpty() || !policy.get().scheduleEnabled()) {
            return Optional.empty();
        }
        if (!ScheduledBackupWindowPolicy.isOpen(now)) return Optional.empty();
        var localNow = now.atZone(JobWindowPolicy.ZONE_ID);
        Instant dayStart = localNow.toLocalDate().atStartOfDay(JobWindowPolicy.ZONE_ID).toInstant();
        if (policy.get().lastScheduledRunAt().filter(last -> !last.isBefore(dayStart)).isPresent())
            return Optional.empty();
        // Only today's occurrence can be due; a missed 02:00 can run once before 03:00.
        Optional<Instant> due = BackupScheduleDue.dueSlot(policy.get().dailyBackupCron(),
                policy.get().lastScheduledRunAt(), now, 3600, JobWindowPolicy.ZONE_ID);
        if (due.isEmpty()) return Optional.empty();
        if (due.get().isBefore(ScheduledBackupWindowPolicy.start(now).toInstant())) return Optional.empty();
        // V203: the family's switch, checked before the day is claimed, so switching it back on inside 02:00-02:59 still runs today.
        if (!switches.isOn(ScheduledJobFamily.SCHEDULED_BACKUP)) {
            // One line per night, not one per minute of the 02:00 window.
            if (now.isBefore(ScheduledBackupWindowPolicy.start(now).plusMinutes(1).toInstant()))
                LOG.info("[BACKUP_SCHEDULE] skipped: scheduled family scheduled_backup is switched OFF");
            return Optional.empty();
        }
        // Fence the whole calendar day, even if another instance read an older policy or a more frequent cron.
        if (!policyRepository.claimScheduledRun(dayStart, now, ACTOR, ACTION_SLOT_CLAIMED)) {
            return Optional.empty(); // another instance took it, or the schedule was just disabled
        }
        Optional<Instant> slot = Optional.of(ScheduledBackupWindowPolicy.start(now).toInstant());
        BackupCollectService.BulkOutcome outcome = collectService.requestCollectAll(ACTOR,
                "Scheduled fleet backup (slot " + slot.get() + ")");
        LOG.info("[BACKUP_SCHEDULE] slot " + slot.get() + ": targets=" + outcome.targets() + " admitted="
                + outcome.admitted() + " refused=" + outcome.refused() + " held_by_credential_alert=" + outcome.held());
        // No MDS export is ever scheduled (PO 2026-11-11, "İzlenen elle çalışsın"; MDS fork storm fix contract B.1):
        // an operator starts it by hand and watches it.
        return slot;
    }
}
