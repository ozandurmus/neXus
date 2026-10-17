package com.securityexpert.nexus.ui2.persistence.policy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * Change evidence ("who changed what, when"; V163 {@code policy_change_evidence} and {@code policy_change_cursor}): the
 * redacted Panorama config-log entries and the Check Point show-changes summaries. Counts, opaque ids, an identity digest
 * and timestamps only. The cursor moves in the transaction that stores the page, so a crash re-reads the page and the
 * unique evidence identity drops the duplicates.
 */
public class PolicyChangeEvidenceRepository {
    public static final String PAN_CONFIG_LOG = "PAN_CONFIG_LOG";
    public static final String CP_SHOW_CHANGES = "CP_SHOW_CHANGES";

    /** The newest receive_time seen (verbatim device-local text) and the seqnos stored at exactly that second. */
    public record Cursor(String lastReceiveTime, List<String> boundarySeqnos) {
        public Cursor { boundarySeqnos = List.copyOf(boundarySeqnos); }
    }

    /**
     * One evidence row. {@code changeTime} is {@code yyyy-MM-dd HH:mm:ss} text bound as a timestamp; {@code timeBasis} is
     * DEVICE_LOCAL (Palo Alto receive_time) or UTC (Check Point publish time). {@code adminRef} is a 64-hex HMAC,
     * {@code masked:<length>} or UNKNOWN. {@code objectChangesJson} is a JSON array of {uid, type, op} or null.
     */
    public record Evidence(String vendor, String evidenceKind, String containerRef, String entryRef, String changeTime,
            String timeBasis, String adminRef, String commandClass, String resultClass, String pathHmac, int redactionCount,
            Integer addedCount, Integer modifiedCount, Integer deletedCount, String objectChangesJson, boolean objectChangesTruncated) {}

    private final TransactionBoundary tx;

    public PolicyChangeEvidenceRepository(TransactionBoundary tx) { this.tx = tx; }

    public Optional<Cursor> cursor(String sourceId) {
        return tx.inTransaction(db -> db.fetch("select last_receive_time, boundary_seqnos::text as seqnos from policy_change_cursor "
            + "where source_id = {0} and change_source = 'pan_config_log'", sourceId).stream().findFirst().map(r -> new Cursor(
                r.get("last_receive_time", String.class), seqnos(r.get("seqnos", String.class)))));
    }

    private static List<String> seqnos(String json) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            var out = new java.util.ArrayList<String>();
            node.forEach(item -> out.add(item.asText()));
            return out;
        } catch (java.io.IOException invalid) { return List.of(); }
    }

    /** Stores the rows (duplicates by identity are dropped) and, when given, advances the cursor, in one transaction. */
    public int save(String sourceId, String jobId, List<Evidence> rows, Optional<Cursor> newCursor) {
        return tx.inTransaction(db -> {
            int stored = 0;
            for (Evidence e : rows) {
                stored += db.execute("insert into policy_change_evidence(evidence_id, source_id, vendor, evidence_kind, container_ref, job_id, "
                    + "entry_ref, change_time, time_basis, admin_ref, command_class, result_class, path_hmac, redaction_count, added_count, "
                    + "modified_count, deleted_count, object_changes, object_changes_truncated) "
                    + "values ({0},{1},{2},{3},{4},{5},{6},{7}::timestamp,{8},{9},{10},{11},{12},{13},{14},{15},{16},{17}::jsonb,{18}) "
                    + "on conflict do nothing",
                    UUID.randomUUID().toString(), sourceId, e.vendor(), e.evidenceKind(), e.containerRef(), jobId, e.entryRef(),
                    e.changeTime(), e.timeBasis(), e.adminRef(), e.commandClass(), e.resultClass(), e.pathHmac(), e.redactionCount(),
                    e.addedCount(), e.modifiedCount(), e.deletedCount(), e.objectChangesJson(), e.objectChangesTruncated());
            }
            newCursor.ifPresent(c -> db.execute("insert into policy_change_cursor(source_id, change_source, last_receive_time, boundary_seqnos, updated_job_id) "
                + "values ({0},'pan_config_log',{1},{2}::jsonb,{3}) on conflict (source_id, change_source) do update set "
                + "last_receive_time = excluded.last_receive_time, boundary_seqnos = excluded.boundary_seqnos, "
                + "updated_job_id = excluded.updated_job_id, updated_at = now()",
                sourceId, c.lastReceiveTime(), jsonArray(c.boundarySeqnos()), jobId));
            return stored;
        });
    }

    private static String jsonArray(List<String> values) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(values); }
        catch (com.fasterxml.jackson.core.JsonProcessingException impossible) { return "[]"; }
    }
}
