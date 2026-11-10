package com.securityexpert.nexus.ui2.persistence.diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * Read-only source of the estate names the masked system-log view adds to its dictionary ({@code logmask-v5},
 * docs/design/LOG_MASKING_RISK_MEASUREMENT_2026_11_10.md §5): administrator names from policy history, virtual-system,
 * zone and installed-policy names from the device tables, MDS domain and server names, the Check Point object
 * inventory, and the stored policy snapshots.
 *
 * <p>Policy snapshots are large (the biggest is about 11 MB compressed): they are listed by id and version first and
 * then read one row at a time, so the caller re-reads only those that changed and the walk over the document runs in
 * Java. No recursive or set-returning JSON function runs in the database (a recursive key-path walk over
 * {@code policy_snapshot} OOM-killed the database pod on 2026-11-10). Nothing read here is logged.</p>
 */
public final class JooqEstateNameRepository {

    /** One name read from a flat column, with the class the column carries. */
    public record FlatName(Category category, String value) {
        @Override
        public String toString() {
            return "FlatName[" + category + "]";
        }
    }

    /** What a flat column holds. */
    public enum Category { USER, VSYS, ZONE, POLICY, DOMAIN, HOST }

    /** The metadata and snapshot documents of one stored policy snapshot. */
    public record PolicyDocuments(String metadataJson, String snapshotJson) {
        @Override
        public String toString() {
            return "PolicyDocuments[redacted]";
        }
    }

    /** Same version expression as {@code PolicySnapshotRepository}: collected_at and the stored size. */
    private static final String VERSION = "to_char(collected_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US') "
            + "|| '|' || pg_column_size(snapshot)::text";

    private static final String[][] FLAT = {
        {"USER", "select distinct changed_by as v from policy_rule_history where changed_by is not null"},
        {"VSYS", "select distinct context as v from device_interface where context is not null"},
        {"VSYS", "select distinct routing_context as v from device_interface where routing_context is not null"},
        {"ZONE", "select distinct zone as v from device_interface where zone is not null"},
        {"POLICY", "select distinct policy_name as v from device_policy_install where policy_name is not null"},
        {"DOMAIN", "select distinct domain_name as v from cp_domain_server_placement where domain_name is not null"},
        {"HOST", "select distinct server_name as v from cp_domain_server_placement where server_name is not null"},
        {"HOST", "select distinct multi_domain_server as v from cp_domain_server_placement where multi_domain_server is not null"},
    };

    private final TransactionBoundary transactions;

    public JooqEstateNameRepository(TransactionBoundary transactions) {
        this.transactions = transactions;
    }

    /** The distinct values of the flat name columns (small: one row per distinct name). */
    public List<FlatName> flatNames() {
        return transactions.inTransaction(db -> {
            List<FlatName> out = new ArrayList<>();
            for (String[] q : FLAT) {
                Category category = Category.valueOf(q[0]);
                db.fetch(q[1]).forEach(row -> out.add(new FlatName(category, row.get("v", String.class))));
            }
            return out;
        });
    }

    /** The Check Point object inventory documents (small rows: one per source, domain and object type). */
    public List<String> objectInventoryDocuments() {
        return transactions.inTransaction(db -> db.fetch("select snapshot::text as s from cp_policy_object_inventory")
                .map(row -> row.get("s", String.class)));
    }

    /** Every stored policy snapshot's id and version, without reading a document (the size is the TOAST pointer's). */
    public Map<String, String> policySnapshotVersions() {
        return transactions.inTransaction(db -> {
            Map<String, String> out = new LinkedHashMap<>();
            db.fetch("select policy_id, " + VERSION + " as version from policy_snapshot order by policy_id")
                    .forEach(row -> out.put(row.get("policy_id", String.class), row.get("version", String.class)));
            return out;
        });
    }

    /** One policy snapshot's documents, read alone. */
    public Optional<PolicyDocuments> policySnapshot(String policyId) {
        return transactions.inTransaction(db -> db.fetch(
                "select metadata::text as m, snapshot::text as s from policy_snapshot where policy_id = {0}", policyId)
                .stream().findFirst().map(row -> new PolicyDocuments(row.get("m", String.class), row.get("s", String.class))));
    }
}
