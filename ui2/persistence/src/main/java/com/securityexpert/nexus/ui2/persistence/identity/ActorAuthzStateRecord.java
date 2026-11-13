package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.Instant;
import java.util.Set;

/**
 * A read view of one {@code actor_authz_state} row -- a derived, ephemeral cache (C3 §4.4).
 * {@code directoryDisplayName} is display only (the directory account name shown in the app bar); it is never the
 * identity key, never compared for authorization, and is null for a row written before V211.
 */
public record ActorAuthzStateRecord(String actorFingerprint, Set<String> groupReferences,
        Instant resolvedAt, Instant validUntil, String directoryProfileId,
        byte[] principalReferenceEncrypted, String principalReferenceKeyId, String directoryDisplayName) {

    public ActorAuthzStateRecord(String actorFingerprint, Set<String> groupReferences, Instant resolvedAt, Instant validUntil) {
        this(actorFingerprint, groupReferences, resolvedAt, validUntil, null, null, null, null);
    }

    public ActorAuthzStateRecord(String actorFingerprint, Set<String> groupReferences, Instant resolvedAt, Instant validUntil,
            String directoryProfileId, byte[] principalReferenceEncrypted, String principalReferenceKeyId) {
        this(actorFingerprint, groupReferences, resolvedAt, validUntil, directoryProfileId, principalReferenceEncrypted,
                principalReferenceKeyId, null);
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
