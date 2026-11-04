package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;

/**
 * Everything one build reads: enrolled Check Point / Palo Alto devices with their newest inventory run, and the
 * overrides. The builder never reads anything else and never contacts a device.
 *
 * @param idSalt per-snapshot secret for the opaque ids; never persisted, logged or returned
 */
public record TopologyInput(String snapshotId, Instant builtAt, List<DeviceFacts> devices,
        List<TopologyLinkOverride> overrides, byte[] idSalt) {

    public static final String VENDOR_CHECK_POINT = "check_point";
    public static final String VENDOR_PALO_ALTO = "palo_alto";

    /**
     * @param clusterMemberRef the enrolled {@code devices.cluster_member_ref} (peer-follow PF-2); the only cluster
     *                         identity -- a hostname or a VIP never identifies a unit (design §3)
     */
    public record DeviceFacts(String deviceId, String vendor, Optional<String> hostname, Optional<String> clusterMemberRef,
            Optional<InventoryRun> latestRun) {
        public DeviceFacts {
            Objects.requireNonNull(deviceId, "deviceId");
            Objects.requireNonNull(vendor, "vendor");
            hostname = hostname == null ? Optional.empty() : hostname;
            clusterMemberRef = clusterMemberRef == null ? Optional.empty() : clusterMemberRef.filter(r -> !r.isBlank());
            latestRun = latestRun == null ? Optional.empty() : latestRun;
        }
    }

    public TopologyInput {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(builtAt, "builtAt");
        devices = List.copyOf(devices);
        overrides = List.copyOf(overrides);
        Objects.requireNonNull(idSalt, "idSalt");
        if (idSalt.length < 16) {
            throw new IllegalArgumentException("id salt too short");
        }
        idSalt = idSalt.clone();
    }

    @Override
    public byte[] idSalt() {
        return idSalt.clone();
    }
}
