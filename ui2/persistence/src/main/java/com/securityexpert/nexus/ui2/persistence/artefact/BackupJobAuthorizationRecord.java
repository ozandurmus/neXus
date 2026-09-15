package com.securityexpert.nexus.ui2.persistence.artefact;

/**
 * {@code backup_job_authorization} row (migration V23, BK-12/BW-4): the
 * immutable evidence {@code BackupCollectService} recorded at admission
 * time -- the actor and reason the pilot-allowlist/role/reason gates
 * actually verified -- for {@code BackupJobExecutor} to re-check at claim
 * time, when those facts may no longer hold.
 */
public record BackupJobAuthorizationRecord(String jobId, String deviceId, String actorFingerprint, String reason) {
}
