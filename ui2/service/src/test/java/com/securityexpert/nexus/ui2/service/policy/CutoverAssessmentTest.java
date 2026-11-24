package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyLookupTest.pan;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** ACI segmentation design section 4 acceptance criteria ACI-1..5, ACI-7 on synthetic policies (documentation ranges only). */
class CutoverAssessmentTest {
    static final Map<String, String> MAPPING = Map.of("source", "src", "destination", "dst", "port", "port", "protocol", "proto");
    static final String HEADER = "src,dst,port,proto\n";
    static final CutoverAssessment.Scope SCOPE = new CutoverAssessment.Scope(List.of("192.0.2.0/24"), List.of("198.51.100.0/24"));
    static final Instant LATER = Instant.now().plusSeconds(600);

    static InputStream csv(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    static CutoverAssessment.Target target(PolicySnapshot snapshot, String segmentZone, String peerZone) {
        return new CutoverAssessment.Target(CutoverAssessment.stored(snapshot, "", ""), segmentZone, peerZone, false, false);
    }
    static CutoverAssessment.Result assess(CutoverAssessment.Target target, CutoverAssessment.FlowPolicy old, Map<String, String> mapping, String text) throws Exception {
        var run = CutoverAssessment.start(SCOPE, target, old); run.read(csv(text), mapping); return run.finish(LATER);
    }
    static CutoverAssessment.Result assess(CutoverAssessment.Target target, String text) throws Exception { return assess(target, null, MAPPING, text); }
    static Map<String, Object> flow(CutoverAssessment.Result result, String source, String destination, int port) {
        return result.flows().stream().filter(f -> f.get("flowSource").equals(source) && f.get("flowDestination").equals(destination)
            && f.get("port").equals(port)).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked") static List<String> codes(CutoverAssessment.Result result) {
        return ((List<Map<String, Object>>) result.summary().get("coverage")).stream().map(c -> (String) c.get("code")).toList();
    }
    @SuppressWarnings("unchecked") static Map<String, Object> counts(CutoverAssessment.Result result) { return (Map<String, Object>) result.summary().get("counts"); }

    @Test void blockersDecisionsScopeAndMissingRules() throws Exception {
        var policy = pan(rule("r1", "host", "port", "allow", true), rule("r2", "any", "any", "deny", true));
        var result = assess(target(policy, "Z-SEG", "Z-PEER"), HEADER
            + "192.0.2.8,198.51.100.8,443,tcp\n192.0.2.8,198.51.100.8,443,6\n"   // allowed by r1, same tuple twice
            + "192.0.2.9,198.51.100.8,443,tcp\n"                                  // dropped by r2
            + "198.51.100.8,192.0.2.8,22,tcp\n"                                   // reverse direction, dropped by r2
            + "203.0.113.5,198.51.100.8,443,tcp\n"                                // outside the redirect scope
            + "2001:db8::1,2001:db8::2,443,tcp\nbad,198.51.100.8,443,tcp\n");
        assertEquals("BLOCKERS_FOUND", result.readiness()); assertEquals("COMPLETE", result.status());
        assertEquals("OPERATOR_OVERRIDE", result.summary().get("targetSource")); assertTrue(codes(result).contains("TARGET_FROM_OPERATOR_OVERRIDE"));
        assertEquals(3L, counts(result).get("tuples")); assertEquals(4L, counts(result).get("flows")); assertEquals(2L, counts(result).get("WOULD_DROP"));
        var allowed = flow(result, "192.0.2.8", "198.51.100.8", 443);
        assertEquals("WOULD_ALLOW", allowed.get("decision")); assertEquals(2L, allowed.get("flowCount"));
        assertEquals("r1", ((Map<?, ?>) allowed.get("decidingRule")).get("ruleId"));
        var reverse = flow(result, "198.51.100.8", "192.0.2.8", 22);
        assertEquals("PEER_TO_SEGMENT", reverse.get("direction")); assertEquals("WOULD_DROP", reverse.get("decision"));
        assertEquals("r2", ((Map<?, ?>) reverse.get("decidingRule")).get("ruleId"));
        assertEquals("WOULD_DROP", result.flows().get(0).get("decision"));
        assertEquals("2 of 3 evaluated flows would be dropped by the target policy.", result.summary().get("headline"));
        assertTrue(codes(result).containsAll(List.of("OUT_OF_SCOPE_FLOWS", "IPV6_FLOWS_SET_ASIDE", "INVALID_ROWS", "INSTALL_STATE_NOT_VERIFIED")));
        assertEquals(2, result.missingRules().size());
        var missing = result.missingRules().stream().filter(m -> m.get("port").equals(443)).findFirst().orElseThrow();
        assertEquals(List.of("192.0.2.9"), missing.get("flowSources")); assertEquals("198.51.100.8", missing.get("flowDestination"));
    }

    @Test void bestVerdictIsNeverWordedSafeAndAlwaysCarriesCoverage() throws Exception {   // ACI-1
        var allowAll = pan(rule("r1", "any", "any", "allow", true));
        var results = List.of(
            assess(target(allowAll, "Z-SEG", "Z-PEER"), HEADER + "192.0.2.8,198.51.100.8,443,tcp\n"),
            assess(target(pan(rule("r1", "any", "any", "deny", true)), "Z-SEG", "Z-PEER"), HEADER + "192.0.2.8,198.51.100.8,443,tcp\n"),
            assess(target(allowAll, "", ""), HEADER + "192.0.2.8,198.51.100.8,443,tcp\n"),
            assess(target(allowAll, "Z-SEG", "Z-PEER"), HEADER + "203.0.113.8,198.51.100.8,443,tcp\n"));
        assertEquals(List.of("NO_BLOCKERS_IN_EVALUATED_FLOWS", "BLOCKERS_FOUND", "NOT_DECIDED", "NO_EVIDENCE"),
            results.stream().map(CutoverAssessment.Result::readiness).toList());
        for (var result : results) {
            String words = (result.readiness() + " " + result.summary().get("headline")).toLowerCase(Locale.ROOT);
            for (String banned : List.of("safe", "ready", "pass", "healthy", "symmetric", "compliant")) assertFalse(words.contains(banned), words);
            assertTrue(codes(result).containsAll(List.of("LOG_PERIOD", "LOGGING_COMPLETENESS_UNKNOWN", "UNLOGGED_FLOWS_INVISIBLE")));
        }
        assertTrue(results.get(0).summary().get("headline").toString().startsWith("No blocking flow found among 1 evaluated flows."));
    }

    @Test void upperBoundExplainsButNeverDecides() throws Exception {   // ACI-2
        var conditional = edit(rule("r1", "host", "port", "allow", true), cell("host"), Map.of("source-user", List.of("USR-ALPHA-01")));
        var policy = pan(conditional, rule("r2", "any", "any", "allow", true));
        var result = assess(target(policy, "Z-SEG", "Z-PEER"), HEADER + "192.0.2.8,198.51.100.8,443,tcp\n192.0.2.9,198.51.100.8,443,tcp\n");
        var covered = flow(result, "192.0.2.8", "198.51.100.8", 443);
        assertEquals("NOT_DECIDED", covered.get("decision"));
        assertEquals(List.of("CONDITIONAL_RULE"), covered.get("flags"));
        assertEquals("r1", ((Map<?, ?>) covered.get("conditionalRule")).get("ruleId"));
        assertEquals(List.of("source-user"), covered.get("droppedConstraints"));
        var outside = flow(result, "192.0.2.9", "198.51.100.8", 443);
        assertEquals("NOT_DECIDED", outside.get("decision")); assertEquals(List.of(), outside.get("flags"));
        assertTrue(result.flows().stream().noneMatch(f -> f.get("decision").equals("WOULD_ALLOW")));
        assertEquals("NOT_DECIDED", result.readiness());
    }

    @Test void targetsWithoutAnEvaluablePolicyAreNotEvaluableForEveryFlow() throws Exception {   // ACI-3
        var asa = new CutoverAssessment.Target(CutoverAssessment.notEvaluable(CutoverAssessment.VENDOR_POLICY_NOT_COLLECTED), "", "", false, false);
        var result = assess(asa, HEADER + "192.0.2.8,198.51.100.8,443,tcp\n198.51.100.8,192.0.2.8,53,udp\n192.0.2.8,198.51.100.8,0,icmp\n");
        assertEquals(3, result.flows().size());
        for (var flow : result.flows()) {
            assertEquals("NOT_EVALUABLE", flow.get("decision")); assertEquals("VENDOR_POLICY_NOT_COLLECTED", flow.get("decisionReason"));
        }
        assertEquals("NOT_DECIDED", result.readiness()); assertTrue(codes(result).contains("TARGET_NOT_EVALUABLE"));
        assertFalse(codes(result).contains("INSTALL_STATE_NOT_VERIFIED")); assertTrue(result.missingRules().isEmpty());
        var other = pan(rule("r1", "any", "any", "allow", true));
        var foreign = new PolicySnapshot(new Metadata("p9", "s9", "MGR-BRAVO-01", "RAD", "c1", "DOM-TANGO-01", "POL-ALPHA-02",
            other.metadata().collectedAt(), "a1", List.of()), other.sections(), other.objects());
        assertFalse(CutoverAssessment.stored(foreign, "", "").evaluable());
        assertEquals("VENDOR_POLICY_NOT_EVALUABLE", CutoverAssessment.stored(foreign, "", "").decide("192.0.2.8", "198.51.100.8", "tcp", 443, "", "").reason());
    }

    @Test void paloAltoWithoutTheZonePairIsNeverDecided() throws Exception {   // ACI-4
        var allowAll = pan(rule("r1", "any", "any", "allow", true));
        for (var zones : List.of(List.of("", ""), List.of("Z-SEG", ""), List.of("", "Z-PEER"))) {
            var result = assess(target(allowAll, zones.get(0), zones.get(1)), HEADER + "192.0.2.8,198.51.100.8,443,tcp\n198.51.100.8,192.0.2.8,80,tcp\n");
            for (var flow : result.flows()) {
                assertEquals("NOT_DECIDED", flow.get("decision")); assertEquals("zone or target required", flow.get("decisionReason"));
            }
            assertTrue(codes(result).contains("ZONE_PAIR_MISSING"));
        }
        // Check Point has no zone pair requirement; its implicit cleanup stays UNKNOWN.
        var cp = sections("CP", cpSection("s1", null, edit(cpRule("r1", "Accept"), cell("host"), Map.of())));
        var result = assess(target(cp, "", ""), HEADER + "192.0.2.9,198.51.100.8,443,tcp\n");
        assertEquals("NOT_DECIDED", result.flows().get(0).get("decision"));
        assertEquals("implicit cleanup action not collected", result.flows().get(0).get("decisionReason"));
        assertFalse(codes(result).contains("ZONE_PAIR_MISSING"));
    }

    @Test void storedResultCarriesNoRawRowsHeadersOrMappings() throws Exception {   // ACI-5
        var mapping = new HashMap<>(MAPPING); mapping.put("time", "seen"); mapping.put("count", "n"); mapping.put("action", "act"); mapping.put("rule", "fwrule");
        var result = assess(target(pan(rule("r1", "any", "any", "allow", true)), "Z-SEG", "Z-PEER"), null, mapping,
            "src,dst,port,proto,seen,n,act,fwrule\n192.0.2.8,198.51.100.8,443,tcp,2026-11-01T10:00:00Z,5,accept,RULE-SECRET-TEXT\n");
        String json = new ObjectMapper().writeValueAsString(Map.of("summary", result.summary(), "flows", result.flows(), "missing", result.missingRules()));
        for (String forbidden : List.of("\"rows\":", "\"mapping\":", "\"mappings\":", "\"headers\":", "\"csv\":", "\"raw\":", "\"file\":",
                "192.0.2.8,198.51.100.8", "RULE-SECRET-TEXT", "fwrule", "seen"))
            assertFalse(json.contains(forbidden), forbidden);
        assertTrue(result.summary().keySet().containsAll(List.of("counts", "coverage", "uploads")));
        assertEquals("2026-11-01T10:00:00Z", result.flows().get(0).get("firstSeen")); assertEquals(5L, result.flows().get(0).get("flowCount"));
    }

    @Test void limitsRefuseInsteadOfTruncating() throws Exception {   // ACI-7
        var allowAll = target(pan(rule("r1", "any", "any", "allow", true)), "Z-SEG", "Z-PEER");
        var many = new StringBuilder(HEADER);
        for (int i = 0; i <= CutoverAssessment.MAX_TUPLES; i++) many.append("192.0.2.").append(i % 256).append(",198.51.100.").append(i / 256).append(",443,tcp\n");
        var run = CutoverAssessment.start(SCOPE, allowAll, null);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> run.read(csv(many.toString()), MAPPING)).getMessage().startsWith("TUPLE_LIMIT"));
        var rows = CutoverAssessment.start(SCOPE, allowAll, null);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> rows.read(csv(HEADER + "192.0.2.8,198.51.100.8,443,tcp\n192.0.2.8,198.51.100.8,443,tcp\n"), MAPPING, 10_000, 1))
            .getMessage().startsWith("CSV_ROW_LIMIT"));
        var files = CutoverAssessment.start(SCOPE, allowAll, null);
        for (int i = 0; i < CutoverAssessment.MAX_UPLOADS; i++) files.read(csv(HEADER + "192.0.2.8,198.51.100.8,443,tcp\n"), MAPPING);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> files.read(csv(HEADER + "192.0.2.8,198.51.100.8,443,tcp\n"), MAPPING))
            .getMessage().startsWith("CSV_FILE_LIMIT"));
        assertEquals(8L, counts(files.finish(LATER)).get("flows"));
        var timed = CutoverAssessment.start(SCOPE, allowAll, null);
        timed.read(csv(HEADER + "192.0.2.8,198.51.100.8,443,tcp\n"), MAPPING);
        var result = timed.finish(Instant.EPOCH);
        assertEquals("INCOMPLETE", result.status()); assertEquals("NOT_DECIDED", result.readiness());
        assertEquals("ASSESSMENT_TIME_LIMIT", result.flows().get(0).get("decisionReason")); assertTrue(codes(result).contains("ASSESSMENT_INCOMPLETE"));
        assertTrue(result.missingRules().isEmpty());
    }

    @Test void alreadyDroppedTodayAndOldPathReference() throws Exception {
        var mapping = new HashMap<>(MAPPING); mapping.put("action", "act");
        var deny = pan(rule("r1", "any", "any", "deny", true));
        var old = CutoverAssessment.stored(pan(rule("r1", "any", "any", "allow", true)), "", "");
        var result = assess(target(deny, "Z-SEG", "Z-PEER"), old, mapping, "src,dst,port,proto,act\n"
            + "192.0.2.8,198.51.100.8,443,tcp,drop\n192.0.2.8,198.51.100.8,443,tcp,Deny\n"
            + "192.0.2.9,198.51.100.9,443,tcp,accept\n192.0.2.10,198.51.100.9,443,tcp,client-rst\n");
        var dropped = flow(result, "192.0.2.8", "198.51.100.8", 443);
        assertTrue(((List<?>) dropped.get("flags")).containsAll(List.of("ALREADY_DROPPED_TODAY", "OLD_PATH_ALLOWED")));
        var open = flow(result, "192.0.2.9", "198.51.100.9", 443);
        assertEquals("WOULD_DROP", open.get("decision")); assertEquals("WOULD_ALLOW", open.get("oldPathDecision"));
        assertEquals("BLOCKERS_FOUND", result.readiness()); assertEquals(2L, counts(result).get("blockingTuples"));
        assertTrue(codes(result).contains("ACTION_VALUES_UNRECOGNISED"));
        assertEquals(1, result.missingRules().size());
        assertEquals(List.of("192.0.2.9", "192.0.2.10"), result.missingRules().get(0).get("flowSources"));
        assertEquals("r1", ((Map<?, ?>) result.missingRules().get(0).get("oldPathRule")).get("ruleId"));
        var onlyDropped = assess(target(deny, "Z-SEG", "Z-PEER"), old, mapping, "src,dst,port,proto,act\n192.0.2.8,198.51.100.8,443,tcp,drop\n");
        assertEquals("NO_BLOCKERS_IN_EVALUATED_FLOWS", onlyDropped.readiness());
    }

    @Test void segmentBlocksAreCanonicalAndDisjointFromPeers() {
        for (var bad : List.of(List.of("192.0.2.7/24"), List.of("192.0.2.0"), List.of("192.0.2.0/24", "192.0.2.0/24"), List.of("192.0.02.0/24"),
                List.of("256.0.2.0/24"), List.<String>of()))
            assertThrows(IllegalArgumentException.class, () -> new CutoverAssessment.Scope(bad, List.of("198.51.100.0/24")), bad.toString());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new CutoverAssessment.Scope(List.of("192.0.2.0/24"), List.of("192.0.2.128/25")))
            .getMessage().startsWith("SCOPE_OVERLAP"));
        var nested = new CutoverAssessment.Scope(List.of("192.0.2.0/24", "192.0.2.128/25", "203.0.113.0/32"), List.of("0.0.0.0/1"));
        assertEquals(3, nested.segment().size());
        var intervals = new CutoverAssessment.Intervals(nested.segment());
        assertTrue(intervals.contains(PolicyRuleQuery.interval("203.0.113.0")[0])); assertFalse(intervals.contains(PolicyRuleQuery.interval("203.0.113.1")[0]));
    }
    // ---- Cisco ASA and FortiGate stored policies, decided through the same lookup (delivery step 9) -----------------

    static CutoverAssessment.Target localTarget(PolicySnapshot snapshot, String device, String segmentZone, String peerZone) {
        return new CutoverAssessment.Target(CutoverAssessment.stored(snapshot, device, ""), segmentZone, peerZone, false, false);
    }
    static final String LOCAL_LOG = HEADER + "192.0.2.8,198.51.100.20,443,tcp\n192.0.2.8,198.51.100.20,22,tcp\n";

    @Test void aStoredAsaPolicyDecidesEveryFlowThroughTheLookup() throws Exception {
        var asa = AsaPolicyEvaluationTest.asa("""
                access-list ACL-OUT extended deny tcp any host 198.51.100.20 eq 22
                access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq https
                access-group ACL-OUT in interface outside
                """);
        assertTrue(CutoverAssessment.stored(asa, "dev-asa-1", "").evaluable());
        var result = assess(localTarget(asa, "dev-asa-1", "outside", "inside"), LOCAL_LOG);
        var allowed = flow(result, "192.0.2.8", "198.51.100.20", 443);
        assertEquals("WOULD_ALLOW", allowed.get("decision")); assertEquals("first matching rule", allowed.get("decisionReason"));
        assertEquals("WOULD_DROP", flow(result, "192.0.2.8", "198.51.100.20", 22).get("decision"));
        assertTrue(codes(result).contains("INSTALL_STATE_NOT_VERIFIED")); assertFalse(codes(result).contains("TARGET_NOT_EVALUABLE"));
        assertFalse(codes(result).contains("ZONE_PAIR_MISSING"));
        // An interface the policy does not know is never an allow.
        var unknown = assess(localTarget(asa, "dev-asa-1", "nowhere", "inside"), LOCAL_LOG);
        for (var f : unknown.flows()) assertNotEquals("WOULD_ALLOW", f.get("decision"));
    }

    @Test void aStoredFortiGatePolicyDecidesEveryFlowThroughTheLookup() throws Exception {
        var fgt = FortiGatePolicyEvaluationTest.fgt(
            FortiGatePolicyEvaluationTest.policy(10, FortiGatePolicyEvaluationTest.with("srcintf \"port1\"", "dstintf \"port2\"", "service \"HTTPS\"", "action accept"))
            + FortiGatePolicyEvaluationTest.policy(20, FortiGatePolicyEvaluationTest.with("srcintf \"port1\"", "dstintf \"port2\"", "service \"SSH\"", "action deny")));
        assertTrue(CutoverAssessment.stored(fgt, "dev-fgt-1", "").evaluable());
        var result = assess(localTarget(fgt, "dev-fgt-1", "port1", "port2"), LOCAL_LOG);
        assertEquals("WOULD_ALLOW", flow(result, "192.0.2.8", "198.51.100.20", 443).get("decision"));
        assertEquals("WOULD_DROP", flow(result, "192.0.2.8", "198.51.100.20", 22).get("decision"));
        assertTrue(codes(result).contains("INSTALL_STATE_NOT_VERIFIED")); assertFalse(codes(result).contains("TARGET_NOT_EVALUABLE"));
    }

    @Test void aMissingInterfaceOnAnAsaOrFortiGateTargetIsNeverAnAllow() throws Exception {
        var asa = AsaPolicyEvaluationTest.asa("access-list ACL-OUT extended permit ip any any\naccess-group ACL-OUT in interface outside\n");
        var fgt = FortiGatePolicyEvaluationTest.fgt(FortiGatePolicyEvaluationTest.policy(10,
            FortiGatePolicyEvaluationTest.with("srcintf \"port1\"", "dstintf \"port2\"", "service \"ALL\"", "action accept")));
        for (var target : List.of(List.of(asa, "dev-asa-1", "outside", "inside"), List.of(fgt, "dev-fgt-1", "port1", "port2"))) {
            var snapshot = (PolicySnapshot) target.get(0); var device = (String) target.get(1);
            // The lookup itself never decides without both interfaces (its own reason is kept) ...
            var direct = CutoverAssessment.stored(snapshot, device, "").decide("192.0.2.8", "198.51.100.20", "tcp", 443, "", "");
            assertEquals("NOT_DECIDED", direct.decision()); assertFalse(direct.reason().isEmpty());
            // ... and so does the assessment, for either interface empty, with the zone-pair coverage code.
            for (var zones : List.of(List.of("", ""), List.of((String) target.get(2), ""), List.of("", (String) target.get(3)))) {
                var result = assess(localTarget(snapshot, device, zones.get(0), zones.get(1)), LOCAL_LOG);
                for (var f : result.flows()) {
                    assertEquals("NOT_DECIDED", f.get("decision"), snapshot.metadata().vendor() + zones); assertEquals("zone or target required", f.get("decisionReason"));
                }
                assertTrue(codes(result).contains("ZONE_PAIR_MISSING")); assertEquals("NOT_DECIDED", result.readiness());
            }
        }
    }
}
