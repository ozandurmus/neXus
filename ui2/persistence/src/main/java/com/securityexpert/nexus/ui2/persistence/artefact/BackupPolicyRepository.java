package com.securityexpert.nexus.ui2.persistence.artefact;

import java.time.Instant;
import java.util.Optional;

/** V44: the one backup policy row -- schedule, retention -- read by the scheduler and the pruner, written by backup admins. */
public interface BackupPolicyRepository {

    record BackupPolicy(String policyId, boolean scheduleEnabled, String dailyBackupCron, int backupRetentionDays,
            int snapshotRetentionDepth, Optional<Instant> lastScheduledRunAt, Instant updatedAt) {
    }

    Optional<BackupPolicy> find();

    /** Audited UPDATE of the operator-set fields; false when the row is absent. */
    boolean update(boolean scheduleEnabled, String dailyBackupCron, int backupRetentionDays, int snapshotRetentionDepth,
            String actorFingerprint, String actionId);

    /**
     * Claims one scheduled run: sets {@code last_scheduled_run_at = runAt} only if the stored value is
     * still older than {@code dueSince}, so two service instances never both fire the same slot.
     */
    boolean claimScheduledRun(Instant dueSince, Instant runAt, String actorFingerprint, String actionId);
}
