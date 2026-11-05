package com.securityexpert.nexus.ui2.service.pathanalysis.engine;

import static com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathFixtures.*;
import static com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathReasons.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;

/**
 * Golden path scenarios, stage A4 (PATH_ANALYSIS_DESIGN.md §5 / §7 A4): synthetic estates built by the real A3 builder,
 * policies of the real snapshot shapes, documentation addresses and pseudonym names (FW-TANGO-04 ...). Each scenario
 * asserts its load-bearing verdict facts explicitly, and all of them together are rendered into one golden text so a
 * change in any hop field is visible. A mismatch writes the actual rendering to {@code build/path-golden/}; the golden
 * is only ever updated by hand.
 */
class PathEngineGoldenTest {

    // ---- policies ---------------------------------------------------------------------------------------------------

    /** CP: publish 203.0.113.200 as the DMZ web server (static destination NAT); clients may go anywhere. */
    static PolicySnapshot cpChain(String... devices) {
        return checkPoint(List.of(cpAccess("r1", 1, "clients", "any", "any", "Accept"), cpAccess("r2", 2, "any", "any", "any", "Drop")),
                List.of(cpNat("n1", 1, of("any", "public", "any"), of("original", "web", "original"))), devices);
    }

    /** PAN: source NAT into the DMZ; HTTPS to the web server only, everything else denied. */
    static PolicySnapshot panHttpsOnly(String deviceId, String nat) {
        return paloAlto(deviceId, panSecurity("RULE-WEB-01", "ZONE-TRANSIT", "ZONE-DMZ", "OBJ-WEB-01", "SVC-HTTPS", "any", "allow")
                + panSecurity("RULE-DENY-01", "any", "any", "any", "any", "any", "deny"), nat);
    }

    static PolicySnapshot panDenyAll(String deviceId) {
        return paloAlto(deviceId, panSecurity("RULE-DENY-01", "any", "any", "any", "any", "any", "deny"), "");
    }

    static Policies chainPolicies() {
        return new Policies().bind("dev-t04", cpChain("dev-t04"), cpDevice("dev-t04"))
                .bind("dev-b02", panHttpsOnly("dev-b02", PAN_SNAT), panDevice("dev-b02"));
    }

    /** ECMP estate: FW-BRAVO-02 allows HTTPS, FW-JULIET-06 denies everything. */
    static Policies ecmpPolicies() {
        return new Policies().bind("dev-t04", cpChain("dev-t04"), cpDevice("dev-t04"))
                .bind("dev-b02", panHttpsOnly("dev-b02", ""), panDevice("dev-b02"))
                .bind("dev-j06", panDenyAll("dev-j06"), panDevice("dev-j06"));
    }

    static PathResult run(TopologyGraph graph, HopPolicySource policies, PathQuery query) {
        return PathEngine.analyze(graph, policies, query, COMPUTED_AT);
    }

    // ---- scenarios ----------------------------------------------------------------------------------------------------

    record Scenario(String name, TopologyGraph graph, PathResult result) {
    }

    static List<Scenario> scenarios() {
        TopologyGraph chain = graph("chain.json"), ecmp = graph("ecmp.json"), asym = graph("asymmetric.json"),
                many = graph("truncation.json");
        var https = PathQuery.of("198.51.100.20", "203.0.113.200", "tcp", 443);
        var ssh = PathQuery.of("198.51.100.20", "203.0.113.200", "tcp", 22);
        var external = PathQuery.of("198.51.100.20", "203.0.113.150", "tcp", 443);
        // Straight to the server: with the CP hop unbound its NAT is unknown, so a published address would not route on.
        var direct = PathQuery.of("198.51.100.20", "203.0.113.10", "tcp", 443);
        var directSsh = PathQuery.of("198.51.100.20", "203.0.113.10", "tcp", 22);
        Policies cpUnbound = new Policies().bind("dev-b02", panHttpsOnly("dev-b02", PAN_SNAT), panDevice("dev-b02"));
        Policies ecmpPolicies = ecmpPolicies();
        Policies asymPolicies = new Policies().bind("dev-t04", cpChain("dev-t04", "dev-j06"), cpDevice("dev-t04"))
                .bind("dev-j06", cpChain("dev-t04", "dev-j06"), cpDevice("dev-j06"))
                .bind("dev-b02", panHttpsOnly("dev-b02", ""), panDevice("dev-b02"));
        String tango = node(asym, "FW-TANGO-04").nodeId();
        // PAN destination NAT: the published address routes back toward the transit (provisional zone), the server
        // address into the DMZ (second lookup); security names the pre-NAT address with the post-NAT zone.
        TopologyGraph published = graph(addRoute(estate("chain.json"), "dev-b02", "{\"destination\": \"203.0.113.192/26\", "
                + "\"next_hop\": \"192.0.2.1\", \"interface\": \"ethernet1/1\", \"protocol\": \"static\", \"table\": \"vr-main\", "
                + "\"kind\": \"IP\", \"active\": true}"));
        Policies panDnat = new Policies()
                .bind("dev-t04", checkPoint(List.of(cpAccess("r1", 1, "clients", "any", "any", "Accept")), List.of(), "dev-t04"), cpDevice("dev-t04"))
                .bind("dev-b02", paloAlto("dev-b02", panSecurity("RULE-PUBLISH-01", "ZONE-TRANSIT", "ZONE-DMZ", "OBJ-PUBLIC-01", "SVC-HTTPS", "any", "allow")
                        + panSecurity("RULE-DENY-01", "any", "any", "any", "any", "any", "deny"), PAN_DNAT), panDevice("dev-b02"));
        Policies manyPolicies = new Policies().bind("dev-t04", cpChain("dev-t04"), cpDevice("dev-t04"));
        // A more specific route toward part of the DMZ through the unenrolled uplink router: the branch leaves the known
        // topology although the destination sits on FW-BRAVO-02's DMZ segment.
        TopologyGraph detour = graph(addRoute(estate("chain.json"), "dev-t04", "{\"destination\": \"203.0.113.0/27\", "
                + "\"next_hop\": \"198.51.100.130\", \"interface\": \"eth3\", \"protocol\": \"static\", \"kind\": \"IP\", \"active\": true}"));
        return List.of(
                new Scenario("allowed-cp-pan-nat", chain, run(chain, chainPolicies(), https)),
                new Scenario("denied-at-second-hop", chain, run(chain, chainPolicies(), ssh)),
                new Scenario("unknown-hop-taints-downstream", chain, run(chain, cpUnbound, direct)),
                new Scenario("unknown-hop-then-deny", chain, run(chain, cpUnbound, directSsh)),
                new Scenario("pan-dnat-second-lookup", published, run(published, panDnat, https)),
                new Scenario("exits-known-topology", chain, run(chain, chainPolicies(), external)),
                new Scenario("edge-mode", chain, run(chain, chainPolicies(), external.withEdgeMode(true))),
                new Scenario("discard-route", chain, run(chain, chainPolicies(), PathQuery.of("198.51.100.20", "203.0.113.70", "tcp", 443))),
                new Scenario("ecmp-partial", ecmp, run(ecmp, ecmpPolicies, PathQuery.of("198.51.100.20", "203.0.113.10", "tcp", 443))),
                new Scenario("asymmetric-return", asym, run(asym, asymPolicies,
                        PathQuery.of("198.51.100.20", "203.0.113.10", "tcp", 443).withIngress(tango, "eth1"))),
                new Scenario("ambiguous-ingress", asym, run(asym, asymPolicies, PathQuery.of("198.51.100.20", "203.0.113.10", "tcp", 443))),
                new Scenario("ambiguous-ingress-candidates-agree", asym, run(asym, asymPolicies,
                        PathQuery.of("198.51.100.20", "203.0.113.10", "tcp", 22))),
                new Scenario("exit-destination-in-known-topology", detour, run(detour, chainPolicies(), direct)),
                new Scenario("routing-loop", asym, run(asym, asymPolicies,
                        PathQuery.of("198.51.100.20", "203.0.113.130", "tcp", 443).withIngress(tango, "eth1"))),
                new Scenario("ecmp-truncated", many, run(many, manyPolicies,
                        PathQuery.of("198.51.100.20", "203.0.113.150", "udp", 53).withEdgeMode(true))));
    }

    static PathResult scenario(String name) {
        return scenarios().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow().result();
    }

    @Test
    void allowedThroughCheckPointAndPaloAltoWithNatBothWays() {
        PathResult r = scenario("allowed-cp-pan-nat");
        assertEquals(ALLOWED, r.verdict());
        assertTrue(r.plain(), "no caveat on either hop");
        assertEquals(INFERRED, r.ingress().mode());
        var hops = r.branches().get(0).hops();
        assertEquals(2, hops.size());
        // CP: destination NAT 203.0.113.200 -> 203.0.113.10, access equal on both tuples.
        assertEquals("203.0.113.10", hops.get(0).packetOut().destination());
        assertTrue(hops.get(0).postNatSecurity().isPresent());
        // PAN: ingress zone from the ingress interface, source NAT, delivery on the DMZ.
        assertEquals(Optional.of("ZONE-TRANSIT"), hops.get(1).ingress().zone());
        assertEquals(Optional.of("ZONE-DMZ"), hops.get(1).egress().zone());
        assertEquals("203.0.113.1", hops.get(1).packetOut().source());
        assertEquals(DELIVERED, r.branches().get(0).termination());
        assertEquals(SYMMETRIC, r.branches().get(0).returnPath().status());
        assertTrue(r.branches().get(0).returnPath().branches().get(0).hops().stream().allMatch(h -> h.reverseTranslated()));
    }

    @Test
    void deniedAtTheSecondHopWhenTheFirstIsDecided() {
        PathResult r = scenario("denied-at-second-hop");
        assertEquals(DENIED, r.verdict());
        assertEquals(DENIED_AT_HOP, r.reason());
        assertEquals(Optional.of(new PathResult.HopRef("b1", 2)), r.blockingHop());
        assertEquals("Traffic is DENIED at hop 2 by rule 2", r.executiveSummary().headline());
    }

    @Test
    void anUnknownHopTaintsTheBranchAndDownstreamHopsAreConditional() {
        PathResult r = scenario("unknown-hop-taints-downstream");
        assertEquals(UNKNOWN, r.verdict());
        var hops = r.branches().get(0).hops();
        assertEquals(UNKNOWN, hops.get(0).ownVerdict());
        assertEquals(POLICY_BINDING_UNPROVEN, hops.get(0).reason());
        assertEquals(ALLOWED, hops.get(1).ownVerdict());
        assertEquals(CONDITIONAL, hops.get(1).verdict());
        // A deny after an unknown hop is never an overall DENIED.
        PathResult denied = scenario("unknown-hop-then-deny");
        assertEquals(UNKNOWN, denied.verdict());
        assertEquals(DENIED_AFTER_UNKNOWN_HOP, denied.reason());
        assertEquals(List.of(new PathResult.HopRef("b1", 2)), denied.executiveSummary().blockingHops());
    }

    @Test
    void paloAltoDestinationNatUsesTheSecondLookupForZoneAndForwarding() {
        PathResult r = scenario("pan-dnat-second-lookup");
        assertEquals(ALLOWED, r.verdict());
        var pan = r.branches().get(0).hops().get(1);
        assertEquals("203.0.113.10", pan.packetOut().destination());
        assertEquals(Optional.of("ZONE-DMZ"), pan.egress().zone(), "forwarded on the post-NAT route");
        assertEquals("RULE-PUBLISH-01", pan.security().orElseThrow().decidingRule().orElseThrow().get("name"));
        assertEquals(SYMMETRIC, r.branches().get(0).returnPath().status());
    }

    @Test
    void exitIntoACloudIsUnknownUnlessEdgeModeIsAsked() {
        assertEquals(UNKNOWN, scenario("exits-known-topology").verdict());
        assertEquals(EXITS_KNOWN_TOPOLOGY, scenario("exits-known-topology").reason());
        PathResult edge = scenario("edge-mode");
        assertEquals(ALLOWED_TO_EDGE, edge.verdict());
        assertEquals(NOT_COMPUTED, edge.branches().get(0).returnPath().status());
    }

    @Test
    void aDiscardRouteDropsAtTheFirstHop() {
        PathResult r = scenario("discard-route");
        assertEquals(DENIED, r.verdict());
        assertEquals(ROUTE_DISCARD, r.branches().get(0).hops().get(0).reason());
    }

    @Test
    void mixedEcmpBranchesArePartial() {
        PathResult r = scenario("ecmp-partial");
        assertEquals(PARTIAL, r.verdict());
        assertEquals(2, r.branches().size());
        assertEquals(List.of(ALLOWED, DENIED), r.branches().stream().map(PathResult.Branch::verdict).toList());
        assertTrue(r.branches().get(0).hops().get(0).route().orElseThrow().ecmpBranch());
        assertFalse(r.ecmpTruncated());
    }

    @Test
    void anAsymmetricReturnIsNeverAllowed() {
        PathResult r = scenario("asymmetric-return");
        assertEquals(UNKNOWN, r.verdict());
        assertEquals(ASYMMETRIC_RETURN, r.reason());
        assertTrue(r.asymmetric());
        assertTrue(r.warnings().contains(OUT_OF_STATE_DROP_LIKELY));
        assertEquals(GIVEN, r.ingress().mode());
    }

    @Test
    void tiedSourceSegmentsAreAllTracedAndOnlyAUnanimousVerdictStands() {
        // The same client LAN on two gateways is never auto-joined: without a hint the ingress is ambiguous. Both
        // candidates are traced; here one returns asymmetrically and one is allowed, so nothing is claimed.
        PathResult ambiguous = scenario("ambiguous-ingress");
        assertEquals(UNKNOWN, ambiguous.verdict());
        assertEquals(AMBIGUOUS_INGRESS, ambiguous.reason());
        assertEquals(AMBIGUOUS_INGRESS, ambiguous.ingress().status());
        assertEquals(2, ambiguous.ingress().candidates().size());
        assertEquals(java.util.Set.of(UNKNOWN, ALLOWED),
                ambiguous.branches().stream().map(PathResult.Branch::verdict).collect(java.util.stream.Collectors.toSet()));
        assertFalse(ambiguous.caveats().contains(INGRESS_CANDIDATES_AGREE));
        // SSH is denied at FW-BRAVO-02 whichever gateway the client sits behind: the verdict does not depend on the
        // undecided ingress, which stays AMBIGUOUS_INGRESS and is carried as a caveat.
        PathResult agree = scenario("ambiguous-ingress-candidates-agree");
        assertEquals(DENIED, agree.verdict());
        assertEquals(DENIED_AT_HOP, agree.reason());
        assertEquals(AMBIGUOUS_INGRESS, agree.ingress().status());
        assertEquals(2, agree.branches().size());
        assertTrue(agree.branches().stream().allMatch(b -> DENIED.equals(b.verdict())));
        assertTrue(agree.caveats().contains(INGRESS_CANDIDATES_AGREE));
    }

    @Test
    void anExitSaysWhyItLeftTheKnownTopology() {
        PathResult viaDefault = scenario("exits-known-topology");
        var exitHop = viaDefault.branches().get(0).hops().get(0);
        assertTrue(exitHop.notes().contains(EXIT_VIA_DEFAULT_ROUTE));
        assertFalse(viaDefault.warnings().contains(DESTINATION_IN_KNOWN_TOPOLOGY), "203.0.113.150 is on no known segment");
        PathResult detour = scenario("exit-destination-in-known-topology");
        assertEquals(UNKNOWN, detour.verdict());
        assertEquals(EXITS_KNOWN_TOPOLOGY, detour.reason());
        assertTrue(detour.warnings().contains(DESTINATION_IN_KNOWN_TOPOLOGY));
        assertFalse(detour.branches().get(0).hops().get(0).notes().contains(EXIT_VIA_DEFAULT_ROUTE), "a /27, not a default");
    }

    @Test
    void aRoutingLoopEndsTheBranchUnknown() {
        PathResult r = scenario("routing-loop");
        assertEquals(UNKNOWN, r.verdict());
        assertEquals(ROUTING_LOOP, r.branches().get(0).termination());
        assertEquals(2, r.branches().get(0).hops().size());
    }

    @Test
    void moreThanEightEcmpNextHopsAreTruncatedAndTheVerdictIsUnknown() {
        PathResult r = scenario("ecmp-truncated");
        assertTrue(r.ecmpTruncated());
        assertEquals(8, r.branches().size());
        assertTrue(r.branches().stream().allMatch(b -> ALLOWED_TO_EDGE.equals(b.verdict())));
        assertEquals(UNKNOWN, r.verdict(), "an unexplored branch counts as UNKNOWN");
        assertEquals(ECMP_TRUNCATED, r.reason());
    }

    @Test
    void everyScenarioMatchesTheGolden() throws IOException {
        StringBuilder all = new StringBuilder();
        for (Scenario s : scenarios()) {
            all.append(render(s.name(), s.graph(), s.result())).append('\n');
        }
        String actual = all.toString();
        String expected = text("scenarios.golden.txt");
        if (expected == null || !expected.equals(actual)) {
            Path out = Path.of("build", "path-golden", "scenarios.golden.txt.actual");
            Files.createDirectories(out.getParent());
            Files.writeString(out, actual);
        }
        assertNotNull(expected, "missing golden scenarios.golden.txt");
        assertEquals(expected, actual);
    }

    @Test
    void theResultIsDeterministic() {
        assertEquals(scenarios().stream().map(Scenario::result).toList(), scenarios().stream().map(Scenario::result).toList());
    }
}
