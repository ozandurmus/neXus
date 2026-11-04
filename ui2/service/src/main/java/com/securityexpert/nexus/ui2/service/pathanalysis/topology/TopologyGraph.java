package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;

/**
 * The in-memory topology of one snapshot (PO record 2026-11-03 D-1: the graph is built from inventory and never stored
 * node by node). Immutable once built. Every id here is opaque: a keyed hash under a per-snapshot secret salt that is
 * never persisted or returned, so no address or name can be recovered from an id (design §3).
 *
 * <p>Routes are references to the {@code device_route} rows of the input runs, kept in memory for the path engine
 * (stage A4); they are never copied into a table.</p>
 */
public final class TopologyGraph {

    private final String snapshotId;
    private final Instant builtAt;
    private final List<Node> nodes;
    private final List<Segment> segments;
    private final List<Attachment> attachments;
    private final List<Conflict> conflicts;
    private final List<OverrideResult> overrides;
    private final List<NextHopEdge> nextHops;
    private final Counts counts;
    private final Fingerprints fingerprints;
    private final NextHopResolver resolver;


    /** Node kinds; B/C/D kinds ({@code lb_virtual_server}, {@code aci_vrf}, {@code router}) are reserved (F§1.4). */
    public enum NodeKind { ROUTING_CONTEXT, CLUSTER_UNIT, CLOUD }

    /** Facts on a node; none of them is an identity. */
    public enum NodeFlag {
        /** Gaia non-main routing tables are present: the main table is evaluated, the hop carries this flag (design §4). */
        PBR_PRESENT,
        /** A cluster unit whose active member is not provable from collected HA facts. */
        ACTIVE_MEMBER_UNPROVEN,
        /** A cluster unit with only one member's inventory collected: one-sided, not corroborated. */
        CLUSTER_UNCORROBORATED,
        /** Members of a cluster unit disagree on HA facts or VIPs (RELATIONSHIP_INCONSISTENT); the unit is kept. */
        CLUSTER_MEMBERS_DISAGREE,
        /** Active and standby route sets differ (HA_ROUTE_DRIFT conflict); routing uses the active member. */
        HA_ROUTE_DRIFT,
        /** Interfaces derived from stored configuration (PAN-OS 12, V184 CONFIG_DERIVED), not a runtime read. */
        CONFIG_DERIVED_INTERFACES,
        /** A VSX warp-link cloud (design §4: stays a cloud until a measured fixture proves the pairing). */
        VSX_WARP_CLOUD
    }

    public enum SegmentKind {
        /** Three or more units, or two units on a prefix longer than /31 or shorter than /30. */
        SHARED,
        /** {@code /30}-{@code /31} with exactly two units: drawn as a direct edge (design §4). */
        POINT_TO_POINT,
        /** One unit only. */
        STUB,
        /** A VSX {@code wrp*} link, never joined with another unit. */
        VSX_WARP,
        /** Declared by a {@code FORCE_LINK} override, never discovered. */
        MANUAL
    }

    /** One member device of a node, with the HA role its own run reported for the node's context (empty = UNKNOWN). */
    public record Member(String deviceId, Optional<String> hostname, Optional<String> haRole, Optional<String> runId,
            Optional<Instant> collectedAt) {
    }

    /**
     * @param context      {@code physical}, a VSID or a vsys id -- the vocabulary of {@code device_interface.context}
     * @param routingTable the routing instance ({@code main} on Check Point; the Palo Alto virtual router); empty for a cloud
     * @param activeMemberDeviceId the member whose tables route for the unit; empty when not provable
     * @param routes       the active member's main-table routes (in memory only; empty for a cloud)
     */
    public record Node(String nodeId, NodeKind kind, String vendor, Optional<String> clusterMemberRef, String context,
            Optional<String> routingTable, List<Member> members, Optional<String> activeMemberDeviceId,
            Set<NodeFlag> flags, List<InventoryRoute> routes) {
        public Node {
            members = List.copyOf(members);
            flags = Set.copyOf(flags);
            routes = List.copyOf(routes);
        }
    }

    public record Segment(String segmentId, Optional<Ipv4Prefix> network, SegmentKind kind, Optional<String> overrideId) {
    }

    /**
     * One unit's presence on a segment through a real interface (or, for {@link Source#OVERRIDE}, through the declared
     * link). {@code address} is the address the unit is reached at: the VIP for a Check Point cluster unit, the member
     * address otherwise.
     */
    public record Attachment(String attachmentId, String nodeId, String segmentId, Optional<String> interfaceName,
            Optional<String> address, Optional<String> addressRole, Optional<String> zone, boolean corroborated,
            Source source, Optional<String> evidenceGrade) {
        public enum Source { INVENTORY, OVERRIDE }
    }

    public enum ConflictKind {
        DUPLICATE_PRIVATE_PREFIX, OVERLAPPING_PREFIX, DUPLICATE_ADDRESS, HA_ROUTE_DRIFT, CLUSTER_MEMBERS_DISAGREE,
        INTERFACE_WITHOUT_CONTEXT, ADDRESS_PREFIX_UNKNOWN, NEXT_HOP_UNREACHABLE, NEXT_HOP_AMBIGUOUS, OVERRIDE_NO_MATCH
    }

    /** {@code OPEN}, or {@code OVERRIDDEN} when an override resolved it in this build. */
    public enum Resolution { OPEN, OVERRIDDEN }

    /** A conflict names the relationship by opaque ids; {@code network} is the only address it carries. */
    public record Conflict(String conflictId, ConflictKind kind, Optional<String> deviceId, Optional<String> peerDeviceId,
            Optional<String> context, Optional<Ipv4Prefix> network, Optional<String> overrideId, List<String> nodeIds,
            Resolution resolution) {
        public Conflict {
            nodeIds = List.copyOf(nodeIds);
        }
    }

    public enum OverrideStatus { APPLIED, NO_EFFECT, NO_MATCH }

    /** Why an override did not match (empty when it matched). Unmatched overrides are reported, never dropped. */
    public enum NoMatchReason { DEVICE_REMOVED, ENDPOINT_NOT_FOUND, ENDPOINT_AMBIGUOUS, SAME_NODE }

    public record OverrideResult(String overrideId, OverrideStatus status, Optional<NoMatchReason> reason) {
    }

    /** How a node's routes reach the next units: distinct targets per egress segment (no route values). */
    public record NextHopEdge(String fromNodeId, String segmentId, String toNodeId, int routeCount) {
    }

    public record Counts(int devices, int devicesWithoutInventory, int nodes, int clusterUnits, int clouds, int segments,
            int attachments, int conflicts, int overridesApplied, int overridesUnmatched) {
    }

    /** SHA-256 over sorted opaque refs: {@code device_id|run_id|collected_at} and {@code override_id|revoked_at}. */
    public record Fingerprints(String inventory, String overrides) {
    }

    public TopologyGraph(String snapshotId, Instant builtAt, List<Node> nodes, List<Segment> segments,
            List<Attachment> attachments, List<Conflict> conflicts, List<OverrideResult> overrides,
            List<NextHopEdge> nextHops, Counts counts, Fingerprints fingerprints) {
        this.snapshotId = snapshotId;
        this.builtAt = builtAt;
        this.nodes = List.copyOf(nodes);
        this.segments = List.copyOf(segments);
        this.attachments = List.copyOf(attachments);
        this.conflicts = List.copyOf(conflicts);
        this.overrides = List.copyOf(overrides);
        this.nextHops = List.copyOf(nextHops);
        this.counts = counts;
        this.fingerprints = fingerprints;
        this.resolver = new NextHopResolver(this.nodes, this.segments, this.attachments);
    }

    public String snapshotId() { return snapshotId; }
    public Instant builtAt() { return builtAt; }
    public List<Node> nodes() { return nodes; }
    public List<Segment> segments() { return segments; }
    public List<Attachment> attachments() { return attachments; }
    public List<Conflict> conflicts() { return conflicts; }
    public List<OverrideResult> overrides() { return overrides; }
    public List<NextHopEdge> nextHops() { return nextHops; }
    public Counts counts() { return counts; }
    public Fingerprints fingerprints() { return fingerprints; }

    public Optional<Node> node(String nodeId) {
        return nodes.stream().filter(n -> n.nodeId().equals(nodeId)).findFirst();
    }

    public Optional<Segment> segment(String segmentId) {
        return segments.stream().filter(s -> s.segmentId().equals(segmentId)).findFirst();
    }

    public List<Attachment> attachmentsOf(String nodeId) {
        return attachments.stream().filter(a -> a.nodeId().equals(nodeId)).toList();
    }

    public List<Attachment> attachmentsOn(String segmentId) {
        return attachments.stream().filter(a -> a.segmentId().equals(segmentId)).toList();
    }

    public Optional<OverrideResult> override(String overrideId) {
        return overrides.stream().filter(o -> o.overrideId().equals(overrideId)).findFirst();
    }

    /** Direct edges: point-to-point segments with exactly two units (design §4). */
    public List<List<String>> directEdges() {
        return segments.stream().filter(s -> s.kind() == SegmentKind.POINT_TO_POINT)
                .map(s -> attachmentsOn(s.segmentId()).stream().map(Attachment::nodeId).distinct().sorted().toList())
                .filter(ends -> ends.size() == 2).toList();
    }

    /** Outcome of resolving one route's next hop against this snapshot; never a guess. */
    public sealed interface NextHop {
        record ToNode(String nodeId, String segmentId) implements NextHop {
        }

        /** No enrolled unit holds the address: the route exits into a cloud (stage A4: EXITS_KNOWN_TOPOLOGY). */
        record ToCloud(String cloudNodeId, String segmentId) implements NextHop {
        }

        /** No enrolled unit holds the address and this snapshot drew no cloud on that segment. */
        record Unheld(String segmentId) implements NextHop {
        }

        record Ambiguous(String segmentId, List<String> nodeIds) implements NextHop {
        }

        /** The next hop lies on none of the node's segments, or is not an IPv4 host address. */
        record Unreachable() implements NextHop {
        }
    }

    /**
     * Resolves a next-hop address leaving {@code nodeId} (stage A4's entry point). The egress interface's segment is
     * preferred; otherwise the longest attached prefix containing the address. Holders are units attached to that
     * segment at exactly that address (equality after canonical IPv4 parsing).
     */
    public NextHop resolveNextHop(String nodeId, Optional<String> egressInterface, String nextHopAddress) {
        return resolver.resolve(nodeId, egressInterface, nextHopAddress);
    }
}
