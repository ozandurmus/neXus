package com.securityexpert.nexus.ui2.jobs.failover.checks;

import com.securityexpert.nexus.ui2.jobs.failover.model.CheckResult;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterMemberEvidence;
import com.securityexpert.nexus.ui2.jobs.failover.model.EnforcementPolicy;
import com.securityexpert.nexus.ui2.jobs.failover.spi.PreflightCheck;

/**
 * Evaluates cluster preemption configuration.
 * Warns operators if preemption is enabled, which would cause an automatic and uncontrolled
 * failback once the demoted node returns to service.
 */
public class PreemptionAwarenessCheck implements PreflightCheck {

    @Override
    public String id() {
        return "preflight.preemption_awareness";
    }

    @Override
    public String name() {
        return "Preemption Hazard Disclosure";
    }

    @Override
    public String category() {
        return "Operational Risk";
    }

    @Override
    public EnforcementPolicy defaultPolicy() {
        return EnforcementPolicy.ADVISORY;
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
                "Preemption flags cannot be confirmed without peer telemetry.",
                "REMEDIATE_CHECK_PREEMPTION_CONFIG"
            );
        }

        ClusterMemberEvidence a = snapshot.memberA();
        ClusterMemberEvidence b = snapshot.memberB();

        if (a.preemptionEnabled() || b.preemptionEnabled()) {
            return CheckResult.warning(
                id(), name(), category(),
                "PREEMPTION IS ENABLED: When the demoted node is restored, it will immediately attempt to preemptively reclaim active status, causing a secondary traffic flap.",
                "ADVISORY_DISABLE_PREEMPTION_BEFORE_MAINTENANCE"
            );
        }

        return CheckResult.pass(
            id(), name(), category(), defaultPolicy(),
            "Preemption is disabled across both cluster members. Failover will remain stable post-transition."
        );
    }
}
