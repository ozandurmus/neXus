package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.privacy.*;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.NOW;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.cell;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.cpSection;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.edit;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.object;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Insight I3 (covered by several earlier rules together), Check Point fixtures: the covering set, the action relation,
 * fixed anchors (Q3), never across a layer (Q9), placement, a rule-complete snapshot, and budget exhaustion as NOT_PROVEN
 * (Q8). Masked names and documentation addresses (192.0.2.0/24) only.
 */
class PolicyUnionCoverTest {
    /** The two halves of the documentation /24, each a rule of its own. */
    static PolicySnapshot snapshot(Section... sections) {
        var base = cpSnapshot(sections);
        var objects = new HashMap<>(base.objects());
        objects.put("lo", object("lo", "address", "ip-netmask: 192.0.2.0/25"));
        objects.put("hi", object("hi", "address", "ip-netmask: 192.0.2.128/25"));
        objects.put("hi-a", object("hi-a", "address", "ip-netmask: 192.0.2.128/26"));
        objects.put("t1", new PolicyObject("t1", "OBJ-TIME-01", "time", List.of(), List.of(), "RESOLVED"));
        return new PolicySnapshot(base.metadata(), base.sections(), objects, base.failures());
    }
    static PolicyInsights.Report report(PolicySnapshot snapshot) { return PolicyInsightsTest.report(snapshot); }
    static List<PolicyInsights.Insight> i3(PolicySnapshot snapshot) { return PolicyInsightsTest.of(report(snapshot), "I3"); }
    static List<String> ids(PolicyInsights.Insight insight) { return insight.rules().stream().map(PolicyInsights.RuleRef::ruleId).toList(); }
    static Rule timed(Rule rule) { return edit(rule, rule.source(), Map.of("time", List.of("t1"))); }

    @Test void aRuleCoveredByTwoEarlierRulesTogetherIsProvenI3WithTheCoveringSetListed() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi", "any"), cp("r3", 3, "Accept", "net", "any")));
        assertEquals("NOT_SHADOWED", at(snapshot, "r3").shadowStatus(), "no single earlier rule covers it: not I1");
        var found = i3(snapshot);
        assertEquals(1, found.size());
        var insight = found.get(0);
        assertEquals("PROVEN", insight.grade());
        assertEquals(List.of("r3", "r1", "r2"), ids(insight));
        assertEquals(List.of("subject", "covering", "covering"), insight.rules().stream().map(PolicyInsights.RuleRef::insightRole).toList());
        assertEquals(List.of(PolicyInsights.I3_SAME), insight.evidence());
        assertEquals(PolicyUnionCover.SAME, insight.counter().get("coverAction"));
        assertEquals(2, insight.counter().get("coveringRules"));
        assertEquals(List.of(PolicyInsights.INSTALL_STATE_NOT_VERIFIED), insight.caveats());
        assertEquals("PROVEN", insight.raiseGrade()); assertEquals(PolicyInsights.NONE, insight.needs());
        assertEquals(1, report(snapshot).byType().get("I3"));
    }
    @Test void onlyTheRulesThatTakePartAreListed() {
        // r2 lies inside r1's region: once r1 is subtracted it takes nothing, so it is not part of the covering set.
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "lo", "any"),
            cp("r3", 3, "Accept", "hi-a", "any"), cp("r4", 4, "Accept", "hi", "any"), cp("r5", 5, "Accept", "net", "any")));
        var r5 = i3(snapshot).stream().filter(i -> ids(i).get(0).equals("r5")).findFirst().orElseThrow();
        assertEquals(List.of("r5", "r1", "r3", "r4"), ids(r5));
    }
    @Test void aCoveringSetWithAnotherActionIsStillProvenAndSaysSo() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Drop", "hi", "any"), cp("r3", 3, "Accept", "net", "any")));
        var insight = i3(snapshot).get(0);
        assertEquals("PROVEN", insight.grade(), "the rule is never reached whatever the actions are");
        assertEquals(PolicyUnionCover.DIFFERENT, insight.counter().get("coverAction"));
        assertEquals(List.of(PolicyInsights.I3_DIFFERENT), insight.evidence());
    }
    @Test void applyLayerRulesInTheCoveringSetAreNamedAsSuch() {
        var snapshot = snapshot(cpSection("s1", null, applyLayer("r1", 1, "lo", "inline-a"), cp("r2", 2, "Accept", "hi", "any"), cp("r3", 3, "Accept", "net", "any")));
        var insight = i3(snapshot).get(0);
        assertEquals(PolicyUnionCover.INLINE_LAYER, insight.counter().get("coverAction"));
        assertEquals(List.of(PolicyInsights.I3_INLINE), insight.evidence());
    }
    @Test void aPartialUnionIsNoInsightAndASingleRuleCoverStaysI1() {
        var partial = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi-a", "any"), cp("r3", 3, "Accept", "net", "any")));
        assertTrue(i3(partial).isEmpty(), "192.0.2.192/26 is left: not covered");
        var single = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "net", "any"), cp("r3", 3, "Accept", "hi", "any")));
        var report = report(single);
        assertTrue(PolicyInsightsTest.of(report, "I3").isEmpty(), "r2 alone covers r3: that is I1, never I3");
        assertEquals(List.of("r3", "r2"), ids(PolicyInsightsTest.of(report, "I1").get(0)));
    }
    @Test void anchorsNeverCoverAndNeverBlockACover() {
        // r2 has a time constraint (an anchor, design Q3): it takes no part in a cover, so r1 alone leaves the upper half open.
        var anchorOnly = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), timed(cp("r2", 2, "Accept", "hi", "any")), cp("r3", 3, "Accept", "net", "any")));
        assertTrue(i3(anchorOnly).isEmpty());
        // An unreadable rule between the covering rules and the subject does not weaken the proof.
        var between = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("rx", 2, "Accept", "missing", "any"),
            timed(cp("ry", 3, "Drop", "any", "any")), cp("r2", 4, "Accept", "hi", "any"), cp("r3", 5, "Accept", "net", "any")));
        var insight = i3(between).get(0);
        assertEquals(List.of("r3", "r1", "r2"), ids(insight));
        // An unreadable subject is never an I3 subject.
        var subject = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi", "any"), timed(cp("r3", 3, "Accept", "net", "any"))));
        assertTrue(i3(subject).isEmpty());
        var union = PolicyUnionCover.analyze(subject, PolicyHygiene.analyze(subject, 90, NOW, 250000));
        assertEquals(Map.of(PolicyUnionCover.NOT_RESOLVED, 1), union.notEvaluable());
    }
    @Test void earlierRulesOfAnotherLayerNeverCover() {
        // Two ordered layers: the second starts its own scope, so the first layer's rules are no part of its covers (Q9).
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi", "any")),
            cpSection("s2", null, inLayer(cp("b1", 1, "Accept", "net", "any"), "LAYER-BRAVO")));
        assertEquals("NOT_SHADOWED", at(snapshot, "b1").shadowStatus());
        assertTrue(i3(snapshot).isEmpty());
        // The same rules in one layer, two sections: the layer continues and the cover holds.
        var sections = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi", "any")),
            cpSection("s2", null, cp("r3", 3, "Accept", "net", "any")));
        assertEquals(List.of("r3", "r1", "r2"), ids(i3(sections).get(0)));
    }
    @Test void anotherInstallOnTakesNoPartAndADisabledRuleNeither() {
        var installOn = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "targets"), cp("r2", 2, "Accept", "hi", "targets-2"), cp("r3", 3, "Accept", "net", "targets")));
        assertTrue(i3(installOn).isEmpty());
        var r2 = cp("r2", 2, "Accept", "hi", "any");
        var disabled = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"),
            new Rule(r2.id(), r2.uuid(), r2.number(), r2.name(), false, r2.source(), r2.destination(), r2.service(), r2.application(), r2.action(), r2.log(), r2.comment(), r2.extras()),
            cp("r3", 3, "Accept", "net", "any")));
        assertTrue(i3(disabled).isEmpty());
    }
    @Test void anIncompleteSnapshotEvaluatesNothing() {
        var complete = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi", "any"), cp("r3", 3, "Accept", "net", "any")));
        var incomplete = new PolicySnapshot(complete.metadata(), complete.sections(), complete.objects(), List.of(new CollectionFailure("layer", "COLLECTION_PENDING")));
        assertTrue(i3(incomplete).isEmpty());
        var union = PolicyUnionCover.analyze(incomplete, PolicyHygiene.analyze(incomplete, 90, NOW, 250000));
        assertEquals(0, union.evaluated());
        assertEquals(Map.of(PolicyUnionCover.INCOMPLETE, 3), union.notEvaluable());
    }
    @Test void anExhaustedBudgetIsNotEvaluableNeverProven() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi", "any"), cp("r3", 3, "Accept", "net", "any")));
        var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250000);
        boolean sawNotProven = false;
        for (int work = 0; work < 400; work++) {
            var union = PolicyUnionCover.analyze(snapshot, hygiene, work, work);
            var report = PolicyInsights.analyze(snapshot, hygiene, null, Map.of(), 90, union);
            for (var insight : PolicyInsightsTest.of(report, "I3")) {
                if (insight.grade().equals("PROVEN")) assertEquals(List.of("r3", "r1", "r2"), ids(insight), "work " + work);
                else {
                    sawNotProven = true;
                    assertEquals("NOT_EVALUABLE", insight.grade());
                    assertEquals(List.of(PolicyInsights.I3_BUDGET), insight.evidence());
                    assertEquals(PolicyUnionCover.BUDGET, insight.counter().get("unionReason"));
                    assertTrue(insight.caveats().isEmpty());
                }
            }
            assertEquals(union.notProven(), report.unionCover().notProven());
        }
        assertTrue(sawNotProven);
    }
    @Test void theInsightsRouteCarriesI3AndTheAiviewProjectionKeepsItsVocabulary() {
        var mapper = new ObjectMapper();
        var names = new TopologyNamePseudonymizer(new byte[32]); var ips = new SubnetPreservingIpMasker(new byte[32]);
        var query = new PolicyQueryService(mock(TransactionBoundary.class), mapper, names, null, ips);
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Drop", "hi", "any"), cp("r3", 3, "Accept", "net", "any")));
        JsonNode body = mapper.valueToTree(query.insights(snapshot, 0, "I3", "", 90).body());
        assertEquals(1, body.path("total").asInt());
        assertEquals(1, body.path("unionCover").path("covered").asInt());
        JsonNode masked = mapper.valueToTree(PolicyPrivacy.mask(mapper.convertValue(query.insights(snapshot, 0, "I3", "", 90).body(), Map.class), "", names, ips));
        var row = masked.path("insights").get(0);
        assertEquals("I3", row.path("insightType").asText());
        assertEquals(PolicyInsights.I3_DIFFERENT, row.path("evidence").get(0).asText());
        assertEquals(PolicyUnionCover.DIFFERENT, row.path("counter").path("coverAction").asText());
        assertEquals(2, row.path("counter").path("coveringRules").asInt());
        assertEquals(3, row.path("rules").size());
        assertFalse(masked.toString().contains("OBJ-RULE-"), "rule names are pseudonymised for aiview");
        var detail = query.insightDetail(snapshot, body.path("insights").get(0).path("insightId").asText(), 90).orElseThrow();
        assertEquals(3, ((List<?>) detail.body().get("rules")).size());
    }
}
