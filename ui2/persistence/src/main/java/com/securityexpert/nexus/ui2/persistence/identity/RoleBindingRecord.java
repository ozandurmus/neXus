package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.Instant;
import com.securityexpert.nexus.ui2.platform.DirectoryBindingKind;
import java.util.Optional;

/** A read view of one {@code role_bindings} row (C3 §4.2). */
public record RoleBindingRecord(
        String bindingId,
        String roleToken,
        byte[] groupReferenceEncrypted,
        String groupReferenceKeyId,
        String createdByActorFingerprint,
        Instant createdAt,
        Optional<Instant> revokedAt,
        Optional<String> revokedByActorFingerprint, DirectoryBindingKind bindingKind, String directoryProfileId) {

    public RoleBindingRecord(String bindingId, String roleToken, byte[] groupReferenceEncrypted,
            String groupReferenceKeyId, String createdByActorFingerprint, Instant createdAt,
            Optional<Instant> revokedAt, Optional<String> revokedByActorFingerprint) {
        this(bindingId, roleToken, groupReferenceEncrypted, groupReferenceKeyId, createdByActorFingerprint,
                createdAt, revokedAt, revokedByActorFingerprint, DirectoryBindingKind.LEGACY, null);
    }

    @Override public String toString() { return "RoleBindingRecord[redacted]"; }

    public boolean isActive() {
        return revokedAt.isEmpty();
    }
}
