package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.Attachment;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.Counts;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.Fingerprints;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.Node;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.NextHop;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.NextHopEdge;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.NodeKind;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.Segment;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.SegmentKind;

/**
 * Unit tests for {@link TopologyGraph} lookups and next-hop resolution on hand-built synthetic graphs. Ids and
 * addresses are synthetic ({@code FW-TANGO-04}-style names, {@code 198.51.100.0/24} and {@code 192.0.2.0/24}); the
 * graph is never asked to normalise an id.
 */
class TopologyGraphTest {

    private static final Instant BUILT_AT = Instant.parse("2026-11-23T07:00:00Z");
    private static final String SEGMENT = "seg-alpha";
    private static final String NODE_A = "node-fw-tango-04";
    private static final String NODE_B = "node-fw-juliet-06";
    private static final String NODE_C = "node-fw-bravo-02";
    private static final String CLOUD = "node-cloud-echo-01";

    private static final Counts COUNTS = new Counts(3, 0, 3, 0, 1, 1, 3, 0, 0, 0);
    private static final Fingerprints FINGERPRINTS = new Fingerprints("inv-fp", "ovr-fp");

    private static Node unit(String nodeId) {
        return new Node(nodeId, NodeKind.ROUTING_CONTEXT, "synthetic-vendor", Optional.empty(), "physical",
                Optional.of("main"), List.of(), Optional.empty(), Set.of(), List.of());
    }

    private static Node cloud(String nodeId) {
        return new Node(nodeId, NodeKind.CLOUD, "synthetic-vendor", Optional.empty(), "physical", Optional.empty(),
                List.of(), Optional.empty(), Set.of(), List.of());
    }

    private static Segment segment(String segmentId, String prefix, SegmentKind kind) {
        return new Segment(segmentId, Ipv4Prefix.parse(prefix), kind, Optional.empty());
    }

    private static Attachment attachment(String attachmentId, String nodeId, String segmentId, Optional<String> address) {
        return new Attachment(attachmentId, nodeId, segmentId, Optional.of("eth1"), address, Optional.empty(),
                Optional.empty(), true, Attachment.Source.INVENTORY, Optional.empty());
    }

    private static TopologyGraph graph(List<Node> nodes, List<Segment> segments, List<Attachment> attachments,
            List<NextHopEdge> nextHops) {
        return new TopologyGraph("tps-synthetic", BUILT_AT, nodes, segments, attachments, List.of(), List.of(),
                nextHops, COUNTS, FINGERPRINTS);
    }

    /** A is on the segment at 198.51.100.1; B and C are optional holders of other addresses. */
    private static TopologyGraph twoUnitsOnSegment() {
        return graph(List.of(unit(NODE_A), unit(NODE_B)), List.of(segment(SEGMENT, "198.51.100.0/24", SegmentKind.SHARED)),
                List.of(attachment("att-a", NODE_A, SEGMENT, Optional.of("198.51.100.1")),
                        attachment("att-b", NODE_B, SEGMENT, Optional.of("198.51.100.2"))),
                List.of());
    }

    // ---- lookups: unknown ids -------------------------------------------------------------------------------------

    @Test
    void nodeLookupForUnknownIdIsEmptyNotNull() {
        TopologyGraph g = twoUnitsOnSegment();

        assertThat(g.node("node-unknown-zulu-99")).isEmpty();
    }

    @Test
    void segmentLookupForUnknownIdIsEmptyNotNull() {
        TopologyGraph g = twoUnitsOnSegment();

        assertThat(g.segment("seg-unknown-zulu-99")).isEmpty();
    }

    @Test
    void overrideLookupForUnknownIdIsEmptyNotNull() {
        TopologyGraph g = twoUnitsOnSegment();

        assertThat(g.override("ovr-unknown-zulu-99")).isEmpty();
    }

    @Test
    void attachmentQueriesForUnknownIdsReturnEmptyLists() {
        TopologyGraph g = twoUnitsOnSegment();

        assertThat(g.attachmentsOf("node-unknown-zulu-99")).isEmpty();
        assertThat(g.attachmentsOn("seg-unknown-zulu-99")).isEmpty();
    }

    // ---- lookups: known ids and exact matching -------------------------------------------------------------------

    @Test
    void nodeLookupForKnownIdReturnsThatNode() {
        TopologyGraph g = twoUnitsOnSegment();

        assertThat(g.node(NODE_A)).map(Node::nodeId).contains(NODE_A);
    }

    @Test
    void attachmentsOfReturnsOnlyThatNodesAttachments() {
        TopologyGraph g = twoUnitsOnSegment();

        assertThat(g.attachmentsOf(NODE_A)).extracting(Attachment::attachmentId).containsExactly("att-a");
        assertThat(g.attachmentsOn(SEGMENT)).extracting(Attachment::attachmentId).containsExactly("att-a", "att-b");
    }

    @Test
    void idLookupIsExactMatchWithoutCaseOrWhitespaceNormalisation() {
        TopologyGraph g = twoUnitsOnSegment();

        assertThat(g.node(NODE_A.toUpperCase())).isEmpty();
        assertThat(g.node(" " + NODE_A)).isEmpty();
        assertThat(g.segment(SEGMENT.toUpperCase())).isEmpty();
    }

    @Test
    void countsAndFingerprintsAreReturnedAsBuilt() {
        TopologyGraph g = twoUnitsOnSegment();

        assertThat(g.counts()).isEqualTo(COUNTS);
        assertThat(g.fingerprints()).isEqualTo(FINGERPRINTS);
        assertThat(g.snapshotId()).isEqualTo("tps-synthetic");
        assertThat(g.builtAt()).isEqualTo(BUILT_AT);
    }

    @Test
    void collectionsAreImmutableSnapshots() {
        TopologyGraph g = twoUnitsOnSegment();

        assertThatThrownBy(() -> g.nodes().add(unit("node-extra-kilo-07")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> g.attachments().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- direct edges --------------------------------------------------------------------------------------------

    @Test
    void directEdgesListOnlyTwoUnitPointToPointSegments() {
        TopologyGraph g = graph(List.of(unit(NODE_A), unit(NODE_B), unit(NODE_C)),
                List.of(segment("seg-p2p", "192.0.2.0/30", SegmentKind.POINT_TO_POINT),
                        segment("seg-shared", "192.0.2.4/30", SegmentKind.SHARED),
                        segment("seg-stub", "192.0.2.8/30", SegmentKind.POINT_TO_POINT)),
                List.of(attachment("att-a1", NODE_A, "seg-p2p", Optional.of("192.0.2.1")),
                        attachment("att-b1", NODE_B, "seg-p2p", Optional.of("192.0.2.2")),
                        attachment("att-a2", NODE_A, "seg-shared", Optional.of("192.0.2.5")),
                        attachment("att-b2", NODE_B, "seg-shared", Optional.of("192.0.2.6")),
                        attachment("att-c2", NODE_C, "seg-shared", Optional.of("192.0.2.7")),
                        attachment("att-c3", NODE_C, "seg-stub", Optional.of("192.0.2.9"))),
                List.of());

        assertThat(g.directEdges()).containsExactly(List.of(NODE_B, NODE_A));
    }

    // ---- next-hop resolution -------------------------------------------------------------------------------------

    @Test
    void nextHopToUnitHoldingTheAddressResolvesToNode() {
        TopologyGraph g = twoUnitsOnSegment();

        assertThat(g.resolveNextHop(NODE_A, Optional.empty(), "198.51.100.2"))
                .isEqualTo(new NextHop.ToNode(NODE_B, SEGMENT));
    }

    @Test
    void nextHopNoUnitHoldsAddressAndNoCloudIsUnheld() {
        TopologyGraph g = twoUnitsOnSegment();

        assertThat(g.resolveNextHop(NODE_A, Optional.empty(), "198.51.100.9"))
                .isEqualTo(new NextHop.Unheld(SEGMENT));
    }

    @Test
    void nextHopNoUnitHoldsAddressAndCloudOnSegmentExitsToCloud() {
        TopologyGraph g = graph(List.of(unit(NODE_A), cloud(CLOUD)),
                List.of(segment(SEGMENT, "198.51.100.0/24", SegmentKind.SHARED)),
                List.of(attachment("att-a", NODE_A, SEGMENT, Optional.of("198.51.100.1")),
                        attachment("att-cloud", CLOUD, SEGMENT, Optional.empty())),
                List.of());

        assertThat(g.resolveNextHop(NODE_A, Optional.empty(), "198.51.100.9"))
                .isEqualTo(new NextHop.ToCloud(CLOUD, SEGMENT));
    }

    @Test
    void nextHopHeldByTwoUnitsIsAmbiguousWithSortedIds() {
        TopologyGraph g = graph(List.of(unit(NODE_A), unit(NODE_B), unit(NODE_C)),
                List.of(segment(SEGMENT, "198.51.100.0/24", SegmentKind.SHARED)),
                List.of(attachment("att-a", NODE_A, SEGMENT, Optional.of("198.51.100.1")),
                        attachment("att-c", NODE_C, SEGMENT, Optional.of("198.51.100.2")),
                        attachment("att-b", NODE_B, SEGMENT, Optional.of("198.51.100.2"))),
                List.of());

        assertThat(g.resolveNextHop(NODE_A, Optional.empty(), "198.51.100.2"))
                .isEqualTo(new NextHop.Ambiguous(SEGMENT, List.of(NODE_C, NODE_B)));
    }

    @Test
    void nextHopOffEveryAttachedSegmentIsUnreachable() {
        TopologyGraph g = twoUnitsOnSegment();

        assertThat(g.resolveNextHop(NODE_A, Optional.empty(), "203.0.113.5"))
                .isInstanceOf(NextHop.Unreachable.class);
    }

    @Test
    void nextHopThatIsNotAnIpv4HostAddressIsUnreachable() {
        TopologyGraph g = twoUnitsOnSegment();

        assertThat(g.resolveNextHop(NODE_A, Optional.empty(), "not-an-address"))
                .isInstanceOf(NextHop.Unreachable.class);
        assertThat(g.resolveNextHop(NODE_A, Optional.empty(), ""))
                .isInstanceOf(NextHop.Unreachable.class);
    }

    @Test
    void nextHopFromUnknownNodeIsUnreachableNotNull() {
        TopologyGraph g = twoUnitsOnSegment();

        assertThat(g.resolveNextHop("node-unknown-zulu-99", Optional.empty(), "198.51.100.2"))
                .isInstanceOf(NextHop.Unreachable.class);
    }

    // ---- next-hop edges as supplied ------------------------------------------------------------------------------

    @Test
    void suppliedNextHopEdgesAreReturnedAsBuilt() {
        NextHopEdge edge = new NextHopEdge(NODE_A, SEGMENT, NODE_B, 2);
        TopologyGraph g = graph(List.of(unit(NODE_A), unit(NODE_B)),
                List.of(segment(SEGMENT, "198.51.100.0/24", SegmentKind.SHARED)),
                List.of(attachment("att-a", NODE_A, SEGMENT, Optional.of("198.51.100.1")),
                        attachment("att-b", NODE_B, SEGMENT, Optional.of("198.51.100.2"))),
                List.of(edge));

        assertThat(g.nextHops()).containsExactly(edge);
    }
}
