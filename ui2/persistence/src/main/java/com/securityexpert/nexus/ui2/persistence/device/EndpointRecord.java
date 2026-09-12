package com.securityexpert.nexus.ui2.persistence.device;

import java.time.Instant;

/**
 * A read view of one {@code endpoints} row (C1 §3.2). {@link #toString()}
 * omits {@link #addressRef()} -- the management address is CLASS 2 and
 * must never appear in a log line (contract §7).
 */
public record EndpointRecord(String endpointId, String deviceId, String transportKind, String addressRef,
        Instant createdAt) {

    @Override
    public String toString() {
        return "EndpointRecord[endpointId=" + endpointId + ", deviceId=" + deviceId
                + ", transportKind=" + transportKind + ", addressRef=<redacted>, createdAt=" + createdAt + "]";
    }
}
