package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.identity.SessionEndReason;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;

/**
 * Contract §8 tests 2-6, exercised against {@link LoginFlow} with an
 * in-memory fake that itself enforces the single-active-session invariant
 * (mirroring the partial unique index's effect for this pure-logic layer;
 * the index's own structural enforcement against a bypassed SQL INSERT is
 * proved separately, at the database layer, by a Testcontainers-dependent
 * test this environment cannot run — see SESSION_CLOSE / test 1).
 */
class LoginFlowTest {

    private static final Instant NOW = Instant.parse("2026-09-12T10:00:00Z");

    /** Enforces "at most one ACTIVE row per actor" itself, the way the partial index does at the DB layer. */
    private static final class InMemorySessionRepository implements SessionRepository {
        final Map<String, SessionRecord> bySessionId = new HashMap<>();

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
        public java.util.List<SessionRecord> findActivePastDeadline(Instant asOf) {
            return bySessionId.values().stream()
                    .filter(r -> r.state() == SessionState.ACTIVE
                            && (!asOf.isBefore(r.idleDeadlineAt()) || !asOf.isBefore(r.absoluteExpiresAt())))
                    .toList();
        }

        @Override
        public SessionRecord createActive(String sessionId, String actorFingerprint, String csrfSecret, Instant now,
                Duration idleTimeout, Duration absoluteLifetime, String actionId) {
            if (findActiveByActor(actorFingerprint).isPresent()) {
                throw new IllegalStateException("ux_sessions_one_active_per_actor violated");
            }
            SessionRecord record = new SessionRecord(sessionId, actorFingerprint, csrfSecret, SessionState.ACTIVE,
                    now, now, now.plus(idleTimeout), now.plus(absoluteLifetime), Optional.empty(), Optional.empty(),
                    Optional.empty());
            bySessionId.put(sessionId, record);
            return record;
        }

        @Override
        public SessionRecord takeover(String priorSessionId, String newSessionId, String actorFingerprint,
                String csrfSecret, Instant now, Duration idleTimeout, Duration absoluteLifetime, String actionId) {
            SessionRecord prior = bySessionId.get(priorSessionId);
            bySessionId.put(priorSessionId, new SessionRecord(prior.sessionId(), prior.actorFingerprint(),
                    prior.csrfSecret(), SessionState.SUPERSEDED, prior.createdAt(), prior.lastSeenAt(),
                    prior.idleDeadlineAt(), prior.absoluteExpiresAt(), Optional.of(newSessionId),
                    Optional.empty(), Optional.empty()));
            SessionRecord next = new SessionRecord(newSessionId, actorFingerprint, csrfSecret, SessionState.ACTIVE,
                    now, now, now.plus(idleTimeout), now.plus(absoluteLifetime), Optional.empty(), Optional.empty(),
                    Optional.empty());
            bySessionId.put(newSessionId, next);
            return next;
        }

        @Override
        public void heartbeat(String sessionId, Instant now, Duration idleTimeout) {
            SessionRecord r = bySessionId.get(sessionId);
            bySessionId.put(sessionId, new SessionRecord(r.sessionId(), r.actorFingerprint(), r.csrfSecret(),
                    r.state(), r.createdAt(), now, now.plus(idleTimeout), r.absoluteExpiresAt(),
                    r.supersededBySessionId(), r.endedByActorFingerprint(), r.endReason()));
        }

        @Override
        public void expire(String sessionId, SessionEndReason reason, String actionId) {
            SessionRecord r = bySessionId.get(sessionId);
            bySessionId.put(sessionId, new SessionRecord(r.sessionId(), r.actorFingerprint(), r.csrfSecret(),
                    SessionState.EXPIRED, r.createdAt(), r.lastSeenAt(), r.idleDeadlineAt(), r.absoluteExpiresAt(),
                    r.supersededBySessionId(), r.endedByActorFingerprint(), Optional.of(reason)));
        }

        @Override
        public void revoke(String sessionId, String endedByActorFingerprint, String actionId) {
            SessionRecord r = bySessionId.get(sessionId);
            bySessionId.put(sessionId, new SessionRecord(r.sessionId(), r.actorFingerprint(), r.csrfSecret(),
                    SessionState.REVOKED, r.createdAt(), r.lastSeenAt(), r.idleDeadlineAt(), r.absoluteExpiresAt(),
                    r.supersededBySessionId(), Optional.of(endedByActorFingerprint),
                    Optional.of(SessionEndReason.REVOKED_BY_ADMIN)));
        }

        @Override
        public void revokeAccessGroupLost(String sessionId, String actionId) {
            SessionRecord r = bySessionId.get(sessionId);
            bySessionId.put(sessionId, new SessionRecord(r.sessionId(), r.actorFingerprint(), r.csrfSecret(),
                    SessionState.REVOKED, r.createdAt(), r.lastSeenAt(), r.idleDeadlineAt(), r.absoluteExpiresAt(),
                    r.supersededBySessionId(), r.endedByActorFingerprint(), Optional.of(SessionEndReason.ACCESS_GROUP_LOST)));
        }
    }

    private static LoginFlow newFlow(InMemorySessionRepository repo) {
        return new LoginFlow(repo, Duration.ofMinutes(30), Duration.ofHours(10));
    }

    @Test
    void firstLoginForAnIdentityCreatesAnActiveSession() {
        InMemorySessionRepository repo = new InMemorySessionRepository();
        LoginFlow flow = newFlow(repo);

        LoginFlow.LoginResult result = flow.login("actor1", NOW);

        assertTrue(result instanceof LoginFlow.LoginResult.NewSession);
        SessionRecord session = ((LoginFlow.LoginResult.NewSession) result).session();
        assertEquals(SessionState.ACTIVE, session.state());
    }

    @Test
    void secondLoginForTheSameIdentityIsAConflictAndDoesNotTouchTheFirstSession() {
        InMemorySessionRepository repo = new InMemorySessionRepository();
        LoginFlow flow = newFlow(repo);
        LoginFlow.LoginResult.NewSession first =
                (LoginFlow.LoginResult.NewSession) flow.login("actor1", NOW);

        LoginFlow.LoginResult second = flow.login("actor1", NOW.plusSeconds(5));

        assertTrue(second instanceof LoginFlow.LoginResult.Conflict);
        SessionRecord stillActive = repo.findActiveByActor("actor1").orElseThrow();
        assertEquals(first.session().sessionId(), stillActive.sessionId());
        assertEquals(SessionState.ACTIVE, stillActive.state());
    }

    @Test
    void takeoverTransitionsPriorToSupersededAndCreatesANewActiveSessionInOneCall() {
        InMemorySessionRepository repo = new InMemorySessionRepository();
        LoginFlow flow = newFlow(repo);
        LoginFlow.LoginResult.NewSession first = (LoginFlow.LoginResult.NewSession) flow.login("actor1", NOW);
        LoginFlow.LoginResult.Conflict conflict =
                (LoginFlow.LoginResult.Conflict) flow.login("actor1", NOW.plusSeconds(5));

        LoginFlow.ResolveResult resolved = flow.resolve(conflict.conflictToken(), "takeover", NOW.plusSeconds(6));

        assertTrue(resolved instanceof LoginFlow.ResolveResult.TakenOver);
        SessionRecord s1 = repo.findBySessionId(first.session().sessionId()).orElseThrow();
        assertEquals(SessionState.SUPERSEDED, s1.state());
        SessionRecord s2 = ((LoginFlow.ResolveResult.TakenOver) resolved).session();
        assertEquals(SessionState.ACTIVE, s2.state());
        assertEquals(s2.sessionId(), s1.supersededBySessionId().orElseThrow());

        // A test connecting directly to Postgres and enumerating audit_log
        // rows to prove "exactly one" is the database-layer half of this
        // scenario (contract §8 test 2's own wording); that half requires
        // a container this environment does not have and is named as a
        // disabled placeholder in integration-tests, not silently skipped.
    }

    @Test
    void refuseLeavesThePriorSessionCompletelyUntouched() {
        InMemorySessionRepository repo = new InMemorySessionRepository();
        LoginFlow flow = newFlow(repo);
        LoginFlow.LoginResult.NewSession first = (LoginFlow.LoginResult.NewSession) flow.login("actor1", NOW);
        LoginFlow.LoginResult.Conflict conflict =
                (LoginFlow.LoginResult.Conflict) flow.login("actor1", NOW.plusSeconds(5));

        LoginFlow.ResolveResult resolved = flow.resolve(conflict.conflictToken(), "refuse", NOW.plusSeconds(6));

        assertTrue(resolved instanceof LoginFlow.ResolveResult.Refused);
        assertEquals("login_refused_active_session", ((LoginFlow.ResolveResult.Refused) resolved).reasonCode());
        SessionRecord s1 = repo.findBySessionId(first.session().sessionId()).orElseThrow();
        assertEquals(SessionState.ACTIVE, s1.state());
        assertTrue(s1.supersededBySessionId().isEmpty());
    }

    @Test
    void refuseByInactionIsTheDefaultAfterTheConflictTokenExpires() {
        InMemorySessionRepository repo = new InMemorySessionRepository();
        LoginFlow flow = newFlow(repo);
        LoginFlow.LoginResult.NewSession first = (LoginFlow.LoginResult.NewSession) flow.login("actor1", NOW);
        LoginFlow.LoginResult.Conflict conflict =
                (LoginFlow.LoginResult.Conflict) flow.login("actor1", NOW.plusSeconds(5));

        // /login/resolve is never called before the token's bound (2
        // minutes) expires; a late resolve attempt with "takeover" must
        // still refuse, and S1 must be exactly as it was.
        LoginFlow.ResolveResult resolved = flow.resolve(conflict.conflictToken(), "takeover",
                NOW.plusSeconds(5).plus(Duration.ofMinutes(3)));

        assertTrue(resolved instanceof LoginFlow.ResolveResult.Refused);
        SessionRecord s1 = repo.findBySessionId(first.session().sessionId()).orElseThrow();
        assertEquals(SessionState.ACTIVE, s1.state());
        assertTrue(s1.supersededBySessionId().isEmpty());
    }

    @Test
    void takeoverIsAlwaysAnExplicitCallNeverInferredFromTheTakeoverTokenAloneWithoutAResolveCall() {
        InMemorySessionRepository repo = new InMemorySessionRepository();
        LoginFlow flow = newFlow(repo);
        flow.login("actor1", NOW);
        flow.login("actor1", NOW.plusSeconds(5));

        // No /login/resolve call at all: S1 remains ACTIVE forever, by
        // construction -- there is no code path here that mutates a
        // session without an explicit resolve() call.
        SessionRecord s1 = repo.findActiveByActor("actor1").orElseThrow();
        assertEquals(SessionState.ACTIVE, s1.state());
    }
}
