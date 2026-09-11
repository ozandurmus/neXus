package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRecord;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionEndReason;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;

/**
 * B1-4b contract §8 tests 2 ({@code unauthorized_role_cannot_register}) and
 * 3 ({@code security_admin_cannot_register}), proved at the same
 * {@code GateChain}/{@code E1}-{@code E4} layer {@link GateChainTest}
 * already exercises: {@link ActionRegistry#DEVICE_REGISTER} requires
 * exactly {@code role:onboarding_admin} (C3 §4.1), so a viewer/operator
 * session with no binding for that token, and a security_admin session
 * whose only binding is for a *different* token, both fail {@code E4} --
 * neither ever reaches {@link
 * com.securityexpert.nexus.ui2.service.device.DeviceRegistrationService}.
 */
class DeviceRegistrationGateTest {

    private static final Instant NOW = Instant.parse("2026-09-12T10:00:00Z");

    private static final class FakeSessionRepository implements SessionRepository {
        final Map<String, SessionRecord> bySessionId = new HashMap<>();

        void put(SessionRecord record) {
            bySessionId.put(record.sessionId(), record);
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
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void revokeAccessGroupLost(String sessionId, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static final class FakeAuthzDecisionRepository implements AuthzDecisionRepository {
        int callCount = 0;

        @Override
        public long insert(String sessionId, String actorFingerprint, String actionId, Optional<String> targetRef,
                AuthzOutcome outcome, Optional<String> authority, Optional<String> reasonCode,
                Optional<String> bindingId) {
            callCount++;
            return callCount;
        }
    }

    private static final class FakeRoleBindingRepository implements RoleBindingRepository {
        final List<RoleBindingRecord> bindings = new ArrayList<>();

        void addActive(String bindingId, String roleToken, byte[] encryptedGroupReference) {
            bindings.add(new RoleBindingRecord(bindingId, roleToken, encryptedGroupReference, "k1", "creator",
                    Instant.now(), Optional.empty(), Optional.empty()));
        }

        @Override
        public List<RoleBindingRecord> findActiveByToken(String roleToken) {
            return bindings.stream().filter(b -> b.roleToken().equals(roleToken)).toList();
        }

        @Override
        public Optional<RoleBindingRecord> find(String bindingId) {
            return Optional.empty();
        }

        @Override
        public boolean hasAnyActiveBinding(String roleToken) {
            return !findActiveByToken(roleToken).isEmpty();
        }

        @Override
        public String create(String bindingId, String roleToken, byte[] groupReferenceEncrypted,
                String groupReferenceKeyId, String createdByActorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void revoke(String bindingId, String revokedByActorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static final class FakeActorAuthzStateRepository implements ActorAuthzStateRepository {
        private final Map<String, Set<String>> groupsByActor = new HashMap<>();

        void put(String actorFingerprint, Set<String> groupReferences) {
            groupsByActor.put(actorFingerprint, groupReferences);
        }

        @Override
        public Optional<ActorAuthzStateRecord> find(String actorFingerprint) {
            Set<String> groups = groupsByActor.get(actorFingerprint);
            if (groups == null) {
                return Optional.empty();
            }
            return Optional.of(new ActorAuthzStateRecord(actorFingerprint, groups, NOW.minusSeconds(10),
                    NOW.plusSeconds(900)));
        }

        @Override
        public void upsert(String actorFingerprint, Set<String> groupReferences, Instant resolvedAt,
                Instant validUntil) {
        }

        @Override
        public void delete(String actorFingerprint) {
        }
    }

    private static GroupReferenceCipher cipher() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return GroupReferenceCipher.fromBase64Key(Base64.getEncoder().encodeToString(key));
    }

    private SessionRecord activeSession(String sessionId, String actor) {
        return new SessionRecord(sessionId, actor, "csrf-secret", SessionState.ACTIVE,
                NOW.minusSeconds(60), NOW.minusSeconds(10), NOW.plusSeconds(1800), NOW.plusSeconds(36000),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private GateRequest registerRequest(String rawCookie) {
        return new GateRequest("POST", Optional.of(rawCookie), Optional.of("csrf-secret"),
                Optional.of("https://ui2.example.com"), ActionRegistry.DEVICE_REGISTER, Optional.empty());
    }

    @Test
    void viewerSessionIsRefusedNeverPermitted() {
        // Test 2: no active binding at all for role:onboarding_admin ->
        // AUTHZ_NOT_EVALUATED at E4, never PERMITTED, never a devices row.
        FakeSessionRepository sessions = new FakeSessionRepository();
        String sessionId = SessionHasher.hash("viewer-cookie");
        sessions.put(activeSession(sessionId, "viewer-actor"));
        GroupReferenceCipher cipher = cipher();
        FakeRoleBindingRepository bindings = new FakeRoleBindingRepository();
        // role:onboarding_admin has zero active bindings in this scenario.
        FakeActorAuthzStateRepository authzState = new FakeActorAuthzStateRepository();
        authzState.put("viewer-actor", Set.of());
        RbacEvaluator evaluator = new RbacEvaluator(bindings, authzState, cipher);
        GateChain chain = new GateChain(sessions, new ActionRegistry(), evaluator, new FakeAuthzDecisionRepository());

        GateOutcome outcome = chain.evaluate(registerRequest("viewer-cookie"), NOW);

        assertTrue(outcome instanceof GateOutcome.Refused);
        assertEquals("E4", ((GateOutcome.Refused) outcome).gate());
        assertEquals("AUTHZ_NOT_EVALUATED", ((GateOutcome.Refused) outcome).body().get("outcome"));
    }

    @Test
    void operatorSessionBoundToADifferentTokenIsDeniedNeverPermitted() {
        // Test 2, the DENIED variant: role:onboarding_admin IS bound to
        // some group, but this actor's resolved groups do not include it.
        FakeSessionRepository sessions = new FakeSessionRepository();
        String sessionId = SessionHasher.hash("operator-cookie");
        sessions.put(activeSession(sessionId, "operator-actor"));
        GroupReferenceCipher cipher = cipher();
        FakeRoleBindingRepository bindings = new FakeRoleBindingRepository();
        bindings.addActive("b1", RoleToken.ONBOARDING_ADMIN.token(),
                cipher.encrypt("cn=onboarding-admins,dc=example,dc=com"));
        FakeActorAuthzStateRepository authzState = new FakeActorAuthzStateRepository();
        authzState.put("operator-actor", Set.of("cn=operators,dc=example,dc=com"));
        RbacEvaluator evaluator = new RbacEvaluator(bindings, authzState, cipher);
        GateChain chain = new GateChain(sessions, new ActionRegistry(), evaluator, new FakeAuthzDecisionRepository());

        GateOutcome outcome = chain.evaluate(registerRequest("operator-cookie"), NOW);

        assertTrue(outcome instanceof GateOutcome.Refused);
        assertEquals("E4", ((GateOutcome.Refused) outcome).gate());
        assertEquals("DENIED", ((GateOutcome.Refused) outcome).body().get("outcome"));
    }

    @Test
    void securityAdminSessionIsRefusedNeverPermitted() {
        // Test 3 (C3 §4.1 separation of duties): security_admin's own
        // binding is for role:security_admin, not role:onboarding_admin --
        // being in the security-admin group grants nothing here.
        FakeSessionRepository sessions = new FakeSessionRepository();
        String sessionId = SessionHasher.hash("security-admin-cookie");
        sessions.put(activeSession(sessionId, "security-admin-actor"));
        GroupReferenceCipher cipher = cipher();
        FakeRoleBindingRepository bindings = new FakeRoleBindingRepository();
        bindings.addActive("b1", RoleToken.SECURITY_ADMIN.token(),
                cipher.encrypt("cn=security-admins,dc=example,dc=com"));
        // role:onboarding_admin has zero active bindings.
        FakeActorAuthzStateRepository authzState = new FakeActorAuthzStateRepository();
        authzState.put("security-admin-actor", Set.of("cn=security-admins,dc=example,dc=com"));
        RbacEvaluator evaluator = new RbacEvaluator(bindings, authzState, cipher);
        GateChain chain = new GateChain(sessions, new ActionRegistry(), evaluator, new FakeAuthzDecisionRepository());

        GateOutcome outcome = chain.evaluate(registerRequest("security-admin-cookie"), NOW);

        assertTrue(outcome instanceof GateOutcome.Refused);
        assertEquals("E4", ((GateOutcome.Refused) outcome).gate());
        assertEquals("AUTHZ_NOT_EVALUATED", ((GateOutcome.Refused) outcome).body().get("outcome"),
                "security_admin has never been assigned role:onboarding_admin in this scenario -- "
                        + "the token itself has zero active bindings");
    }

    @Test
    void securityAdminInBothGroupsIsStillDeniedForOnboardingAdminToken() {
        // A stronger separation-of-duties check: even if role:onboarding_admin
        // IS bound to some group, a security_admin actor whose resolved
        // groups do not include THAT group is DENIED, never PERMITTED --
        // membership in the security-admin group never substitutes.
        FakeSessionRepository sessions = new FakeSessionRepository();
        String sessionId = SessionHasher.hash("security-admin-cookie-2");
        sessions.put(activeSession(sessionId, "security-admin-actor-2"));
        GroupReferenceCipher cipher = cipher();
        FakeRoleBindingRepository bindings = new FakeRoleBindingRepository();
        bindings.addActive("b1", RoleToken.SECURITY_ADMIN.token(),
                cipher.encrypt("cn=security-admins,dc=example,dc=com"));
        bindings.addActive("b2", RoleToken.ONBOARDING_ADMIN.token(),
                cipher.encrypt("cn=onboarding-admins,dc=example,dc=com"));
        FakeActorAuthzStateRepository authzState = new FakeActorAuthzStateRepository();
        authzState.put("security-admin-actor-2", Set.of("cn=security-admins,dc=example,dc=com"));
        RbacEvaluator evaluator = new RbacEvaluator(bindings, authzState, cipher);
        GateChain chain = new GateChain(sessions, new ActionRegistry(), evaluator, new FakeAuthzDecisionRepository());

        GateOutcome outcome = chain.evaluate(registerRequest("security-admin-cookie-2"), NOW);

        assertTrue(outcome instanceof GateOutcome.Refused);
        assertEquals("E4", ((GateOutcome.Refused) outcome).gate());
        assertEquals("DENIED", ((GateOutcome.Refused) outcome).body().get("outcome"));
    }

    @Test
    void onboardingAdminSessionProceeds() {
        // Sanity check: this harness does not vacuously refuse every actor.
        FakeSessionRepository sessions = new FakeSessionRepository();
        String sessionId = SessionHasher.hash("onboarding-admin-cookie");
        sessions.put(activeSession(sessionId, "onboarding-admin-actor"));
        GroupReferenceCipher cipher = cipher();
        FakeRoleBindingRepository bindings = new FakeRoleBindingRepository();
        bindings.addActive("b1", RoleToken.ONBOARDING_ADMIN.token(),
                cipher.encrypt("cn=onboarding-admins,dc=example,dc=com"));
        FakeActorAuthzStateRepository authzState = new FakeActorAuthzStateRepository();
        authzState.put("onboarding-admin-actor", Set.of("cn=onboarding-admins,dc=example,dc=com"));
        RbacEvaluator evaluator = new RbacEvaluator(bindings, authzState, cipher);
        GateChain chain = new GateChain(sessions, new ActionRegistry(), evaluator, new FakeAuthzDecisionRepository());

        GateOutcome outcome = chain.evaluate(registerRequest("onboarding-admin-cookie"), NOW);

        assertTrue(outcome instanceof GateOutcome.Proceed);
    }
}
