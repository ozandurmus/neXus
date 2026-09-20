package com.securityexpert.nexus.ui2.jobs.failover.checks;

import com.securityexpert.nexus.ui2.jobs.failover.model.CheckResult;
import com.securityexpert.nexus.ui2.jobs.failover.model.CheckStatus;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterMemberEvidence;
import com.securityexpert.nexus.ui2.jobs.failover.model.EnforcementPolicy;
import com.securityexpert.nexus.ui2.jobs.failover.spi.PreflightCheck;

import java.util.Optional;

/**
 * Verifies that a viable standby peer exists, is directly observed, and is in a healthy standby/passive state.
 */
public class ViableTargetCheck implements PreflightCheck {

    @Override
    public String id() {
        return "preflight.viable_target";
    }

    @Override
    public String name() {
        return "Viable Target Standby Peer";
    }

    @Override
    public String category() {
        return "Topology & Identity";
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
                "Direct independent observation of both cluster members failed; standby state cannot be verified.",
                "REMEDIATE_CHECK_PEER_CONNECTIVITY"
            );
        }

        Optional<ClusterMemberEvidence> standbyOpt = snapshot.standbyMember();
        if (standbyOpt.isEmpty()) {
            return CheckResult.fail(
                id(), name(), category(), defaultPolicy(),
                "No viable Standby/Passive member found in cluster " + snapshot.maskedClusterName(),
                "REMEDIATE_RESTORE_STANDBY_PEER"
            );
        }

        ClusterMemberEvidence standby = standbyOpt.get();
        String state = standby.selfState();
        if (!"STANDBY".equalsIgnoreCase(state) && !"PASSIVE".equalsIgnoreCase(state)) {
            return CheckResult.fail(
                id(), name(), category(), defaultPolicy(),
                "Target peer " + standby.maskedName() + " is in unexpected state: " + state,
                "REMEDIATE_TARGET_NOT_STANDBY"
            );
        }

        return CheckResult.pass(
            id(), name(), category(), defaultPolicy(),
            "Target standby peer " + standby.maskedName() + " is healthy, reachable, and in " + state + " state."
        );
    }
}
