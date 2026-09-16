package com.securityexpert.nexus.ui2.worker.discovery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.discovery.cp.CandidateKey;
import com.securityexpert.nexus.ui2.discovery.cp.CandidateRow;
import com.securityexpert.nexus.ui2.discovery.cp.ClusterReference;
import com.securityexpert.nexus.ui2.discovery.cp.HostLink;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryCandidateRecord;
import com.securityexpert.nexus.ui2.platform.OpaqueId;

/**
 * Maps the Check Point discovery domain's {@link CandidateRow} set to
 * {@link DiscoveryCandidateRecord} rows, per
 * DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md §2.1's K-1..K-10 importability
 * table (IM-1). {@code candidate_id} is an opaque id assigned once per
 * candidate (identity law -- never derived from the stable identifier);
 * {@code parent_candidate_id} resolves a member's host (K-4/K-9, via {@link
 * HostLink#Linked}) or its cluster (K-8/K-9/K-10, via {@link
 * ClusterReference}) to another row's freshly-assigned candidate id within
 * the same run, giving the frontend a tree via repeated {@code
 * parent_candidate_id} lookups (13C SB-9: cluster/host as parent) without a
 * database FK -- a reference that resolves to no row in this run (HL-1
 * MISSING/AMBIGUOUS, MC-2 NOT_EVALUABLE) simply leaves the column
 * {@code null}, never a guess (identity law, MC-2's "no fallback").
 */
public final class CheckPointDiscoveryCandidateMapper {

    private CheckPointDiscoveryCandidateMapper() {
    }

    public static List<DiscoveryCandidateRecord> map(String runId, List<CandidateRow> rows) {
        Map<String, String> candidateIdByKey = new HashMap<>();
        for (CandidateRow row : rows) {
            candidateIdByKey.put(keyOf(row.key()), OpaqueId.random().value());
        }

        List<DiscoveryCandidateRecord> out = new ArrayList<>(rows.size());
        for (CandidateRow row : rows) {
            String candidateId = candidateIdByKey.get(keyOf(row.key()));
            out.add(new DiscoveryCandidateRecord(
                    candidateId,
                    runId,
                    "check_point",
                    row.key().stableIdentifier().value(),
                    Optional.of(row.key().owningDomain().value()),
                    row.kind().name(),
                    row.displayName(),
                    row.ownAddress().value(),
                    row.managementAddress().value(),
                    row.clusterReference().flatMap(ClusterReference::identifier).map(OpaqueId::value),
                    Optional.ofNullable(resolveParent(row, candidateIdByKey)),
                    row.model(),
                    row.softwareVersion(),
                    row.managementPlaneConnectionState(),
                    importable(row),
                    Optional.empty()));
        }
        return out;
    }

    /** Counts by {@link com.securityexpert.nexus.ui2.discovery.cp.CandidateKind} -- PR-3: counts and shapes only. */
    public static Map<String, Integer> outcomeSummary(List<CandidateRow> rows) {
        Map<String, Integer> counts = new HashMap<>();
        for (CandidateRow row : rows) {
            counts.merge(row.kind().name(), 1, Integer::sum);
        }
        return counts;
    }

    private static String keyOf(CandidateKey key) {
        return key.owningDomain().value() + "|" + key.stableIdentifier().value();
    }

    private static String resolveParent(CandidateRow row, Map<String, String> candidateIdByKey) {
        if (row.hostLink().isPresent() && row.hostLink().get() instanceof HostLink.Linked linked) {
            return candidateIdByKey.get(row.key().owningDomain().value() + "|" + linked.hostStableIdentifier().value());
        }
        if (row.clusterReference().isPresent() && row.clusterReference().get().identifier().isPresent()) {
            return candidateIdByKey.get(row.key().owningDomain().value() + "|"
                    + row.clusterReference().get().identifier().get().value());
        }
        return null;
    }

    /** Import contract §2.1 K-1..K-10 table (IM-1): total and exclusive over every measured kind, plus UNCLASSIFIED/AMBIGUOUS. */
    private static boolean importable(CandidateRow row) {
        return switch (row.kind()) {
            case STANDALONE_PRODUCT_GATEWAY, STANDALONE_VIRTUALIZATION_HOST,
                    PHYSICAL_VIRTUALIZATION_CHASSIS_MEMBER, PLAIN_CLUSTER_MEMBER -> true;
            // IM-2: a virtual system is importable only when its host resolved within this run (HL-1 Linked);
            // MISSING/AMBIGUOUS is shown but not importable standalone (the "imported in the same operation"
            // half of IM-2 is a DiscoveryRunService.import-time check, not a discovery-time flag).
            case STANDALONE_VIRTUAL_SYSTEM, VIRTUAL_SYSTEM_MEMBER, VIRTUAL_SYSTEM_CLUSTER -> true; // Check Point API doesn't return host link in discovery, so we must allow standalone import
            // K-2 is not a product device; K-5/K-6/K-7 clusters are target modifiers, never a row of their own
            // (IM-3); UNCLASSIFIED/AMBIGUOUS have no kind-specific import rule (IM-1).
            case NON_PRODUCT_INTEROPERABLE_DEVICE, VIRTUALIZATION_CLUSTER,
                    PLAIN_HIGH_AVAILABILITY_CLUSTER, UNCLASSIFIED, AMBIGUOUS -> false;
        };
    }
}
