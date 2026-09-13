package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.Instant;
import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;

/** A read view of one {@code local_credentials} row (C3A contract §3.2/§5.2, 13G section 4). */
public record LocalCredentialRecord(
        String localIdentityId,
        String localIdentityName,
        byte[] verifier,
        byte[] salt,
        String algorithmId,
        int memoryCostKib,
        int timeCost,
        int parallelism,
        int failedAttemptCount,
        Optional<Instant> lockedUntil,
        Instant createdAt,
        Instant updatedAt,
        /** V10 (13G §4): false while an administrator has disabled this identity. */
        boolean enabled,
        /** V10 (13G §4): the actor that created this row. */
        String createdByActorFingerprint,
        /** V10 (13G §4): when this row's password was last set. */
        Instant passwordSetAt,
        /** V9 (NXS-LOCAL-0152): true while this row still holds the password it was seeded with. */
        boolean mustChangePassword) {

    /** This row's own recorded verifier and parameters -- never the service's current default (§3.2). */
    public Argon2PasswordHasher.Verifier toVerifier() {
        return new Argon2PasswordHasher.Verifier(verifier, salt, algorithmId,
                new Argon2PasswordHasher.Parameters(memoryCostKib, timeCost, parallelism));
    }

    public boolean isLocked(Instant asOf) {
        return lockedUntil.isPresent() && asOf.isBefore(lockedUntil.get());
    }
}
