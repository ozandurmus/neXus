package com.securityexpert.nexus.ui2.jobs.failover;

import com.securityexpert.nexus.ui2.jobs.failover.checks.*;
import com.securityexpert.nexus.ui2.jobs.failover.model.*;
import com.securityexpert.nexus.ui2.jobs.failover.spi.PreflightRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreflightEngineTest {

    private PreflightRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PreflightRegistry();
    }

    private ClusterMemberEvidence createHealthyMember(String id, String maskedName, String state) {
        return new ClusterMemberEvidence(
            id,
            maskedName,
            state,
            "ACTIVE".equalsIgnoreCase(state) ? "STANDBY" : "ACTIVE",
            "CLUSTER_XL_HA",
            "SYNC_OK",
            5,
            true,
            0,
            true,
            List.of(),
            true,
            0,
            "R81.20-JUMBO_TAKE_79",
            "sha256:abc123policyhash",
            25,
            42,
            12000,
            100000,
            false,
            0,
            0,
            false,
            true,
            Instant.now()
        );
    }

    private ClusterEvidenceSnapshot createHealthyCheckPointSnapshot() {
        return new ClusterEvidenceSnapshot(
            "cls-uuid-001",
            "CLS-ROMEO-01",
            "CHECK_POINT",
            "CLUSTER_XL_HA",
            null,
            createHealthyMember("fw-1", "FW-TANGO-04", "ACTIVE"),
            createHealthyMember("fw-2", "FW-JULIET-06", "STANDBY"),
            Instant.now()
        );
    }

    @Test
    @DisplayName("Healthy Check Point cluster passes all pre-flight checks")
    void testHealthyCheckPointClusterPasses() {
        ClusterEvidenceSnapshot snapshot = createHealthyCheckPointSnapshot();
        PreflightReport report = registry.evaluateAll(snapshot);

        assertNotNull(report);
        assertEquals(PreflightVerdict.NO_BLOCKING_CONDITIONS_OBSERVED, report.overallVerdict());
        assertEquals(0, report.blockingFailureCount());
        assertTrue(report.passCount() >= 10);
    }

    @Test
    @DisplayName("Two-Sided Split-Brain detection: both members claiming ACTIVE strictly triggers FAIL")
    void testSplitBrainDetection() {
        ClusterMemberEvidence memA = createHealthyMember("fw-1", "FW-TANGO-04", "ACTIVE");
        ClusterMemberEvidence memB = createHealthyMember("fw-2", "FW-JULIET-06", "ACTIVE"); // Also ACTIVE!
        ClusterEvidenceSnapshot splitBrainSnapshot = new ClusterEvidenceSnapshot(
            "cls-uuid-001", "CLS-ROMEO-01", "CHECK_POINT", "CLUSTER_XL_HA", null, memA, memB, Instant.now()
        );

        PreflightReport report = registry.evaluateAll(splitBrainSnapshot);

        assertEquals(PreflightVerdict.BLOCKING_CONDITIONS_PRESENT, report.overallVerdict());
        assertTrue(report.blockingFailureCount() >= 1);

        CheckResult splitBrainCheck = report.checks().stream()
            .filter(c -> c.checkId().equals("preflight.split_brain_prevention"))
            .findFirst()
            .orElseThrow();

        assertEquals(CheckStatus.FAIL, splitBrainCheck.status());
        assertEquals(EnforcementPolicy.BLOCKING, splitBrainCheck.enforcement());
        assertTrue(splitBrainCheck.summary().contains("CRITICAL SPLIT-BRAIN DETECTED"));
    }

    @Test
    @DisplayName("Uncorroborated / one-sided evidence fails closed with INSUFFICIENT_EVIDENCE")
    void testUncorroboratedEvidenceFailsClosed() {
        ClusterMemberEvidence memA = createHealthyMember("fw-1", "FW-TANGO-04", "ACTIVE");
        ClusterMemberEvidence unobservedB = new ClusterMemberEvidence(
            "fw-2", "FW-JULIET-06", "UNKNOWN", "UNKNOWN", "CLUSTER_XL_HA", "UNKNOWN", 0,
            false, 0, false, List.of(), false, 0, null, null, 0, 0, 0, 0, false, 0, 0, false,
            false, // directObservationSuccessful = false!
            Instant.now()
        );
        ClusterEvidenceSnapshot snapshot = new ClusterEvidenceSnapshot(
            "cls-uuid-001", "CLS-ROMEO-01", "CHECK_POINT", "CLUSTER_XL_HA", null, memA, unobservedB, Instant.now()
        );

        PreflightReport report = registry.evaluateAll(snapshot);

        assertEquals(PreflightVerdict.BLOCKING_CONDITIONS_PRESENT, report.overallVerdict());

        CheckResult splitBrainCheck = report.checks().stream()
            .filter(c -> c.checkId().equals("preflight.split_brain_prevention"))
            .findFirst()
            .orElseThrow();

        assertEquals(CheckStatus.INSUFFICIENT_EVIDENCE, splitBrainCheck.status());
    }

    @Test
    @DisplayName("Standby peer resource exhaustion (CPU >= 80%) strictly blocks failover")
    void testStandbyHighCpuBlocks() {
        ClusterMemberEvidence memA = createHealthyMember("fw-1", "FW-TANGO-04", "ACTIVE");
        ClusterMemberEvidence overloadedStandby = new ClusterMemberEvidence(
            "fw-2", "FW-JULIET-06", "STANDBY", "ACTIVE", "CLUSTER_XL_HA", "SYNC_OK", 0,
            true, 0, true, List.of(), true, 0, "R81.20-JUMBO_TAKE_79", "sha256:abc123policyhash",
            92, // High CPU 92%!
            40, 5000, 100000, false, 0, 0, false, true, Instant.now()
        );
        ClusterEvidenceSnapshot snapshot = new ClusterEvidenceSnapshot(
            "cls-uuid-001", "CLS-ROMEO-01", "CHECK_POINT", "CLUSTER_XL_HA", null, memA, overloadedStandby, Instant.now()
        );

        PreflightReport report = registry.evaluateAll(snapshot);

        assertEquals(PreflightVerdict.BLOCKING_CONDITIONS_PRESENT, report.overallVerdict());

        CheckResult headroomCheck = report.checks().stream()
            .filter(c -> c.checkId().equals("preflight.standby_resource_headroom"))
            .findFirst()
            .orElseThrow();

        assertEquals(CheckStatus.FAIL, headroomCheck.status());
        assertTrue(headroomCheck.summary().contains("INSUFFICIENT CPU HEADROOM"));
    }

    @Test
    @DisplayName("Software version disparity triggers blocking failure")
    void testSoftwareVersionDisparityBlocks() {
        ClusterMemberEvidence memA = createHealthyMember("fw-1", "FW-TANGO-04", "ACTIVE");
        ClusterMemberEvidence differentVersionStandby = new ClusterMemberEvidence(
            "fw-2", "FW-JULIET-06", "STANDBY", "ACTIVE", "CLUSTER_XL_HA", "SYNC_OK", 0,
            true, 0, true, List.of(), true, 0,
            "R81.20-JUMBO_TAKE_65", // Different version from take 79!
            "sha256:abc123policyhash", 20, 35, 5000, 100000, false, 0, 0, false, true, Instant.now()
        );
        ClusterEvidenceSnapshot snapshot = new ClusterEvidenceSnapshot(
            "cls-uuid-001", "CLS-ROMEO-01", "CHECK_POINT", "CLUSTER_XL_HA", null, memA, differentVersionStandby, Instant.now()
        );

        PreflightReport report = registry.evaluateAll(snapshot);

        assertEquals(PreflightVerdict.BLOCKING_CONDITIONS_PRESENT, report.overallVerdict());

        CheckResult policyCheck = report.checks().stream()
            .filter(c -> c.checkId().equals("preflight.policy_parity"))
            .findFirst()
            .orElseThrow();

        assertEquals(CheckStatus.FAIL, policyCheck.status());
        assertTrue(policyCheck.summary().contains("SOFTWARE VERSION MISMATCH"));
    }

    @Test
    @DisplayName("Cluster with flap history >= 2 transitions in last 24h triggers blocking failure")
    void testFlappingClusterBlocks() {
        ClusterMemberEvidence memA = createHealthyMember("fw-1", "FW-TANGO-04", "ACTIVE");
        ClusterMemberEvidence flappingStandby = new ClusterMemberEvidence(
            "fw-2", "FW-JULIET-06", "STANDBY", "ACTIVE", "CLUSTER_XL_HA", "SYNC_OK", 0,
            true, 0, true, List.of(), true, 0, "R81.20-JUMBO_TAKE_79", "sha256:abc123policyhash",
            20, 35, 5000, 100000, false, 0,
            3, // 3 flaps in last 24h!
            false, true, Instant.now()
        );
        ClusterEvidenceSnapshot snapshot = new ClusterEvidenceSnapshot(
            "cls-uuid-001", "CLS-ROMEO-01", "CHECK_POINT", "CLUSTER_XL_HA", null, memA, flappingStandby, Instant.now()
        );

        PreflightReport report = registry.evaluateAll(snapshot);

        assertEquals(PreflightVerdict.BLOCKING_CONDITIONS_PRESENT, report.overallVerdict());

        CheckResult flapCheck = report.checks().stream()
            .filter(c -> c.checkId().equals("preflight.flap_history"))
            .findFirst()
            .orElseThrow();

        assertEquals(CheckStatus.FAIL, flapCheck.status());
        assertTrue(flapCheck.summary().contains("RECENT CLUSTER INSTABILITY"));
    }

    @Test
    @DisplayName("Unsupported HA mode (e.g. Active/Active or VSLS) fails closed with UNSUPPORTED")
    void testUnsupportedHaModeBlocks() {
        ClusterMemberEvidence memA = createHealthyMember("fw-1", "FW-TANGO-04", "ACTIVE");
        ClusterMemberEvidence memB = createHealthyMember("fw-2", "FW-JULIET-06", "STANDBY");
        ClusterEvidenceSnapshot snapshot = new ClusterEvidenceSnapshot(
            "cls-uuid-001", "CLS-ROMEO-01", "CHECK_POINT",
            "VSLS_LOAD_SHARING", // Unsupported!
            null, memA, memB, Instant.now()
        );

        PreflightReport report = registry.evaluateAll(snapshot);

        assertEquals(PreflightVerdict.BLOCKING_CONDITIONS_PRESENT, report.overallVerdict());

        CheckResult modeCheck = report.checks().stream()
            .filter(c -> c.checkId().equals("preflight.platform_mode_gate"))
            .findFirst()
            .orElseThrow();

        assertEquals(CheckStatus.UNSUPPORTED, modeCheck.status());
        assertEquals(EnforcementPolicy.BLOCKING, modeCheck.enforcement());
    }

    @Test
    @DisplayName("Generated Matrix Invariant: if any BLOCKING check is non-PASS, overallVerdict CANNOT be NO_BLOCKING_CONDITIONS_OBSERVED")
    void testGeneratedMatrixInvariant() {
        CheckStatus[] statuses = CheckStatus.values();
        EnforcementPolicy[] policies = EnforcementPolicy.values();

        for (CheckStatus status : statuses) {
            for (EnforcementPolicy policy : policies) {
                CheckResult dummy = new CheckResult(
                    "dummy.check", "Dummy", "Test", status, policy, "summary", null, Instant.now()
                );
                PreflightReport report = PreflightReport.fromResults(
                    "cls-1", "CLS-ROMEO-01", "CHECK_POINT", "CLUSTER_XL_HA", List.of(dummy)
                );

                if (policy == EnforcementPolicy.BLOCKING && status != CheckStatus.PASS) {
                    assertEquals(
                        PreflightVerdict.BLOCKING_CONDITIONS_PRESENT,
                        report.overallVerdict(),
                        "Invariant violated for status=" + status + " and policy=" + policy
                    );
                } else {
                    assertEquals(
                        PreflightVerdict.NO_BLOCKING_CONDITIONS_OBSERVED,
                        report.overallVerdict(),
                        "Invariant violated for status=" + status + " and policy=" + policy
                    );
                }
            }
        }
    }
}
