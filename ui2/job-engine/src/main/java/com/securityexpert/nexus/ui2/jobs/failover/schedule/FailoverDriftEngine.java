package com.securityexpert.nexus.ui2.jobs.failover.schedule;

import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterMemberEvidence;
import com.securityexpert.nexus.ui2.jobs.failover.model.PreflightReport;
import com.securityexpert.nexus.ui2.jobs.failover.model.PreflightVerdict;

import java.util.*;

/**
 * Deterministic, typed drift evaluation engine for scheduled failovers.
 * Evaluates live T₀ JIT evidence against the cryptographically signed baseline snapshot.
 * Invariant: Any non-MATCH on a safety-critical or authorization-critical dimension terminates in ABORT.
 */
public class FailoverDriftEngine {

    // Typed reason codes (sanitized for UI, logging and privacy compliance)
    public static final String REASON_ACTIVE_IDENTITY_CHANGED = "ACTIVE_IDENTITY_CHANGED";
    public static final String REASON_STANDBY_IDENTITY_CHANGED = "STANDBY_IDENTITY_CHANGED";
    public static final String REASON_TOPOLOGY_CHANGED = "CLUSTER_TOPOLOGY_CHANGED";
    public static final String REASON_PREFLIGHT_BLOCKED = "PREFLIGHT_BLOCKING_CONDITIONS_PRESENT";
    public static final String REASON_POLICY_CHANGED = "POLICY_IDENTITY_CHANGED";
    public static final String REASON_SOFTWARE_VERSION_CHANGED = "SOFTWARE_VERSION_CHANGED";
    public static final String REASON_CLUSTER_FLAP_DETECTED = "CLUSTER_TRANSITION_DETECTED";
    public static final String REASON_INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE";

    public DriftEvaluationResult evaluateDrift(
        BaselineSnapshotSummary baseline,
        ClusterEvidenceSnapshot liveSnapshot,
        PreflightReport t0Report
    ) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(liveSnapshot, "liveSnapshot must not be null");
        Objects.requireNonNull(t0Report, "t0Report must not be null");

        Map<String, DriftDimensionStatus> dimensions = new LinkedHashMap<>();
        List<String> reasonCodes = new ArrayList<>();
        List<String> messages = new ArrayList<>();

        // 0. Single Snapshot Invariant (Claude CF-P0.13):
        // The T0 preflight report and the live evidence snapshot presented to this gate must be
        // proven to originate from the exact same evidence-collection pass. A management-plane
        // report evaluated against a different read of the cluster is not corroborating evidence.
        ClusterEvidenceSnapshot t0Snapshot = t0Report.evidenceSnapshot();
        if (t0Snapshot == null || liveSnapshot.snapshotId() == null
            || !liveSnapshot.snapshotId().equals(t0Snapshot.snapshotId())) {
            dimensions.put("evidence_snapshot_identity", DriftDimensionStatus.NOT_EVALUABLE);
            return DriftEvaluationResult.insufficientEvidence(
                dimensions, REASON_INSUFFICIENT_EVIDENCE,
                "T0 pre-flight report and live evidence snapshot could not be corroborated as the same evidence-collection pass",
                t0Report
            );
        }
        dimensions.put("evidence_snapshot_identity", DriftDimensionStatus.MATCH);

        // 1. Preflight Report Verdict Check
        if (t0Report.overallVerdict() != PreflightVerdict.NO_BLOCKING_CONDITIONS_OBSERVED) {
            dimensions.put("preflight_readiness", DriftDimensionStatus.MISMATCH);
            reasonCodes.add(REASON_PREFLIGHT_BLOCKED);
            messages.add("T₀ pre-flight check battery observed " + t0Report.blockingFailureCount() + " blocking failure(s)");
            return DriftEvaluationResult.blocked(dimensions, reasonCodes, messages, t0Report);
        }
        dimensions.put("preflight_readiness", DriftDimensionStatus.MATCH);

        // 2. Active Member Identity Check (Signed Mutation Target)
        Optional<ClusterMemberEvidence> activeOpt = liveSnapshot.activeMember();
        if (activeOpt.isEmpty()) {
            dimensions.put("active_member_identity", DriftDimensionStatus.NOT_EVALUABLE);
            return DriftEvaluationResult.insufficientEvidence(
                dimensions, REASON_INSUFFICIENT_EVIDENCE, "Live snapshot active member identity could not be affirmatively corroborated", t0Report
            );
        }

        ClusterMemberEvidence liveActive = activeOpt.get();
        if (!baseline.activeMemberId().equals(liveActive.memberId())) {
            dimensions.put("active_member_identity", DriftDimensionStatus.MISMATCH);
            reasonCodes.add(REASON_ACTIVE_IDENTITY_CHANGED);
            messages.add("Active member role drifted from baseline active member (signed mutation target mismatch)");
        } else {
            dimensions.put("active_member_identity", DriftDimensionStatus.MATCH);
        }

        // 3. Standby Member Identity Check
        Optional<ClusterMemberEvidence> standbyOpt = liveSnapshot.standbyMember();
        if (standbyOpt.isEmpty()) {
            dimensions.put("standby_member_identity", DriftDimensionStatus.NOT_EVALUABLE);
            return DriftEvaluationResult.insufficientEvidence(
                dimensions, REASON_INSUFFICIENT_EVIDENCE, "Live snapshot standby member identity could not be affirmatively corroborated", t0Report
            );
        }

        ClusterMemberEvidence liveStandby = standbyOpt.get();
        if (!baseline.standbyMemberId().equals(liveStandby.memberId())) {
            dimensions.put("standby_member_identity", DriftDimensionStatus.MISMATCH);
            reasonCodes.add(REASON_STANDBY_IDENTITY_CHANGED);
            messages.add("Standby member identity drifted from authorized baseline standby member");
        } else {
            dimensions.put("standby_member_identity", DriftDimensionStatus.MATCH);
        }

        // 4. Cluster Topology & Mode Check
        boolean liveTopologyVerifiable = liveSnapshot.vendor() != null && !liveSnapshot.vendor().isBlank()
            && liveSnapshot.haMode() != null && !liveSnapshot.haMode().isBlank();
        if (!liveTopologyVerifiable) {
            dimensions.put("cluster_topology", DriftDimensionStatus.NOT_EVALUABLE);
            return DriftEvaluationResult.insufficientEvidence(
                dimensions, REASON_INSUFFICIENT_EVIDENCE,
                "Live cluster topology (vendor/HA mode) could not be affirmatively verified", t0Report
            );
        }

        boolean vendorMatch = baseline.vendor().equalsIgnoreCase(liveSnapshot.vendor());
        boolean haModeMatch = baseline.haMode() == null || baseline.haMode().equalsIgnoreCase(liveSnapshot.haMode());
        if (!vendorMatch || !haModeMatch) {
            dimensions.put("cluster_topology", DriftDimensionStatus.MISMATCH);
            reasonCodes.add(REASON_TOPOLOGY_CHANGED);
            messages.add("Cluster vendor or HA clustering mode drifted from authorized baseline");
        } else {
            dimensions.put("cluster_topology", DriftDimensionStatus.MATCH);
        }

        // 5. Software Take / Version Check
        if (baseline.softwareVersion() != null && !baseline.softwareVersion().isBlank()) {
            String liveVersion = liveActive.softwareVersion();
            if (liveVersion == null || liveVersion.isBlank()) {
                dimensions.put("software_version", DriftDimensionStatus.NOT_EVALUABLE);
                return DriftEvaluationResult.insufficientEvidence(
                    dimensions, REASON_INSUFFICIENT_EVIDENCE,
                    "Baseline recorded a software version but live active member software version is missing", t0Report
                );
            }
            if (!baseline.softwareVersion().equals(liveVersion)) {
                dimensions.put("software_version", DriftDimensionStatus.MISMATCH);
                reasonCodes.add(REASON_SOFTWARE_VERSION_CHANGED);
                messages.add("Software version or hotfix take drifted from authorized baseline");
            } else {
                dimensions.put("software_version", DriftDimensionStatus.MATCH);
            }
        } else {
            dimensions.put("software_version", DriftDimensionStatus.MATCH);
        }

        // 6. Security Policy Identity Check
        if (baseline.policyHash() != null && !baseline.policyHash().isBlank()) {
            String livePolicyHash = liveActive.installedPolicyHash();
            if (livePolicyHash == null || livePolicyHash.isBlank()) {
                dimensions.put("installed_policy", DriftDimensionStatus.NOT_EVALUABLE);
                return DriftEvaluationResult.insufficientEvidence(
                    dimensions, REASON_INSUFFICIENT_EVIDENCE,
                    "Baseline recorded an installed policy hash but live active member policy hash is missing", t0Report
                );
            }
            if (!baseline.policyHash().equals(livePolicyHash)) {
                dimensions.put("installed_policy", DriftDimensionStatus.MISMATCH);
                reasonCodes.add(REASON_POLICY_CHANGED);
                messages.add("Installed security policy hash drifted from authorized baseline");
            } else {
                dimensions.put("installed_policy", DriftDimensionStatus.MATCH);
            }
        } else {
            dimensions.put("installed_policy", DriftDimensionStatus.MATCH);
        }

        // 7. Flap / Transition Counter Check
        long liveTransitions = liveActive.flapCountLast24Hours();
        if (liveTransitions < baseline.transitionCounter()) {
            // A live counter lower than the baseline indicates a reboot or counter rollover
            // since authorization was granted; the baseline transition history can no longer
            // be trusted as continuous evidence.
            dimensions.put("flap_history", DriftDimensionStatus.NOT_EVALUABLE);
            return DriftEvaluationResult.insufficientEvidence(
                dimensions, REASON_CLUSTER_FLAP_DETECTED,
                "Live transition counter is lower than the authorized baseline (member reboot or counter rollover suspected)",
                t0Report
            );
        } else if (liveTransitions > baseline.transitionCounter()) {
            dimensions.put("flap_history", DriftDimensionStatus.MISMATCH);
            reasonCodes.add(REASON_CLUSTER_FLAP_DETECTED);
            messages.add("Cluster role transitions observed between authorization baseline and T₀ window (" +
                (liveTransitions - baseline.transitionCounter()) + " new transition(s))");
        } else {
            dimensions.put("flap_history", DriftDimensionStatus.MATCH);
        }

        if (!reasonCodes.isEmpty()) {
            return DriftEvaluationResult.drift(dimensions, reasonCodes, messages, t0Report);
        }

        return DriftEvaluationResult.pass(dimensions, t0Report);
    }
}
