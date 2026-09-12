package com.securityexpert.nexus.ui2.jobs.evidence;

/**
 * The evidence writer (contract §2 module-placement table: "port in {@code
 * .jobs.evidence}, jOOQ impl in {@code persistence}", DIR-7: domain never
 * depends on jOOQ/Flyway/JDBC directly). Persists exactly contract §7's set
 * -- a {@code provenance_records} row, the step-attempt outcome fields, and
 * (for a capability with its own projection table) the capability's own
 * projection row -- in one transaction, under the reserved worker actor's
 * audit context (adjudication F3).
 *
 * <p>Never persists the raw response beyond {@code sanitizedFragment}'s own
 * bound (contract §7 "Never persisted"): this interface's signature itself
 * has no field wide enough to carry an unbounded raw transcript -- the
 * caller has already discarded it (or never captured it beyond the parse
 * step) by the time this method is called.</p>
 */
public interface EvidenceWriterPort {

    /**
     * @param attemptId  the {@code job_step_attempt} row this outcome
     *                   belongs to (already inserted pre-contact by {@code
     *                   JobStepAttemptRepository#insertPreContact})
     * @param leaseEpoch the fencing token the attempt row was created
     *                   under -- the outcome write is fenced identically
     *                   to every other post-claim write (C2 §4.2)
     */
    void writeStepEvidence(String attemptId, long leaseEpoch, ProvenanceRecordData provenance,
            StepAttemptOutcome outcome);
}
