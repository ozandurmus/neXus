package com.securityexpert.nexus.ui2.persistence.jobrecords;

import java.util.Optional;

/**
 * Raw {@code job_step_attempt} access (C2 §5.1). {@link
 * #insertPreContact} is committed by the caller's own transaction boundary
 * before any transport call is made -- this DAO does not itself decide
 * ordering, it only performs the write the caller asks for, in the
 * transaction the caller opened (contract §7: "before the SSH/API call").
 */
public interface JobStepAttemptDao {

    /** {@code mutation_boundary_crossed = false} always, at insert. Returns the new {@code attempt_id}. */
    String insertPreContact(String jobId, long leaseEpoch, int stepIndex, String stepKind, String actionClass,
            int attemptNumber);

    /** Flips the boundary in the same transaction as recording "the command has been sent" (C2 §5.1). */
    boolean markBoundaryCrossed(String attemptId, long leaseEpoch);

    boolean writeOutcome(String attemptId, long leaseEpoch, String outcome, String errorClass, long outputBytes,
            long outputLines, String fingerprintSha256);

    Optional<StepAttemptRow> find(String attemptId);

    /** Every attempt row for one {@code (job_id, step_index)}, in {@code attempt_number} order. */
    java.util.List<StepAttemptRow> findByJobAndStep(String jobId, int stepIndex);
}
