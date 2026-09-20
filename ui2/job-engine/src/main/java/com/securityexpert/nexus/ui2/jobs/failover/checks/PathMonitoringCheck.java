package com.securityexpert.nexus.ui2.jobs.failover.checks;

import com.securityexpert.nexus.ui2.jobs.failover.model.CheckResult;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterMemberEvidence;
import com.securityexpert.nexus.ui2.jobs.failover.model.EnforcementPolicy;
import com.securityexpert.nexus.ui2.jobs.failover.spi.PreflightCheck;

/**
 * Validates Palo Alto Networks HA Path & Link Monitoring status.
 * Ensures upstream/downstream monitored destination groups and links are fully operational.
 */
public class PathMonitoringCheck implements PreflightCheck {

    @Override
    public String id() {
        return "preflight.paloalto_path_monitoring";
    }

    @Override
    public String name() {
        return "HA Path & Link Monitoring";
    }

    @Override
    public String category() {
        return "Vendor Diagnostics";
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
                "Path monitoring telemetry unavailable without direct communication with both peers.",
                "REMEDIATE_CHECK_PAN_HA_STATUS"
            );
        }

        ClusterMemberEvidence a = snapshot.memberA();
        ClusterMemberEvidence b = snapshot.memberB();

        if (!a.pathMonitoringOk() || !b.pathMonitoringOk()) {
            int failedTotal = a.failedPathsCount() + b.failedPathsCount();
            return CheckResult.fail(
                id(), name(), category(), defaultPolicy(),
                "HA Path Monitoring failure detected (" + failedTotal + " paths/groups down). Failing over will cause route flapping.",
                "REMEDIATE_RESTORE_MONITORED_PATHS"
            );
        }

        return CheckResult.pass(
            id(), name(), category(), defaultPolicy(),
            "All Palo Alto monitored network destination groups and physical link groups are UP."
        );
    }
}
