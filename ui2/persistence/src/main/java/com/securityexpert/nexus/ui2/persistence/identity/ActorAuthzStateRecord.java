package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.Instant;
import java.util.Set;

/** A read view of one {@code actor_authz_state} row -- a derived, ephemeral cache (C3 §4.4). */
public record ActorAuthzStateRecord(String actorFingerprint, Set<String> groupReferences,
        Instant resolvedAt, Instant validUntil, String directoryProfileId,
        byte[] principalReferenceEncrypted, String principalReferenceKeyId) {

    public ActorAuthzStateRecord(String actorFingerprint, Set<String> groupReferences, Instant resolvedAt, Instant validUntil) {
        this(actorFingerprint, groupReferences, resolvedAt, validUntil, null, null, null);
    }

    public boolean hasDirectoryProof() {
        return directoryProfileId != null && !directoryProfileId.isBlank()
                && principalReferenceEncrypted != null && principalReferenceKeyId != null;
    }

    @Override public String toString() { return "ActorAuthzStateRecord[redacted]"; }

    public boolean isFresh(Instant asOf) {
        return asOf.isBefore(validUntil);
    }
}
