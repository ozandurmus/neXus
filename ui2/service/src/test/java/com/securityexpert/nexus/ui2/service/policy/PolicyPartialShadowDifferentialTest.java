package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.inLayer;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.NOW;
import static com.securityexpert.nexus.ui2.service.policy.PolicyUnionCoverDifferentialTest.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Design section 9, phase P2 "differential tests" for I4: the same random small Check Point rulebases as the I3 test
 * (eight documentation hosts, four TCP ports, anchors, disabled rules, mixed actions), now in one or two layers. A
 * brute-force enumeration over that universe decides, per layer, which readable rules are PARTIAL: some earlier readable
 * rule of the layer shares a point with the rule, while neither one earlier rule nor the union of the earlier readable
 * rules covers it. The engine's PARTIAL set, its overlapping lists and the same-action flag must equal that exactly; I4
 * insights are the same-action ones, and never a rule that I1, I2 or I3 reports. Small budgets give NOT_PROVEN and never
 * a wrong PARTIAL. Synthetic values only.
 */
class PolicyPartialShadowDifferentialTest {
    record Placed(Synthetic rule, int layer) {}
    record Expected(Set<String> overlapping, boolean sameAction) {}

    /** One or two layers: the rules are cut in two at a random point, the second part in LAYER-BRAVO with numbering that keeps increasing. */
    static List<Placed> layout(Random random, boolean coarse, boolean twoLayers) {
        List<Placed> out = new ArrayList<>();
        int n = 3 + random.nextInt(9), cut = twoLayers ? 1 + random.nextInt(n - 1) : n;
        for (int i = 0; i < n; i++) out.add(new Placed(rule(random, i, coarse), i < cut ? 0 : 1));
        return out;
    }
    static PolicySnapshot snapshot(List<Placed> rules) {
        List<Section> sections = new ArrayList<>();
        for (int layer = 0; layer < 2; layer++) {
            final int l = layer;
            var in = rules.stream().filter(p -> p.layer() == l).map(p -> l == 0 ? p.rule().rule() : inLayer(p.rule().rule(), "LAYER-BRAVO")).toList();
            if (!in.isEmpty()) sections.add(new Section("s" + layer, "OBJ-SECTION-0" + layer, "CP access layer", null, in));
        }
        var base = PolicyHygieneTest.sections("CP", sections.toArray(Section[]::new));
        return new PolicySnapshot(base.metadata(), base.sections(), OBJECTS);
    }
    /** The brute-force verdict per readable rule that has a partial overlap; rules covered by one earlier rule or by their union have none. */
    static Map<String, Expected> expected(List<Placed> rules) {
        Map<String, Expected> out = new TreeMap<>();
        for (int k = 0; k < rules.size(); k++) {
            var subject = rules.get(k);
            if (!subject.rule().readable()) continue;
            var points = points(subject.rule().rule());
            Set<List<Integer>> union = new HashSet<>(); Set<String> overlapping = new TreeSet<>(); boolean same = true;
            for (int j = 0; j < k; j++) {
                var prior = rules.get(j);
                if (!prior.rule().readable() || prior.layer() != subject.layer()) continue;
                var priorPoints = points(prior.rule().rule());
                union.addAll(priorPoints);
                if (Collections.disjoint(priorPoints, points)) continue;
                overlapping.add(prior.rule().rule().id());
                same &= prior.rule().rule().action().equals(subject.rule().rule().action());
            }
            if (!overlapping.isEmpty() && !union.containsAll(points)) out.put(subject.rule().rule().id(), new Expected(overlapping, same));
        }
        return out;
    }

    @Test void i4IsExactlyTheBruteForcePartialOverlapOnRandomCheckPointRulebases() {
        var random = new Random(1126L);
        int partial = 0, different = 0, twoLayerTrials = 0;
        for (int trial = 0; trial < 500; trial++) {
            boolean twoLayers = trial % 3 == 0; twoLayerTrials += twoLayers ? 1 : 0;
            var rules = layout(random, trial % 2 == 0, twoLayers);
            var snapshot = snapshot(rules);
            var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250000);
            var union = PolicyUnionCover.analyze(snapshot, hygiene);
            var result = PolicyPartialShadow.analyze(snapshot, hygiene, union);
            var report = PolicyInsights.analyze(snapshot, hygiene, null, Map.of(), 90, union, result);
            var expected = expected(rules);
            Map<String, PolicyPartialShadow.Partial> found = new TreeMap<>(result.rules());
            assertEquals(expected.keySet(), found.keySet(), "trial " + trial);
            Set<String> expectedInsights = new TreeSet<>();
            for (var e : expected.entrySet()) {
                var verdict = found.get(e.getKey());
                assertEquals(PolicyPartialShadow.PARTIAL, verdict.status(), "trial " + trial);
                assertEquals(e.getValue().overlapping(), new TreeSet<>(verdict.overlapping().stream().map(Rule::id).toList()), "trial " + trial + " " + e.getKey());
                assertEquals(e.getValue().sameAction(), verdict.sameAction(), "trial " + trial + " " + e.getKey());
                if (e.getValue().sameAction()) { expectedInsights.add(e.getKey()); partial++; } else different++;
            }
            Set<String> insights = new TreeSet<>();
            for (var i : PolicyInsightsTest.of(report, "I4")) {
                assertEquals("PROVEN", i.grade(), "trial " + trial);
                assertEquals("subject", i.rules().get(0).insightRole());
                insights.add(i.rules().get(0).ruleId());
                assertTrue(i.rules().size() <= 1 + PolicyInsights.MAX_OVERLAPPING);
            }
            assertEquals(expectedInsights, insights, "trial " + trial);
            // A rule reported as I1, I2 or I3 never has an I4, and a different-action overlap never has one.
            for (String type : List.of("I1", "I2", "I3"))
                for (var i : PolicyInsightsTest.of(report, type)) assertFalse(insights.contains(i.rules().get(0).ruleId()), "trial " + trial + " " + type);
            for (var e : expected.entrySet()) if (!e.getValue().sameAction()) assertFalse(insights.contains(e.getKey()), "trial " + trial);
            assertEquals(expectedInsights.size(), result.partial(), "trial " + trial);
            assertEquals(found.size() - expectedInsights.size(), result.differentAction(), "trial " + trial);
            assertEquals(0, result.notProven());
        }
        assertTrue(partial >= 150, "same-action partial overlaps exercised: " + partial);
        assertTrue(different >= 100, "different-action overlaps exercised: " + different);
        assertTrue(twoLayerTrials >= 100);
    }

    @Test void smallBudgetsGiveNotProvenAndNeverAWrongPartial() {
        var random = new Random(1127L);
        int notProven = 0, proven = 0;
        for (int trial = 0; trial < 120; trial++) {
            var rules = layout(random, trial % 2 == 0, trial % 3 == 0);
            var snapshot = snapshot(rules);
            var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250000);
            var union = PolicyUnionCover.analyze(snapshot, hygiene);
            var expected = expected(rules);
            for (int work : new int[]{0, 3, 8, 15, 30, 60, 120, 250, 600}) {
                for (int window : new int[]{work, Math.max(1, work / 4)}) {
                    var result = PolicyPartialShadow.analyze(snapshot, hygiene, union, work, window);
                    for (var e : result.rules().entrySet()) {
                        var verdict = e.getValue();
                        if (verdict.status().equals(PolicyPartialShadow.NOT_PROVEN)) {
                            notProven++;
                            assertEquals(PolicyPartialShadow.BUDGET, verdict.reason());
                            assertTrue(verdict.overlapping().isEmpty());
                            continue;
                        }
                        proven++;
                        var truth = expected.get(e.getKey());
                        assertNotNull(truth, "a PARTIAL that brute force does not find: trial " + trial + " work " + work + " " + e.getKey());
                        assertEquals(truth.overlapping(), new TreeSet<>(verdict.overlapping().stream().map(Rule::id).toList()), "trial " + trial + " work " + work);
                        assertEquals(truth.sameAction(), verdict.sameAction(), "trial " + trial + " work " + work);
                    }
                    assertEquals(result.rules().values().stream().filter(v -> v.status().equals(PolicyPartialShadow.NOT_PROVEN)).count(), result.notProven());
                }
            }
        }
        assertTrue(notProven >= 50, "budget exhaustion exercised: " + notProven);
        assertTrue(proven >= 100, "proven verdicts under a budget exercised: " + proven);
    }
}
