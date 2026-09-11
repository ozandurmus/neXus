package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.Instant;
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
        Optional<String> revokedByActorFingerprint) {

    public boolean isActive() {
        return revokedAt.isEmpty();
    }
}
