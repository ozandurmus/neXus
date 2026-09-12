package com.securityexpert.nexus.ui2.persistence.evidence;

/**
 * Raw {@code provenance_records} + {@code job_step_attempt} outcome
 * write, in one transaction (contract §7). Plain-typed so {@code
 * job-engine}'s {@code PersistenceEvidenceWriterPort} adapter never has to
 * import {@code org.jooq} (DIR-7).
 */
public interface EvidenceDao {

    void writeStepEvidence(
            String provenanceId, String runId, String stepId, String parserVersion, String capabilityVersion,
            String captureArtifactId, String sourceLocation, String sanitizedFragment, String fingerprintSha256,
            String attemptId, long leaseEpoch, String outcome, String errorClass, long outputBytes,
            long outputLines);
}
