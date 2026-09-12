package com.securityexpert.nexus.ui2.discovery.cp;

import java.util.List;

/**
 * Contract §4.3 classification. CL-1: kind is decided from object type and
 * the three flags of {@link ClassificationFlags} — from nothing else; no
 * display name, label, ordinal, model string, version string or address is
 * ever consulted. The ten rows of §4.2 are held as data, matched by
 * counting rather than by an if/else preference chain, so that CL-2's
 * zero-match and multi-match outcomes fall out of the match count itself
 * rather than from an order a maintainer could accidentally encode.
 */
public final class CandidateClassifier {

    private record Row(ObjectType objectType, ClassificationFlags flags, CandidateKind kind) {
    }

    private static final List<Row> ROWS = List.of(
            new Row(ObjectType.GATEWAY, new ClassificationFlags(true, false, false), CandidateKind.STANDALONE_PRODUCT_GATEWAY),
            new Row(ObjectType.GATEWAY, new ClassificationFlags(false, false, false), CandidateKind.NON_PRODUCT_INTEROPERABLE_DEVICE),
            new Row(ObjectType.GATEWAY, new ClassificationFlags(true, true, false), CandidateKind.STANDALONE_VIRTUALIZATION_HOST),
            new Row(ObjectType.GATEWAY, new ClassificationFlags(true, false, true), CandidateKind.STANDALONE_VIRTUAL_SYSTEM),
            new Row(ObjectType.CLUSTER, new ClassificationFlags(true, true, false), CandidateKind.VIRTUALIZATION_CLUSTER),
            new Row(ObjectType.CLUSTER, new ClassificationFlags(true, false, true), CandidateKind.VIRTUAL_SYSTEM_CLUSTER),
            new Row(ObjectType.CLUSTER, new ClassificationFlags(true, false, false), CandidateKind.PLAIN_HIGH_AVAILABILITY_CLUSTER),
            new Row(ObjectType.MEMBER, new ClassificationFlags(true, true, false), CandidateKind.PHYSICAL_VIRTUALIZATION_CHASSIS_MEMBER),
            new Row(ObjectType.MEMBER, new ClassificationFlags(true, false, true), CandidateKind.VIRTUAL_SYSTEM_MEMBER),
            new Row(ObjectType.MEMBER, new ClassificationFlags(true, false, false), CandidateKind.PLAIN_CLUSTER_MEMBER));

    private CandidateClassifier() {
    }

    /** CL-1/CL-2/CL-3/CL-4. */
    public static CandidateKind classify(ObjectType objectType, ClassificationFlags flags) {
        List<CandidateKind> matches = ROWS.stream()
                .filter(row -> row.objectType() == objectType && row.flags().equals(flags))
                .map(Row::kind)
                .toList();
        if (matches.isEmpty()) {
            return CandidateKind.UNCLASSIFIED;
        }
        if (matches.size() > 1) {
            return CandidateKind.AMBIGUOUS;
        }
        return matches.get(0);
    }
}
