package com.securityexpert.nexus.ui2.service.device;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code /devices/{device_id}} payload (contract §3.2, §4.2, §5, §6.1).
 * Every component is a declared column or a derived relationship §3-§6
 * name; nothing else is added.
 *
 * <p>Deliberately absent, structurally (not merely unpopulated): {@code
 * credential_reference_id}'s value (only {@link #credentialConfigured()},
 * a boolean, per §3.2), every {@code credential_references} column, {@code
 * endpoints.address_ref} (see {@link TransportSummary}), and any freshness
 * /health/timestamp component for {@link #enrollmentState()} (§5.1,
 * {@code UNKNOWN-3}).</p>
 *
 * @param actionAffordance contract §6.1's map; keys never vary by actor
 *                          (proven at the call site that supplies the
 *                          action id list, not by this type)
 */
public record DeviceWorkspaceView(
        String deviceId,
        String vendorHint,
        String registrationSource,
        Instant createdAt,
        boolean isTestTarget,
        boolean credentialConfigured,
        EnrollmentStateView enrollmentState,
        boolean disabled,
        TransportSummary transport,
        Map<String, ActionAffordanceView> actionAffordance) {

    public DeviceWorkspaceView {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(vendorHint, "vendorHint");
        Objects.requireNonNull(registrationSource, "registrationSource");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(enrollmentState, "enrollmentState");
        Objects.requireNonNull(transport, "transport");
        actionAffordance = Map.copyOf(actionAffordance);
    }
}
