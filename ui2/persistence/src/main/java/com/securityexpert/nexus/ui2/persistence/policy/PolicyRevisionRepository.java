package com.securityexpert.nexus.ui2.persistence.policy;

import java.time.Instant;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * The revision record of policy collection (V163 {@code policy_revision}; plan section 9): one row per collection per
 * container, CHANGED, UNCHANGED or UNKNOWN against the previous marker. Parsed, opaque values only: a marker token, counts
 * and opaque container references. A NULL count means UNKNOWN, never zero. Like {@code policy_snapshot} and
 * {@code cp_policy_domain_run}, these rows are collected evidence written by the policy job; the job row is the audited
 * admission path.
 */
public class PolicyRevisionRepository {
    /** The newest earlier row of a container. */
    public record Latest(int revisionNo, String markerKind, String markerJson) {}

    /** @param markerJson the opaque marker object as JSON, or {@code "{}"} when there is none. */
    public record Revision(String sourceId, String vendor, String containerKind, String containerRef, String jobId,
            int revisionNo, String changeState, String unknownReason, String markerKind, String markerJson, boolean complete,
            Integer rulesTotal, Integer natRules, Integer rulesAdded, Integer rulesModified, Integer rulesRemoved,
            Integer objectsAdded, Integer objectsModified, Integer objectsRemoved) {}

    /** Rule revisions the existing {@code policy_rule_history} trigger recorded for a container; initial captures excluded. */
    public record RuleChanges(int added, int modified, int removed) {}

    private final TransactionBoundary tx;

    public PolicyRevisionRepository(TransactionBoundary tx) { this.tx = tx; }

    /** The newest row of the container written by a job other than {@code excludingJobId}. */
    public Optional<Latest> latest(String sourceId, String containerRef, String excludingJobId) {
        return tx.inTransaction(db -> db.fetch("select revision_no, marker_kind, marker::text as marker from policy_revision "
            + "where source_id = {0} and container_ref = {1} and job_id <> {2} order by observed_at desc, revision_id desc limit 1",
            sourceId, containerRef, excludingJobId).stream().findFirst().map(r -> new Latest(
                r.get("revision_no", Integer.class), r.get("marker_kind", String.class), r.get("marker", String.class))));
    }

    /** Idempotent per (job, container): a resumed job writes the same row once. */
    public boolean record(Revision r) {
        return tx.inTransaction(db -> db.execute("insert into policy_revision(source_id, vendor, container_kind, container_ref, job_id, "
            + "revision_no, change_state, unknown_reason, marker_kind, marker, complete, rules_total, nat_rules, rules_added, rules_modified, "
            + "rules_removed, objects_added, objects_modified, objects_removed) "
            + "values ({0},{1},{2},{3},{4},{5},{6},{7},{8},{9}::jsonb,{10},{11},{12},{13},{14},{15},{16},{17},{18}) "
            + "on conflict on constraint policy_revision_job_container do nothing",
            r.sourceId(), r.vendor(), r.containerKind(), r.containerRef(), r.jobId(), r.revisionNo(), r.changeState(),
            r.unknownReason(), r.markerKind(), r.markerJson(), r.complete(), r.rulesTotal(), r.natRules(), r.rulesAdded(),
            r.rulesModified(), r.rulesRemoved(), r.objectsAdded(), r.objectsModified(), r.objectsRemoved()) == 1);
    }

    /** Added/modified/removed rule revisions of the container recorded since {@code since} (the job's start). */
    public RuleChanges ruleChanges(String sourceId, String containerRef, Instant since) {
        return tx.inTransaction(db -> {
            int added = 0, modified = 0, removed = 0;
            for (var row : db.fetch("select h.change_type, count(*) as n from policy_rule_history h "
                + "join policy_snapshot s on s.policy_id = h.policy_id "
                + "where s.metadata->>'sourceId' = {0} and s.metadata->>'containerId' = {1} "
                + "and not h.initial_capture and h.collected_at >= {2}::timestamptz group by h.change_type",
                sourceId, containerRef, since.toString())) {
                int n = row.get("n", Integer.class);
                switch (row.get("change_type", String.class)) {
                    case "added" -> added = n;
                    case "modified" -> modified = n;
                    case "removed" -> removed = n;
                    default -> { }
                }
            }
            return new RuleChanges(added, modified, removed);
        });
    }

    /** Links each revision of the job to the evidence rows the readers wrote for the same container in the same job. */
    public int refreshEvidenceCounts(String jobId) {
        return tx.inTransaction(db -> db.execute("update policy_revision r set evidence_count = "
            + "(select count(*) from policy_change_evidence e where e.job_id = r.job_id and e.container_ref = r.container_ref) "
            + "where r.job_id = {0}", jobId));
    }

}
