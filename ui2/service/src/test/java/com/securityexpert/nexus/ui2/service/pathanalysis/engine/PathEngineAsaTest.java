package com.securityexpert.nexus.ui2.service.pathanalysis.engine;

import static com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathFixtures.COMPUTED_AT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.asa.AsaConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyMapper;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyBuilder;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyInput;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyLinkOverride;
import com.securityexpert.nexus.ui2.service.policy.AsaInterfaceNames;
import com.securityexpert.nexus.ui2.service.policy.HopNatEvaluator;
import com.securityexpert.nexus.ui2.service.policy.HopPolicyEvaluator;

/**
 * Cisco ASA through Trace (ASA policy step 8): the inventory's interface names become nameifs from the stored
 * configuration's interface table, the binder's policy is evaluated with the ingress and egress nameifs, and a NAT rule
 * that may touch the packet keeps the hop UNKNOWN. Synthetic addresses and masked names only.
 */
class PathEngineAsaTest {

    static final String DEVICE = "dev-asa-1";
    static final String BASE = """
            ASA Version 9.18(4)22
            interface GigabitEthernet0/0
             nameif outside
             security-level 0
            interface GigabitEthernet0/1
             nameif inside
             security-level 100
            access-list ACL-IN extended permit tcp any host 203.0.113.10 eq https
            access-group ACL-IN in interface inside
            """;

    static PolicySnapshot policy(String extra) {
        return AsaPolicyMapper.map(DEVICE, "Cisco ASA " + DEVICE, "2026-11-04T05:00:00Z", "artefact",
                AsaConfigPolicyParser.parse(BASE + extra));
    }

    static InventoryRoute connected(String destination, String nameif) {
        // As the ASA route parser stores a "directly connected" routing-table entry: active, next hop kind CONNECTED.
        return new InventoryRoute("r-" + destination, destination, Optional.empty(), Optional.of(nameif), "connected",
                Optional.empty(), Optional.of(true), Optional.empty(), Optional.empty(), Optional.of("CONNECTED"));
    }

    static InventoryInterface iface(String name, String address) {
        return new InventoryInterface("if-" + name, name, Optional.empty(), "physical", "up",
                List.of(new InventoryAddress("a-" + address, address, InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER)));
    }

    /** The inventory as the ASA collector stores it: interfaces by name, routes by nameif. */
    static TopologyGraph graph(Map<String, Map<String, String>> names) {
        var run = new InventoryRun("run-asa", DEVICE, "job-asa", Instant.parse("2026-11-04T06:00:00Z"), 1,
                List.of(new InventoryContext(InventoryContext.PHYSICAL,
                        List.of(iface("GigabitEthernet0/0", "203.0.113.1/24"), iface("GigabitEthernet0/1", "198.51.100.1/24")),
                        List.of(connected("203.0.113.0/24", "outside"), connected("198.51.100.0/24", "inside")))));
        var device = new TopologyInput.DeviceFacts(DEVICE, TopologyInput.VENDOR_CISCO_ASA, Optional.of("FW-ALPHA-01"),
                Optional.empty(), Optional.of(run));
        return TopologyBuilder.build(new TopologyInput("tps-asa", Instant.parse("2026-11-04T07:00:00Z"), List.of(device),
                List.of(), "synthetic-asa-topology-salt-0001".getBytes(StandardCharsets.UTF_8), List.of(), names));
    }

    static PathResult run(TopologyGraph graph, PolicySnapshot policy, int port) {
        var policies = new PathFixtures.Policies().bind(DEVICE, policy, new HopPolicyEvaluator.Device(DEVICE, "", null));
        return PathEngine.analyze(graph, policies, PathQuery.of("198.51.100.20", "203.0.113.10", "tcp", port), COMPUTED_AT);
    }

    static PathResult.Hop onlyHop(PathResult result) {
        assertEquals(1, result.branches().size());
        assertEquals(1, result.branches().get(0).hops().size());
        return result.branches().get(0).hops().get(0);
    }

    @Test
    void theStoredInterfaceTableRenamesAsaInterfacesToNameifsAndTheirZones() {
        var policy = policy("");
        var names = new AsaInterfaceNames(() -> List.of(policy)).load();
        assertEquals(Map.of(DEVICE, Map.of("GigabitEthernet0/0", "outside", "GigabitEthernet0/1", "inside")), names);
        TopologyGraph g = graph(names);
        assertEquals(1, g.nodes().size());
        assertEquals(TopologyInput.VENDOR_CISCO_ASA, g.nodes().get(0).vendor());
        assertEquals(List.of("inside", "outside"), g.attachments().stream().map(a -> a.interfaceName().orElseThrow()).sorted().toList());
        assertTrue(g.attachments().stream().allMatch(a -> a.zone().equals(a.interfaceName())));
        assertTrue(g.attachments().stream().allMatch(TopologyGraph.Attachment::corroborated), "connected routes by nameif corroborate");
    }

    @Test
    void aHopThroughAnAsaIsDecidedByTheIngressListOnTheNameifs() {
        TopologyGraph g = graph(new AsaInterfaceNames(() -> List.of(policy(""))).load());
        var allowed = onlyHop(run(g, policy(""), 443));
        assertEquals("ALLOWED", allowed.ownVerdict(), allowed.reasons().toString());
        assertEquals(HopPolicyEvaluator.FIRST_MATCHING_RULE, allowed.reason());
        assertEquals(Optional.of("inside"), allowed.ingress().zone());
        assertEquals(Optional.of("outside"), allowed.egress().interfaceName());
        assertEquals(HopNatEvaluator.NO_TRANSLATION, allowed.nat().orElseThrow().status());

        var denied = onlyHop(run(g, policy(""), 22));
        assertEquals("DENIED", denied.ownVerdict());
        assertEquals(HopPolicyEvaluator.ASA_IMPLICIT_DENY, denied.reason());
        assertEquals("DENIED", run(g, policy(""), 22).verdict());
    }

    @Test
    void aNatRuleThatMayTouchThePacketKeepsTheHopUnknownAndOneThatCannotDoesNot() {
        TopologyGraph g = graph(new AsaInterfaceNames(() -> List.of(policy(""))).load());
        var pat = policy("""
                object network OBJ-INSIDE
                 subnet 198.51.100.0 255.255.255.0
                 nat (inside,outside) dynamic interface
                """);
        var hop = onlyHop(run(g, pat, 443));
        assertEquals("UNKNOWN", hop.ownVerdict());
        assertEquals(HopPolicyEvaluator.NAT_UNKNOWN, hop.reason());
        assertEquals(HopNatEvaluator.ASA_NAT_NOT_EVALUATED, hop.nat().orElseThrow().reason());
        assertEquals("ALLOWED", hop.security().orElseThrow().verdict(), "the access evaluation stays as context");

        var elsewhere = policy("""
                object network OBJ-OTHER
                 subnet 192.0.2.0 255.255.255.0
                object network OBJ-OTHER-MAPPED
                 host 192.0.2.200
                object network OBJ-SERVER
                 host 192.0.2.10
                 nat (inside,outside) static 192.0.2.250
                nat (inside,outside) source static OBJ-OTHER OBJ-OTHER-MAPPED
                """);
        var untouched = onlyHop(run(g, elsewhere, 443));
        assertEquals("ALLOWED", untouched.ownVerdict());
        assertEquals(HopNatEvaluator.NAT_NO_RULE_MATCHED, untouched.nat().orElseThrow().reason());
    }

    @Test
    void withoutTheInterfaceTableTheAsaHopIsNeverDecided() {
        TopologyGraph g = graph(Map.of());
        assertTrue(g.attachments().stream().allMatch(a -> a.zone().isEmpty()));
        var allowedQuery = onlyHop(run(g, policy(""), 443));
        assertEquals("UNKNOWN", allowedQuery.ownVerdict());
        assertEquals(HopPolicyEvaluator.ASA_INTERFACE_UNKNOWN, allowedQuery.reason());
        var deniedQuery = onlyHop(run(g, policy(""), 22));
        assertEquals("UNKNOWN", deniedQuery.ownVerdict());
        assertEquals(HopPolicyEvaluator.ASA_INTERFACE_UNKNOWN, deniedQuery.reason());
        assertNotEquals("ALLOWED", run(g, policy(""), 443).verdict());
        assertNotEquals("DENIED", run(g, policy(""), 22).verdict());
    }

    @Test
    void subinterfacesAndVlansBindToTheirNameifsAndUnmappedInterfacesStayUnknownWithReason() {
        var subAndVlanPolicy = AsaPolicyMapper.map(DEVICE, "Cisco ASA " + DEVICE, "2026-11-04T05:00:00Z", "artefact",
                AsaConfigPolicyParser.parse("""
                        ASA Version 9.18(4)22
                        interface Port-channel1
                         no nameif
                        interface Port-channel1.100
                         vlan 100
                         nameif inside
                         security-level 100
                        interface Vlan200
                         nameif dmz
                         security-level 50
                        interface GigabitEthernet0/0
                         nameif outside
                         security-level 0
                        access-list ACL-IN extended permit tcp any host 192.0.2.10 eq https
                        access-list ACL-DMZ extended permit tcp any host 203.0.113.10 eq https
                        access-group ACL-IN in interface inside
                        access-group ACL-DMZ in interface dmz
                        """));
        var names = new AsaInterfaceNames(() -> List.of(subAndVlanPolicy)).load();
        assertEquals(Map.of(DEVICE, Map.of(
                "Port-channel1.100", "inside",
                "Vlan200", "dmz",
                "GigabitEthernet0/0", "outside")), names);

        var subIface = new InventoryInterface("if-po1.100", "Port-channel1.100", Optional.of("Port-channel1"),
                InventoryInterface.KIND_SUBINTERFACE, InventoryInterface.STATE_UP,
                List.of(new InventoryAddress("a-in", "198.51.100.1/24", InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER)));
        var vlanIface = new InventoryInterface("if-vlan200", "Vlan200", Optional.empty(),
                InventoryInterface.KIND_VLAN, InventoryInterface.STATE_UP,
                List.of(new InventoryAddress("a-dmz", "192.0.2.1/24", InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER)));
        var physIface = iface("GigabitEthernet0/0", "203.0.113.1/24");
        var unmappedIface = new InventoryInterface("if-gi0/2.300", "GigabitEthernet0/2.300", Optional.of("GigabitEthernet0/2"),
                InventoryInterface.KIND_SUBINTERFACE, InventoryInterface.STATE_UP,
                List.of(new InventoryAddress("a-unmapped", "198.51.100.129/25", InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER)));

        var viaNextHop = new InventoryRoute("r-default", "0.0.0.0/0", Optional.of("203.0.113.254"), Optional.of("outside"),
                InventoryRoute.PROTOCOL_DEFAULT, Optional.empty(), Optional.of(true), Optional.empty(), Optional.empty(),
                Optional.of(InventoryRoute.NEXT_HOP_IP));
        var run = new InventoryRun("run-asa-sub", DEVICE, "job-asa-sub", Instant.parse("2026-11-04T06:00:00Z"), 1,
                List.of(new InventoryContext(InventoryContext.PHYSICAL,
                        List.of(subIface, vlanIface, physIface, unmappedIface),
                        List.of(
                                connected("198.51.100.0/24", "inside"),
                                connected("192.0.2.0/24", "dmz"),
                                connected("203.0.113.0/24", "outside"),
                                viaNextHop))));
        var device = new TopologyInput.DeviceFacts(DEVICE, TopologyInput.VENDOR_CISCO_ASA, Optional.of("FW-ALPHA-01"),
                Optional.empty(), Optional.of(run));
        TopologyGraph g = TopologyBuilder.build(new TopologyInput("tps-asa-sub", Instant.parse("2026-11-04T07:00:00Z"),
                List.of(device), List.of(), "synthetic-asa-topology-salt-0002".getBytes(StandardCharsets.UTF_8), List.of(), names));

        var policies = new PathFixtures.Policies().bind(DEVICE, subAndVlanPolicy, new HopPolicyEvaluator.Device(DEVICE, "", null));

        // Subinterface (Port-channel1.100 -> inside) to VLAN interface (Vlan200 -> dmz).
        var subToVlan = onlyHop(PathEngine.analyze(g, policies, PathQuery.of("198.51.100.20", "192.0.2.10", "tcp", 443), COMPUTED_AT));
        assertEquals("ALLOWED", subToVlan.ownVerdict());
        assertEquals(Optional.of("inside"), subToVlan.ingress().interfaceName());
        assertEquals(Optional.of("inside"), subToVlan.ingress().zone());
        assertEquals(Optional.of("dmz"), subToVlan.egress().interfaceName());
        assertEquals(Optional.of("dmz"), subToVlan.egress().zone());

        // VLAN interface (Vlan200 -> dmz) to physical interface (GigabitEthernet0/0 -> outside).
        var vlanToPhys = onlyHop(PathEngine.analyze(g, policies, PathQuery.of("192.0.2.20", "203.0.113.10", "tcp", 443), COMPUTED_AT));
        assertEquals("ALLOWED", vlanToPhys.ownVerdict());
        assertEquals(Optional.of("dmz"), vlanToPhys.ingress().zone());
        assertEquals(Optional.of("outside"), vlanToPhys.egress().zone());

        // Unmapped ingress subinterface (GigabitEthernet0/2.300): keeps inventory name, no zone, hop is UNKNOWN with reason.
        var unmappedIngress = onlyHop(PathEngine.analyze(g, policies, PathQuery.of("198.51.100.140", "192.0.2.10", "tcp", 443), COMPUTED_AT));
        assertEquals(Optional.of("GigabitEthernet0/2.300"), unmappedIngress.ingress().interfaceName());
        assertEquals(Optional.empty(), unmappedIngress.ingress().zone());
        assertEquals("UNKNOWN", unmappedIngress.ownVerdict());
        assertEquals(HopPolicyEvaluator.ASA_INTERFACE_UNKNOWN, unmappedIngress.reason());
    }

    @Test
    void anUnmappedEgressRouteInterfaceNeverGuessesAnotherAttachmentOnTheSegment() {
        var partialPolicy = AsaPolicyMapper.map(DEVICE, "Cisco ASA " + DEVICE, "2026-11-04T05:00:00Z", "artefact",
                AsaConfigPolicyParser.parse("""
                        ASA Version 9.18(4)22
                        interface GigabitEthernet0/1
                         nameif inside
                         security-level 100
                        interface GigabitEthernet0/2
                         nameif dmz
                         security-level 50
                        access-list ACL-IN extended permit ip any any
                        access-group ACL-IN in interface inside
                        """));
        var names = new AsaInterfaceNames(() -> List.of(partialPolicy)).load();
        // Route names "outside" (whose physical interface was not in the stored config), while 203.0.113.0/24 is attached on dmz.
        var viaUnmapped = new InventoryRoute("r-out", "198.51.100.128/25", Optional.of("203.0.113.254"), Optional.of("outside"),
                InventoryRoute.PROTOCOL_STATIC, Optional.empty(), Optional.of(true), Optional.empty(), Optional.empty(),
                Optional.of(InventoryRoute.NEXT_HOP_IP));
        var run = new InventoryRun("run-asa-unmapped-egress", DEVICE, "job-asa", Instant.parse("2026-11-04T06:00:00Z"), 1,
                List.of(new InventoryContext(InventoryContext.PHYSICAL,
                        List.of(iface("GigabitEthernet0/1", "198.51.100.1/25"), iface("GigabitEthernet0/2", "203.0.113.1/24")),
                        List.of(connected("198.51.100.0/25", "inside"), connected("203.0.113.0/24", "dmz"), viaUnmapped))));
        var device = new TopologyInput.DeviceFacts(DEVICE, TopologyInput.VENDOR_CISCO_ASA, Optional.of("FW-ALPHA-01"),
                Optional.empty(), Optional.of(run));
        TopologyGraph g = TopologyBuilder.build(new TopologyInput("tps-asa-eg", Instant.parse("2026-11-04T07:00:00Z"),
                List.of(device), List.of(), "synthetic-asa-topology-salt-0003".getBytes(StandardCharsets.UTF_8), List.of(), names));
        var policies = new PathFixtures.Policies().bind(DEVICE, partialPolicy, new HopPolicyEvaluator.Device(DEVICE, "", null));
        var hop = onlyHop(PathEngine.analyze(g, policies, PathQuery.of("198.51.100.20", "198.51.100.200", "tcp", 443), COMPUTED_AT));
        assertEquals("UNKNOWN", hop.ownVerdict());
        assertEquals(HopPolicyEvaluator.ASA_INTERFACE_UNKNOWN, hop.reason());
        assertEquals(Optional.of("outside"), hop.egress().interfaceName());
        assertEquals(Optional.empty(), hop.egress().zone(), "never guesses dmz from the segment");
    }

    @Test
    void topologyLinkOverridesByPhysicalInterfaceNameResolveToTheMappedNameif() {
        var policy1 = AsaPolicyMapper.map("dev-asa-1", "Cisco ASA dev-asa-1", "2026-11-04T05:00:00Z", "artefact",
                AsaConfigPolicyParser.parse("""
                        ASA Version 9.18(4)22
                        interface GigabitEthernet0/1.10
                         vlan 10
                         nameif inside
                         security-level 100
                        interface GigabitEthernet0/0
                         nameif transit
                         security-level 50
                        access-list ACL-IN extended permit tcp any host 203.0.113.10 eq https
                        access-group ACL-IN in interface inside
                        """));
        var policy2 = AsaPolicyMapper.map("dev-asa-2", "Cisco ASA dev-asa-2", "2026-11-04T05:00:00Z", "artefact",
                AsaConfigPolicyParser.parse("""
                        ASA Version 9.18(4)22
                        interface Port-channel1.20
                         vlan 20
                         nameif transit
                         security-level 50
                        interface Vlan30
                         nameif outside
                         security-level 0
                        access-list ACL-TR extended permit tcp any host 203.0.113.10 eq https
                        access-group ACL-TR in interface transit
                        """));
        var names = new AsaInterfaceNames(() -> List.of(policy1, policy2)).load();
        // Neither ASA has a route corroborating the transit link in inventory; a FORCE_LINK override names the physical interfaces.
        var rToPeer = new InventoryRoute("r-to-2", "203.0.113.0/24", Optional.of("192.0.2.2"), Optional.of("transit"),
                InventoryRoute.PROTOCOL_STATIC, Optional.empty(), Optional.of(true), Optional.empty(), Optional.empty(),
                Optional.of(InventoryRoute.NEXT_HOP_IP));
        var rBack = new InventoryRoute("r-to-1", "198.51.100.0/24", Optional.of("192.0.2.1"), Optional.of("transit"),
                InventoryRoute.PROTOCOL_STATIC, Optional.empty(), Optional.of(true), Optional.empty(), Optional.empty(),
                Optional.of(InventoryRoute.NEXT_HOP_IP));
        var run1 = new InventoryRun("run-1", "dev-asa-1", "job-1", Instant.parse("2026-11-04T06:00:00Z"), 1,
                List.of(new InventoryContext(InventoryContext.PHYSICAL,
                        List.of(iface("GigabitEthernet0/1.10", "198.51.100.1/24"), iface("GigabitEthernet0/0", "192.0.2.1/30")),
                        List.of(connected("198.51.100.0/24", "inside"), rToPeer))));
        var run2 = new InventoryRun("run-2", "dev-asa-2", "job-2", Instant.parse("2026-11-04T06:00:00Z"), 1,
                List.of(new InventoryContext(InventoryContext.PHYSICAL,
                        List.of(iface("Port-channel1.20", "192.0.2.2/30"), iface("Vlan30", "203.0.113.1/24")),
                        List.of(connected("203.0.113.0/24", "outside"), rBack))));
        var d1 = new TopologyInput.DeviceFacts("dev-asa-1", TopologyInput.VENDOR_CISCO_ASA, Optional.of("FW-ALPHA-01"),
                Optional.empty(), Optional.of(run1));
        var d2 = new TopologyInput.DeviceFacts("dev-asa-2", TopologyInput.VENDOR_CISCO_ASA, Optional.of("FW-BRAVO-02"),
                Optional.empty(), Optional.of(run2));
        var override = new TopologyLinkOverride("tlo-asa-1", TopologyLinkOverride.ACTION_FORCE_LINK, "STATIC_L3",
                Optional.of("dev-asa-1"), InventoryContext.PHYSICAL, "GigabitEthernet0/0",
                Optional.of("dev-asa-2"), InventoryContext.PHYSICAL, "Port-channel1.20",
                Optional.empty(), "DUPLICATE_PRIVATE_PREFIX_SAME_NETWORK", Optional.empty(), "synthetic-actor",
                Instant.parse("2026-11-04T06:30:00Z"), Optional.empty(), Optional.empty(), Optional.empty());
        TopologyGraph g = TopologyBuilder.build(new TopologyInput("tps-asa-2", Instant.parse("2026-11-04T07:00:00Z"),
                List.of(d1, d2), List.of(override), "synthetic-asa-topology-salt-0004".getBytes(StandardCharsets.UTF_8),
                List.of(), names));
        assertEquals(TopologyGraph.OverrideStatus.NO_EFFECT, g.override("tlo-asa-1").orElseThrow().status());

        var policies = new PathFixtures.Policies()
                .bind("dev-asa-1", policy1, new HopPolicyEvaluator.Device("dev-asa-1", "", null))
                .bind("dev-asa-2", policy2, new HopPolicyEvaluator.Device("dev-asa-2", "", null));
        PathResult result = PathEngine.analyze(g, policies, PathQuery.of("198.51.100.20", "203.0.113.10", "tcp", 443), COMPUTED_AT);
        assertEquals("ALLOWED", result.verdict());
        assertEquals(2, result.branches().get(0).hops().size());
        var hop1 = result.branches().get(0).hops().get(0);
        var hop2 = result.branches().get(0).hops().get(1);
        assertEquals(Optional.of("inside"), hop1.ingress().zone());
        assertEquals(Optional.of("transit"), hop1.egress().zone());
        assertEquals(Optional.of("transit"), hop2.ingress().zone());
        assertEquals(Optional.of("outside"), hop2.egress().zone());
    }
}
