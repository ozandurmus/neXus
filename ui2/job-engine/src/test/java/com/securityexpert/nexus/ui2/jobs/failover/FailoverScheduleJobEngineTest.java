package com.securityexpert.nexus.ui2.jobs.failover;

import com.securityexpert.nexus.ui2.jobs.failover.checks.ClockHealthCheck;
import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverActionKind;
import com.securityexpert.nexus.ui2.jobs.failover.model.*;
import com.securityexpert.nexus.ui2.jobs.failover.schedule.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FailoverScheduleJobEngineTest {

    private static final byte[] MASTER_KEY = "0123456789abcdef0123456789abcdef".getBytes();

    @Test
    @DisplayName("ScheduleCryptographicService validates authentic envelopes and detects tampered fields")
    void cryptoSignatureAndTamperTest() {
        ScheduleCryptographicService crypto = new ScheduleCryptographicService(MASTER_KEY);

        Instant now = Instant.now();
        Instant windowEnd = now.plus(Duration.ofHours(1));

        FailoverScheduleEnvelope envelope = new FailoverScheduleEnvelope(
            "sched-001",
            "cls-cp-01",
            "CHECK_POINT",
            "cmd-cp-clusterxl",
            "CONTROLLED_FAILOVER",
            "dev-cp-1",
            now,
            windowEnd,
            15,
            "operator-alice",
            "approver-bob",
            "grant-777",
            "digest-abc-123",
            "nonce-xyz-456",
            "k1",
            "HMAC_SHA256_V1"
        );

        String signature = crypto.signEnvelope(envelope);
        assertNotNull(signature);
        assertFalse(signature.isBlank());

        // 1. Valid verification
        assertTrue(crypto.verifyEnvelope(envelope, signature));

        // 2. Tampered target member
        FailoverScheduleEnvelope tamperedTarget = new FailoverScheduleEnvelope(
            "sched-001", "cls-cp-01", "CHECK_POINT", "cmd-cp-clusterxl", "CONTROLLED_FAILOVER",
            "dev-cp-2", // tampered!
            now, windowEnd, 15, "operator-alice", "approver-bob", "grant-777",
            "digest-abc-123", "nonce-xyz-456", "k1", "HMAC_SHA256_V1"
        );
        assertFalse(crypto.verifyEnvelope(tamperedTarget, signature));

        // 3. Tampered window delay
        FailoverScheduleEnvelope tamperedDelay = new FailoverScheduleEnvelope(
            "sched-001", "cls-cp-01", "CHECK_POINT", "cmd-cp-clusterxl", "CONTROLLED_FAILOVER",
            "dev-cp-1",
            now, windowEnd, 60, // enlarged delay!
            "operator-alice", "approver-bob", "grant-777",
            "digest-abc-123", "nonce-xyz-456", "k1", "HMAC_SHA256_V1"
        );
        assertFalse(crypto.verifyEnvelope(tamperedDelay, signature));

        // 4. Tampered grant ID
        FailoverScheduleEnvelope tamperedGrant = new FailoverScheduleEnvelope(
            "sched-001", "cls-cp-01", "CHECK_POINT", "cmd-cp-clusterxl", "CONTROLLED_FAILOVER",
            "dev-cp-1",
            now, windowEnd, 15, "operator-alice", "approver-bob",
            "grant-888", // modified grant!
            "digest-abc-123", "nonce-xyz-456", "k1", "HMAC_SHA256_V1"
        );
        assertFalse(crypto.verifyEnvelope(tamperedGrant, signature));

        // 5. Tampered action kind (Claude CF-P0.1: action must be cryptographically bound)
        FailoverScheduleEnvelope tamperedAction = new FailoverScheduleEnvelope(
            "sched-001", "cls-cp-01", "CHECK_POINT", "cmd-cp-clusterxl",
            "RETURN_TO_SERVICE", // tampered!
            "dev-cp-1", now, windowEnd, 15, "operator-alice", "approver-bob", "grant-777",
            "digest-abc-123", "nonce-xyz-456", "k1", "HMAC_SHA256_V1"
        );
        assertFalse(crypto.verifyEnvelope(tamperedAction, signature));

        // 6. Malformed hex signature must return false, never throw (Claude CF-P1.3)
        assertFalse(crypto.verifyEnvelope(envelope, "not-valid-hex!!"));
        assertFalse(crypto.verifyEnvelope(envelope, "abc")); // odd length
    }

    @Test
    @DisplayName("ScheduleCryptographicService rejects master keys shorter than 32 bytes")
    void shortMasterKeyRejectedTest() {
        byte[] shortKey = new byte[24];
        assertThrows(IllegalArgumentException.class, () -> new ScheduleCryptographicService(shortKey));
    }

    @Test
    @DisplayName("BaselineSnapshotSummary.computeCanonicalDigest is deterministic and content-sensitive")
    void baselineCanonicalDigestTest() {
        Instant recordedAt = Instant.parse("2026-01-01T00:00:00Z");

        BaselineSnapshotSummary baselineA = BaselineSnapshotSummary.of(
            "cls-cp-01", "CHECK_POINT", "ClusterXL", "dev-cp-1", "dev-cp-2",
            "R81.20", "hash-policy-v1", 0L, "unused", recordedAt
        );
        BaselineSnapshotSummary baselineB = BaselineSnapshotSummary.of(
            "cls-cp-01", "CHECK_POINT", "ClusterXL", "dev-cp-1", "dev-cp-2",
            "R81.20", "hash-policy-v1", 0L, "different-unused-value", recordedAt
        );
        BaselineSnapshotSummary baselineDifferent = BaselineSnapshotSummary.of(
            "cls-cp-01", "CHECK_POINT", "ClusterXL", "dev-cp-1", "dev-cp-2",
            "R81.21", "hash-policy-v1", 0L, "unused", recordedAt
        );

        // Same content fields -> same digest, regardless of the (unhashed) assessmentDigest field
        assertEquals(baselineA.computeCanonicalDigest(), baselineB.computeCanonicalDigest());
        // Different content field -> different digest
        assertNotEquals(baselineA.computeCanonicalDigest(), baselineDifferent.computeCanonicalDigest());
    }

    // Shared correlation token proving the T0 preflight report and each live snapshot variant
    // below originate from "the same evidence-collection pass" for the purposes of this test
    // (Claude CF-P0.13 single-snapshot invariant).
    private static final String T0_SNAPSHOT_ID = "t0-snapshot-drift-test";

    @Test
    @DisplayName("FailoverDriftEngine detects active member, policy, and flap drift")
    void driftEngineEvaluationTest() {
        FailoverDriftEngine engine = new FailoverDriftEngine();
        Instant now = Instant.now();

        BaselineSnapshotSummary baseline = BaselineSnapshotSummary.of(
            "cls-cp-01", "CHECK_POINT", "ClusterXL",
            "dev-cp-1", "dev-cp-2", "R81.20", "hash-policy-v1",
            0L, "digest-preflight-001", now.minus(Duration.ofHours(2))
        );

        ClusterMemberEvidence liveActive = new ClusterMemberEvidence(
            "dev-cp-1", "FW-TANGO-01", "ACTIVE", "STANDBY", "ClusterXL",
            "SYNCHRONIZED", 0L, true, 0, true, List.of(),
            true, 0, "R81.20", "hash-policy-v1",
            20, 30, 1000L, 50000L, false, 0, 0, false, true, now
        );
        ClusterMemberEvidence liveStandby = new ClusterMemberEvidence(
            "dev-cp-2", "FW-TANGO-02", "STANDBY", "ACTIVE", "ClusterXL",
            "SYNCHRONIZED", 0L, true, 0, true, List.of(),
            true, 0, "R81.20", "hash-policy-v1",
            15, 25, 1000L, 50000L, false, 0, 0, false, true, now
        );

        ClusterEvidenceSnapshot cleanSnapshot = new ClusterEvidenceSnapshot(
            T0_SNAPSHOT_ID, "cls-cp-01", "CLS-TANGO-01", "CHECK_POINT", "ClusterXL", null,
            liveActive, liveStandby, now
        );

        PreflightReport cleanReport = PreflightReport.fromResults(
            "cls-cp-01", "CLS-TANGO-01", "CHECK_POINT", "ClusterXL",
            List.of(CheckResult.pass("chk1", "Check 1", "Health", EnforcementPolicy.BLOCKING, "OK")),
            cleanSnapshot
        );

        // 1. Zero drift -> PASS
        DriftEvaluationResult passResult = engine.evaluateDrift(baseline, cleanSnapshot, cleanReport);
        assertTrue(passResult.isPass());
        assertEquals(DriftEvaluationResult.DriftStatus.PASS, passResult.status());

        // 2. Policy hash drift
        ClusterMemberEvidence policyDriftActive = new ClusterMemberEvidence(
            "dev-cp-1", "FW-TANGO-01", "ACTIVE", "STANDBY", "ClusterXL",
            "SYNCHRONIZED", 0L, true, 0, true, List.of(),
            true, 0, "R81.20", "hash-policy-MODIFIED",
            20, 30, 1000L, 50000L, false, 0, 0, false, true, now
        );
        ClusterEvidenceSnapshot policyDriftSnapshot = new ClusterEvidenceSnapshot(
            T0_SNAPSHOT_ID, "cls-cp-01", "CLS-TANGO-01", "CHECK_POINT", "ClusterXL", null,
            policyDriftActive, liveStandby, now
        );
        DriftEvaluationResult policyResult = engine.evaluateDrift(baseline, policyDriftSnapshot, cleanReport);
        assertFalse(policyResult.isPass());
        assertEquals(DriftEvaluationResult.DriftStatus.DRIFT, policyResult.status());
        assertTrue(policyResult.typedReasonCodes().contains(FailoverDriftEngine.REASON_POLICY_CHANGED));

        // 3. Active member role swap drift
        ClusterMemberEvidence swappedActive = new ClusterMemberEvidence(
            "dev-cp-2", "FW-TANGO-02", "ACTIVE", "STANDBY", "ClusterXL", // dev-cp-2 is active!
            "SYNCHRONIZED", 0L, true, 0, true, List.of(),
            true, 0, "R81.20", "hash-policy-v1",
            20, 30, 1000L, 50000L, false, 0, 0, false, true, now
        );
        ClusterMemberEvidence swappedStandby = new ClusterMemberEvidence(
            "dev-cp-1", "FW-TANGO-01", "STANDBY", "ACTIVE", "ClusterXL",
            "SYNCHRONIZED", 0L, true, 0, true, List.of(),
            true, 0, "R81.20", "hash-policy-v1",
            15, 25, 1000L, 50000L, false, 0, 0, false, true, now
        );
        ClusterEvidenceSnapshot swappedSnapshot = new ClusterEvidenceSnapshot(
            T0_SNAPSHOT_ID, "cls-cp-01", "CLS-TANGO-01", "CHECK_POINT", "ClusterXL", null,
            swappedActive, swappedStandby, now
        );
        DriftEvaluationResult roleResult = engine.evaluateDrift(baseline, swappedSnapshot, cleanReport);
        assertFalse(roleResult.isPass());
        assertTrue(roleResult.typedReasonCodes().contains(FailoverDriftEngine.REASON_ACTIVE_IDENTITY_CHANGED));

        // 4. Flap detected drift (live counter advanced beyond baseline)
        ClusterMemberEvidence flappedActive = new ClusterMemberEvidence(
            "dev-cp-1", "FW-TANGO-01", "ACTIVE", "STANDBY", "ClusterXL",
            "SYNCHRONIZED", 0L, true, 0, true, List.of(),
            true, 0, "R81.20", "hash-policy-v1",
            20, 30, 1000L, 50000L, false, 0, 3, // 3 flaps!
            false, true, now
        );
        ClusterEvidenceSnapshot flappedSnapshot = new ClusterEvidenceSnapshot(
            T0_SNAPSHOT_ID, "cls-cp-01", "CLS-TANGO-01", "CHECK_POINT", "ClusterXL", null,
            flappedActive, liveStandby, now
        );
        DriftEvaluationResult flapResult = engine.evaluateDrift(baseline, flappedSnapshot, cleanReport);
        assertFalse(flapResult.isPass());
        assertTrue(flapResult.typedReasonCodes().contains(FailoverDriftEngine.REASON_CLUSTER_FLAP_DETECTED));
    }

    @Test
    @DisplayName("FailoverDriftEngine fails closed when live snapshot cannot be corroborated as the same T0 evidence pass")
    void driftEngineSingleSnapshotInvariantTest() {
        FailoverDriftEngine engine = new FailoverDriftEngine();
        Instant now = Instant.now();

        BaselineSnapshotSummary baseline = BaselineSnapshotSummary.of(
            "cls-cp-01", "CHECK_POINT", "ClusterXL",
            "dev-cp-1", "dev-cp-2", "R81.20", "hash-policy-v1",
            0L, "digest-preflight-001", now.minus(Duration.ofHours(2))
        );
        ClusterMemberEvidence active = new ClusterMemberEvidence(
            "dev-cp-1", "FW-TANGO-01", "ACTIVE", "STANDBY", "ClusterXL",
            "SYNCHRONIZED", 0L, true, 0, true, List.of(),
            true, 0, "R81.20", "hash-policy-v1",
            20, 30, 1000L, 50000L, false, 0, 0, false, true, now
        );
        ClusterMemberEvidence standby = new ClusterMemberEvidence(
            "dev-cp-2", "FW-TANGO-02", "STANDBY", "ACTIVE", "ClusterXL",
            "SYNCHRONIZED", 0L, true, 0, true, List.of(),
            true, 0, "R81.20", "hash-policy-v1",
            15, 25, 1000L, 50000L, false, 0, 0, false, true, now
        );

        ClusterEvidenceSnapshot t0Snapshot = new ClusterEvidenceSnapshot(
            "snapshot-A", "cls-cp-01", "CLS-TANGO-01", "CHECK_POINT", "ClusterXL", null, active, standby, now
        );
        PreflightReport t0Report = PreflightReport.fromResults(
            "cls-cp-01", "CLS-TANGO-01", "CHECK_POINT", "ClusterXL",
            List.of(CheckResult.pass("chk1", "Check 1", "Health", EnforcementPolicy.BLOCKING, "OK")),
            t0Snapshot
        );

        // A different snapshot object (different snapshotId) presented as "live" must fail closed,
        // even though its member evidence is byte-for-byte identical to the T0 snapshot.
        ClusterEvidenceSnapshot differentPassSnapshot = new ClusterEvidenceSnapshot(
            "snapshot-B", "cls-cp-01", "CLS-TANGO-01", "CHECK_POINT", "ClusterXL", null, active, standby, now
        );
        DriftEvaluationResult mismatched = engine.evaluateDrift(baseline, differentPassSnapshot, t0Report);
        assertFalse(mismatched.isPass());
        assertEquals(DriftEvaluationResult.DriftStatus.INSUFFICIENT_EVIDENCE, mismatched.status());
        assertEquals(DriftDimensionStatus.NOT_EVALUABLE, mismatched.dimensions().get("evidence_snapshot_identity"));

        // A report with no evidence snapshot at all (legacy construction) must also fail closed.
        PreflightReport reportWithoutSnapshot = PreflightReport.fromResults(
            "cls-cp-01", "CLS-TANGO-01", "CHECK_POINT", "ClusterXL",
            List.of(CheckResult.pass("chk1", "Check 1", "Health", EnforcementPolicy.BLOCKING, "OK"))
        );
        DriftEvaluationResult noSnapshotResult = engine.evaluateDrift(baseline, t0Snapshot, reportWithoutSnapshot);
        assertFalse(noSnapshotResult.isPass());
        assertEquals(DriftEvaluationResult.DriftStatus.INSUFFICIENT_EVIDENCE, noSnapshotResult.status());

        // The exact same snapshot object/id presented as both T0 and live evidence must pass this gate.
        DriftEvaluationResult matched = engine.evaluateDrift(baseline, t0Snapshot, t0Report);
        assertEquals(DriftDimensionStatus.MATCH, matched.dimensions().get("evidence_snapshot_identity"));
    }

    @Test
    @DisplayName("FailoverDriftEngine fails closed on missing live software version, policy hash, and counter rollback")
    void driftEngineInsufficientEvidenceTest() {
        FailoverDriftEngine engine = new FailoverDriftEngine();
        Instant now = Instant.now();

        BaselineSnapshotSummary baseline = BaselineSnapshotSummary.of(
            "cls-cp-01", "CHECK_POINT", "ClusterXL",
            "dev-cp-1", "dev-cp-2", "R81.20", "hash-policy-v1",
            5L, "digest-preflight-001", now.minus(Duration.ofHours(2))
        );
        ClusterMemberEvidence standby = new ClusterMemberEvidence(
            "dev-cp-2", "FW-TANGO-02", "STANDBY", "ACTIVE", "ClusterXL",
            "SYNCHRONIZED", 0L, true, 0, true, List.of(),
            true, 0, "R81.20", "hash-policy-v1",
            15, 25, 1000L, 50000L, false, 0, 0, false, true, now
        );

        // 1. Missing live software version -> NOT_EVALUABLE, never MATCH
        ClusterMemberEvidence missingVersionActive = new ClusterMemberEvidence(
            "dev-cp-1", "FW-TANGO-01", "ACTIVE", "STANDBY", "ClusterXL",
            "SYNCHRONIZED", 0L, true, 0, true, List.of(),
            true, 0, "", "hash-policy-v1",
            20, 30, 1000L, 50000L, false, 0, 5, false, true, now
        );
        ClusterEvidenceSnapshot s1 = new ClusterEvidenceSnapshot(
            "snap-1", "cls-cp-01", "CLS-TANGO-01", "CHECK_POINT", "ClusterXL", null, missingVersionActive, standby, now
        );
        PreflightReport r1 = PreflightReport.fromResults(
            "cls-cp-01", "CLS-TANGO-01", "CHECK_POINT", "ClusterXL",
            List.of(CheckResult.pass("chk1", "Check 1", "Health", EnforcementPolicy.BLOCKING, "OK")), s1
        );
        DriftEvaluationResult res1 = engine.evaluateDrift(baseline, s1, r1);
        assertEquals(DriftEvaluationResult.DriftStatus.INSUFFICIENT_EVIDENCE, res1.status());
        assertEquals(FailoverDriftEngine.REASON_INSUFFICIENT_EVIDENCE, res1.typedReasonCodes().get(0));

        // 2. Missing live policy hash -> NOT_EVALUABLE, never MATCH
        ClusterMemberEvidence missingPolicyActive = new ClusterMemberEvidence(
            "dev-cp-1", "FW-TANGO-01", "ACTIVE", "STANDBY", "ClusterXL",
            "SYNCHRONIZED", 0L, true, 0, true, List.of(),
            true, 0, "R81.20", "",
            20, 30, 1000L, 50000L, false, 0, 5, false, true, now
        );
        ClusterEvidenceSnapshot s2 = new ClusterEvidenceSnapshot(
            "snap-2", "cls-cp-01", "CLS-TANGO-01", "CHECK_POINT", "ClusterXL", null, missingPolicyActive, standby, now
        );
        PreflightReport r2 = PreflightReport.fromResults(
            "cls-cp-01", "CLS-TANGO-01", "CHECK_POINT", "ClusterXL",
            List.of(CheckResult.pass("chk1", "Check 1", "Health", EnforcementPolicy.BLOCKING, "OK")), s2
        );
        DriftEvaluationResult res2 = engine.evaluateDrift(baseline, s2, r2);
        assertEquals(DriftEvaluationResult.DriftStatus.INSUFFICIENT_EVIDENCE, res2.status());

        // 3. Live transition counter lower than baseline (reboot/rollover suspected) -> NOT_EVALUABLE
        ClusterMemberEvidence rolledBackActive = new ClusterMemberEvidence(
            "dev-cp-1", "FW-TANGO-01", "ACTIVE", "STANDBY", "ClusterXL",
            "SYNCHRONIZED", 0L, true, 0, true, List.of(),
            true, 0, "R81.20", "hash-policy-v1",
            20, 30, 1000L, 50000L, false, 0, 0, // counter reset to 0, baseline was 5
            false, true, now
        );
        ClusterEvidenceSnapshot s3 = new ClusterEvidenceSnapshot(
            "snap-3", "cls-cp-01", "CLS-TANGO-01", "CHECK_POINT", "ClusterXL", null, rolledBackActive, standby, now
        );
        PreflightReport r3 = PreflightReport.fromResults(
            "cls-cp-01", "CLS-TANGO-01", "CHECK_POINT", "ClusterXL",
            List.of(CheckResult.pass("chk1", "Check 1", "Health", EnforcementPolicy.BLOCKING, "OK")), s3
        );
        DriftEvaluationResult res3 = engine.evaluateDrift(baseline, s3, r3);
        assertEquals(DriftEvaluationResult.DriftStatus.INSUFFICIENT_EVIDENCE, res3.status());
        assertTrue(res3.typedReasonCodes().contains(FailoverDriftEngine.REASON_CLUSTER_FLAP_DETECTED));
    }

    @Test
    @DisplayName("ClockHealthCheck evaluates monotonic alignment and snapshot freshness")
    void clockHealthCheckTest() {
        ClockHealthCheck check = new ClockHealthCheck();
        Instant now = Instant.now();

        // 1. Fresh snapshot -> PASS
        ClusterEvidenceSnapshot fresh = new ClusterEvidenceSnapshot(
            "cls-1", "CLS-1", "CHECK_POINT", "ClusterXL", null,
            null, null, now
        );
        CheckResult r1 = check.evaluate(fresh);
        assertEquals(CheckStatus.PASS, r1.status());

        // 2. Stale snapshot (10 minutes ago) -> FAIL
        ClusterEvidenceSnapshot stale = new ClusterEvidenceSnapshot(
            "cls-1", "CLS-1", "CHECK_POINT", "ClusterXL", null,
            null, null, now.minus(Duration.ofMinutes(10))
        );
        CheckResult r2 = check.evaluate(stale);
        assertEquals(CheckStatus.FAIL, r2.status());

        // 3. Future snapshot -> FAIL
        ClusterEvidenceSnapshot future = new ClusterEvidenceSnapshot(
            "cls-1", "CLS-1", "CHECK_POINT", "ClusterXL", null,
            null, null, now.plus(Duration.ofMinutes(5))
        );
        CheckResult r3 = check.evaluate(future);
        assertEquals(CheckStatus.FAIL, r3.status());
    }
}
