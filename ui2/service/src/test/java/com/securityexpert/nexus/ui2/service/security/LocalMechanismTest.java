package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;
import com.securityexpert.nexus.ui2.platform.AttemptOutcome;
import com.securityexpert.nexus.ui2.platform.Clock;

/**
 * C3A contract §11.2 tests 4, 5, 6 (lockout threshold/effect/clearing;
 * refusal uniformity) and §5.3's timing requirement, exercised against
 * {@link LocalMechanism} with an in-memory fake -- the pure-logic half; the
 * database half (the CHECK/UNIQUE constraints and the audit trigger) is a
 * Testcontainers-dependent test this environment cannot run (see
 * integration-tests, SESSION_CLOSE).
 */
class LocalMechanismTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    private static final char[] REAL_PASSWORD = "correct-horse-battery-staple".toCharArray();

    private static final class FixedClock implements Clock {
        private Instant now;

        FixedClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant now() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    /** Enforces nothing beyond a plain map -- LocalMechanism's own logic is what this test proves. */
    private static final class InMemoryLocalCredentialsRepository implements LocalCredentialsRepository {
        private final Map<String, LocalCredentialRecord> byId = new HashMap<>();
        private final Map<String, String> idByName = new HashMap<>();

        String seed(String name) {
            String id = "id-" + name;
            Argon2PasswordHasher.Verifier verifier = Argon2PasswordHasher.hash(REAL_PASSWORD, Argon2PasswordHasher.DEFAULT_PARAMETERS);
            idByName.put(name, id);
            byId.put(id, new LocalCredentialRecord(id, name, verifier.verifier(), verifier.salt(), verifier.algorithmId(),
                    verifier.parameters().memoryCostKib(), verifier.parameters().timeCost(), verifier.parameters().parallelism(),
                    0, Optional.empty(), NOW, NOW, true, "system:bootstrap", NOW, false));
            return id;
        }

        LocalCredentialRecord row(String id) {
            return byId.get(id);
        }

        /** 13G LIA-3.6 test helper. */
        void disable(String id) {
            LocalCredentialRecord r = byId.get(id);
            byId.put(id, new LocalCredentialRecord(r.localIdentityId(), r.localIdentityName(), r.verifier(), r.salt(),
                    r.algorithmId(), r.memoryCostKib(), r.timeCost(), r.parallelism(), r.failedAttemptCount(),
                    r.lockedUntil(), r.createdAt(), r.updatedAt(), false, r.createdByActorFingerprint(),
                    r.passwordSetAt(), r.mustChangePassword()));
        }

        @Override
        public Optional<LocalCredentialRecord> findByName(String localIdentityName) {
            String id = idByName.get(localIdentityName);
            return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<LocalCredentialRecord> findById(String localIdentityId) {
            return Optional.ofNullable(byId.get(localIdentityId));
        }

        @Override
        public String create(String localIdentityId, String localIdentityName, Argon2PasswordHasher.Verifier verifier,
                String createdByActorFingerprint) {
            throw new UnsupportedOperationException("use seed(...) in this test");
        }

        @Override
        public List<LocalCredentialRecord> findAll() {
            return List.copyOf(byId.values());
        }

        @Override
        public boolean anyExist() {
            return !byId.isEmpty();
        }

        @Override
        public void recordFailedAttempt(String localIdentityId, Instant now, int lockoutThreshold,
                Duration lockoutDuration) {
            LocalCredentialRecord r = byId.get(localIdentityId);
            int next = r.failedAttemptCount() + 1;
            Optional<Instant> lockedUntil = next >= lockoutThreshold ? Optional.of(now.plus(lockoutDuration)) : r.lockedUntil();
            byId.put(localIdentityId, new LocalCredentialRecord(r.localIdentityId(), r.localIdentityName(), r.verifier(),
                    r.salt(), r.algorithmId(), r.memoryCostKib(), r.timeCost(), r.parallelism(), next, lockedUntil,
                    r.createdAt(), now, r.enabled(), r.createdByActorFingerprint(), r.passwordSetAt(),
                    r.mustChangePassword()));
        }

        @Override
        public void recordSuccessfulLogin(String localIdentityId, Instant now, String actorFingerprint) {
            LocalCredentialRecord r = byId.get(localIdentityId);
            byId.put(localIdentityId, new LocalCredentialRecord(r.localIdentityId(), r.localIdentityName(), r.verifier(),
                    r.salt(), r.algorithmId(), r.memoryCostKib(), r.timeCost(), r.parallelism(), 0, Optional.empty(),
                    r.createdAt(), now, r.enabled(), r.createdByActorFingerprint(), r.passwordSetAt(),
                    r.mustChangePassword()));
        }

        @Override
        public void changePassword(String localIdentityId, Argon2PasswordHasher.Verifier newVerifier,
                String changedByActorFingerprint) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void adminSetPassword(String localIdentityId, Argon2PasswordHasher.Verifier newVerifier,
                String settingAdminActorFingerprint) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void setEnabled(String localIdentityId, boolean enabled, String actingAdminActorFingerprint) {
            throw new UnsupportedOperationException("use disable(...) in this test");
        }
    }

    @Test
    void unknownIdentityIsRefused() {
        InMemoryLocalCredentialsRepository repo = new InMemoryLocalCredentialsRepository();
        LocalMechanism mechanism = new LocalMechanism(repo, new FixedClock(NOW));

        AttemptOutcome outcome = mechanism.attempt("no-such-user", "whatever".toCharArray());

        assertTrue(outcome instanceof AttemptOutcome.Refused);
    }

    @Test
    void wrongPasswordIsRefusedAndCountedAsAFailure() {
        InMemoryLocalCredentialsRepository repo = new InMemoryLocalCredentialsRepository();
        String id = repo.seed("nexusadmin");
        LocalMechanism mechanism = new LocalMechanism(repo, new FixedClock(NOW));

        AttemptOutcome outcome = mechanism.attempt("nexusadmin", "wrong-password".toCharArray());

        assertTrue(outcome instanceof AttemptOutcome.Refused);
        assertEquals(1, repo.row(id).failedAttemptCount());
    }

    @Test
    void correctPasswordSucceedsAndResetsAnyPriorFailureCount() {
        InMemoryLocalCredentialsRepository repo = new InMemoryLocalCredentialsRepository();
        String id = repo.seed("nexusadmin");
        LocalMechanism mechanism = new LocalMechanism(repo, new FixedClock(NOW));
        mechanism.attempt("nexusadmin", "wrong-once".toCharArray());
        assertEquals(1, repo.row(id).failedAttemptCount());

        AttemptOutcome outcome = mechanism.attempt("nexusadmin", REAL_PASSWORD);

        assertTrue(outcome instanceof AttemptOutcome.Success);
        assertEquals(0, repo.row(id).failedAttemptCount());
        assertTrue(repo.row(id).lockedUntil().isEmpty());
    }

    @Test
    void fifthConsecutiveFailureLocksTheIdentityAndASixthCorrectPasswordAttemptIsStillRefused() {
        InMemoryLocalCredentialsRepository repo = new InMemoryLocalCredentialsRepository();
        String id = repo.seed("nexusadmin");
        FixedClock clock = new FixedClock(NOW);
        LocalMechanism mechanism = new LocalMechanism(repo, clock);

        for (int i = 0; i < LocalMechanism.LOCKOUT_THRESHOLD; i++) {
            AttemptOutcome outcome = mechanism.attempt("nexusadmin", "wrong".toCharArray());
            assertTrue(outcome instanceof AttemptOutcome.Refused);
        }
        assertTrue(repo.row(id).lockedUntil().isPresent(), "5th failure must set locked_until");

        // A 6th attempt, this time with the CORRECT password, must still be
        // refused -- the lockout is never revealed as a distinct state (§5.2).
        AttemptOutcome sixthAttempt = mechanism.attempt("nexusadmin", REAL_PASSWORD);
        assertTrue(sixthAttempt instanceof AttemptOutcome.Refused);
    }

    @Test
    void lockoutClearsBySelfExpiryOnANextCorrectPasswordAttempt() {
        InMemoryLocalCredentialsRepository repo = new InMemoryLocalCredentialsRepository();
        String id = repo.seed("nexusadmin");
        FixedClock clock = new FixedClock(NOW);
        LocalMechanism mechanism = new LocalMechanism(repo, clock);
        for (int i = 0; i < LocalMechanism.LOCKOUT_THRESHOLD; i++) {
            mechanism.attempt("nexusadmin", "wrong".toCharArray());
        }
        assertTrue(repo.row(id).lockedUntil().isPresent());

        clock.advance(LocalMechanism.LOCKOUT_DURATION.plusSeconds(1));
        AttemptOutcome outcome = mechanism.attempt("nexusadmin", REAL_PASSWORD);

        assertTrue(outcome instanceof AttemptOutcome.Success);
        assertEquals(0, repo.row(id).failedAttemptCount());
        assertTrue(repo.row(id).lockedUntil().isEmpty());
    }

    @Test
    void everyRefusalCauseIsIndistinguishableAtTheOutcomeLevel() {
        InMemoryLocalCredentialsRepository repo = new InMemoryLocalCredentialsRepository();
        String id = repo.seed("nexusadmin");
        repo.seed("claudeadmin");
        FixedClock clock = new FixedClock(NOW);
        LocalMechanism mechanism = new LocalMechanism(repo, clock);
        for (int i = 0; i < LocalMechanism.LOCKOUT_THRESHOLD; i++) {
            mechanism.attempt("nexusadmin", "wrong".toCharArray());
        }
        assertTrue(repo.row(id).lockedUntil().isPresent());

        AttemptOutcome unknownIdentity = mechanism.attempt("no-such-user", "x".toCharArray());
        AttemptOutcome wrongPasswordKnownUser = mechanism.attempt("claudeadmin", "x".toCharArray());
        AttemptOutcome emptyPassword = mechanism.attempt("nexusadmin", new char[0]);
        AttemptOutcome lockedWithCorrectPassword = mechanism.attempt("nexusadmin", REAL_PASSWORD);

        // The controller maps every AttemptOutcome.Refused to the identical
        // 401 INVALID_CREDENTIALS body regardless of reasonCode (§5.3) --
        // this asserts the outcome TYPE is uniform, which is what the
        // controller branches on (LoginController never reads reasonCode).
        assertTrue(unknownIdentity instanceof AttemptOutcome.Refused);
        assertTrue(emptyPassword instanceof AttemptOutcome.Refused);
        assertTrue(lockedWithCorrectPassword instanceof AttemptOutcome.Refused);
        assertNotEquals(AttemptOutcome.Success.class, wrongPasswordKnownUser.getClass());
    }

    @Test
    void aLockedIdentityAndAnUnknownIdentityAreNotMeasurablyDistinguishableByElapsedTime() {
        InMemoryLocalCredentialsRepository repo = new InMemoryLocalCredentialsRepository();
        String id = repo.seed("nexusadmin");
        repo.seed("claudeadmin");
        FixedClock clock = new FixedClock(NOW);
        LocalMechanism mechanism = new LocalMechanism(repo, clock);
        for (int i = 0; i < LocalMechanism.LOCKOUT_THRESHOLD; i++) {
            mechanism.attempt("nexusadmin", "wrong".toCharArray());
        }
        assertTrue(repo.row(id).lockedUntil().isPresent());

        long knownWrongPasswordNanos = elapsedNanos(() -> mechanism.attempt("claudeadmin", "x".toCharArray()));
        long unknownIdentityNanos = elapsedNanos(() -> mechanism.attempt("no-such-user", "x".toCharArray()));
        long lockedIdentityNanos = elapsedNanos(() -> mechanism.attempt("nexusadmin", REAL_PASSWORD));

        // §5.3/§11 U-4: not an exact constant-time guarantee, but no refusal
        // path may be a different order of magnitude cheaper than another --
        // a skipped Argon2 computation would show as microseconds against
        // tens of milliseconds for a performed one. Every path here performs
        // one Argon2 computation (LocalCredentialVerification), so all three
        // durations must be within the same broad band.
        assertTrue(unknownIdentityNanos > knownWrongPasswordNanos / 4,
                "an unknown identity must not resolve dramatically faster than a known one");
        assertTrue(lockedIdentityNanos > knownWrongPasswordNanos / 4,
                "a locked identity's skipped-verification-result path must not resolve dramatically faster "
                        + "than a performed verification");
    }

    /** 13G LIA-3.6: a disabled identity cannot authenticate, even with the correct password. */
    @Test
    void aDisabledIdentityCannotAuthenticateEvenWithTheCorrectPassword() {
        InMemoryLocalCredentialsRepository repo = new InMemoryLocalCredentialsRepository();
        repo.seed("nexusadmin");
        repo.disable("id-nexusadmin");
        LocalMechanism mechanism = new LocalMechanism(repo, new FixedClock(NOW));

        AttemptOutcome outcome = mechanism.attempt("nexusadmin", REAL_PASSWORD);

        assertTrue(outcome instanceof AttemptOutcome.Refused, "a disabled identity must not be able to authenticate");
    }

    private static long elapsedNanos(Runnable work) {
        long start = System.nanoTime();
        work.run();
        return System.nanoTime() - start;
    }
}
