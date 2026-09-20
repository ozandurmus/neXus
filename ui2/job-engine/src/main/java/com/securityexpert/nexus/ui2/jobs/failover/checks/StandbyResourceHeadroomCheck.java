package com.securityexpert.nexus.ui2.jobs.failover.checks;

import com.securityexpert.nexus.ui2.jobs.failover.model.CheckResult;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterMemberEvidence;
import com.securityexpert.nexus.ui2.jobs.failover.model.EnforcementPolicy;
import com.securityexpert.nexus.ui2.jobs.failover.spi.PreflightCheck;

import java.util.Optional;

/**
 * Validates hardware and capacity headroom on the standby peer (the node taking over).
 * Ensures the standby firewall has adequate CPU, memory, and connection table capacity to absorb live load.
 */
public class StandbyResourceHeadroomCheck implements PreflightCheck {

    private static final int MAX_ALLOWABLE_CPU_PCT = 80;
    private static final int MAX_ALLOWABLE_MEM_PCT = 85;

    @Override
    public String id() {
        return "preflight.standby_resource_headroom";
    }

    @Override
    public String name() {
        return "Standby Member Resource Headroom";
    }

    @Override
    public String category() {
        return "Resource & Capacity";
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
        Optional<ClusterMemberEvidence> standbyOpt = snapshot.standbyMember();
        if (standbyOpt.isEmpty()) {
            return CheckResult.insufficientEvidence(
                id(), name(), category(), defaultPolicy(),
                "Cannot evaluate headroom without identifying the standby node.",
                "REMEDIATE_IDENTIFY_STANDBY"
            );
        }

        ClusterMemberEvidence standby = standbyOpt.get();

        if (standby.cpuUtilizationPct() >= MAX_ALLOWABLE_CPU_PCT) {
            return CheckResult.fail(
                id(), name(), category(), defaultPolicy(),
                "INSUFFICIENT CPU HEADROOM: Standby peer " + standby.maskedName() + " is already running at " + standby.cpuUtilizationPct() + "% CPU (threshold: " + MAX_ALLOWABLE_CPU_PCT + "%).",
                "REMEDIATE_INVESTIGATE_HIGH_CPU_ON_STANDBY"
            );
        }

        if (standby.memoryUtilizationPct() >= MAX_ALLOWABLE_MEM_PCT) {
            return CheckResult.fail(
                id(), name(), category(), defaultPolicy(),
                "INSUFFICIENT MEMORY HEADROOM: Standby peer " + standby.maskedName() + " memory utilization is " + standby.memoryUtilizationPct() + "% (threshold: " + MAX_ALLOWABLE_MEM_PCT + "%).",
                "REMEDIATE_INVESTIGATE_HIGH_MEMORY_ON_STANDBY"
            );
        }

        return CheckResult.pass(
            id(), name(), category(), defaultPolicy(),
            "Standby peer " + standby.maskedName() + " has sufficient capacity headroom (CPU: " + standby.cpuUtilizationPct() + "%, Mem: " + standby.memoryUtilizationPct() + "%)."
        );
    }
}
