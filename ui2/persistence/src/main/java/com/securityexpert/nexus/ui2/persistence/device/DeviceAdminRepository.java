package com.securityexpert.nexus.ui2.persistence.device;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository.BackupDisposition;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository.DeleteResult;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * Service-exclusive administrative {@code devices}/{@code endpoints} persistence port
 * ({@code docs/design/DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md} §2.5.3, §4.2 Step 0).
 *
 * <p>Only {@code ui2_service} holds {@code INSERT} and {@code DELETE} on {@code devices}/{@code endpoints} and the
 * cross-plane cascading {@code DELETE} privileges required by {@link #deleteDevice}. Worker, scheduler, and packet
 * analysis classes are forbidden from invoking these methods (enforced by {@code ModuleDatabaseOwnershipArchTest}).</p>
 */
public interface DeviceAdminRepository extends DeviceInventoryWriteRepository {

    /**
     * Registers a device as {@code DRAFT} together with its one endpoint,
     * in a single audited transaction.
     *
     * @return the registered device's opaque {@code device_id}
     */
    String registerDraft(DeviceDraft draft, String actorFingerprint, String actionId);

    /**
     * Applies one contract §3 transition, guarded by a
     * {@code WHERE enrollment_state = fromState} condition so a
     * concurrent or illegal transition never silently overwrites another.
     */
    boolean transitionEnrollmentState(String deviceId, DeviceEnrollmentState fromState, DeviceEnrollmentState toState,
            String actorFingerprint, String actionId);

    /** Contract §3 {@code DRAFT -> deleted}: withdrawal before confirmation. */
    boolean withdrawDraft(String deviceId, String actorFingerprint, String actionId);

    /** Deletes a device and its device-owned records in one audited transaction. */
    default DeleteResult deleteDevice(String deviceId, BackupDisposition backupDisposition,
            String actorFingerprint, String actionId) {
        return new DeleteResult(false, 0, false);
    }

    /** Contract §3 {@code any -> disabled}: a separate boolean column, not a state. */
    boolean setDisabled(String deviceId, boolean disabled, String actorFingerprint, String actionId);

    /** The device's login credential, replaced (PO, 2026-09-24); true when a row changed. */
    default boolean setCredentialReference(String deviceId, String credentialReferenceId, String actorFingerprint, String actionId) {
        throw new UnsupportedOperationException("setCredentialReference");
    }

    /** Backups > Backup targets: an audited devices UPDATE; false when the row is absent or already in that state. */
    default boolean setBackupTarget(String deviceId, boolean backupTarget, String actorFingerprint, String actionId) {
        throw new UnsupportedOperationException("setBackupTarget");
    }
}
