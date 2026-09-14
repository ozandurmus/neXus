package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code sessions} persistence port (contract §3 placement row). Every
 * mutation implicitly goes through {@link com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary}
 * (F3): {@code sessions} is audited on {@code INSERT}/{@code UPDATE OF state},
 * so a state-changing call here always carries an actor fingerprint and
 * action id.
 */
public interface SessionRepository {

    Optional<SessionRecord> findActiveByActor(String actorFingerprint);

    /** Expiry-on-read variant for login conflict decisions (C3 §3.3 rows 4-5). */
    default Optional<SessionRecord> findActiveByActor(String actorFingerprint, Instant asOf) {
        expirePastDeadline(asOf);
        return findActiveByActor(actorFingerprint);
    }

    Optional<SessionRecord> findBySessionId(String sessionId);

    /** Expiry-on-read variant for E1 session decisions (C3 §3.3 rows 4-5). */
    default Optional<SessionRecord> findBySessionId(String sessionId, Instant asOf) {
        expirePastDeadline(asOf);
        return findBySessionId(sessionId);
    }

    /** {@code ACTIVE} rows whose idle or absolute deadline has already elapsed, for the reconciler (C3 §3.3 rows 4-5). */
    List<SessionRecord> findActivePastDeadline(Instant asOf);

    /** Ends stale rows before a decision reads them; idle wins when both deadlines have elapsed. */
    default void expirePastDeadline(Instant asOf) {
        for (SessionRecord session : findActivePastDeadline(asOf)) {
            boolean idleExpired = !asOf.isBefore(session.idleDeadlineAt());
            expire(session.sessionId(), idleExpired ? SessionEndReason.IDLE_TIMEOUT : SessionEndReason.ABSOLUTE_LIFETIME,
                    idleExpired ? "session_reconcile_idle_timeout" : "session_reconcile_absolute_lifetime");
        }
    }

    /**
     * Creates the first {@code ACTIVE} session for an identity that has
     * none (C3 §3.3 row 1). Fails (partial unique index violation) if a
     * concurrent request already created one — the structural enforcement
     * C3 §3.1 relies on, never merely an application check.
     */
    SessionRecord createActive(String sessionId, String actorFingerprint, String csrfSecret,
            Instant now, Duration idleTimeout, Duration absoluteLifetime, String actionId);

    /**
     * Takeover (C3 §3.3 row 2): {@code priorSessionId} → {@code SUPERSEDED},
     * {@code newSessionId} → {@code ACTIVE}, one transaction, attributed to
     * the <b>new</b> session's actor (C3 §3.4).
     */
    SessionRecord takeover(String priorSessionId, String newSessionId, String actorFingerprint, String csrfSecret,
            Instant now, Duration idleTimeout, Duration absoluteLifetime, String actionId);

    /**
     * {@code last_seen_at}/{@code idle_deadline_at} touch only — never
     * updates {@code state}, so {@code trg_audit_sessions} never fires and
     * no audit context is required (C3 §3.5).
     */
    void heartbeat(String sessionId, Instant now, Duration idleTimeout);

    /** Reconciler transition (C3 §3.3 rows 4-5), attributed to {@code system:session_reconciler}. */
    void expire(String sessionId, SessionEndReason reason, String actionId);

    /** {@code role:security_admin}-caused transition (C3 §3.3 row 6), attributed to the admin. */
    void revoke(String sessionId, String endedByActorFingerprint, String actionId);

    /** Re-validation-caused transition (C3 §3.3 row 7), attributed to {@code system:revalidation_adapter}. */
    void revokeAccessGroupLost(String sessionId, String actionId);

    /** Reserved, non-directory-derived actors C3 §3.4 names for reconciler/adapter-caused transitions. */
    String SYSTEM_SESSION_RECONCILER = "system:session_reconciler";
    String SYSTEM_REVALIDATION_ADAPTER = "system:revalidation_adapter";
}
