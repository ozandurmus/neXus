package com.securityexpert.nexus.ui2.persistence.device;

import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * {@code devices}/{@code endpoints} persistence port (contract §12/F12
 * placement: device repositories live in {@code persistence}). The
 * registering role check and every validation in contract §4 are the
 * service layer's job ({@code service} module, F12); this port only ever
 * performs the write or read it is asked to.
 */
public interface DeviceRepository {

    enum BackupDisposition {
        KEEP,
        REMOVE
    }

    record DeleteResult(boolean deleted, long backupArtefactCount, boolean dispositionRequired) {
    }

    Optional<DeviceRecord> find(String deviceId);

    Optional<EndpointRecord> findEndpoint(String endpointId);

    /** The one endpoint a manually-registered device carries (B1-4b contract §4: one device, one endpoint at this maturity). */
    Optional<EndpointRecord> findEndpointByDeviceId(String deviceId);

    /**
     * The device (if any) that already owns an endpoint at this exact address
     * reference -- the Product Owner's rule (2026-09-22): a device is never
     * registered twice under the same address. Case-insensitive, whitespace
     * ignored; a disabled device still counts (delete it first).
     */
    default Optional<String> findDeviceIdByEndpointAddress(String addressRef) {
        return Optional.empty();
    }

    /**
     * Registers a device as {@code DRAFT} together with its one endpoint,
     * in a single audited transaction (contract §4: "the devices INSERT
     * (and paired endpoints... rows) each get an audit_log row" -- one
     * transaction, two INSERTs, two audit rows, or neither row exists;
     * C1 §3.5's fail-closed trigger makes a partial write impossible).
     *
     * @return the registered device's opaque {@code device_id}
     */
    String registerDraft(DeviceDraft draft, String actorFingerprint, String actionId);

    /**
     * Applies one contract §3 transition, guarded by a
     * {@code WHERE enrollment_state = fromState} condition so a
     * concurrent or illegal transition never silently overwrites another.
     *
     * @return {@code true} only if a row matching {@code deviceId} AND
     *         currently in {@code fromState} was updated; {@code false}
     *         otherwise -- never throws for a mismatch, so the caller
     *         (which already validated {@link DeviceEnrollmentState#canTransitionTo})
     *         can distinguish "device not found" from "state already
     *         moved on" without a race window.
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

    /**
     * EC-J3: the confirm job's completion handler moves the row {@code
     * DRAFT -> ENROLLED} with the observed facts, atomically, in one
     * fenced update guarded by {@code WHERE enrollment_state = 'DRAFT'} --
     * the same fenced-update pattern {@link #transitionEnrollmentState}
     * uses. No other write path ever sets any V12 column or moves a row to
     * {@code ENROLLED} on first contact (EC-J3: "no other code path
     * changes enrollment state on first contact"). Never called for any
     * job kind other than the enrollment confirm (EC-J1).
     *
     * @return {@code true} only if a row matching {@code deviceId} AND
     *         currently {@code DRAFT} was updated; {@code false} means the
     *         device was not found or already left {@code DRAFT} (a
     *         zombie writer's own signal to stop, mirroring {@link
     *         #transitionEnrollmentState}'s contract).
     */
    boolean recordConfirmSuccess(String deviceId, DeviceConfirmFacts facts, String actorFingerprint, String actionId);

    /** The V12 facts a confirmed device carries, for a read model (GET /devices, GET /devices/{id}). */
    Optional<DeviceConfirmFacts> findConfirmFacts(String deviceId);

    /** Sets observed hostname / software version only where they are still empty (a later read never overwrites). */
    default boolean fillObservedIdentityIfAbsent(String deviceId, Optional<String> hostname, Optional<String> softwareVersion,
            String actorFingerprint, String actionId) {
        return false;
    }

    /** GET /devices (DeviceSummary rows), newest first -- every device, its vendor and its observed facts. */
    List<DeviceSummaryRecord> listAll();

    /** Backups > Backup targets: an audited devices UPDATE; false when the row is absent or already in that state. */
    default boolean setBackupTarget(String deviceId, boolean backupTarget, String actorFingerprint, String actionId) {
        throw new UnsupportedOperationException("setBackupTarget");
    }

    /**
     * The same coalesced view {@link #listAll()} produces (device's own confirmed facts, falling
     * back to its discovery candidate's, per {@code DEVICE_SUMMARY_SELECT}), narrowed to one
     * device -- so a value such as {@code observedModel()} is available before that device has
     * ever been confirmed, whenever its discovery candidate already carried one (e.g. a Check
     * Point Management Server's own "hardware" field).
     */
    default Optional<DeviceSummaryRecord> findSummary(String deviceId) {
        return listAll().stream().filter(summary -> summary.deviceId().equals(deviceId)).findFirst();
    }

    /** Returns members of a specific cluster (by cluster_member_ref or candidate display name) directly without full scan. */
    default List<DeviceSummaryRecord> findMembersByClusterRef(String clusterMemberRef) {
        return listAll().stream()
                .filter(summary -> summary.clusterMemberRef().equals(Optional.ofNullable(clusterMemberRef)))
                .toList();
    }
}
