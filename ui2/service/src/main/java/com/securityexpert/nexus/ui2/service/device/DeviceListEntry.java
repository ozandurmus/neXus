package com.securityexpert.nexus.ui2.service.device;

import java.time.Instant;
import java.util.Objects;

/**
 * One row of the {@code /devices} list (contract §3.1). Every component
 * here is a declared column, rendered verbatim -- {@link #deviceId()} in
 * particular is opaque {@code TEXT}, never parsed, padded, truncated or
 * sorted as a number (identity law).
 */
public record DeviceListEntry(
        String deviceId,
        String vendorHint,
        Instant registeredAt,
        String registrationSource,
        EnrollmentStateView enrollmentState,
        boolean disabled,
        boolean isTestTarget) {

    public DeviceListEntry {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(vendorHint, "vendorHint");
        Objects.requireNonNull(registeredAt, "registeredAt");
        Objects.requireNonNull(registrationSource, "registrationSource");
        Objects.requireNonNull(enrollmentState, "enrollmentState");
    }
}
