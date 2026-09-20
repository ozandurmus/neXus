package com.securityexpert.nexus.ui2.jobs.failover.checks;

import com.securityexpert.nexus.ui2.jobs.failover.model.CheckResult;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterMemberEvidence;
import com.securityexpert.nexus.ui2.jobs.failover.model.EnforcementPolicy;
import com.securityexpert.nexus.ui2.jobs.failover.spi.PreflightCheck;

/**
 * Validates that no uncommitted configuration changes or in-progress commits exist
 * on either cluster peer before failover.
 */
public class PendingCommitCheck implements PreflightCheck {

    @Override
    public String id() {
        return "preflight.paloalto_pending_commits";
    }

    @Override
    public String name() {
        return "Pending / In-Flight Commits";
    }

    @Override
    public String category() {
        return "Configuration & Alignment";
    }

    @Override
    public EnforcementPolicy defaultPolicy() {
        return EnforcementPolicy.BLOCKING;
    }

    @Override
    public boolean appliesTo(String vendor, String haMode) {
        return vendor != null && (vendor.equalsIgnoreCase("PALO_ALTO") || vendor.equalsIgnoreCase("PAN_OS"));
    }

    @Override
    public CheckResult evaluate(ClusterEvidenceSnapshot snapshot) {
        if (!appliesTo(snapshot.vendor(), snapshot.haMode())) {
            return CheckResult.pass(id(), name(), category(), EnforcementPolicy.ADVISORY, "Not applicable for vendor " + snapshot.vendor());
        }

        if (!snapshot.bothMembersDirectlyObserved()) {
            return CheckResult.insufficientEvidence(
                id(), name(), category(), defaultPolicy(),
                "Cannot verify commit status without direct telemetry from both peers.",
                "REMEDIATE_CHECK_COMMIT_STATUS"
            );
        }

        ClusterMemberEvidence a = snapshot.memberA();
        ClusterMemberEvidence b = snapshot.memberB();

        if (a.pendingCommit() || b.pendingCommit()) {
            return CheckResult.fail(
                id(), name(), category(), defaultPolicy(),
                "PENDING OR IN-FLIGHT COMMITS DETECTED: Configuration commit is in progress or pending. Failover must not occur during policy installation.",
                "REMEDIATE_COMPLETE_OR_REVERT_COMMITS"
            );
        }

        return CheckResult.pass(
            id(), name(), category(), defaultPolicy(),
            "No pending or in-flight commits detected across cluster peers."
        );
    }
}
