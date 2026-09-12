package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Objects;

/**
 * The inputs to one manual registration (contract §4 "Captured"): a
 * device, its one endpoint, and a reference to an already-existing
 * {@code credential_reference}. Every identifier here is opaque, assigned
 * by the caller from {@link com.securityexpert.nexus.ui2.platform.OpaqueId}
 * before this record is built -- this type never generates one itself.
 *
 * <p>{@link #toString()} is overridden to omit {@link #addressRef()}: the
 * management address is CLASS 2 and must never be logged (contract §7),
 * and a record's generated {@code toString()} would otherwise print every
 * component including it.</p>
 */
public record DeviceDraft(
        String deviceId,
        String vendorHint,
        String registrationSource,
        boolean isTestTarget,
        String credentialReferenceId,
        String endpointId,
        String transportKind,
        String addressRef) {

    public DeviceDraft {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(vendorHint, "vendorHint");
        Objects.requireNonNull(registrationSource, "registrationSource");
        Objects.requireNonNull(credentialReferenceId, "credentialReferenceId");
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(transportKind, "transportKind");
        Objects.requireNonNull(addressRef, "addressRef");
    }

    @Override
    public String toString() {
        return "DeviceDraft[deviceId=" + deviceId + ", vendorHint=" + vendorHint
                + ", registrationSource=" + registrationSource + ", isTestTarget=" + isTestTarget
                + ", credentialReferenceId=" + credentialReferenceId + ", endpointId=" + endpointId
                + ", transportKind=" + transportKind + ", addressRef=<redacted>]";
    }
}
