package com.securityexpert.nexus.ui2.service.security;

import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;

/**
 * The shared, constant-cost verify step behind both a login attempt
 * ({@link LocalMechanism}) and a password change's current-password
 * re-check ({@link PasswordChangeService}) (C3A contract §5.3, §4).
 *
 * <p>Argon2 always runs -- against the row's own recorded parameters when
 * the identity is known, or a fixed, never-matching dummy verifier when it
 * is not -- so an unknown identity pays the identical computation cost a
 * known identity's wrong-password check pays. This is what makes "no code
 * path returns measurably faster for an unknown username than for a known
 * one" (§5.3) true by construction rather than by a tuned sleep: the
 * expensive step is never skipped, only its result is sometimes
 * discarded.</p>
 */
final class LocalCredentialVerification {

    /**
     * Never derived from, or compared against, any real credential --
     * generated once per process so its own Argon2 cost is representative,
     * and never persisted anywhere.
     */
    private static final Argon2PasswordHasher.Verifier DUMMY_VERIFIER =
            Argon2PasswordHasher.hash("no-such-local-identity-marker".toCharArray(),
                    Argon2PasswordHasher.DEFAULT_PARAMETERS);

    private LocalCredentialVerification() {
    }

    /** @return whether {@code credential} matches {@code row}'s stored verifier; always {@code false} when {@code row} is empty. */
    static boolean matches(Optional<LocalCredentialRecord> row, char[] credential) {
        Argon2PasswordHasher.Verifier toCheck = row.map(LocalCredentialRecord::toVerifier).orElse(DUMMY_VERIFIER);
        return Argon2PasswordHasher.verify(credential, toCheck);
    }
}
