package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.identity.SessionEndReason;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;

/**
 * NXS-LOCAL-0152 AC-4: sign-out ends the server-side session row, so a later
 * request carrying the same cookie is unauthenticated -- proved here at the
 * repository-state level (a session's {@link SessionRecord#isActive} flips
 * to false), which is exactly what {@code GateChain}'s E1 and
 * {@code SessionStatusController} both key their own authenticated/
 * unauthenticated answer on.
 */
class SessionSelfLogoutServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    private static final class FakeSessionRepository implements SessionRepository {
        final Map<String, SessionRecord> bySessionId = new HashMap<>();
        String lastRevokedBy;
        String lastActionId;

        @Override
        public Optional<SessionRecord> findActiveByActor(String actorFingerprint) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public Optional<SessionRecord> findBySessionId(String sessionId) {
            return Optional.ofNullable(bySessionId.get(sessionId));
        }

        @Override
        public List<SessionRecord> findActivePastDeadline(Instant asOf) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public SessionRecord createActive(String sessionId, String actorFingerprint, String csrfSecret, Instant now,
                Duration idleTimeout, Duration absoluteLifetime, String actionId) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public SessionRecord takeover(String priorSessionId, String newSessionId, String actorFingerprint,
                String csrfSecret, Instant now, Duration idleTimeout, Duration absoluteLifetime, String actionId) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void heartbeat(String sessionId, Instant now, Duration idleTimeout) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void expire(String sessionId, SessionEndReason reason, String actionId) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void revoke(String sessionId, String endedByActorFingerprint, String actionId) {
            lastRevokedBy = endedByActorFingerprint;
            lastActionId = actionId;
            SessionRecord r = bySessionId.get(sessionId);
            bySessionId.put(sessionId, new SessionRecord(r.sessionId(), r.actorFingerprint(), r.csrfSecret(),
                    SessionState.REVOKED, r.createdAt(), r.lastSeenAt(), r.idleDeadlineAt(), r.absoluteExpiresAt(),
                    r.supersededBySessionId(), Optional.of(endedByActorFingerprint), Optional.of(SessionEndReason.REVOKED_BY_ADMIN)));
        }

        @Override
        public void revokeAccessGroupLost(String sessionId, String actionId) {
            throw new UnsupportedOperationException("not exercised by this test");
        }
    }

    private SessionRecord activeSession(String sessionId, String actorFingerprint) {
        return new SessionRecord(sessionId, actorFingerprint, "csrf-secret", SessionState.ACTIVE,
                NOW.minusSeconds(60), NOW.minusSeconds(10), NOW.plusSeconds(1800), NOW.plusSeconds(36000),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    void signOutEndsTheServerSideSessionSoALaterRequestWithTheSameCookieIsUnauthenticated() {
        FakeSessionRepository repository = new FakeSessionRepository();
        String sessionId = "session-1";
        repository.bySessionId.put(sessionId, activeSession(sessionId, "actor-1"));
        SessionSelfLogoutService service = new SessionSelfLogoutService(repository);

        SessionSelfLogoutService.Result result = service.logout(sessionId);

        assertTrue(result instanceof SessionSelfLogoutService.Result.Ok);
        SessionRecord afterLogout = repository.findBySessionId(sessionId).orElseThrow();
        assertFalse(afterLogout.isActive(NOW), "a later request against this session id must see it as inactive");
        assertEquals(SessionState.REVOKED, afterLogout.state());
        assertEquals("actor-1", repository.lastRevokedBy, "self sign-out is attributed to the session's own actor");
        assertEquals(SessionSelfLogoutService.ACTION_SESSION_LOGOUT_SELF, repository.lastActionId,
                "the audit action id must be distinguishable from an admin-caused revoke");
    }

    @Test
    void signingOutASessionThatIsAlreadyGoneIsReportedAsNoActiveSessionRatherThanThrowing() {
        FakeSessionRepository repository = new FakeSessionRepository();
        SessionSelfLogoutService service = new SessionSelfLogoutService(repository);

        SessionSelfLogoutService.Result result = service.logout("no-such-session");

        assertTrue(result instanceof SessionSelfLogoutService.Result.NoActiveSession);
    }
}
