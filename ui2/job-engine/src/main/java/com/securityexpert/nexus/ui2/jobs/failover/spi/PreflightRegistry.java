package com.securityexpert.nexus.ui2.jobs.failover.spi;

import com.securityexpert.nexus.ui2.jobs.failover.checks.*;
import com.securityexpert.nexus.ui2.jobs.failover.model.*;

import java.time.Instant;
import java.util.*;

/**
 * Closed, immutable registry and execution coordinator for failover pre-flight checks.
 * Enforces per-vendor required check manifests to guarantee coverage fail-closed invariants.
 */
public class PreflightRegistry {

    private final Map<String, PreflightCheck> checksById;
    private final Map<String, Set<String>> requiredManifestByVendor;

    public PreflightRegistry() {
        this.checksById = new LinkedHashMap<>();
        this.requiredManifestByVendor = new HashMap<>();

        // Register built-in checks
        register(new PlatformAndModeGateCheck());
        register(new ViableTargetCheck());
        register(new TwoSidedSplitBrainCheck());
        register(new StateSyncCurrentCheck());
        register(new PolicyParityCheck());
        register(new ControlSyncLinkHealthCheck());
        register(new CriticalDevicesPnotesCheck());
        register(new PathMonitoringCheck());
        register(new StandbyResourceHeadroomCheck());
        register(new PreemptionAwarenessCheck());
        register(new FlapHistoryCheck());
        register(new PendingCommitCheck());
        register(new ClockHealthCheck());

        // Define Required Check Manifests
        Set<String> cpRequired = Set.of(
            "preflight.platform_mode_gate",
            "preflight.viable_target",
            "preflight.split_brain_prevention",
            "preflight.state_sync_current",
            "preflight.policy_parity",
            "preflight.control_sync_link_health",
            "preflight.checkpoint_pnotes",
            "preflight.standby_resource_headroom",
            "preflight.preemption_awareness",
            "preflight.flap_history",
            "preflight.clock_health"
        );
        requiredManifestByVendor.put("CHECK_POINT", cpRequired);
        requiredManifestByVendor.put("CHECKPOINT", cpRequired);

        Set<String> panRequired = Set.of(
            "preflight.platform_mode_gate",
            "preflight.viable_target",
            "preflight.split_brain_prevention",
            "preflight.state_sync_current",
            "preflight.policy_parity",
            "preflight.control_sync_link_health",
            "preflight.paloalto_path_monitoring",
            "preflight.paloalto_pending_commits",
            "preflight.standby_resource_headroom",
            "preflight.preemption_awareness",
            "preflight.flap_history",
            "preflight.clock_health"
        );
        requiredManifestByVendor.put("PALO_ALTO", panRequired);
        requiredManifestByVendor.put("PAN_OS", panRequired);
    }

    private void register(PreflightCheck check) {
        if (checksById.containsKey(check.id())) {
            throw new IllegalStateException("Duplicate check ID detected: " + check.id());
        }
        checksById.put(check.id(), check);
    }

    public List<PreflightCheck> getRegisteredChecks() {
        return List.copyOf(checksById.values());
    }

    public Set<String> getRequiredCheckIdsForVendor(String vendor) {
        if (vendor == null) {
            return Set.of();
        }
        return requiredManifestByVendor.getOrDefault(vendor.toUpperCase(), Set.of());
    }

    /**
     * Executes the pre-flight battery against a corroborated cluster evidence snapshot.
     * Enforces the required-check manifest and guarantees fail-closed behavior on coverage shortfall or error.
     */
    public PreflightReport evaluateAll(ClusterEvidenceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        List<CheckResult> results = new ArrayList<>();
        Set<String> executedCheckIds = new HashSet<>();
        String vendor = snapshot.vendor();
        String haMode = snapshot.haMode();

        for (PreflightCheck check : checksById.values()) {
            if (check.appliesTo(vendor, haMode)) {
                try {
                    CheckResult result = check.evaluate(snapshot);
                    results.add(result);
                    executedCheckIds.add(check.id());
                } catch (Exception ex) {
                    // Fail closed on exception
                    results.add(new CheckResult(
                        check.id(),
                        check.name(),
                        check.category(),
                        CheckStatus.COLLECTION_FAILED,
                        check.defaultPolicy(),
                        "Check execution threw unexpected exception: " + ex.getClass().getSimpleName(),
                        "REMEDIATE_CHECK_EXECUTION_FAILURE",
                        Instant.now()
                    ));
                    executedCheckIds.add(check.id());
                }
            }
        }

        // Verify required manifest coverage
        Set<String> required = getRequiredCheckIdsForVendor(vendor);
        if (required.isEmpty()) {
            // Unknown or uncontracted vendor -> Fail closed
            results.add(CheckResult.unsupported(
                "preflight.vendor_validation",
                "Vendor Validation & Support",
                "Topology & Identity",
                "Vendor '" + vendor + "' is not supported or unrecognized. Engine fails closed.",
                "REMEDIATE_UNSUPPORTED_VENDOR"
            ));
        } else {
            for (String reqId : required) {
                if (!executedCheckIds.contains(reqId)) {
                    // Coverage shortfall -> Inject blocking failure
                    results.add(new CheckResult(
                        reqId,
                        "Missing Required Check: " + reqId,
                        "Coverage & Manifest",
                        CheckStatus.INSUFFICIENT_EVIDENCE,
                        EnforcementPolicy.BLOCKING,
                        "Mandatory pre-flight check " + reqId + " was not executed. Engine fails closed on coverage shortfall.",
                        "REMEDIATE_ENFORCE_MANIFEST_COVERAGE",
                        Instant.now()
                    ));
                }
            }
        }

        return PreflightReport.fromResults(
            snapshot.clusterId(),
            snapshot.maskedClusterName(),
            snapshot.vendor(),
            snapshot.haMode(),
            results,
            snapshot
        );
    }
}
