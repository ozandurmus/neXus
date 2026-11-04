package com.securityexpert.nexus.ui2.service.pathanalysis.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Regression for the 2026-11-04 live symptom: an interface address taken from {@code GET /api/v2/topology} exactly as
 * the response advice rendered it for aiview (host of a {@code /25} or {@code /23} segment) was refused with
 * PSEUDONYM_REFUSED, while a {@code /30} host resolved. The advice renders {@code "address": "h/len"} under the
 * interface's own prefix; the reverse map accepted only the bare-host form, which follows the longest prefix registered
 * by other responses (a {@code /32} local route, a {@code /24} inside the {@code /23}) or a pseudonym cached before the
 * segment was known. Synthetic estate, documentation addresses only.
 */
class PathPseudonymAdviceConsistencyTest {

    private static final byte[] KEY = "synthetic-path-advice-mask-key-01".getBytes(StandardCharsets.UTF_8);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ESTATE = """
            {"devices": [
              {"device_id": "dev-t04", "vendor": "check_point", "hostname": "FW-TANGO-04",
               "run": {"run_id": "run-t04", "collected_at": "2026-11-04T06:00:00Z",
                 "contexts": [{"context": "physical",
                   "interfaces": [
                     {"name": "eth1", "addresses": [{"address": "198.51.100.10/23", "role": "member"}]},
                     {"name": "eth2", "addresses": [{"address": "203.0.113.129/25", "role": "member"}]},
                     {"name": "eth3", "addresses": [{"address": "192.0.2.1/30", "role": "member"}]}],
                   "routes": [
                     {"destination": "198.51.100.0/23", "interface": "eth1", "protocol": "kernel", "kind": "CONNECTED", "active": true},
                     {"destination": "203.0.113.128/25", "interface": "eth2", "protocol": "kernel", "kind": "CONNECTED", "active": true},
                     {"destination": "192.0.2.0/30", "interface": "eth3", "protocol": "kernel", "kind": "CONNECTED", "active": true}]}]}},
              {"device_id": "dev-b02", "vendor": "palo_alto", "hostname": "FW-BRAVO-02",
               "run": {"run_id": "run-b02", "collected_at": "2026-11-04T06:00:00Z",
                 "contexts": [{"context": "vsys1",
                   "interfaces": [
                     {"name": "ethernet1/1", "zone": "ZONE-LAN", "routing_context": "vr-main", "addresses": [{"address": "198.51.100.11/23", "role": "member"}]},
                     {"name": "ethernet1/2", "zone": "ZONE-DMZ", "routing_context": "vr-main", "addresses": [{"address": "203.0.113.130/25", "role": "member"}]},
                     {"name": "ethernet1/3", "zone": "ZONE-TRANSIT", "routing_context": "vr-main", "addresses": [{"address": "192.0.2.2/30", "role": "member"}]}],
                   "routes": [
                     {"destination": "198.51.100.0/23", "interface": "ethernet1/1", "protocol": "connected", "table": "vr-main", "kind": "CONNECTED", "active": true},
                     {"destination": "198.51.100.11/32", "interface": "ethernet1/1", "protocol": "local", "table": "vr-main", "kind": "CONNECTED", "active": true},
                     {"destination": "203.0.113.128/25", "interface": "ethernet1/2", "protocol": "connected", "table": "vr-main", "kind": "CONNECTED", "active": true},
                     {"destination": "203.0.113.130/32", "interface": "ethernet1/2", "protocol": "local", "table": "vr-main", "kind": "CONNECTED", "active": true},
                     {"destination": "192.0.2.0/30", "interface": "ethernet1/3", "protocol": "connected", "table": "vr-main", "kind": "CONNECTED", "active": true}]}]}}
            ]}""";

    private final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(KEY);
    private final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(KEY);
    private final PrivacyMaskingResponseBodyAdvice advice = new PrivacyMaskingResponseBodyAdvice(ips, names);
    private ServletServerHttpRequest aiview;
    private TopologyGraph graph;

    @BeforeEach
    void setUp() throws Exception {
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)).thenReturn(true);
        aiview = mock(ServletServerHttpRequest.class);
        when(aiview.getServletRequest()).thenReturn(http);
        JsonNode estate = JSON.readTree(ESTATE);
        graph = TopologyBuilder.build(new TopologyInput("tps-pseudo-1104", Instant.parse("2026-11-04T07:00:00Z"),
                TopologyFixtures.devices(estate), List.of(), "synthetic-pseudo-1104-salt".getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> render(Map<String, Object> body) {
        return (Map<String, Object>) advice.beforeBodyWrite(body, null, null, null, aiview, null);
    }

    /** Responses aiview read before the topology: they grow the masker's LPM state and its bare-host cache. */
    private void otherResponsesFirst() {
        // An inventory row read before any prefix was known: the bare management address is cached under the /24 fallback.
        render(new LinkedHashMap<>(Map.of("management_ip", "198.51.100.10", "next_hop", "203.0.113.129")));
        // A route table with local /32 host routes and a /24 inside the /23 registers longer prefixes.
        List<Object> routes = new ArrayList<>();
        for (String destination : List.of("198.51.100.11/32", "203.0.113.130/32", "198.51.100.0/24")) {
            routes.add(new LinkedHashMap<>(Map.of("destination", destination)));
        }
        render(new LinkedHashMap<>(Map.of("routes", routes)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> shownAttachmentHosts() {
        Map<String, Object> rendered = render(TopologyView.topology(Optional.of(graph), Optional.empty()));
        Map<String, String> rawByShown = new LinkedHashMap<>();
        List<Map<String, Object>> raw = (List<Map<String, Object>>) TopologyView.topology(Optional.of(graph),
                Optional.empty()).get("attachments");
        List<Map<String, Object>> masked = (List<Map<String, Object>>) rendered.get("attachments");
        for (int i = 0; i < masked.size(); i++) {
            Object address = masked.get(i).get("address");
            if (address instanceof String shown) {
                String rawAddress = (String) raw.get(i).get("address");
                rawByShown.put(shown.substring(0, shown.indexOf('/')), rawAddress.substring(0, rawAddress.indexOf('/')));
            }
        }
        return rawByShown;
    }

    @Test
    void everyInterfaceAddressAiviewSeesInTheTopologyResolves() {
        otherResponsesFirst();
        Map<String, String> shown = shownAttachmentHosts();
        assertEquals(6, shown.size(), "two /23, two /25 and two /30 interface addresses rendered");
        PathPseudonymMap map = PathPseudonymMap.build(graph, PathPseudonymMap.over(ips, names));
        List<String> registeredBefore = ips.registeredSubnets();
        for (Map.Entry<String, String> e : shown.entrySet()) {
            assertTrue(PathPseudonymMap.maskedSpace(ipv4(e.getKey())), "the rendered host is masked");
            assertEquals(new PathPseudonymMap.Resolved(e.getValue()), map.resolveAddress(e.getKey()), "a rendered host");
        }
        assertEquals(registeredBefore, ips.registeredSubnets(), "resolving registered nothing (D-3 condition 4)");
    }

    @Test
    void theLiveSymptomTheBareHostFormDiffersFromTheRenderedInterfaceAddress() {
        otherResponsesFirst();
        Map<String, String> shown = shownAttachmentHosts();
        // The reproduction: for the /23 and /25 hosts the bare-host pseudonym (what the old rule compared against)
        // is not what the topology response showed -- a cached /24 fallback or a /32 local route wins.
        for (String raw : List.of("198.51.100.10", "198.51.100.11", "203.0.113.129", "203.0.113.130")) {
            String rendered = shown.entrySet().stream().filter(e -> e.getValue().equals(raw)).findFirst().orElseThrow()
                    .getKey();
            assertNotEquals(rendered, ips.peek(raw), raw);
        }
        // The bare-host forms aiview sees elsewhere (inventory, route next hops) resolve as well.
        PathPseudonymMap map = PathPseudonymMap.build(graph, PathPseudonymMap.over(ips, names));
        for (String raw : List.of("198.51.100.10", "203.0.113.129", "198.51.100.11")) {
            assertEquals(new PathPseudonymMap.Resolved(raw), map.resolveAddress(ips.mask(raw)), raw);
        }
    }

    @Test
    void aHostOffsetInsideARenderedSegmentResolvesAndOutsideTheSnapshotDoesNot() {
        otherResponsesFirst();
        shownAttachmentHosts();
        PathPseudonymMap map = PathPseudonymMap.build(graph, PathPseudonymMap.over(ips, names));
        // aiview picks a host it never saw by its offset inside the masked /23 and /25 segment networks it was shown.
        String net23 = ips.maskNetworkWithoutSideEffects("198.51.100.0/23");
        long host23 = ipv4(net23.substring(0, net23.indexOf('/'))) + 77; // 198.51.100.77
        assertEquals(new PathPseudonymMap.Resolved("198.51.100.77"), map.resolveAddress(text(host23)));
        String net25 = ips.maskNetworkWithoutSideEffects("203.0.113.128/25");
        long host25 = ipv4(net25.substring(0, net25.indexOf('/'))) + 100; // 203.0.113.228
        assertEquals(new PathPseudonymMap.Resolved("203.0.113.228"), map.resolveAddress(text(host25)));
        // A masked prefix the masker knows from another response but the snapshot does not contain stays unknown.
        render(new LinkedHashMap<>(Map.of("address", "192.0.2.129/25")));
        String elsewhere = ips.mask("192.0.2.129/25");
        assertEquals(new PathPseudonymMap.Refused(PathPseudonymMap.UNKNOWN_PSEUDONYM),
                map.resolveAddress(elsewhere.substring(0, elsewhere.indexOf('/'))));
    }

    private static long ipv4(String text) {
        String[] o = text.split("\\.");
        return (Long.parseLong(o[0]) << 24) | (Long.parseLong(o[1]) << 16) | (Long.parseLong(o[2]) << 8) | Long.parseLong(o[3]);
    }

    private static String text(long v) {
        return PathPseudonymMap.text(v);
    }
}
