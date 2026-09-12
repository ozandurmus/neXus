package com.securityexpert.nexus.ui2.discovery.cp;

import java.util.List;
import java.util.Optional;

/**
 * Turns one enumeration's {@link RawCandidateInput} list into the returned
 * candidate set: derives each kind (CR-6), resolves each member's cluster
 * link (§5.3) directly from its own reference, then — in a second pass,
 * because HL-1 searches across the whole set — resolves each host link
 * (§5.2). DI-3/DI-4: every input produces exactly one output row; nothing
 * is dropped for being incomplete, unclassifiable or unresolvable.
 */
public final class CandidateRowAssembler {

    private CandidateRowAssembler() {
    }

    public static List<CandidateRow> assemble(List<RawCandidateInput> inputs) {
        List<CandidateRow> withoutHostLink = inputs.stream()
                .map(CandidateRowAssembler::toRowWithoutHostLink)
                .toList();
        return withoutHostLink.stream()
                .map(row -> row.withHostLink(HostLinkResolver.resolve(row, withoutHostLink)))
                .toList();
    }

    private static CandidateRow toRowWithoutHostLink(RawCandidateInput in) {
        CandidateKind kind = CandidateClassifier.classify(in.objectType(), in.flags());
        ClusterLink clusterLink = ClusterLinkResolver.resolve(in.clusterReference());
        return new CandidateRow(in.key(), in.objectType(), kind, in.displayName(), in.ownAddress(),
                in.managementAddress(), in.clusterReference(), in.model(), in.softwareVersion(),
                in.managementPlaneConnectionState(), Optional.empty(), clusterLink);
    }
}
