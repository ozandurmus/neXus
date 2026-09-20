package com.securityexpert.nexus.ui2.jobs.failover.checks;

import com.securityexpert.nexus.ui2.jobs.failover.model.CheckResult;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.EnforcementPolicy;
import com.securityexpert.nexus.ui2.jobs.failover.spi.PreflightCheck;

import java.util.Set;

/**
 * Gate check verifying that the cluster topology and mode are explicitly contracted.
 * Fails closed as UNSUPPORTED for Active/Active, VSLS, VRRP, or Maestro Security Groups.
 */
public class PlatformAndModeGateCheck implements PreflightCheck {

    private static final Set<String> SUPPORTED_MODES = Set.of(
        "CLUSTER_XL_HA",
        "HIGH_AVAILABILITY",
        "PAN_ACTIVE_PASSIVE",
        "ACTIVE_PASSIVE",
        "ACTIVE-PASSIVE"
    );

    @Override
    public String id() {
        return "preflight.platform_mode_gate";
    }

    @Override
    public String name() {
        return "Platform & HA Mode Gate";
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
        String mode = snapshot.haMode();
        if (mode == null || mode.isBlank()) {
            return CheckResult.insufficientEvidence(
                id(), name(), category(), defaultPolicy(),
                "HA Mode is undefined or cannot be determined from cluster evidence.",
                "REMEDIATE_VERIFY_HA_MODE"
            );
        }

        String normalized = mode.trim().toUpperCase();
        if (!SUPPORTED_MODES.contains(normalized)) {
            return CheckResult.unsupported(
                id(), name(), category(),
                "UNSUPPORTED HA TOPOLOGY: Mode '" + mode + "' is not supported for automated failover (Active/Active, VSLS, VRRP, and Maestro are prohibited).",
                "REMEDIATE_UNSUPPORTED_TOPOLOGY"
            );
        }

        return CheckResult.pass(
            id(), name(), category(), defaultPolicy(),
            "HA Mode '" + mode + "' is verified and supported for automated Active/Passive operations."
        );
    }
}
