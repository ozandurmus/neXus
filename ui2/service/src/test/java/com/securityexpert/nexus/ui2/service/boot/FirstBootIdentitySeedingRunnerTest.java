package com.securityexpert.nexus.ui2.service.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.identity.FirstBootIdentityRoleBindingSeeder;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.platform.SecurityAdminBootstrapPort;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRecord;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.service.security.LocalMechanism;
import com.securityexpert.nexus.ui2.service.security.PasswordChangeService;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * C3B contract (docs/design/UI2_0_C3B_BOOTSTRAP_IDENTITIES_AND_ROLES.md,
 * FROZEN 2026-09-13, BOOT-5 corrected the same day) §4 acceptance tests 1,
 * 2, 5, 6 and 7, proved against in-memory fakes -- the pure-logic half; the
 * database half (audit_log rows, the same-transaction guarantee against a
 * real jOOQ savepoint) is a Testcontainers-dependent test this environment
 * cannot run (see integration-tests).
 */
class FirstBootIdentitySeedingRunnerTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    private static GroupReferenceCipher cipher() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return GroupReferenceCipher.fromBase64Key(Base64.getEncoder().encodeToString(key));
    }

    /** No real transaction machinery needed: every mutation here goes through the fakes below, not raw SQL. */
    private static final class NoopTransactionBoundary implements TransactionBoundary {
        @Override
        public <T> T inTransaction(java.util.function.Function<org.jooq.DSLContext, T> work) {
            return work.apply(null);
        }
    }

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
            idSequence++;
            idByName.put(localIdentityName, localIdentityId);
            byId.put(localIdentityId, new LocalCredentialRecord(localIdentityId, localIdentityName, verifier.verifier(),
                    verifier.salt(), verifier.algorithmId(), verifier.parameters().memoryCostKib(),
                    verifier.parameters().timeCost(), verifier.parameters().parallelism(), 0, Optional.empty(), NOW, NOW,
                    true, "system:bootstrap", NOW, false));
            return localIdentityId;
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
                    r.createdAt(), Instant.now(), r.enabled(), r.createdByActorFingerprint(), r.passwordSetAt(),
                    r.mustChangePassword()));
        }

        @Override
        public List<LocalCredentialRecord> findAll() {
            return List.copyOf(byId.values());
        }

        @Override
        public void adminSetPassword(String localIdentityId, Argon2PasswordHasher.Verifier newVerifier,
                String settingAdminActorFingerprint) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void setEnabled(String localIdentityId, boolean enabled, String actingAdminActorFingerprint) {
            throw new UnsupportedOperationException("not exercised by this test");
        }
    }

    private static final class RecordingRoleBindingRepository implements RoleBindingRepository {
        private final List<RoleBindingRecord> bindings = new ArrayList<>();

        @Override
        public List<RoleBindingRecord> findActiveByToken(String roleToken) {
            return bindings.stream().filter(b -> b.roleToken().equals(roleToken) && b.isActive()).toList();
        }

        @Override
        public Optional<RoleBindingRecord> find(String bindingId) {
            return bindings.stream().filter(b -> b.bindingId().equals(bindingId)).findFirst();
        }

        @Override
        public boolean hasAnyActiveBinding(String roleToken) {
            return !findActiveByToken(roleToken).isEmpty();
        }

        @Override
        public String create(String bindingId, String roleToken, byte[] groupReferenceEncrypted,
                String groupReferenceKeyId, String createdByActorFingerprint, String actionId) {
            bindings.add(new RoleBindingRecord(bindingId, roleToken, groupReferenceEncrypted, groupReferenceKeyId,
                    createdByActorFingerprint, Instant.now(), Optional.empty(), Optional.empty()));
            return bindingId;
        }

        @Override
        public void revoke(String bindingId, String revokedByActorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not exercised by this test");
        }
    }

    private static final class RecordingActorAuthzStateRepository implements ActorAuthzStateRepository {
        private final Map<String, ActorAuthzStateRecord> rows = new HashMap<>();

        @Override
        public Optional<ActorAuthzStateRecord> find(String actorFingerprint) {
            return Optional.ofNullable(rows.get(actorFingerprint));
        }

        @Override
        public void upsert(String actorFingerprint, Set<String> groupReferences, Instant resolvedAt, Instant validUntil) {
            rows.put(actorFingerprint, new ActorAuthzStateRecord(actorFingerprint, groupReferences, resolvedAt, validUntil));
        }

        @Override
        public void delete(String actorFingerprint) {
            rows.remove(actorFingerprint);
        }
    }

    private static final class Fixture {
        final RecordingLocalCredentialsRepository localCredentials = new RecordingLocalCredentialsRepository();
        final RecordingRoleBindingRepository roleBindings = new RecordingRoleBindingRepository();
        final GroupReferenceCipher cipher = cipher();
        final FirstBootIdentitySeedingRunner runner;

        Fixture() {
            FirstBootIdentityRoleBindingSeeder seeder = new FirstBootIdentityRoleBindingSeeder(
                    new NoopTransactionBoundary(), localCredentials, roleBindings, cipher, "test-key-id");
            runner = new FirstBootIdentitySeedingRunner(localCredentials, seeder);
        }
    }

    @Test
    void anEmptyTableIsSeededWithExactlyTheTwoBootstrapIdentitiesAndNoMore() {
        Fixture fixture = new Fixture();

        fixture.runner.run(null);

        assertEquals(2, fixture.localCredentials.byId.size(), "AC-1: exactly two identities, no more");
        assertTrue(fixture.localCredentials.findByName(BootstrapCredentialDefaults.NEXUSADMIN_NAME).isPresent());
        assertTrue(fixture.localCredentials.findByName(BootstrapCredentialDefaults.CLAUDEADMIN_NAME).isPresent());
    }

    @Test
    void aTableHoldingAnyRowIsLeftCompletelyUntouched() {
        Fixture fixture = new Fixture();
        String operatorCreatedId = fixture.localCredentials.create("id-operator", "an-operator-created-identity",
                Argon2PasswordHasher.hash("operator-password-1".toCharArray(), Argon2PasswordHasher.DEFAULT_PARAMETERS),
                "some-operator-actor");

        fixture.runner.run(null);

        assertEquals(1, fixture.localCredentials.byId.size(), "AC-2: first boot creates nothing and changes nothing");
        assertTrue(fixture.localCredentials.findById(operatorCreatedId).isPresent());
        assertFalse(fixture.localCredentials.findByName(BootstrapCredentialDefaults.NEXUSADMIN_NAME).isPresent());
        assertFalse(fixture.localCredentials.findByName(BootstrapCredentialDefaults.CLAUDEADMIN_NAME).isPresent());
        assertTrue(fixture.roleBindings.bindings.isEmpty(), "AC-5: no binding is created when seeding is skipped");
    }

    @Test
    void aPartiallySeededTableWithOnlyOneBootstrapIdentityIsLeftAloneRatherThanToppedUp() {
        // BOOT-2's decisive rule extended to the partially-seeded case
        // (AC-4): one bootstrap row already present is still "any row",
        // so the missing sibling -- and its binding -- is never created
        // after the fact.
        Fixture fixture = new Fixture();
        fixture.localCredentials.create("id-nexusadmin", BootstrapCredentialDefaults.NEXUSADMIN_NAME,
                Argon2PasswordHasher.hash("some-other-password".toCharArray(), Argon2PasswordHasher.DEFAULT_PARAMETERS),
                SecurityAdminBootstrapPort.BOOTSTRAP_ACTOR);

        fixture.runner.run(null);

        assertEquals(1, fixture.localCredentials.byId.size(), "AC-4: the partially-seeded table gains no second identity");
        assertFalse(fixture.localCredentials.findByName(BootstrapCredentialDefaults.CLAUDEADMIN_NAME).isPresent());
        assertTrue(fixture.roleBindings.bindings.isEmpty(), "no binding is created for the untouched partial state");
    }

    @Test
    void aRestartOfTheSeedingRoutineNeverResetsAPasswordOrDuplicatesARoleBinding() {
        // AC-5, THE DECISIVE TEST for idempotence, extended to bindings.
        Fixture fixture = new Fixture();
        fixture.runner.run(null); // first boot: seeds nexusadmin/claudeadmin with their documented passwords and bindings

        char[] changedPassword = "correct-horse-battery-staple".toCharArray();
        PasswordChangeService passwordChangeService = new PasswordChangeService(fixture.localCredentials);
        PasswordChangeService.Result changeResult = passwordChangeService.changePassword(
                BootstrapCredentialDefaults.NEXUSADMIN_NAME,
                BootstrapCredentialDefaults.nexusadminInitialPassword(), changedPassword);
        assertTrue(changeResult instanceof PasswordChangeService.Result.Ok(), "the password change itself must succeed");

        int bindingCountAfterFirstBoot = fixture.roleBindings.bindings.size();

        fixture.runner.run(null); // simulated restart: the seeding routine runs again

        LocalCredentialRecord afterRestart = fixture.localCredentials.findByName(BootstrapCredentialDefaults.NEXUSADMIN_NAME).orElseThrow();
        assertTrue(Argon2PasswordHasher.verify(changedPassword, afterRestart.toVerifier()),
                "the operator-changed password must still authenticate after a restart");
        assertFalse(Argon2PasswordHasher.verify(BootstrapCredentialDefaults.nexusadminInitialPassword(), afterRestart.toVerifier()),
                "the documented initial password must no longer authenticate once it has been changed");
        assertEquals(2, fixture.localCredentials.byId.size(), "the restart must not have created a duplicate or third row");
        assertEquals(bindingCountAfterFirstBoot, fixture.roleBindings.bindings.size(),
                "AC-5: a restart must not duplicate or alter a role binding");
    }

    @Test
    void immediatelyAfterFirstBootNexusadminResolvesFullAdministrativeCapabilityAndClaudeadminResolvesViewerOnly() {
        // AC-1/AC-2, THE DECISIVE TEST: observed to FAIL against the
        // currently merged code, which creates no role_bindings row at all
        // (every RbacEvaluator.evaluate call below would return
        // AUTHZ_NOT_EVALUATED(role_token_unbound) for every token).
        Fixture fixture = new Fixture();
        fixture.runner.run(null);

        String nexusadminId = fixture.localCredentials.findByName(BootstrapCredentialDefaults.NEXUSADMIN_NAME)
                .orElseThrow().localIdentityId();
        String claudeadminId = fixture.localCredentials.findByName(BootstrapCredentialDefaults.CLAUDEADMIN_NAME)
                .orElseThrow().localIdentityId();
        String nexusadminActor = LocalMechanism.actorFingerprintFor(nexusadminId);
        String claudeadminActor = LocalMechanism.actorFingerprintFor(claudeadminId);

        // Simulates what a real login resolves into actor_authz_state for a
        // local identity (C3A §7.1): the single-element set containing the
        // identity's own reference. This movement does not wire that write
        // path (out of scope, C3's own RBAC wiring) -- only the seeded
        // role_bindings rows this movement is responsible for are under
        // test here.
        RecordingActorAuthzStateRepository authzState = new RecordingActorAuthzStateRepository();
        Instant now = NOW.plusSeconds(60);
        authzState.upsert(nexusadminActor, Set.of(nexusadminId), now, now.plus(15, java.time.temporal.ChronoUnit.MINUTES));
        authzState.upsert(claudeadminActor, Set.of(claudeadminId), now, now.plus(15, java.time.temporal.ChronoUnit.MINUTES));

        RbacEvaluator evaluator = new RbacEvaluator(fixture.roleBindings, authzState, fixture.cipher);

        for (RoleToken token : RoleToken.values()) {
            RbacEvaluator.Decision decision = evaluator.evaluate(nexusadminActor, Optional.of(token), now);
            assertEquals(AuthzOutcome.PERMITTED, decision.outcome(),
                    "AC-1: nexusadmin must resolve full administrative capability -- " + token + " was refused");
        }

        RbacEvaluator.Decision claudeadminViewer = evaluator.evaluate(claudeadminActor, Optional.of(RoleToken.VIEWER), now);
        assertEquals(AuthzOutcome.PERMITTED, claudeadminViewer.outcome(), "AC-2: claudeadmin must resolve role:viewer");

        for (RoleToken mutatingToken : List.of(RoleToken.OPERATOR, RoleToken.ONBOARDING_ADMIN, RoleToken.BACKUP_ADMIN,
                RoleToken.COMPLIANCE_ADMIN, RoleToken.SECURITY_ADMIN)) {
            RbacEvaluator.Decision decision = evaluator.evaluate(claudeadminActor, Optional.of(mutatingToken), now);
            assertFalse(decision.outcome().proceeds(),
                    "AC-2: a mutation path (" + mutatingToken + ") must be refused for claudeadmin, visibly, never a silent pass");
        }
    }
}
