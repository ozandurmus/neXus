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
                    "select actor_fingerprint, group_references, resolved_at, valid_until, directory_profile_id, principal_reference_encrypted, principal_reference_key_id "
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
                        + "valid_until = excluded.valid_until, directory_profile_id = null, "
                        + "principal_reference_encrypted = null, principal_reference_key_id = null",
                actorFingerprint, json, Timestamp.from(resolvedAt), Timestamp.from(validUntil)));
    }

    @Override
    public void upsertDirectory(ActorAuthzStateRecord observation) {
        if (!observation.hasDirectoryProof()) throw new IllegalArgumentException("directory_identity_not_proven");
        transactionBoundary.inTransaction(dsl -> {
            // Never attach two independently proven principals to one truncated actor correlator.
            dsl.fetch("select actor_fingerprint from sessions where actor_fingerprint = {0} for update", observation.actorFingerprint());
            var existing = dsl.fetch("select actor_fingerprint, group_references, resolved_at, valid_until, directory_profile_id, "
                    + "principal_reference_encrypted, principal_reference_key_id from actor_authz_state where actor_fingerprint = {0}",
                    observation.actorFingerprint()).stream().findFirst().map(JooqActorAuthzStateRepository::toRecord);
            if (existing.isPresent() && (!java.util.Objects.equals(existing.get().directoryProfileId(), observation.directoryProfileId())
                    || !java.util.Objects.equals(existing.get().principalReferenceKeyId(), observation.principalReferenceKeyId()))) {
                throw new IllegalStateException("directory_actor_ambiguous");
            }
            return dsl.execute("insert into actor_authz_state(actor_fingerprint, group_references, resolved_at, valid_until, "
                    + "directory_profile_id, principal_reference_encrypted, principal_reference_key_id) "
                    + "values ({0}, {1}::jsonb, {2}, {3}, {4}, {5}, {6}) "
                    + "on conflict (actor_fingerprint) do update set group_references = excluded.group_references, "
                    + "resolved_at = excluded.resolved_at, valid_until = excluded.valid_until, "
                    + "directory_profile_id = excluded.directory_profile_id, principal_reference_encrypted = excluded.principal_reference_encrypted, "
                    + "principal_reference_key_id = excluded.principal_reference_key_id",
                    observation.actorFingerprint(), toJsonArray(observation.groupReferences()), Timestamp.from(observation.resolvedAt()),
                    Timestamp.from(observation.validUntil()), observation.directoryProfileId(), observation.principalReferenceEncrypted(),
                    observation.principalReferenceKeyId());
        });
    }

    @Override public void expireDirectory() {
        transactionBoundary.inTransaction(dsl -> dsl.execute("delete from actor_authz_state where directory_profile_id is not null"));
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
                row.get("valid_until", Timestamp.class).toInstant(),
                row.get("directory_profile_id", String.class), row.get("principal_reference_encrypted", byte[].class),
                row.get("principal_reference_key_id", String.class));
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
