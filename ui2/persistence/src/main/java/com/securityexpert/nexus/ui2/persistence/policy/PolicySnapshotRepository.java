package com.securityexpert.nexus.ui2.persistence.policy;

import java.util.List;
import java.util.Optional;
import java.util.Objects;
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
    /**
     * A stored snapshot with the immutable version of the row it was read from. {@link #save} replaces a row only with a
     * strictly newer collected_at, so (collected_at, stored size) changes whenever the content does; the version is
     * built in UTC so it does not depend on the connection's time zone.
     */
    public record Versioned(String version, String snapshotJson) {}
    private static final String VERSION = "to_char(collected_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US') "
        + "|| '|' || pg_column_size(snapshot)::text";
    /** The row's version without reading the snapshot document (the size comes from the TOAST pointer). */
    public Optional<String> version(String id) {
        return transactions.inTransaction(db -> db.fetch("select " + VERSION + " as version from policy_snapshot where policy_id = {0}", id)
                .stream().findFirst().map(row -> row.get("version", String.class)));
    }
    /** The snapshot and its version read in one statement, so the pair is consistent even while the row is replaced. */
    public Optional<Versioned> findVersioned(String id) {
        return transactions.inTransaction(db -> db.fetch("select " + VERSION + " as version, snapshot::text as snapshot "
                + "from policy_snapshot where policy_id = {0}", id)
                .stream().findFirst().map(row -> new Versioned(row.get("version", String.class), row.get("snapshot", String.class))));
    }
    /** Existence only; never reads or parses the snapshot document. */
    public boolean exists(String id) {
        return transactions.inTransaction(db -> !db.fetch("select 1 from policy_snapshot where policy_id = {0}", id).isEmpty());
    }
    public List<String> inventories(String source, String domain) {
        var result = new java.util.ArrayList<String>();
        streamInventories(source, domain, reader -> {
            try { var out = new java.io.StringWriter(); reader.transferTo(out); result.add(out.toString()); }
            catch (java.io.IOException invalid) { throw new IllegalStateException("POLICY_CHUNK_INVALID"); }
        });
        return result;
    }
    /** Each reader fetches one bounded chunk at a time, outside the manifest transaction. */
    public void streamInventories(String source, String domain, java.util.function.Consumer<java.io.Reader> consume) {
        streamInventoryChunks(source, domain, (metadata, reader) -> consume.accept(reader));
    }
    public void streamInventoryChunks(String source, String domain, java.util.function.BiConsumer<String, java.io.Reader> consume) {
        var rows = transactions.inTransaction(db -> db.fetch("select snapshot::text as snapshot, object_type, chunk_generation, chunk_count "
            + "from cp_policy_object_inventory where source_id = {0} and domain_ref = {1} order by object_type", source, domain));
        for (var row : rows) {
            try (var reader = row.field("chunk_generation") == null || row.get("chunk_generation", String.class) == null
                    ? new java.io.StringReader(row.get("snapshot", String.class))
                    : new PolicyChunkStore(transactions).reader(source, domain, row.get("object_type", String.class), "INVENTORY",
                        new PolicyChunkStore.Manifest(row.get("chunk_generation", String.class), row.get("chunk_count", Integer.class)))) {
                consume.accept(row.field("chunk_generation") == null || row.get("chunk_generation", String.class) == null
                    ? null : row.get("snapshot", String.class), reader);
            } catch (java.io.IOException invalid) { throw new IllegalStateException("POLICY_CHUNK_INVALID"); }
        }
    }
    public List<String> inventoryRevision(String source, String domain) {
        return transactions.inTransaction(db -> db.fetch("select snapshot::text as snapshot, object_type, chunk_generation, chunk_count "
            + "from cp_policy_object_inventory where source_id = {0} and domain_ref = {1} order by object_type", source, domain)
            .map(row -> row.get("snapshot", String.class) + (row.field("chunk_generation") == null ? "" :
                "|" + row.get("chunk_generation", String.class) + "|" + row.get("chunk_count", Integer.class))));
    }
    /** Cheap change marker per stored snapshot; content only changes with a strictly newer collected_at in {@link #save}. */
    public List<String> domainSnapshotRevisions(String source, String domain) {
        return transactions.inTransaction(db -> db.fetch("select policy_id || '|' || collected_at::text || '|' || pg_column_size(snapshot)::text as revision "
                + "from policy_snapshot where metadata->>'sourceId' = {0} and metadata->>'containerId' = {1} order by policy_id", source, domain)
                .map(row -> row.get("revision", String.class)));
    }
    public List<String> domainSnapshots(String source, String domain) {
        return transactions.inTransaction(db -> db.fetch("select snapshot::text as snapshot from policy_snapshot "
                + "where metadata->>'sourceId' = {0} and metadata->>'containerId' = {1} order by policy_id", source, domain)
                .map(row -> row.get("snapshot", String.class)));
    }
    public record InventoryLink(String uid, String name, String deviceId) {}
    /** Navigation links only: exact stored UID/name matches within the management source/domain, never identity proof. */
    public List<InventoryLink> inventoryLinks(String source, String domain) {
        return transactions.inTransaction(db -> db.fetch("select distinct c.stable_identifier, c.display_name, d.device_id "
            + "from devices manager join endpoints e on e.device_id = manager.device_id "
            + "join discovery_run r on r.management_address = e.address_ref and r.vendor = manager.vendor_hint and r.state = 'FINISHED' "
            + "join discovery_candidate c on c.run_id = r.run_id "
            + "join devices d on d.discovery_match_key = 'check_point|' || c.owning_domain || '|' || c.stable_identifier "
            + "where manager.device_id = {0} and c.owning_domain in "
            + "(select metadata->>'containerName' from policy_snapshot where metadata->>'sourceId' = {0} and metadata->>'containerId' = {1}) "
            + "union select '', t->>'name', d.device_id from policy_snapshot p "
            + "cross join lateral jsonb_array_elements(p.metadata->'targets') t join devices d on d.device_id = t->>'deviceId' "
            + "where p.metadata->>'sourceId' = {0} and p.metadata->>'containerId' = {1}",
            source, domain).map(row -> new InventoryLink(Objects.requireNonNullElse(row.get("stable_identifier", String.class), ""),
                Objects.requireNonNullElse(row.get("display_name", String.class), ""), row.get("device_id", String.class))));
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
    /**
     * SQL twin of {@code PolicySnapshot#ruleComplete} over a stored snapshot document: true when some failure is neither
     * advisory ({@code PolicySnapshot.ADVISORY_REASONS}) nor the explicit UNKNOWN NAT part ({@code PolicySnapshot#unknownPart}),
     * i.e. some rules of the policy are missing (an in-progress checkpoint, a lost layer). Reads only the failures array.
     */
    static String gap(String document) {
        return "exists (select 1 from jsonb_array_elements(coalesce(" + document + "->'failures', '[]'::jsonb)) f "
            + "where not (coalesce(f->>'reason', '') in ('POLICY_HITS_UNAVAILABLE', 'POLICY_DB_INVENTORY_WRITE_FAILED') "
            + "or (f->>'reason' = 'VENDOR_NAME_NOT_UNIQUE' and f->>'layerName' = 'NAT')))";
    }
    /**
     * Older runs cannot replace newer ones, and a snapshot with missing rules never replaces one that has every rule:
     * a running collection's checkpoint, or a run that lost a layer, leaves the last rule-complete snapshot current
     * (2026-11-13: CP checkpoints made a 9,407-rule policy read as 6,262 rules for the length of every collection).
     * A gapped snapshot still replaces a gapped or missing one, so a first or recovering collection stays visible.
     * Returns whether the row was written.
     */
    public boolean save(Stored stored, String actorFingerprint, String actionId) {
        return PolicyJsonWrite.guarded("PolicySnapshotRepository.save", "SNAPSHOT_UPSERT_HISTORY_TRIGGER",
            PolicyJsonWrite.bytes(stored.metadataJson(), stored.snapshotJson()),
            () -> new AuditedTransactionBoundary(transactions).inTransaction(actorFingerprint, actionId, db ->
            db.execute("insert into policy_snapshot(policy_id, collected_at, metadata, snapshot) values ({0}, {1}::timestamptz, {2}::jsonb, {3}::jsonb) "
                    + "on conflict (policy_id) do update set collected_at = excluded.collected_at, metadata = excluded.metadata, snapshot = excluded.snapshot "
                    + "where policy_snapshot.collected_at < excluded.collected_at "
                    + "and (not " + gap("excluded.snapshot") + " or " + gap("policy_snapshot.snapshot") + ")",
                    stored.id(), stored.collectedAt(), PolicyJsonWrite.json(db, stored.metadataJson()), PolicyJsonWrite.json(db, stored.snapshotJson())) > 0));
    }
    /** Rule count of the stored snapshot and whether it has every rule; empty when none is stored. */
    public record RuleFacts(int rules, boolean ruleComplete) {}
    public Optional<RuleFacts> ruleFacts(String policyId) {
        return transactions.inTransaction(db -> db.fetch("select coalesce((select sum(jsonb_array_length(coalesce(s->'rules', '[]'::jsonb))) "
                + "from jsonb_array_elements(coalesce(snapshot->'sections', '[]'::jsonb)) s), 0)::int as rules, "
                + "not " + gap("snapshot") + " as complete from policy_snapshot where policy_id = {0}", policyId)
            .stream().findFirst().map(r -> new RuleFacts(r.get("rules", Integer.class), Boolean.TRUE.equals(r.get("complete", Boolean.class)))));
    }
}
