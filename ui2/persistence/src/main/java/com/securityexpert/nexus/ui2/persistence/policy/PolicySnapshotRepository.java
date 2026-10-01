package com.securityexpert.nexus.ui2.persistence.policy;

import java.util.List;
import java.util.Optional;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;

/** One latest parsed snapshot per management policy; no raw vendor response or HTTP write surface. */
public final class PolicySnapshotRepository {
    public record Stored(String id, String collectedAt, String metadataJson, String snapshotJson) {}
    private final TransactionBoundary transactions;
    public PolicySnapshotRepository(TransactionBoundary transactions) { this.transactions = transactions; }

    public List<String> catalog() {
        return transactions.inTransaction(db -> db.fetch("select metadata::text as metadata from policy_snapshot order by policy_id")
                .map(row -> row.get("metadata", String.class)));
    }
    public Optional<String> find(String id) {
        return transactions.inTransaction(db -> db.fetch("select snapshot::text as snapshot from policy_snapshot where policy_id = {0}", id)
                .stream().findFirst().map(row -> row.get("snapshot", String.class)));
    }
    /** Future approved collectors publish a complete parse atomically; older runs cannot replace newer ones. */
    public void save(Stored stored, String actorFingerprint, String actionId) {
        new AuditedTransactionBoundary(transactions).inTransaction(actorFingerprint, actionId, db -> {
            db.execute("insert into policy_snapshot(policy_id, collected_at, metadata, snapshot) values ({0}, {1}::timestamptz, {2}::jsonb, {3}::jsonb) "
                    + "on conflict (policy_id) do update set collected_at = excluded.collected_at, metadata = excluded.metadata, snapshot = excluded.snapshot "
                    + "where policy_snapshot.collected_at < excluded.collected_at",
                    stored.id(), stored.collectedAt(), stored.metadataJson(), stored.snapshotJson());
            return null;
        });
    }
}
