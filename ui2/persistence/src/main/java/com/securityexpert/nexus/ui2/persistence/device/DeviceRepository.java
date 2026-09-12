package com.securityexpert.nexus.ui2.persistence.device;

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

    Optional<DeviceRecord> find(String deviceId);

    Optional<EndpointRecord> findEndpoint(String endpointId);

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

    /** Contract §3 {@code any -> disabled}: a separate boolean column, not a state. */
    boolean setDisabled(String deviceId, boolean disabled, String actorFingerprint, String actionId);
}
