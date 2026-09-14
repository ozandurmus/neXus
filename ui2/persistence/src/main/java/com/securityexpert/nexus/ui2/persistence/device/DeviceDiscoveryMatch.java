package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Optional;

/**
 * RD-5's own read shape: what an existing {@code devices} row carries for
 * the three-outcome comparison (14F import §3.3) against a discovery
 * candidate sharing its {@link com.securityexpert.nexus.ui2.persistence.device.DeviceDraft#discoveryMatchKey()}
 * -- the recorded management address (via the device's one endpoint) and
 * the recorded target-modifier shape (cluster member vs. virtual system vs.
 * plain physical), never the candidate's own current values (those are the
 * caller's own side of the comparison).
 *
 * <p>{@link #toString()} omits {@link #addressRef()} (CLASS 2).</p>
 */
public record DeviceDiscoveryMatch(String deviceId, String addressRef, Optional<String> clusterMemberRef,
        Optional<String> virtualSystemRef) {

    @Override
    public String toString() {
        return "DeviceDiscoveryMatch[deviceId=" + deviceId + ", addressRef=<redacted>, clusterMemberRef="
                + clusterMemberRef + ", virtualSystemRef=" + virtualSystemRef + "]";
    }
}
