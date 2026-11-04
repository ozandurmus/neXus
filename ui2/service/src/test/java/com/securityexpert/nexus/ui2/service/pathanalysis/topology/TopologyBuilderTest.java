package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.ConflictKind;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.NodeFlag;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.NodeKind;

/** Stage A3 builder invariants beyond the goldens: determinism, opaque ids, identity rules, next-hop resolution. */
class TopologyBuilderTest {

    private static final String CP = TopologyInput.VENDOR_CHECK_POINT;
    private static final String PAN = TopologyInput.VENDOR_PALO_ALTO;

    @Test
    void identicalInputsGiveAnIdenticalGraphAndTheSaltAloneChangesTheIds() {
        var estate = TopologyFixtures.resource("estate.json");
        var overrides = TopologyFixtures.overrides(TopologyFixtures.resource("overrides.json"));
        TopologyGraph first = TopologyBuilder.build(TopologyFixtures.input(TopologyFixtures.devices(estate), overrides));
        TopologyGraph second = TopologyBuilder.build(TopologyFixtures.input(TopologyFixtures.devices(estate), overrides));
        assertEquals(TopologyFixtures.render(first), TopologyFixtures.render(second));
        assertEquals(ids(first), ids(second));
        assertEquals(first.fingerprints(), second.fingerprints());

        TopologyGraph otherSalt = TopologyBuilder.build(new TopologyInput(first.snapshotId(), first.builtAt(),
                TopologyFixtures.devices(estate), overrides, "another-synthetic-salt-0002".getBytes(StandardCharsets.UTF_8)));
        assertEquals(TopologyFixtures.render(first), TopologyFixtures.render(otherSalt));
        List<String> a = ids(first);
        List<String> b = ids(otherSalt);
        assertTrue(a.stream().noneMatch(b::contains), "ids are not linkable across snapshots");
    }

    @Test
    void everyIdIsOpaqueAndCarriesNoAddressNameOrDeviceId() {
        var estate = TopologyFixtures.resource("estate.json");
        TopologyGraph g = TopologyBuilder.build(TopologyFixtures.input(TopologyFixtures.devices(estate),
                TopologyFixtures.overrides(TopologyFixtures.resource("overrides.json"))));
        String raw = estate.toString();
        for (String id : ids(g)) {
            assertTrue(id.matches("t[nsac]-[0-9a-f]{20}"), id);
            String hex = id.substring(3);
            // No dotted address, no interface or host name, no device id can sit inside 20 hex digits.
            assertFalse(hex.contains(".") || hex.contains("/"), id);
            assertFalse(raw.contains("\"" + id + "\""), id);
        }
    }

    @Test
    void aClusterUnitIsIdentifiedByTheEnrolledRefNeverByVipEqualityOrHostname() {
        // Two Check Point gateways without a cluster ref that report the same VIP and the same hostname stay two units.
        var a = cp("dev-a", Optional.empty(), "FW-TANGO-04", "ACTIVE",
                iface("eth1", addr("192.0.2.1", InventoryAddress.ROLE_CLUSTER_VIRTUAL), addr("192.0.2.2/26", "member")),
                connected("192.0.2.0/26", "eth1"));
        var b = cp("dev-b", Optional.empty(), "FW-TANGO-04", "ACTIVE",
                iface("eth1", addr("192.0.2.1", InventoryAddress.ROLE_CLUSTER_VIRTUAL), addr("192.0.2.3/26", "member")),
                connected("192.0.2.0/26", "eth1"));
        TopologyGraph g = build(a, b);
        assertEquals(2, g.nodes().stream().filter(n -> n.kind() == NodeKind.ROUTING_CONTEXT).count());
        assertEquals(0, g.counts().clusterUnits());
        assertTrue(kinds(g).contains(ConflictKind.DUPLICATE_PRIVATE_PREFIX), "same private prefix, no route between them");

        // The same two with the enrolled ref are one unit; the VIP attaches it, member addresses are evidence only.
        var ca = cp("dev-a", Optional.of("cls-ref"), "FW-TANGO-04", "ACTIVE",
                iface("eth1", addr("192.0.2.1", InventoryAddress.ROLE_CLUSTER_VIRTUAL), addr("192.0.2.2/26", "member")),
                connected("192.0.2.0/26", "eth1"));
        var cb = cp("dev-b", Optional.of("cls-ref"), "FW-TANGO-05", "STANDBY",
                iface("eth1", addr("192.0.2.1", InventoryAddress.ROLE_CLUSTER_VIRTUAL), addr("192.0.2.3/26", "member")),
                connected("192.0.2.0/26", "eth1"));
        TopologyGraph unit = build(ca, cb);
        var node = unit.nodes().stream().filter(n -> n.kind() == NodeKind.CLUSTER_UNIT).findFirst().orElseThrow();
        assertEquals(Optional.of("dev-a"), node.activeMemberDeviceId());
        assertEquals(Set.of(), node.flags());
        assertEquals(List.of(Optional.of("192.0.2.1/26")), unit.attachmentsOf(node.nodeId()).stream()
                .map(TopologyGraph.Attachment::address).toList(), "VIP prefix from the connected route; members not attached");
    }

    @Test
    void oneSidedOrRoleLessClustersAreKeptButNeverRoutedOnAGuess() {
        var only = cp("dev-a", Optional.of("cls-ref"), "FW-TANGO-04", "ACTIVE",
                iface("eth1", addr("192.0.2.1/26", InventoryAddress.ROLE_CLUSTER_VIRTUAL)), connected("192.0.2.0/26", "eth1"));
        var missingPeer = new TopologyInput.DeviceFacts("dev-b", CP, Optional.of("FW-TANGO-05"), Optional.of("cls-ref"),
                Optional.empty());
        var node = build(only, missingPeer).nodes().get(0);
        assertTrue(node.flags().contains(NodeFlag.CLUSTER_UNCORROBORATED));
        assertEquals(Optional.of("dev-a"), node.activeMemberDeviceId());

        var a = cp("dev-a", Optional.of("cls-ref"), "FW-TANGO-04", "unknown",
                iface("eth1", addr("192.0.2.1/26", InventoryAddress.ROLE_CLUSTER_VIRTUAL)), connected("192.0.2.0/26", "eth1"));
        var b = cp("dev-b", Optional.of("cls-ref"), "FW-TANGO-05", "STANDBY",
                iface("eth1", addr("192.0.2.1/26", InventoryAddress.ROLE_CLUSTER_VIRTUAL)), connected("192.0.2.0/26", "eth1"));
        var unproven = build(a, b).nodes().get(0);
        assertTrue(unproven.flags().contains(NodeFlag.ACTIVE_MEMBER_UNPROVEN));
        assertEquals(Optional.empty(), unproven.activeMemberDeviceId());
        assertEquals(List.of(), unproven.routes(), "no table is routed when the active member is unproven");

        var c = cp("dev-a", Optional.of("cls-ref"), "FW-TANGO-04", "ACTIVE",
                iface("eth1", addr("192.0.2.1/26", InventoryAddress.ROLE_CLUSTER_VIRTUAL)), connected("192.0.2.0/26", "eth1"));
        var d = cp("dev-b", Optional.of("cls-ref"), "FW-TANGO-05", "STANDALONE",
                iface("eth1", addr("192.0.2.9/26", InventoryAddress.ROLE_CLUSTER_VIRTUAL)), connected("192.0.2.0/26", "eth1"));
        TopologyGraph disagree = build(c, d);
        assertTrue(disagree.nodes().get(0).flags().contains(NodeFlag.CLUSTER_MEMBERS_DISAGREE),
                "relationship inconsistent, the unit is kept");
        assertEquals(2, disagree.conflicts().stream().filter(k -> k.kind() == ConflictKind.CLUSTER_MEMBERS_DISAGREE).count());
    }

    @Test
    void nextHopsResolveToAUnitACloudOrNothingAndDuplicateAddressesAreAmbiguous() {
        // Two units both claim 192.0.2.10 on a joined segment; a third routes to it.
        var r1 = cp("dev-r1", Optional.empty(), "FW-BRAVO-02", "STANDALONE",
                iface("eth1", addr("192.0.2.10/26", "member")), connected("192.0.2.0/26", "eth1"),
                route("0.0.0.0/0", "192.0.2.20", "eth1"));
        var r2 = cp("dev-r2", Optional.empty(), "FW-BRAVO-03", "STANDALONE",
                iface("eth1", addr("192.0.2.10/26", "member")), connected("192.0.2.0/26", "eth1"),
                route("0.0.0.0/0", "192.0.2.20", "eth1"));
        var r3 = cp("dev-r3", Optional.empty(), "FW-BRAVO-04", "STANDALONE",
                iface("eth1", addr("192.0.2.20/26", "member")), connected("192.0.2.0/26", "eth1"),
                route("198.51.100.0/24", "192.0.2.10", "eth1"), route("203.0.113.0/24", "192.0.2.30", "eth1"));
        TopologyGraph g = build(r1, r2, r3);
        String n3 = nodeOf(g, "dev-r3");
        assertTrue(kinds(g).contains(ConflictKind.DUPLICATE_ADDRESS));
        assertInstanceOf(TopologyGraph.NextHop.Ambiguous.class, g.resolveNextHop(n3, Optional.of("eth1"), "192.0.2.10"));
        assertTrue(kinds(g).contains(ConflictKind.NEXT_HOP_AMBIGUOUS));
        var toR3 = assertInstanceOf(TopologyGraph.NextHop.ToNode.class,
                g.resolveNextHop(nodeOf(g, "dev-r1"), Optional.of("eth1"), "192.0.2.20"));
        assertEquals(n3, toR3.nodeId());
        assertInstanceOf(TopologyGraph.NextHop.ToCloud.class, g.resolveNextHop(n3, Optional.of("eth1"), "192.0.2.30"));
        assertInstanceOf(TopologyGraph.NextHop.Unreachable.class, g.resolveNextHop(n3, Optional.empty(), "198.51.100.9"));
        assertInstanceOf(TopologyGraph.NextHop.Unreachable.class, g.resolveNextHop(n3, Optional.empty(), "not-an-address"));
    }

    @Test
    void panInterfacesFollowTheirVirtualRouterAndConfigDerivedInterfacesAreFlagged() {
        var run = new InventoryRun("run-p", "dev-p", "job-p", Instant.parse("2026-11-04T06:00:00Z"), 1, List.of(
                new InventoryContext("vsys1", List.of(
                        new InventoryInterface("if-1", "ethernet1/1", Optional.empty(), "physical", "up",
                                List.of(addr("192.0.2.1/26", "member")), Optional.empty(), Optional.empty(),
                                Optional.of("trust"), Optional.of("vr-a"), Optional.of(InventoryInterface.GRADE_CONFIG_DERIVED)),
                        new InventoryInterface("if-2", "ethernet1/2", Optional.empty(), "physical", "up",
                                List.of(addr("198.51.100.1/24", "member")), Optional.empty(), Optional.empty(),
                                Optional.of("untrust"), Optional.of("vr-b"), Optional.of(InventoryInterface.GRADE_RUNTIME))),
                        List.of())),
                List.of(new InventoryHaFact("ha", "physical", "STANDALONE", Optional.empty(), "pan_high_availability_state")));
        TopologyGraph g = build(new TopologyInput.DeviceFacts("dev-p", PAN, Optional.of("FW-MIKE-09"), Optional.empty(),
                Optional.of(run)));
        assertEquals(2, g.nodes().size());
        for (var node : g.nodes()) {
            var attached = g.attachmentsOf(node.nodeId());
            assertEquals(1, attached.size());
            String expectedInterface = node.routingTable().equals(Optional.of("vr-a")) ? "ethernet1/1" : "ethernet1/2";
            assertEquals(Optional.of(expectedInterface), attached.get(0).interfaceName());
            assertEquals(node.routingTable().equals(Optional.of("vr-a")),
                    node.flags().contains(NodeFlag.CONFIG_DERIVED_INTERFACES));
        }
    }

    @Test
    void ipv4PrefixCanonicalisesRepresentationOnlyAndClassifiesReachability() {
        assertEquals("192.0.2.0/24", Ipv4Prefix.parse("192.0.2.77/24").orElseThrow().toString());
        assertEquals("192.0.2.77/32", Ipv4Prefix.parse(" 192.0.2.77 ").orElseThrow().toString());
        assertTrue(Ipv4Prefix.parse("192.0.2.010/24").isEmpty(), "a leading zero is not a proven representation");
        assertTrue(Ipv4Prefix.parse("192.0.2.1/33").isEmpty());
        assertTrue(Ipv4Prefix.parse("192.0.2.256").isEmpty());
        assertTrue(Ipv4Prefix.parseHost("192.0.2.1").orElseThrow().length().isEmpty(), "no length = UNKNOWN, not /32");
        for (String doc : List.of("192.0.2.0/24", "198.51.100.0/25", "203.0.113.4/30")) {
            assertFalse(Ipv4Prefix.parse(doc).orElseThrow().globallyReachable(), doc);
        }
        assertTrue(new Ipv4Prefix(0x0800_0000L, 8).globallyReachable());
        assertTrue(Ipv4Prefix.parse("198.51.100.0/31").orElseThrow().pointToPoint());
        assertFalse(Ipv4Prefix.parse("198.51.100.0/29").orElseThrow().pointToPoint());
    }

    @Test
    void anUnmatchedOverrideIsReportedAndChangesNothing() {
        var estate = TopologyFixtures.resource("estate.json");
        var devices = TopologyFixtures.devices(estate);
        var bogus = new TopologyLinkOverride("tlo-00000000-0000-4000-8000-0000000000ff", "FORCE_LINK", "STATIC_L3",
                Optional.of("dev-b02"), "physical", "eth4", Optional.of("dev-b02"), "physical", "eth3", Optional.empty(),
                "OTHER", Optional.empty(), "synthetic-actor", Instant.EPOCH, Optional.empty(), Optional.empty(),
                Optional.empty());
        TopologyGraph without = TopologyBuilder.build(TopologyFixtures.input(devices, List.of()));
        TopologyGraph with = TopologyBuilder.build(TopologyFixtures.input(devices, List.of(bogus)));
        assertEquals(TopologyGraph.OverrideStatus.NO_MATCH, with.override(bogus.overrideId()).orElseThrow().status());
        assertEquals(Optional.of(TopologyGraph.NoMatchReason.SAME_NODE), with.override(bogus.overrideId()).orElseThrow().reason());
        assertEquals(without.segments().size(), with.segments().size());
        assertEquals(without.conflicts().size() + 1, with.conflicts().size());
        assertNotEquals(without.fingerprints().overrides(), with.fingerprints().overrides());
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static List<String> ids(TopologyGraph g) {
        List<String> out = new ArrayList<>();
        g.nodes().forEach(n -> out.add(n.nodeId()));
        g.segments().forEach(s -> out.add(s.segmentId()));
        g.attachments().forEach(a -> out.add(a.attachmentId()));
        g.conflicts().forEach(c -> out.add(c.conflictId()));
        return out;
    }

    private static Set<ConflictKind> kinds(TopologyGraph g) {
        return Set.copyOf(g.conflicts().stream().map(TopologyGraph.Conflict::kind).toList());
    }

    private static String nodeOf(TopologyGraph g, String deviceId) {
        return g.nodes().stream().filter(n -> n.members().stream().anyMatch(m -> m.deviceId().equals(deviceId)))
                .findFirst().orElseThrow().nodeId();
    }

    private static TopologyGraph build(TopologyInput.DeviceFacts... devices) {
        return TopologyBuilder.build(TopologyFixtures.input(List.of(devices), List.of()));
    }

    private static InventoryAddress addr(String address, String role) {
        return new InventoryAddress("addr-" + address + role, address, InventoryAddress.FAMILY_IPV4, role);
    }

    private static InventoryInterface iface(String name, InventoryAddress... addresses) {
        return new InventoryInterface("if-" + name, name, Optional.empty(), "physical", "up", List.of(addresses));
    }

    private static InventoryRoute connected(String destination, String iface) {
        return new InventoryRoute("r-" + destination, destination, Optional.empty(), Optional.of(iface), "connected",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("CONNECTED"));
    }

    private static InventoryRoute route(String destination, String nextHop, String iface) {
        return new InventoryRoute("r-" + destination + nextHop, destination, Optional.of(nextHop), Optional.of(iface),
                "static", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("IP"));
    }

    private static TopologyInput.DeviceFacts cp(String deviceId, Optional<String> clusterRef, String hostname, String role,
            Object... parts) {
        List<InventoryInterface> interfaces = Stream.of(parts).filter(InventoryInterface.class::isInstance)
                .map(InventoryInterface.class::cast).toList();
        List<InventoryRoute> routes = Stream.of(parts).filter(InventoryRoute.class::isInstance)
                .map(InventoryRoute.class::cast).toList();
        var run = new InventoryRun("run-" + deviceId, deviceId, "job-" + deviceId, Instant.parse("2026-11-04T06:00:00Z"), 1,
                List.of(new InventoryContext("physical", interfaces, routes)),
                List.of(new InventoryHaFact("ha-" + deviceId, "physical", role, Optional.empty(), "cp_cphaprob_stat")));
        return new TopologyInput.DeviceFacts(deviceId, CP, Optional.of(hostname), clusterRef, Optional.of(run));
    }
}
