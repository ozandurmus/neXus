package com.securityexpert.nexus.ui2.jobs.failover.plan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Compiled execution and reversal plan for controlled firewall failover.
 * In Phase B, planType is strictly DRY_RUN and mutationAuthorized is strictly false.
 * Per architectural review (Astra & Fable), fabricated traffic impact milliseconds have been removed.
 */
public record FailoverExecutionPlan(
    String planId,
    String clusterId,
    String maskedClusterName,
    String vendor,
    String haMode,
    String planType,
    List<FailoverActionStep> transitionSteps,
    List<FailoverActionStep> reversalSteps,
    String sessionContinuityRisk,
    String preemptionBehavior,
    Instant compiledAt,
    boolean mutationAuthorized
) {
    public FailoverExecutionPlan {
        Objects.requireNonNull(planId, "planId must not be null");
        Objects.requireNonNull(clusterId, "clusterId must not be null");
        Objects.requireNonNull(maskedClusterName, "maskedClusterName must not be null");
        Objects.requireNonNull(vendor, "vendor must not be null");
        Objects.requireNonNull(haMode, "haMode must not be null");
        Objects.requireNonNull(planType, "planType must not be null");
        transitionSteps = List.copyOf(Objects.requireNonNull(transitionSteps, "transitionSteps must not be null"));
        reversalSteps = List.copyOf(Objects.requireNonNull(reversalSteps, "reversalSteps must not be null"));
        Objects.requireNonNull(sessionContinuityRisk, "sessionContinuityRisk must not be null");
        Objects.requireNonNull(preemptionBehavior, "preemptionBehavior must not be null");
        Objects.requireNonNull(compiledAt, "compiledAt must not be null");
    }
}
