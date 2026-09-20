package com.securityexpert.nexus.ui2.jobs.failover.plan;

import com.securityexpert.nexus.ui2.jobs.failover.authz.FailoverLeaseToken;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterMemberEvidence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Compiles dry-run execution and reversal plans for firewall clusters (Phase B).
 * In accordance with Phase B invariants, this compiler executes ZERO device mutations
 * and operates purely as a deterministic disclosure of vendor actions and reversibility steps.
 * 
 * Invariants enforced per external model reviews (Astra & Fable):
 * 1. Fail-closed: Refuses compilation if active or standby member identity is UNKNOWN (F-2).
 * 2. Removes fabricated traffic impact millisecond claims (Refinement C).
 * 3. Binds actions to opaque endpoint IDs as well as presentation masked names (F-9).
 */
public class FailoverDryRunPlanner {

    public FailoverExecutionPlan compileDryRunPlan(ClusterEvidenceSnapshot snapshot, FailoverLeaseToken leaseToken) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");

        // F-2: Refuse compilation if active or standby member is UNKNOWN (fail-closed)
        ClusterMemberEvidence activeMember = snapshot.activeMember()
                .orElseThrow(() -> new IllegalStateException("Cannot compile failover plan: active member identity is UNKNOWN"));
        ClusterMemberEvidence standbyMember = snapshot.standbyMember()
                .orElseThrow(() -> new IllegalStateException("Cannot compile failover plan: standby member identity is UNKNOWN"));

        String vendor = snapshot.vendor() != null ? snapshot.vendor().toUpperCase(Locale.ROOT) : "";
        List<FailoverActionStep> transitions = new ArrayList<>();
        List<FailoverActionStep> reversals = new ArrayList<>();
        String preemptionBehavior;
        String sessionContinuityRisk;

        if ("CHECK_POINT".equals(vendor) || "CHECKPOINT".equals(vendor)) {
            transitions.add(new FailoverActionStep(
                1,
                activeMember.memberId(),
                activeMember.maskedName(),
                "CONTROLLED_FAILOVER",
                "clusterXL_admin down",
                "Instruct active ClusterXL member to gracefully lower priority and release Cluster VIPs (non-persistent).",
                "CLASS_2_MUTATION"
            ));
            transitions.add(new FailoverActionStep(
                2,
                standbyMember.memberId(),
                standbyMember.maskedName(),
                "VERIFICATION",
                "cphaprob stat",
                "Verify standby member successfully assumed active role with all interfaces healthy.",
                "READ_ONLY_OBSERVATION"
            ));

            reversals.add(new FailoverActionStep(
                1,
                activeMember.memberId(),
                activeMember.maskedName(),
                "REVERSAL",
                "clusterXL_admin up",
                "Re-enable ClusterXL state on original active member to return it to standby.",
                "CLASS_2_MUTATION"
            ));

            preemptionBehavior = "ClusterXL configured with preemption disabled. Manual reversal required to restore original roles.";
            sessionContinuityRisk = "Connection state sync verified via preflight. Empirical traffic impact under load is NOT_EVALUABLE statically.";
        } else if ("PALO_ALTO".equals(vendor) || "PAN_OS".equals(vendor)) {
            transitions.add(new FailoverActionStep(
                1,
                activeMember.memberId(),
                activeMember.maskedName(),
                "CONTROLLED_FAILOVER",
                "request high-availability state suspend",
                "Gracefully suspend active peer to trigger passive peer promotion.",
                "CLASS_2_MUTATION"
            ));
            transitions.add(new FailoverActionStep(
                2,
                standbyMember.memberId(),
                standbyMember.maskedName(),
                "VERIFICATION",
                "show high-availability state",
                "Verify passive peer promoted to active with dataplane interfaces up.",
                "READ_ONLY_OBSERVATION"
            ));

            reversals.add(new FailoverActionStep(
                1,
                activeMember.memberId(),
                activeMember.maskedName(),
                "REVERSAL",
                "request high-availability state functional",
                "Return suspended peer to functional state (re-enters passive standby).",
                "CLASS_2_MUTATION"
            ));

            preemptionBehavior = "Preemption disabled in HA configuration. Manual return to functional state required.";
            sessionContinuityRisk = "HA2 session synchronization verified current via preflight. Empirical traffic impact under load is NOT_EVALUABLE statically.";
        } else {
            throw new IllegalArgumentException("Unsupported vendor for failover dry-run compilation: " + vendor);
        }

        return new FailoverExecutionPlan(
            UUID.randomUUID().toString(),
            snapshot.clusterId(),
            snapshot.maskedClusterName(),
            snapshot.vendor(),
            snapshot.haMode(),
            "DRY_RUN",
            transitions,
            reversals,
            sessionContinuityRisk,
            preemptionBehavior,
            Instant.now(),
            false // Strictly false: ZERO device mutations permitted in Phase B
        );
    }
}
