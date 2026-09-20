package com.securityexpert.nexus.ui2.jobs.failover.checks;

import com.securityexpert.nexus.ui2.jobs.failover.model.CheckResult;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterMemberEvidence;
import com.securityexpert.nexus.ui2.jobs.failover.model.EnforcementPolicy;
import com.securityexpert.nexus.ui2.jobs.failover.spi.PreflightCheck;

/**
 * Validates cluster state by independently querying both members in the same collection window.
 * Strictly detects split-brain conditions where both nodes independently declare themselves ACTIVE.
 */
public class TwoSidedSplitBrainCheck implements PreflightCheck {

    @Override
    public String id() {
        return "preflight.split_brain_prevention";
    }

    @Override
    public String name() {
        return "Two-Sided Split-Brain Prevention";
    }

    @Override
    public String category() {
        return "Cluster Health";
    }

    @Override
    public EnforcementPolicy defaultPolicy() {
        return EnforcementPolicy.BLOCKING;
    }

    @Override
    public boolean appliesTo(String vendor, String haMode) {
        return true;
    }

    @Override
    public CheckResult evaluate(ClusterEvidenceSnapshot snapshot) {
        if (!snapshot.bothMembersDirectlyObserved()) {
            return CheckResult.insufficientEvidence(
                id(), name(), category(), defaultPolicy(),
                "Two-sided independent observation incomplete. Cannot rule out split-brain without direct reachability to both peers.",
                "REMEDIATE_INSPECT_CLUSTER_HEARTBEAT"
            );
        }

        ClusterMemberEvidence a = snapshot.memberA();
        ClusterMemberEvidence b = snapshot.memberB();

        boolean aActive = "ACTIVE".equalsIgnoreCase(a.selfState());
        boolean bActive = "ACTIVE".equalsIgnoreCase(b.selfState());

        if (aActive && bActive) {
            return CheckResult.fail(
                id(), name(), category(), defaultPolicy(),
                "CRITICAL SPLIT-BRAIN DETECTED: Both " + a.maskedName() + " and " + b.maskedName() + " report state ACTIVE.",
                "REMEDIATE_RESOLVE_SPLIT_BRAIN_IMMEDIATELY"
            );
        }

        if (!aActive && !bActive) {
            return CheckResult.fail(
                id(), name(), category(), defaultPolicy(),
                "NO ACTIVE MEMBER FOUND: Neither member reports state ACTIVE (Member A: " + a.selfState() + ", Member B: " + b.selfState() + ").",
                "REMEDIATE_INVESTIGATE_CLUSTER_FAILURE"
            );
        }

        boolean aStandby = "STANDBY".equalsIgnoreCase(a.selfState()) || "PASSIVE".equalsIgnoreCase(a.selfState());
        boolean bStandby = "STANDBY".equalsIgnoreCase(b.selfState()) || "PASSIVE".equalsIgnoreCase(b.selfState());

        if ((aActive && !bStandby) || (bActive && !aStandby)) {
            String nonStandby = aActive ? b.maskedName() + " (" + b.selfState() + ")" : a.maskedName() + " (" + a.selfState() + ")";
            return CheckResult.fail(
                id(), name(), category(), defaultPolicy(),
                "INCONSISTENT CLUSTER STATE: Expected exactly one Active and one Standby/Passive member, but peer is in non-viable state: " + nonStandby,
                "REMEDIATE_INCONSISTENT_CLUSTER_STATE"
            );
        }

        return CheckResult.pass(
            id(), name(), category(), defaultPolicy(),
            "Split-brain ruled out via two-sided observation: exactly one active member (" + (aActive ? a.maskedName() : b.maskedName()) + ") and one viable standby member."
        );
    }
}
