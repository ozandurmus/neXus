package com.securityexpert.nexus.ui2.jobs.evidence;

import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.evidence.EvidenceDao;

/** {@link EvidenceWriterPort} adapter over {@code persistence}'s {@link EvidenceDao} (DIR-7: no jOOQ import here). */
public final class PersistenceEvidenceWriterPort implements EvidenceWriterPort {

    private final EvidenceDao dao;

    public PersistenceEvidenceWriterPort(EvidenceDao dao) {
        this.dao = Objects.requireNonNull(dao, "dao");
    }

    @Override
    public void writeStepEvidence(String attemptId, long leaseEpoch, ProvenanceRecordData provenance,
            StepAttemptOutcome outcome) {
        dao.writeStepEvidence(
                provenance.provenanceId(), provenance.runId(), provenance.stepId(), provenance.parserVersion(),
                provenance.capabilityVersion(), provenance.captureArtifactId().orElse(null),
                provenance.sourceLocation(), provenance.sanitizedFragment().orElse(null),
                provenance.fingerprintSha256(),
                attemptId, leaseEpoch,
                outcome.outcome(), outcome.errorClass().orElse(null), outcome.outputBytes(), outcome.outputLines());
    }
}
