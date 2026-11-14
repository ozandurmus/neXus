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
        assertNotEquals("ALLOWED", run(g, policy(""), 443).verdict());
        assertNotEquals("DENIED", run(g, policy(""), 22).verdict());
    }
}
