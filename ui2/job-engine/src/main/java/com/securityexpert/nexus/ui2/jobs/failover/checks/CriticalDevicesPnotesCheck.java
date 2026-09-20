package com.securityexpert.nexus.ui2.jobs.failover.checks;

import com.securityexpert.nexus.ui2.jobs.failover.model.CheckResult;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterMemberEvidence;
import com.securityexpert.nexus.ui2.jobs.failover.model.EnforcementPolicy;
import com.securityexpert.nexus.ui2.jobs.failover.spi.PreflightCheck;

/**
 * Validates Check Point ClusterXL problem notification devices (pnotes).
 * Strictly requires all monitored critical devices (fwd, cphad, Interface Active Check, etc.) to report OK.
 */
public class CriticalDevicesPnotesCheck implements PreflightCheck {

    @Override
    public String id() {
        return "preflight.checkpoint_pnotes";
    }

    @Override
    public String name() {
        return "Critical Problem Notifications (pnotes)";
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
        return vendor != null && (vendor.equalsIgnoreCase("CHECK_POINT") || vendor.equalsIgnoreCase("CHECKPOINT"));
    }

    @Override
    public CheckResult evaluate(ClusterEvidenceSnapshot snapshot) {
        if (!appliesTo(snapshot.vendor(), snapshot.haMode())) {
            return CheckResult.pass(id(), name(), category(), EnforcementPolicy.ADVISORY, "Not applicable for vendor " + snapshot.vendor());
        }

        if (!snapshot.bothMembersDirectlyObserved()) {
            return CheckResult.insufficientEvidence(
                id(), name(), category(), defaultPolicy(),
                "Critical device telemetry unavailable without direct contact with both members.",
                "REMEDIATE_CHECK_CPHAPROB"
            );
        }

        ClusterMemberEvidence a = snapshot.memberA();
        ClusterMemberEvidence b = snapshot.memberB();

        if (!a.criticalDevicesOk() || !b.criticalDevicesOk()) {
            String nonOk = String.join(", ", a.failedCriticalDevices().isEmpty() ? b.failedCriticalDevices() : a.failedCriticalDevices());
            return CheckResult.fail(
                id(), name(), category(), defaultPolicy(),
                "Critical devices (pnotes) reporting problem state: [" + nonOk + "]. Cluster cannot safely transition.",
                "REMEDIATE_RESTORE_FAILED_PNOTES"
            );
        }

        return CheckResult.pass(
            id(), name(), category(), defaultPolicy(),
            "All Check Point critical devices (fwd, cphad, daemons, interfaces) are reporting OK across both members."
        );
    }
}
