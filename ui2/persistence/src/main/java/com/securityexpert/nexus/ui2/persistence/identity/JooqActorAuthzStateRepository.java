package com.securityexpert.nexus.ui2.persistence.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Result;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * jOOQ-backed {@link ActorAuthzStateRepository}. Uses the plain
 * {@link TransactionBoundary} (never {@code AuditedTransactionBoundary}):
 * this table is a deliberate, named exception to C1-1's audit mechanism
 * (C3 §3.5).
 */
public final class JooqActorAuthzStateRepository implements ActorAuthzStateRepository {

    private final TransactionBoundary transactionBoundary;

    public JooqActorAuthzStateRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Override
    public Optional<ActorAuthzStateRecord> find(String actorFingerprint) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch(
                    "select actor_fingerprint, group_references, resolved_at, valid_until "
                            + "from actor_authz_state where actor_fingerprint = {0}", actorFingerprint);
            return rows.stream().findFirst().map(JooqActorAuthzStateRepository::toRecord);
        });
    }

    @Override
    public void upsert(String actorFingerprint, Set<String> groupReferences, Instant resolvedAt, Instant validUntil) {
        String json = toJsonArray(groupReferences);
        transactionBoundary.inTransaction(dsl -> dsl.execute(
                "insert into actor_authz_state(actor_fingerprint, group_references, resolved_at, valid_until) "
                        + "values ({0}, {1}::jsonb, {2}, {3}) "
                        + "on conflict (actor_fingerprint) do update set "
                        + "group_references = excluded.group_references, resolved_at = excluded.resolved_at, "
                        + "valid_until = excluded.valid_until",
                actorFingerprint, json, Timestamp.from(resolvedAt), Timestamp.from(validUntil)));
    }

    @Override
    public void delete(String actorFingerprint) {
        transactionBoundary.inTransaction(dsl -> dsl.execute(
                "delete from actor_authz_state where actor_fingerprint = {0}", actorFingerprint));
    }

    private static ActorAuthzStateRecord toRecord(Record row) {
        String json = row.get("group_references", JSONB.class).data();
        return new ActorAuthzStateRecord(
                row.get("actor_fingerprint", String.class),
                fromJsonArray(json),
                row.get("resolved_at", Timestamp.class).toInstant(),
                row.get("valid_until", Timestamp.class).toInstant());
    }

    static String toJsonArray(Set<String> values) {
        return values.stream()
                .map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    static Set<String> fromJsonArray(String json) {
        Set<String> result = new LinkedHashSet<>();
        String trimmed = json.strip();
        if (trimmed.length() <= 2) {
            return result;
        }
        String inner = trimmed.substring(1, trimmed.length() - 1);
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '"' && (i == 0 || inner.charAt(i - 1) != '\\')) {
                inString = !inString;
                continue;
            }
            if (c == ',' && !inString) {
                result.add(unescape(current.toString()));
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            result.add(unescape(current.toString()));
        }
        return result;
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
