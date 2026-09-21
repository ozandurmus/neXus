package com.securityexpert.nexus.ui2.persistence.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

class SessionExpiryOnReadTest {

    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");

    private static final class InMemorySessionRepository implements SessionRepository {
        final Map<String, SessionRecord> sessions = new HashMap<>();

        @Override
        public Optional<SessionRecord> findActiveByActor(String actorFingerprint) {
            return sessions.values().stream().filter(session -> session.actorFingerprint().equals(actorFingerprint)
                    && session.state() == SessionState.ACTIVE).findFirst();
        }

        @Override
        public Optional<SessionRecord> findBySessionId(String sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public List<SessionRecord> findActivePastDeadline(Instant asOf) {
            return sessions.values().stream().filter(session -> session.state() == SessionState.ACTIVE
                    && (!asOf.isBefore(session.idleDeadlineAt()) || !asOf.isBefore(session.absoluteExpiresAt()))).toList();
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
            SessionRecord session = sessions.get(sessionId);
            sessions.put(sessionId, new SessionRecord(session.sessionId(), session.actorFingerprint(), session.csrfSecret(),
                    SessionState.EXPIRED, session.createdAt(), session.lastSeenAt(), session.idleDeadlineAt(),
                    session.absoluteExpiresAt(), session.supersededBySessionId(), session.endedByActorFingerprint(),
                    Optional.of(reason)));
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
    void expiryOnReadUsesTheRightReasonAndLeavesALiveSessionUntouched() {
        InMemorySessionRepository repository = new InMemorySessionRepository();
        repository.sessions.put("idle", session("idle", NOW.minusSeconds(1), NOW.plusSeconds(3600)));
        repository.sessions.put("absolute", session("absolute", NOW.plusSeconds(3600), NOW.minusSeconds(1)));
        repository.sessions.put("live", session("live", NOW.plusSeconds(3600), NOW.plusSeconds(3600)));

        repository.expirePastDeadline(NOW);

        assertEquals(SessionEndReason.IDLE_TIMEOUT, repository.findBySessionId("idle").orElseThrow().endReason().orElseThrow());
        assertEquals(SessionEndReason.ABSOLUTE_LIFETIME,
                repository.findBySessionId("absolute").orElseThrow().endReason().orElseThrow());
        assertEquals(SessionState.ACTIVE, repository.findBySessionId("live").orElseThrow().state());
    }

    @Test
    void conflictQueryRequiresBothDeadlinesToRemainInTheFuture() {
        List<String> statements = new ArrayList<>();
        TransactionBoundary boundary = new TransactionBoundary() {
            @Override
            public <T> T inTransaction(java.util.function.Function<org.jooq.DSLContext, T> work) {
                return work.apply(recordingDsl(statements));
            }
        };

        new JooqSessionRepository(boundary).findActiveByActor("actor", NOW);

        assertTrue(statements.stream().anyMatch(statement -> statement.contains("actor_fingerprint = {0}")
                && statement.contains("idle_deadline_at > {1}")
                && statement.contains("absolute_expires_at > {1}")));
    }

    @Test
    void activeListRequiresBothDeadlinesToRemainInTheFuture() {
        List<String> statements = new ArrayList<>();
        TransactionBoundary boundary = new TransactionBoundary() {
            @Override
            public <T> T inTransaction(java.util.function.Function<org.jooq.DSLContext, T> work) {
                return work.apply(recordingDsl(statements));
            }
        };

        new JooqSessionRepository(boundary).findActive(NOW);

        assertTrue(statements.stream().anyMatch(statement -> statement.contains("state = 'ACTIVE'")
                && statement.contains("idle_deadline_at > {0}")
                && statement.contains("absolute_expires_at > {0}")));
    }

    private static SessionRecord session(String id, Instant idleDeadline, Instant absoluteDeadline) {
        return new SessionRecord(id, "actor", "csrf", SessionState.ACTIVE, NOW.minusSeconds(10), NOW.minusSeconds(10),
                idleDeadline, absoluteDeadline, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static DSLContext recordingDsl(List<String> statements) {
        return (DSLContext) Proxy.newProxyInstance(SessionExpiryOnReadTest.class.getClassLoader(),
                new Class<?>[] { DSLContext.class }, (proxy, method, args) -> {
                    if (method.getName().equals("execute")) {
                        statements.add((String) args[0]);
                        return 0;
                    }
                    if (method.getName().equals("fetch")) {
                        statements.add((String) args[0]);
                        return emptyResult();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static Result<Record> emptyResult() {
        return (Result<Record>) Proxy.newProxyInstance(SessionExpiryOnReadTest.class.getClassLoader(),
                new Class<?>[] { Result.class }, (proxy, method, args) -> {
                    if (method.getName().equals("stream")) {
                        return Stream.empty();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
