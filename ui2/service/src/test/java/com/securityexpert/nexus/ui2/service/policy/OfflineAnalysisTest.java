package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyLookupTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

class OfflineAnalysisTest {
    static final Map<String, String> MAPPING = Map.of("source", "src", "destination", "dst", "port", "port", "protocol", "proto");
    static final String HEADER = "src,dst,port,proto\r\n";
    static InputStream csv(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    static Map<String, Object> analyze(PolicySnapshot snapshot, String text) throws Exception { return OfflineAnalysis.analyze(snapshot, csv(text), MAPPING); }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> rules(Map<String, Object> result) { return (List<Map<String, Object>>) result.get("rules"); }
    @Test void allowDenyUnknownAndBadRowsRemainAccountedFor() throws Exception {
        var sample = HEADER + "192.0.2.8,198.51.100.8,443,tcp\n203.0.113.8,198.51.100.8,80,tcp\n"
            + "bad,198.51.100.8,443,tcp\n192.0.2.8,198.51.100.8,65536,tcp\n192.0.2.8,198.51.100.8,53,icmp\nshort,row\n";
        var result = analyze(pan(rule("r1", "host", "port", "allow", true), rule("r2", "any", "any", "deny", true)), sample);
        assertEquals(6L, result.get("rowCount")); assertEquals(3L, result.get("invalidRows")); assertEquals(3L, result.get("flowCount"));
        assertEquals(2L, result.get("attributedFlows")); assertEquals(1L, rules(result).get(0).get("flowCount"));
        assertTrue(result.get("unknownStats").toString().contains("protocol unsupported"));
        var uncertain = analyze(pan(rule("r1", "missing", "port", "allow", true), rule("r2", "any", "any", "deny", true)), HEADER + "192.0.2.8,198.51.100.8,443,tcp\n");
        assertEquals(0L, uncertain.get("attributedFlows")); assertTrue(uncertain.get("unknownStats").toString().contains("earlier rule could match"));
        assertTrue(rules(uncertain).stream().allMatch(r -> r.get("flowCount").equals(0L)));
    }
    @Test void weightedCountsTimesAndExactSiemComparisons() throws Exception {
        var mapping = new HashMap<>(MAPPING); mapping.put("rule", "rule"); mapping.put("count", "count"); mapping.put("time", "time");
        var snapshot = pan(rule("r1", "any", "any", "allow", true));
        String text = "src,dst,port,proto,rule,count,time\n"
            + "192.0.2.8,198.51.100.8,443,6,r1,7,2026-10-09T10:00:00Z\n"
            + "192.0.2.9,198.51.100.8,443,tcp,other,2,2026-10-09T11:00:00Z\n"
            + "192.0.2.8,198.51.100.8,443,tcp, r1,1,2026-10-09T12:00:00Z\n"
            + "192.0.2.8,198.51.100.8,443,tcp,r1,0,2026-10-09T12:00:00Z\n";
        var result = OfflineAnalysis.analyze(snapshot, csv(text), mapping);
        assertEquals(7L, result.get("agreementCount")); assertEquals(3L, result.get("disagreementCount")); assertEquals(1L, result.get("invalidRows"));
        assertEquals("2026-10-09T10:00:00Z", result.get("periodStart")); assertEquals(10L, rules(result).get(0).get("flowCount"));
        assertEquals(2, rules(result).get(0).get("distinctSources")); assertTrue(result.get("comparisonNote").toString().contains("NAT/routing"));
    }
    @Test void zeroTrafficWordingAndResolvedNestedUnseenMembers() throws Exception {
        var objects = objects(); objects.put("other", object("other", "address", "ipv4-address: 192.0.2.9"));
        objects.put("group", new PolicyObject("group", "GROUP-SYNTHETIC", "group", List.of("host", "other"), List.of(), "RESOLVED"));
        var original = pan(rule("r1", "group", "port", "allow", true), rule("r2", "any", "any", "deny", false));
        var result = analyze(new PolicySnapshot(original.metadata(), original.sections(), objects), HEADER + "192.0.2.8,198.51.100.8,443,tcp\n");
        assertTrue(rules(result).get(0).get("unseenMembers").toString().contains("memberId=other"));
        assertFalse(rules(result).get(0).get("unseenMembers").toString().contains("memberId=host"));
        assertEquals("No traffic in this sample (period UNKNOWN–UNKNOWN, 1 rows).", rules(result).get(1).get("zeroTraffic"));
        assertEquals("LOW", rules(result).get(1).get("severity"));
    }
    @Test void exactNarrowingCoversEveryAttributedFlowAndDoesNotPersistRawRows() throws Exception {
        var result = analyze(pan(rule("r1", "any", "any", "allow", true)), HEADER
            + "192.0.2.8,198.51.100.8,443,tcp\n192.0.2.9,198.51.100.9,444,tcp\n192.0.2.8,198.51.100.8,53,udp\n");
        var narrow = (Map<?, ?>) rules(result).get(0).get("narrowing");
        assertEquals(100, narrow.get("coveragePercent")); assertEquals(3L, narrow.get("coveredFlows"));
        assertEquals(List.of("192.0.2.8/31"), narrow.get("observedSource")); assertEquals(List.of("198.51.100.8/31"), narrow.get("observedDestination"));
        assertEquals(List.of("tcp/443-444", "udp/53"), narrow.get("observedService"));
        String json = new ObjectMapper().writeValueAsString(result);
        for (String forbidden : List.of("\"rows\":", "\"mapping\":", "\"headers\":", "\"csv\":", "\"raw\":", "192.0.2.8,198.51.100.8")) assertFalse(json.contains(forbidden));
        assertEquals(Set.of("policyId", "collectedAt", "rowCount", "invalidRows", "flowCount", "attributedFlows", "periodStart", "periodEnd",
            "parseStats", "unknownStats", "verdictStats", "agreementCount", "disagreementCount", "comparisonUnknownCount", "comparisonEnabled",
            "comparisonNote", "attributionNote", "limitations", "caution", "rules"), result.keySet());
    }
    @Test void limitsNeverPublishPartialResultsAndMappingsMustBeUnambiguous() throws Exception {
        var snapshot = pan(rule("r1", "any", "any", "allow", true)); String text = HEADER + "192.0.2.8,198.51.100.8,443,tcp\n";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> OfflineAnalysis.analyze(snapshot, csv(text), MAPPING, text.length() - 1, 10)).getMessage().startsWith("CSV_SIZE_LIMIT"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> OfflineAnalysis.analyze(snapshot, csv(text + text.substring(HEADER.length())), MAPPING, 10000, 1)).getMessage().startsWith("CSV_ROW_LIMIT"));
        assertEquals(1L, OfflineAnalysis.analyze(snapshot, csv(text), MAPPING, text.length(), 1).get("rowCount"));
        for (var mapping : List.of(Map.<String, String>of(), Map.of("source", "absent", "destination", "dst", "port", "port", "protocol", "proto"),
                Map.of("source", "src", "destination", "src", "port", "port", "protocol", "proto")))
            assertThrows(IllegalArgumentException.class, () -> OfflineAnalysis.analyze(snapshot, csv(text), mapping));
        assertThrows(IllegalArgumentException.class, () -> analyze(snapshot, "src,src,port,proto\n"));
        assertThrows(IllegalArgumentException.class, () -> analyze(snapshot, HEADER));
    }
    @Test void csvSupportsQuotesBomCrLfAndRejectsBinaryMalformedAndHugeRecords() throws Exception {
        var csv = new OfflineCsv(csv("\uFEFFsrc,dst,port,proto,note\r\n192.0.2.8,198.51.100.8,443,tcp,\"hello,\"\"world\"\"\nnext\"\r\n"), 10000);
        var headers = csv.next(); OfflineCsv.mapping(headers, MAPPING); assertEquals("src", headers.get(0));
        assertEquals("hello,\"world\"\nnext", csv.next().get(4)); assertNull(csv.next());
        for (String bad : List.of("\"unterminated", "a\"b,c", "\"a\"b,c", "a\0b", "a".repeat(65537)))
            assertThrows(IllegalArgumentException.class, () -> new OfflineCsv(csv(bad), 100000).next());
        assertThrows(IOException.class, () -> new OfflineCsv(new ByteArrayInputStream(new byte[]{(byte) 0xc3, (byte) 0x28}), 1000).next());
    }
    @Test void boundedSuggestionsAndTopValuesNeverClaimPartialCoverageAsComplete() throws Exception {
        StringBuilder text = new StringBuilder(HEADER);
        for (int i = 0; i < 51; i++) text.append("192.0.2.").append(i * 2).append(",198.51.100.8,443,tcp\n");
        var result = analyze(pan(rule("r1", "any", "any", "allow", true)), text.toString());
        assertEquals(50, ((List<?>) rules(result).get(0).get("topObserved")).size());
        var narrow = (Map<?, ?>) rules(result).get(0).get("narrowing"); assertEquals("UNKNOWN", narrow.get("memberStatus")); assertFalse(narrow.containsKey("coveragePercent"));
    }
    @Test void orderedLayersUseSharedEngineAndDoNotInventOneDecidingRule() throws Exception {
        var snapshot = sections("CP", cpSection("s1", null, cpLayerRule("r1", 1, "LAYER-ALPHA", "Accept")),
            cpSection("s2", null, cpLayerRule("r2", 1, "LAYER-BRAVO", "Accept")));
        var mapping = new HashMap<>(MAPPING); mapping.put("rule", "rule");
        var result = OfflineAnalysis.analyze(snapshot, csv("src,dst,port,proto,rule\n192.0.2.8,198.51.100.8,443,tcp,r1\n"), mapping);
        assertEquals(1L, result.get("attributedFlows")); assertTrue(rules(result).stream().allMatch(r -> r.get("flowCount").equals(1L)));
        assertEquals(1L, result.get("comparisonUnknownCount"));
        var q = query("192.0.2.8"); assertEquals(PolicyLookup.evaluate(snapshot, q, 250000), PolicyLookup.prepare(snapshot, 250000).evaluate(q));
    }
}
