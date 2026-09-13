package com.securityexpert.nexus.ui2.persistence.identity;

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

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.LocalIdentityAdministrationPort;
import com.securityexpert.nexus.ui2.platform.LocalPrincipalFingerprint;
import com.securityexpert.nexus.ui2.platform.RoleToken;

/**
 * 13G ({@code PO_DECISION_RECORD_2026_09_13G}) local identity
 * administration. {@code disableOfTheLastEnabledSecurityAdminIsRefused} is
 * the decisive test's disable-path half (the revoke-path half is
 * {@code RoleBindingAdminServiceTest.revokingTheLastEnabledSecurityAdminBindingIsRefused}) --
 * observed failing before {@link SecurityAdminLockoutGuard} existed.
 */
class LocalIdentityAdministrationTest {

    private static GroupReferenceCipher cipher() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return GroupReferenceCipher.fromBase64Key(Base64.getEncoder().encodeToString(key));
    }

    private static final class FakeLocalCredentialsRepository implements LocalCredentialsRepository {
        private final java.util.List<String> mustChangePassword = new java.util.ArrayList<>();

        final Map<String, LocalCredentialRecord> byId = new HashMap<>();
        int createCalls = 0;

        @Override
        public Optional<LocalCredentialRecord> findByName(String localIdentityName) {
            return byId.values().stream().filter(r -> r.localIdentityName().equals(localIdentityName)).findFirst();
        }

        @Override
        public Optional<LocalCredentialRecord> findById(String localIdentityId) {
            return Optional.ofNullable(byId.get(localIdentityId));
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
        public String create(String localIdentityId, String localIdentityName, Argon2PasswordHasher.Verifier verifier,
                String createdByActorFingerprint) {
            createCalls++;
            Instant now = Instant.now();
            byId.put(localIdentityId, new LocalCredentialRecord(localIdentityId, localIdentityName,
                    verifier.verifier(), verifier.salt(), verifier.algorithmId(), verifier.parameters().memoryCostKib(),
                    verifier.parameters().timeCost(), verifier.parameters().parallelism(), 0, Optional.empty(), now,
                    now, true, createdByActorFingerprint, now, true));
            return localIdentityId;
        }

        @Override
        public void recordFailedAttempt(String localIdentityId, Instant now, int lockoutThreshold,
                Duration lockoutDuration) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void recordSuccessfulLogin(String localIdentityId, Instant now, String actorFingerprint) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void changePassword(String localIdentityId, Argon2PasswordHasher.Verifier newVerifier,
                String changedByActorFingerprint) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void adminSetPassword(String localIdentityId, Argon2PasswordHasher.Verifier newVerifier,
                String settingAdminActorFingerprint) {
            LocalCredentialRecord existing = byId.get(localIdentityId);
            Instant now = Instant.now();
            byId.put(localIdentityId, new LocalCredentialRecord(existing.localIdentityId(), existing.localIdentityName(),
                    newVerifier.verifier(), newVerifier.salt(), newVerifier.algorithmId(),
                    newVerifier.parameters().memoryCostKib(), newVerifier.parameters().timeCost(),
                    newVerifier.parameters().parallelism(), existing.failedAttemptCount(), existing.lockedUntil(),
                    existing.createdAt(), now, existing.enabled(), existing.createdByActorFingerprint(), now, true));
        }

        /** NXS-LOCAL-0152's V9 seeding hook; this fake records the call and nothing else. */
        @Override
        public void markMustChangePassword(String localIdentityId, String actorFingerprint) {
            mustChangePassword.add(localIdentityId);
        }

        @Override
        public void setEnabled(String localIdentityId, boolean enabled, String actingAdminActorFingerprint) {
            LocalCredentialRecord existing = byId.get(localIdentityId);
            byId.put(localIdentityId, new LocalCredentialRecord(existing.localIdentityId(), existing.localIdentityName(),
                    existing.verifier(), existing.salt(), existing.algorithmId(), existing.memoryCostKib(),
                    existing.timeCost(), existing.parallelism(), existing.failedAttemptCount(), existing.lockedUntil(),
                    existing.createdAt(), Instant.now(), enabled, existing.createdByActorFingerprint(),
                    existing.passwordSetAt(), existing.mustChangePassword()));
        }
    }

    private static final class FakeRoleBindingRepository implements RoleBindingRepository {
        final List<RoleBindingRecord> bindings = new ArrayList<>();

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
            bindings.removeIf(b -> b.bindingId().equals(bindingId));
        }
    }

    private static final class FakeSessionRepository implements SessionRepository {
        final Map<String, SessionRecord> bySessionId = new HashMap<>();
        String lastRevokedSessionId;
        String lastRevokedBy;

        void putActive(String sessionId, String actorFingerprint) {
            Instant now = Instant.now();
            bySessionId.put(sessionId, new SessionRecord(sessionId, actorFingerprint, "csrf", SessionState.ACTIVE,
                    now, now, now.plusSeconds(1800), now.plusSeconds(36000), Optional.empty(), Optional.empty(),
                    Optional.empty()));
        }

        @Override
        public Optional<SessionRecord> findActiveByActor(String actorFingerprint) {
            return bySessionId.values().stream()
                    .filter(r -> r.actorFingerprint().equals(actorFingerprint) && r.state() == SessionState.ACTIVE)
                    .findFirst();
        }

        @Override
        public Optional<SessionRecord> findBySessionId(String sessionId) {
            return Optional.ofNullable(bySessionId.get(sessionId));
        }

        @Override
        public List<SessionRecord> findActivePastDeadline(Instant asOf) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public SessionRecord createActive(String sessionId, String actorFingerprint, String csrfSecret, Instant now,
                Duration idleTimeout, Duration absoluteLifetime, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public SessionRecord takeover(String priorSessionId, String newSessionId, String actorFingerprint,
                String csrfSecret, Instant now, Duration idleTimeout, Duration absoluteLifetime, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void heartbeat(String sessionId, Instant now, Duration idleTimeout) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void expire(String sessionId, SessionEndReason reason, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void revoke(String sessionId, String endedByActorFingerprint, String actionId) {
            lastRevokedSessionId = sessionId;
            lastRevokedBy = endedByActorFingerprint;
            SessionRecord existing = bySessionId.get(sessionId);
            bySessionId.put(sessionId, new SessionRecord(existing.sessionId(), existing.actorFingerprint(),
                    existing.csrfSecret(), SessionState.REVOKED, existing.createdAt(), existing.lastSeenAt(),
                    existing.idleDeadlineAt(), existing.absoluteExpiresAt(), existing.supersededBySessionId(),
                    Optional.of(endedByActorFingerprint), Optional.of(SessionEndReason.REVOKED_BY_ADMIN)));
        }

        @Override
        public void revokeAccessGroupLost(String sessionId, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private record Fixture(LocalIdentityAdministration administration, FakeLocalCredentialsRepository credentials,
            FakeRoleBindingRepository bindings, FakeSessionRepository sessions, GroupReferenceCipher cipher) {
    }

    private static Fixture fixture() {
        FakeLocalCredentialsRepository credentials = new FakeLocalCredentialsRepository();
        FakeRoleBindingRepository bindings = new FakeRoleBindingRepository();
        FakeSessionRepository sessions = new FakeSessionRepository();
        GroupReferenceCipher cipher = cipher();
        SecurityAdminLockoutGuard guard = new SecurityAdminLockoutGuard(bindings, credentials, cipher);
        return new Fixture(new LocalIdentityAdministration(credentials, sessions, guard), credentials, bindings,
                sessions, cipher);
    }

    @Test
    void createHashesThePasswordAndNeverPersistsItItself() {
        Fixture fx = fixture();
        char[] password = "correct horse battery staple".toCharArray();

        LocalIdentityAdministrationPort.LocalIdentityView view = fx.administration.create("admin1", "alice", password);

        assertTrue(view.enabled());
        assertTrue(view.mustChangePassword(), "13G LIA-3.3: a created identity must change its password at next sign-in");
        LocalCredentialRecord stored = fx.credentials.findById(view.localIdentityId()).orElseThrow();
        assertFalse(new String(stored.verifier()).contains("correct horse battery staple"));
        assertEquals("admin1", stored.createdByActorFingerprint());
    }

    @Test
    void listReturnsEveryCreatedIdentity() {
        Fixture fx = fixture();
        fx.administration.create("admin1", "alice", "p1".toCharArray());
        fx.administration.create("admin1", "bob", "p2".toCharArray());

        List<LocalIdentityAdministrationPort.LocalIdentityView> views = fx.administration.list();

        assertEquals(2, views.size());
    }

    @Test
    void adminSetPasswordMarksMustChangePasswordAndIsDistinctFromSelfServiceChange() {
        Fixture fx = fixture();
        LocalIdentityAdministrationPort.LocalIdentityView created =
                fx.administration.create("admin1", "alice", "p1".toCharArray());

        LocalIdentityAdministrationPort.MutationResult result =
                fx.administration.setPassword("admin1", created.localIdentityId(), "new-password".toCharArray());

        assertTrue(result instanceof LocalIdentityAdministrationPort.MutationResult.Ok);
        assertTrue(((LocalIdentityAdministrationPort.MutationResult.Ok) result).view().mustChangePassword());
    }

    @Test
    void setPasswordOnAnUnknownIdentityIsNotFound() {
        Fixture fx = fixture();

        LocalIdentityAdministrationPort.MutationResult result = fx.administration.setPassword("admin1", "no-such-id",
                "x".toCharArray());

        assertTrue(result instanceof LocalIdentityAdministrationPort.MutationResult.NotFound);
    }

    /**
     * 13G {@code LIA-3.5} decisive test, disable-path half: a single
     * enabled local identity holds the product's only active
     * {@code role:security_admin} binding -- disabling it must be refused.
     */
    @Test
    void disableOfTheLastEnabledSecurityAdminIsRefused() {
        Fixture fx = fixture();
        LocalIdentityAdministrationPort.LocalIdentityView onlyAdmin =
                fx.administration.create("system:bootstrap", "nexusadmin", "p1".toCharArray());
        fx.bindings.create("binding-1", RoleToken.SECURITY_ADMIN.token(),
                fx.cipher.encrypt(onlyAdmin.localIdentityId()), "key-1", "system:bootstrap", "role_binding_create");

        LocalIdentityAdministrationPort.MutationResult result =
                fx.administration.disable("admin1", onlyAdmin.localIdentityId());

        assertTrue(result instanceof LocalIdentityAdministrationPort.MutationResult.LastSecurityAdminRefused,
                "expected LastSecurityAdminRefused, got " + result);
        assertTrue(fx.credentials.findById(onlyAdmin.localIdentityId()).orElseThrow().enabled(),
                "the last security_admin identity must remain enabled");
    }

    @Test
    void disableSucceedsWhenAnotherEnabledSecurityAdminRemainsAndEndsTheActiveSession() {
        Fixture fx = fixture();
        LocalIdentityAdministrationPort.LocalIdentityView first =
                fx.administration.create("system:bootstrap", "nexusadmin", "p1".toCharArray());
        LocalIdentityAdministrationPort.LocalIdentityView second =
                fx.administration.create("system:bootstrap", "claudeadmin", "p2".toCharArray());
        fx.bindings.create("binding-1", RoleToken.SECURITY_ADMIN.token(), fx.cipher.encrypt(first.localIdentityId()),
                "key-1", "system:bootstrap", "role_binding_create");
        fx.bindings.create("binding-2", RoleToken.SECURITY_ADMIN.token(), fx.cipher.encrypt(second.localIdentityId()),
                "key-1", "admin1", "role_binding_create");
        String firstActorFingerprint = LocalPrincipalFingerprint.forLocalIdentity(first.localIdentityId());
        fx.sessions.putActive("session-1", firstActorFingerprint);

        LocalIdentityAdministrationPort.MutationResult result = fx.administration.disable("admin1",
                first.localIdentityId());

        assertTrue(result instanceof LocalIdentityAdministrationPort.MutationResult.Ok, "expected Ok, got " + result);
        assertFalse(fx.credentials.findById(first.localIdentityId()).orElseThrow().enabled());
        assertEquals("session-1", fx.sessions.lastRevokedSessionId, "the disabled identity's active session must end");
        assertEquals(SessionState.REVOKED, fx.sessions.findBySessionId("session-1").orElseThrow().state());
    }

    @Test
    void enableDoesNotResetTheCredential() {
        Fixture fx = fixture();
        LocalIdentityAdministrationPort.LocalIdentityView created =
                fx.administration.create("admin1", "alice", "p1".toCharArray());
        byte[] originalVerifier = fx.credentials.findById(created.localIdentityId()).orElseThrow().verifier();
        fx.administration.disable("admin1", created.localIdentityId());

        LocalIdentityAdministrationPort.MutationResult result = fx.administration.enable("admin1",
                created.localIdentityId());

        assertTrue(result instanceof LocalIdentityAdministrationPort.MutationResult.Ok);
        LocalCredentialRecord reEnabled = fx.credentials.findById(created.localIdentityId()).orElseThrow();
        assertTrue(reEnabled.enabled());
        assertEquals(java.util.Arrays.toString(originalVerifier), java.util.Arrays.toString(reEnabled.verifier()));
    }
}
