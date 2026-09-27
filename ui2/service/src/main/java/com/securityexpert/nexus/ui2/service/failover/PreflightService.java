package com.securityexpert.nexus.ui2.service.failover;

import com.securityexpert.nexus.ui2.jobs.failover.model.*;
import com.securityexpert.nexus.ui2.jobs.failover.spi.PreflightRegistry;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates read-only pre-flight readiness checks across estate clusters.
 * Enforces in-memory caching with a 5-minute display TTL and guarantees
 * strict AIView deterministic pseudonymization of all cluster and device identities.
 */
@Service
public class PreflightService {

    private static final Duration REPORT_DISPLAY_TTL = Duration.ofMinutes(5);
    private static final Set<String> UNMEASURED_INVENTORY_CHECKS = Set.of(
        "preflight.platform_mode_gate",
        "preflight.state_sync_current",
        "preflight.policy_parity",
        "preflight.control_sync_link_health",
        "preflight.checkpoint_pnotes",
        "preflight.paloalto_path_monitoring",
        "preflight.standby_resource_headroom",
        "preflight.preemption_awareness",
        "preflight.flap_history",
        "preflight.paloalto_pending_commits"
    );

    private final PreflightRegistry preflightRegistry;
    private final DeviceRepository deviceRepository;
    private final DeviceInventoryRepository inventoryRepository;
    private final TopologyNamePseudonymizer pseudonymizer;

    private final Map<String, CachedReport> reportCache = new ConcurrentHashMap<>();

    private record CachedReport(PreflightReport report, Instant cachedAt) {
        boolean isExpired() {
            return Instant.now().isAfter(cachedAt.plus(REPORT_DISPLAY_TTL));
        }
    }

    public PreflightService(
        DeviceRepository deviceRepository,
        DeviceInventoryRepository inventoryRepository,
        TopologyNamePseudonymizer pseudonymizer
    ) {
        this.preflightRegistry = new PreflightRegistry();
        this.deviceRepository = deviceRepository;
        this.inventoryRepository = inventoryRepository;
        this.pseudonymizer = pseudonymizer;
    }

    public PreflightReport getLatestReport(String clusterRef) {
        CachedReport cached = reportCache.get(clusterRef);
        if (cached != null && !cached.isExpired()) {
            return cached.report();
        }
        return evaluateCluster(clusterRef);
    }

    public PreflightReport evaluateCluster(String clusterRef) {
        ClusterEvidenceSnapshot snapshot = buildSnapshotForCluster(clusterRef);
        PreflightReport report = evaluateSnapshot(snapshot);
        reportCache.put(clusterRef, new CachedReport(report, Instant.now()));
        return report;
    }

    /**
     * Evaluates the pre-flight battery against an already-captured snapshot rather than
     * building a new one. Callers that must prove a T0 report and a separately-held live
     * snapshot originate from the exact same evidence-collection pass (not merely the same
     * cluster) must use this method with that live snapshot, never {@link #getLatestReport}.
     */
    public PreflightReport evaluateSnapshot(ClusterEvidenceSnapshot snapshot) {
        PreflightReport report = preflightRegistry.evaluateAll(snapshot);
        // Inventory records carry roles and versions, but none of the readiness facts below.
        // Primitive evidence fields have no null representation; -1 marks this inventory projection.
        boolean inventoryProjection = snapshot.memberA() != null && snapshot.memberA().syncQueueDelta() == -1;
        boolean missingObservationTime = Instant.EPOCH.equals(snapshot.snapshotTimestamp());
        if (!inventoryProjection && !missingObservationTime) {
            return report;
        }
        List<CheckResult> checks = report.checks().stream().map(result -> {
            boolean unmeasured = (inventoryProjection && UNMEASURED_INVENTORY_CHECKS.contains(result.checkId()))
                || (missingObservationTime && "preflight.clock_health".equals(result.checkId()));
            if (!unmeasured
                || result.status() == CheckStatus.INSUFFICIENT_EVIDENCE
                || result.status() == CheckStatus.COLLECTION_FAILED
                || ("preflight.policy_parity".equals(result.checkId()) && result.status() == CheckStatus.FAIL)) {
                return result;
            }
            return CheckResult.insufficientEvidence(result.checkId(), result.name(), result.category(),
                result.enforcement(), "Required readiness facts were not observed in inventory.",
                "REMEDIATE_COLLECT_READINESS_EVIDENCE");
        }).toList();
        return PreflightReport.fromResults(report.clusterId(), report.maskedClusterName(), report.vendor(),
            report.haMode(), checks, snapshot);
    }

    public List<PreflightCheckSummary> listRegisteredChecks() {
        return preflightRegistry.getRegisteredChecks().stream()
            .map(c -> new PreflightCheckSummary(
                c.id(),
                c.name(),
                c.category(),
                c.defaultPolicy().name()
            ))
            .toList();
    }

    public record PreflightCheckSummary(
        String id,
        String name,
        String category,
        String defaultPolicy
    ) {}

    public ClusterEvidenceSnapshot buildSnapshotForCluster(String clusterRef) {
        String maskedClusterName = pseudonymizer != null
            ? pseudonymizer.maskClusterName(clusterRef)
            : clusterRef;

        List<DeviceSummaryRecord> members = deviceRepository != null
            ? deviceRepository.findMembersByClusterRef(clusterRef)
            : List.of();

        if (members.size() >= 2) {
            DeviceSummaryRecord memA = members.get(0);
            DeviceSummaryRecord memB = members.get(1);

            Optional<InventoryRun> runA = inventoryRepository != null
                ? inventoryRepository.findLatestRun(memA.deviceId())
                : Optional.empty();
            Optional<InventoryRun> runB = inventoryRepository != null
                ? inventoryRepository.findLatestRun(memB.deviceId())
                : Optional.empty();

            ClusterMemberEvidence evA = extractMemberEvidence(memA, runA, clusterRef);
            ClusterMemberEvidence evB = extractMemberEvidence(memB, runB, clusterRef);

            String vendor = memA.vendorHint() != null ? memA.vendorHint().toUpperCase(Locale.ROOT) : "UNKNOWN";
            String haMode = haModeForVendor(vendor);
            Instant oldestObservation = runA.map(InventoryRun::collectedAt).orElse(Instant.EPOCH);
            Instant peerObservation = runB.map(InventoryRun::collectedAt).orElse(Instant.EPOCH);

            return new ClusterEvidenceSnapshot(
                clusterRef,
                maskedClusterName,
                vendor,
                haMode,
                null,
                evA,
                evB,
                oldestObservation.isBefore(peerObservation) ? oldestObservation : peerObservation
            );
        }

        // C-1 Fail-Closed Remediation:
        // Do NOT fabricate synthetic healthy nodes when fewer than 2 enrolled members exist.
        // Return actual evidence (or null members) so checks evaluate to INSUFFICIENT_EVIDENCE -> BLOCKING_CONDITIONS_PRESENT.
        String vendor = "UNKNOWN";
        Instant observedAt = Instant.EPOCH;

        ClusterMemberEvidence evA = null;
        if (!members.isEmpty()) {
            DeviceSummaryRecord memA = members.get(0);
            Optional<InventoryRun> runA = inventoryRepository != null
                ? inventoryRepository.findLatestRun(memA.deviceId())
                : Optional.empty();
            evA = extractMemberEvidence(memA, runA, clusterRef);
            if (memA.vendorHint() != null) {
                vendor = memA.vendorHint().toUpperCase(Locale.ROOT);
            }
            observedAt = runA.map(InventoryRun::collectedAt).orElse(Instant.EPOCH);
        }

        return new ClusterEvidenceSnapshot(
            clusterRef,
            maskedClusterName,
            vendor,
            haModeForVendor(vendor),
            null,
            evA,
            null,
            observedAt
        );
    }

    private ClusterMemberEvidence extractMemberEvidence(
        DeviceSummaryRecord member,
        Optional<InventoryRun> run,
        String clusterRef
    ) {
        String rawHostname = member.observedHostname().orElse(member.deviceId());
        String maskedName = pseudonymizer != null
            ? pseudonymizer.maskDeviceName(rawHostname, clusterRef)
            : rawHostname;

        boolean observed = run.isPresent();
        
        // C-2 Remediation: Strictly derive role from runtime evidence or observedHaRole, never list order
        String selfState = "UNKNOWN";
        if (run.isPresent()) {
            for (InventoryHaFact fact : run.get().haFacts()) {
                if (fact.role() != null && !fact.role().isBlank()) {
                    selfState = normalizeHaRole(fact.role());
                    break;
                }
            }
        }
        if ("UNKNOWN".equals(selfState) && member.observedHaRole().isPresent()) {
            selfState = normalizeHaRole(member.observedHaRole().get());
        }

        // Peer state is UNKNOWN from a single member's perspective until independently corroborated
        String peerState = "UNKNOWN";

        String version = member.observedSoftwareVersion().orElse(null);

        return new ClusterMemberEvidence(
            member.deviceId(),
            maskedName,
            selfState,
            peerState,
            haModeForVendor(member.vendorHint()),
            "UNKNOWN",
            -1,
            false,
            -1,
            false,
            List.of(),
            false,
            -1,
            version,
            null,
            -1,
            -1,
            -1,
            -1,
            false,
            -1,
            -1,
            false,
            observed,
            observed ? run.get().collectedAt() : Instant.EPOCH
        );
    }

    private String haModeForVendor(String vendor) {
        if ("CHECK_POINT".equalsIgnoreCase(vendor) || "CHECKPOINT".equalsIgnoreCase(vendor)) {
            return "CLUSTER_XL_HA";
        }
        if ("PALO_ALTO".equalsIgnoreCase(vendor) || "PAN_OS".equalsIgnoreCase(vendor)) {
            return "PAN_ACTIVE_PASSIVE";
        }
        return "UNKNOWN";
    }

    private String normalizeHaRole(String rawRole) {
        if (rawRole == null) return "UNKNOWN";
        String r = rawRole.trim().toUpperCase();
        if (r.contains("ACTIVE") || r.contains("PRIMARY")) {
            return "ACTIVE";
        }
        if (r.contains("STANDBY") || r.contains("PASSIVE") || r.contains("SECONDARY")) {
            return "STANDBY";
        }
        return r;
    }
}
