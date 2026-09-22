package com.securityexpert.nexus.ui2.service.device.backup;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository.BackupPolicy;

/**
 * Runs the fleet backup on the policy's cron (V44, Backbox-model backup
 * screen). Every minute: read the policy; if the schedule is enabled and a
 * slot is due, claim it with a fenced UPDATE (two service instances never
 * fire the same slot) and submit one collect per backup target through the
 * same admission path "Run Fleet Backup" uses, under the scheduler's own
 * actor.
 */
public final class BackupScheduler {

    private static final Logger LOG = Logger.getLogger(BackupScheduler.class.getName());
    public static final String ACTOR = "system:backup-scheduler";
    public static final String ACTION_SLOT_CLAIMED = "backup_schedule_slot_claimed";
    /** A slot missed while the service was down is still run if it is at most this old. */
    static final long CATCH_UP_WINDOW_SECONDS = 6 * 60 * 60;

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
        Optional<Instant> slot = BackupScheduleDue.dueSlot(policy.get().dailyBackupCron(), policy.get().lastScheduledRunAt(),
                now, CATCH_UP_WINDOW_SECONDS);
        if (slot.isEmpty()) {
            return Optional.empty();
        }
        if (!policyRepository.claimScheduledRun(slot.get(), now, ACTOR, ACTION_SLOT_CLAIMED)) {
            return Optional.empty(); // another instance took it, or the schedule was just disabled
        }
        BackupCollectService.BulkOutcome outcome = collectService.requestCollectAll(ACTOR,
                "Scheduled fleet backup (slot " + slot.get() + ")");
        LOG.info("[BACKUP_SCHEDULE] slot " + slot.get() + ": targets=" + outcome.targets() + " admitted="
                + outcome.admitted() + " refused=" + outcome.refused());
        return slot;
    }
}
