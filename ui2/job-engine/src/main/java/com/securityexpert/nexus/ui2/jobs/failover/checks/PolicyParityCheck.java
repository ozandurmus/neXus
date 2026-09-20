package com.securityexpert.nexus.ui2.jobs.failover.checks;

import com.securityexpert.nexus.ui2.jobs.failover.model.CheckResult;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterMemberEvidence;
import com.securityexpert.nexus.ui2.jobs.failover.model.EnforcementPolicy;
import com.securityexpert.nexus.ui2.jobs.failover.spi.PreflightCheck;

/**
 * Validates software version parity and security policy alignment between cluster peers.
 */
public class PolicyParityCheck implements PreflightCheck {

    @Override
    public String id() {
        return "preflight.policy_parity";
    }

    @Override
    public String name() {
        return "Software & Policy Parity";
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
        return true;
    }

    @Override
    public CheckResult evaluate(ClusterEvidenceSnapshot snapshot) {
        if (!snapshot.bothMembersDirectlyObserved()) {
            return CheckResult.insufficientEvidence(
                id(), name(), category(), defaultPolicy(),
                "Software and policy parity cannot be evaluated without direct telemetry from both peers.",
                "REMEDIATE_COLLECT_PEER_POLICY"
            );
        }

        ClusterMemberEvidence a = snapshot.memberA();
        ClusterMemberEvidence b = snapshot.memberB();

        if (a.softwareVersion() == null || b.softwareVersion() == null) {
            return CheckResult.insufficientEvidence(
                id(), name(), category(), defaultPolicy(),
                "Software version telemetry missing from one or both members.",
                "REMEDIATE_COLLECT_SOFTWARE_VERSION"
            );
        }

        if (!a.softwareVersion().equalsIgnoreCase(b.softwareVersion())) {
            return CheckResult.fail(
                id(), name(), category(), defaultPolicy(),
                "SOFTWARE VERSION MISMATCH: Member A is running " + a.softwareVersion() + " while Member B is running " + b.softwareVersion(),
                "REMEDIATE_ALIGN_SOFTWARE_VERSIONS"
            );
        }

        if (a.installedPolicyHash() != null && b.installedPolicyHash() != null) {
            if (!a.installedPolicyHash().equalsIgnoreCase(b.installedPolicyHash())) {
                return CheckResult.fail(
                    id(), name(), category(), defaultPolicy(),
                    "INSTALLED POLICY DIVERGENCE: Security policy hash differs between cluster members. Failover will alter rule evaluation.",
                    "REMEDIATE_INSTALL_SECURITY_POLICY_TO_BOTH_MEMBERS"
                );
            }
        }

        return CheckResult.pass(
            id(), name(), category(), defaultPolicy(),
            "Software version (" + a.softwareVersion() + ") and security policy hashes are aligned across both members."
        );
    }
}
