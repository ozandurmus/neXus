package com.securityexpert.nexus.ui2.persistence.device;
// 14I MS-1

import java.util.Objects;
import java.util.Optional;

/**
 * The inputs to one registration (contract §4 "Captured"): a device, its
 * one endpoint, and a reference to an already-existing {@code
 * credential_reference}. Every identifier here is opaque, assigned by the
 * caller from {@link com.securityexpert.nexus.ui2.platform.OpaqueId}
 * before this record is built -- this type never generates one itself.
 *
 * <p>{@link #clusterMemberRef()}/{@link #virtualSystemRef()} are the
 * {@code C4} §4.2 target modifiers (14F import §2, IM-7's endpoint mapping
 * companion): empty for a manual single-device registration (peer follow
 * sets {@link #clusterMemberRef()} later, post-corroboration, through
 * {@code DeviceRepository}'s own transition methods, never through this
 * draft), populated at draft time for a discovery import, which already
 * resolved the grouping structurally at discovery. {@link
 * #discoveryMatchKey()} is IM-10's carried vendor stable identifier
 * (opaque, never the row's own {@code device_id}) -- empty for a manually
 * registered device, which has no discovery-time identifier to carry.</p>
 *
 * <p>{@link #toString()} is overridden to omit {@link #addressRef()}: the
 * management address is CLASS 2 and must never be logged (contract §7),
 * and a record's generated {@code toString()} would otherwise print every
 * component including it.</p>
 */
public record DeviceDraft(
        String deviceId,
        String role,
        String vendorHint,
        String registrationSource,
        boolean isTestTarget,
        String credentialReferenceId,
        String endpointId,
        String transportKind,
        String addressRef,
        Optional<String> clusterMemberRef,
        Optional<String> virtualSystemRef,
        Optional<String> discoveryMatchKey) {

    public DeviceDraft {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(vendorHint, "vendorHint");
        Objects.requireNonNull(registrationSource, "registrationSource");
        Objects.requireNonNull(credentialReferenceId, "credentialReferenceId");
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(transportKind, "transportKind");
        Objects.requireNonNull(addressRef, "addressRef");
        clusterMemberRef = clusterMemberRef == null ? Optional.empty() : clusterMemberRef;
        virtualSystemRef = virtualSystemRef == null ? Optional.empty() : virtualSystemRef;
        discoveryMatchKey = discoveryMatchKey == null ? Optional.empty() : discoveryMatchKey;
    }

    /** The manual-registration shape (B1-4b §4): no target modifier, no discovery match key -- every existing call site's own arity. */
    public DeviceDraft(String deviceId, String role, String vendorHint, String registrationSource, boolean isTestTarget,
            String credentialReferenceId, String endpointId, String transportKind, String addressRef) {
        this(deviceId, role, vendorHint, registrationSource, isTestTarget, credentialReferenceId, endpointId,
                transportKind, addressRef, Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Override
    public String toString() {
        return "DeviceDraft[deviceId=" + deviceId + ", role=" + role + ", vendorHint=" + vendorHint
                + ", registrationSource=" + registrationSource + ", isTestTarget=" + isTestTarget
                + ", credentialReferenceId=" + credentialReferenceId + ", endpointId=" + endpointId
                + ", transportKind=" + transportKind + ", addressRef=<redacted>, clusterMemberRef=" + clusterMemberRef
                + ", virtualSystemRef=" + virtualSystemRef + "]";
    }
}
