package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.identity.SessionEndReason;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;

class SessionReconcilerTest {

    private static final class RecordingSessionRepository implements SessionRepository {
        final List<SessionRecord> active = new ArrayList<>();
        final List<String> expiredSessionIds = new ArrayList<>();
        final List<SessionEndReason> expiredReasons = new ArrayList<>();

        @Override
        public Optional<SessionRecord> findActiveByActor(String actorFingerprint) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<SessionRecord> findBySessionId(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SessionRecord> findActivePastDeadline(Instant asOf) {
            return active;
        }

        @Override
        public SessionRecord createActive(String sessionId, String actorFingerprint, String csrfSecret, Instant now,
                Duration idleTimeout, Duration absoluteLifetime, String actionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionRecord takeover(String priorSessionId, String newSessionId, String actorFingerprint,
                String csrfSecret, Instant now, Duration idleTimeout, Duration absoluteLifetime, String actionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void heartbeat(String sessionId, Instant now, Duration idleTimeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void expire(String sessionId, SessionEndReason reason, String actionId) {
            expiredSessionIds.add(sessionId);
            expiredReasons.add(reason);
        }

        @Override
        public void revoke(String sessionId, String endedByActorFingerprint, String actionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void revokeAccessGroupLost(String sessionId, String actionId) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void expiresAnIdleSessionWithTheIdleTimeoutReason() {
        Instant now = Instant.parse("2026-09-12T10:00:00Z");
        RecordingSessionRepository repo = new RecordingSessionRepository();
        repo.active.add(new SessionRecord("s1", "actor1", "csrf", SessionState.ACTIVE,
                now.minusSeconds(3600), now.minusSeconds(3600), now.minusSeconds(1), now.plusSeconds(30000),
                Optional.empty(), Optional.empty(), Optional.empty()));

        int count = new SessionReconciler(repo).reconcileOnce(now);

        assertEquals(1, count);
        assertEquals("s1", repo.expiredSessionIds.get(0));
        assertEquals(SessionEndReason.IDLE_TIMEOUT, repo.expiredReasons.get(0));
    }

    @Test
    void expiresASessionPastAbsoluteLifetimeWithThatReason() {
        Instant now = Instant.parse("2026-09-12T10:00:00Z");
        RecordingSessionRepository repo = new RecordingSessionRepository();
        repo.active.add(new SessionRecord("s2", "actor2", "csrf", SessionState.ACTIVE,
                now.minusSeconds(36001), now.minusSeconds(10), now.plusSeconds(1000), now.minusSeconds(1),
                Optional.empty(), Optional.empty(), Optional.empty()));

        int count = new SessionReconciler(repo).reconcileOnce(now);

        assertEquals(1, count);
        assertEquals(SessionEndReason.ABSOLUTE_LIFETIME, repo.expiredReasons.get(0));
    }
}
