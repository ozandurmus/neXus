package com.securityexpert.nexus.ui2.service.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalIdentityBootstrap;
import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;
import com.securityexpert.nexus.ui2.platform.LocalIdentityBootstrapPort;
import com.securityexpert.nexus.ui2.platform.SecurityAdminBootstrapPort;
import com.securityexpert.nexus.ui2.service.security.PasswordChangeService;

/**
 * C3B contract (docs/design/UI2_0_C3B_BOOTSTRAP_IDENTITIES_AND_ROLES.md,
 * FROZEN 2026-09-13) §4 acceptance tests 1, 2 and 5, proved against an
 * in-memory fake -- the pure-logic half; the database half (audit_log rows,
 * role_bindings staying empty) is a Testcontainers-dependent test this
 * environment cannot run (see integration-tests).
 */
class FirstBootIdentitySeedingRunnerTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    /** A working fake: unlike the other service-test fakes, {@code create}/{@code changePassword} are exercised here. */
    private static final class RecordingLocalCredentialsRepository implements LocalCredentialsRepository {
        private final Map<String, LocalCredentialRecord> byId = new HashMap<>();
        private final Map<String, String> idByName = new HashMap<>();
        private int idSequence;

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
        public boolean anyExist() {
            return !byId.isEmpty();
        }

        @Override
        public String create(String localIdentityId, String localIdentityName, Argon2PasswordHasher.Verifier verifier,
                String createdByActorFingerprint) {
            String id = "id-" + (++idSequence);
            idByName.put(localIdentityName, id);
            byId.put(id, new LocalCredentialRecord(id, localIdentityName, verifier.verifier(), verifier.salt(),
                    verifier.algorithmId(), verifier.parameters().memoryCostKib(), verifier.parameters().timeCost(),
                    verifier.parameters().parallelism(), 0, Optional.empty(), NOW, NOW));
            return id;
        }

        @Override
        public void recordFailedAttempt(String localIdentityId, Instant now, int lockoutThreshold,
                Duration lockoutDuration) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void recordSuccessfulLogin(String localIdentityId, Instant now, String actorFingerprint) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void changePassword(String localIdentityId, Argon2PasswordHasher.Verifier newVerifier,
                String changedByActorFingerprint) {
            LocalCredentialRecord r = byId.get(localIdentityId);
            byId.put(localIdentityId, new LocalCredentialRecord(r.localIdentityId(), r.localIdentityName(),
                    newVerifier.verifier(), newVerifier.salt(), newVerifier.algorithmId(),
                    newVerifier.parameters().memoryCostKib(), newVerifier.parameters().timeCost(),
                    newVerifier.parameters().parallelism(), r.failedAttemptCount(), r.lockedUntil(),
                    r.createdAt(), Instant.now()));
        }
    }

    private static FirstBootIdentitySeedingRunner runnerFor(RecordingLocalCredentialsRepository repository) {
        LocalIdentityBootstrapPort bootstrap = new LocalIdentityBootstrap(repository);
        return new FirstBootIdentitySeedingRunner(repository, bootstrap);
    }

    @Test
    void anEmptyTableIsSeededWithExactlyTheTwoBootstrapIdentitiesAndNoMore() {
        RecordingLocalCredentialsRepository repository = new RecordingLocalCredentialsRepository();
        FirstBootIdentitySeedingRunner runner = runnerFor(repository);

        runner.run(null);

        assertEquals(2, repository.byId.size(), "AC-1: exactly two identities, no more");
        assertTrue(repository.findByName(BootstrapCredentialDefaults.NEXUSADMIN_NAME).isPresent());
        assertTrue(repository.findByName(BootstrapCredentialDefaults.CLAUDEADMIN_NAME).isPresent());
    }

    @Test
    void aTableHoldingAnyRowIsLeftCompletelyUntouched() {
        RecordingLocalCredentialsRepository repository = new RecordingLocalCredentialsRepository();
        String operatorCreatedId = repository.create("id-operator", "an-operator-created-identity",
                Argon2PasswordHasher.hash("operator-password-1".toCharArray(), Argon2PasswordHasher.DEFAULT_PARAMETERS),
                "some-operator-actor");
        FirstBootIdentitySeedingRunner runner = runnerFor(repository);

        runner.run(null);

        assertEquals(1, repository.byId.size(), "AC-2: first boot creates nothing and changes nothing");
        assertTrue(repository.findById(operatorCreatedId).isPresent());
        assertFalse(repository.findByName(BootstrapCredentialDefaults.NEXUSADMIN_NAME).isPresent());
        assertFalse(repository.findByName(BootstrapCredentialDefaults.CLAUDEADMIN_NAME).isPresent());
    }

    @Test
    void aPartiallySeededTableWithOnlyOneBootstrapIdentityIsLeftAloneRatherThanToppedUp() {
        // BOOT-2's decisive rule extended to the partially-seeded case
        // (AC-4): one bootstrap row already present is still "any row",
        // so the missing sibling is never created after the fact.
        RecordingLocalCredentialsRepository repository = new RecordingLocalCredentialsRepository();
        repository.create("id-nexusadmin", BootstrapCredentialDefaults.NEXUSADMIN_NAME,
                Argon2PasswordHasher.hash("some-other-password".toCharArray(), Argon2PasswordHasher.DEFAULT_PARAMETERS),
                SecurityAdminBootstrapPort.BOOTSTRAP_ACTOR);
        FirstBootIdentitySeedingRunner runner = runnerFor(repository);

        runner.run(null);

        assertEquals(1, repository.byId.size(), "AC-4: the partially-seeded table gains no second identity");
        assertFalse(repository.findByName(BootstrapCredentialDefaults.CLAUDEADMIN_NAME).isPresent());
    }

    @Test
    void aRestartOfTheSeedingRoutineNeverResetsAPasswordAnOperatorHasChanged() {
        // AC-3, THE DECISIVE TEST. Observed to FAIL against a naive
        // always-seed implementation (SESSION_CLOSE records the observed
        // failure before this fix existed).
        RecordingLocalCredentialsRepository repository = new RecordingLocalCredentialsRepository();
        FirstBootIdentitySeedingRunner runner = runnerFor(repository);
        runner.run(null); // first boot: seeds nexusadmin/claudeadmin with their documented passwords

        char[] changedPassword = "correct-horse-battery-staple".toCharArray();
        PasswordChangeService passwordChangeService = new PasswordChangeService(repository);
        PasswordChangeService.Result changeResult = passwordChangeService.changePassword(
                BootstrapCredentialDefaults.NEXUSADMIN_NAME,
                BootstrapCredentialDefaults.nexusadminInitialPassword(), changedPassword);
        assertTrue(changeResult instanceof PasswordChangeService.Result.Ok(), "the password change itself must succeed");

        runner.run(null); // simulated restart: the seeding routine runs again

        LocalCredentialRecord afterRestart = repository.findByName(BootstrapCredentialDefaults.NEXUSADMIN_NAME).orElseThrow();
        assertTrue(Argon2PasswordHasher.verify(changedPassword, afterRestart.toVerifier()),
                "the operator-changed password must still authenticate after a restart");
        assertFalse(Argon2PasswordHasher.verify(BootstrapCredentialDefaults.nexusadminInitialPassword(), afterRestart.toVerifier()),
                "the documented initial password must no longer authenticate once it has been changed");
        assertEquals(2, repository.byId.size(), "the restart must not have created a duplicate or third row");
    }
}
