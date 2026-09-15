package com.securityexpert.nexus.ui2.service.api;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.persistence.audit.AuditLogRow;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRecord;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionEndReason;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.audit.AuditService;
import com.securityexpert.nexus.ui2.service.audit.FakeAuditLogRepository;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChain;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * Contract §8 tests 5-8, end to end through the real {@link GateChain} and
 * {@link RbacEvaluator} (fakes only below {@code SessionRepository}/{@code
 * RoleBindingRepository}/{@code ActorAuthzStateRepository}/{@code
 * AuthzDecisionRepository}) -- proving the own/all resolution
 * ({@code GateChain}'s dynamic-action overload) without a real PostgreSQL
 * server. The real-database half of these same claims (that seeded rows
 * really are the ones a real HTTP request reaches) is {@code
 * integration-tests}' job.
 */
class AuditControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
    private static final String VIEWER_GROUP = "cn=viewers,dc=test";
    private static final String SECURITY_ADMIN_GROUP = "cn=security-admins,dc=test";

    private static final class FakeSessionRepository implements SessionRepository {
        final Map<String, SessionRecord> bySessionId = new HashMap<>();

        @Override
        public Optional<SessionRecord> findActiveByActor(String actorFingerprint) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<SessionRecord> findBySessionId(String sessionId) {
            return Optional.ofNullable(bySessionId.get(sessionId));
        }

        @Override
        public List<SessionRecord> findActivePastDeadline(Instant asOf) {
            return List.of();
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
            // no-op: irrelevant to authorization outcome.
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
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void revoke(String bindingId, String revokedByActorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static final class FakeActorAuthzStateRepository implements ActorAuthzStateRepository {
        Set<String> groupReferences = Set.of();

        @Override
        public Optional<ActorAuthzStateRecord> find(String actorFingerprint) {
            // AuditController.authorize() calls the real Instant.now() (it is
            // not test-injectable), so this fixture's freshness window must
            // be relative to wall-clock time, not the fixed NOW used for
            // audit_log row timestamps below.
            Instant now = Instant.now();
            return Optional.of(new ActorAuthzStateRecord(actorFingerprint, groupReferences, now.minusSeconds(10),
                    now.plusSeconds(900)));
        }

        @Override
        public void upsert(String actorFingerprint, Set<String> groupReferences, Instant resolvedAt,
                Instant validUntil) {
        }

        @Override
        public void delete(String actorFingerprint) {
        }
    }

    private static final class FakeAuthzDecisionRepository implements AuthzDecisionRepository {
        final List<String> actionIds = new ArrayList<>();
        int callCount = 0;

        @Override
        public long insert(String sessionId, String actorFingerprint, String actionId, Optional<String> targetRef,
                AuthzOutcome outcome, Optional<String> authority, Optional<String> reasonCode,
                Optional<String> bindingId) {
            actionIds.add(actionId);
            callCount++;
            return callCount;
        }
    }

    private static GroupReferenceCipher cipher() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return GroupReferenceCipher.fromBase64Key(Base64.getEncoder().encodeToString(key));
    }

    private static SessionRecord activeSession(String sessionId, String actorFingerprint) {
        // Same wall-clock-relative reasoning as FakeActorAuthzStateRepository above.
        Instant now = Instant.now();
        return new SessionRecord(sessionId, actorFingerprint, "csrf-secret", SessionState.ACTIVE,
                now.minusSeconds(60), now.minusSeconds(10), now.plusSeconds(1800), now.plusSeconds(36000),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static MockHttpServletRequest requestWithSessionCookie(String rawCookieValue) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/audit");
        request.setCookies(new jakarta.servlet.http.Cookie("ui2_session", rawCookieValue));
        return request;
    }

    private record Harness(AuditController controller, FakeAuthzDecisionRepository decisions,
            FakeAuditLogRepository repository) {
    }

    private static Harness harness(String actorFingerprint, Set<String> groupReferences) {
        FakeSessionRepository sessions = new FakeSessionRepository();
        String sessionId = com.securityexpert.nexus.ui2.service.security.SessionHasher.hash("raw-cookie-1");
        sessions.bySessionId.put(sessionId, activeSession(sessionId, actorFingerprint));

        GroupReferenceCipher cipher = cipher();
        FakeRoleBindingRepository bindings = new FakeRoleBindingRepository();
        bindings.bindings.add(new RoleBindingRecord("binding-viewer", RoleToken.VIEWER.token(),
                cipher.encrypt(VIEWER_GROUP), "key-1", "bootstrap", NOW.minusSeconds(1000), Optional.empty(),
                Optional.empty()));
        bindings.bindings.add(new RoleBindingRecord("binding-admin", RoleToken.SECURITY_ADMIN.token(),
                cipher.encrypt(SECURITY_ADMIN_GROUP), "key-1", "bootstrap", NOW.minusSeconds(1000), Optional.empty(),
                Optional.empty()));

        FakeActorAuthzStateRepository authzState = new FakeActorAuthzStateRepository();
        authzState.groupReferences = groupReferences;

        RbacEvaluator rbacEvaluator = new RbacEvaluator(bindings, authzState, cipher);
        FakeAuthzDecisionRepository decisions = new FakeAuthzDecisionRepository();
        GateChain gateChain = new GateChain(sessions, new ActionRegistry(), rbacEvaluator, decisions);

        FakeAuditLogRepository repository = new FakeAuditLogRepository();
        AuditService auditService = new AuditService(repository);
        AuditController controller = new AuditController(auditService, gateChain, rbacEvaluator);
        return new Harness(controller, decisions, repository);
    }

    @Test
    void viewerSeesOnlyOwnRowsAndOneAuthzDecisionIsWritten() {
        Harness h = harness("viewer-actor", Set.of(VIEWER_GROUP));
        h.repository.rows.add(new AuditLogRow(1, "devices", "dev-1", "INSERT", "viewer-actor", "device_register", NOW,
                Optional.empty(), Optional.empty(), Optional.empty()));
        h.repository.rows.add(new AuditLogRow(2, "devices", "dev-2", "INSERT", "other-actor", "device_register", NOW,
                Optional.empty(), Optional.empty(), Optional.empty()));

        ResponseEntity<Map<String, Object>> response = h.controller.list(null, null, null, null, null, null, null,
                null, null, null, requestWithSessionCookie("raw-cookie-1"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) response.getBody().get("entries");
        assertEquals(1, entries.size());
        assertEquals("dev-1", entries.get(0).get("row_pk"));
        assertEquals(1, h.decisions.callCount, "exactly one authz_decisions row per read (AC-6)");
        assertEquals(List.of(ActionRegistry.AUDIT_READ_OWN), h.decisions.actionIds);
    }

    @Test
    void securityAdminSeesEveryRow() {
        Harness h = harness("admin-actor", Set.of(SECURITY_ADMIN_GROUP));
        h.repository.rows.add(new AuditLogRow(1, "devices", "dev-1", "INSERT", "viewer-actor", "device_register", NOW,
                Optional.empty(), Optional.empty(), Optional.empty()));
        h.repository.rows.add(new AuditLogRow(2, "devices", "dev-2", "INSERT", "other-actor", "device_register", NOW,
                Optional.empty(), Optional.empty(), Optional.empty()));

        ResponseEntity<Map<String, Object>> response = h.controller.list(null, null, null, null, null, null, null,
                null, null, null, requestWithSessionCookie("raw-cookie-1"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) response.getBody().get("entries");
        assertEquals(2, entries.size());
        assertEquals(List.of(ActionRegistry.AUDIT_READ_ALL), h.decisions.actionIds);
    }

    /** Contract §5.1/AC-5: neither token -> an explained refusal, not a leak, and still exactly one decision row. */
    @Test
    void actorWithNeitherTokenIsRefusedWithExactlyOneDecisionRow() {
        Harness h = harness("nobody-actor", Set.of());

        ResponseEntity<Map<String, Object>> response = h.controller.list(null, null, null, null, null, null, null,
                null, null, null, requestWithSessionCookie("raw-cookie-1"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("ACTION_REFUSED", response.getBody().get("error"));
        assertTrue(response.getBody().containsKey("reason_code"));
        assertEquals(1, h.decisions.callCount, "AC-6: refused or permitted, exactly one row either way");
        assertEquals(List.of(ActionRegistry.AUDIT_READ_OWN), h.decisions.actionIds,
                "the resolver's own-scope default is what a non-security-admin actor is evaluated against");
    }

    /** Contract §5.2/§8 test 7: never silently narrowed. */
    @Test
    void viewerPassingAnotherActorsFingerprintIsRefusedNotNarrowed() {
        Harness h = harness("viewer-actor", Set.of(VIEWER_GROUP));

        ResponseEntity<Map<String, Object>> response = h.controller.list(null, null, null, "other-actor", null, null,
                null, null, null, null, requestWithSessionCookie("raw-cookie-1"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("actor_not_in_required_group", response.getBody().get("reason_code"));
    }

    /** Contract §4.1: an unknown table_name is a plain 400, not the RBAC-shaped 403 envelope. */
    @Test
    void unknownTableNameIsPlainValidationFailedNotActionRefused() {
        Harness h = harness("admin-actor", Set.of(SECURITY_ADMIN_GROUP));

        ResponseEntity<Map<String, Object>> response = h.controller.list("not_a_real_table", null, null, null, null,
                null, null, null, null, null, requestWithSessionCookie("raw-cookie-1"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_FAILED", response.getBody().get("error"));
        assertEquals("unknown_table_name", response.getBody().get("reason_code"));
    }

    @Test
    void detailOutsideViewerScopeIs404() {
        Harness h = harness("viewer-actor", Set.of(VIEWER_GROUP));
        h.repository.rows.add(new AuditLogRow(1, "devices", "dev-1", "INSERT", "other-actor", "device_register", NOW,
                Optional.empty(), Optional.empty(), Optional.empty()));

        ResponseEntity<Map<String, Object>> response = h.controller.get("1", requestWithSessionCookie("raw-cookie-1"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
