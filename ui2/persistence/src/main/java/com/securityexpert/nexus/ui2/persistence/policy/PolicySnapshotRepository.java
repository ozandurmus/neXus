package com.securityexpert.nexus.ui2.persistence.policy;

import java.util.List;
import java.util.Optional;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;

/** One latest parsed snapshot per management policy; no raw vendor response or HTTP write surface. */
public final class PolicySnapshotRepository {
    public record Stored(String id, String collectedAt, String metadataJson, String snapshotJson) {}
    public record CatalogEntry(String metadataJson, int ruleCount) {}
    private final TransactionBoundary transactions;
    public PolicySnapshotRepository(TransactionBoundary transactions) { this.transactions = transactions; }

    public List<String> catalog() {
        return transactions.inTransaction(db -> db.fetch("select metadata::text as metadata from policy_snapshot order by policy_id")
                .map(row -> row.get("metadata", String.class)));
    }
    public List<CatalogEntry> catalogEntries() {
        return transactions.inTransaction(db -> db.fetch("select metadata::text as metadata, "
                + "(select coalesce(sum(jsonb_array_length(s->'rules')), 0)::int from jsonb_array_elements(snapshot->'sections') s) as rule_count "
                + "from policy_snapshot order by policy_id")
                .map(row -> new CatalogEntry(row.get("metadata", String.class), row.get("rule_count", Integer.class))));
    }
    public Optional<String> find(String id) {
        return transactions.inTransaction(db -> db.fetch("select snapshot::text as snapshot from policy_snapshot where policy_id = {0}", id)
                .stream().findFirst().map(row -> row.get("snapshot", String.class)));
    }
    public List<String> inventories(String source, String domain) {
        return transactions.inTransaction(db -> db.fetch("select snapshot::text as snapshot from cp_policy_object_inventory "
                + "where source_id = {0} and domain_ref = {1} order by object_type", source, domain)
                .map(row -> row.get("snapshot", String.class)));
    }
    public List<String> domainSnapshots(String source, String domain) {
        return transactions.inTransaction(db -> db.fetch("select snapshot::text as snapshot from policy_snapshot "
                + "where metadata->>'sourceId' = {0} and metadata->>'containerId' = {1} order by policy_id", source, domain)
                .map(row -> row.get("snapshot", String.class)));
    }
    public List<String> history(String policyId, String ruleId, int page) {
        return transactions.inTransaction(db -> db.fetch("select jsonb_build_object('revision', revision_id, 'ruleId', rule_id, "
                + "'identityFallback', identity_fallback, 'changeType', change_type, 'collectedAt', collected_at, "
                + "'changedOn', changed_on, 'changedBy', changed_by, 'changes', changes)::text as revision "
                + "from policy_rule_history where policy_id = {0} and ({1} = '' or rule_key in "
                + "(select rule_key from policy_rule_history where policy_id = {0} and rule_id = {1})) "
                + "order by collected_at desc, revision_id desc limit 200 offset {2}", policyId, ruleId, (long) page * 200)
                .map(row -> row.get("revision", String.class)));
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
