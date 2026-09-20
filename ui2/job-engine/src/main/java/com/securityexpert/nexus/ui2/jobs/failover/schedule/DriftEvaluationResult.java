package com.securityexpert.nexus.ui2.jobs.failover.schedule;

import com.securityexpert.nexus.ui2.jobs.failover.model.PreflightReport;
import com.securityexpert.nexus.ui2.jobs.failover.model.PreflightVerdict;

import java.util.*;

/**
 * Closed result of the T₀ JIT drift evaluation.
 * Only a status of PASS may proceed to execution.
 */
public record DriftEvaluationResult(
    DriftStatus status,
    Map<String, DriftDimensionStatus> dimensions,
    List<String> typedReasonCodes,
    List<String> sanitizedMessages,
    PreflightReport t0Report
) {
    public enum DriftStatus {
        PASS,
        DRIFT,
        BLOCKED,
        INSUFFICIENT_EVIDENCE,
        COLLECTION_FAILED
    }

    public DriftEvaluationResult {
        Objects.requireNonNull(status, "status must not be null");
        dimensions = Map.copyOf(dimensions);
        typedReasonCodes = List.copyOf(typedReasonCodes);
        sanitizedMessages = List.copyOf(sanitizedMessages);
        Objects.requireNonNull(t0Report, "t0Report must not be null");
    }

    public boolean isPass() {
        return status == DriftStatus.PASS &&
            t0Report.overallVerdict() == PreflightVerdict.NO_BLOCKING_CONDITIONS_OBSERVED;
    }

    public static DriftEvaluationResult pass(Map<String, DriftDimensionStatus> dimensions, PreflightReport t0Report) {
        return new DriftEvaluationResult(
            DriftStatus.PASS,
            dimensions,
            List.of(),
            List.of("Zero drift detected between authorized baseline and T₀ JIT evidence"),
            t0Report
        );
    }

    public static DriftEvaluationResult drift(
        Map<String, DriftDimensionStatus> dimensions,
        List<String> typedReasonCodes,
        List<String> sanitizedMessages,
        PreflightReport t0Report
    ) {
        return new DriftEvaluationResult(
            DriftStatus.DRIFT,
            dimensions,
            typedReasonCodes,
            sanitizedMessages,
            t0Report
        );
    }

    public static DriftEvaluationResult blocked(
        Map<String, DriftDimensionStatus> dimensions,
        List<String> typedReasonCodes,
        List<String> sanitizedMessages,
        PreflightReport t0Report
    ) {
        return new DriftEvaluationResult(
            DriftStatus.BLOCKED,
            dimensions,
            typedReasonCodes,
            sanitizedMessages,
            t0Report
        );
    }

    public static DriftEvaluationResult insufficientEvidence(
        Map<String, DriftDimensionStatus> dimensions,
        String reasonCode,
        String sanitizedMessage,
        PreflightReport t0Report
    ) {
        return new DriftEvaluationResult(
            DriftStatus.INSUFFICIENT_EVIDENCE,
            dimensions,
            List.of(reasonCode),
            List.of(sanitizedMessage),
            t0Report
        );
    }
}
