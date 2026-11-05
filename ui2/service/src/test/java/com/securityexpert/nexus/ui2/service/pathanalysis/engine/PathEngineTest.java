package com.securityexpert.nexus.ui2.service.pathanalysis.engine;

import static com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathFixtures.*;
import static com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathReasons.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;
import com.securityexpert.nexus.ui2.service.policy.HopPolicyEvaluator;

/**
 * Stage A4 path engine: the verdict-lattice edges, the bounds and the fail-closed behaviour on bad data that the golden
 * scenarios do not show. Synthetic estates and policies only (see {@link PathFixtures}).
 */
class PathEngineTest {

    static final PathQuery TO_SERVER = PathQuery.of("198.51.100.20", "203.0.113.10", "tcp", 443);

    static PathResult run(TopologyGraph graph, HopPolicySource policies, PathQuery query) {
        return PathEngine.analyze(graph, policies, query, COMPUTED_AT);
    }

    static Policies chain() {
        return PathEngineGoldenTest.chainPolicies();
    }

    static String route(String destination, String nextHop, String iface, String table) {
        return "{\"destination\": \"" + destination + "\"" + (nextHop == null ? "" : ", \"next_hop\": \"" + nextHop + "\"")
                + ", \"interface\": \"" + iface + "\", \"protocol\": \"static\"" + (table == null ? "" : ", \"table\": \"" + table + "\"")
                + ", \"kind\": \"IP\", \"active\": true}";
    }

    // ---- stale policy and caveats ---------------------------------------------------------------------------------

    @Test
    void stalePolicyIsUnknownAndKeepsTheLastKnownRuleAsContext() {
        var policies = chain().stale("dev-t04", PathEngineGoldenTest.cpChain("dev-t04"), cpDevice("dev-t04"));
        PathResult r = run(graph("chain.json"), policies, TO_SERVER);
        var hop = r.branches().get(0).hops().get(0);
        assertEquals(UNKNOWN, hop.ownVerdict());
        assertEquals(STALE_OR_OUT_OF_SYNC_POLICY, hop.reason());
        assertTrue(hop.policyStale());
        assertEquals("RULE-r1", hop.security().orElseThrow().decidingRule().orElseThrow().get("name"), "last-known rule shown");
        assertEquals(CONDITIONAL, r.branches().get(0).hops().get(1).verdict());
        assertEquals(UNKNOWN, r.verdict());
        assertEquals("STALE", r.executiveSummary().freshnessStatus());
    }

    @Test
    void anAppIdAssumedHopIsAllowedButNeverPlain() {
        // Panorama-shaped: the local-firewall projection does not store application objects (no default ports).
        var policies = chain().bind("dev-b02", panorama("dev-b02", panSecurity("RULE-APPDEF-01", "ZONE-TRANSIT", "ZONE-DMZ",
                "OBJ-WEB-01", "application-default", "APP-SYNTH-01", "allow")
                + panSecurity("RULE-DENY-01", "any", "any", "any", "any", "any", "deny"), PAN_SNAT), panDevice("dev-b02"));
        PathResult r = run(graph("chain.json"), policies, PathQuery.of("198.51.100.20", "203.0.113.10", "tcp", 8443));
                assertEquals(ALLOWED, r.verdict());
        assertFalse(r.plain());
        assertEquals(List.of(HopPolicyEvaluator.APP_ID_ASSUMED), r.caveats());
        assertTrue(r.executiveSummary().headline().endsWith("with caveats"));
    }

    // ---- routing facts ---------------------------------------------------------------------------------------------

    @Test
    void aRouteWithoutAnActiveFactMakesTheHopUnknown() {
        JsonNode estate = editRoutes(estate("chain.json"), "dev-t04", r -> r.remove("active"));
        PathResult r = run(graph(estate), chain(), TO_SERVER);
        var hop = r.branches().get(0).hops().get(0);
        assertEquals(UNKNOWN, hop.ownVerdict());
        assertTrue(hop.reasons().contains(ROUTE_ACTIVE_UNKNOWN));
        assertEquals(UNKNOWN, r.verdict());
        assertEquals(UNKNOWN_HOP, r.reason());
        // The return walk crosses the same unit on an unproven route: symmetry is not proven either.
        assertEquals(INCOMPLETE, r.branches().get(0).returnPath().status());
    }

    @Test
    void anInactiveRouteIsNeverRoutedOver() {
        JsonNode estate = editRoutes(estate("chain.json"), "dev-t04", r -> {
            if (r.get("destination").asText().equals("203.0.113.0/26")) {
                r.put("active", false);
            }
        });
        PathResult r = run(graph(estate), chain(), TO_SERVER);
        // The default route to the unenrolled router is the active match now.
        assertEquals(EXITS_KNOWN_TOPOLOGY, r.branches().get(0).termination());
    }

    @Test
    void equalRoutesThatAreNotProvenEcmpAreAmbiguousUnlessAMetricSeparatesThem() {
        JsonNode plain = editRoutes(estate("ecmp.json"), "dev-t04", r -> r.remove("ecmp"));
        PathResult ambiguous = run(graph(plain), PathEngineGoldenTest.ecmpPolicies(), TO_SERVER);
        assertEquals(2, ambiguous.branches().size());
        assertTrue(ambiguous.branches().get(0).hops().get(0).reasons().contains(ROUTE_SELECTION_AMBIGUOUS));
        assertEquals(UNKNOWN, ambiguous.branches().get(0).verdict());

        JsonNode metric = editRoutes(plain, "dev-t04", r -> {
            if (r.has("next_hop")) {
                r.put("metric", r.get("next_hop").asText().endsWith(".2") ? 10 : 20);
            }
        });
        PathResult chosen = run(graph(metric), PathEngineGoldenTest.ecmpPolicies(), TO_SERVER);
        assertEquals(1, chosen.branches().size());
        assertEquals(ALLOWED, chosen.verdict());
        assertEquals(Optional.of("192.0.2.2"), chosen.branches().get(0).hops().get(0).route().orElseThrow().nextHop());
    }

    @Test
    void checkPointRoutingThatDiffersBeforeAndAfterNatIsUnknown() {
        JsonNode estate = editRoutes(estate("chain.json"), "dev-t04", r -> {
            if (r.get("destination").asText().equals("203.0.113.192/26")) {
                r.put("next_hop", "198.51.100.130");
                r.put("interface", "eth3");
            }
        });
        PathResult r = run(graph(estate), chain(), PathQuery.of("198.51.100.20", "203.0.113.200", "tcp", 443));
        var hop = r.branches().get(0).hops().get(0);
        assertEquals(UNKNOWN, hop.ownVerdict());
        assertEquals(CP_ROUTE_AFTER_NAT_UNKNOWN, hop.reason());
        assertEquals(CONDITIONAL, r.branches().get(0).hops().get(1).verdict(), "traced on after the post-NAT route");
    }

    @Test
    void anUnparseableRouteRowTaintsTheLookup() {
        JsonNode estate = addRoute(estate("chain.json"), "dev-t04", route("not-a-prefix", "192.0.2.2", "eth2", null));
        var hop = run(graph(estate), chain(), TO_SERVER).branches().get(0).hops().get(0);
        assertEquals(UNKNOWN, hop.ownVerdict());
        assertTrue(hop.reasons().contains(ROUTE_TABLE_UNPARSEABLE));
    }

    // ---- ingress ---------------------------------------------------------------------------------------------------

    @Test
    void aSourceBehindACloudEntersAtTheUnitWithTheMostSpecificRouteTowardIt() {
        JsonNode estate = addRoute(estate("chain.json"), "dev-t04", route("192.0.2.128/25", "198.51.100.130", "eth3", null));
        TopologyGraph g = graph(estate);
        PathResult r = run(g, chain(), PathQuery.of("192.0.2.140", "203.0.113.10", "tcp", 443));
        assertEquals(INFERRED, r.ingress().mode());
        assertEquals("RESOLVED", r.ingress().status());
        assertEquals(node(g, "FW-TANGO-04").nodeId(), r.ingress().candidates().get(0).nodeId());
        assertEquals(Optional.of("eth3"), r.branches().get(0).hops().get(0).ingress().interfaceName());
        assertEquals(DENIED, r.verdict(), "the source is not a client: the cleanup rule drops it");
        // Only a default route toward the source: never guessed.
        PathResult none = run(g, chain(), PathQuery.of("203.0.113.150", "203.0.113.10", "tcp", 443));
        assertEquals(INGRESS_NOT_FOUND, none.reason());
    }

    @Test
    void anIngressHintThatMatchesNothingIsRefusedAsUnknown() {
        TopologyGraph g = graph("chain.json");
        assertEquals(INGRESS_HINT_NOT_FOUND, run(g, chain(), TO_SERVER.withIngress("tn-missing", null)).reason());
        assertEquals(INGRESS_INTERFACE_NOT_FOUND,
                run(g, chain(), TO_SERVER.withIngress(node(g, "FW-TANGO-04").nodeId(), "eth9")).reason());
    }

    @Test
    void aClusterWithoutAProvableActiveMemberIsUnknown() {
        JsonNode estate = estate("chain.json").deepCopy();
        ObjectNode tango = (ObjectNode) estate.get("devices").get(0);
        tango.put("cluster_member_ref", "cls-ref-romeo");
        ((ObjectNode) tango.get("run")).putArray("ha").addObject().put("context", "physical").put("role", "STANDBY");
        TopologyGraph g = graph(estate);
        PathResult r = run(g, chain(), TO_SERVER.withIngress(node(g, "FW-TANGO-04").nodeId(), null));
        var hop = r.branches().get(0).hops().get(0);
        assertEquals(UNKNOWN, hop.ownVerdict());
        assertEquals(ACTIVE_MEMBER_UNPROVEN, hop.reason());
        assertTrue(hop.caveats().contains(CLUSTER_UNCORROBORATED));
        assertEquals(NO_ROUTE, r.branches().get(0).termination(), "nothing is routed on an unproven table");
        assertEquals(ACTIVE_MEMBER_UNPROVEN, r.branches().get(0).reason(), "the cause, not the symptom");
        assertEquals(ACTIVE_MEMBER_UNPROVEN, r.reason());
    }

    // ---- why a branch is UNKNOWN (diagnostics only: no verdict changes) --------------------------------------------

    /** A copy of the estate without the routes of one device that match {@code drop}. */
    static JsonNode withoutRoutes(JsonNode estate, String deviceId, java.util.function.Predicate<JsonNode> drop) {
        JsonNode copy = estate.deepCopy();
        for (JsonNode d : copy.get("devices")) {
            if (d.get("device_id").asText().equals(deviceId)) {
                for (JsonNode c : d.get("run").get("contexts")) {
                    var routes = (com.fasterxml.jackson.databind.node.ArrayNode) c.get("routes");
                    for (int i = routes.size() - 1; i >= 0; i--) {
                        if (drop.test(routes.get(i))) {
                            routes.remove(i);
                        }
                    }
                }
            }
        }
        return copy;
    }

    @Test
    void noRouteOnAnEmptyTableIsReportedAsAnEmptyTable() {
        // FW-BRAVO-02 keeps its interfaces (and its corroborated transit link) but no route was collected for it.
        PathResult r = run(graph(withoutRoutes(estate("chain.json"), "dev-b02", x -> true)), chain(), TO_SERVER);
        assertEquals(UNKNOWN, r.verdict());
        assertEquals(NO_ROUTE, r.branches().get(0).termination());
        assertEquals(ROUTE_TABLE_EMPTY, r.reason());
        // A table that has routes, none of them covering the destination, is still plain NO_ROUTE.
        JsonNode noDefault = withoutRoutes(estate("chain.json"), "dev-t04", x -> "0.0.0.0/0".equals(x.get("destination").asText()));
        PathResult none = run(graph(noDefault), chain(), PathQuery.of("198.51.100.20", "203.0.113.150", "tcp", 443));
        assertEquals(UNKNOWN, none.verdict());
        assertEquals(NO_ROUTE, none.reason());
    }

    @Test
    void anExitThroughAVsxWarpLinkIsNamed() {
        JsonNode estate = addRoute(estate("chain.json"), "dev-t04", route("203.0.113.128/26", "192.0.2.18", "wrp0", null));
        ObjectNode tango = (ObjectNode) estate.get("devices").get(0).get("run").get("contexts").get(0);
        ObjectNode warp = ((com.fasterxml.jackson.databind.node.ArrayNode) tango.get("interfaces")).addObject().put("name", "wrp0");
        warp.putArray("addresses").addObject().put("address", "192.0.2.17/30").put("role", "member");
        PathResult r = run(graph(estate), chain(), PathQuery.of("198.51.100.20", "203.0.113.150", "tcp", 443));
        assertEquals(EXITS_KNOWN_TOPOLOGY, r.reason());
        var hop = r.branches().get(0).hops().get(0);
        assertTrue(hop.notes().contains(EXIT_VIA_VSX_WARP));
        assertFalse(hop.notes().contains(EXIT_VIA_DEFAULT_ROUTE));
    }

    @Test
    void anAddressAUnitHoldsItselfIsACaveatNeverAPlainAnswer() {
        TopologyGraph g = graph("chain.json");
        // FW-TANGO-04's client-LAN address to FW-BRAVO-02's DMZ address: to- and from-the-box traffic is not modelled.
        PathResult r = run(g, chain(), PathQuery.of("198.51.100.1", "203.0.113.1", "tcp", 443));
        assertEquals(DENIED, r.verdict(), "the verdict itself is unchanged");
        assertTrue(r.caveats().containsAll(List.of(SOURCE_IS_UNIT_ADDRESS, DESTINATION_IS_UNIT_ADDRESS)));
        PathResult host = run(g, chain(), TO_SERVER);
        assertTrue(host.plain(), "a host address is not a unit address");
    }

    // ---- bounds ----------------------------------------------------------------------------------------------------

    @Test
    void theTotalBranchBoundTruncates() {
        PathResult r = run(graph("truncation.json"), new Policies().bind("dev-t04", PathEngineGoldenTest.cpChain("dev-t04"),
                cpDevice("dev-t04")), PathQuery.of("198.51.100.20", "203.0.113.150", "udp", 53).withEdgeMode(true)
                .withLimits(new PathQuery.Limits(32, 8, 4, 4_000)));
        assertEquals(4, r.branches().size());
        assertTrue(r.ecmpTruncated());
        assertEquals(UNKNOWN, r.verdict());
    }

    @Test
    void hopLimitAndWorkBudgetEndTheBranchUnknown() {
        TopologyGraph g = graph("chain.json");
        PathResult hops = run(g, chain(), TO_SERVER.withLimits(new PathQuery.Limits(1, 8, 16, 4_000)));
        assertEquals(HOP_LIMIT, hops.branches().get(0).termination());
        assertEquals(UNKNOWN, hops.verdict());
        PathResult work = run(g, chain(), TO_SERVER.withLimits(new PathQuery.Limits(32, 8, 16, 1)));
        assertEquals(ANALYSIS_BUDGET, work.branches().get(0).termination());
        assertThrows(IllegalArgumentException.class, () -> new PathQuery.Limits(33, 8, 16, 4_000), "never above the default");
        assertThrows(IllegalArgumentException.class, () -> new PathQuery.Limits(32, 9, 16, 4_000));
        assertThrows(IllegalArgumentException.class, () -> new PathQuery.Limits(32, 8, 17, 4_000));
    }

    // ---- bad data never throws ---------------------------------------------------------------------------------------

    @Test
    void aFailingPolicySourceIsAnUnknownHopNotAnException() {
        HopPolicySource broken = node -> {
            throw new IllegalStateException("synthetic failure 203.0.113.10");
        };
        PathResult r = run(graph("chain.json"), broken, TO_SERVER);
        assertEquals(POLICY_SOURCE_FAILED, r.branches().get(0).hops().get(0).reason());
        assertEquals(UNKNOWN, r.verdict());
        PathResult none = PathEngine.analyze(graph("chain.json"), null, TO_SERVER, COMPUTED_AT);
        assertEquals(POLICY_BINDING_UNPROVEN, none.branches().get(0).hops().get(0).reason());
    }

    @Test
    void aMissingGraphIsAnUnknownResultWithoutDetail() {
        PathResult r = PathEngine.analyze(null, chain(), TO_SERVER, COMPUTED_AT);
        assertEquals(UNKNOWN, r.verdict());
        assertEquals(ENGINE_DATA_ERROR, r.reason());
        assertTrue(r.branches().isEmpty());
    }

    @Test
    void aMalformedQueryIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PathQuery.of("2001:db8::1", "203.0.113.10", "tcp", 443));
        assertThrows(IllegalArgumentException.class, () -> PathQuery.of("198.51.100.20", "203.0.113.10/32", "tcp", 443));
        assertThrows(IllegalArgumentException.class, () -> PathQuery.of("198.051.100.20", "203.0.113.10", "tcp", 443));
        assertThrows(IllegalArgumentException.class, () -> PathQuery.of("198.51.100.20", "203.0.113.10", "icmp", 443));
        assertThrows(IllegalArgumentException.class, () -> PathQuery.of("198.51.100.20", "203.0.113.10", "TCP", 443));
        assertThrows(IllegalArgumentException.class, () -> PathQuery.of("198.51.100.20", "203.0.113.10", "tcp", 0));
        assertThrows(IllegalArgumentException.class, () -> PathQuery.of("198.51.100.20", "203.0.113.10", "tcp", 65536));
    }
}
