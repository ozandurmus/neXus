package com.securityexpert.nexus.ui2.service.pathanalysis.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.Ipv4Prefix;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyBuilder;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyFixtures;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyInput;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyView;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * Regression for the 2026-11-04 17:30 live symptom: aiview took the host part of attachment addresses from
 * {@code GET /api/v2/topology} and 11 of 20 path queries were refused with {@code AMBIGUOUS_PSEUDONYM}.
 *
 * <p>Root cause, reproduced here with the real masker: the reverse map accepted a raw candidate whenever the submitted
 * value is the host part of {@code h/len} under ANY real prefix containing it (a snapshot network or a registered
 * subnet). The masker keys a pseudonym network on {@code len - 4} hashed bits only ({@code 240.0.0.0/4} plus at most
 * 24 hash bits), so a short prefix the estate routes -- a {@code /8}, {@code /10}, {@code /12} or {@code /16}
 * summary -- has 16 to about 3,800 possible masked networks. Any masked host whose leading bits match one of them
 * makes a second, different raw host under that summary "consistent" with the value, although no response ever
 * rendered that host with that length. The value aiview saw in the topology is then refused as ambiguous.</p>
 *
 * <p>The synthetic estate mirrors the live shapes: {@code /23} (with {@code /24}s inside), {@code /24}, {@code /27},
 * {@code /28}, {@code /30} segments, {@code /32} local routes, a Check Point cluster with a VIP on both members, the
 * same range in two VRFs, and summary routes. Documentation and benchmark ranges (RFC 5737, RFC 2544) and the
 * well-known private summaries only.</p>
 */
class PathPseudonymOverlapTest {

    private static final byte[] KEY = "synthetic-path-overlap-mask-key-1104".getBytes(StandardCharsets.UTF_8);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SUMMARIES = List.of("10.0.0.0/8", "100.64.0.0/10", "172.16.0.0/12",
            "192.168.0.0/16", "198.18.0.0/15", "10.1.0.0/16", "10.2.0.0/16", "10.3.0.0/16", "0.0.0.0/0");
    private static final int ROUTERS = 8;

    private final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(KEY);
    private final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(KEY);
    private final PrivacyMaskingResponseBodyAdvice advice = new PrivacyMaskingResponseBodyAdvice(ips, names);
    private ServletServerHttpRequest aiview;
    private TopologyGraph graph;
    /** The two raw /16 interface hosts whose masked /16 networks coincide under KEY (found below, never printed). */
    private List<String> collidingPair;

    @BeforeEach
    void setUp() {
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)).thenReturn(true);
        aiview = mock(ServletServerHttpRequest.class);
        when(aiview.getServletRequest()).thenReturn(http);
        collidingPair = collidingSixteens();
        graph = TopologyBuilder.build(new TopologyInput("tps-overlap-1104", Instant.parse("2026-11-04T17:30:00Z"),
                TopologyFixtures.devices(estate()), List.of(), "synthetic-overlap-1104-salt".getBytes(StandardCharsets.UTF_8)));
    }

    // ---- the synthetic estate ------------------------------------------------------------------------------------

    /** Two raw hosts on different /16 interfaces whose masked /16 networks are equal: a genuine pseudonym collision. */
    private List<String> collidingSixteens() {
        Map<String, Integer> seen = new HashMap<>();
        for (int a = 16; a < 256; a++) {
            String masked = ips.maskNetworkWithoutSideEffects("10." + a + ".0.0/16");
            Integer before = seen.putIfAbsent(masked, a);
            if (before != null) {
                return List.of("10." + before + ".0.1", "10." + a + ".0.1");
            }
        }
        throw new IllegalStateException("no /16 collision under the test key");
    }

    private ObjectNode estate() {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode devices = root.putArray("devices");
        // Routed segments shared by router pairs, each router at offset 1 or 2 (.95 and .125 on the larger ones).
        List<String> segments = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            segments.add("198.18." + (i * 2) + ".0/23");
        }
        for (int i = 0; i < 8; i++) {
            segments.add("198.18." + (16 + i) + ".0/24");
        }
        for (int i = 0; i < 8; i++) {
            segments.add("198.18.32." + (i * 32) + "/27");
        }
        for (int i = 0; i < 16; i++) {
            segments.add("198.18.33." + (i * 16) + "/28");
        }
        for (int i = 0; i < 32; i++) {
            segments.add("198.18.34." + (i * 4) + "/30");
        }
        Map<Integer, List<String[]>> interfacesOf = new TreeMap<>();
        for (int s = 0; s < segments.size(); s++) {
            Ipv4Prefix p = Ipv4Prefix.parse(segments.get(s)).orElseThrow();
            int a = s % ROUTERS;
            int b = (s + 1 + s / ROUTERS) % ROUTERS;
            if (b == a) {
                b = (a + 1) % ROUTERS;
            }
            boolean large = p.length() <= 24;
            interfacesOf.computeIfAbsent(a, k -> new ArrayList<>())
                    .add(new String[] {host(p, 1), segments.get(s)});
            interfacesOf.computeIfAbsent(b, k -> new ArrayList<>())
                    .add(new String[] {host(p, large ? (s % 2 == 0 ? 95 : 125) : 2), segments.get(s)});
        }
        for (int r = 0; r < ROUTERS; r++) {
            devices.add(paRouter("dev-r" + r, "FW-ROUTER-" + r, "vr-main", interfacesOf.get(r), true));
        }
        // The same documentation range in two VRFs of two units: the masker renders the same raw text the same way.
        devices.add(paRouter("dev-va", "FW-VRF-A", "vr-a",
                List.of(new String[] {"203.0.113.1/24", "203.0.113.0/24"}, new String[] {collidingPair.get(0) + "/16",
                        collidingPair.get(0).replaceAll("\\.0\\.1$", ".0.0") + "/16"}), false));
        devices.add(paRouter("dev-vb", "FW-VRF-B", "vr-b",
                List.of(new String[] {"203.0.113.1/24", "203.0.113.0/24"}, new String[] {collidingPair.get(1) + "/16",
                        collidingPair.get(1).replaceAll("\\.0\\.1$", ".0.0") + "/16"}), false));
        // A Check Point cluster: members .2/.3 with the VIP .1 on both (bare, as cphaprob reports it) and a /28 VIP.
        devices.add(cpMember("dev-c1", "FW-TANGO-04", "ACTIVE", "192.0.2.2/26", "192.0.2.18/28"));
        devices.add(cpMember("dev-c2", "FW-TANGO-05", "STANDBY", "192.0.2.3/26", "192.0.2.19/28"));
        return root;
    }

    private ObjectNode paRouter(String id, String hostname, String vrf, List<String[]> interfaces, boolean summaries) {
        ObjectNode d = JSON.createObjectNode();
        d.put("device_id", id).put("vendor", "palo_alto").put("hostname", hostname);
        ObjectNode run = d.putObject("run");
        run.put("run_id", "run-" + id).put("collected_at", "2026-11-04T17:00:00Z");
        ObjectNode ctx = run.putArray("contexts").addObject();
        ctx.put("context", "vsys1");
        ArrayNode ifs = ctx.putArray("interfaces");
        ArrayNode routes = ctx.putArray("routes");
        for (int i = 0; i < interfaces.size(); i++) {
            String name = "ethernet1/" + (i + 1);
            String address = interfaces.get(i)[0];
            String network = interfaces.get(i)[1];
            ObjectNode ifn = ifs.addObject();
            ifn.put("name", name).put("zone", "ZONE-" + (i + 1)).put("routing_context", vrf);
            ifn.putArray("addresses").addObject().put("address", address).put("role", "member");
            route(routes, network, name, "connected", vrf, "CONNECTED", null);
            route(routes, address.substring(0, address.indexOf('/')) + "/32", name, "local", vrf, "CONNECTED", null);
            Ipv4Prefix p = Ipv4Prefix.parse(network).orElseThrow();
            if (p.length() == 23) { // the /24 halves of a /23, as a more specific route table shows them
                route(routes, new Ipv4Prefix(p.network(), 24).toString(), name, "static", vrf, "IP", null);
            }
        }
        if (summaries) {
            String nextHop = interfaces.get(0)[0].substring(0, interfaces.get(0)[0].indexOf('/'));
            for (String summary : SUMMARIES) {
                route(routes, summary, "ethernet1/1", "static", vrf, "IP", nextHop);
            }
        }
        return d;
    }

    private ObjectNode cpMember(String id, String hostname, String role, String lan, String dmz) {
        ObjectNode d = JSON.createObjectNode();
        d.put("device_id", id).put("vendor", "check_point").put("hostname", hostname)
                .put("cluster_member_ref", "cls-ref-overlap");
        ObjectNode run = d.putObject("run");
        run.put("run_id", "run-" + id).put("collected_at", "2026-11-04T17:00:00Z");
        run.putArray("ha").addObject().put("context", "physical").put("role", role);
        ObjectNode ctx = run.putArray("contexts").addObject();
        ctx.put("context", "physical");
        ArrayNode ifs = ctx.putArray("interfaces");
        ObjectNode eth1 = ifs.addObject();
        eth1.put("name", "eth1");
        ArrayNode a1 = eth1.putArray("addresses");
        a1.addObject().put("address", lan).put("role", "member");
        a1.addObject().put("address", "192.0.2.1").put("role", "cluster_virtual");
        ObjectNode eth2 = ifs.addObject();
        eth2.put("name", "eth2");
        ArrayNode a2 = eth2.putArray("addresses");
        a2.addObject().put("address", dmz).put("role", "member");
        a2.addObject().put("address", "192.0.2.17/28").put("role", "cluster_virtual");
        ArrayNode routes = ctx.putArray("routes");
        route(routes, "192.0.2.0/26", "eth1", "connected", null, "CONNECTED", null);
        route(routes, "192.0.2.16/28", "eth2", "connected", null, "CONNECTED", null);
        for (String summary : SUMMARIES) {
            route(routes, summary, "eth1", "static", null, "IP", "192.0.2.4");
        }
        return d;
    }

    private static void route(ArrayNode routes, String destination, String iface, String protocol, String table,
            String kind, String nextHop) {
        ObjectNode r = routes.addObject();
        r.put("destination", destination).put("interface", iface).put("protocol", protocol).put("kind", kind)
                .put("active", true);
        if (table != null) {
            r.put("table", table);
        }
        if (nextHop != null) {
            r.put("next_hop", nextHop);
        }
    }

    private static String host(Ipv4Prefix p, int offset) {
        return PathPseudonymMap.text(p.network() + offset) + "/" + p.length();
    }

    // ---- what aiview saw -----------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> render(Map<String, Object> body) {
        return (Map<String, Object>) advice.beforeBodyWrite(body, null, null, null, aiview, null);
    }

    /** aiview read every unit's route table first (registers the summaries, /32 local routes and /24 halves). */
    private void routeTablesFirst() {
        for (TopologyGraph.Node n : graph.nodes()) {
            List<Object> rows = new ArrayList<>();
            for (InventoryRoute r : n.routes()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("destination", r.destination());
                r.nextHop().ifPresent(h -> row.put("next_hop", h));
                rows.add(row);
            }
            render(new LinkedHashMap<>(Map.of("routes", rows)));
        }
    }

    /** Masked host shown in the topology -> the raw hosts it was rendered from (one, unless two render the same). */
    @SuppressWarnings("unchecked")
    private Map<String, Set<String>> shownAttachmentHosts() {
        Map<String, Object> rendered = render(TopologyView.topology(Optional.of(graph), Optional.empty()));
        List<Map<String, Object>> raw = (List<Map<String, Object>>) TopologyView.topology(Optional.of(graph),
                Optional.empty()).get("attachments");
        List<Map<String, Object>> masked = (List<Map<String, Object>>) rendered.get("attachments");
        Map<String, Set<String>> rawByShown = new TreeMap<>();
        for (int i = 0; i < masked.size(); i++) {
            if (masked.get(i).get("address") instanceof String shown && raw.get(i).get("address") instanceof String r) {
                rawByShown.computeIfAbsent(hostPart(shown), k -> new TreeSet<>()).add(hostPart(r));
            }
        }
        return rawByShown;
    }

    private static String hostPart(String address) {
        int slash = address.indexOf('/');
        return slash < 0 ? address : address.substring(0, slash);
    }

    // ---- tests ---------------------------------------------------------------------------------------------------

    @Test
    void everyAttachmentHostTheTopologyShowedResolvesToTheHostItWasRenderedFrom() {
        routeTablesFirst();
        Map<String, Set<String>> shown = shownAttachmentHosts();
        assertTrue(shown.size() > 120, "the estate renders " + shown.size() + " attachment hosts");
        PathPseudonymMap map = PathPseudonymMap.build(graph, PathPseudonymMap.over(ips, names));
        List<String> failures = new ArrayList<>();
        int resolved = 0;
        for (Map.Entry<String, Set<String>> e : shown.entrySet()) {
            PathPseudonymMap.Resolution got = map.resolveAddress(e.getKey());
            PathPseudonymMap.Resolution want = e.getValue().size() == 1
                    ? new PathPseudonymMap.Resolved(e.getValue().iterator().next())
                    : new PathPseudonymMap.Refused(PathPseudonymMap.AMBIGUOUS_PSEUDONYM);
            if (!want.equals(got)) {
                failures.add(String.valueOf(got)); // the code or "Resolved[withheld]", never a raw value
            } else if (got instanceof PathPseudonymMap.Resolved) {
                resolved++;
            }
        }
        assertEquals(List.of(), failures, failures.size() + " of " + shown.size() + " shown hosts misresolved");
        assertEquals(shown.size() - 1, resolved, "all but the one genuinely colliding value resolve");
    }

    /**
     * The reproduction: the any-prefix rule ({@link PathPseudonymMap#acceptedUnderAnyPrefix}, the only rule before
     * the fix) finds a second raw host for many shown values, and every such second host comes from a short summary
     * prefix (length 16 or less), never from the /32 local routes or the /24 halves of a /23.
     */
    @Test
    void rootCauseAShortSummaryPrefixMakesAnotherRawHostConsistentWithTheShownValue() {
        routeTablesFirst();
        Map<String, Set<String>> shown = shownAttachmentHosts();
        PathPseudonymMap map = PathPseudonymMap.build(graph, PathPseudonymMap.over(ips, names));
        Set<Ipv4Prefix> prefixes = realPrefixes();
        int ambiguous = 0;
        Map<Integer, Integer> competitorPrefixLengths = new TreeMap<>();
        for (Map.Entry<String, Set<String>> e : shown.entrySet()) {
            if (e.getValue().size() > 1) {
                continue; // the genuine collision is asserted separately
            }
            List<Long> accepted = map.acceptedUnderAnyPrefix(e.getKey());
            long rendered = Ipv4Prefix.parseHost(e.getValue().iterator().next()).orElseThrow().host();
            assertTrue(accepted.contains(rendered), "the host it was rendered from is always a candidate");
            if (accepted.size() > 1) {
                ambiguous++;
                for (long other : accepted) {
                    if (other != rendered) {
                        competitorPrefixLengths.merge(longestExplaining(other, e.getKey(), prefixes), 1, Integer::sum);
                    }
                }
            }
        }
        System.out.println("[pseudonym-overlap] shown=" + shown.size() + " ambiguousUnderAnyPrefix=" + ambiguous
                + " competitorPrefixLength->count=" + competitorPrefixLengths);
        assertTrue(ambiguous > 0, "the any-prefix rule refuses shown values (live: 12 of 40)");
        assertTrue(competitorPrefixLengths.keySet().stream().allMatch(len -> len <= 16),
                "every competing raw host is consistent only through a short summary: " + competitorPrefixLengths);
    }

    @Test
    void aClusterVipOnBothMembersIsOneRawValueAndResolves() {
        routeTablesFirst();
        Map<String, Set<String>> shown = shownAttachmentHosts();
        PathPseudonymMap map = PathPseudonymMap.build(graph, PathPseudonymMap.over(ips, names));
        for (String vip : List.of("192.0.2.1", "192.0.2.17")) {
            String masked = shown.entrySet().stream().filter(e -> e.getValue().equals(Set.of(vip))).map(Map.Entry::getKey)
                    .findFirst().orElseThrow(() -> new AssertionError("the VIP is shown"));
            assertEquals(new PathPseudonymMap.Resolved(vip), map.resolveAddress(masked));
        }
    }

    @Test
    void theSameRangeInTwoVrfsIsOneRawValueAndResolvesTheEngineChoosesTheVrf() {
        routeTablesFirst();
        Map<String, Set<String>> shown = shownAttachmentHosts();
        PathPseudonymMap map = PathPseudonymMap.build(graph, PathPseudonymMap.over(ips, names));
        String masked = shown.entrySet().stream().filter(e -> e.getValue().equals(Set.of("203.0.113.1")))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        long attachments = graph.attachments().stream().filter(a -> a.address().filter("203.0.113.1/24"::equals)
                .isPresent()).count();
        assertEquals(2, attachments, "the address is on two units in two VRFs");
        // One raw value: the pseudonym layer resolves it; which VRF is meant is the engine's call (ingress hint or
        // AMBIGUOUS_INGRESS), not a pseudonym ambiguity.
        assertEquals(new PathPseudonymMap.Resolved("203.0.113.1"), map.resolveAddress(masked));
    }

    @Test
    void twoDifferentHostsTheTopologyShowedTheSameWayStayAmbiguous() {
        routeTablesFirst();
        Map<String, Set<String>> shown = shownAttachmentHosts();
        PathPseudonymMap map = PathPseudonymMap.build(graph, PathPseudonymMap.over(ips, names));
        List<String> colliding = shown.entrySet().stream().filter(e -> e.getValue().size() > 1).map(Map.Entry::getKey)
                .toList();
        assertEquals(1, colliding.size(), "one masked value shown for two different interfaces");
        assertEquals(Set.copyOf(collidingPair), shown.get(colliding.get(0)));
        assertEquals(new PathPseudonymMap.Refused(PathPseudonymMap.AMBIGUOUS_PSEUDONYM),
                map.resolveAddress(colliding.get(0)));
    }

    @Test
    void noRawValueInAnyOutputAndNoMaskerSideEffect() {
        routeTablesFirst();
        Map<String, Set<String>> shown = shownAttachmentHosts();
        PathPseudonymMap map = PathPseudonymMap.build(graph, PathPseudonymMap.over(ips, names));
        List<String> registeredBefore = ips.registeredSubnets();
        Set<String> codes = new TreeSet<>();
        StringBuilder printed = new StringBuilder(map.toString());
        for (String value : shown.keySet()) {
            PathPseudonymMap.Resolution r = map.resolveAddress(value);
            printed.append(r);
            if (r instanceof PathPseudonymMap.Refused refused) {
                codes.add(refused.code());
            }
        }
        // Values aiview never saw and raw values: uniform internal codes only.
        for (String value : List.of("250.1.2.3", "198.18.34.1", "203.0.113.1")) {
            PathPseudonymMap.Resolution r = map.resolveAddress(value);
            printed.append(r);
            codes.add(((PathPseudonymMap.Refused) r).code());
        }
        assertFalse(printed.toString().matches("(?s).*\\d+\\.\\d+\\.\\d+\\.\\d+.*"), printed.toString());
        assertTrue(Set.of(PathPseudonymMap.RAW_VALUE_REFUSED, PathPseudonymMap.UNKNOWN_PSEUDONYM,
                PathPseudonymMap.AMBIGUOUS_PSEUDONYM).containsAll(codes), codes.toString());
        assertEquals(registeredBefore, ips.registeredSubnets(), "resolving registered nothing (D-3 condition 4)");
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private Set<Ipv4Prefix> realPrefixes() {
        Set<Ipv4Prefix> all = new TreeSet<>();
        graph.segments().forEach(s -> s.network().ifPresent(all::add));
        graph.attachments().forEach(a -> a.address().flatMap(Ipv4Prefix::parseHost)
                .flatMap(Ipv4Prefix.HostAddress::prefix).ifPresent(all::add));
        graph.nodes().forEach(n -> n.routes().forEach(r -> Ipv4Prefix.parse(r.destination()).ifPresent(all::add)));
        ips.registeredSubnets().forEach(s -> Ipv4Prefix.parse(s).ifPresent(all::add));
        return all;
    }

    /** The longest real prefix under which raw {@code host} renders as {@code shown} (0 if only the bare form does). */
    private int longestExplaining(long host, String shown, Set<Ipv4Prefix> prefixes) {
        String text = PathPseudonymMap.text(host);
        int best = 0;
        for (Ipv4Prefix p : prefixes) {
            if (p.length() >= 4 && p.contains(host) && (shown + "/" + p.length())
                    .equals(ips.maskNetworkWithoutSideEffects(text + "/" + p.length()))) {
                best = Math.max(best, p.length());
            }
        }
        return best;
    }
}
