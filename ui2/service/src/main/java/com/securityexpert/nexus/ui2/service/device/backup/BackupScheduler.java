package com.securityexpert.nexus.ui2.service.device.backup;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository.BackupPolicy;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ScheduledBackupWindowPolicy;

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

    public BackupScheduler(BackupPolicyRepository policyRepository, BackupCollectService collectService) {
        this.policyRepository = Objects.requireNonNull(policyRepository, "policyRepository");
        this.collectService = Objects.requireNonNull(collectService, "collectService");
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
        // Fence the whole calendar day, even if another instance read an older policy or a more frequent cron.
        if (!policyRepository.claimScheduledRun(dayStart, now, ACTOR, ACTION_SLOT_CLAIMED)) {
            return Optional.empty(); // another instance took it, or the schedule was just disabled
        }
        Optional<Instant> slot = Optional.of(ScheduledBackupWindowPolicy.start(now).toInstant());
        BackupCollectService.BulkOutcome outcome = collectService.requestCollectAll(ACTOR,
                "Scheduled fleet backup (slot " + slot.get() + ")");
        LOG.info("[BACKUP_SCHEDULE] slot " + slot.get() + ": targets=" + outcome.targets() + " admitted="
                + outcome.admitted() + " refused=" + outcome.refused());
        collectService.requestScheduledMdsExports();
        return slot;
    }
}
