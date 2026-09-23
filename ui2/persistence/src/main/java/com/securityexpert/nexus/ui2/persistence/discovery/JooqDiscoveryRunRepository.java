package com.securityexpert.nexus.ui2.persistence.discovery;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** jOOQ-backed {@link DiscoveryRunRepository}, in the same raw-SQL-plus-record-mapping style as {@code JooqDeviceRepository}. */
public final class JooqDiscoveryRunRepository implements DiscoveryRunRepository {

    private static final int MAX_RUN_AGE_HOURS = 24;

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqDiscoveryRunRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = transactionBoundary;
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public void createRun(String runId, String vendor, String managementAddress, String credentialReferenceId,
            String requestedByActorFingerprint, String actorFingerprint, String actionId) {
        auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "insert into discovery_run(run_id, vendor, management_address, credential_reference_id, "
                        + "requested_by_actor_fingerprint, state, created_at) "
                        + "values ({0}, {1}, {2}, {3}, {4}, 'REQUESTED', {5})",
                runId, vendor, managementAddress, credentialReferenceId, requestedByActorFingerprint,
                Timestamp.from(Instant.now())));
    }

    @Override
    public boolean setJobId(String runId, String jobId, String actorFingerprint, String actionId) {
        int updated = auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "update discovery_run set job_id = {0} where run_id = {1} and job_id is null", jobId, runId));
        return updated == 1;
    }

    @Override
    public Optional<DiscoveryRun> findRun(String runId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select * from discovery_run where run_id = {0}", runId)
                .stream().findFirst().map(JooqDiscoveryRunRepository::toRun));
    }

    @Override
    public boolean markRunning(String runId, String actorFingerprint, String actionId) {
        int updated = auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "update discovery_run set state = 'RUNNING', started_at = {0} "
                        + "where run_id = {1} and state = 'REQUESTED'",
                Timestamp.from(Instant.now()), runId));
        return updated == 1;
    }

    @Override
    public boolean markFinished(String runId, Map<String, Integer> outcomeSummary, String actorFingerprint,
            String actionId) {
        int updated = auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "update discovery_run set state = 'FINISHED', finished_at = {0}, outcome_summary = {1}::jsonb "
                        + "where run_id = {2} and state = 'RUNNING'",
                Timestamp.from(Instant.now()), OutcomeSummaryJson.toJson(outcomeSummary), runId));
        return updated == 1;
    }

    @Override
    public boolean markFailed(String runId, String reasonClass, String actorFingerprint, String actionId) {
        int updated = auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "update discovery_run set state = 'FAILED', finished_at = {0}, "
                        + "outcome_summary = {1}::jsonb where run_id = {2} and state in ('REQUESTED', 'RUNNING')",
                Timestamp.from(Instant.now()),
                OutcomeSummaryJson.toJson(Map.of("failure_reason_class:" + reasonClass, 1)), runId));
        return updated == 1;
    }

    @Override
    public void replaceCandidates(String runId, List<DiscoveryCandidateRecord> candidates, String actorFingerprint,
            String actionId) {
        auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            dsl.execute("delete from discovery_candidate where run_id = {0}", runId);
            if (candidates.isEmpty()) {
                return null;
            }
            List<Object> bindings = new ArrayList<>();
            StringBuilder valuesClause = new StringBuilder();
            int placeholder = 0;
            for (int i = 0; i < candidates.size(); i++) {
                DiscoveryCandidateRecord c = candidates.get(i);
                if (i > 0) {
                    valuesClause.append(',');
                }
                valuesClause.append('(');
                for (int col = 0; col < 16; col++) {
                    if (col > 0) {
                        valuesClause.append(',');
                    }
                    valuesClause.append('{').append(placeholder++).append('}');
                }
                valuesClause.append(')');
                bindings.add(c.candidateId());
                bindings.add(c.runId());
                bindings.add(c.vendor());
                bindings.add(c.stableIdentifier());
                bindings.add(c.owningDomain().orElse(null));
                bindings.add(c.kind());
                bindings.add(c.displayName());
                bindings.add(c.ownAddress().orElse(null));
                bindings.add(c.managementAddress().orElse(null));
                bindings.add(c.clusterReference().orElse(null));
                bindings.add(c.parentCandidateId().orElse(null));
                bindings.add(c.model().orElse(null));
                bindings.add(c.softwareVersion().orElse(null));
                bindings.add(c.connectionState().orElse(null));
                bindings.add(c.importable());
                bindings.add(c.importOutcome().orElse(null));
            }
            String sql = "insert into discovery_candidate(candidate_id, run_id, vendor, stable_identifier, "
                    + "owning_domain, kind, display_name, own_address, management_address, cluster_reference, "
                    + "parent_candidate_id, model, software_version, connection_state, importable, import_outcome) "
                    + "values " + valuesClause;
            return dsl.execute(sql, bindings.toArray());
        });
    }

    @Override
    public List<DiscoveryCandidateRecord> listCandidates(String runId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select * from discovery_candidate where run_id = {0} order by candidate_id", runId)
                .stream().map(JooqDiscoveryRunRepository::toCandidate).toList());
    }

    @Override
    public Optional<DiscoveryCandidateRecord> findCandidate(String candidateId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select * from discovery_candidate where candidate_id = {0}", candidateId)
                .stream().findFirst().map(JooqDiscoveryRunRepository::toCandidate));
    }

    @Override
    public boolean markImportOutcome(String candidateId, String importOutcome, String actorFingerprint,
            String actionId) {
        int updated = auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "update discovery_candidate set import_outcome = {0} where candidate_id = {1}",
                importOutcome, candidateId));
        return updated == 1;
    }

    @Override
    public int sweepExpired(Instant now, String actorFingerprint, String actionId) {
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                // The latest FINISHED run per management server is kept past the window: the managed-estate tree reads it,
                // and a failed nightly refresh must not leave that tree empty (PO, 2026-09-23; amends 14F DR-3).
                "delete from discovery_run where finished_at is not null and finished_at < {0} and run_id not in ("
                        + "select distinct on (vendor, management_address) run_id from discovery_run where state = 'FINISHED' "
                        + "order by vendor, management_address, finished_at desc)",
                Timestamp.from(now.minus(java.time.Duration.ofHours(MAX_RUN_AGE_HOURS)))));
    }

    private static DiscoveryRun toRun(Record row) {
        String outcomeSummaryJson = row.get("outcome_summary", String.class);
        return new DiscoveryRun(
                row.get("run_id", String.class),
                row.get("vendor", String.class),
                row.get("management_address", String.class),
                row.get("credential_reference_id", String.class),
                row.get("requested_by_actor_fingerprint", String.class),
                DiscoveryRunState.valueOf(row.get("state", String.class)),
                Optional.ofNullable(row.get("job_id", String.class)),
                Optional.ofNullable(row.get("started_at", java.sql.Timestamp.class)).map(java.sql.Timestamp::toInstant),
                Optional.ofNullable(row.get("finished_at", java.sql.Timestamp.class)).map(java.sql.Timestamp::toInstant),
                Optional.ofNullable(outcomeSummaryJson).map(OutcomeSummaryJson::fromJson));
    }

    private static DiscoveryCandidateRecord toCandidate(Record row) {
        return new DiscoveryCandidateRecord(
                row.get("candidate_id", String.class),
                row.get("run_id", String.class),
                row.get("vendor", String.class),
                row.get("stable_identifier", String.class),
                Optional.ofNullable(row.get("owning_domain", String.class)),
                row.get("kind", String.class),
                row.get("display_name", String.class),
                Optional.ofNullable(row.get("own_address", String.class)),
                Optional.ofNullable(row.get("management_address", String.class)),
                Optional.ofNullable(row.get("cluster_reference", String.class)),
                Optional.ofNullable(row.get("parent_candidate_id", String.class)),
                Optional.ofNullable(row.get("model", String.class)),
                Optional.ofNullable(row.get("software_version", String.class)),
                Optional.ofNullable(row.get("connection_state", String.class)),
                Boolean.TRUE.equals(row.get("importable", Boolean.class)),
                Optional.ofNullable(row.get("import_outcome", String.class)));
    }
}
