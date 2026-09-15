package com.securityexpert.nexus.ui2.persistence.identity;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;

/** C9: the existing C3 session lifecycle and C1 envelope custody, without a new transport. */
public final class ReplaySessionRepository {
    private final TransactionBoundary transactions;
    private final AuditedTransactionBoundary audited;
    private final GroupReferenceCipher cipher;
    private final String wrappingKeyId;

    public ReplaySessionRepository(TransactionBoundary transactions, GroupReferenceCipher cipher, String wrappingKeyId) {
        this.transactions = transactions;
        this.audited = new AuditedTransactionBoundary(transactions);
        this.cipher = cipher;
        this.wrappingKeyId = wrappingKeyId;
    }

    public boolean isReplay(String sessionId) {
        return transactions.inTransaction(dsl -> Boolean.TRUE.equals(dsl.fetchValue(
                "select replay_viewer from sessions where session_id = {0}", Boolean.class, sessionId)));
    }

    /** Locks and supersedes S1, inserts replay S2 and creates the installation key in one transaction. */
    public void activate(String priorId, String newId, String actor, Instant now) {
        audited.inTransaction(actor, "replay_session_activate", dsl -> {
            var prior = dsl.fetchOne("select csrf_secret, idle_deadline_at, absolute_expires_at from sessions "
                    + "where session_id = {0} and actor_fingerprint = {1} and state = 'ACTIVE' "
                    + "and not replay_viewer and idle_deadline_at > {2} and absolute_expires_at > {2} for update",
                    priorId, actor, Timestamp.from(now));
            if (prior == null) {
                throw new IllegalStateException("replay activation requires an active ordinary session");
            }
            // INSERT ... ON CONFLICT serializes first activations; losing candidates are discarded.
            if (dsl.fetchOne("select key_id from replay_projection_key where key_id = 'replay-v1'") == null) {
                byte[] key = new byte[32];
                new SecureRandom().nextBytes(key);
                dsl.execute("insert into replay_projection_key(key_id, encrypted_key, wrapping_key_id) "
                        + "values ('replay-v1', {0}, {1}) on conflict (key_id) do nothing",
                        cipher.encrypt(Base64.getEncoder().encodeToString(key)), wrappingKeyId);
            }
            dsl.execute("update sessions set state = 'SUPERSEDED', end_reason = 'login_elsewhere' "
                    + "where session_id = {0}", priorId);
            dsl.execute("insert into sessions(session_id, actor_fingerprint, csrf_secret, state, created_at, "
                    + "last_seen_at, idle_deadline_at, absolute_expires_at, replay_viewer) "
                    + "values ({0}, {1}, {2}, 'ACTIVE', {3}, {3}, {4}, {5}, true)",
                    newId, actor, prior.get("csrf_secret"), Timestamp.from(now),
                    prior.get("idle_deadline_at"), prior.get("absolute_expires_at"));
            dsl.execute("update sessions set superseded_by_session_id = {0} where session_id = {1}", newId, priorId);
            return null;
        });
    }

    public byte[] projectionKey() {
        return transactions.inTransaction(dsl -> {
            var row = dsl.fetchOne("select encrypted_key, wrapping_key_id from replay_projection_key "
                    + "where key_id = 'replay-v1'");
            if (row == null || !wrappingKeyId.equals(row.get("wrapping_key_id", String.class))) {
                throw new IllegalStateException("replay key custody unavailable");
            }
            byte[] key = Base64.getDecoder().decode(cipher.decrypt(row.get("encrypted_key", byte[].class)));
            if (key.length != 32) {
                throw new IllegalStateException("replay key custody unavailable");
            }
            return key;
        });
    }
}
