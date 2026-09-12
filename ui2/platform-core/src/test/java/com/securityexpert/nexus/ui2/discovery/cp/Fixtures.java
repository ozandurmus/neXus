package com.securityexpert.nexus.ui2.discovery.cp;

import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.OpaqueId;

/**
 * Synthetic fixtures shared by the §9 property-check tests (checks 8, 10,
 * 12, 13). No real address, object name, domain name or identifier appears
 * here — every value is invented for this test source.
 */
final class Fixtures {

    private Fixtures() {
    }

    static final OpaqueId DOMAIN_A = OpaqueId.of("fixture-domain-a");
    static final OpaqueId DOMAIN_B = OpaqueId.of("fixture-domain-b");

    private static CandidateKey key(OpaqueId domain, String id) {
        return new CandidateKey(domain, OpaqueId.of(id));
    }

    /**
     * One domain's worth of raw inputs, covering every kind, a host/virtual-
     * system pair, a cluster/member pair, a MISSING host, an AMBIGUOUS host,
     * a member with an absent cluster reference and one with a reference
     * that carries a display name but no identifier.
     */
    static List<RawCandidateInput> domainAInputs() {
        return List.of(
                // K-1 standalone product gateway: a physical device (HR-1).
                new RawCandidateInput(key(DOMAIN_A, "gw-1"), ObjectType.GATEWAY,
                        new ClassificationFlags(true, false, false), "fixture-gateway-one",
                        Address.of("198.51.100.1"), Address.of("198.51.100.1"),
                        Optional.empty(), Optional.of("fixture-model-1"), Optional.of("1.0"), Optional.empty()),
                // K-2 non-product interoperable device: no management address (HR-3).
                new RawCandidateInput(key(DOMAIN_A, "interop-1"), ObjectType.GATEWAY,
                        new ClassificationFlags(false, false, false), "fixture-interop-one",
                        Address.of("198.51.100.2"), Address.absent(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                // K-3 standalone virtualization host: a physical device (HR-1), hosts VS-1.
                new RawCandidateInput(key(DOMAIN_A, "host-1"), ObjectType.GATEWAY,
                        new ClassificationFlags(true, true, false), "fixture-host-one",
                        Address.of("198.51.100.3"), Address.of("198.51.100.3"),
                        Optional.empty(), Optional.of("fixture-model-3"), Optional.of("1.0"), Optional.empty()),
                // K-4 standalone virtual system on host-1: own address present-but-empty (HR-2).
                new RawCandidateInput(key(DOMAIN_A, "vs-1"), ObjectType.GATEWAY,
                        new ClassificationFlags(true, false, true), "fixture-vs-one",
                        Address.of(""), Address.of("198.51.100.3"),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                // K-5 virtualization cluster: no management address (HR-3), no own address measured.
                new RawCandidateInput(key(DOMAIN_A, "vcluster-1"), ObjectType.CLUSTER,
                        new ClassificationFlags(true, true, false), "fixture-vcluster-one",
                        Address.absent(), Address.absent(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                // K-8 physical virtualization chassis member of vcluster-1: a physical device (HR-1).
                new RawCandidateInput(key(DOMAIN_A, "chassis-member-1"), ObjectType.MEMBER,
                        new ClassificationFlags(true, true, false), "fixture-chassis-member-one",
                        Address.of("198.51.100.4"), Address.of("198.51.100.4"),
                        Optional.of(new ClusterReference(Optional.of(OpaqueId.of("vcluster-1")), Optional.of("fixture-vcluster-one"))),
                        Optional.of("fixture-model-8"), Optional.of("1.0"), Optional.empty()),
                // K-6 virtual-system cluster: no management address (HR-3).
                new RawCandidateInput(key(DOMAIN_A, "vscluster-1"), ObjectType.CLUSTER,
                        new ClassificationFlags(true, false, true), "fixture-vscluster-one",
                        Address.absent(), Address.absent(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                // K-9 virtual-system member of vscluster-1, hosted on chassis-member-1 (HR-2, links to chassis-member-1).
                new RawCandidateInput(key(DOMAIN_A, "vs-member-1"), ObjectType.MEMBER,
                        new ClassificationFlags(true, false, true), "fixture-vs-member-one",
                        Address.of(""), Address.of("198.51.100.4"),
                        Optional.of(new ClusterReference(Optional.of(OpaqueId.of("vscluster-1")), Optional.of("fixture-vscluster-one"))),
                        Optional.empty(), Optional.empty(), Optional.empty()),
                // K-7 plain HA cluster: no management address (HR-3).
                new RawCandidateInput(key(DOMAIN_A, "hacluster-1"), ObjectType.CLUSTER,
                        new ClassificationFlags(true, false, false), "fixture-hacluster-one",
                        Address.absent(), Address.absent(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                // K-10 plain cluster member of hacluster-1: a physical device (HR-1).
                new RawCandidateInput(key(DOMAIN_A, "plain-member-1"), ObjectType.MEMBER,
                        new ClassificationFlags(true, false, false), "fixture-plain-member-one",
                        Address.of("198.51.100.5"), Address.of("198.51.100.5"),
                        Optional.of(new ClusterReference(Optional.of(OpaqueId.of("hacluster-1")), Optional.of("fixture-hacluster-one"))),
                        Optional.of("fixture-model-10"), Optional.of("1.0"), Optional.empty()),
                // K-10 member whose cluster reference is entirely absent -> ClusterLink NOT_EVALUABLE.
                new RawCandidateInput(key(DOMAIN_A, "plain-member-no-ref"), ObjectType.MEMBER,
                        new ClassificationFlags(true, false, false), "fixture-plain-member-no-ref",
                        Address.of("198.51.100.6"), Address.of("198.51.100.6"),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                // K-10 member whose cluster reference carries a display name but no identifier -> NOT_EVALUABLE (MC-2).
                new RawCandidateInput(key(DOMAIN_A, "plain-member-name-only-ref"), ObjectType.MEMBER,
                        new ClassificationFlags(true, false, false), "fixture-plain-member-name-only-ref",
                        Address.of("198.51.100.7"), Address.of("198.51.100.7"),
                        Optional.of(new ClusterReference(Optional.empty(), Optional.of("fixture-hacluster-one"))),
                        Optional.empty(), Optional.empty(), Optional.empty()),
                // K-4 virtual system whose management address matches no sibling's own address -> host MISSING.
                new RawCandidateInput(key(DOMAIN_A, "vs-missing-host"), ObjectType.GATEWAY,
                        new ClassificationFlags(true, false, true), "fixture-vs-missing-host",
                        Address.of(""), Address.of("198.51.100.99"),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                // Two standalone virtualization hosts that (deliberately, for this fixture) share one own address...
                new RawCandidateInput(key(DOMAIN_A, "host-dup-1"), ObjectType.GATEWAY,
                        new ClassificationFlags(true, true, false), "fixture-host-dup-one",
                        Address.of("198.51.100.50"), Address.of("198.51.100.50"),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                new RawCandidateInput(key(DOMAIN_A, "host-dup-2"), ObjectType.GATEWAY,
                        new ClassificationFlags(true, true, false), "fixture-host-dup-two",
                        Address.of("198.51.100.50"), Address.of("198.51.100.50"),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                // ...so a virtual system whose management address matches both is AMBIGUOUS, not resolved either way.
                new RawCandidateInput(key(DOMAIN_A, "vs-ambiguous-host"), ObjectType.GATEWAY,
                        new ClassificationFlags(true, false, true), "fixture-vs-ambiguous-host",
                        Address.of(""), Address.of("198.51.100.50"),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                // UNCLASSIFIED: a flag combination absent from the §4.2 table (both virtualization flags set).
                new RawCandidateInput(key(DOMAIN_A, "unclassified-1"), ObjectType.GATEWAY,
                        new ClassificationFlags(true, true, true), "fixture-unclassified-one",
                        Address.of("198.51.100.8"), Address.of("198.51.100.8"),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    /** A second domain's virtualization host, used to prove HL-1 never links across domains. */
    static List<RawCandidateInput> domainBInputs() {
        return List.of(
                new RawCandidateInput(key(DOMAIN_B, "host-b-1"), ObjectType.GATEWAY,
                        new ClassificationFlags(true, true, false), "fixture-host-b-one",
                        Address.of("198.51.100.3"), Address.of("198.51.100.3"),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    static List<RawCandidateInput> allInputs() {
        return java.util.stream.Stream.concat(domainAInputs().stream(), domainBInputs().stream()).toList();
    }

    /** §9 check 8 (NP-5): every display-name field, including inside a cluster reference, replaced by one constant. */
    static List<RawCandidateInput> withEveryDisplayNameReplaced(List<RawCandidateInput> inputs, String constant) {
        return inputs.stream()
                .map(in -> new RawCandidateInput(in.key(), in.objectType(), in.flags(), constant,
                        in.ownAddress(), in.managementAddress(),
                        in.clusterReference().map(ref -> new ClusterReference(ref.identifier(), Optional.of(constant))),
                        in.model(), in.softwareVersion(), in.managementPlaneConnectionState()))
                .toList();
    }

    /** §9 check 12 (MC-2): every cluster reference's identifier removed, display name kept. */
    static List<RawCandidateInput> withEveryClusterReferenceIdentifierRemoved(List<RawCandidateInput> inputs) {
        return inputs.stream()
                .map(in -> new RawCandidateInput(in.key(), in.objectType(), in.flags(), in.displayName(),
                        in.ownAddress(), in.managementAddress(),
                        in.clusterReference().map(ref -> new ClusterReference(Optional.empty(), ref.displayName())),
                        in.model(), in.softwareVersion(), in.managementPlaneConnectionState()))
                .toList();
    }
}
