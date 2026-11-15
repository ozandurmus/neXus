package com.securityexpert.nexus.ui2.service.pathanalysis.engine;

import static com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathFixtures.COMPUTED_AT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGateConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyMapper;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.PathPolicyBinder;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.PathPolicyCatalog;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyBuilder;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyInput;
import com.securityexpert.nexus.ui2.service.policy.HopNatEvaluator;
import com.securityexpert.nexus.ui2.service.policy.HopPolicyEvaluator;

/**
 * FortiGate through Trace (FortiGate policy step 8): VDOM units in the topology carry their FortiOS interface names as
 * zones, {@link PathPolicyBinder} binds {@code physical -> root} and each named VDOM to its own VDOM snapshot, and any
 * VIP, central SNAT or reachable policy SNAT rule that may touch the packet keeps the hop UNKNOWN (A0-FG-17..22).
 * Synthetic addresses and masked names only.
 */
class PathEngineFortiGateTest {

    static final String DEVICE = "dev-fgt-1";
    static final String PEER = "dev-fgt-2";
    static final String BASE = """
            #config-version=FG10E1-7.4.4-FW-build2662-240514:opmode=0:vdom=0
            config system interface
                edit "port1"
                    set vdom "root"
                    set ip 198.51.100.1 255.255.255.0
                next
                edit "port2"
                    set vdom "root"
                    set ip 203.0.113.1 255.255.255.0
                next
            end
            config firewall address
                edit "ADDR-CLIENT"
                    set subnet 198.51.100.0 255.255.255.0
                next
                edit "ADDR-WEB"
                    set subnet 203.0.113.10 255.255.255.255
                next
                edit "ADDR-OTHER"
                    set subnet 192.0.2.0 255.255.255.0
                next
            end
            config firewall service custom
                edit "SVC-HTTPS"
                    set tcp-portrange 443
                next
            end
            """;
    static final String ALLOW_HTTPS = """
            config firewall policy
                edit 1
                    set name "ALLOW-HTTPS"
                    set srcintf "port1"
                    set dstintf "port2"
                    set srcaddr "ADDR-CLIENT"
                    set dstaddr "ADDR-WEB"
                    set action accept
                    set schedule "always"
                    set service "SVC-HTTPS"
                next
            end
            """;

    static List<PolicySnapshot> snapshots(String config) {
        return FortiGatePolicyMapper.map(DEVICE, "FortiGate " + DEVICE, "2026-11-04T05:00:00Z", "artefact",
                FortiGateConfigPolicyParser.parse(config));
    }

    static HopPolicySource binder(List<PolicySnapshot> snapshots) {
        var catalog = new PathPolicyCatalog() {
            @Override
            public List<PolicySnapshot.Metadata> catalog() {
                return snapshots.stream().map(PolicySnapshot::metadata).toList();
            }

            @Override
            public Optional<PolicySnapshot> find(String policyId) {
                return snapshots.stream().filter(s -> s.metadata().id().equals(policyId)).findFirst();
            }

            @Override
            public List<PolicyCollectionRepository.TargetBinding> bindings(String policyId) {
                return List.of();
            }

            @Override
            public Optional<String> panSerial(String deviceId) {
                return Optional.empty();
            }
        };
        return new PathPolicyBinder(catalog).forQuery(COMPUTED_AT);
    }

    static InventoryRoute connected(String destination, String iface) {
        return new InventoryRoute("r-" + destination + "-" + iface, destination, Optional.empty(), Optional.of(iface),
                "connected", Optional.empty(), Optional.of(true), Optional.empty(), Optional.empty(), Optional.of("CONNECTED"));
    }

    static InventoryRoute staticVia(String destination, String nextHop, String iface) {
        return new InventoryRoute("r-" + destination + "-" + nextHop, destination, Optional.of(nextHop), Optional.of(iface),
                "static", Optional.empty(), Optional.of(true), Optional.empty(), Optional.empty(), Optional.of("IP"));
    }

    static InventoryInterface iface(String name, String address) {
        return new InventoryInterface("if-" + name, name, Optional.empty(), "physical", "up",
                List.of(new InventoryAddress("a-" + address, address, InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER)));
    }

    static TopologyGraph singleVdomGraph() {
        var run = new InventoryRun("run-fgt", DEVICE, "job-fgt", Instant.parse("2026-11-04T06:00:00Z"), 1,
                List.of(new InventoryContext(InventoryContext.PHYSICAL,
                        List.of(iface("port1", "198.51.100.1/24"), iface("port2", "203.0.113.1/24")),
                        List.of(connected("198.51.100.0/24", "port1"), connected("203.0.113.0/24", "port2")))));
        var device = new TopologyInput.DeviceFacts(DEVICE, TopologyInput.VENDOR_FORTINET, Optional.of("FW-FOXTROT-12"),
                Optional.empty(), Optional.of(run));
        return TopologyBuilder.build(new TopologyInput("tps-fgt", Instant.parse("2026-11-04T07:00:00Z"), List.of(device),
                List.of(), "synthetic-fgt-topology-salt-0001".getBytes(StandardCharsets.UTF_8)));
    }

    static PathResult run(TopologyGraph graph, List<PolicySnapshot> snapshots, int port) {
        return PathEngine.analyze(graph, binder(snapshots), PathQuery.of("198.51.100.20", "203.0.113.10", "tcp", port),
                COMPUTED_AT);
    }

    static PathResult.Hop onlyHop(PathResult result) {
        assertEquals(1, result.branches().size());
        assertEquals(1, result.branches().get(0).hops().size());
        return result.branches().get(0).hops().get(0);
    }

    @Test
    void aSingleVdomFortiGateAttachesInterfacesAsZonesAndEvaluatesRootPolicyAndImplicitDeny() {
        TopologyGraph g = singleVdomGraph();
        assertEquals(1, g.nodes().size());
        assertEquals(TopologyInput.VENDOR_FORTINET, g.nodes().get(0).vendor());
        assertEquals(List.of("port1", "port2"), g.attachments().stream().map(a -> a.interfaceName().orElseThrow()).sorted().toList());
        assertTrue(g.attachments().stream().allMatch(a -> a.zone().equals(a.interfaceName())));
        assertTrue(g.attachments().stream().allMatch(TopologyGraph.Attachment::corroborated));

        var policy = snapshots(BASE + ALLOW_HTTPS);
        var allowed = onlyHop(run(g, policy, 443));
        assertEquals("ALLOWED", allowed.ownVerdict(), allowed.reasons().toString());
        assertEquals(HopPolicyEvaluator.FIRST_MATCHING_RULE, allowed.reason());
        assertEquals(Optional.of("port1"), allowed.ingress().zone());
        assertEquals(Optional.of("port2"), allowed.egress().interfaceName());
        assertEquals(HopNatEvaluator.NO_TRANSLATION, allowed.nat().orElseThrow().status());

        var denied = onlyHop(run(g, policy, 22));
        assertEquals("DENIED", denied.ownVerdict());
        assertEquals(HopPolicyEvaluator.FGT_IMPLICIT_DENY, denied.reason());
        assertEquals("DENIED", run(g, policy, 22).verdict());
    }

    @Test
    void aMultiVdomHaPairRoutesThroughPrimaryAndEvaluatesEachVdomsOwnPolicy() {
        var primaryContextIn = new InventoryContext("VD-INGRESS",
                List.of(iface("port1", "198.51.100.1/24"), iface("vlink0", "192.0.2.1/30")),
                List.of(connected("198.51.100.0/24", "port1"), connected("192.0.2.0/30", "vlink0"),
                        staticVia("203.0.113.0/24", "192.0.2.2", "vlink0")));
        var primaryContextOut = new InventoryContext("VD-EGRESS",
                List.of(iface("vlink1", "192.0.2.2/30"), iface("port2", "203.0.113.1/24")),
                List.of(connected("192.0.2.0/30", "vlink1"), connected("203.0.113.0/24", "port2"),
                        staticVia("198.51.100.0/24", "192.0.2.1", "vlink1")));
        var primaryRun = new InventoryRun("run-fgt-p", DEVICE, "job-p", Instant.parse("2026-11-04T06:00:00Z"), 2,
                List.of(primaryContextIn, primaryContextOut),
                List.of(new InventoryHaFact("ha-p", InventoryContext.PHYSICAL, "primary", Optional.of("a-p"),
                        InventoryHaFact.SOURCE_FGT_HA_STATUS)));
        var secondaryRun = new InventoryRun("run-fgt-s", PEER, "job-s", Instant.parse("2026-11-04T06:00:00Z"), 2,
                List.of(primaryContextIn, primaryContextOut),
                List.of(new InventoryHaFact("ha-s", InventoryContext.PHYSICAL, "secondary", Optional.of("a-p"),
                        InventoryHaFact.SOURCE_FGT_HA_STATUS)));
        var d1 = new TopologyInput.DeviceFacts(DEVICE, TopologyInput.VENDOR_FORTINET, Optional.of("FW-FOXTROT-12"),
                Optional.of("cls-ref-fgt"), Optional.of(primaryRun));
        var d2 = new TopologyInput.DeviceFacts(PEER, TopologyInput.VENDOR_FORTINET, Optional.of("FW-FOXTROT-13"),
                Optional.of("cls-ref-fgt"), Optional.of(secondaryRun));
        TopologyGraph g = TopologyBuilder.build(new TopologyInput("tps-fgt-ha", Instant.parse("2026-11-04T07:00:00Z"),
                List.of(d1, d2), List.of(), "synthetic-fgt-topology-salt-0002".getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, g.nodes().size());
        assertTrue(g.nodes().stream().allMatch(n -> Optional.of(DEVICE).equals(n.activeMemberDeviceId())));
        assertTrue(g.nodes().stream().noneMatch(n -> n.flags().contains(TopologyGraph.NodeFlag.ACTIVE_MEMBER_UNPROVEN)));

        var vdomSnapshots = snapshots("""
                #config-version=FG10E1-7.4.4-FW-build2662-240514:opmode=0:vdom=1
                config vdom
                edit VD-INGRESS
                next
                edit VD-EGRESS
                next
                end
                config vdom
                edit VD-INGRESS
                config firewall address
                    edit "ADDR-CLIENT"
                        set subnet 198.51.100.0 255.255.255.0
                    next
                    edit "ADDR-WEB"
                        set subnet 203.0.113.10 255.255.255.255
                    next
                end
                config firewall service custom
                    edit "SVC-HTTPS"
                        set tcp-portrange 443
                    next
                    edit "SVC-SSH"
                        set tcp-portrange 22
                    next
                end
                config firewall policy
                    edit 1
                        set name "IN-ALLOW"
                        set srcintf "port1"
                        set dstintf "vlink0"
                        set srcaddr "ADDR-CLIENT"
                        set dstaddr "ADDR-WEB"
                        set action accept
                        set schedule "always"
                        set service "SVC-HTTPS" "SVC-SSH"
                    next
                end
                end
                config vdom
                edit VD-EGRESS
                config firewall address
                    edit "ADDR-CLIENT"
                        set subnet 198.51.100.0 255.255.255.0
                    next
                    edit "ADDR-WEB"
                        set subnet 203.0.113.10 255.255.255.255
                    next
                end
                config firewall service custom
                    edit "SVC-HTTPS"
                        set tcp-portrange 443
                    next
                end
                config firewall policy
                    edit 1
                        set name "OUT-ALLOW-HTTPS"
                        set srcintf "vlink1"
                        set dstintf "port2"
                        set srcaddr "ADDR-CLIENT"
                        set dstaddr "ADDR-WEB"
                        set action accept
                        set schedule "always"
                        set service "SVC-HTTPS"
                    next
                end
                end
                """);
        var allowed = run(g, vdomSnapshots, 443);
        assertEquals("ALLOWED", allowed.verdict(), allowed.reason());
        assertEquals(2, allowed.branches().get(0).hops().size());
        assertEquals("VD-INGRESS", allowed.branches().get(0).hops().get(0).context());
        assertEquals("VD-EGRESS", allowed.branches().get(0).hops().get(1).context());

        var blocked = run(g, vdomSnapshots, 22);
        assertEquals("DENIED", blocked.verdict());
        assertEquals(2, blocked.blockingHop().orElseThrow().hopIndex());
        assertEquals(HopPolicyEvaluator.FGT_IMPLICIT_DENY, blocked.branches().get(0).hops().get(1).reason());
    }

    @Test
    void vipCentralSnatAndPolicySnatRulesTouchingTheTupleKeepTheHopUnknownWhileUnrelatedOnesDoNot() {
        TopologyGraph g = singleVdomGraph();
        var touchingVip = snapshots(BASE + """
                config firewall vip
                    edit "VIP-WEB"
                        set extip 203.0.113.10
                        set mappedip "192.0.2.10"
                        set extintf "port1"
                    next
                end
                """ + ALLOW_HTTPS);
        var vipHop = onlyHop(run(g, touchingVip, 443));
        assertEquals("UNKNOWN", vipHop.ownVerdict());
        assertEquals(HopPolicyEvaluator.NAT_UNKNOWN, vipHop.reason());
        assertEquals(HopNatEvaluator.FGT_NAT_NOT_EVALUATED, vipHop.nat().orElseThrow().reason());

        var unrelatedVip = snapshots(BASE + """
                config firewall vip
                    edit "VIP-OTHER"
                        set extip 192.0.2.50
                        set mappedip "192.0.2.150"
                        set extintf "port1"
                    next
                end
                """ + ALLOW_HTTPS);
        var unrelatedVipHop = onlyHop(run(g, unrelatedVip, 443));
        assertEquals("ALLOWED", unrelatedVipHop.ownVerdict());
        assertEquals(HopNatEvaluator.NAT_NO_RULE_MATCHED, unrelatedVipHop.nat().orElseThrow().reason());

        var touchingCentralSnat = snapshots(BASE + ALLOW_HTTPS + """
                config firewall central-snat-map
                    edit 1
                        set srcintf "port1"
                        set dstintf "port2"
                        set orig-addr "ADDR-CLIENT"
                        set dst-addr "ADDR-WEB"
                    next
                end
                """);
        var snatHop = onlyHop(run(g, touchingCentralSnat, 443));
        assertEquals("UNKNOWN", snatHop.ownVerdict());
        assertEquals(HopPolicyEvaluator.NAT_UNKNOWN, snatHop.reason());
        assertEquals(HopNatEvaluator.FGT_NAT_NOT_EVALUATED, snatHop.nat().orElseThrow().reason());

        var policySnat = snapshots(BASE + """
                config firewall policy
                    edit 1
                        set name "ALLOW-HTTPS-SNAT"
                        set srcintf "port1"
                        set dstintf "port2"
                        set srcaddr "ADDR-CLIENT"
                        set dstaddr "ADDR-WEB"
                        set action accept
                        set schedule "always"
                        set service "SVC-HTTPS"
                        set nat enable
                    next
                end
                """);
        var policySnatHop = onlyHop(run(g, policySnat, 443));
        assertEquals("UNKNOWN", policySnatHop.ownVerdict());
        assertEquals(HopPolicyEvaluator.NAT_UNKNOWN, policySnatHop.reason());
        assertEquals(HopNatEvaluator.FGT_NAT_NOT_EVALUATED, policySnatHop.nat().orElseThrow().reason());
        assertEquals("ALLOWED", policySnatHop.security().orElseThrow().verdict(), "the security evaluation stays as context");

        var laterUnreachedPolicySnat = snapshots(BASE + """
                config firewall policy
                    edit 1
                        set name "ALLOW-HTTPS-NO-NAT"
                        set srcintf "port1"
                        set dstintf "port2"
                        set srcaddr "ADDR-CLIENT"
                        set dstaddr "ADDR-WEB"
                        set action accept
                        set schedule "always"
                        set service "SVC-HTTPS"
                    next
                    edit 2
                        set name "LATER-SNAT"
                        set srcintf "port1"
                        set dstintf "port2"
                        set srcaddr "ADDR-CLIENT"
                        set dstaddr "ADDR-WEB"
                        set action accept
                        set schedule "always"
                        set service "SVC-HTTPS"
                        set nat enable
                    next
                end
                """);
        var unreachedHop = onlyHop(run(g, laterUnreachedPolicySnat, 443));
        assertEquals("ALLOWED", unreachedHop.ownVerdict());
        assertEquals("NO_TRANSLATION", unreachedHop.nat().orElseThrow().status());
        assertEquals(HopNatEvaluator.NAT_NO_RULE_MATCHED, unreachedHop.nat().orElseThrow().reason());
    }

    @Test
    void zonePlacementsAndIntrazoneAllowStayUnknownPerA0Amendment() {
        TopologyGraph g = singleVdomGraph();
        var zonePolicy = snapshots(BASE + """
                config system zone
                    edit "ZN-INSIDE"
                        set intrazone allow
                        set interface "port1"
                    next
                end
                config firewall policy
                    edit 1
                        set name "ZONE-RULE"
                        set srcintf "ZN-INSIDE"
                        set dstintf "port2"
                        set srcaddr "ADDR-CLIENT"
                        set dstaddr "ADDR-WEB"
                        set action accept
                        set schedule "always"
                        set service "SVC-HTTPS"
                    next
                end
                """);
        var zoneHop = onlyHop(run(g, zonePolicy, 443));
        assertEquals("UNKNOWN", zoneHop.ownVerdict());
        assertEquals(HopPolicyEvaluator.EARLIER_RULE_UNRESOLVED, zoneHop.reason());

        var intrazonePolicy = snapshots(BASE + """
                config system zone
                    edit "ZN-INSIDE"
                        set intrazone allow
                        set interface "port1"
                    next
                end
                config firewall policy
                    edit 1
                        set name "ALLOW-HTTPS"
                        set srcintf "port1"
                        set dstintf "port2"
                        set srcaddr "ADDR-CLIENT"
                        set dstaddr "ADDR-WEB"
                        set action accept
                        set schedule "always"
                        set service "SVC-HTTPS"
                    next
                end
                """);
        var intrazoneFallthrough = onlyHop(run(g, intrazonePolicy, 22));
        assertEquals("UNKNOWN", intrazoneFallthrough.ownVerdict());
        assertEquals(HopPolicyEvaluator.FGT_INTRAZONE_UNKNOWN, intrazoneFallthrough.reason());
    }
}
