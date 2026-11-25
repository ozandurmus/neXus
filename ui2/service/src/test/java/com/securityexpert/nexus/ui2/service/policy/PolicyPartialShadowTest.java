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
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.object;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Insight I4 (partly shadowed by earlier rules of the same action), Check Point fixtures: the overlapping set, the action
 * relation (a different-action overlap is counted, never shown), fixed anchors (Q3), never across a layer (Q9), placement,
 * a rule-complete snapshot and budget exhaustion as NOT_PROVEN (Q8). Documentation addresses (192.0.2.0/24) only.
 */
class PolicyPartialShadowTest {
    /** The halves of the documentation /24 and a /26 inside the upper half. */
    static PolicySnapshot snapshot(Section... sections) { return PolicyUnionCoverTest.snapshot(sections); }
    static PolicyInsights.Report report(PolicySnapshot snapshot) { return PolicyInsightsTest.report(snapshot); }
    static List<PolicyInsights.Insight> i4(PolicySnapshot snapshot) { return PolicyInsightsTest.of(report(snapshot), "I4"); }
    static List<String> ids(PolicyInsights.Insight insight) { return insight.rules().stream().map(PolicyInsights.RuleRef::ruleId).toList(); }
    static PolicyPartialShadow.Result partial(PolicySnapshot snapshot) {
        var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250000);
        return PolicyPartialShadow.analyze(snapshot, hygiene, PolicyUnionCover.analyze(snapshot, hygiene));
    }

    @Test void oneEarlierSameActionRuleOverlappingPartOfTheRuleIsProvenI4() {
        // r2 (the whole /24) is larger than r1 (the lower half): r1 overlaps part of r2 and r2 is not covered.
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "net", "any")));
        assertEquals("NOT_SHADOWED", at(snapshot, "r2").shadowStatus());
        var found = i4(snapshot);
        assertEquals(1, found.size());
        var insight = found.get(0);
        assertEquals("PROVEN", insight.grade());
        assertEquals(List.of("r2", "r1"), ids(insight));
        assertEquals(List.of("subject", "overlapping"), insight.rules().stream().map(PolicyInsights.RuleRef::insightRole).toList());
        assertEquals(List.of(PolicyInsights.I4_PARTIAL), insight.evidence());
        assertEquals(1, insight.counter().get("overlappingRules")); assertEquals(0, insight.counter().get("moreOverlapping"));
        assertEquals(List.of(PolicyInsights.INSTALL_STATE_NOT_VERIFIED), insight.caveats());
        assertEquals("PROVEN", insight.raiseGrade()); assertEquals(PolicyInsights.NONE, insight.needs());
        var report = report(snapshot);
        assertEquals(1, report.byType().get("I4"));
        assertEquals(1, report.partialShadow().partial()); assertEquals(0, report.partialShadow().differentAction());
        for (String word : List.of("safe", "moved", "behaviour"))
            assertFalse(insight.evidence().get(0).toLowerCase(Locale.ROOT).contains(word), word);
        assertTrue(insight.evidence().get(0).contains("proven"));
    }
    @Test void twoEarlierRulesOverlappingTheRuleAreBothListed() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi-a", "any"), cp("r3", 3, "Accept", "net", "any")));
        var insight = i4(snapshot).stream().filter(i -> ids(i).get(0).equals("r3")).findFirst().orElseThrow();
        assertEquals(List.of("r3", "r1", "r2"), ids(insight));
        assertEquals(2, insight.counter().get("overlappingRules"));
        assertTrue(PolicyInsightsTest.of(report(snapshot), "I3").isEmpty(), "192.0.2.192/26 is left: not covered");
    }
    @Test void anOverlapTheOtherEarlierRulesHideIsStillListed() {
        // r2 lies inside r1, so subtracting r1 leaves r2 nothing: it still really intersects r3.
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "lo", "any"), cp("r3", 3, "Accept", "net", "any")));
        var insight = i4(snapshot).stream().filter(i -> ids(i).get(0).equals("r3")).findFirst().orElseThrow();
        assertEquals(List.of("r3", "r1", "r2"), ids(insight));
    }
    @Test void anOverlapWithAnotherActionIsCountedAndShowsNoInsight() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Drop", "lo", "any"), cp("r2", 2, "Accept", "net", "any")));
        var report = report(snapshot);
        assertTrue(PolicyInsightsTest.of(report, "I4").isEmpty());
        assertEquals(1, report.partialShadow().differentAction()); assertEquals(0, report.partialShadow().partial());
        // One different-action rule among the intersecting ones is enough, even when it is not the first.
        var mixed = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "hi-a", "any"), cp("r2", 2, "Drop", "lo", "any"), cp("r3", 3, "Accept", "net", "any")));
        assertTrue(i4(mixed).stream().noneMatch(i -> ids(i).get(0).equals("r3")));
        assertEquals(1, partial(mixed).differentAction());
        // A different-action rule that does not intersect the subject does not matter.
        var apart = snapshot(cpSection("s1", null, cp("r1", 1, "Drop", "lo", "any"), cp("r2", 2, "Accept", "hi", "any"), cp("r3", 3, "Accept", "hi-a", "any")));
        assertTrue(i4(apart).isEmpty(), "r3 lies inside r2 (I1); r2 does not meet r1");
    }
    @Test void anApplyLayerRuleIsNeverTheSameAction() {
        var snapshot = snapshot(cpSection("s1", null, applyLayer("r1", 1, "lo", "inline-a"), cp("r2", 2, "Accept", "net", "any")));
        assertTrue(i4(snapshot).isEmpty());
        assertEquals(1, partial(snapshot).differentAction());
        var both = snapshot(cpSection("s1", null, applyLayer("r1", 1, "lo", "inline-a"), applyLayer("r2", 2, "net", "inline-a")));
        assertTrue(i4(both).isEmpty(), "two Apply Layer rules for one inline layer are still not 'the same action'");
    }
    @Test void anUnreadableAnchorIsNeverAnOverlapNorACover() {
        // r1 has a time constraint: it is an anchor, so nothing of r2 is proven to overlap an earlier readable rule.
        var timed = snapshot(cpSection("s1", null, PolicyUnionCoverTest.timed(cp("r1", 1, "Accept", "lo", "any")), cp("r2", 2, "Accept", "net", "any")));
        assertTrue(i4(timed).isEmpty());
        // An unreadable rule in between neither hides nor adds an overlap.
        var between = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("rx", 2, "Drop", "missing", "any"),
            PolicyUnionCoverTest.timed(cp("ry", 3, "Drop", "any", "any")), cp("r2", 4, "Accept", "net", "any")));
        var insight = i4(between).get(0);
        assertEquals(List.of("r2", "r1"), ids(insight));
        // An unreadable subject has no insight and is counted as such.
        var subject = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), PolicyUnionCoverTest.timed(cp("r2", 2, "Accept", "net", "any"))));
        assertTrue(i4(subject).isEmpty());
        assertEquals(Map.of(PolicyUnionCover.NOT_RESOLVED, 1), partial(subject).notEvaluable());
    }
    @Test void aRuleTheEarlierRulesCoverAndAShadowedRuleAreNoI4() {
        var union = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi", "any"), cp("r3", 3, "Accept", "net", "any")));
        assertEquals(1, PolicyInsightsTest.of(report(union), "I3").size());
        assertTrue(i4(union).stream().noneMatch(i -> ids(i).get(0).equals("r3")));
        var single = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "net", "any"), cp("r2", 2, "Accept", "lo", "any")));
        assertEquals(1, PolicyInsightsTest.of(report(single), "I1").size());
        assertTrue(i4(single).isEmpty());
    }
    @Test void earlierRulesOfAnotherLayerAreNeverAnOverlap() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any")),
            cpSection("s2", null, inLayer(cp("b1", 1, "Accept", "net", "any"), "LAYER-BRAVO")));
        assertTrue(i4(snapshot).isEmpty());
        // The same rules in one layer, two sections: the layer continues and the overlap holds.
        var continued = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any")), cpSection("s2", null, cp("r2", 2, "Accept", "net", "any")));
        assertEquals(List.of("r2", "r1"), ids(i4(continued).get(0)));
    }
    @Test void anotherInstallOnTakesNoPartAndADisabledRuleNeither() {
        var installOn = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "targets"), cp("r2", 2, "Accept", "net", "targets-2")));
        assertTrue(i4(installOn).isEmpty());
        var r1 = cp("r1", 1, "Accept", "lo", "any");
        var disabled = snapshot(cpSection("s1", null,
            new Rule(r1.id(), r1.uuid(), r1.number(), r1.name(), false, r1.source(), r1.destination(), r1.service(), r1.application(), r1.action(), r1.log(), r1.comment(), r1.extras()),
            cp("r2", 2, "Accept", "net", "any")));
        assertTrue(i4(disabled).isEmpty());
        assertEquals(1, partial(disabled).evaluated(), "only the enabled rule is a subject");
    }
    @Test void anIncompleteSnapshotEvaluatesNothing() {
        var complete = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "net", "any")));
        var incomplete = new PolicySnapshot(complete.metadata(), complete.sections(), complete.objects(), List.of(new CollectionFailure("layer", "COLLECTION_PENDING")));
        assertTrue(i4(incomplete).isEmpty());
        var result = partial(incomplete);
        assertEquals(0, result.evaluated());
        assertEquals(Map.of(PolicyUnionCover.INCOMPLETE, 2), result.notEvaluable());
    }
    @Test void anotherVendorEvaluatesNothing() {
        var pan = PolicyHygieneTest.snapshot(List.of(PolicyHygieneTest.rule("r1", "any", "any", "allow", true), PolicyHygieneTest.rule("r2", "any", "any", "allow", true)), PolicyHygieneTest.objects());
        var result = PolicyPartialShadow.analyze(pan, PolicyHygiene.analyze(pan, 90, NOW, 250000), PolicyUnionCover.analyze(pan, PolicyHygiene.analyze(pan, 90, NOW, 250000)));
        assertEquals(0, result.evaluated()); assertTrue(result.rules().isEmpty());
        assertEquals(Set.of(PolicyUnionCover.UNSUPPORTED_VENDOR), result.notEvaluable().keySet(), "every subject of another vendor is not evaluable for that one reason");
        assertFalse(result.notEvaluable().isEmpty());
    }
    @Test void theListOfOverlappingRulesIsCappedAndTheRestIsCounted() {
        List<Rule> rules = new ArrayList<>();
        int earlier = PolicyInsights.MAX_OVERLAPPING + 3;
        for (int i = 1; i <= earlier; i++) rules.add(cp("r" + i, i, "Accept", "lo", "any"));
        // A second /26 keeps the subject from being covered by the lower half alone.
        rules.add(cp("rs", earlier + 1, "Accept", "net", "any"));
        var snapshot = snapshot(cpSection("s1", null, rules.toArray(Rule[]::new)));
        var insight = i4(snapshot).stream().filter(i -> ids(i).get(0).equals("rs")).findFirst().orElseThrow();
        assertEquals(1 + PolicyInsights.MAX_OVERLAPPING, insight.rules().size());
        assertEquals(earlier, insight.counter().get("overlappingRules"));
        assertEquals(3, insight.counter().get("moreOverlapping"));
    }
    @Test void anExhaustedBudgetIsNotEvaluableNeverAWrongPartial() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi-a", "any"), cp("r3", 3, "Accept", "net", "any")));
        var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250000);
        var union = PolicyUnionCover.analyze(snapshot, hygiene);
        boolean sawNotProven = false, sawProven = false;
        for (int work = 0; work < 600; work++) {
            var partial = PolicyPartialShadow.analyze(snapshot, hygiene, union, work, work);
            var report = PolicyInsights.analyze(snapshot, hygiene, null, Map.of(), 90, union, partial);
            for (var insight : PolicyInsightsTest.of(report, "I4")) {
                if (insight.grade().equals("PROVEN")) {
                    sawProven = true;
                    assertEquals(List.of("r3", "r1", "r2"), ids(insight), "work " + work);
                } else {
                    sawNotProven = true;
                    assertEquals("NOT_EVALUABLE", insight.grade());
                    assertEquals(List.of(PolicyInsights.I4_BUDGET), insight.evidence());
                    assertEquals(PolicyPartialShadow.BUDGET, insight.counter().get("partialReason"));
                    assertTrue(insight.caveats().isEmpty());
                }
            }
            assertEquals(partial.notProven(), report.partialShadow().notProven());
        }
        assertTrue(sawNotProven); assertTrue(sawProven);
    }
    @Test void theInsightsRouteCarriesI4AndTheAiviewProjectionKeepsItsVocabulary() {
        var mapper = new ObjectMapper();
        var names = new TopologyNamePseudonymizer(new byte[32]); var ips = new SubnetPreservingIpMasker(new byte[32]);
        var query = new PolicyQueryService(mock(TransactionBoundary.class), mapper, names, null, ips);
        // Layer one: r2 is partly shadowed by r1 (same action). Layer two: b2 meets a Drop rule, so it is counted, not shown.
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "net", "any")),
            cpSection("s2", null, inLayer(cp("b1", 1, "Drop", "lo", "any"), "LAYER-BRAVO"), inLayer(cp("b2", 2, "Accept", "net", "any"), "LAYER-BRAVO")));
        JsonNode body = mapper.valueToTree(query.insights(snapshot, 0, "I4", "", 90).body());
        assertEquals(1, body.path("total").asInt());
        assertEquals(1, body.path("byType").path("I4").asInt());
        var block = body.path("partialShadow");
        assertEquals(body.path("total").asInt(), block.path("partial").asInt());
        assertEquals(1, block.path("differentAction").asInt(), "b2 meets the Drop rule b1");
        assertEquals(4, block.path("evaluated").asInt(), "four enabled subjects: r1 and b1 meet nothing, r2 and b2 meet a rule");
        assertTrue(block.has("notProven")); assertTrue(block.has("notEvaluable"));
        // The same call twice reads the cached I4 pass of the same snapshot instance.
        assertEquals(body, mapper.valueToTree(query.insights(snapshot, 0, "I4", "", 90).body()));
        JsonNode masked = mapper.valueToTree(PolicyPrivacy.mask(mapper.convertValue(query.insights(snapshot, 0, "I4", "", 90).body(), Map.class), "", names, ips));
        var row = masked.path("insights").get(0);
        assertEquals("I4", row.path("insightType").asText());
        assertEquals(PolicyInsights.I4_PARTIAL, row.path("evidence").get(0).asText());
        assertEquals("overlapping", row.path("rules").get(1).path("insightRole").asText());
        assertTrue(row.path("counter").path("overlappingRules").asInt() >= 1);
        assertTrue(masked.path("partialShadow").has("evaluated"));
        assertFalse(masked.toString().contains("OBJ-RULE-"), "rule names are pseudonymised for aiview");
        var detail = query.insightDetail(snapshot, body.path("insights").get(0).path("insightId").asText(), 90).orElseThrow();
        assertEquals(body.path("insights").get(0).path("rules").size(), ((List<?>) detail.body().get("rules")).size());
    }
    @Test void aiviewKeepsOnlyTheClosedPartialReasonWords() {
        var mapper = new ObjectMapper();
        var names = new TopologyNamePseudonymizer(new byte[32]); var ips = new SubnetPreservingIpMasker(new byte[32]);
        assertEquals("analysis budget", PolicyPrivacy.mask("analysis budget", "partialReason", names, ips));
        assertEquals("rule not resolved", PolicyPrivacy.mask("rule not resolved", "partialReason", names, ips));
        assertEquals("UNKNOWN", PolicyPrivacy.mask("OBJ-RULE-7 source 192.0.2.9", "partialReason", names, ips));
        assertEquals("overlapping", PolicyPrivacy.mask("overlapping", "insightRole", names, ips));
        assertEquals("UNKNOWN", PolicyPrivacy.mask("OBJ-RULE-7", "insightRole", names, ips));
        assertEquals("Evidence withheld in AIView.", PolicyPrivacy.mask("Part of OBJ-RULE-7 overlaps.", "evidence", names, ips));
    }
}
