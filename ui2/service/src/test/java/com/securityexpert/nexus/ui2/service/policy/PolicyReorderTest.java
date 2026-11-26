package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.privacy.*;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.NOW;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.cpSection;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.edit;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.object;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Insight I7 (safe reorder, promote), Check Point fixtures: the plain move, crossed rules that are disjoint or of the same
 * verdict effect, a different verdict effect (UNSAFE, counted), anchors and Apply Layer rules (Q3), profile and log
 * differences (Q2, "verdict-safe, inspection changes"), UNKNOWN signature parts, a section boundary (Q9), install-on, an
 * incomplete snapshot, another vendor, the cap, the budget (Q8), the route and the AIView projection. Documentation
 * addresses (192.0.2.0/24, 198.51.100.0/24) and masked names only.
 */
class PolicyReorderTest {
    /** PolicyUnionCoverTest's halves of 192.0.2.0/24, plus an address outside it and a second atomic gateway. */
    static PolicySnapshot snapshot(Section... sections) {
        var base = PolicyUnionCoverTest.snapshot(sections);
        var objects = new HashMap<>(base.objects());
        objects.put("other", object("other", "address", "ip-netmask: 198.51.100.0/24"));
        objects.put("fw-juliet-06", object("fw-juliet-06", "gateway"));
        return new PolicySnapshot(base.metadata(), base.sections(), objects, base.failures());
    }
    static PolicyInsights.Report report(PolicySnapshot snapshot) { return PolicyInsightsTest.report(snapshot); }
    static List<PolicyInsights.Insight> i7(PolicySnapshot snapshot) { return PolicyInsightsTest.of(report(snapshot), "I7"); }
    static List<String> ids(PolicyInsights.Insight insight) { return insight.rules().stream().map(PolicyInsights.RuleRef::ruleId).toList(); }
    static List<String> roles(PolicyInsights.Insight insight) { return insight.rules().stream().map(PolicyInsights.RuleRef::insightRole).toList(); }
    static PolicyReorder.Result reorder(PolicySnapshot snapshot) {
        var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250000);
        return PolicyReorder.analyze(snapshot, PolicyPartialShadow.analyze(snapshot, hygiene, PolicyUnionCover.analyze(snapshot, hygiene)));
    }
    static Rule withLog(Rule r, String log) {
        return new Rule(r.id(), r.uuid(), r.number(), r.name(), r.enabled(), r.source(), r.destination(), r.service(), r.application(), r.action(), log, r.comment(), r.extras());
    }
    static Rule profiled(Rule r, String profile) { return edit(r, r.source(), Map.of("profile-setting/group", List.of(profile))); }
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> proof(PolicyInsights.Insight insight) { return (List<Map<String, Object>>) insight.counter().get("crossedProof"); }
    static void assertNoBehaviourWordingOnlyWhenSafe(PolicyInsights.Insight insight) {
        boolean safe = PolicyReorder.SAFE.equals(insight.counter().get("reorderResult"));
        for (String e : insight.evidence()) {
            assertEquals(safe, e.contains("no behaviour change"), e);
            assertEquals(!safe, e.contains("verdict-safe, inspection changes"), e);
        }
        if (safe) assertEquals(PolicyReorder.EQUIVALENT, insight.counter().get("equivalence"), "SAFE implies EQUIVALENT");
    }

    @Test void thePlainPromoteIsProvenSafeWithItsProof() {
        // r2 (the whole /24) overlaps r1 (the lower half), same action: I4. Moving r2 above r1 changes no verdict.
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "net", "any")));
        var found = i7(snapshot);
        assertEquals(1, found.size());
        var insight = found.get(0);
        assertEquals("PROVEN", insight.grade());
        assertEquals("package", insight.scope());
        assertEquals(List.of("r2", "r1"), ids(insight));
        assertEquals(List.of("subject", "promoteAbove"), roles(insight));
        assertEquals(List.of(PolicyInsights.I7_SAFE), insight.evidence());
        assertEquals(List.of(PolicyInsights.INSTALL_STATE_NOT_VERIFIED), insight.caveats());
        assertEquals("PROVEN", insight.raiseGrade()); assertEquals(PolicyInsights.NONE, insight.needs());
        assertEquals(PolicyReorder.SAFE, insight.counter().get("reorderResult"));
        assertEquals(PolicyReorder.EQUIVALENT, insight.counter().get("equivalence"));
        assertEquals(0, insight.counter().get("crossedRules")); assertEquals(0, insight.counter().get("moreCrossed"));
        assertEquals(1, insight.counter().get("decidingRules"), "r1 decides the lower half today and hands it to r2");
        assertEquals(0, insight.counter().get("changedRules")); assertEquals(0, insight.counter().get("changedBoxes"));
        assertEquals(List.of(Map.of("ruleId", "r1", "crossedReason", PolicyReorder.SAME_VERDICT, "inspectionChanges", false)), proof(insight));
        assertNoBehaviourWordingOnlyWhenSafe(insight);
        var report = report(snapshot);
        assertEquals(1, report.byType().get("I7"));
        var block = report.safeReorder();
        assertEquals(1, block.evaluated()); assertEquals(1, block.safe());
        assertEquals(0, block.safeInspectionChanges() + block.unsafeDifferentEffect() + block.anchorBlocked() + block.crossesSection() + block.notProven());
        // I7 sits right after I4 in the display order, and the package counts stay the shadow engine's.
        assertTrue(PolicyInsights.TYPES.indexOf("I7") == PolicyInsights.TYPES.indexOf("I4") + 1);
        assertEquals(PolicyInsightsTest.report(snapshot).counts(), report.counts());
    }
    @Test void aCrossedDisjointRuleIsHarmlessAndListed() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("x", 2, "Drop", "other", "any"), cp("r2", 3, "Accept", "net", "any")));
        var insight = i7(snapshot).get(0);
        assertEquals(List.of("r2", "r1", "x"), ids(insight));
        assertEquals(List.of("subject", "promoteAbove", "crossed"), roles(insight));
        assertEquals(PolicyReorder.SAFE, insight.counter().get("reorderResult"));
        assertEquals(1, insight.counter().get("crossedRules"));
        assertEquals(PolicyReorder.DISJOINT, proof(insight).get(1).get("crossedReason"));
        assertEquals("x", proof(insight).get(1).get("ruleId"));
    }
    @Test void aCrossedOverlappingRuleOfTheSameEffectIsHarmless() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("x", 2, "Accept", "hi-a", "any"), cp("r2", 3, "Accept", "net", "any")));
        var insight = i7(snapshot).get(0);
        assertEquals(List.of("r2", "r1", "x"), ids(insight));
        assertEquals(PolicyReorder.SAME_VERDICT, proof(insight).get(1).get("crossedReason"));
        assertEquals(PolicyReorder.SAFE, insight.counter().get("reorderResult"));
        assertEquals(2, insight.counter().get("decidingRules"));
        assertNoBehaviourWordingOnlyWhenSafe(insight);
    }
    @Test void aCrossedOverlappingRuleOfAnotherActionMakesTheMoveUnsafe() {
        // B and r are installed on one gateway; the Drop rule between them on "Policy Targets", which may include it. The
        // I4 pass compares equal install-on only, so r is I4; the move would hand r2's packets from the Drop rule to r2.
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "fw-tango-04"), cp("x", 2, "Drop", "hi-a", "targets"),
            cp("r2", 3, "Accept", "net", "fw-tango-04")));
        assertEquals(1, PolicyInsightsTest.of(report(snapshot), "I4").size());
        assertTrue(i7(snapshot).isEmpty());
        var result = reorder(snapshot);
        assertEquals(1, result.unsafeDifferentEffect()); assertEquals(0, result.safe());
        assertEquals(PolicyReorder.UNSAFE_DIFFERENT_EFFECT, result.moves().get("r2").result());
        // The same Drop rule that does not meet r2's match is harmless.
        var apart = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "fw-tango-04"), cp("x", 2, "Drop", "other", "targets"),
            cp("r2", 3, "Accept", "net", "fw-tango-04")));
        assertEquals(PolicyReorder.SAFE, reorder(apart).moves().get("r2").result());
    }
    @Test void aCrossedAnchorBlocksTheMove() {
        for (var anchor : List.of(PolicyUnionCoverTest.timed(cp("x", 2, "Accept", "other", "any")), cp("x", 2, "Accept", "missing", "any"),
                edit(cp("x", 2, "Accept", "other", "any"), PolicyHygieneTest.cell("other"), Map.of("auto-generated", List.of("true"))))) {
            var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), anchor, cp("r2", 3, "Accept", "net", "any")));
            assertEquals(1, PolicyInsightsTest.of(report(snapshot), "I4").size(), "the anchor takes no part in I4");
            assertTrue(i7(snapshot).isEmpty(), "nothing moves across an anchor, even one that looks disjoint");
            var result = reorder(snapshot);
            assertEquals(1, result.anchorBlocked());
            assertEquals(PolicyReorder.ANCHOR_BLOCKED, result.moves().get("r2").result());
        }
        // An anchor before B or after r is not crossed.
        var outside = snapshot(cpSection("s1", null, PolicyUnionCoverTest.timed(cp("a", 1, "Drop", "other", "any")), cp("r1", 2, "Accept", "lo", "any"),
            cp("r2", 3, "Accept", "net", "any"), PolicyUnionCoverTest.timed(cp("z", 4, "Drop", "any", "any"))));
        assertEquals(1, i7(outside).size());
    }
    @Test void aCrossedApplyLayerRuleIsAnAnchor() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), applyLayer("x", 2, "other", "inline-a"), cp("r2", 3, "Accept", "net", "any")));
        assertEquals(1, PolicyInsightsTest.of(report(snapshot), "I4").size());
        assertTrue(i7(snapshot).isEmpty());
        assertEquals(1, reorder(snapshot).anchorBlocked());
    }
    @Test void aProfileDifferenceIsVerdictSafeWithInspectionChangesNeverNoBehaviourChange() {
        var snapshot = snapshot(cpSection("s1", null, profiled(cp("r1", 1, "Accept", "lo", "any"), "PROF-ALPHA"), cp("r2", 2, "Accept", "net", "any")));
        var insight = i7(snapshot).get(0);
        assertEquals("PROVEN", insight.grade(), "PROVEN for the verdict");
        assertEquals(PolicyReorder.SAFE_INSPECTION_CHANGES, insight.counter().get("reorderResult"));
        assertEquals(List.of(PolicyInsights.I7_INSPECTION), insight.evidence());
        assertEquals(PolicyReorder.CHANGES, insight.counter().get("equivalence"));
        assertEquals(1, insight.counter().get("changedRules"));
        assertTrue((Integer) insight.counter().get("changedBoxes") >= 1);
        assertEquals(true, proof(insight).get(0).get("inspectionChanges"));
        assertNoBehaviourWordingOnlyWhenSafe(insight);
        assertFalse(PolicyInsights.I7_INSPECTION.contains("no behaviour change"));
        assertEquals(1, report(snapshot).safeReorder().safeInspectionChanges());
        // The same profile on both sides is equal again.
        var same = snapshot(cpSection("s1", null, profiled(cp("r1", 1, "Accept", "lo", "any"), "PROF-ALPHA"), profiled(cp("r2", 2, "Accept", "net", "any"), "PROF-ALPHA")));
        assertEquals(PolicyReorder.SAFE, i7(same).get(0).counter().get("reorderResult"));
    }
    @Test void aLogSettingDifferenceIsAnInspectionChange() {
        var snapshot = snapshot(cpSection("s1", null, withLog(cp("r1", 1, "Accept", "lo", "any"), "None"), cp("r2", 2, "Accept", "net", "any")));
        assertEquals(PolicyReorder.SAFE_INSPECTION_CHANGES, i7(snapshot).get(0).counter().get("reorderResult"));
        var tracked = snapshot(cpSection("s1", null, edit(cp("r1", 1, "Accept", "lo", "any"), PolicyHygieneTest.cell("lo"), Map.of("track-accounting", List.of("true"))),
            cp("r2", 2, "Accept", "net", "any")));
        assertEquals(PolicyReorder.SAFE_INSPECTION_CHANGES, i7(tracked).get(0).counter().get("reorderResult"), "track fields are part of the log setting");
        // A crossed rule of another log setting that does not meet r2 changes nothing.
        var apart = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), withLog(cp("x", 2, "Accept", "other", "any"), "None"), cp("r2", 3, "Accept", "net", "any")));
        assertEquals(PolicyReorder.SAFE, i7(apart).get(0).counter().get("reorderResult"));
    }
    @Test void anUnknownSignaturePartIsNeverEqualToAnything() {
        // The track type was not resolved on either side: UNKNOWN equals nothing, another UNKNOWN included.
        var snapshot = snapshot(cpSection("s1", null, withLog(cp("r1", 1, "Accept", "lo", "any"), "UNKNOWN"), withLog(cp("r2", 2, "Accept", "net", "any"), "UNKNOWN")));
        var insight = i7(snapshot).get(0);
        assertEquals(PolicyReorder.SAFE_INSPECTION_CHANGES, insight.counter().get("reorderResult"));
        assertNoBehaviourWordingOnlyWhenSafe(insight);
        assertFalse(PolicyEffectSignature.Part.UNKNOWN.same(PolicyEffectSignature.Part.UNKNOWN));
        var r = cp("r1", 1, "Accept", "lo", "any");
        assertFalse(PolicyEffectSignature.sameFull("CP", withLog(r, null), withLog(r, null)));
        assertTrue(PolicyEffectSignature.sameFull("CP", r, r));
        // A vendor whose profile and log settings are not mapped in this step: every part but the verdict is UNKNOWN.
        var pan = PolicyEffectSignature.of("PAN", r);
        assertFalse(pan.profiles().known()); assertFalse(pan.log().known());
        assertFalse(PolicyEffectSignature.sameFull("PAN", r, r));
        // Check Point stores no profile reference on an access rule: the part is not collected (UNKNOWN), never known and empty.
        var cpSignature = PolicyEffectSignature.of("CP", r);
        assertFalse(cpSignature.profiles().known());
        assertEquals(List.of("type=Log"), cpSignature.log().values());
    }
    @Test void aMoveThatWouldLeaveItsSectionIsNotOffered() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any")), cpSection("s2", null, cp("r2", 2, "Accept", "net", "any")));
        assertEquals(1, PolicyInsightsTest.of(report(snapshot), "I4").size(), "the layer continues across the section");
        assertTrue(i7(snapshot).isEmpty());
        assertEquals(1, reorder(snapshot).crossesSection());
        // Never across a layer: another layer's rule is no B at all.
        var layers = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any")), cpSection("s2", null, inLayer(cp("b1", 1, "Accept", "net", "any"), "LAYER-BRAVO")));
        assertTrue(i7(layers).isEmpty());
        assertEquals(0, reorder(layers).evaluated());
    }
    @Test void aCrossedRuleOnAProvenDisjointGatewayTakesNoPart() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "fw-tango-04"), cp("x", 2, "Drop", "hi-a", "fw-juliet-06"),
            cp("r2", 3, "Accept", "net", "fw-tango-04")));
        var insight = i7(snapshot).get(0);
        assertEquals(PolicyReorder.SAFE, insight.counter().get("reorderResult"));
        assertEquals(PolicyReorder.DISJOINT, proof(insight).get(1).get("crossedReason"));
        // B on another install-on than r is no I4 overlap, hence no move.
        var apart = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "fw-juliet-06"), cp("r2", 2, "Accept", "net", "fw-tango-04")));
        assertTrue(i7(apart).isEmpty());
        assertEquals(0, reorder(apart).evaluated());
    }
    @Test void anIncompleteSnapshotAndAnotherVendorEvaluateNothing() {
        var complete = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "net", "any")));
        var incomplete = new PolicySnapshot(complete.metadata(), complete.sections(), complete.objects(), List.of(new CollectionFailure("layer", "COLLECTION_PENDING")));
        assertTrue(i7(incomplete).isEmpty());
        var result = reorder(incomplete);
        assertEquals(0, result.evaluated());
        assertEquals(Map.of(PolicyUnionCover.INCOMPLETE, 2), result.notEvaluable());
        assertEquals(List.of(Map.of("reorderReason", PolicyUnionCover.INCOMPLETE, "rules", 2)), report(incomplete).safeReorder().notEvaluable());
        var pan = PolicyHygieneTest.snapshot(List.of(PolicyHygieneTest.rule("r1", "net", "any", "allow", true), PolicyHygieneTest.rule("r2", "any", "any", "allow", true)), PolicyHygieneTest.objects());
        var panResult = reorder(pan);
        assertEquals(0, panResult.evaluated()); assertTrue(panResult.moves().isEmpty());
        assertEquals(Set.of(PolicyUnionCover.UNSUPPORTED_VENDOR), panResult.notEvaluable().keySet());
        assertTrue(PolicyInsightsTest.of(PolicyInsightsTest.report(pan), "I7").isEmpty());
    }
    @Test void theListOfCrossedRulesIsCappedAndTheRestIsCounted() {
        List<Rule> rules = new ArrayList<>();
        rules.add(cp("r1", 1, "Accept", "lo", "any"));
        int crossed = PolicyInsights.MAX_OVERLAPPING + 3;
        for (int i = 0; i < crossed; i++) rules.add(cp("x" + i, i + 2, "Drop", "other", "any"));
        rules.add(cp("rs", crossed + 2, "Accept", "net", "any"));
        var insight = i7(snapshot(cpSection("s1", null, rules.toArray(Rule[]::new)))).get(0);
        assertEquals(2 + PolicyInsights.MAX_OVERLAPPING, insight.rules().size());
        assertEquals(crossed, insight.counter().get("crossedRules"));
        assertEquals(3, insight.counter().get("moreCrossed"));
        assertEquals(1 + PolicyInsights.MAX_OVERLAPPING, proof(insight).size(), "B and every listed crossed rule carry their proof");
    }
    @Test void anExhaustedBudgetIsNotEvaluableNeverAWrongSafeMove() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("x", 2, "Accept", "hi-a", "any"), cp("r2", 3, "Accept", "net", "any")));
        var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250000);
        var union = PolicyUnionCover.analyze(snapshot, hygiene);
        var partial = PolicyPartialShadow.analyze(snapshot, hygiene, union);
        boolean sawNotProven = false, sawProven = false;
        for (int work = 0; work < 800; work++) {
            var reorder = PolicyReorder.analyze(snapshot, partial, work, work);
            var report = PolicyInsights.analyze(snapshot, hygiene, null, Map.of(), 90, union, partial, reorder);
            for (var insight : PolicyInsightsTest.of(report, "I7")) {
                if (insight.grade().equals("PROVEN")) {
                    sawProven = true;
                    assertEquals(List.of("r2", "r1", "x"), ids(insight), "work " + work);
                    assertEquals(PolicyReorder.SAFE, insight.counter().get("reorderResult"));
                } else {
                    sawNotProven = true;
                    assertEquals("NOT_EVALUABLE", insight.grade());
                    assertEquals(List.of(PolicyInsights.I7_BUDGET), insight.evidence());
                    assertEquals(PolicyReorder.BUDGET, insight.counter().get("reorderReason"));
                    assertEquals(PolicyReorder.NOT_PROVEN, insight.counter().get("equivalence"));
                    assertTrue(insight.caveats().isEmpty());
                    assertEquals(List.of("subject", "promoteAbove"), roles(insight));
                }
            }
            assertEquals(reorder.notProven(), report.safeReorder().notProven());
            assertEquals(reorder.evaluated(), reorder.safe() + reorder.safeInspectionChanges() + reorder.unsafeDifferentEffect()
                + reorder.anchorBlocked() + reorder.crossesSection() + reorder.notProven());
        }
        assertTrue(sawNotProven); assertTrue(sawProven);
    }
    @Test void theInsightsRouteCarriesI7AndTheAiviewProjectionKeepsItsVocabulary() {
        var mapper = new ObjectMapper();
        var names = new TopologyNamePseudonymizer(new byte[32]); var ips = new SubnetPreservingIpMasker(new byte[32]);
        var query = new PolicyQueryService(mock(TransactionBoundary.class), mapper, names, null, ips);
        // Layer one: r2 can move above r1 across x (disjoint). Layer two: b2 is blocked by an anchor.
        var snapshot = snapshot(cpSection("s1", null, profiled(cp("r1", 1, "Accept", "lo", "any"), "PROF-ALPHA"), cp("x", 2, "Drop", "other", "any"), cp("r2", 3, "Accept", "net", "any")),
            cpSection("s2", null, inLayer(cp("b1", 1, "Accept", "lo", "any"), "LAYER-BRAVO"), inLayer(PolicyUnionCoverTest.timed(cp("bx", 2, "Accept", "other", "any")), "LAYER-BRAVO"),
                inLayer(cp("b2", 3, "Accept", "net", "any"), "LAYER-BRAVO")));
        JsonNode body = mapper.valueToTree(query.insights(snapshot, 0, "I7", "", 90).body());
        assertEquals(1, body.path("total").asInt());
        assertEquals(1, body.path("byType").path("I7").asInt());
        var block = body.path("safeReorder");
        assertEquals(2, block.path("evaluated").asInt());
        assertEquals(1, block.path("safeInspectionChanges").asInt());
        assertEquals(1, block.path("anchorBlocked").asInt());
        for (String key : List.of("safe", "unsafeDifferentEffect", "crossesSection", "notProven", "notEvaluable")) assertTrue(block.has(key), key);
        assertTrue(body.has("partialShadow")); assertTrue(body.has("unionCover"));
        // The same call twice reads the cached I7 pass of the same snapshot instance.
        assertEquals(body, mapper.valueToTree(query.insights(snapshot, 0, "I7", "", 90).body()));
        JsonNode masked = mapper.valueToTree(PolicyPrivacy.mask(mapper.convertValue(query.insights(snapshot, 0, "I7", "", 90).body(), Map.class), "", names, ips));
        var row = masked.path("insights").get(0);
        assertEquals("I7", row.path("insightType").asText());
        assertEquals(PolicyInsights.I7_INSPECTION, row.path("evidence").get(0).asText());
        assertEquals(List.of("subject", "promoteAbove", "crossed"), List.of(row.path("rules").get(0).path("insightRole").asText(),
            row.path("rules").get(1).path("insightRole").asText(), row.path("rules").get(2).path("insightRole").asText()));
        assertEquals(PolicyReorder.SAFE_INSPECTION_CHANGES, row.path("counter").path("reorderResult").asText());
        assertEquals(PolicyReorder.CHANGES, row.path("counter").path("equivalence").asText());
        assertEquals(PolicyReorder.DISJOINT, row.path("counter").path("crossedProof").get(1).path("crossedReason").asText());
        assertTrue(row.path("counter").path("crossedProof").get(0).path("inspectionChanges").asBoolean());
        assertEquals(1, masked.path("safeReorder").path("safeInspectionChanges").asInt());
        assertFalse(masked.toString().contains("OBJ-RULE-"), "rule names are pseudonymised for aiview");
        assertFalse(masked.toString().contains("198.51.100") || masked.toString().contains("192.0.2"), "no address in an I7 row");
        var detail = query.insightDetail(snapshot, body.path("insights").get(0).path("insightId").asText(), 90).orElseThrow();
        assertEquals(3, ((List<?>) detail.body().get("rules")).size(), "the promoted rule, B and the crossed rule");
        JsonNode detailJson = mapper.valueToTree(detail.body());
        assertTrue(detailJson.path("ruleObjects").size() >= 3, "the objects the three rules name");
        JsonNode maskedDetail = mapper.valueToTree(PolicyPrivacy.mask(mapper.convertValue(detail.body(), Map.class), "", names, ips));
        assertFalse(maskedDetail.toString().contains("OBJ-RULE-"));
    }
    @Test void aiviewKeepsOnlyTheClosedReorderWords() {
        var names = new TopologyNamePseudonymizer(new byte[32]); var ips = new SubnetPreservingIpMasker(new byte[32]);
        for (String role : List.of("promoteAbove", "crossed")) assertEquals(role, PolicyPrivacy.mask(role, "insightRole", names, ips));
        assertEquals("UNKNOWN", PolicyPrivacy.mask("OBJ-RULE-7", "insightRole", names, ips));
        for (String word : List.of(PolicyReorder.SAFE, PolicyReorder.SAFE_INSPECTION_CHANGES)) assertEquals(word, PolicyPrivacy.mask(word, "reorderResult", names, ips));
        assertEquals("UNKNOWN", PolicyPrivacy.mask(PolicyReorder.UNSAFE_DIFFERENT_EFFECT, "reorderResult", names, ips), "an unoffered result is never shown");
        for (String word : List.of(PolicyReorder.EQUIVALENT, PolicyReorder.CHANGES, PolicyReorder.NOT_PROVEN)) assertEquals(word, PolicyPrivacy.mask(word, "equivalence", names, ips));
        assertEquals(PolicyReorder.NOT_PROVEN, PolicyPrivacy.mask("192.0.2.0/25 changes", "equivalence", names, ips));
        for (String word : List.of(PolicyReorder.DISJOINT, PolicyReorder.SAME_VERDICT)) assertEquals(word, PolicyPrivacy.mask(word, "crossedReason", names, ips));
        assertEquals("UNKNOWN", PolicyPrivacy.mask("OBJ-RULE-7 overlaps", "crossedReason", names, ips));
        assertEquals("analysis budget", PolicyPrivacy.mask("analysis budget", "reorderReason", names, ips));
        assertEquals("UNKNOWN", PolicyPrivacy.mask("OBJ-RULE-7", "reorderReason", names, ips));
        for (String e : List.of(PolicyInsights.I7_SAFE, PolicyInsights.I7_INSPECTION, PolicyInsights.I7_BUDGET)) assertEquals(e, PolicyPrivacy.mask(e, "evidence", names, ips));
    }
}
