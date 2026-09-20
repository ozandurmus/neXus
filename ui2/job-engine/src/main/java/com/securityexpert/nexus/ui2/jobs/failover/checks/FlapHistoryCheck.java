package com.securityexpert.nexus.ui2.jobs.failover.checks;

import com.securityexpert.nexus.ui2.jobs.failover.model.CheckResult;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterMemberEvidence;
import com.securityexpert.nexus.ui2.jobs.failover.model.EnforcementPolicy;
import com.securityexpert.nexus.ui2.jobs.failover.spi.PreflightCheck;

/**
 * Validates cluster stability by inspecting state transition / flap history in the last 24 hours.
 * Strictly blocks failover into an already unstable or oscillating cluster (>= 2 flaps).
 */
public class FlapHistoryCheck implements PreflightCheck {

    private static final int MAX_BLOCKING_FLAP_COUNT = 2;

    @Override
    public String id() {
        return "preflight.flap_history";
    }

    @Override
    public String name() {
        return "Cluster Stability & Flap History";
    }

    @Override
    public String category() {
        return "Operational Risk";
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
                "Flap counters cannot be verified without telemetry from both nodes.",
                "REMEDIATE_CHECK_CLUSTER_LOGS"
            );
        }

        ClusterMemberEvidence a = snapshot.memberA();
        ClusterMemberEvidence b = snapshot.memberB();

        int maxFlaps = Math.max(a.flapCountLast24Hours(), b.flapCountLast24Hours());

        if (maxFlaps >= MAX_BLOCKING_FLAP_COUNT) {
            return CheckResult.fail(
                id(), name(), category(), defaultPolicy(),
                "RECENT CLUSTER INSTABILITY: " + maxFlaps + " state transitions/flaps observed in the last 24 hours. Initiating failover on an oscillating cluster risks complete outage.",
                "REMEDIATE_STABILIZE_CLUSTER_BEFORE_FAILOVER"
            );
        }

        if (maxFlaps == 1) {
            return CheckResult.warning(
                id(), name(), category(),
                "1 failover transition observed in the last 24 hours. Verify underlying cause before proceeding.",
                "ADVISORY_REVIEW_RECENT_FAILOVER_LOGS"
            );
        }

        return CheckResult.pass(
            id(), name(), category(), defaultPolicy(),
            "Cluster has been completely stable with 0 state transitions in the last 24 hours."
        );
    }
}
