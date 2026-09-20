package com.securityexpert.nexus.ui2.jobs.failover.checks;

import com.securityexpert.nexus.ui2.jobs.failover.model.CheckResult;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterMemberEvidence;
import com.securityexpert.nexus.ui2.jobs.failover.model.EnforcementPolicy;
import com.securityexpert.nexus.ui2.jobs.failover.spi.PreflightCheck;

/**
 * Validates interface link states and error counters across HA control and synchronization interfaces.
 */
public class ControlSyncLinkHealthCheck implements PreflightCheck {

    @Override
    public String id() {
        return "preflight.control_sync_link_health";
    }

    @Override
    public String name() {
        return "Control & Sync Link Health";
    }

    @Override
    public String category() {
        return "Network & Interfaces";
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
                "Interface health cannot be assessed without telemetry from both cluster nodes.",
                "REMEDIATE_CHECK_PHYSICAL_INTERFACES"
            );
        }

        ClusterMemberEvidence a = snapshot.memberA();
        ClusterMemberEvidence b = snapshot.memberB();

        if (!a.clusterInterfacesUp() || !b.clusterInterfacesUp()) {
            return CheckResult.fail(
                id(), name(), category(), defaultPolicy(),
                "One or more cluster sync/VIP interfaces are DOWN (Member A: " + (a.clusterInterfacesUp() ? "UP" : "DOWN") + ", Member B: " + (b.clusterInterfacesUp() ? "UP" : "DOWN") + ").",
                "REMEDIATE_RESTORE_CLUSTER_INTERFACES"
            );
        }

        int totalErrors = a.interfaceErrorCount() + b.interfaceErrorCount();
        if (totalErrors > 0) {
            return CheckResult.fail(
                id(), name(), category(), defaultPolicy(),
                "Active link errors detected on cluster interfaces (total errors: " + totalErrors + "). Failover risks dropped heartbeats.",
                "REMEDIATE_CLEAR_INTERFACE_ERRORS"
            );
        }

        return CheckResult.pass(
            id(), name(), category(), defaultPolicy(),
            "All cluster control, sync, and monitored virtual interfaces are UP with zero link errors."
        );
    }
}
