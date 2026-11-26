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
        // I7 sits right after I5 (and I5 after I4) in the display order, and the package counts stay the shadow engine's.
        assertTrue(PolicyInsights.TYPES.indexOf("I5") == PolicyInsights.TYPES.indexOf("I4") + 1);
        assertTrue(PolicyInsights.TYPES.indexOf("I7") == PolicyInsights.TYPES.indexOf("I5") + 1);
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
        var asa = PolicyEffectSignature.of("ASA", r);
        assertFalse(asa.profiles().known()); assertFalse(asa.log().known());
        assertFalse(PolicyEffectSignature.sameFull("ASA", r, r));
        // Palo Alto IS mapped, but a non-PAN log value ("Log") and absent profile-setting/group both fail closed.
        var panBad = PolicyEffectSignature.of("PAN", r);
        assertFalse(panBad.profiles().known()); assertFalse(panBad.log().known());
        assertFalse(PolicyEffectSignature.sameFull("PAN", r, r));
        var panRule = withLog(profiled(r, "PROF-ALPHA"), "start=no, end=yes");
        var panGood = PolicyEffectSignature.of("PAN", panRule);
        assertTrue(panGood.profiles().known()); assertTrue(panGood.log().known());
        assertTrue(PolicyEffectSignature.sameFull("PAN", panRule, panRule));
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
        for (String vendor : List.of("ASA", "FGT")) {
            var unsup = PolicyUnionCoverTest.unsupportedVendorSnapshot(vendor);
            var unsupResult = reorder(unsup);
            assertEquals(0, unsupResult.evaluated(), vendor);
            assertTrue(unsupResult.moves().isEmpty(), vendor);
            assertEquals(Set.of(PolicyUnionCover.UNSUPPORTED_VENDOR), unsupResult.notEvaluable().keySet(), vendor);
            assertTrue(PolicyInsightsTest.of(PolicyInsightsTest.report(unsup), "I7").isEmpty(), vendor);
        }
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

    @Test void paloAltoSafePromoteInspectionChangesSourcePortCrossedDenyScopeBoundariesAndAnchors() {
        String za = PolicyUnionCoverTest.ZA, zb = PolicyUnionCoverTest.ZB, zc = PolicyUnionCoverTest.ZC;
        Map<String, List<String>> profA = Map.of("profile-setting/group", List.of("PROF-ALPHA"), "log-setting", List.of("LOG-FWD-ALPHA"), "target/negate", List.of("no"));

        // 1. Plain SAFE promote on PAN across a zone-disjoint deny rule.
        var safeSnap = PolicyUnionCoverTest.panSnapshot(PolicyUnionCoverTest.panSection("DG-TANGO-01", "pre-rulebase",
            PolicyUnionCoverTest.panRule("r1", 1, "allow", List.of(za, zb), List.of(zc), "lo", "any", "svc-p1", profA),
            PolicyUnionCoverTest.panRule("x", 2, "deny", List.of(zc), List.of(zc), "any", "any", "svc-p1", Map.of()),
            PolicyUnionCoverTest.panRule("r2", 3, "allow", List.of(za), List.of(zc), "net", "any", "svc-p1", profA)));
        var safeMove = reorder(safeSnap).moves().get("r2");
        assertEquals(PolicyReorder.SAFE, safeMove.result());
        assertEquals(PolicyReorder.EQUIVALENT, safeMove.equivalence());
        assertEquals(List.of(new PolicyReorder.Crossed(safeSnap.sections().get(0).rules().get(1), PolicyReorder.DISJOINT, false)), safeMove.crossed());

        // 2. Profile group, individual spyware profile, log-setting, log-start/end, or start=UNKNOWN -> SAFE_INSPECTION_CHANGES.
        List<Rule> inspectionSubjects = List.of(
            PolicyUnionCoverTest.panRule("r2", 2, "allow", List.of(za), List.of(zc), "net", "any", "svc-p1", Map.of()),
            PolicyUnionCoverTest.panRule("r2", 2, "allow", List.of(za), List.of(zc), "net", "any", "svc-p1", Map.of("profile-setting/profiles/spyware", List.of("AS-ALPHA"), "log-setting", List.of("LOG-FWD-ALPHA"))),
            PolicyUnionCoverTest.panRule("r2", 2, "allow", List.of(za), List.of(zc), "net", "any", "svc-p1", Map.of("profile-setting/group", List.of("PROF-ALPHA"))),
            withLog(PolicyUnionCoverTest.panRule("r2", 2, "allow", List.of(za), List.of(zc), "net", "any", "svc-p1", profA), "start=yes, end=yes"),
            withLog(PolicyUnionCoverTest.panRule("r2", 2, "allow", List.of(za), List.of(zc), "net", "any", "svc-p1", profA), "start=UNKNOWN, end=yes"));
        for (Rule subj : inspectionSubjects) {
            var snap = PolicyUnionCoverTest.panSnapshot(PolicyUnionCoverTest.panSection("DG-TANGO-01", "pre-rulebase",
                PolicyUnionCoverTest.panRule("r1", 1, "allow", List.of(za), List.of(zc), "lo", "any", "svc-p1", profA),
                subj));
            var mv = reorder(snap).moves().get("r2");
            assertEquals(PolicyReorder.SAFE_INSPECTION_CHANGES, mv.result(), subj.toString());
            assertEquals(PolicyReorder.CHANGES, mv.equivalence(), subj.toString());
        }

        // 2b. Absent profile-setting/* or individual profiles on BOTH rules still fail closed to SAFE_INSPECTION_CHANGES (uncollected data-filtering).
        for (Map<String, List<String>> unclosedProf : List.of(Map.<String, List<String>>of(), Map.of("profile-setting/profiles/virus", List.of("AV-ALPHA")))) {
            var snap = PolicyUnionCoverTest.panSnapshot(PolicyUnionCoverTest.panSection("DG-TANGO-01", "pre-rulebase",
                PolicyUnionCoverTest.panRule("r1", 1, "allow", List.of(za), List.of(zc), "lo", "any", "svc-p1", unclosedProf),
                PolicyUnionCoverTest.panRule("r2", 2, "allow", List.of(za), List.of(zc), "net", "any", "svc-p1", unclosedProf)));
            var mv = reorder(snap).moves().get("r2");
            assertEquals(PolicyReorder.SAFE_INSPECTION_CHANGES, mv.result(), unclosedProf.toString());
            assertEquals(PolicyReorder.CHANGES, mv.equivalence(), unclosedProf.toString());
        }

        // 3. Source-port family overlap on a crossed deny rule: I4 suppresses r2 as differentAction, and PolicyReorder
        // itself also refuses r2 as UNSAFE_DIFFERENT_EFFECT when handed r2 as a candidate (defense in depth).
        var spUnsafe = PolicyUnionCoverTest.panSnapshot(PolicyUnionCoverTest.panSection("DG-TANGO-01", "pre-rulebase",
            PolicyUnionCoverTest.panRule("r1", 1, "allow", List.of(za), List.of(zb), "lo", "any", "svc-p1", Map.of()),
            PolicyUnionCoverTest.panRule("x", 2, "deny", List.of(za), List.of(zb), "hi", "any", "svc-p1-s-hi", Map.of()),
            PolicyUnionCoverTest.panRule("r2", 3, "allow", List.of(za), List.of(zb), "net", "any", "svc-p1", Map.of())));
        assertEquals(1, PolicyPartialShadowTest.partial(spUnsafe).differentAction());
        assertTrue(reorder(spUnsafe).moves().isEmpty());
        var syntheticCandidate = new PolicyPartialShadow.Result(
            Map.of("r2", new PolicyPartialShadow.Partial(PolicyPartialShadow.PARTIAL, null, List.of(spUnsafe.sections().get(0).rules().get(0)), true)),
            1, 1, 0, 0, Map.of());
        assertEquals(PolicyReorder.UNSAFE_DIFFERENT_EFFECT, PolicyReorder.analyze(spUnsafe, syntheticCandidate).moves().get("r2").result());

        // 4. Phase (Pre / Local / Post) and device-group (Shared / DG-PARENT / DG-CHILD) boundaries -> CROSSES_SECTION.
        List<PolicySnapshot> boundaryCases = List.of(
            PolicyUnionCoverTest.panSnapshot(
                PolicyUnionCoverTest.panSection("Shared", "pre-rulebase", PolicyUnionCoverTest.panRule("r1", 1, "allow", List.of(za), List.of(zb), "lo", "any", "svc-p1", Map.of())),
                PolicyUnionCoverTest.panSection("DG-TANGO-01", "pre-rulebase", PolicyUnionCoverTest.panRule("r2", 1, "allow", List.of(za), List.of(zb), "net", "any", "svc-p1", Map.of()))),
            PolicyUnionCoverTest.panSnapshot(
                PolicyUnionCoverTest.panSection("DG-TANGO-01", "pre-rulebase", PolicyUnionCoverTest.panRule("r1", 1, "allow", List.of(za), List.of(zb), "lo", "any", "svc-p1", Map.of())),
                PolicyUnionCoverTest.panSection("DG-TANGO-01", "rulebase", PolicyUnionCoverTest.panRule("r2", 1, "allow", List.of(za), List.of(zb), "net", "any", "svc-p1", Map.of()))),
            PolicyUnionCoverTest.panSnapshot(
                PolicyUnionCoverTest.panSection("DG-TANGO-01", "rulebase", PolicyUnionCoverTest.panRule("r1", 1, "allow", List.of(za), List.of(zb), "lo", "any", "svc-p1", Map.of())),
                PolicyUnionCoverTest.panSection("DG-TANGO-01", "post-rulebase", PolicyUnionCoverTest.panRule("r2", 1, "allow", List.of(za), List.of(zb), "net", "any", "svc-p1", Map.of()))),
            PolicyUnionCoverTest.panSnapshot(
                PolicyUnionCoverTest.panSection("DG-TANGO-01", "post-rulebase", PolicyUnionCoverTest.panRule("r1", 1, "allow", List.of(za), List.of(zb), "lo", "any", "svc-p1", Map.of())),
                PolicyUnionCoverTest.panSection("Shared", "post-rulebase", PolicyUnionCoverTest.panRule("r2", 1, "allow", List.of(za), List.of(zb), "net", "any", "svc-p1", Map.of()))));
        for (PolicySnapshot boundary : boundaryCases) {
            var res = reorder(boundary);
            assertEquals(1, res.crossesSection());
            assertEquals(PolicyReorder.CROSSES_SECTION, res.moves().get("r2").result());
        }

        // 5. Every PAN anchor between B and r blocks the promote (ANCHOR_BLOCKED), even on disjoint addresses/zones.
        List<Rule> panAnchors = List.of(
            PolicyUnionCoverTest.panRule("x", 2, "allow", List.of(zc), List.of(zc), "other", "other", PolicyUnionCoverTest.panAppDefaultRef(), Map.of()),
            PolicyUnionCoverTest.panRuleCells("x", 2, true, "allow", "start=no, end=yes", List.of(zc), List.of(zc),
                List.of("other"), List.of("other"), List.of("svc-p1"), List.of(PolicyUnionCoverTest.panContentAppRef()), Map.of()),
            PolicyUnionCoverTest.panRule("x", 2, "allow", List.of(zc), List.of(zc), "other", "other", "svc-p1", Map.of("source-user", List.of("OBJ-USER-01"))),
            PolicyUnionCoverTest.panRule("x", 2, "allow", List.of(zc), List.of(zc), "other", "other", "svc-p1", Map.of("schedule", List.of("sched-1"))),
            PolicyUnionCoverTest.panRule("x", 2, "allow", List.of(zc), List.of(zc), "other", "other", "svc-p1", Map.of("target-selectors", List.of("SERIAL-ALPHA-01"))),
            PolicyUnionCoverTest.panRule("x", 2, "allow", List.of(zc), List.of(zc), "other", "other", "svc-p1", Map.of("target/negate", List.of("yes"))),
            PolicyUnionCoverTest.panRule("x", 2, "allow", List.of(zc), List.of(zc), "other", "other", "svc-p1", Map.of("rule-type", List.of("intrazone"))),
            PolicyUnionCoverTest.panRule("x", 2, "allow", List.of("any", zc), List.of(zc), "other", "other", "svc-p1", Map.of()));
        for (Rule anchor : panAnchors) {
            var snap = PolicyUnionCoverTest.panSnapshot(PolicyUnionCoverTest.panSection("DG-TANGO-01", "pre-rulebase",
                PolicyUnionCoverTest.panRule("r1", 1, "allow", List.of(za), List.of(zb), "lo", "any", "svc-p1", Map.of()),
                anchor,
                PolicyUnionCoverTest.panRule("r2", 3, "allow", List.of(za), List.of(zb), "net", "any", "svc-p1", Map.of())));
            assertEquals(PolicyReorder.ANCHOR_BLOCKED, reorder(snap).moves().get("r2").result(), anchor.toString());
        }
    }

    @Test void paloAltoEveryMoveAgreesWithBruteForceOracleOnRandomRulebases() {
        Random random = new Random(2026112603L);
        Set<PolicyUnionCoverTest.PanPacket> allUniverse = new HashSet<>(PolicyUnionCoverTest.panRulePackets(
            PolicyUnionCoverTest.panRule("u", 1, "allow", List.of("any"), List.of("any"), "any", "any", "any", Map.of()),
            PolicyUnionCoverTest.PAN_WORLD_EXT, PolicyUnionCoverTest.PAN_WORLD_EXT));
        allUniverse.addAll(PolicyUnionCoverTest.panRulePackets(
            PolicyUnionCoverTest.panRule("u2", 1, "allow", List.of("any"), List.of("any"), "other", "other", "any", Map.of()),
            PolicyUnionCoverTest.PAN_WORLD_EXT, PolicyUnionCoverTest.PAN_WORLD_EXT));

        int safeCount = 0, inspectionCount = 0, unsafeCount = 0, anchorCount = 0, sectionCount = 0, exactChecks = 0, budgetStops = 0;
        for (int trial = 0; trial < 340; trial++) {
            var realized = PolicyUnionCoverTest.panRealize(
                PolicyUnionCoverTest.randomPanRulebase(random, trial % 2 == 0, trial % 3 == 0));
            var items = realized.items();
            var snapshot = realized.snapshot();
            var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250_000);
            var union = PolicyUnionCover.analyze(snapshot, hygiene);
            var partial = PolicyPartialShadow.analyze(snapshot, hygiene, union);
            var result = PolicyReorder.analyze(snapshot, partial);

            var expectedCandidates = partial.rules().entrySet().stream()
                .filter(e -> PolicyPartialShadow.PARTIAL.equals(e.getValue().status()) && e.getValue().sameAction())
                .map(Map.Entry::getKey).sorted().toList();
            assertEquals(expectedCandidates, result.moves().keySet().stream().sorted().toList(), "trial " + trial);
            assertEquals(result.evaluated(), result.safe() + result.safeInspectionChanges() + result.unsafeDifferentEffect()
                + result.anchorBlocked() + result.crossesSection() + result.notProven(), "trial " + trial);

            Map<String, PolicyUnionCoverTest.PanSynthetic> byId = new HashMap<>();
            Map<String, Set<PolicyUnionCoverTest.PanPacket>> pts = new HashMap<>();
            for (var item : items) {
                byId.put(item.rule().id(), item);
                pts.put(item.rule().id(), PolicyUnionCoverTest.panRulePackets(
                    item.rule(), PolicyUnionCoverTest.PAN_WORLD_EXT, PolicyUnionCoverTest.PAN_WORLD_EXT));
            }
            List<PolicyUnionCoverTest.PanSynthetic> enabled = items.stream().filter(PolicyUnionCoverTest.PanSynthetic::enabled).toList();

            for (var e : result.moves().entrySet()) {
                var move = e.getValue();
                var r = byId.get(e.getKey());
                var b = byId.get(move.above().rule().id());
                int ib = enabled.indexOf(b), ir = enabled.indexOf(r);
                assertTrue(ib >= 0 && ib < ir, "trial " + trial);
                var between = enabled.subList(ib + 1, ir);
                boolean hasAnchor = between.stream().anyMatch(PolicyUnionCoverTest.PanSynthetic::anchor);
                boolean diffSec = b.sectionIndex() != r.sectionIndex();
                boolean diffVerdict = between.stream().anyMatch(c -> c.readable()
                    && !Collections.disjoint(pts.get(c.rule().id()), pts.get(r.rule().id()))
                    && !PolicyEffectSignature.sameVerdict(c.rule(), r.rule()));

                switch (move.result()) {
                    case PolicyReorder.ANCHOR_BLOCKED -> { anchorCount++; assertTrue(hasAnchor, "trial " + trial); continue; }
                    case PolicyReorder.CROSSES_SECTION -> { sectionCount++; assertFalse(hasAnchor); assertTrue(diffSec, "trial " + trial); continue; }
                    case PolicyReorder.UNSAFE_DIFFERENT_EFFECT -> { unsafeCount++; assertFalse(hasAnchor || diffSec); assertTrue(diffVerdict, "trial " + trial); continue; }
                    case PolicyReorder.SAFE -> { safeCount++; assertEquals(PolicyReorder.EQUIVALENT, move.equivalence()); }
                    case PolicyReorder.SAFE_INSPECTION_CHANGES -> inspectionCount++;
                    default -> fail("unexpected PAN reorder result: " + move.result());
                }
                assertFalse(hasAnchor || diffSec || diffVerdict, "offered invalid PAN move in trial " + trial);

                List<PolicyUnionCoverTest.PanSynthetic> after = new ArrayList<>(enabled);
                after.remove(r);
                after.add(after.indexOf(b), r);
                boolean exact = enabled.subList(0, ir).stream().allMatch(p -> !p.readable()
                    || Collections.disjoint(pts.get(p.rule().id()), pts.get(r.rule().id()))
                    || PolicyNormalization.zonesCover(
                        Set.copyOf(p.rule().extras().get("from")), Set.copyOf(p.rule().extras().get("to")),
                        Set.copyOf(r.rule().extras().get("from")), Set.copyOf(r.rule().extras().get("to")), false));

                for (boolean anchorsMatch : new boolean[]{false, true}) {
                    Set<String> oldDeciders = new TreeSet<>();
                    boolean fullEffectKept = true;
                    for (var pkt : allUniverse) {
                        Rule beforeDec = PolicyMergeTest.panFirstMatch(enabled, pkt, anchorsMatch, pts);
                        Rule afterDec = PolicyMergeTest.panFirstMatch(after, pkt, anchorsMatch, pts);
                        if (beforeDec == afterDec) continue;
                        assertNotNull(beforeDec); assertNotNull(afterDec);
                        assertTrue(PolicyEffectSignature.sameVerdict(beforeDec, afterDec), "PAN verdict changed in trial " + trial);
                        assertSame(r.rule(), afterDec);
                        oldDeciders.add(beforeDec.id());
                        if (!PolicyEffectSignature.sameFull("PAN", beforeDec, afterDec)) fullEffectKept = false;
                        if (PolicyReorder.SAFE.equals(move.result())) {
                            assertTrue(PolicyEffectSignature.sameFull("PAN", beforeDec, afterDec), "SAFE PAN move changed effect in trial " + trial);
                        }
                    }
                    Set<String> reportedDeciding = new TreeSet<>(move.deciding().stream().map(Rule::id).toList());
                    assertTrue(reportedDeciding.containsAll(oldDeciders), "missing old decider in trial " + trial);
                    if (PolicyReorder.EQUIVALENT.equals(move.equivalence())) {
                        assertTrue(fullEffectKept, "EQUIVALENT but PAN effect changed in trial " + trial);
                    }
                    if (!anchorsMatch && exact) {
                        exactChecks++;
                        assertEquals(oldDeciders, reportedDeciding, "exact deciding set mismatch in trial " + trial);
                        assertEquals(fullEffectKept, PolicyReorder.EQUIVALENT.equals(move.equivalence()), "exact equivalence mismatch in trial " + trial);
                    }
                }
            }

            var limited = PolicyReorder.analyze(snapshot, partial, 15 + random.nextInt(120), 10 + random.nextInt(60));
            for (var e : limited.moves().entrySet()) {
                var mv = e.getValue();
                if (PolicyReorder.NOT_PROVEN.equals(mv.result())) {
                    budgetStops++;
                    assertEquals(PolicyReorder.BUDGET, mv.reason());
                    assertEquals(PolicyReorder.NOT_PROVEN, mv.equivalence());
                } else if (PolicyReorder.OFFERED.contains(mv.result())) {
                    assertEquals(result.moves().get(e.getKey()).result(), mv.result(), "limited budget wrong PAN reorder in trial " + trial);
                }
            }
        }
        assertTrue(safeCount >= 20, "PAN SAFE moves exercised: " + safeCount);
        assertTrue(inspectionCount >= 20, "PAN inspection-change moves exercised: " + inspectionCount);
        // On PAN, PolicyPartialShadow already screens every non-disjoint overlapping rule for sameAction, so I4-fed moves have 0 unsafe.
        assertEquals(0, unsafeCount);
        assertTrue(anchorCount >= 15, "PAN anchor-blocked moves exercised: " + anchorCount);
        assertTrue(sectionCount >= 10, "PAN section/phase/DG-blocked moves exercised: " + sectionCount);
        assertTrue(exactChecks >= 20, "PAN exact deciding-region checks exercised: " + exactChecks);
        assertTrue(budgetStops >= 15, "PAN reorder budget stops exercised: " + budgetStops);
    }
}
