package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.service.privacy.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.cpSection;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/** The Insights read path: response shape, paging, filters, the detail view, a non-Check-Point package and the AIView projection. */
class PolicyInsightsQueryTest {
    final ObjectMapper mapper = new ObjectMapper();
    final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(new byte[32]);
    final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(new byte[32]);
    final PolicyQueryService query = new PolicyQueryService(mock(TransactionBoundary.class), mapper, names, null, ips);

    JsonNode json(Object body) { return mapper.valueToTree(body); }
    JsonNode masked(Object body) { return json(PolicyPrivacy.mask(mapper.convertValue(body, Map.class), "", names, ips)); }

    @Test void overviewCountsFilterPagingAndDetailAreCoherent() {
        var snapshot = cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "host", "any"), cp("r2", 2, "Accept", "host", "any"),
            PolicyInsightsTest.withHits(cp("r3", 3, "Accept", "net", "any"), 0L, null)));
        var all = json(query.insights(snapshot, 0, "", "", 90).body());
        assertTrue(all.path("supported").asBoolean());
        assertEquals("CP", all.path("vendor").asText());
        assertEquals(3, all.path("counts").path("rules").asInt());
        assertEquals(all.path("total").asInt(), all.path("insights").size());
        assertEquals(1, all.path("byType").path("I1").asInt()); assertEquals(1, all.path("byType").path("I10").asInt());
        // The I5 and I7 measurement blocks sit next to the I3 and I4 ones, with every counter and the not-evaluable list.
        for (String block : List.of("unionCover", "partialShadow", "merge", "safeReorder")) assertTrue(all.has(block), block);
        for (String key : List.of("evaluated", "candidates", "alreadyShadowed", "differsInSeveralCells", "differentEffect", "differentInstallOn", "anchorBlocked", "notProven", "notEvaluable"))
            assertTrue(all.path("merge").has(key), key);
        for (String key : List.of("evaluated", "safe", "safeInspectionChanges", "unsafeDifferentEffect", "anchorBlocked", "crossesSection", "notProven", "notEvaluable"))
            assertTrue(all.path("safeReorder").has(key), key);
        // r3 (the /24) overlaps r1 (one host) with the same action: one promote of r3 above r1, across r2, with no behaviour change.
        assertEquals(1, all.path("byType").path("I7").asInt());
        assertEquals(1, all.path("safeReorder").path("safe").asInt());
        var i1 = json(query.insights(snapshot, 0, "I1", "", 90).body());
        assertEquals(1, i1.path("total").asInt());
        var row = i1.path("insights").get(0);
        assertEquals("PROVEN", row.path("evidenceGrade").asText());
        assertEquals("INSTALL_STATE_NOT_VERIFIED", row.path("insightCaveats").get(0).asText());
        assertEquals(0, json(query.insights(snapshot, 0, "I1", "INDICATED", 90).body()).path("total").asInt());
        assertEquals(0, json(query.insights(snapshot, Integer.MAX_VALUE, "", "", 90).body()).path("insights").size());
        var detail = json(query.insightDetail(snapshot, row.path("insightId").asText(), 90).orElseThrow().body());
        assertEquals(2, detail.path("rules").size());
        assertEquals("r2", detail.path("rules").get(0).path("id").asText());
        // The cells of both rules resolve: every object they name is listed once, without members or values.
        var named = new java.util.TreeSet<String>();
        for (JsonNode o : detail.path("ruleObjects")) { named.add(o.path("id").asText()); assertEquals(0, o.path("values").size()); }
        assertEquals(java.util.Set.of("any", "host"), named);
        assertEquals(named.size(), detail.path("ruleObjects").size());
        assertTrue(query.insightDetail(snapshot, "not-an-insight", 90).isEmpty());
    }
    @Test void aPackageOfAnotherVendorIsUnsupportedNotEmpty() {
        var pan = PolicyHygieneTest.snapshot(List.of(PolicyHygieneTest.rule("r1", "any", "any", "allow", true)), PolicyHygieneTest.objects());
        var body = json(query.insights(pan, 0, "", "", 90).body());
        assertFalse(body.path("supported").asBoolean());
        assertFalse(body.has("insights")); assertFalse(body.has("counts"));
        assertTrue(query.insightDetail(pan, "x", 90).isEmpty());
        var proposal = json(query.proposal(pan, 90).body());
        assertFalse(proposal.path("supported").asBoolean());
        assertFalse(proposal.has("steps"));
    }
    @Test void proposalControllerValidatesDaysUsesHeavyGuardAndProjectsForAiview() {
        var snapshot = cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "host", "any"), cp("r2", 2, "Accept", "host", "any"),
            PolicyInsightsTest.withHits(cp("r3", 3, "Accept", "net", "any"), 0L, null)));
        var mocked = mock(PolicyQueryService.class);
        when(mocked.find("p1")).thenReturn(Optional.of(snapshot));
        when(mocked.proposal(snapshot, 90)).thenAnswer(inv -> query.proposal(snapshot, 90));
        var controller = new com.securityexpert.nexus.ui2.service.api.PolicyController(mocked);
        assertEquals(400, controller.proposal("p1", 0).getStatusCode().value());
        assertEquals(400, controller.proposal("p1", 36501).getStatusCode().value());
        assertEquals(404, controller.proposal("missing", 90).getStatusCode().value());
        var ok = controller.proposal("p1", 90);
        assertEquals(200, ok.getStatusCode().value());
        var body = json(((PolicyResponse) ok.getBody()).body());
        assertTrue(body.path("supported").asBoolean());
        assertEquals(1, body.path("accepted").asInt());
        assertEquals("EQUIVALENT", body.path("packageVerdict").asText());
        var projected = masked(((PolicyResponse) ok.getBody()).body());
        assertFalse(projected.toString().contains("OBJ-RULE-"));
        assertEquals(PolicyProposalView.CAVEAT, projected.path("caveat").asText());
        // Saturated bulkhead answers 503 POLICY_BUSY.
        var saturated = new PolicyBulkhead(1, 0);
        try (var ignored = saturated.tryEnter()) {
            assertEquals(503, new com.securityexpert.nexus.ui2.service.api.PolicyController(mocked, saturated).proposal("p1", 90).getStatusCode().value());
        }
    }
    @Test void theAiviewProjectionKeepsGradesAndCountsAndMasksRuleNames() {
        var snapshot = cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "host", "any"), cp("r2", 2, "Accept", "host", "any"),
            PolicyInsightsTest.withHits(cp("r3", 3, "Accept", "net", "any"), 0L, null)));
        var body = masked(query.insights(snapshot, 0, "", "", 90).body());
        assertEquals(3, body.path("counts").path("rules").asInt());
        assertTrue(body.path("merge").path("evaluated").isNumber(), "the I5 counters pass the projection as numbers");
        assertTrue(body.path("safeReorder").path("evaluated").isNumber(), "the I7 counters pass the projection as numbers");
        for (JsonNode row : body.path("insights")) {
            assertTrue(PolicyInsights.TYPES.contains(row.path("insightType").asText()));
            assertTrue(PolicyInsights.GRADES.contains(row.path("evidenceGrade").asText()));
            assertTrue(PolicyInsights.NEEDS.contains(row.path("raiseTo").path("insightNeeds").asText()));
            for (JsonNode e : row.path("evidence")) assertTrue(PolicyHygiene.EVIDENCE.contains(e.asText()) || PolicyInsights.EVIDENCE.contains(e.asText()), e.asText());
            for (JsonNode rule : row.path("rules")) assertNotEquals(names.maskPolicyName("x", "y"), "", "projection ran");
        }
        // The synthetic rule names must not pass through unchanged.
        assertFalse(body.toString().contains("OBJ-RULE-"), "rule names are pseudonymised for aiview");
        var detailId = json(query.insights(snapshot, 0, "I1", "", 90).body()).path("insights").get(0).path("insightId").asText();
        var detail = masked(query.insightDetail(snapshot, detailId, 90).orElseThrow().body());
        assertEquals(2, detail.path("rules").size());
        assertFalse(detail.toString().contains("OBJ-RULE-"));
        assertEquals(2, detail.path("ruleObjects").size());
        assertFalse(detail.toString().contains("OBJ-TEST-"), "object names in the compared cells are pseudonymised for aiview");
    }
    @Test void theAiviewProjectionKeepsNotReadableAndWaitingCountsUnmangled() {
        var objects = new HashMap<>(cpObjects());
        objects.put("role-dyn", new com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject(
            "role-dyn", "OBJ-ROLE-01", "access-role", List.of(), List.of(), "DYNAMIC"));
        var base = cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "role-dyn", "net"), cp("r2", 2, "Drop", "host", "net")));
        var snapshot = new com.securityexpert.nexus.ui2.policy.PolicySnapshot(base.metadata(), base.sections(), objects);
        var counts = masked(query.insights(snapshot, 0, "", "", 90).body()).path("counts");
        assertEquals(1, counts.path("notReadable").asInt());
        assertEquals(1, counts.path("waiting").asInt());
        assertEquals(PolicyHygiene.ACCESS_ROLE_CONSTRAINT, counts.path("notDecidable").get(0).path("shadowReason").asText());
        var waiting = counts.path("waitingRules").get(0);
        assertEquals("r1", waiting.path("blockingRuleId").asText());
        assertEquals(1, waiting.path("blockingRuleNumber").asInt());
        assertEquals(PolicyHygiene.ACCESS_ROLE_CONSTRAINT, waiting.path("shadowReason").asText());
        assertEquals("LOGS", waiting.path("reasonClass").asText());
        assertEquals(1, waiting.path("rules").asInt());
    }
}
