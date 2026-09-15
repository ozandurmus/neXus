package com.securityexpert.nexus.ui2.persistence.artefact;

import java.util.Optional;

/**
 * {@code backup_job_authorization} persistence (migration V23, BK-12/BW-4).
 * Written once, by {@code BackupCollectService}, at the moment a backup job
 * is admitted -- never updated, mirroring the "immutable request reason"
 * requirement. Read by {@code BackupJobExecutor} at claim time; a missing
 * row is refused, never inferred (see the migration's own compatibility
 * note).
 */
public interface BackupJobAuthorizationRepository {

    void record(String jobId, String deviceId, String actorFingerprint, String reason, String actionId);

    Optional<BackupJobAuthorizationRecord> find(String jobId);
}
