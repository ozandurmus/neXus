package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.Instant;
import java.util.Set;

/** A read view of one {@code actor_authz_state} row -- a derived, ephemeral cache (C3 §4.4). */
public record ActorAuthzStateRecord(String actorFingerprint, Set<String> groupReferences,
        Instant resolvedAt, Instant validUntil) {

    public boolean isFresh(Instant asOf) {
        return asOf.isBefore(validUntil);
    }
}
