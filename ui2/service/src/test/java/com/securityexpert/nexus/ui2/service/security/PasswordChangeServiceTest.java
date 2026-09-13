package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;

/**
 * C3A contract §4/§11.2 test 8's positive half: "the change-password path
 * is reachable and succeeds ... without any special-cased restriction" --
 * and that no forced change gates it (no code path here requires a prior
 * compulsory change).
 */
class PasswordChangeServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    private static final char[] CURRENT_PASSWORD = "initial-bootstrap-password".toCharArray();

    private static final class InMemoryRepository implements LocalCredentialsRepository {
        private final Map<String, LocalCredentialRecord> byId = new HashMap<>();
        private final Map<String, String> idByName = new HashMap<>();
        byte[] lastChangedVerifier;

        String seed(String name) {
            String id = "id-" + name;
            Argon2PasswordHasher.Verifier verifier =
                    Argon2PasswordHasher.hash(CURRENT_PASSWORD, Argon2PasswordHasher.DEFAULT_PARAMETERS);
            idByName.put(name, id);
            byId.put(id, new LocalCredentialRecord(id, name, verifier.verifier(), verifier.salt(), verifier.algorithmId(),
                    verifier.parameters().memoryCostKib(), verifier.parameters().timeCost(),
                    verifier.parameters().parallelism(), 0, Optional.empty(), NOW, NOW, true));
            return id;
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
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean anyExist() {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public java.util.List<LocalCredentialRecord> findAll() {
            return java.util.List.copyOf(byId.values());
        }

        @Override
        public void markMustChangePassword(String localIdentityId, String actorFingerprint) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void recordFailedAttempt(String localIdentityId, Instant now, int lockoutThreshold,
                Duration lockoutDuration) {
            throw new UnsupportedOperationException("password change must never touch login lockout state");
        }

        @Override
        public void recordSuccessfulLogin(String localIdentityId, Instant now, String actorFingerprint) {
            throw new UnsupportedOperationException("password change must never touch login lockout state");
        }

        @Override
        public void changePassword(String localIdentityId, Argon2PasswordHasher.Verifier newVerifier,
                String changedByActorFingerprint) {
            lastChangedVerifier = newVerifier.verifier();
            LocalCredentialRecord r = byId.get(localIdentityId);
            byId.put(localIdentityId, new LocalCredentialRecord(r.localIdentityId(), r.localIdentityName(),
                    newVerifier.verifier(), newVerifier.salt(), newVerifier.algorithmId(),
                    newVerifier.parameters().memoryCostKib(), newVerifier.parameters().timeCost(),
                    newVerifier.parameters().parallelism(), r.failedAttemptCount(), r.lockedUntil(), r.createdAt(), NOW,
                    false));
        }
    }

    @Test
    void changingAPasswordSucceedsForABootstrapAccountWithNoForcedChangeInFront() {
        InMemoryRepository repo = new InMemoryRepository();
        repo.seed("nexusadmin");
        PasswordChangeService service = new PasswordChangeService(repo);

        PasswordChangeService.Result result = service.changePassword(
                "nexusadmin", CURRENT_PASSWORD, "a-brand-new-password-12".toCharArray());

        assertTrue(result instanceof PasswordChangeService.Result.Ok);
        assertTrue(repo.lastChangedVerifier != null);
    }

    @Test
    void aSuccessfulChangeClearsTheMustChangePasswordFlag() {
        // NXS-LOCAL-0152 AC-2: seeded identities start with the flag set;
        // a successful change clears it in the same repository call that
        // writes the new verifier.
        InMemoryRepository repo = new InMemoryRepository();
        String id = repo.seed("nexusadmin");
        assertTrue(repo.findById(id).orElseThrow().mustChangePassword(), "seeded row must start requiring a change");
        PasswordChangeService service = new PasswordChangeService(repo);

        PasswordChangeService.Result result = service.changePassword(
                "nexusadmin", CURRENT_PASSWORD, "a-brand-new-password-12".toCharArray());

        assertTrue(result instanceof PasswordChangeService.Result.Ok);
        assertTrue(!repo.findById(id).orElseThrow().mustChangePassword(), "AC-2: the flag must be cleared");
    }

    @Test
    void aWrongCurrentPasswordIsRefusedWithoutChangingAnything() {
        InMemoryRepository repo = new InMemoryRepository();
        repo.seed("nexusadmin");
        PasswordChangeService service = new PasswordChangeService(repo);

        PasswordChangeService.Result result = service.changePassword(
                "nexusadmin", "not-the-current-password".toCharArray(), "a-brand-new-password-12".toCharArray());

        assertTrue(result instanceof PasswordChangeService.Result.InvalidCredentials);
        assertEquals(null, repo.lastChangedVerifier);
    }

    @Test
    void aNewPasswordViolatingThePolicyIsRefusedEvenWithTheCorrectCurrentPassword() {
        InMemoryRepository repo = new InMemoryRepository();
        repo.seed("nexusadmin");
        PasswordChangeService service = new PasswordChangeService(repo);

        PasswordChangeService.Result result = service.changePassword("nexusadmin", CURRENT_PASSWORD, "short".toCharArray());

        assertTrue(result instanceof PasswordChangeService.Result.PolicyViolation);
        assertEquals(null, repo.lastChangedVerifier);
    }
}
