package com.securityexpert.nexus.ui2.persistence.device;

import java.time.Instant;

import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * A read view of one {@code devices} row (C1 §3.2, B1-4b contract §2/§3).
 * Carries no display label/name column: C1 §3.2's sketch names none, and
 * this movement does not invent one (§7's sensitive-field list already
 * covers {@code endpoints.address_ref}; a name column would add a second
 * sensitive field with no contract basis).
 */
public record DeviceRecord(
        String deviceId,
        String vendorHint,
        String registrationSource,
        Instant createdAt,
        boolean isTestTarget,
        DeviceEnrollmentState enrollmentState,
        boolean disabled,
        String credentialReferenceId) {

    /** Contract §3: eligible for read-class collection, and not disabled. */
    public boolean permitsReadCollection() {
        return !disabled && enrollmentState.permitsReadCollection();
    }
}
