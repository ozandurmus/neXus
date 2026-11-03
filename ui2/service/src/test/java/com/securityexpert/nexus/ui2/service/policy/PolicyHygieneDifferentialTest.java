package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;

/**
 * The indexed, per-rule-budgeted shadowing must answer exactly what the former linear scan answered wherever the former
 * scan did not run out of budget, over seeded random snapshots (zones, install-on, negation, groups, ranges, disabled
 * and unknown-enabled rules, both vendors, scope breaks and collection failures). Under deliberately tight budgets it
 * may only degrade towards UNKNOWN, never towards a cleaner verdict.
 */
class PolicyHygieneDifferentialTest {
    static final Instant NOW = PolicyHygieneTest.NOW;
    static final int SNAPSHOTS = 4000, LARGE = 120;

    @Test void productionBudgetsMatchTheLegacyScanOnEveryRule() {
        Map<String, Integer> seen = new TreeMap<>(); int rules = 0;
        for (long seed = 0; seed < SNAPSHOTS + LARGE; seed++) {
            var snapshot = SyntheticPolicies.random(seed, seed < SNAPSHOTS ? 1 + (int) (seed % 60) : 150 + (int) (seed % 150));
            var reference = LegacyPolicyHygiene.analyze(snapshot, 90, NOW, Integer.MAX_VALUE);
            var legacyDefault = LegacyPolicyHygiene.analyze(snapshot, 90, NOW, 250_000);
            var actual = PolicyHygiene.analyze(snapshot, 90, NOW, PolicyHygiene.TOTAL_WORK);
            assertFalse(reference.budgetReached(), "seed " + seed);
            assertFalse(actual.budgetReached(), "seed " + seed);
            assertEquals(reference.rules().keySet(), actual.rules().keySet());
            for (var entry : reference.rules().entrySet()) {
                var expected = entry.getValue(); var got = actual.rules().get(entry.getKey());
                assertEquals(expected, got, "seed " + seed + " rule " + entry.getKey());
                var legacy = legacyDefault.rules().get(entry.getKey());
                if (!"analysis budget".equals(legacy.shadowReason())) assertEquals(legacy, got, "seed " + seed + " rule " + entry.getKey());
                seen.merge(snapshot.metadata().vendor() + " " + expected.shadowStatus() + (expected.shadowReason() == null ? "" : " / " + expected.shadowReason()), 1, Integer::sum);
                rules++;
            }
        }
        // The generator must actually reach every verdict the index can change, for both vendors.
        for (String vendor : List.of("CP", "PAN")) for (String verdict : List.of("NOT_SHADOWED", "REDUNDANT", "CONFLICT",
                "UNKNOWN / earlier rule has unsupported constraints", "UNKNOWN / earlier sections not analysed", "UNKNOWN / negated selector",
                "UNKNOWN / snapshot collection incomplete", "UNKNOWN / enabled state is unknown"))
            assertTrue(seen.getOrDefault(vendor + " " + verdict, 0) >= 20, vendor + " " + verdict + " in " + seen);
        assertTrue(seen.getOrDefault("PAN UNKNOWN / zone or install-on coverage is unproven", 0) >= 20, seen.toString());
        assertTrue(seen.getOrDefault("CP UNKNOWN / zone or install-on coverage is unproven", 0) >= 20, seen.toString());
        assertTrue(seen.getOrDefault("CP UNKNOWN / install-on evidence not collected", 0) > 0, seen.toString());
        assertTrue(seen.getOrDefault("PAN UNKNOWN / zone evidence not collected", 0) > 0, seen.toString());
        assertTrue(rules > 140_000, "rules compared: " + rules);
        System.out.println("DIFFERENTIAL " + (SNAPSHOTS + LARGE) + " snapshots, " + rules + " rules: " + seen);
    }

    @Test void legacyCascadeAndNewIndexAgreeWhereverTheLegacyBudgetHeld() {
        var random = new Random(11);
        int compared = 0, legacyBudget = 0;
        for (long seed = 0; seed < 1500; seed++) {
            var snapshot = SyntheticPolicies.random(seed + 50_000, 20 + (int) (seed % 120));
            int limit = random.nextInt(4000);
            var legacy = LegacyPolicyHygiene.analyze(snapshot, 90, NOW, limit);
            var actual = PolicyHygiene.analyze(snapshot, 90, NOW, PolicyHygiene.TOTAL_WORK);
            for (var entry : legacy.rules().entrySet()) {
                if ("analysis budget".equals(entry.getValue().shadowReason())) { legacyBudget++; continue; }
                assertEquals(entry.getValue(), actual.rules().get(entry.getKey()), "seed " + seed + " limit " + limit + " rule " + entry.getKey());
                compared++;
            }
        }
        assertTrue(compared > 50_000 && legacyBudget > 10_000, compared + " compared, " + legacyBudget + " legacy budget");
    }

    @Test void tightBudgetsOnlyDegradeTowardsUnknown() {
        var random = new Random(23);
        int budgetRules = 0, laterDefinite = 0;
        for (long seed = 0; seed < 2500; seed++) {
            var snapshot = SyntheticPolicies.random(seed + 90_000, 10 + (int) (seed % 90));
            var reference = LegacyPolicyHygiene.analyze(snapshot, 90, NOW, Integer.MAX_VALUE).rules();
            int total = random.nextInt(4) == 0 ? random.nextInt(3000) : Integer.MAX_VALUE, rule = 1 + random.nextInt(120);
            var actual = PolicyHygiene.analyze(snapshot, 90, NOW, total, rule, snapshot.objects());
            boolean afterBudget = false;
            for (var section : snapshot.sections()) for (var r : section.rules()) {
                var want = reference.get(r.id()); var got = actual.rules().get(r.id());
                String context = "seed " + seed + " limits " + total + "/" + rule + " rule " + r.id();
                if ("analysis budget".equals(got.shadowReason())) { assertEquals("UNKNOWN", got.shadowStatus()); assertNull(got.coveringRuleId()); budgetRules++; afterBudget = true; continue; }
                switch (got.shadowStatus()) {
                    // A clean verdict needs exactly the reference's candidates and flags: identical, or it is wrong.
                    case "NOT_SHADOWED" -> assertEquals(want, got, context);
                    // A covering rule really covers; it can differ only when the reference's earlier cover was lost to budget.
                    case "REDUNDANT", "CONFLICT" -> {
                        assertTrue(Set.of("REDUNDANT", "CONFLICT").contains(want.shadowStatus()), context);
                        if (!want.coveringRuleId().equals(got.coveringRuleId()))
                            assertEquals("analysis budget", actual.rules().get(want.coveringRuleId()).shadowReason(), context);
                        else assertEquals(want, got, context);
                    }
                    default -> assertEquals("UNKNOWN", got.shadowStatus(), context);
                }
                if (afterBudget && !got.shadowStatus().equals("UNKNOWN")) laterDefinite++;
            }
        }
        // Non-cascading: rules after a budget-exhausted rule still reach definite verdicts.
        assertTrue(budgetRules > 1000 && laterDefinite > 1000, budgetRules + " budget rules, " + laterDefinite + " definite after one");
        System.out.println("TIGHT " + budgetRules + " budget rules, " + laterDefinite + " definite verdicts after one");
    }

    @Test void largeSyntheticPoliciesMatchTheLegacyScan() {
        for (String vendor : List.of("CP", "PAN")) {
            PolicySnapshot snapshot = SyntheticPolicies.large(vendor, 2500, 5);
            var reference = LegacyPolicyHygiene.analyze(snapshot, 90, NOW, Integer.MAX_VALUE);
            var actual = PolicyHygiene.analyze(snapshot, 90, NOW, PolicyHygiene.TOTAL_WORK);
            assertFalse(actual.budgetReached());
            assertEquals(reference.rules(), actual.rules());
        }
    }
}
