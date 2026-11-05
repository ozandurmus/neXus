package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import java.time.Instant;
import java.util.*;

/**
 * The indexed, per-rule-budgeted shadowing must answer exactly what the former linear scan answered wherever the former
 * scan did not run out of budget, over seeded random snapshots (zones, install-on, negation, groups, ranges, disabled
 * and unknown-enabled rules, both vendors, scope breaks and collection failures). Under deliberately tight budgets it
 * may only degrade towards UNKNOWN, never towards a cleaner verdict. These snapshots carry no non-RESOLVED install-on
 * object and no inline layer ({@link SyntheticPolicies#legacyComparable}), so they also prove those changes left every
 * other snapshot's results exactly as they were. The two changes have their own oracles below: an opaque install-on
 * reference answers what a resolved, non-atomic target under the same id answered, and every layer of an inline-layer
 * policy answers what the former analysis answers for that layer alone with its "Apply Layer" rules made terminal.
 * <p>Partial priors: wherever the former analysis gave anything but "earlier rule has unsupported constraints", every
 * comparison here demands the identical result; where it gave that reason, the rule may only be released (NOT_SHADOWED,
 * or "snapshot collection incomplete" when collection failed), and then {@link PartialPriorOracle} must confirm by brute
 * force that it shares no packet with any unresolved earlier rule of its scope.
 */
@Execution(ExecutionMode.CONCURRENT) // methods are independent oracles over local, immutable data
class PolicyHygieneDifferentialTest {
    static final Instant NOW = PolicyHygieneTest.NOW;
    static final int SNAPSHOTS = 4000, LARGE = 120;

    @Test void productionBudgetsMatchTheLegacyScanOnEveryRule() {
        Map<String, Integer> seen = new TreeMap<>(), released = new TreeMap<>(); int rules = 0;
        for (long seed = 0; seed < SNAPSHOTS + LARGE; seed++) {
            var snapshot = SyntheticPolicies.legacyComparable(SyntheticPolicies.random(seed, seed < SNAPSHOTS ? 1 + (int) (seed % 60) : 150 + (int) (seed % 150)));
            var reference = LegacyPolicyHygiene.analyze(snapshot, 90, NOW, Integer.MAX_VALUE);
            var legacyDefault = LegacyPolicyHygiene.analyze(snapshot, 90, NOW, 250_000);
            var actual = PolicyHygiene.analyze(snapshot, 90, NOW, PolicyHygiene.TOTAL_WORK);
            assertFalse(reference.budgetReached(), "seed " + seed);
            assertFalse(actual.budgetReached(), "seed " + seed);
            assertEquals(reference.rules().keySet(), actual.rules().keySet());
            var universe = new PartialPriorOracle.Universe(snapshot);
            for (var entry : reference.rules().entrySet()) {
                var expected = entry.getValue(); var got = actual.rules().get(entry.getKey());
                String context = "seed " + seed + " rule " + entry.getKey();
                boolean release = PartialPriorOracle.assertSameOrSafelyReleased(universe, expected, got, entry.getKey(), context);
                var legacy = legacyDefault.rules().get(entry.getKey());
                if (!"analysis budget".equals(legacy.shadowReason())) PartialPriorOracle.assertSameOrSafelyReleased(universe, legacy, got, entry.getKey(), context);
                String vendor = snapshot.metadata().vendor();
                seen.merge(vendor + " " + expected.shadowStatus() + (expected.shadowReason() == null ? "" : " / " + expected.shadowReason()), 1, Integer::sum);
                if (PartialPriorOracle.UNSUPPORTED.equals(expected.shadowReason())) released.merge(vendor + (release ? " released" : " kept"), 1, Integer::sum);
                rules++;
            }
        }
        // Partial priors must release a real share of the formerly blocked rules for both vendors, and keep others blocked.
        for (String vendor : List.of("CP", "PAN")) for (String outcome : List.of(" released", " kept"))
            assertTrue(released.getOrDefault(vendor + outcome, 0) >= 100, vendor + outcome + " in " + released);
        // The generator must actually reach every verdict the index can change, for both vendors.
        for (String vendor : List.of("CP", "PAN")) for (String verdict : List.of("NOT_SHADOWED", "REDUNDANT", "CONFLICT",
                "UNKNOWN / earlier rule has unsupported constraints", "UNKNOWN / earlier sections not analysed", "UNKNOWN / negated selector",
                "UNKNOWN / snapshot collection incomplete", "UNKNOWN / enabled state is unknown"))
            assertTrue(seen.getOrDefault(vendor + " " + verdict, 0) >= 20, vendor + " " + verdict + " in " + seen);
        assertTrue(seen.getOrDefault("PAN UNKNOWN / zone or install-on coverage is unproven", 0) >= 20, seen.toString());
        assertTrue(seen.getOrDefault("CP UNKNOWN / zone or install-on coverage is unproven", 0) >= 20, seen.toString());
        assertTrue(seen.getOrDefault("CP UNKNOWN / install-on evidence not collected", 0) > 0, seen.toString());
        assertTrue(seen.getOrDefault("PAN UNKNOWN / zone evidence not collected", 0) > 0, seen.toString());
        assertTrue(seen.getOrDefault("CP UNKNOWN / unresolved install-on selector", 0) > 0, seen.toString());
        assertTrue(rules > 140_000, "rules compared: " + rules);
        System.out.println("DIFFERENTIAL " + (SNAPSHOTS + LARGE) + " snapshots, " + rules + " rules: " + seen);
        System.out.println("PARTIAL PRIORS formerly 'earlier rule has unsupported constraints': " + released);
    }

    /**
     * An UNRESOLVED or UNSUPPORTED ("Policy Targets", native type Global) install-on reference is only an opaque placement
     * token: the result is exactly the former result for a RESOLVED target group under the same id -- equal sets compare,
     * unequal ones are never disjoint -- and nothing else about the snapshot changes.
     */
    @Test void opaqueInstallOnAnswersWhatAResolvedNonAtomicTargetAnswered() {
        int opaque = 0, proven = 0, formerlyUnresolved = 0;
        for (long seed = 0; seed < 3000; seed++) {
            var flat = SyntheticPolicies.random(seed + 200_000, 5 + (int) (seed % 80));
            // Inline layers are covered by their own oracle; drop the parent links so only the install-on change is in play.
            flat = new PolicySnapshot(flat.metadata(), flat.sections().stream().map(s -> new PolicySnapshot.Section(s.id(), s.name(), s.source(), null, s.rules())).toList(),
                flat.objects(), flat.failures());
            if (seed % 2 == 1) flat = SyntheticPolicies.policyTargets(flat);
            var actual = PolicyHygiene.analyze(flat, 90, NOW, PolicyHygiene.TOTAL_WORK).rules();
            var expected = LegacyPolicyHygiene.analyze(SyntheticPolicies.resolvedTargets(flat), 90, NOW, Integer.MAX_VALUE).rules();
            var former = LegacyPolicyHygiene.analyze(flat, 90, NOW, Integer.MAX_VALUE).rules();
            assertEquals(expected.keySet(), actual.keySet(), "seed " + seed);
            var universe = new PartialPriorOracle.Universe(flat);
            for (var entry : expected.entrySet())
                PartialPriorOracle.assertSameOrSafelyReleased(universe, entry.getValue(), actual.get(entry.getKey()), entry.getKey(), "seed " + seed + " rule " + entry.getKey());
            for (var section : flat.sections()) for (var rule : section.rules()) {
                if (!rule.extras().getOrDefault("install-on", List.of()).contains("gw-unres")) continue;
                opaque++;
                if (!actual.get(rule.id()).shadowStatus().equals("UNKNOWN")) proven++;
                if ("unresolved install-on selector".equals(former.get(rule.id()).shadowReason())) formerlyUnresolved++;
            }
        }
        assertTrue(opaque > 2000 && proven > 100 && formerlyUnresolved > 500, opaque + " opaque, " + proven + " proven, " + formerlyUnresolved + " formerly unresolved");
        System.out.println("OPAQUE INSTALL-ON " + opaque + " rules, " + proven + " proven, " + formerlyUnresolved + " formerly unresolved");
    }

    /**
     * Each layer of a well-formed inline-layer policy -- the ordered layer and every inline layer -- is analysed exactly as
     * the former analysis analyses that layer's sections alone (no parent link) with each "Apply Layer" rule made a
     * terminal rule: child rules never meet parent rules, the parent resumes after its inline layers with its own earlier
     * rules, and a resolved "Apply Layer" rule covers later parent rules. The covering rule and the reason must be
     * identical. A rule covered by an Apply Layer rule must be REDUNDANT with the inline-layer evidence (or the same-action
     * evidence when both apply the same inline layer); between two rules that apply no layer the status is exactly the
     * former one. Elsewhere REDUNDANT and CONFLICT are compared as covered, because the stand-in action differs.
     */
    @Test void everyLayerOfAnInlineLayerPolicyAnswersWhatThatLayerAloneAnswered() {
        int compared = 0, resumed = 0, formerlyUnproven = 0, coveredByApplyLayer = 0, inline = 0, ruleReasonOnly = 0, releasedInline = 0, passedOver = 0;
        for (long seed = 0; seed < 2500; seed++) {
            var snapshot = SyntheticPolicies.inline(seed + 300_000, 5 + (int) (seed % 70));
            if (seed % 2 == 1) snapshot = SyntheticPolicies.policyTargets(snapshot);
            var actual = PolicyHygiene.analyze(snapshot, 90, NOW, PolicyHygiene.TOTAL_WORK).rules();
            var former = LegacyPolicyHygiene.analyze(snapshot, 90, NOW, Integer.MAX_VALUE).rules();
            var universe = new PartialPriorOracle.Universe(snapshot);
            Map<String, List<PolicySnapshot.Section>> layers = new LinkedHashMap<>();
            for (var section : snapshot.sections()) layers.computeIfAbsent(String.valueOf(section.parentRuleId()), k -> new ArrayList<>()).add(section);
            Set<String> applyLayer = new HashSet<>();
            for (var section : snapshot.sections()) for (var rule : section.rules()) if (rule.extras().containsKey("inline-layer")) applyLayer.add(rule.id());
            for (var entry : layers.entrySet()) {
                List<PolicySnapshot.Section> alone = new ArrayList<>();
                for (var section : entry.getValue()) alone.add(new PolicySnapshot.Section(section.id(), section.name(), section.source(), null,
                    section.rules().stream().map(r -> r.extras().containsKey("inline-layer") ? terminal(r) : r).toList()));
                var standalone = SyntheticPolicies.resolvedTargets(new PolicySnapshot(snapshot.metadata(), alone, snapshot.objects()));
                var expected = LegacyPolicyHygiene.analyze(standalone, 90, NOW, Integer.MAX_VALUE).rules();
                boolean afterInline = false;
                for (var section : entry.getValue()) {
                    for (var rule : section.rules()) {
                        var want = expected.get(rule.id()); var got = actual.get(rule.id());
                        String context = "seed " + seed + " layer " + entry.getKey() + " rule " + rule.id();
                        if (PartialPriorOracle.UNPROVEN.equals(want.shadowReason()) && !PartialPriorOracle.UNPROVEN.equals(got.shadowReason())) {
                            // Selector pass-over: every placement-undecided prior fails to contain a selector, so the rule
                            // is decided without them -- clean (confirmed by brute force in every world) or blocked by a
                            // partial prior, never covered.
                            assertNull(got.coveringRuleId(), context);
                            if (PartialPriorOracle.UNSUPPORTED.equals(got.shadowReason())) assertEquals("UNKNOWN", got.shadowStatus(), context);
                            else {
                                assertEquals("NOT_SHADOWED", got.shadowStatus(), context); assertNull(got.shadowReason(), context);
                                PartialPriorOracle.assertNoCover(universe, rule.id(), context);
                                PartialPriorOracle.assertReleaseIsSafe(universe, rule.id(), context);
                            }
                            passedOver++; compared++;
                            continue;
                        }
                        if (PartialPriorOracle.UNSUPPORTED.equals(want.shadowReason()) && !PartialPriorOracle.UNSUPPORTED.equals(got.shadowReason())) {
                            // Released by partial priors: clean, and the brute-force check must confirm it.
                            assertEquals("NOT_SHADOWED", got.shadowStatus(), context);
                            assertNull(got.shadowReason(), context); assertNull(got.coveringRuleId(), context);
                            PartialPriorOracle.assertReleaseIsSafe(universe, rule.id(), context);
                            releasedInline++; compared++;
                            continue;
                        }
                        assertEquals(covered(want.shadowStatus()), covered(got.shadowStatus()), context);
                        // Rule extras are an immutable map whose iteration order depends on its key set, so dropping the
                        // inline-layer key for the stand-in may surface a different one of the rule's own failing
                        // constraints first. Either way the rule has no match; scope reasons must still be identical.
                        if (applyLayer.contains(rule.id()) && want.shadowReason() != null && got.shadowReason() != null
                                && !SCOPE_REASONS.contains(want.shadowReason()) && !SCOPE_REASONS.contains(got.shadowReason())) ruleReasonOnly++;
                        else assertEquals(want.shadowReason(), got.shadowReason(), context);
                        assertEquals(want.coveringRuleId(), got.coveringRuleId(), context);
                        compared++;
                        if (!entry.getKey().equals("null")) inline++;
                        if (afterInline) resumed++;
                        if (afterInline && "earlier sections not analysed".equals(former.get(rule.id()).shadowReason())
                                && !"earlier sections not analysed".equals(got.shadowReason())) formerlyUnproven++;
                        var shadow = got.findings().stream().filter(f -> Set.of("shadowed", "conflict").contains(f.findingClass())).toList();
                        if (got.coveringRuleId() != null && applyLayer.contains(got.coveringRuleId())) {
                            // Covered by an Apply Layer rule: never reached, never a conflict (the inline layer's verdict is unknown).
                            coveredByApplyLayer++;
                            assertEquals("REDUNDANT", got.shadowStatus(), context);
                            boolean sameLayer = applyLayer.contains(rule.id()) && rule.extras().get("inline-layer")
                                .equals(snapshot.sections().stream().flatMap(s -> s.rules().stream()).filter(r -> r.id().equals(got.coveringRuleId())).findFirst().orElseThrow().extras().get("inline-layer"));
                            assertEquals(List.of(new PolicyHygiene.Finding("shadowed", "MEDIUM", sameLayer
                                ? "Earlier enabled rule fully covers this match with the same action." : PolicyHygiene.INLINE_LAYER_COVER)), shadow, context);
                        } else if (got.coveringRuleId() != null && !applyLayer.contains(rule.id())) {
                            // Neither rule applies a layer: the stand-in changed nothing, so the verdict is exactly the former one.
                            assertEquals(want.shadowStatus(), got.shadowStatus(), context);
                            assertTrue(shadow.stream().noneMatch(f -> f.evidence().equals(PolicyHygiene.INLINE_LAYER_COVER)), context);
                        }
                    }
                    if (!section.rules().isEmpty() && applyLayer.contains(section.rules().get(section.rules().size() - 1).id())) afterInline = true;
                }
            }
        }
        assertTrue(compared > 50_000 && inline > 10_000 && resumed > 10_000 && formerlyUnproven > 5_000 && coveredByApplyLayer > 200 && releasedInline > 200,
            compared + " compared, " + inline + " inline, " + resumed + " resumed, " + formerlyUnproven + " formerly unproven, " + coveredByApplyLayer + " covered by Apply Layer");
        System.out.println("INLINE LAYERS " + compared + " compared, " + inline + " inline, " + resumed + " resumed parent, "
            + formerlyUnproven + " formerly unproven, " + coveredByApplyLayer + " covered by Apply Layer, " + ruleReasonOnly + " rule-reason order only, "
            + releasedInline + " released by partial priors, " + passedOver + " unproven decided by selector pass-over");
    }
    private static final Set<String> SCOPE_REASONS = Set.of("earlier sections not analysed", "earlier rule has unsupported constraints",
        "zone or install-on coverage is unproven", "analysis budget", "snapshot collection incomplete");
    private static PolicySnapshot.Rule terminal(PolicySnapshot.Rule rule) {
        var extras = new HashMap<String, List<String>>(); extras.put("inline-layer", null);
        return SyntheticPolicies.withExtras(rule, "Reject", extras);
    }
    private static String covered(String status) { return Set.of("REDUNDANT", "CONFLICT").contains(status) ? "COVERED" : status; }

    /**
     * Palo Alto application-default as an opaque service token: on seeded snapshots where about a third of the rules use
     * the mapper's application-default service, every rule is compared with the same analysis of the same snapshot with
     * that service not recognised (the former "missing service values" treatment, itself checked against the legacy scan
     * here). A rule may differ only when application-default is its own service or that of an earlier rule of its scope,
     * and then its new verdict must hold for every assignment of the unknown, per-application default ports
     * ({@link PartialPriorOracle#assertDefaultPortsChangeIsSafe}). Every other rule is identical, blocker included.
     * Content-provided applications on explicit-service rules have their own test below; here both analyses read them
     * as before ({@link SyntheticPolicies#unrecognisedApplications}), so only application-default is in play.
     */
    @Test void applicationDefaultChangesOnlyTheRulesItInvolvesAndHoldsInEveryDefaultPortWorld() {
        Map<String, Integer> changed = new TreeMap<>(); int rules = 0, involved = 0, formerlyMissing = 0, formerlyBlocked = 0;
        for (long seed = 0; seed < 3000; seed++) {
            var base = SyntheticPolicies.legacyComparable(SyntheticPolicies.random(seed + 400_000, 2 + (int) (seed % 70)));
            if (!base.metadata().vendor().equals("PAN")) continue;
            var snapshot = SyntheticPolicies.unrecognisedApplications(SyntheticPolicies.applicationDefault(base, seed));
            var unrecognised = SyntheticPolicies.unrecognisedApplications(SyntheticPolicies.unrecognisedDefault(snapshot));
            var former = PolicyHygiene.analyze(unrecognised, 90, NOW, PolicyHygiene.TOTAL_WORK).rules();
            var actual = PolicyHygiene.analyze(snapshot, 90, NOW, PolicyHygiene.TOTAL_WORK).rules();
            // The reference is the former treatment: still the legacy scan's answer up to the known, oracle-checked changes.
            var legacy = LegacyPolicyHygiene.analyze(unrecognised, 90, NOW, Integer.MAX_VALUE).rules();
            var formerUniverse = new PartialPriorOracle.Universe(unrecognised);
            var universe = new PartialPriorOracle.Universe(snapshot);
            assertEquals(former.keySet(), actual.keySet());
            for (var entry : former.entrySet()) {
                String id = entry.getKey(), context = "seed " + seed + " rule " + id;
                PartialPriorOracle.assertSameOrSafelyReleased(formerUniverse, legacy.get(id), entry.getValue(), id, context + " (former)");
                if (PartialPriorOracle.involvesApplicationDefault(universe, id)) involved++;
                String change = PartialPriorOracle.assertDefaultPortsChangeIsSafe(universe, entry.getValue(), actual.get(id), id, context);
                if (change != null) {
                    changed.merge(change, 1, Integer::sum);
                    if ("missing service values".equals(entry.getValue().shadowReason())) formerlyMissing++;
                    if (PartialPriorOracle.UNSUPPORTED.equals(entry.getValue().shadowReason())) formerlyBlocked++;
                }
                rules++;
            }
        }
        // The generator must reach every new verdict, and release a real share of the formerly blocked rules.
        for (String verdict : List.of("REDUNDANT", "CONFLICT", "NOT_SHADOWED", "UNKNOWN / " + PartialPriorOracle.DEFAULT_PORTS))
            assertTrue(changed.getOrDefault(verdict, 0) >= 20, verdict + " in " + changed);
        assertTrue(rules > 40_000 && involved > 20_000 && formerlyMissing > 2_000 && formerlyBlocked > 1_000,
            rules + " rules, " + involved + " involved, " + formerlyMissing + " formerly missing service values, " + formerlyBlocked + " formerly blocked");
        System.out.println("APPLICATION-DEFAULT " + rules + " rules, " + involved + " involve it, changed: " + changed
            + "; formerly missing service values " + formerlyMissing + ", formerly earlier-unsupported " + formerlyBlocked);
    }

    /**
     * Palo Alto content-provided applications as opaque identities: on seeded snapshots with application-default rules
     * and, on about half of the other rules, applications the configuration does not define (UNRESOLVED under their own
     * application reference), every rule is compared with the same analysis of the same snapshot with those references
     * not recognised on explicit-service rules (the former "unresolved object" treatment). A rule may differ only when
     * such an application is named by itself or by an earlier rule of its scope; every other rule is identical, blocker
     * included. A changed verdict must hold in every world: for every overlap of the named applications (one may carry
     * another's traffic) and every assignment of default ports ({@link PartialPriorOracle#assertContentApplicationChangeIsSafe}).
     */
    @Test void contentApplicationsChangeOnlyTheRulesTheyInvolveAndHoldForEveryOverlap() {
        Map<String, Integer> changed = new TreeMap<>(); int rules = 0, involved = 0, formerlyUnresolved = 0, formerlyBlocked = 0;
        for (long seed = 0; seed < 3000; seed++) {
            var base = SyntheticPolicies.legacyComparable(SyntheticPolicies.random(seed + 500_000, 2 + (int) (seed % 70)));
            if (!base.metadata().vendor().equals("PAN")) continue;
            var snapshot = SyntheticPolicies.contentApplications(SyntheticPolicies.applicationDefault(base, seed), seed);
            var reference = PolicyHygiene.analyze(SyntheticPolicies.unrecognisedApplications(snapshot), 90, NOW, PolicyHygiene.TOTAL_WORK).rules();
            var actual = PolicyHygiene.analyze(snapshot, 90, NOW, PolicyHygiene.TOTAL_WORK).rules();
            var universe = new PartialPriorOracle.Universe(snapshot);
            assertEquals(reference.keySet(), actual.keySet());
            for (var entry : reference.entrySet()) {
                String id = entry.getKey(), context = "seed " + seed + " rule " + id;
                if (PartialPriorOracle.involvesContentApplication(universe, id)) involved++;
                String change = PartialPriorOracle.assertContentApplicationChangeIsSafe(universe, entry.getValue(), actual.get(id), id, context);
                if (change != null) {
                    changed.merge(change, 1, Integer::sum);
                    if ("unresolved object".equals(entry.getValue().shadowReason())) formerlyUnresolved++;
                    if (PartialPriorOracle.UNSUPPORTED.equals(entry.getValue().shadowReason())) formerlyBlocked++;
                }
                rules++;
            }
        }
        System.out.println("CONTENT APPLICATIONS " + rules + " rules, " + involved + " involve one, changed: " + changed
            + "; formerly unresolved object " + formerlyUnresolved + ", formerly earlier-unsupported " + formerlyBlocked);
        // The generator must reach every new verdict, and release a real share of the formerly unresolved and blocked rules.
        for (String verdict : List.of("REDUNDANT", "CONFLICT", "NOT_SHADOWED", "UNKNOWN / " + PartialPriorOracle.OVERLAP, "UNKNOWN / " + PartialPriorOracle.DEFAULT_PORTS))
            assertTrue(changed.getOrDefault(verdict, 0) >= 20, verdict + " in " + changed);
        assertTrue(rules > 40_000 && involved > 20_000 && formerlyUnresolved > 2_000 && formerlyBlocked > 500,
            rules + " rules, " + involved + " involved, " + formerlyUnresolved + " formerly unresolved object, " + formerlyBlocked + " formerly blocked");
    }

    @Test void legacyCascadeAndNewIndexAgreeWhereverTheLegacyBudgetHeld() {
        var random = new Random(11);
        int compared = 0, legacyBudget = 0;
        for (long seed = 0; seed < 1500; seed++) {
            var snapshot = SyntheticPolicies.legacyComparable(SyntheticPolicies.random(seed + 50_000, 20 + (int) (seed % 120)));
            int limit = random.nextInt(4000);
            var legacy = LegacyPolicyHygiene.analyze(snapshot, 90, NOW, limit);
            var actual = PolicyHygiene.analyze(snapshot, 90, NOW, PolicyHygiene.TOTAL_WORK);
            var universe = new PartialPriorOracle.Universe(snapshot);
            for (var entry : legacy.rules().entrySet()) {
                if ("analysis budget".equals(entry.getValue().shadowReason())) { legacyBudget++; continue; }
                PartialPriorOracle.assertSameOrSafelyReleased(universe, entry.getValue(), actual.rules().get(entry.getKey()), entry.getKey(),
                    "seed " + seed + " limit " + limit + " rule " + entry.getKey());
                compared++;
            }
        }
        assertTrue(compared > 50_000 && legacyBudget > 10_000, compared + " compared, " + legacyBudget + " legacy budget");
    }

    @Test void tightBudgetsOnlyDegradeTowardsUnknown() {
        var random = new Random(23);
        int budgetRules = 0, laterDefinite = 0;
        for (long seed = 0; seed < 2500; seed++) {
            var snapshot = SyntheticPolicies.legacyComparable(SyntheticPolicies.random(seed + 90_000, 10 + (int) (seed % 90)));
            // The reference is the unlimited production analysis (itself compared with the legacy scan above): budgets
            // may only push a rule towards UNKNOWN, partial priors included.
            var reference = PolicyHygiene.analyze(snapshot, 90, NOW, Integer.MAX_VALUE, Integer.MAX_VALUE, snapshot.objects()).rules();
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
