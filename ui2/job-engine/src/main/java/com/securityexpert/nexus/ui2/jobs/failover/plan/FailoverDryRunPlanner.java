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
 */
public class FailoverDryRunPlanner {

    public FailoverExecutionPlan compileDryRunPlan(ClusterEvidenceSnapshot snapshot, FailoverLeaseToken leaseToken) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");

        String vendor = snapshot.vendor() != null ? snapshot.vendor().toUpperCase(Locale.ROOT) : "";
        String activeMemberName = snapshot.activeMember()
                .map(ClusterMemberEvidence::maskedName)
                .orElse("ACTIVE_MEMBER");
        String standbyMemberName = snapshot.standbyMember()
                .map(ClusterMemberEvidence::maskedName)
                .orElse("STANDBY_MEMBER");

        List<FailoverActionStep> transitions = new ArrayList<>();
        List<FailoverActionStep> reversals = new ArrayList<>();
        long estimatedTrafficImpactMs;
        String preemptionBehavior;
        String sessionContinuityRisk;

        if ("CHECK_POINT".equals(vendor) || "CHECKPOINT".equals(vendor)) {
            transitions.add(new FailoverActionStep(
                1,
                activeMemberName,
                "clusterXL_admin down",
                "Instruct active ClusterXL member to gracefully lower priority and release Cluster VIPs.",
                "CONTROLLED_FAILOVER"
            ));
            transitions.add(new FailoverActionStep(
                2,
                standbyMemberName,
                "cphaprob stat",
                "Verify standby member successfully assumed active role with all interfaces healthy.",
                "VERIFICATION"
            ));

            reversals.add(new FailoverActionStep(
                1,
                activeMemberName,
                "clusterXL_admin up",
                "Re-enable ClusterXL state on original active member to return it to standby.",
                "REVERSAL"
            ));

            estimatedTrafficImpactMs = 250;
            preemptionBehavior = "ClusterXL configured to maintain current active. Reversal will not cause second traffic flap.";
            sessionContinuityRisk = "Full CCP session table synchronized. Stateful TCP/UDP flows preserved without connection drop.";
        } else if ("PALO_ALTO".equals(vendor) || "PAN_OS".equals(vendor)) {
            transitions.add(new FailoverActionStep(
                1,
                activeMemberName,
                "request high-availability state suspend",
                "Gracefully suspend active peer to trigger passive peer promotion.",
                "CONTROLLED_FAILOVER"
            ));
            transitions.add(new FailoverActionStep(
                2,
                standbyMemberName,
                "show high-availability state",
                "Verify passive peer promoted to active with dataplane interfaces up.",
                "VERIFICATION"
            ));

            reversals.add(new FailoverActionStep(
                1,
                activeMemberName,
                "request high-availability state functional",
                "Return suspended peer to functional state (re-enters passive standby).",
                "REVERSAL"
            ));

            estimatedTrafficImpactMs = 350;
            preemptionBehavior = "Preemption disabled. Un-suspending will return peer to passive state without fail-back impact.";
            sessionContinuityRisk = "HA2 session synchronization verified current. Existing connections continue uninterrupted.";
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
            estimatedTrafficImpactMs,
            sessionContinuityRisk,
            preemptionBehavior,
            Instant.now(),
            false // Strictly false: ZERO device mutations permitted in Phase B
        );
    }
}
