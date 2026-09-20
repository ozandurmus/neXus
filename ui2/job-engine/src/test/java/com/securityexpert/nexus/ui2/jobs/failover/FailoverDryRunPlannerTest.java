package com.securityexpert.nexus.ui2.jobs.failover;

import com.securityexpert.nexus.ui2.jobs.failover.authz.FailoverLeaseToken;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterMemberEvidence;
import com.securityexpert.nexus.ui2.jobs.failover.plan.FailoverDryRunPlanner;
import com.securityexpert.nexus.ui2.jobs.failover.plan.FailoverExecutionPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FailoverDryRunPlannerTest {

    private final FailoverDryRunPlanner planner = new FailoverDryRunPlanner();

    private FailoverLeaseToken dummyToken(String clusterId) {
        return new FailoverLeaseToken(
            "tok-001",
            clusterId,
            "operator-alice",
            "operator-bob",
            "sha256:digest123",
            Instant.now(),
            Instant.now().plusSeconds(900),
            "signature-hex"
        );
    }

    private ClusterMemberEvidence member(String id, String maskedName, String state) {
        return new ClusterMemberEvidence(
            id,
            maskedName,
            state,
            "ACTIVE".equals(state) ? "STANDBY" : "ACTIVE",
            "CLUSTER_XL_HA",
            "SYNC_OK",
            0,
            true,
            0,
            true,
            List.of(),
            true,
            0,
            "take_79",
            "sha256:hash",
            20,
            40,
            1000,
            50000,
            false,
            0,
            0,
            false,
            true,
            Instant.now()
        );
    }

    @Test
    @DisplayName("Compiles Check Point ClusterXL dry-run plan with clusterXL_admin commands")
    void compilesCheckPointPlan() {
        var memA = member("dev-cp-1", "FW-TANGO-01", "ACTIVE");
        var memB = member("dev-cp-2", "FW-JULIET-06", "STANDBY");
        var snapshot = new ClusterEvidenceSnapshot(
            "cls-uuid-cp",
            "CLS-ROMEO-01",
            "CHECK_POINT",
            "CLUSTER_XL_HA",
            null,
            memA,
            memB,
            Instant.now()
        );

        FailoverExecutionPlan plan = planner.compileDryRunPlan(snapshot, dummyToken("cls-uuid-cp"));

        assertEquals("DRY_RUN", plan.planType());
        assertFalse(plan.mutationAuthorized(), "mutationAuthorized must be strictly FALSE for Phase B");
        assertEquals("CHECK_POINT", plan.vendor());
        assertEquals("CLUSTER_XL_HA", plan.haMode());

        // Check transition steps
        assertEquals(2, plan.transitionSteps().size());
        assertEquals("clusterXL_admin down", plan.transitionSteps().get(0).command());
        assertEquals("FW-TANGO-01", plan.transitionSteps().get(0).targetMember());
        assertEquals("cphaprob stat", plan.transitionSteps().get(1).command());

        // Check reversal steps
        assertEquals(1, plan.reversalSteps().size());
        assertEquals("clusterXL_admin up", plan.reversalSteps().get(0).command());
        assertEquals("FW-TANGO-01", plan.reversalSteps().get(0).targetMember());
    }

    @Test
    @DisplayName("Compiles Palo Alto Networks HA dry-run plan with high-availability suspend/functional commands")
    void compilesPaloAltoPlan() {
        var memA = member("dev-pa-1", "FW-TANGO-04", "ACTIVE");
        var memB = member("dev-pa-2", "FW-BRAVO-02", "PASSIVE");
        var snapshot = new ClusterEvidenceSnapshot(
            "cls-uuid-pa",
            "CLS-ROMEO-02",
            "PALO_ALTO",
            "ACTIVE_PASSIVE",
            null,
            memA,
            memB,
            Instant.now()
        );

        FailoverExecutionPlan plan = planner.compileDryRunPlan(snapshot, dummyToken("cls-uuid-pa"));

        assertEquals("DRY_RUN", plan.planType());
        assertFalse(plan.mutationAuthorized(), "mutationAuthorized must be strictly FALSE for Phase B");
        assertEquals("PALO_ALTO", plan.vendor());

        // Check transition steps
        assertEquals(2, plan.transitionSteps().size());
        assertEquals("request high-availability state suspend", plan.transitionSteps().get(0).command());
        assertEquals("FW-TANGO-04", plan.transitionSteps().get(0).targetMember());
        assertEquals("show high-availability state", plan.transitionSteps().get(1).command());

        // Check reversal steps
        assertEquals(1, plan.reversalSteps().size());
        assertEquals("request high-availability state functional", plan.reversalSteps().get(0).command());
    }

    @Test
    @DisplayName("Rejects unsupported vendor with IllegalArgumentException")
    void rejectsUnsupportedVendor() {
        var memA = member("dev-unknown-1", "FW-ALPHA-01", "ACTIVE");
        var memB = member("dev-unknown-2", "FW-ALPHA-02", "STANDBY");
        var snapshot = new ClusterEvidenceSnapshot(
            "cls-uuid-unk",
            "CLS-UNKNOWN",
            "CISCO_ASA",
            "ACTIVE_STANDBY",
            null,
            memA,
            memB,
            Instant.now()
        );

        assertThrows(IllegalArgumentException.class, () -> planner.compileDryRunPlan(snapshot, dummyToken("cls-uuid-unk")));
    }
}
