package com.securityexpert.nexus.ui2.persistence.pathvalidation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Path analysis stage A6 (V188): validation requests and their hops. A hop row holds only MATCH / MISMATCH /
 * NOT_EVALUABLE, per-check enums, reason codes and opaque references; its plan (the matcher parameters) exists only
 * while it is {@code PENDING} and is cleared by every statement that ends it.
 */
public interface PathValidationRepository {

    String PENDING = "PENDING";
    String NOT_EVALUABLE = "NOT_EVALUABLE";

    /** One hop to store: {@code planJson} present (and {@code result} PENDING) only when a job will be admitted. */
    record NewHop(int ordinal, String branchId, int hopIndex, String nodeId, String vendor, Optional<String> deviceId,
            Optional<String> planJson, String result, List<String> reasons) {
        public NewHop {
            reasons = List.copyOf(reasons);
        }
    }

    record Hop(int ordinal, String branchId, int hopIndex, String nodeId, String vendor, Optional<String> deviceId,
            Optional<String> jobId, Optional<String> jobState, String result, String checksJson, List<String> reasons,
            Optional<Instant> evaluatedAt) {
        public Hop {
            reasons = List.copyOf(reasons);
        }
    }

    record Validation(String validationId, String submittedBy, Instant submittedAt, String topologySnapshotId,
            String pathVerdict, int hopsNotValidated, List<Hop> hops) {
        public Validation {
            hops = List.copyOf(hops);
        }
    }

    /** What the worker needs for one claimed job: the hop and its plan. */
    record Pending(String validationId, int ordinal, String deviceId, String planJson) {
        @Override
        public String toString() {
            return "Pending[" + validationId + "#" + ordinal + ", plan redacted]";
        }
    }

    void create(String validationId, String actor, String topologySnapshotId, String pathVerdict, int hopsNotValidated,
            List<NewHop> hops);

    /** Binds the admitted job to its PENDING hop; {@code false} when the hop is not pending (nothing changed). */
    boolean attachJob(String validationId, int ordinal, String jobId);

    /** A hop whose job could not be admitted: NOT_EVALUABLE with the reason, plan cleared. */
    void abandon(String validationId, int ordinal, String reason);

    Optional<Pending> pendingForJob(String jobId);

    /**
     * The worker's outcome, fenced to the job's current lease (state EXECUTING, owner valid); the plan is cleared in the
     * same statement. {@code false}: nothing was written.
     */
    boolean recordOutcome(String jobId, long leaseEpoch, String result, String checksJson, List<String> reasons);

    /** A job the worker refused before any contact: the hop becomes NOT_EVALUABLE with the reason, plan cleared. */
    boolean abandonJob(String jobId, String reason);

    Optional<Validation> find(String validationId);
}
