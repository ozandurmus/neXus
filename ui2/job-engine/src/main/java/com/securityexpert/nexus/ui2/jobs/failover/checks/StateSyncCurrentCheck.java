package com.securityexpert.nexus.ui2.jobs.failover.checks;

import com.securityexpert.nexus.ui2.jobs.failover.model.CheckResult;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterMemberEvidence;
import com.securityexpert.nexus.ui2.jobs.failover.model.EnforcementPolicy;
import com.securityexpert.nexus.ui2.jobs.failover.spi.PreflightCheck;

/**
 * Validates that connection state table synchronization is complete and healthy
 * without high queue backlog deltas.
 */
public class StateSyncCurrentCheck implements PreflightCheck {

    private static final long MAX_PERMISSIBLE_SYNC_DELTA = 100;

    @Override
    public String id() {
        return "preflight.state_sync_current";
    }

    @Override
    public String name() {
        return "State Synchronization Health";
    }

    @Override
    public String category() {
        return "State & Synchronization";
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
                "Cannot assess synchronization health without direct telemetry from both peers.",
                "REMEDIATE_INSPECT_SYNC_CONNECTIVITY"
            );
        }

        ClusterMemberEvidence a = snapshot.memberA();
        ClusterMemberEvidence b = snapshot.memberB();

        boolean aSyncOk = "SYNC_OK".equalsIgnoreCase(a.syncStatus()) || "COMPLETE".equalsIgnoreCase(a.syncStatus());
        boolean bSyncOk = "SYNC_OK".equalsIgnoreCase(b.syncStatus()) || "COMPLETE".equalsIgnoreCase(b.syncStatus());

        if (!aSyncOk || !bSyncOk) {
            return CheckResult.fail(
                id(), name(), category(), defaultPolicy(),
                "State synchronization degraded or failed (Member A: " + a.syncStatus() + ", Member B: " + b.syncStatus() + ").",
                "REMEDIATE_SYNCHRONIZATION_TRANSPORT"
            );
        }

        long maxDelta = Math.max(a.syncQueueDelta(), b.syncQueueDelta());
        if (maxDelta > MAX_PERMISSIBLE_SYNC_DELTA) {
            return CheckResult.warning(
                id(), name(), category(),
                "Synchronization backlog elevated (queue delta: " + maxDelta + " events > " + MAX_PERMISSIBLE_SYNC_DELTA + "). Failover may experience brief state reconstruction latency.",
                "ADVISORY_ALLOW_SYNC_QUEUE_DRAIN"
            );
        }

        return CheckResult.pass(
            id(), name(), category(), defaultPolicy(),
            "Connection state synchronization is healthy, current, and synchronized across both peers (delta: " + maxDelta + " events)."
        );
    }
}
