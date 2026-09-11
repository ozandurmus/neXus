package com.securityexpert.nexus.ui2.persistence.identity;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * jOOQ-backed {@link SessionRepository} (contract §3 persistence row).
 * State-changing calls run through {@link AuditedTransactionBoundary} (F3);
 * {@link #heartbeat} runs through the plain {@link TransactionBoundary}
 * because it never updates {@code state} and so never needs an audit
 * context (C3 §3.5).
 */
public final class JooqSessionRepository implements SessionRepository {

    private static final String COLUMNS = "session_id, actor_fingerprint, csrf_secret, state, created_at, "
            + "last_seen_at, idle_deadline_at, absolute_expires_at, superseded_by_session_id, "
            + "ended_by_actor_fingerprint, end_reason";

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqSessionRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public Optional<SessionRecord> findActiveByActor(String actorFingerprint) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select " + COLUMNS
                    + " from sessions where actor_fingerprint = {0} and state = 'ACTIVE'", actorFingerprint);
            return rows.stream().findFirst().map(JooqSessionRepository::toRecord);
        });
    }

    @Override
    public Optional<SessionRecord> findBySessionId(String sessionId) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select " + COLUMNS
                    + " from sessions where session_id = {0}", sessionId);
            return rows.stream().findFirst().map(JooqSessionRepository::toRecord);
        });
    }

    @Override
    public java.util.List<SessionRecord> findActivePastDeadline(Instant asOf) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select " + COLUMNS
                    + " from sessions where state = 'ACTIVE' and (idle_deadline_at <= {0} or absolute_expires_at <= {0})",
                    Timestamp.from(asOf));
            return rows.stream().map(JooqSessionRepository::toRecord).toList();
        });
    }

    @Override
    public SessionRecord createActive(String sessionId, String actorFingerprint, String csrfSecret,
            Instant now, Duration idleTimeout, Duration absoluteLifetime, String actionId) {
        Instant idleDeadline = now.plus(idleTimeout);
        Instant absoluteExpires = now.plus(absoluteLifetime);
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            dsl.execute("insert into sessions(session_id, actor_fingerprint, csrf_secret, state, "
                    + "created_at, last_seen_at, idle_deadline_at, absolute_expires_at) "
                    + "values ({0}, {1}, {2}, 'ACTIVE', {3}, {3}, {4}, {5})",
                    sessionId, actorFingerprint, csrfSecret, Timestamp.from(now),
                    Timestamp.from(idleDeadline), Timestamp.from(absoluteExpires));
            return new SessionRecord(sessionId, actorFingerprint, csrfSecret, SessionState.ACTIVE, now, now,
                    idleDeadline, absoluteExpires, Optional.empty(), Optional.empty(), Optional.empty());
        });
    }

    @Override
    public SessionRecord takeover(String priorSessionId, String newSessionId, String actorFingerprint,
            String csrfSecret, Instant now, Duration idleTimeout, Duration absoluteLifetime, String actionId) {
        Instant idleDeadline = now.plus(idleTimeout);
        Instant absoluteExpires = now.plus(absoluteLifetime);
        // C3 §3.4: attributed to the NEW session's actor, one transaction,
        // so the partial unique index is never transiently absent.
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            dsl.execute("update sessions set state = 'SUPERSEDED', superseded_by_session_id = {0} "
                    + "where session_id = {1} and state = 'ACTIVE'", newSessionId, priorSessionId);
            dsl.execute("insert into sessions(session_id, actor_fingerprint, csrf_secret, state, "
                    + "created_at, last_seen_at, idle_deadline_at, absolute_expires_at) "
                    + "values ({0}, {1}, {2}, 'ACTIVE', {3}, {3}, {4}, {5})",
                    newSessionId, actorFingerprint, csrfSecret, Timestamp.from(now),
                    Timestamp.from(idleDeadline), Timestamp.from(absoluteExpires));
            return new SessionRecord(newSessionId, actorFingerprint, csrfSecret, SessionState.ACTIVE, now, now,
                    idleDeadline, absoluteExpires, Optional.empty(), Optional.empty(), Optional.empty());
        });
    }

    @Override
    public void heartbeat(String sessionId, Instant now, Duration idleTimeout) {
        Instant idleDeadline = now.plus(idleTimeout);
        transactionBoundary.inTransaction((DSLContext dsl) -> dsl.execute(
                "update sessions set last_seen_at = {0}, idle_deadline_at = {1} "
                        + "where session_id = {2} and state = 'ACTIVE'",
                Timestamp.from(now), Timestamp.from(idleDeadline), sessionId));
    }

    @Override
    public void expire(String sessionId, SessionEndReason reason, String actionId) {
        auditedTransactionBoundary.inTransaction(SYSTEM_SESSION_RECONCILER, actionId, dsl -> dsl.execute(
                "update sessions set state = 'EXPIRED', end_reason = {0} "
                        + "where session_id = {1} and state = 'ACTIVE'",
                reason.column(), sessionId));
    }

    @Override
    public void revoke(String sessionId, String endedByActorFingerprint, String actionId) {
        auditedTransactionBoundary.inTransaction(endedByActorFingerprint, actionId, dsl -> dsl.execute(
                "update sessions set state = 'REVOKED', end_reason = 'revoked_by_admin', "
                        + "ended_by_actor_fingerprint = {0} where session_id = {1} and state = 'ACTIVE'",
                endedByActorFingerprint, sessionId));
    }

    @Override
    public void revokeAccessGroupLost(String sessionId, String actionId) {
        auditedTransactionBoundary.inTransaction(SYSTEM_REVALIDATION_ADAPTER, actionId, dsl -> dsl.execute(
                "update sessions set state = 'REVOKED', end_reason = 'access_group_lost' "
                        + "where session_id = {0} and state = 'ACTIVE'",
                sessionId));
    }

    private static SessionRecord toRecord(Record row) {
        return new SessionRecord(
                row.get("session_id", String.class),
                row.get("actor_fingerprint", String.class),
                row.get("csrf_secret", String.class),
                SessionState.valueOf(row.get("state", String.class)),
                row.get("created_at", Timestamp.class).toInstant(),
                row.get("last_seen_at", Timestamp.class).toInstant(),
                row.get("idle_deadline_at", Timestamp.class).toInstant(),
                row.get("absolute_expires_at", Timestamp.class).toInstant(),
                Optional.ofNullable(row.get("superseded_by_session_id", String.class)),
                Optional.ofNullable(row.get("ended_by_actor_fingerprint", String.class)),
                Optional.ofNullable(row.get("end_reason", String.class))
                        .map(JooqSessionRepository::forColumn));
    }

    private static SessionEndReason forColumn(String column) {
        for (SessionEndReason reason : SessionEndReason.values()) {
            if (reason.column().equals(column)) {
                return reason;
            }
        }
        throw new IllegalStateException("unknown end_reason column value: " + column);
    }
}
