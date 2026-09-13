package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.Instant;
import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;

/** A read view of one {@code local_credentials} row (C3A contract §3.2/§5.2). */
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
        Instant updatedAt) {

    /** This row's own recorded verifier and parameters -- never the service's current default (§3.2). */
    public Argon2PasswordHasher.Verifier toVerifier() {
        return new Argon2PasswordHasher.Verifier(verifier, salt, algorithmId,
                new Argon2PasswordHasher.Parameters(memoryCostKib, timeCost, parallelism));
    }

    public boolean isLocked(Instant asOf) {
        return lockedUntil.isPresent() && asOf.isBefore(lockedUntil.get());
    }
}
