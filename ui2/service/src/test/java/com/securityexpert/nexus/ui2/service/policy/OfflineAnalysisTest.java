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
        var evaluated = pan(rule("r1", "any", "any", "allow", true));
        var result = analyze(evaluated, HEADER
            + "192.0.2.8,198.51.100.8,443,tcp\n192.0.2.9,198.51.100.9,444,tcp\n192.0.2.8,198.51.100.8,53,udp\n");
        var narrow = (Map<?, ?>) rules(result).get(0).get("narrowing");
        // The result names the snapshot it was evaluated against, as the stored collection time; the viewer shows its age.
        assertNotNull(evaluated.metadata().collectedAt());
        assertEquals(evaluated.metadata().collectedAt(), result.get("collectedAt"));
        assertEquals(100, narrow.get("coveragePercent")); assertEquals(3L, narrow.get("coveredFlows"));
        assertEquals(List.of("192.0.2.8/31"), narrow.get("observedSource")); assertEquals(List.of("198.51.100.8/31"), narrow.get("observedDestination"));
        assertEquals(List.of("tcp/443-444", "udp/53"), narrow.get("observedService"));
        String json = new ObjectMapper().writeValueAsString(result);
        for (String forbidden : List.of("\"rows\":", "\"mapping\":", "\"headers\":", "\"csv\":", "\"raw\":", "192.0.2.8,198.51.100.8")) assertFalse(json.contains(forbidden));
        assertEquals("WHOLE_POLICY", result.get("mode"));
        assertEquals(Set.of("mode", "policyId", "collectedAt", "rowCount", "invalidRows", "flowCount", "attributedFlows", "periodStart", "periodEnd",
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

    // ---- One-rule mode ----
    static final Map<String, String> COUNTED = Map.of("source", "src", "destination", "dst", "port", "port", "protocol", "proto", "count", "n");
    static final String COUNTED_HEADER = "src,dst,port,proto,n\n";
    static Map<String, Object> one(PolicySnapshot snapshot, String ruleId, String rows) throws Exception {
        return OfflineAnalysis.analyze(snapshot, csv(COUNTED_HEADER + rows), COUNTED, ruleId);
    }
    static Rule rule3(String id, String uuid, Cell source, Cell destination, Cell service) {
        return new Rule(id, uuid, id.equals("r1") ? 1 : 2, "OBJ-RULE-" + id, true, source, destination, service, cell("any"), "allow", "Log", "",
            Map.of("from", List.of("any"), "to", List.of("any")));
    }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> usage(Map<String, Object> result) { return (List<Map<String, Object>>) rules(result).get(0).get("objectUsage"); }
    static Map<String, Object> entry(List<Map<String, Object>> usage, String dimension, String object) {
        return usage.stream().filter(e -> e.get("dimension").equals(dimension) && e.get("objectId").equals(object)).findFirst().orElseThrow();
    }
    static PolicySnapshot withObjects(PolicySnapshot base, Map<String, PolicyObject> objects) { return new PolicySnapshot(base.metadata(), base.sections(), objects); }
    @Test void oneRuleRefusesUnknownAndAmbiguousIdentifiers() throws Exception {
        var snapshot = pan(rule("r1", "host", "port", "allow", true), rule3("other", "r1", cell("any"), cell("any"), cell("any")));
        for (String bad : List.of("nope", "OBJ-RULE-r1", "R1", " r1"))
            assertTrue(assertThrows(IllegalArgumentException.class, () -> one(snapshot, bad, "192.0.2.8,198.51.100.8,443,tcp,1\n")).getMessage().startsWith("RULE_NOT_FOUND"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> one(snapshot, "r1", "192.0.2.8,198.51.100.8,443,tcp,1\n")).getMessage().startsWith("RULE_AMBIGUOUS"));
        assertEquals("other", one(snapshot, "other", "192.0.2.8,198.51.100.8,443,tcp,1\n").get("ruleId"));
        assertEquals("r1", one(pan(rule("r1", "host", "port", "allow", true)), "uuid-r1", "192.0.2.8,198.51.100.8,443,tcp,1\n").get("ruleId"));
    }
    @Test void oneRuleAttributesByDecidingRuleAndCountsOtherRows() throws Exception {
        var result = one(pan(rule("r1", "host", "port", "allow", true), rule("r2", "any", "any", "deny", true)),
            "r1", "192.0.2.8,198.51.100.8,443,tcp,3\n203.0.113.8,198.51.100.8,443,tcp,5\n");
        assertEquals("ONE_RULE", result.get("mode")); assertEquals("r1", result.get("ruleId")); assertEquals(5L, result.get("otherRuleRows"));
        assertEquals(1, rules(result).size()); assertEquals(3L, rules(result).get(0).get("flowCount")); assertEquals(3L, result.get("attributedFlows"));
        assertEquals(8L, result.get("flowCount"));
        var usage = usage(result); assertEquals(3, usage.size());
        assertEquals(List.of("USED", 3L, ""), List.of(entry(usage, "source", "host").get("state"), entry(usage, "source", "host").get("flowCount"), entry(usage, "source", "host").get("reason")));
        assertEquals("USED", entry(usage, "service", "port").get("state"));
        var any = entry(usage, "destination", "any");
        assertEquals("NOT_AUDITABLE", any.get("state")); assertEquals("any", any.get("reason")); assertNull(any.get("parentId")); assertEquals(0, any.get("depth"));
        assertEquals(List.of("dimension", "objectId", "name", "parentId", "depth", "state", "flowCount", "reason"), List.copyOf(any.keySet()));
        var other = one(pan(rule("r1", "host", "port", "allow", true), rule("r2", "any", "any", "deny", true)), "r2", "192.0.2.8,198.51.100.8,443,tcp,3\n203.0.113.8,198.51.100.8,443,tcp,5\n");
        assertEquals(3L, other.get("otherRuleRows")); assertEquals(5L, rules(other).get(0).get("flowCount"));
    }
    @Test void oneRuleNestedGroupShowsUsedAndNotSeenMembers() throws Exception {
        var objects = objects();
        objects.put("other", object("other", "address", "ipv4-address: 192.0.2.9")); objects.put("host2", object("host2", "address", "ipv4-address: 192.0.2.10"));
        objects.put("sub", new PolicyObject("sub", "OBJ-SUB", "group", List.of("other", "host2"), List.of(), "RESOLVED"));
        objects.put("top", new PolicyObject("top", "OBJ-TOP", "group", List.of("host", "sub"), List.of(), "RESOLVED"));
        var base = pan(rule3("r1", "u1", cell("top"), cell("any"), cell("port")), rule("r2", "any", "any", "deny", true));
        var result = one(withObjects(base, objects), "r1", "192.0.2.8,198.51.100.8,443,tcp,4\n192.0.2.10,198.51.100.8,443,tcp,1\n");
        var usage = usage(result);
        assertEquals(List.of("top", "host", "sub", "other", "host2"), usage.stream().filter(e -> e.get("dimension").equals("source")).map(e -> e.get("objectId")).toList());
        assertEquals(5L, entry(usage, "source", "top").get("flowCount")); assertEquals("USED", entry(usage, "source", "top").get("state"));
        assertEquals(4L, entry(usage, "source", "host").get("flowCount")); assertEquals("top", entry(usage, "source", "host").get("parentId")); assertEquals(1, entry(usage, "source", "host").get("depth"));
        assertEquals("USED", entry(usage, "source", "sub").get("state")); assertEquals(1L, entry(usage, "source", "sub").get("flowCount"));
        assertEquals("NOT_SEEN", entry(usage, "source", "other").get("state")); assertEquals(0L, entry(usage, "source", "other").get("flowCount")); assertEquals(2, entry(usage, "source", "other").get("depth"));
        assertEquals("USED", entry(usage, "source", "host2").get("state"));
    }
    @Test void oneRuleOverlappingObjectsEachCountTheSameFlow() throws Exception {
        var base = pan(rule3("r1", "u1", new Cell(List.of("net", "host"), false), cell("any"), cell("any")), rule("r2", "any", "any", "deny", true));
        var usage = usage(one(base, "r1", "192.0.2.8,198.51.100.8,443,tcp,6\n"));
        assertEquals(6L, entry(usage, "source", "net").get("flowCount")); assertEquals(6L, entry(usage, "source", "host").get("flowCount"));
    }
    @Test void oneRuleNegatedCellIsNotAuditableAndRuleProblemNeverShowsNotSeen() throws Exception {
        var objects = objects(); objects.put("group", new PolicyObject("group", "OBJ-GROUP-01", "group", List.of("net", "host"), List.of(), "RESOLVED"));
        var base = withObjects(pan(rule3("r1", "u1", new Cell(List.of("group"), true), cell("net"), cell("port")), rule("r2", "any", "any", "deny", true)), objects);
        var usage = usage(one(base, "r1", "192.0.2.8,198.51.100.8,443,tcp,1\n"));
        for (String id : List.of("group", "net", "host")) {
            assertEquals("NOT_AUDITABLE", entry(usage, "source", id).get("state")); assertEquals("negated cell", entry(usage, "source", id).get("reason"));
        }
        // The rule cannot be evaluated, so it received no flows: its other objects are UNKNOWN, never NOT_SEEN.
        assertTrue(usage.stream().filter(e -> !e.get("dimension").equals("source")).allMatch(e -> e.get("state").equals("UNKNOWN") && !e.get("reason").equals("")));
    }
    @Test void oneRuleUnresolvedMemberIsUnknownAndNothingBelowItIsNotSeen() throws Exception {
        var objects = objects(); objects.put("grp", new PolicyObject("grp", "OBJ-GRP", "group", List.of("host", "missing", "net"), List.of(), "RESOLVED"));
        objects.put("cyc", new PolicyObject("cyc", "OBJ-CYC", "group", List.of("cyc"), List.of(), "RESOLVED"));
        for (String cellId : List.of("grp", "cyc")) {
            var base = withObjects(pan(rule3("r1", "u1", cell(cellId), cell("any"), cell("port")), rule("r2", "any", "any", "deny", true)), objects);
            var usage = usage(one(base, "r1", "198.51.100.77,198.51.100.8,443,tcp,1\n")).stream().filter(e -> e.get("dimension").equals("source")).toList();
            assertTrue(usage.stream().noneMatch(e -> e.get("state").equals("NOT_SEEN")));
            assertTrue(usage.stream().allMatch(e -> !e.get("state").equals("UNKNOWN") || !e.get("reason").equals("")));
            assertEquals("UNKNOWN", usage.get(0).get("state"));
        }
        var usage = usage(one(withObjects(pan(rule3("r1", "u1", cell("grp"), cell("any"), cell("port")), rule("r2", "any", "any", "deny", true)), objects), "r1", "198.51.100.77,198.51.100.8,443,tcp,1\n"));
        assertEquals("unresolved object", entry(usage, "source", "missing").get("reason"));
        var cycle = usage(one(withObjects(pan(rule3("r1", "u1", cell("cyc"), cell("any"), cell("port")), rule("r2", "any", "any", "deny", true)), objects), "r1", "198.51.100.77,198.51.100.8,443,tcp,1\n"));
        assertTrue(cycle.stream().anyMatch(e -> "group cycle or depth limit".equals(e.get("reason"))));
    }
    @Test void oneRuleIncompleteSnapshotCollectionIsUnknown() throws Exception {
        var base = pan(rule("r1", "host", "port", "allow", true), rule("r2", "any", "any", "deny", true));
        var failed = new PolicySnapshot(base.metadata(), base.sections(), base.objects(), List.of(new PolicySnapshot.CollectionFailure("layer", "reason", "name", 0, null)));
        var usage = usage(one(failed, "r1", "192.0.2.8,198.51.100.8,443,tcp,1\n"));
        assertFalse(usage.isEmpty());
        assertTrue(usage.stream().allMatch(e -> e.get("state").equals("UNKNOWN") && e.get("reason").equals("snapshot collection incomplete")));
    }
    @Test void oneRuleObjectUsageIsCappedAtTwoThousandEntries() throws Exception {
        var objects = objects(); var members = new ArrayList<String>();
        for (int i = 0; i < 2100; i++) { objects.put("m" + i, object("m" + i, "address", "ipv4-address: 192.0.2.8")); members.add("m" + i); }
        objects.put("big", new PolicyObject("big", "OBJ-BIG", "group", members, List.of(), "RESOLVED"));
        var base = withObjects(pan(rule3("r1", "u1", cell("big"), cell("any"), cell("port")), rule("r2", "any", "any", "deny", true)), objects);
        var usage = usage(one(base, "r1", "192.0.2.8,198.51.100.8,443,tcp,1\n"));
        assertEquals(2001, usage.size()); assertEquals("object limit", usage.get(2000).get("reason")); assertEquals("UNKNOWN", usage.get(2000).get("state"));
    }
    @Test void wholePolicyOutputIsUnchangedExceptModeAndStoredAggregatesKeepForbiddenKeysOut() throws Exception {
        var snapshot = pan(rule("r1", "host", "port", "allow", true), rule("r2", "any", "any", "deny", true));
        String rows = HEADER + "192.0.2.8,198.51.100.8,443,tcp\n203.0.113.8,198.51.100.8,80,tcp\nbad,1,2,3\n";
        var whole = analyze(snapshot, rows);
        assertEquals(whole, OfflineAnalysis.analyze(snapshot, csv(rows), MAPPING, (String) null));
        assertEquals("WHOLE_POLICY", whole.get("mode")); assertFalse(whole.containsKey("ruleId")); assertFalse(whole.containsKey("otherRuleRows"));
        assertEquals(2, rules(whole).size()); assertFalse(rules(whole).get(0).containsKey("objectUsage"));
        var oneRule = one(snapshot, "r1", "192.0.2.8,198.51.100.8,443,tcp,1\n");
        var json = new ObjectMapper();
        for (var result : List.of(whole, oneRule)) {
            var tree = json.readTree(json.writeValueAsString(result));
            for (String forbidden : List.of("rows", "raw", "file", "csv", "mapping", "headers")) assertFalse(tree.has(forbidden), forbidden);
            assertTrue(tree.has("rowCount") && tree.has("rules") && tree.has("parseStats") && tree.has("unknownStats"));
        }
        assertEquals(Set.of("mode", "ruleId", "otherRuleRows", "policyId", "collectedAt", "rowCount", "invalidRows", "flowCount", "attributedFlows", "periodStart", "periodEnd",
            "parseStats", "unknownStats", "verdictStats", "agreementCount", "disagreementCount", "comparisonUnknownCount", "comparisonEnabled",
            "comparisonNote", "attributionNote", "limitations", "caution", "rules"), oneRule.keySet());
    }
}
