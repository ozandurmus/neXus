package com.securityexpert.nexus.ui2.persistence.evidence;

import java.time.Instant;
import java.sql.Timestamp;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * jOOQ-backed {@link EvidenceDao}. Writes the {@code provenance_records}
 * row and the {@code job_step_attempt} outcome fields in one transaction
 * (contract §7), under the reserved worker actor's audit context (F3).
 * Never receives a raw-response parameter -- there is no field on this
 * interface wide enough to carry one (contract §7 "Never persisted").
 */
public final class JooqEvidenceDao implements EvidenceDao {

    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqEvidenceDao(TransactionBoundary transactionBoundary) {
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public void writeStepEvidence(String provenanceId, String runId, String stepId, String parserVersion,
            String capabilityVersion, String captureArtifactId, String sourceLocation, String sanitizedFragment,
            String fingerprintSha256, String attemptId, long leaseEpoch, String outcome, String errorClass,
            long outputBytes, long outputLines) {
        auditedTransactionBoundary.inTransaction("system:worker", "job_step_evidence_write", dsl -> {
            dsl.execute("insert into provenance_records(provenance_id, run_id, step_id, parser_version, "
                    + "capability_version, capture_artifact_id, source_location, sanitized_fragment, "
                    + "fingerprint_sha256, collected_at) "
                    + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9})",
                    provenanceId, runId, stepId, parserVersion, capabilityVersion, captureArtifactId, sourceLocation,
                    sanitizedFragment, fingerprintSha256, Timestamp.from(Instant.now()));
            dsl.execute("update job_step_attempt set outcome = {0}, error_class = {1}, output_bytes = {2}, "
                    + "output_lines = {3}, fingerprint_sha256 = {4} "
                    + "where attempt_id = {5} and lease_epoch = {6}",
                    outcome, errorClass, outputBytes, outputLines, fingerprintSha256, attemptId, leaseEpoch);
            return null;
        });
    }
}
