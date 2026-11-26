package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.cp;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.inLayer;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.cpSection;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * The scope-local proposal ({@link PolicyProposal#propose}: after an accepted move only the evaluation scope the move
 * touched is analysed again) against the reference it must equal ({@link PolicyProposal#proposeReference}: the three
 * whole-snapshot I3/I4 passes and the whole-snapshot I7 pass after every accepted move). Equal means the whole
 * {@link PolicyProposal.Result}: every step, the verdict, the label, the refusal counts and the final snapshot, for every
 * input, in rule order and in a random order and subset, and under every work limit the budget sweeps use (the shared pool of
 * Q8 is charged the same by a reused scope, so the stop point of every limit is the reference's). The generators are
 * {@link PolicyProposalDifferentialTest}'s (its 1,280 rulebases of one or two layers) and a multi-layer, multi-section one
 * of this class. Synthetic values only.
 */
class PolicyProposalScopeLocalDifferentialTest {
    static void same(String label, PolicySnapshot stored, List<String> subjects, int work, int window) {
        var expected = PolicyProposal.proposeReference(stored, subjects, work, window);
        var stats = new PolicyProposal.Stats();
        var actual = PolicyProposal.propose(stored, subjects, work, window, true, stats);
        assertEquals(expected, actual, label + " work " + work + " window " + window);
    }

    // ---- a multi-layer, multi-section generator on the existing rule generator ----
    static List<PolicyProposalDifferentialTest.Placed> multiLayout(Random random, boolean coarse) {
        int layers = 2 + random.nextInt(2);
        List<PolicyProposalDifferentialTest.Placed> out = new ArrayList<>();
        int i = 0;
        for (int layer = 0; layer < layers; layer++) {
            int count = 2 + random.nextInt(8), cut = random.nextBoolean() ? 1 + random.nextInt(Math.max(1, count - 1)) : count;
            for (int k = 0; k < count; k++, i++) {
                int section = layer * 2 + (k < cut ? 0 : 1);
                var p = PolicyProposalDifferentialTest.placed(random, i, coarse, layer == 0 ? 0 : 1, section);
                if (layer == 2) p = new PolicyProposalDifferentialTest.Placed(inLayer(p.rule(), "LAYER-CHARLIE"), p.kind(), 2, section);
                out.add(p);
            }
        }
        return out;
    }
    static PolicySnapshot multiSnapshot(List<PolicyProposalDifferentialTest.Placed> rules) {
        List<Section> sections = new ArrayList<>();
        for (int s = 0; s < 6; s++) {
            final int section = s;
            var in = rules.stream().filter(p -> p.section() == section).map(PolicyProposalDifferentialTest.Placed::rule).toList();
            if (!in.isEmpty()) sections.add(new Section("m" + s, "OBJ-SECTION-0" + s, "CP access layer", null, in));
        }
        var base = PolicyHygieneTest.sections("CP", sections.toArray(Section[]::new));
        return new PolicySnapshot(base.metadata(), base.sections(), PolicyProposalDifferentialTest.OBJECTS);
    }

    int proposals, reusedRuns, wholeFallbacks, notApplicable;

    void both(String label, PolicySnapshot stored, Random random) {
        var expectedAll = PolicyProposal.proposeReference(stored, null, PolicyProposal.TOTAL_WORK, PolicyProposal.RULE_WORK);
        var stats = new PolicyProposal.Stats();
        var all = PolicyProposal.propose(stored, null, PolicyProposal.TOTAL_WORK, PolicyProposal.RULE_WORK, true, stats);
        assertEquals(expectedAll, all, label + " allOfferedMoves");
        proposals++;
        if (stats.scopes == 0) notApplicable++;
        else {
            int ran = 0; for (int n : stats.candidateRuns) ran += n;
            // Reference: one pass per accepted move over every scope; reused: fewer scope runs than that.
            reusedRuns += (all.accepted() + 1) * stats.scopes - ran;
        }
        wholeFallbacks += stats.fallbacks;
        List<String> subjects = new ArrayList<>(all.steps().stream().map(PolicyProposal.Step::subject).toList());
        stored.sections().stream().flatMap(s -> s.rules().stream()).filter(r -> random.nextInt(5) == 0).forEach(r -> subjects.add(r.id()));
        Collections.shuffle(subjects, random);
        var picked = subjects.stream().filter(s -> random.nextInt(4) != 0).toList();
        same(label + " shuffled " + picked, stored, picked, PolicyProposal.TOTAL_WORK, PolicyProposal.RULE_WORK);
        proposals++;
    }

    @Test void theExisting1280RulebasesGiveTheReferenceResultInRuleOrderAndInRandomOrder() {
        List<Long> seeds = new ArrayList<>(List.of(1126L, 1127L, 1128L, 2026L, 7L));
        var more = new Random(0x2bL);
        for (int i = 0; i < 3; i++) seeds.add(more.nextLong());
        int trials = 0;
        for (long seed : seeds) {
            var random = new Random(seed);
            for (int trial = 0; trial < 160; trial++, trials++) {
                var rules = PolicyProposalDifferentialTest.layout(random, trial % 2 == 0, trial % 3 == 0, trial % 4 == 1);
                both("seed " + seed + " trial " + trial, PolicyProposalDifferentialTest.snapshot(rules), random);
            }
        }
        System.out.println("Scope-local differential (existing generator): " + trials + " rulebases, " + proposals + " proposals, scope runs saved " + reusedRuns
            + ", whole-pass fallbacks " + wholeFallbacks + ", scope-local not applicable " + notApplicable);
        assertTrue(trials >= 1280, "rulebases: " + trials);
        assertTrue(reusedRuns >= 50, "reuse exercised: " + reusedRuns);
    }

    @Test void multiLayerMultiSectionRulebasesGiveTheReferenceResultWithMovesInOneLayerAndCandidatesInAnother() {
        var random = new Random(0x5c0eL);
        int trials = 0;
        for (int trial = 0; trial < 900; trial++, trials++) {
            var rules = multiLayout(random, trial % 2 == 0);
            both("multi trial " + trial, multiSnapshot(rules), random);
        }
        System.out.println("Scope-local differential (multi-layer generator): " + trials + " rulebases, " + proposals + " proposals, scope runs saved " + reusedRuns
            + ", whole-pass fallbacks " + wholeFallbacks + ", scope-local not applicable " + notApplicable);
        assertTrue(reusedRuns >= 150, "reuse exercised: " + reusedRuns);
        assertTrue(notApplicable < trials / 2, "the scope-local path applies to most multi-layer rulebases: " + notApplicable);
    }

    // ---- the budget sweeps ----
    static PolicySnapshot twoLayers() {
        var alpha = List.of(cp("bX", 1, "Drop", "hi-b", "targets"), cp("bb", 2, "Accept", "lo", "fw-tango-04"), cp("x", 3, "Drop", "hi", "targets"), cp("sB", 4, "Accept", "net", "fw-tango-04"));
        var bravo = List.of(inLayer(cp("bX2", 5, "Drop", "hi-b", "targets"), "LAYER-BRAVO"), inLayer(cp("bb2", 6, "Accept", "lo", "fw-tango-04"), "LAYER-BRAVO"),
            inLayer(cp("x2", 7, "Drop", "hi", "targets"), "LAYER-BRAVO"), inLayer(cp("sB2", 8, "Accept", "net", "fw-tango-04"), "LAYER-BRAVO"));
        return PolicyProposalTest.snapshot(cpSection("a1", null, alpha.toArray(Rule[]::new)), cpSection("b1", null, bravo.toArray(Rule[]::new)));
    }

    @Test void everyWorkLimitOfTheFixedFixturesStopsWhereTheReferenceStops() {
        int compared = 0;
        for (var stored : List.of(PolicyProposalTest.stackedRelease(), PolicyProposalTest.stackedRefusal(), twoLayers())) {
            for (int work = 0; work < 1200; work++) {
                same("fixture", stored, null, work, work);
                same("fixture ordered", stored, List.of("sB", "x", "sB2", "x2", "sA", "sB"), work, PolicyProposal.RULE_WORK);
                compared += 2;
            }
            same("fixture window zero", stored, null, PolicyProposal.TOTAL_WORK, 0);
            for (int window = 0; window < 60; window++) same("fixture window", stored, null, PolicyProposal.TOTAL_WORK, window);
        }
        assertTrue(compared >= 7000, "compared " + compared);
    }

    @Test void theRandomBudgetSweepStopsWhereTheReferenceStops() {
        var random = new Random(1129L);
        int compared = 0;
        for (int trial = 0; trial < 120; trial++) {
            var stored = trial % 2 == 0 ? PolicyProposalDifferentialTest.snapshot(PolicyProposalDifferentialTest.layout(random, trial % 3 == 0, trial % 3 == 0, trial % 4 == 1))
                : multiSnapshot(multiLayout(random, trial % 3 == 0));
            for (int work : new int[]{0, 3, 8, 15, 30, 60, 120, 250, 600, 1500, 4000, 20_000}) {
                for (int window : new int[]{work, Math.max(1, work / 4)}) { same("random trial " + trial, stored, null, work, window); compared++; }
            }
        }
        assertTrue(compared >= 2000, "compared " + compared);
    }

    // ---- where the scope-local path gives way ----
    @Test void anUnprovenSectionOrderIsWholeSnapshotPassesWithTheSameResult() {
        // The second section of the layer does not continue its numbers: its place in the order is not proven.
        var broken = PolicyProposalTest.snapshot(cpSection("a1", null, cp("bA", 1, "Accept", "lo-a", "fw-tango-04"), cp("sA", 2, "Accept", "lo", "fw-tango-04")),
            cpSection("a2", null, cp("sD", 1, "Accept", "net", "fw-tango-04")));
        var stats = new PolicyProposal.Stats();
        var fast = PolicyProposal.propose(broken, null, PolicyProposal.TOTAL_WORK, PolicyProposal.RULE_WORK, true, stats);
        assertEquals(0, stats.scopes, "an unproven section order is no scope");
        assertTrue(stats.wholeCandidatePasses >= 1);
        assertEquals(PolicyProposal.proposeReference(broken, null, PolicyProposal.TOTAL_WORK, PolicyProposal.RULE_WORK), fast);
    }
}
