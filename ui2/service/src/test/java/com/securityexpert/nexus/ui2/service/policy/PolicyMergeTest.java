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
 * Insight I5 (merge candidates over adjacent rules), Check Point fixtures: plain one-cell merges on source, destination
 * and service with the union-box property, adjacent chains, equal/covered boxes (alreadyShadowed), two differing cells,
 * different action, different log setting, an UNKNOWN signature part, different install-on, anchors on either side (time,
 * unresolved object, auto-generated, Apply Layer, negated cell), a disabled rule between (skipped) vs an enabled rule
 * between (not adjacent), a section boundary, another vendor, an incomplete snapshot, the budget and invariant-breach
 * containment, the route JSON and the AIView projection. Documentation addresses (192.0.2.0/24, 198.51.100.0/24) and
 * masked names only.
 */
class PolicyMergeTest {
    static PolicySnapshot snapshot(Section... sections) {
        var base = PolicyReorderTest.snapshot(sections);
        var objects = new HashMap<>(base.objects());
        objects.put("svc-http", object("svc-http", "service-tcp", "protocol: tcp", "port: 80"));
        objects.put("svc-https", object("svc-https", "service-tcp", "protocol: tcp", "port: 443"));
        return new PolicySnapshot(base.metadata(), base.sections(), objects, base.failures());
    }

    static PolicyInsights.Report report(PolicySnapshot snapshot) { return PolicyInsightsTest.report(snapshot); }
    static List<PolicyInsights.Insight> i5(PolicySnapshot snapshot) { return PolicyInsightsTest.of(report(snapshot), "I5"); }
    static List<String> ids(PolicyInsights.Insight insight) { return insight.rules().stream().map(PolicyInsights.RuleRef::ruleId).toList(); }
    static List<String> roles(PolicyInsights.Insight insight) { return insight.rules().stream().map(PolicyInsights.RuleRef::insightRole).toList(); }

    static Rule ruleWithCells(String id, int number, String action, String source, String destination, String service, String... installOn) {
        Rule base = cp(id, number, action, source, installOn);
        return new Rule(base.id(), base.uuid(), base.number(), base.name(), base.enabled(),
            cell(source), cell(destination), cell(service), base.application(),
            base.action(), base.log(), "COMMENT-" + id, base.extras(), base.hitCounts());
    }

    static void assertPartsSumToEvaluated(PolicyMerge.Result result) {
        assertEquals(result.evaluated(), result.candidates() + result.alreadyShadowed() + result.differsInSeveralCells()
            + result.differentEffect() + result.differentInstallOn() + result.anchorBlocked() + result.notProven());
    }

    static void assertUnionBoxProperty(PolicySnapshot snapshot, Section section, PolicyMerge.Pair pair) {
        var budget = new PolicyNormalization.Budget(PolicyMerge.TOTAL_WORK);
        var resolver = PolicyNormalization.Resolver.shadowing(snapshot, budget);
        var boxA = PolicyRegion.Box.of(PolicyNormalization.normalize(snapshot, section, pair.surviving(), resolver).selectors());
        var boxB = PolicyRegion.Box.of(PolicyNormalization.normalize(snapshot, section, pair.merged(), resolver).selectors());
        Rule merged = PolicyMerge.mergedRule("CP", pair.surviving(), pair.merged(), pair.differingDim());
        assertEquals(pair.surviving().id(), merged.id(), "the first rule keeps its identity");
        assertEquals(pair.surviving().uuid(), merged.uuid());
        assertEquals(pair.surviving().name(), merged.name(), "names are not concatenated");
        assertEquals(pair.surviving().comment(), merged.comment(), "comments are not concatenated");
        var mergedBox = PolicyRegion.Box.of(PolicyNormalization.normalize(snapshot, section, merged, resolver).selectors());
        assertNotNull(boxA); assertNotNull(boxB); assertNotNull(mergedBox);
        assertEquals(List.of(), PolicyRegion.minus(boxA, mergedBox, budget));
        assertEquals(List.of(), PolicyRegion.minus(boxB, mergedBox, budget));
        assertTrue(PolicyRegion.residual(mergedBox, List.of(boxA, boxB), budget).covered(), "merged box equals box(a) U box(b)");
    }

    @Test void plainOneCellMergeInSourceDestinationAndServiceEachHoldsTheUnionProperty() {
        var byCell = Map.of(
            "source", cpSection("s1", null,
                ruleWithCells("r1", 1, "Accept", "lo", "other", "svc-http", "any"),
                ruleWithCells("r2", 2, "Accept", "hi", "other", "svc-http", "any")),
            "destination", cpSection("s1", null,
                ruleWithCells("r1", 1, "Accept", "lo", "lo", "svc-http", "any"),
                ruleWithCells("r2", 2, "Accept", "lo", "hi", "svc-http", "any")),
            "service", cpSection("s1", null,
                ruleWithCells("r1", 1, "Accept", "lo", "other", "svc-http", "any"),
                ruleWithCells("r2", 2, "Accept", "lo", "other", "svc-https", "any")));
        for (var entry : byCell.entrySet()) {
            String cellName = entry.getKey();
            Section section = entry.getValue();
            var snapshot = snapshot(section);
            var merge = PolicyMerge.analyze(snapshot);
            assertPartsSumToEvaluated(merge);
            assertEquals(1, merge.evaluated(), cellName);
            assertEquals(1, merge.candidates(), cellName);
            var pair = merge.pairs().get("r1");
            assertEquals(PolicyMerge.MERGE_CANDIDATE, pair.result(), cellName);
            assertEquals(cellName, pair.differingCell(), cellName);
            assertUnionBoxProperty(snapshot, section, pair);

            var report = report(snapshot);
            var found = PolicyInsightsTest.of(report, "I5");
            assertEquals(1, found.size(), cellName);
            var insight = found.get(0);
            assertEquals("PROVEN", insight.grade());
            assertEquals("package", insight.scope());
            assertEquals(List.of("r1", "r2"), ids(insight));
            assertEquals(List.of("subject", "merged"), roles(insight));
            assertEquals(List.of(PolicyInsights.I5_MERGE), insight.evidence());
            assertTrue(PolicyInsights.I5_MERGE.endsWith(
                ": no behaviour change for the verdict and the log setting of stored management intent (profile settings are not collected for Check Point)."));
            assertTrue(PolicyInsights.I7_SAFE.endsWith(
                ": no behaviour change for the verdict and the log setting of stored management intent (profile settings are not collected for Check Point)."));
            assertEquals(List.of(PolicyInsights.INSTALL_STATE_NOT_VERIFIED), insight.caveats());
            assertEquals("PROVEN", insight.raiseGrade());
            assertEquals(PolicyInsights.NONE, insight.needs());
            assertEquals(Map.of("mergeResult", PolicyMerge.MERGE_CANDIDATE, "differingCell", cellName), insight.counter());
            assertEquals(1, report.byType().get("I5"));
            assertEquals(1, report.merge().candidates());
            assertFalse(insight.toString().contains("192.0.2") || insight.toString().contains("198.51.100"));
        }
    }

    @Test void chainsOfThreeMergeableRulesEmitBothAdjacentPairsWithoutGrouping() {
        var section = cpSection("s1", null,
            ruleWithCells("r1", 1, "Accept", "lo", "other", "svc-http", "any"),
            ruleWithCells("r2", 2, "Accept", "hi-a", "other", "svc-http", "any"),
            ruleWithCells("r3", 3, "Accept", "hi", "other", "svc-http", "any"));
        var snapshot = snapshot(section);
        var found = i5(snapshot);
        assertEquals(2, found.size());
        assertEquals(List.of("r1", "r2"), ids(found.get(0)));
        assertEquals(List.of("r2", "r3"), ids(found.get(1)));
        assertNotEquals(found.get(0).id(), found.get(1).id());
    }

    @Test void equalBoxesAreCountedAsAlreadyShadowedAndStayInI1() {
        var snapshot = snapshot(cpSection("s1", null,
            ruleWithCells("r1", 1, "Accept", "lo", "other", "svc-http", "any"),
            ruleWithCells("r2", 2, "Accept", "lo", "other", "svc-http", "any")));
        var merge = PolicyMerge.analyze(snapshot);
        assertPartsSumToEvaluated(merge);
        assertEquals(1, merge.evaluated());
        assertEquals(1, merge.alreadyShadowed());
        assertEquals(0, merge.candidates());
        var report = report(snapshot);
        assertTrue(PolicyInsightsTest.of(report, "I5").isEmpty());
        assertEquals(1, PolicyInsightsTest.of(report, "I1").size());

        // When the first rule already covers the second in the differing cell, the second rule is already I1 too.
        var covered = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "net", "any"), cp("r2", 2, "Accept", "lo", "any")));
        var coveredMerge = PolicyMerge.analyze(covered);
        assertEquals(1, coveredMerge.alreadyShadowed());
        assertTrue(i5(covered).isEmpty());
    }

    @Test void twoOrMoreDifferingCellsAreCountedAndShowNoInsight() {
        var snapshot = snapshot(cpSection("s1", null,
            ruleWithCells("r1", 1, "Accept", "lo", "lo", "svc-http", "any"),
            ruleWithCells("r2", 2, "Accept", "hi", "hi", "svc-http", "any")));
        var merge = PolicyMerge.analyze(snapshot);
        assertPartsSumToEvaluated(merge);
        assertEquals(1, merge.evaluated());
        assertEquals(1, merge.differsInSeveralCells());
        assertEquals(0, merge.candidates());
        assertTrue(i5(snapshot).isEmpty());
    }

    @Test void differentActionLogProfileOrAnUnknownSignaturePartProducesNoCandidate() {
        var differentAction = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Drop", "hi", "any")));
        assertEquals(1, PolicyMerge.analyze(differentAction).differentEffect());
        assertTrue(i5(differentAction).isEmpty());

        var differentLog = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"),
            PolicyReorderTest.withLog(cp("r2", 2, "Accept", "hi", "any"), "None")));
        assertEquals(1, PolicyMerge.analyze(differentLog).differentEffect());
        assertTrue(i5(differentLog).isEmpty());

        var differentTrackExtra = snapshot(cpSection("s1", null,
            edit(cp("r1", 1, "Accept", "lo", "any"), cell("lo"), Map.of("track-accounting", List.of("true"))),
            cp("r2", 2, "Accept", "hi", "any")));
        assertEquals(1, PolicyMerge.analyze(differentTrackExtra).differentEffect());
        assertTrue(i5(differentTrackExtra).isEmpty());

        var differentProfile = snapshot(cpSection("s1", null,
            PolicyReorderTest.profiled(cp("r1", 1, "Accept", "lo", "any"), "PROF-ALPHA"),
            cp("r2", 2, "Accept", "hi", "any")));
        assertEquals(1, PolicyMerge.analyze(differentProfile).differentEffect());
        assertTrue(i5(differentProfile).isEmpty());

        var bothUnknownLog = snapshot(cpSection("s1", null,
            PolicyReorderTest.withLog(cp("r1", 1, "Accept", "lo", "any"), "UNKNOWN"),
            PolicyReorderTest.withLog(cp("r2", 2, "Accept", "hi", "any"), "UNKNOWN")));
        assertEquals(1, PolicyMerge.analyze(bothUnknownLog).differentEffect(), "UNKNOWN equals nothing, another UNKNOWN included");
        assertTrue(i5(bothUnknownLog).isEmpty());
    }

    @Test void differentInstallOnProducesNoCandidate() {
        for (var second : List.of(cp("r2", 2, "Accept", "hi", "fw-juliet-06"), cp("r2", 2, "Accept", "hi", "targets"), cp("r2", 2, "Accept", "hi", "any"))) {
            var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "fw-tango-04"), second));
            var merge = PolicyMerge.analyze(snapshot);
            assertPartsSumToEvaluated(merge);
            assertEquals(1, merge.differentInstallOn());
            assertTrue(i5(snapshot).isEmpty());
        }
    }

    @Test void anAnchorOnEitherSideBlocksThePair() {
        Rule negated = new Rule("x", "uuid-x", 1, "OBJ-RULE-X", true, new Cell(List.of("lo"), true), cell("any"), cell("any"),
            new Cell(List.of(), false), "Accept", "Log", "", Map.of("install-on", List.of("any"), "time", List.of("any"), "layer-name", List.of("LAYER-ALPHA")));
        List<Rule> anchors = List.of(
            PolicyUnionCoverTest.timed(cp("x", 1, "Accept", "lo", "any")),
            cp("x", 1, "Accept", "missing", "any"),
            edit(cp("x", 1, "Accept", "lo", "any"), cell("lo"), Map.of("source-user", List.of("OBJ-USER-01"))),
            edit(cp("x", 1, "Accept", "lo", "any"), cell("lo"), Map.of("auto-generated", List.of("true"))),
            applyLayer("x", 1, "lo", "inline-a"),
            negated);
        for (Rule anchor : anchors) {
            Rule firstAnchor = new Rule("r1", anchor.uuid(), 1, anchor.name(), anchor.enabled(), anchor.source(), anchor.destination(),
                anchor.service(), anchor.application(), anchor.action(), anchor.log(), anchor.comment(), anchor.extras());
            var left = snapshot(cpSection("s1", null, firstAnchor, cp("r2", 2, "Accept", "hi", "any")));
            assertEquals(1, PolicyMerge.analyze(left).anchorBlocked(), anchor.toString());
            assertTrue(i5(left).isEmpty());

            Rule secondAnchor = new Rule("r2", anchor.uuid(), 2, anchor.name(), anchor.enabled(), anchor.source(), anchor.destination(),
                anchor.service(), anchor.application(), anchor.action(), anchor.log(), anchor.comment(), anchor.extras());
            var right = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), secondAnchor));
            assertEquals(1, PolicyMerge.analyze(right).anchorBlocked(), anchor.toString());
            assertTrue(i5(right).isEmpty());
        }
    }

    @Test void aDisabledRuleBetweenIsSkippedWhileAnEnabledRuleBetweenBreaksAdjacency() {
        Rule off = cp("off", 2, "Drop", "any", "any");
        off = new Rule(off.id(), off.uuid(), off.number(), off.name(), false, off.source(), off.destination(), off.service(),
            off.application(), off.action(), off.log(), off.comment(), off.extras());
        var withDisabled = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), off, cp("r3", 3, "Accept", "hi", "any")));
        var found = i5(withDisabled);
        assertEquals(1, found.size());
        assertEquals(List.of("r1", "r3"), ids(found.get(0)));
        assertEquals(1, PolicyMerge.analyze(withDisabled).evaluated());

        // Even a disjoint enabled rule in between means r1 and r3 are not adjacent in the stored order.
        var separated = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"),
            cp("r2", 2, "Drop", "other", "any"), cp("r3", 3, "Accept", "hi", "any")));
        assertTrue(i5(separated).isEmpty());
        assertEquals(2, PolicyMerge.analyze(separated).evaluated());
        assertEquals(2, PolicyMerge.analyze(separated).differentEffect());
    }

    @Test void aSectionBoundaryAndAnotherLayerAreNeverCrossed() {
        var sections = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any")),
            cpSection("s2", null, cp("r2", 2, "Accept", "hi", "any")));
        assertTrue(i5(sections).isEmpty());
        assertEquals(0, PolicyMerge.analyze(sections).evaluated(), "each section has one rule, so no intra-section adjacent pair exists");

        var layers = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any")),
            cpSection("s2", null, inLayer(cp("b1", 1, "Accept", "hi", "any"), "LAYER-BRAVO")));
        assertTrue(i5(layers).isEmpty());
        assertEquals(0, PolicyMerge.analyze(layers).evaluated());
    }

    @Test void anotherVendorAndAnIncompleteSnapshotEvaluateNothing() {
        var complete = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi", "any")));
        var incomplete = new PolicySnapshot(complete.metadata(), complete.sections(), complete.objects(),
            List.of(new CollectionFailure("layer", "COLLECTION_PENDING")));
        assertTrue(i5(incomplete).isEmpty());
        var incompleteMerge = PolicyMerge.analyze(incomplete);
        assertEquals(0, incompleteMerge.evaluated());
        assertEquals(Map.of(PolicyUnionCover.INCOMPLETE, 2), incompleteMerge.notEvaluable());
        assertEquals(List.of(Map.of("mergeReason", PolicyUnionCover.INCOMPLETE, "rules", 2)), report(incomplete).merge().notEvaluable());

        var pan = PolicyHygieneTest.snapshot(List.of(
            PolicyHygieneTest.rule("r1", "net", "any", "allow", true),
            PolicyHygieneTest.rule("r2", "any", "any", "allow", true)), PolicyHygieneTest.objects());
        var panMerge = PolicyMerge.analyze(pan);
        assertEquals(0, panMerge.evaluated());
        assertTrue(panMerge.pairs().isEmpty());
        assertEquals(Set.of(PolicyUnionCover.UNSUPPORTED_VENDOR), panMerge.notEvaluable().keySet());
        assertTrue(PolicyInsightsTest.of(PolicyInsightsTest.report(pan), "I5").isEmpty());
    }

    @Test void anExhaustedBudgetIsNotEvaluableNeverACandidate() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi", "any")));
        var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250_000);
        var union = PolicyUnionCover.analyze(snapshot, hygiene);
        var partial = PolicyPartialShadow.analyze(snapshot, hygiene, union);
        boolean sawNotProven = false, sawProven = false;
        for (int work = 0; work < 400; work++) {
            var merge = PolicyMerge.analyze(snapshot, work, work);
            assertPartsSumToEvaluated(merge);
            var report = PolicyInsights.analyze(snapshot, hygiene, null, Map.of(), 90, union, partial, merge);
            for (var insight : PolicyInsightsTest.of(report, "I5")) {
                if (insight.grade().equals("PROVEN")) {
                    sawProven = true;
                    assertEquals(List.of("r1", "r2"), ids(insight), "work " + work);
                    assertEquals(Map.of("mergeResult", PolicyMerge.MERGE_CANDIDATE, "differingCell", "source"), insight.counter());
                } else {
                    sawNotProven = true;
                    assertEquals("NOT_EVALUABLE", insight.grade());
                    assertEquals(List.of(PolicyInsights.I5_BUDGET), insight.evidence());
                    assertEquals(Map.of("mergeReason", PolicyMerge.BUDGET), insight.counter());
                    assertTrue(insight.caveats().isEmpty());
                    assertEquals(List.of("subject", "merged"), roles(insight));
                }
            }
            assertEquals(merge.notProven(), report.merge().notProven());
        }
        assertTrue(sawNotProven);
        assertTrue(sawProven);

        // An internal-invariant breach on one pair turns that one pair into NOT_EVALUABLE and keeps every other pair intact.
        var twoPairs = snapshot(
            cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi", "any")),
            cpSection("s2", null, inLayer(cp("b1", 1, "Accept", "lo", "any"), "LAYER-BRAVO"), inLayer(cp("b2", 2, "Accept", "hi", "any"), "LAYER-BRAVO")));
        var hygiene2 = PolicyHygiene.analyze(twoPairs, 90, NOW, 250_000);
        var union2 = PolicyUnionCover.analyze(twoPairs, hygiene2);
        var partial2 = PolicyPartialShadow.analyze(twoPairs, hygiene2, union2);
        var contained = PolicyMerge.analyze(twoPairs, PolicyMerge.TOTAL_WORK, PolicyMerge.RULE_WORK, (a, b) -> {
            if (a.id().equals("r1")) throw new IllegalStateException("synthetic invariant breach");
        });
        assertPartsSumToEvaluated(contained);
        assertEquals(1, contained.notProven());
        assertEquals(1, contained.candidates());
        var containedInsights = PolicyInsightsTest.of(PolicyInsights.analyze(twoPairs, hygiene2, null, Map.of(), 90, union2, partial2, contained), "I5");
        assertEquals(2, containedInsights.size());
        assertEquals("PROVEN", containedInsights.get(0).grade());
        assertEquals(List.of("b1", "b2"), ids(containedInsights.get(0)));
        assertEquals("NOT_EVALUABLE", containedInsights.get(1).grade());
        assertEquals(List.of("r1", "r2"), ids(containedInsights.get(1)));
    }

    @Test void theInsightsRouteCarriesI5AndTheAiviewProjectionKeepsItsClosedVocabularies() {
        var mapper = new ObjectMapper();
        var names = new TopologyNamePseudonymizer(new byte[32]);
        var ips = new SubnetPreservingIpMasker(new byte[32]);
        var query = new PolicyQueryService(mock(TransactionBoundary.class), mapper, names, null, ips);
        var snapshot = snapshot(
            cpSection("s1", null,
                ruleWithCells("r1", 1, "Accept", "lo", "other", "svc-http", "any"),
                ruleWithCells("r2", 2, "Accept", "hi", "other", "svc-http", "any")),
            cpSection("s2", null,
                inLayer(cp("b1", 1, "Accept", "lo", "any"), "LAYER-BRAVO"),
                inLayer(PolicyUnionCoverTest.timed(cp("b2", 2, "Accept", "hi", "any")), "LAYER-BRAVO")));
        JsonNode body = mapper.valueToTree(query.insights(snapshot, 0, "I5", "", 90).body());
        assertEquals(1, body.path("total").asInt());
        assertEquals(1, body.path("byType").path("I5").asInt());
        var block = body.path("merge");
        assertEquals(2, block.path("evaluated").asInt());
        assertEquals(1, block.path("candidates").asInt());
        assertEquals(1, block.path("anchorBlocked").asInt());
        assertEquals(block.path("evaluated").asInt(),
            block.path("candidates").asInt() + block.path("alreadyShadowed").asInt() + block.path("differsInSeveralCells").asInt()
                + block.path("differentEffect").asInt() + block.path("differentInstallOn").asInt()
                + block.path("anchorBlocked").asInt() + block.path("notProven").asInt());
        assertEquals(body, mapper.valueToTree(query.insights(snapshot, 0, "I5", "", 90).body()), "cached per snapshot instance");

        JsonNode masked = mapper.valueToTree(PolicyPrivacy.mask(mapper.convertValue(query.insights(snapshot, 0, "I5", "", 90).body(), Map.class), "", names, ips));
        var row = masked.path("insights").get(0);
        assertEquals("I5", row.path("insightType").asText());
        assertEquals(PolicyInsights.I5_MERGE, row.path("evidence").get(0).asText());
        assertEquals(List.of("subject", "merged"), List.of(
            row.path("rules").get(0).path("insightRole").asText(),
            row.path("rules").get(1).path("insightRole").asText()));
        assertEquals(PolicyMerge.MERGE_CANDIDATE, row.path("counter").path("mergeResult").asText());
        assertEquals("source", row.path("counter").path("differingCell").asText());
        assertEquals(1, masked.path("merge").path("candidates").asInt());
        assertFalse(masked.toString().contains("OBJ-RULE-"), "rule names are pseudonymised for aiview");
        assertFalse(masked.toString().contains("192.0.2") || masked.toString().contains("198.51.100"));

        String insightId = body.path("insights").get(0).path("insightId").asText();
        var detail = query.insightDetail(snapshot, insightId, 90).orElseThrow();
        JsonNode detailJson = mapper.valueToTree(detail.body());
        assertEquals(2, detailJson.path("rules").size());
        assertEquals("COMMENT-r1", detailJson.path("rules").get(0).path("comment").asText());
        assertEquals("COMMENT-r2", detailJson.path("rules").get(1).path("comment").asText());
        assertTrue(detailJson.path("ruleObjects").size() >= 4);
        JsonNode maskedDetail = mapper.valueToTree(PolicyPrivacy.mask(mapper.convertValue(detail.body(), Map.class), "", names, ips));
        assertFalse(maskedDetail.toString().contains("OBJ-RULE-"));
        assertFalse(maskedDetail.toString().contains("COMMENT-"));

        assertEquals("merged", PolicyPrivacy.mask("merged", "insightRole", names, ips));
        assertEquals(PolicyMerge.MERGE_CANDIDATE, PolicyPrivacy.mask(PolicyMerge.MERGE_CANDIDATE, "mergeResult", names, ips));
        assertEquals("UNKNOWN", PolicyPrivacy.mask(PolicyMerge.ALREADY_SHADOWED, "mergeResult", names, ips));
        for (String c : PolicyMerge.CELLS) assertEquals(c, PolicyPrivacy.mask(c, "differingCell", names, ips));
        assertEquals("UNKNOWN", PolicyPrivacy.mask("192.0.2.0/25", "differingCell", names, ips));
        assertEquals("analysis budget", PolicyPrivacy.mask("analysis budget", "mergeReason", names, ips));
        assertEquals("UNKNOWN", PolicyPrivacy.mask("OBJ-RULE-1", "mergeReason", names, ips));
        for (String e : List.of(PolicyInsights.I5_MERGE, PolicyInsights.I5_BUDGET)) assertEquals(e, PolicyPrivacy.mask(e, "evidence", names, ips));
    }
}
