package com.securityexpert.nexus.ui2.persistence.policy;

import com.securityexpert.nexus.ui2.persistence.*;
import java.util.*;

/**
 * Cutover segments and pre-cutover impact assessments (V212). Writes run in an audited transaction under
 * {@code cutover_assessment_write}; audit rows carry opaque identities and revisions only. An assessment and its flow
 * rows are written in one transaction and never updated.
 */
public final class CutoverRepository {
    public static final String ACTION = "cutover_assessment_write";
    public static final int FLOW_PAGE = 100;
    private static final int INSERT_BATCH = 500;
    private final TransactionBoundary transactions;
    public CutoverRepository(TransactionBoundary transactions) { this.transactions = transactions; }

    private static final String SEGMENT_VIEW = "jsonb_build_object('segmentId', segment_id, 'name', name, 'revision', revision, "
        + "'blocks', blocks, 'peerBlocks', peer_blocks, 'createdBy', created_by, 'createdAt', created_at, 'updatedBy', updated_by, "
        + "'updatedAt', updated_at)::text as result";
    private static final String ASSESSMENT_HEAD = "jsonb_build_object('assessmentId', assessment_id, 'segmentId', segment_id, "
        + "'segmentRevision', segment_revision, 'createdAt', created_at, 'actor', actor, 'targetSource', target_source, "
        + "'targetDeviceId', target_device_id, 'topologySnapshotId', topology_snapshot_id, "
        + "'targetPolicyId', target_policy_id, 'targetCollectedAt', target_collected_at, 'oldPolicyId', old_policy_id, "
        + "'oldCollectedAt', old_collected_at, 'engineVersion', engine_version, 'status', status, 'readiness', readiness)";

    /** @return false when another segment already holds the name */
    public boolean createSegment(String id, String name, String blocks, String peers, String actor) {
        return new AuditedTransactionBoundary(transactions).inTransaction(actor, ACTION, db -> {
            if (!db.fetch("select 1 from cutover_segment where name = {0}", name).isEmpty()) return false;
            db.execute("insert into cutover_segment(segment_id, name, revision, blocks, peer_blocks, created_by, updated_by) "
                + "values ({0}, {1}, 1, {2}::jsonb, {3}::jsonb, {4}, {4})", id, name, blocks, peers, actor);
            return true;
        });
    }

    public enum UpdateOutcome { UPDATED, NOT_FOUND, REVISION_CONFLICT, NAME_TAKEN }

    public UpdateOutcome updateSegment(String id, int expectedRevision, String name, String blocks, String peers, String actor) {
        return new AuditedTransactionBoundary(transactions).inTransaction(actor, ACTION, db -> {
            var current = db.fetch("select revision from cutover_segment where segment_id = {0} for update", id);
            if (current.isEmpty()) return UpdateOutcome.NOT_FOUND;
            if (current.get(0).get("revision", Integer.class) != expectedRevision) return UpdateOutcome.REVISION_CONFLICT;
            if (!db.fetch("select 1 from cutover_segment where name = {0} and segment_id <> {1}", name, id).isEmpty()) return UpdateOutcome.NAME_TAKEN;
            db.execute("update cutover_segment set name = {1}, blocks = {2}::jsonb, peer_blocks = {3}::jsonb, revision = revision + 1, "
                + "updated_by = {4}, updated_at = now() where segment_id = {0}", id, name, blocks, peers, actor);
            return UpdateOutcome.UPDATED;
        });
    }

    public boolean deleteSegment(String id, String actor) {
        return new AuditedTransactionBoundary(transactions).inTransaction(actor, ACTION, db ->
            db.execute("delete from cutover_segment where segment_id = {0}", id) > 0);
    }

    public List<String> listSegments() {
        return transactions.inTransaction(db -> db.fetch("select " + SEGMENT_VIEW + " from cutover_segment order by name limit 500")
            .map(row -> row.get("result", String.class)));
    }

    public Optional<String> findSegment(String id) {
        return transactions.inTransaction(db -> db.fetch("select " + SEGMENT_VIEW + " from cutover_segment where segment_id = {0}", id)
            .stream().findFirst().map(row -> row.get("result", String.class)));
    }

    /** One stored assessment row; {@code rows} are JSON objects {@code {"kind","ordinal","decision","flow"}}. */
    public record Assessment(String id, String segmentId, int segmentRevision, String actor, String targetSource, String targetDeviceId,
            String topologySnapshotId, String targetPolicyId,
            String targetCollectedAt, String oldPolicyId, String oldCollectedAt, String engineVersion, String status, String readiness,
            String summary, List<String> rows) {}

    public void saveAssessment(Assessment a) {
        long bytes = PolicyJsonWrite.bytes(a.summary()) + a.rows().stream().mapToLong(PolicyJsonWrite::bytes).sum();
        PolicyJsonWrite.guarded("CutoverRepository.saveAssessment", "ASSESSMENT_INSERT", bytes, () ->
            new AuditedTransactionBoundary(transactions).inTransaction(a.actor(), ACTION, db -> {
                db.execute("insert into cutover_assessment(assessment_id, segment_id, segment_revision, actor, target_source, target_device_id, "
                    + "topology_snapshot_id, target_policy_id, target_collected_at, old_policy_id, old_collected_at, engine_version, status, readiness, summary) "
                    + "values ({0}, {1}, {2}, {3}, {13}, {4}, {14}, {5}, {6}, {7}, {8}, {9}, {10}, {11}, {12}::jsonb)",
                    a.id(), a.segmentId(), a.segmentRevision(), a.actor(), a.targetDeviceId(), a.targetPolicyId(), a.targetCollectedAt(),
                    a.oldPolicyId(), a.oldCollectedAt(), a.engineVersion(), a.status(), a.readiness(), PolicyJsonWrite.json(db, a.summary()),
                    a.targetSource(), a.topologySnapshotId());
                for (int start = 0; start < a.rows().size(); start += INSERT_BATCH) {
                    String batch = "[" + String.join(",", a.rows().subList(start, Math.min(a.rows().size(), start + INSERT_BATCH))) + "]";
                    db.execute("insert into cutover_assessment_flow(assessment_id, kind, ordinal, decision, flow) "
                        + "select {0}, x->>'kind', (x->>'ordinal')::integer, x->>'decision', x->'flow' from jsonb_array_elements({1}::jsonb) as x",
                        a.id(), PolicyJsonWrite.json(db, batch));
                }
                return null;
            }));
    }

    public List<String> listAssessments(String segmentId) {
        return transactions.inTransaction(db -> db.fetch("select (" + ASSESSMENT_HEAD + " || jsonb_build_object('headline', summary->'headline', "
            + "'counts', summary->'counts'))::text as result from cutover_assessment where segment_id = {0} "
            + "order by created_at desc, assessment_id limit 100", segmentId).map(row -> row.get("result", String.class)));
    }

    public Optional<String> findAssessment(String id) {
        return transactions.inTransaction(db -> db.fetch("select (summary || " + ASSESSMENT_HEAD + ")::text as result "
            + "from cutover_assessment where assessment_id = {0}", id).stream().findFirst().map(row -> row.get("result", String.class)));
    }

    /** One page of flow or missing-rule rows, in stored order; {@code decision} may be null for every decision. */
    public List<String> flows(String id, String kind, String decision, int page) {
        return transactions.inTransaction(db -> db.fetch("select flow::text as result from cutover_assessment_flow where assessment_id = {0} "
            + "and kind = {1} and ({2}::text is null or decision = {2}) order by ordinal limit {3} offset {4}",
            id, kind, decision, FLOW_PAGE, (long) page * FLOW_PAGE).map(row -> row.get("result", String.class)));
    }

    public long flowCount(String id, String kind, String decision) {
        return transactions.inTransaction(db -> db.fetch("select count(*) as n from cutover_assessment_flow where assessment_id = {0} "
            + "and kind = {1} and ({2}::text is null or decision = {2})", id, kind, decision).get(0).get("n", Long.class));
    }

    public boolean deleteAssessment(String id, String actor) {
        return new AuditedTransactionBoundary(transactions).inTransaction(actor, ACTION, db ->
            db.execute("delete from cutover_assessment where assessment_id = {0}", id) > 0);
    }

    /** The enrolled device's vendor hint, or empty when the device is not enrolled. */
    public Optional<String> deviceVendor(String deviceId) {
        return transactions.inTransaction(db -> db.fetch("select coalesce(vendor_hint, '') as vendor from devices where device_id = {0}", deviceId)
            .stream().findFirst().map(row -> row.get("vendor", String.class)));
    }
}
