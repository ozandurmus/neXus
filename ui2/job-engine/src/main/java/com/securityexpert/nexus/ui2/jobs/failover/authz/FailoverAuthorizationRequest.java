package com.securityexpert.nexus.ui2.jobs.failover.authz;

import java.util.Objects;

/**
 * Immutable value record requesting 4-eyes authorization for a controlled cluster failover (Phase B).
 * Enforces dual-control separation between the initiating operator (requester) and authorizing reviewer (approver).
 */
public record FailoverAuthorizationRequest(
    String clusterRef,
    String requesterId,
    String approverId,
    String reason,
    String maintenanceWindowRef,
    String assessmentDigest,
    String clientNonce
) {
    public FailoverAuthorizationRequest {
        Objects.requireNonNull(clusterRef, "clusterRef must not be null");
        Objects.requireNonNull(requesterId, "requesterId must not be null");
        Objects.requireNonNull(approverId, "approverId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(maintenanceWindowRef, "maintenanceWindowRef must not be null");
        Objects.requireNonNull(assessmentDigest, "assessmentDigest must not be null");
        Objects.requireNonNull(clientNonce, "clientNonce must not be null");
    }
}
