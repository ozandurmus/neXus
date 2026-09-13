package com.securityexpert.nexus.ui2.service.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.platform.AttemptOutcome;
import com.securityexpert.nexus.ui2.platform.Clock;
import com.securityexpert.nexus.ui2.platform.Mechanism;
import com.securityexpert.nexus.ui2.platform.PrincipalFingerprint;

/**
 * The {@code local} {@link Mechanism} (C3A contract §2.1, §5): the
 * permanent, non-removable registry member (enforced structurally by
 * {@link MechanismRegistry}'s constructor, not by this class).
 *
 * <p>Every clause below carries the contract clause it implements:</p>
 * <ul>
 *   <li>LOCK-1/LOCK-2 (§5.1): per-identity failure counting, threshold 5.</li>
 *   <li>§5.2: on the 5th failure, {@code locked_until} = now + 15 minutes;
 *       a locked identity is refused even with the correct password, and a
 *       post-expiry success clears the counter and the lock.</li>
 *   <li>§5.3: an unknown identity, a wrong password, an empty password and
 *       an active lockout all reach the identical refusal outcome, and
 *       {@link LocalCredentialVerification} guarantees none of them is
 *       computed measurably faster than the others.</li>
 * </ul>
 */
public final class LocalMechanism implements Mechanism {

    public static final String MECHANISM_ID = "local";
    public static final String LOCAL_PRINCIPAL_PREFIX = "local:";

    /** LOCK-2. */
    public static final int LOCKOUT_THRESHOLD = 5;
    /** §5.2. */
    public static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private static final String REASON_UNKNOWN_IDENTITY = "unknown_identity";
    private static final String REASON_LOCKED = "locked";
    private static final String REASON_WRONG_PASSWORD = "wrong_password";

    private final LocalCredentialsRepository repository;
    private final Clock clock;

    public LocalMechanism(LocalCredentialsRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String mechanismId() {
        return MECHANISM_ID;
    }

    @Override
    public AttemptOutcome attempt(String identity, char[] credential) {
        Instant now = clock.now();
        Optional<LocalCredentialRecord> row = repository.findByName(identity);

        // §5.3: Argon2 runs unconditionally, before any branch below --
        // whether the identity is unknown, locked, or the password is
        // simply wrong, the same computation happens first.
        boolean matches = LocalCredentialVerification.matches(row, credential);

        if (row.isEmpty()) {
            return AttemptOutcome.refused(REASON_UNKNOWN_IDENTITY);
        }
        LocalCredentialRecord record = row.get();
        if (record.isLocked(now)) {
            // §5.2: refused regardless of `matches` -- the computed result
            // above is deliberately discarded while locked.
            return AttemptOutcome.refused(REASON_LOCKED);
        }
        if (!matches) {
            repository.recordFailedAttempt(record.localIdentityId(), now, LOCKOUT_THRESHOLD, LOCKOUT_DURATION);
            return AttemptOutcome.refused(REASON_WRONG_PASSWORD);
        }
        String actorFingerprint = actorFingerprintFor(record.localIdentityId());
        repository.recordSuccessfulLogin(record.localIdentityId(), now, actorFingerprint);
        return AttemptOutcome.success(actorFingerprint);
    }

    public static String actorFingerprintFor(String localIdentityId) {
        return PrincipalFingerprint.of(LOCAL_PRINCIPAL_PREFIX + localIdentityId);
    }
}
