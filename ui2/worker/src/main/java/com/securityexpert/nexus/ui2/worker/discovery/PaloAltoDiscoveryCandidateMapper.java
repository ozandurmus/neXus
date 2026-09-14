package com.securityexpert.nexus.ui2.worker.discovery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.discovery.pan.CandidateRow;
import com.securityexpert.nexus.ui2.discovery.pan.PairingOutcome;
import com.securityexpert.nexus.ui2.platform.OpaqueId;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryCandidateRecord;

/**
 * Maps the Palo Alto discovery domain's {@link CandidateRow} set to {@link
 * DiscoveryCandidateRecord} rows, per
 * DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md §2.2's row-shape table (IM-4).
 * PAN-DISCOVERY defines no kind lattice (CL-1): {@link #KIND_DEVICE} and
 * {@link #KIND_VIRTUAL_SYSTEM} are this movement's own local discriminator
 * between a physical entry and a nested virtual-system entry, not a vendor
 * kind. A reciprocal HA pair (HA-1) shares one {@code cluster_reference}
 * value -- both sides compute it independently as the two stable
 * identifiers sorted (name-blind, address-blind, ID-1: never a display
 * name or address) -- there being no cluster *object* on this vendor the
 * way Check Point has one (contrast {@code
 * CheckPointDiscoveryCandidateMapper}'s {@code parent_candidate_id} use for
 * a real cluster row).
 */
public final class PaloAltoDiscoveryCandidateMapper {

    static final String KIND_DEVICE = "PALO_ALTO_DEVICE";
    static final String KIND_VIRTUAL_SYSTEM = "PALO_ALTO_VIRTUAL_SYSTEM";

    private PaloAltoDiscoveryCandidateMapper() {
    }

    public static List<DiscoveryCandidateRecord> map(String runId, List<CandidateRow> rows) {
        Map<String, String> candidateIdBySerial = new HashMap<>();
        for (CandidateRow row : rows) {
            row.stableIdentifier().value().ifPresent(serial -> candidateIdBySerial.put(serial, OpaqueId.random().value()));
        }
        Map<CandidateRow, String> candidateIdByRow = new IdentityHashMap<>();
        for (CandidateRow row : rows) {
            String id = row.stableIdentifier().value().map(candidateIdBySerial::get).orElseGet(() -> OpaqueId.random().value());
            candidateIdByRow.put(row, id);
        }

        List<DiscoveryCandidateRecord> out = new ArrayList<>(rows.size());
        for (CandidateRow row : rows) {
            boolean isVirtualSystem = row.hostLink().isPresent();
            String parentCandidateId = isVirtualSystem
                    ? row.hostLink().get().hostStableIdentifier().value().map(candidateIdBySerial::get).orElse(null)
                    : null;
            String clusterReference = !isVirtualSystem ? pairedClusterReference(row) : null;

            out.add(new DiscoveryCandidateRecord(
                    candidateIdByRow.get(row),
                    runId,
                    "palo_alto",
                    row.stableIdentifier().value().orElse(""),
                    Optional.empty(),
                    isVirtualSystem ? KIND_VIRTUAL_SYSTEM : KIND_DEVICE,
                    row.displayName(),
                    ownAddress(row),
                    Optional.empty(),
                    Optional.ofNullable(clusterReference),
                    Optional.ofNullable(parentCandidateId),
                    Optional.empty(),
                    Optional.empty(),
                    row.connectionState(),
                    isVirtualSystem ? parentCandidateId != null : true,
                    Optional.empty()));
        }
        return out;
    }

    /** Counts by the local kind token (device/virtual-system) -- PR-3: counts and shapes only. */
    public static Map<String, Integer> outcomeSummary(List<CandidateRow> rows) {
        Map<String, Integer> counts = new HashMap<>();
        for (CandidateRow row : rows) {
            counts.merge(row.hostLink().isPresent() ? KIND_VIRTUAL_SYSTEM : KIND_DEVICE, 1, Integer::sum);
        }
        return counts;
    }

    /** IM-7: whichever of the IPv4/IPv6 elements this movement's own field binding names -- IPv4 preferred, IPv6 the fallback. */
    private static Optional<String> ownAddress(CandidateRow row) {
        return row.ownIpv4Address().isPresent() ? row.ownIpv4Address() : row.ownIpv6Address();
    }

    /** HA-1 reciprocity only: a NOT_EVALUABLE outcome (including a one-sided claim) never forms a grouping (IM-6). */
    private static String pairedClusterReference(CandidateRow row) {
        if (!(row.pairingOutcome() instanceof PairingOutcome.Paired paired)) {
            return null;
        }
        String self = row.stableIdentifier().value().orElse(null);
        String peer = paired.peerStableIdentifier();
        if (self == null || peer == null) {
            return null;
        }
        return self.compareTo(peer) <= 0 ? self + "|" + peer : peer + "|" + self;
    }
}
