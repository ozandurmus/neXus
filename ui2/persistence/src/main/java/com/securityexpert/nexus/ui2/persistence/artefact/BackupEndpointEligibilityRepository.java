package com.securityexpert.nexus.ui2.persistence.artefact;

/**
 * {@code backup_endpoint_ineligibility} persistence (migration V18).
 * WORKER.md: "record cleanup_failed as a failure marking the endpoint
 * ineligible if the delete fails." A device stays marked until a later
 * successful run's own job executor clears the row -- distinct from {@code
 * devices.disabled}, a device-wide concept this movement never touches.
 */
public interface BackupEndpointEligibilityRepository {

    boolean isIneligible(String deviceId);

    void markIneligible(String deviceId, String reason, String actorFingerprint, String actionId);

    /** Cleared by a later run's own successful cleanup -- idempotent, no error if the device was never marked. */
    void clear(String deviceId, String actorFingerprint, String actionId);
}
