package com.securityexpert.nexus.ui2.service.device.backup;

import java.time.Instant;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository.BackupPolicy;

/** Uses the existing policy enable switch and durable claim fence at the shared six-hour slots. */
public final class BackupScheduler {

    private static final Logger LOG = Logger.getLogger(BackupScheduler.class.getName());
    public static final String ACTOR = "system:backup-scheduler";
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
        if (!JobWindowPolicy.SYSTEM.isSlotMinute(now)) return Optional.empty();
        Optional<Instant> slot = Optional.of(JobWindowPolicy.SYSTEM.slotStart(now).toInstant());
        if (policy.get().lastScheduledRunAt().filter(last -> !last.isBefore(slot.get())).isPresent())
            return Optional.empty();
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
