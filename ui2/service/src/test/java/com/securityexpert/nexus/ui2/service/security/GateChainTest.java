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
 * Contract §8 test 11 ({@code ChainRefusesAtEachLinkInOrder}) and test 12
 * ({@code E3NeverReevaluatedInsideE4}), proved without a database or a
 * directory: {@link GateChain} depends only on interfaces, exercised here
 * with in-memory fakes.
 */
class GateChainTest {

    private static final String ACTOR = "af3a9c1e2b7d";
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
            throw new UnsupportedOperationException("not used by GateChainTest");
        }

        @Override
        public SessionRecord createActive(String sessionId, String actorFingerprint, String csrfSecret, Instant now,
                Duration idleTimeout, Duration absoluteLifetime, String actionId) {
            throw new UnsupportedOperationException("not used by GateChainTest");
        }

        @Override
        public SessionRecord takeover(String priorSessionId, String newSessionId, String actorFingerprint,
                String csrfSecret, Instant now, Duration idleTimeout, Duration absoluteLifetime, String actionId) {
            throw new UnsupportedOperationException("not used by GateChainTest");
        }

        @Override
        public void heartbeat(String sessionId, Instant now, Duration idleTimeout) {
            throw new UnsupportedOperationException("not used by GateChainTest");
        }

        @Override
        public void expire(String sessionId, SessionEndReason reason, String actionId) {
            throw new UnsupportedOperationException("not used by GateChainTest");
        }

        @Override
        public void revoke(String sessionId, String endedByActorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by GateChainTest");
        }

        @Override
        public void revokeAccessGroupLost(String sessionId, String actionId) {
            throw new UnsupportedOperationException("not used by GateChainTest");
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
            throw new UnsupportedOperationException("not used by GateChainTest");
        }

        @Override
        public void revoke(String bindingId, String revokedByActorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by GateChainTest");
        }
    }

    private static final class FakeActorAuthzStateRepository implements ActorAuthzStateRepository {
        @Override
        public Optional<ActorAuthzStateRecord> find(String actorFingerprint) {
            return Optional.of(new ActorAuthzStateRecord(actorFingerprint, Set.of(), NOW.minusSeconds(10),
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

    private SessionRecord activeSession(String sessionId) {
        return new SessionRecord(sessionId, ACTOR, "csrf-secret", SessionState.ACTIVE,
                NOW.minusSeconds(60), NOW.minusSeconds(10), NOW.plusSeconds(1800), NOW.plusSeconds(36000),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private GateChain newChain(FakeSessionRepository sessions, FakeRoleBindingRepository bindings,
            FakeAuthzDecisionRepository decisions) {
        RbacEvaluator evaluator = new RbacEvaluator(bindings, new FakeActorAuthzStateRepository(), cipher());
        return new GateChain(sessions, new ActionRegistry(), evaluator, decisions);
    }

    @Test
    void e1RefusesWhenNoSessionCookieIsPresented() {
        GateChain chain = newChain(new FakeSessionRepository(), new FakeRoleBindingRepository(),
                new FakeAuthzDecisionRepository());
        GateRequest request = new GateRequest("GET", Optional.empty(), Optional.empty(), Optional.empty(),
                ActionRegistry.ROLE_BINDING_CREATE, Optional.empty());

        GateOutcome outcome = chain.evaluate(request, NOW);

        assertTrue(outcome instanceof GateOutcome.Refused);
        assertEquals("E1", ((GateOutcome.Refused) outcome).gate());
    }

    @Test
    void e2RefusesOnAnUnknownActionWithAnOtherwiseValidSession() {
        FakeSessionRepository sessions = new FakeSessionRepository();
        String sessionId = SessionHasher.hash("raw-cookie-1");
        sessions.put(activeSession(sessionId));
        GateChain chain = newChain(sessions, new FakeRoleBindingRepository(), new FakeAuthzDecisionRepository());

        GateRequest request = new GateRequest("GET", Optional.of("raw-cookie-1"), Optional.empty(), Optional.empty(),
                "no_such_action", Optional.empty());

        GateOutcome outcome = chain.evaluate(request, NOW);

        assertTrue(outcome instanceof GateOutcome.Refused);
        assertEquals("E2", ((GateOutcome.Refused) outcome).gate());
        assertEquals(404, ((GateOutcome.Refused) outcome).httpStatus());
    }

    @Test
    void e3RefusesAClass1ActionUnconditionally() {
        FakeSessionRepository sessions = new FakeSessionRepository();
        String sessionId = SessionHasher.hash("raw-cookie-2");
        sessions.put(activeSession(sessionId));
        GateChain chain = newChain(sessions, new FakeRoleBindingRepository(), new FakeAuthzDecisionRepository());

        GateRequest request = new GateRequest("GET", Optional.of("raw-cookie-2"), Optional.empty(), Optional.empty(),
                ActionRegistry.RECOVERY_WRITE_EXAMPLE, Optional.empty());

        GateOutcome outcome = chain.evaluate(request, NOW);

        assertTrue(outcome instanceof GateOutcome.Refused);
        assertEquals("E3", ((GateOutcome.Refused) outcome).gate());
    }

    @Test
    void e4RefusesOnAnUnboundRoleTokenWithEveryEarlierGatePassing() {
        FakeSessionRepository sessions = new FakeSessionRepository();
        String sessionId = SessionHasher.hash("raw-cookie-3");
        sessions.put(activeSession(sessionId));
        // FakeRoleBindingRepository starts empty -- role_binding_create's
        // required token (security_admin) has zero active bindings.
        GateChain chain = newChain(sessions, new FakeRoleBindingRepository(), new FakeAuthzDecisionRepository());

        GateRequest request = new GateRequest("POST", Optional.of("raw-cookie-3"), Optional.of("csrf-secret"),
                Optional.of("https://ui2.example.com"), ActionRegistry.ROLE_BINDING_CREATE, Optional.empty());

        GateOutcome outcome = chain.evaluate(request, NOW);

        assertTrue(outcome instanceof GateOutcome.Refused);
        assertEquals("E4", ((GateOutcome.Refused) outcome).gate());
        assertEquals("AUTHZ_NOT_EVALUATED", ((GateOutcome.Refused) outcome).body().get("outcome"));
    }

    @Test
    void e3NeverReevaluatedInsideE4() {
        // A class-1 action whose token would ALSO independently fail E4
        // (zero active bindings): the response must name only E3's
        // refusal, and E4 (the authz-decision write) must never run.
        FakeSessionRepository sessions = new FakeSessionRepository();
        String sessionId = SessionHasher.hash("raw-cookie-4");
        sessions.put(activeSession(sessionId));
        FakeAuthzDecisionRepository decisions = new FakeAuthzDecisionRepository();
        GateChain chain = newChain(sessions, new FakeRoleBindingRepository(), decisions);

        GateRequest request = new GateRequest("GET", Optional.of("raw-cookie-4"), Optional.empty(), Optional.empty(),
                ActionRegistry.RECOVERY_WRITE_EXAMPLE, Optional.empty());

        GateOutcome outcome = chain.evaluate(request, NOW);

        assertTrue(outcome instanceof GateOutcome.Refused);
        GateOutcome.Refused refused = (GateOutcome.Refused) outcome;
        assertEquals("E3", refused.gate());
        assertEquals("recovery_write_not_console_submittable", refused.body().get("reason_code"));
        assertEquals(0, decisions.callCount, "E4 must never run once E3 has refused (H5c)");
    }

    @Test
    void proceedsWhenEveryGatePasses() {
        FakeSessionRepository sessions = new FakeSessionRepository();
        String sessionId = SessionHasher.hash("raw-cookie-5");
        sessions.put(activeSession(sessionId));

        // An action declaring no required role token: E4 evaluates
        // NO_APPLICABLE_AUTHORITY, which proceeds (C3 §5.1).
        ActionRegistry registry = new ActionRegistry();
        registry.register(new ActionDescriptor("open_to_any_session", true, Optional.empty()));
        RbacEvaluator evaluator = new RbacEvaluator(new FakeRoleBindingRepository(),
                new FakeActorAuthzStateRepository(), cipher());
        GateChain chain = new GateChain(sessions, registry, evaluator, new FakeAuthzDecisionRepository());

        GateRequest request = new GateRequest("GET", Optional.of("raw-cookie-5"), Optional.empty(), Optional.empty(),
                "open_to_any_session", Optional.empty());

        GateOutcome outcome = chain.evaluate(request, NOW);

        assertTrue(outcome instanceof GateOutcome.Proceed);
        assertEquals(ACTOR, ((GateOutcome.Proceed) outcome).actorFingerprint());
    }
}
