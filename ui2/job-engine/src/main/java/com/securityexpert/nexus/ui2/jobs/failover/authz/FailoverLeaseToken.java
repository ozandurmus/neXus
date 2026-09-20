package com.securityexpert.nexus.ui2.jobs.failover.authz;

import java.time.Instant;
import java.util.Objects;

/**
 * Ephemeral, single-use cryptographic lease token issued upon passing the 4-eyes authorization gate.
 * Valid for at most 15 minutes and bound to the explicit pre-flight assessment digest.
 */
public record FailoverLeaseToken(
    String tokenId,
    String clusterRef,
    String requesterId,
    String approverId,
    String assessmentDigest,
    Instant issuedAt,
    Instant expiresAt,
    String tokenSignature
) {
    public FailoverLeaseToken {
        Objects.requireNonNull(tokenId, "tokenId must not be null");
        Objects.requireNonNull(clusterRef, "clusterRef must not be null");
        Objects.requireNonNull(requesterId, "requesterId must not be null");
        Objects.requireNonNull(approverId, "approverId must not be null");
        Objects.requireNonNull(assessmentDigest, "assessmentDigest must not be null");
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(tokenSignature, "tokenSignature must not be null");
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
