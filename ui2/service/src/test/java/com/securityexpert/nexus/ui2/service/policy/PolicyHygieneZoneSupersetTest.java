package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygienePartialPriorTest.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Palo Alto zone superset coverage (PolicyNormalization.zonesCover): an earlier universal rule whose from- and to-zones
 * hold a later rule's zone pairs may cover it; one whose zones provably do not hold them cannot cover it (single-rule
 * cover) and is passed over; "zone or install-on coverage is unproven" stays where the zone relation cannot be decided
 * (any mixed with names, a lone any against names, unequal install-on); disjoint zones prove nothing overlaps. Rule types per the PAN-OS 11.1 admin guide (Components of a Security
 * Policy Rule): universal = from x to, intrazone = within each source zone, interzone = between zones only. Synthetic
 * fixtures: documentation addresses and masked zone names only.
 */
class PolicyHygieneZoneSupersetTest {
    static final String A = "ZONE-ALPHA-01", B = "ZONE-BRAVO-02", C = "ZONE-CHARLIE-03", D = "ZONE-DELTA-04", E = "ZONE-ECHO-05";
    static final String UNPROVEN = PartialPriorOracle.UNPROVEN, RULE_TYPE = PartialPriorOracle.RULE_TYPE;

    static Rule zoned(String id, String source, String action, List<String> from, List<String> to, String... ruleType) {
        var extras = new HashMap<String, List<String>>(Map.of("from", from, "to", to));
        if (ruleType.length > 0) extras.put("rule-type", List.of(ruleType));
        return with(pan(id, source, "any", "port", action), extras);
    }
    static PolicyHygiene.Assessment at(PolicySnapshot snapshot, String id) { return PolicyHygieneTest.assessment(snapshot, id); }
    static void assertCovered(PolicySnapshot snapshot, String id, String covering, String status) {
        var result = at(snapshot, id);
        assertEquals(status, result.shadowStatus(), id + " " + result);
        assertEquals(covering, result.coveringRuleId());
        assertNull(result.shadowReason());
        assertTrue(result.findings().stream().noneMatch(f -> f.evidence().startsWith("Shadowing:")), result.findings().toString());
        assertOracle(snapshot);
    }
    static void assertReason(PolicySnapshot snapshot, String id, String reason) {
        var result = at(snapshot, id);
        assertEquals("UNKNOWN", result.shadowStatus(), id + " " + result);
        assertEquals(reason, result.shadowReason());
        assertNull(result.coveringRuleId());
        assertOracle(snapshot);
    }
    /** Every rule's verdict against the former analysis, through the brute-force oracle. */
    static void assertOracle(PolicySnapshot snapshot) {
        var expected = LegacyPolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, Integer.MAX_VALUE).rules();
        var actual = PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK).rules();
        var universe = new PartialPriorOracle.Universe(snapshot);
        for (var entry : expected.entrySet())
            PartialPriorOracle.assertSameOrSafelyReleased(universe, entry.getValue(), actual.get(entry.getKey()), entry.getKey(), entry.getKey());
    }

    @Test void earlierZoneSupersetCoversOnFromAndOnTo() {
        // from: A, B holds A; to equal.
        assertCovered(pan(zoned("r1", "net", "allow", List.of(A, B), List.of(C)), zoned("r2", "host", "allow", List.of(A), List.of(C))), "r2", "r1", "REDUNDANT");
        // to: C, D holds D; a different action is a conflict.
        assertCovered(pan(zoned("r1", "net", "deny", List.of(A), List.of(C, D)), zoned("r2", "host", "allow", List.of(A), List.of(D))), "r2", "r1", "CONFLICT");
        // Both at once, several zones each.
        assertCovered(pan(zoned("r1", "any", "allow", List.of(A, B, C), List.of(C, D, E)), zoned("r2", "host", "allow", List.of(B, C), List.of(D, E))), "r2", "r1", "REDUNDANT");
    }
    @Test void zoneSupersetWithSelectorsThatDoNotHoldTheRuleIsNotShadowed() {
        // Formerly unproven: the superset prior is now compared and does not contain the source.
        var snapshot = pan(zoned("r1", "host", "allow", List.of(A, B), List.of(C)), zoned("r2", "net", "allow", List.of(A), List.of(C)));
        assertEquals(UNPROVEN, LegacyPolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, Integer.MAX_VALUE).rules().get("r2").shadowReason());
        assertClean(snapshot, "r2");
        assertOracle(snapshot);
    }
    @Test void overlapThatIsProvablyNoSupersetCannotCover() {
        // B, C against A, B: overlap on B, but C traffic is outside the earlier rule, so it cannot cover.
        assertCleanAndSafe(pan(zoned("r1", "any", "deny", List.of(A, B), List.of(C)), zoned("r2", "host", "allow", List.of(B, C), List.of(C))), "r2");
        // The later rule's zones hold the earlier one's, not the reverse.
        assertCleanAndSafe(pan(zoned("r1", "any", "deny", List.of(A), List.of(C)), zoned("r2", "host", "allow", List.of(A, B), List.of(C))), "r2");
        // Superset on from, overlap only on to.
        assertCleanAndSafe(pan(zoned("r1", "any", "deny", List.of(A, B), List.of(C, D)), zoned("r2", "host", "allow", List.of(A), List.of(D, E))), "r2");
        // The former analysis called each of these unproven.
        assertEquals(UNPROVEN, LegacyPolicyHygiene.analyze(pan(zoned("r1", "any", "deny", List.of(A, B), List.of(C)),
            zoned("r2", "host", "allow", List.of(B, C), List.of(C))), 90, PolicyHygieneTest.NOW, Integer.MAX_VALUE).rules().get("r2").shadowReason());
        // An earlier overlapping prior that is no superset (r1) does not stop a later superset prior (r2) from covering.
        assertCovered(pan(zoned("r1", "any", "deny", List.of(B, C), List.of(C)), zoned("r2", "any", "allow", List.of(A, B, C), List.of(C)),
            zoned("r3", "host", "allow", List.of(A, B), List.of(C))), "r3", "r2", "REDUNDANT");
        // Neither the superset prior r2 (host does not hold net) nor r1 (A is outside B, C) covers r3.
        assertCleanAndSafe(pan(zoned("r1", "any", "deny", List.of(B, C), List.of(C)), zoned("r2", "host", "allow", List.of(A, B), List.of(C)),
            zoned("r3", "net", "allow", List.of(A, B), List.of(C))), "r3");
        // A provably non-covering prior does not hide an undecidable one: a mixed any set still leaves it unproven.
        assertReason(pan(zoned("r1", "any", "deny", List.of(B, C), List.of(C)), zoned("r2", "any", "deny", List.of("any", B), List.of(C)),
            zoned("r3", "net", "allow", List.of(A, B), List.of(C))), "r3", UNPROVEN);
    }
    static void assertCleanAndSafe(PolicySnapshot snapshot, String id) {
        assertClean(snapshot, id);
        assertOracle(snapshot);
        PartialPriorOracle.assertNoCover(new PartialPriorOracle.Universe(snapshot), id, id);
    }
    @Test void anyHoldsEveryZoneAndOnlyAnyHoldsAny() {
        // An earlier any (already the former behaviour) together with a new superset on the other side.
        assertCovered(pan(zoned("r1", "any", "allow", List.of("any"), List.of(C, D)), zoned("r2", "host", "allow", List.of(A, B), List.of(C))), "r2", "r1", "REDUNDANT");
        // A later any against named zones, however many, cannot be decided (the zones of the virtual system are not collected).
        assertReason(pan(zoned("r1", "any", "allow", List.of(A, B, C, D, E), List.of(C)), zoned("r2", "host", "allow", List.of("any"), List.of(C))), "r2", UNPROVEN);
        // A set mixing any with names is not interpreted: it holds only itself, and only a lone any (or itself) holds it.
        assertReason(pan(zoned("r1", "any", "allow", List.of("any", C), List.of(C)), zoned("r2", "host", "allow", List.of(A), List.of(C))), "r2", UNPROVEN);
        assertCovered(pan(zoned("r1", "any", "allow", List.of("any"), List.of(C)), zoned("r2", "host", "allow", List.of("any", C), List.of(C))), "r2", "r1", "REDUNDANT");
        assertCovered(pan(zoned("r1", "any", "allow", List.of("any", C), List.of(C)), zoned("r2", "host", "allow", List.of("any", C), List.of(C))), "r2", "r1", "REDUNDANT");
        // any is never disjoint: a later any against an earlier named zone stays unproven, never NOT_SHADOWED.
        assertReason(pan(zoned("r1", "any", "allow", List.of(A), List.of(C)), zoned("r2", "host", "allow", List.of("any"), List.of(C))), "r2", UNPROVEN);
    }
    @Test void disjointZonesProveNoOverlap() {
        assertClean(pan(zoned("r1", "any", "deny", List.of(A, B), List.of(C)), zoned("r2", "host", "allow", List.of(D, E), List.of(C))), "r2");
        assertClean(pan(zoned("r1", "any", "deny", List.of(A), List.of(C, D)), zoned("r2", "host", "allow", List.of(A, B), List.of(E))), "r2");
        assertOracle(pan(zoned("r1", "any", "deny", List.of(A), List.of(C, D)), zoned("r2", "host", "allow", List.of(A, B), List.of(E))));
    }
    @Test void compatibleCoverStillWinsOverAnEarlierSupersetCover() {
        // r1 (superset, deny) and r2 (same key, allow) both cover r3; the former analysis named r2 and still does.
        var snapshot = pan(zoned("r1", "any", "deny", List.of(A, B), List.of(C)), zoned("r2", "any", "allow", List.of(A), List.of(C)),
            zoned("r3", "host", "allow", List.of(A), List.of(C)));
        assertCovered(snapshot, "r3", "r2", "REDUNDANT");
        // r2 itself is covered by the superset r1.
        assertCovered(snapshot, "r2", "r1", "CONFLICT");
    }
    @Test void earlierUniversalRuleCoversIntrazoneRuleOnlyWhenFromAndToHoldItsZones() {
        // Universal A, B -> A, B matches traffic within A and within B: it covers intrazone A (and intrazone A, B).
        assertCovered(pan(zoned("r1", "net", "allow", List.of(A, B), List.of(A, B)), zoned("r2", "host", "allow", List.of(A), List.of(A), "intrazone")), "r2", "r1", "REDUNDANT");
        assertCovered(pan(zoned("r1", "net", "deny", List.of(A, B), List.of(A, B, C)), zoned("r2", "host", "allow", List.of(A, B), List.of("any"), "intrazone")), "r2", "r1", "CONFLICT");
        // Universal A -> C never matches traffic within A.
        assertReason(pan(zoned("r1", "net", "allow", List.of(A, B), List.of(C)), zoned("r2", "host", "allow", List.of(A), List.of(C), "intrazone")), "r2", RULE_TYPE);
        // Intrazone any needs any on both sides.
        assertReason(pan(zoned("r1", "net", "allow", List.of("any"), List.of(A, B)), zoned("r2", "host", "allow", List.of("any"), List.of("any"), "intrazone")), "r2", RULE_TYPE);
        assertCovered(pan(zoned("r1", "net", "allow", List.of("any"), List.of("any")), zoned("r2", "host", "allow", List.of("any"), List.of("any"), "intrazone")), "r2", "r1", "REDUNDANT");
        // Selectors still decide: a narrower earlier source does not cover.
        assertReason(pan(zoned("r1", "host", "allow", List.of(A), List.of(A)), zoned("r2", "net", "allow", List.of(A), List.of(A), "intrazone")), "r2", RULE_TYPE);
    }
    @Test void earlierUniversalRuleCoversInterzoneRuleWhenFromAndToHoldItsZones() {
        assertCovered(pan(zoned("r1", "net", "allow", List.of(A, B), List.of(C, D)), zoned("r2", "host", "allow", List.of(A), List.of(C), "interzone")), "r2", "r1", "REDUNDANT");
        assertCovered(pan(zoned("r1", "net", "deny", List.of("any"), List.of("any")), zoned("r2", "host", "allow", List.of("any"), List.of("any"), "interzone")), "r2", "r1", "CONFLICT");
        assertReason(pan(zoned("r1", "net", "allow", List.of(A, B), List.of(C, D)), zoned("r2", "host", "allow", List.of(A), List.of(E), "interzone")), "r2", RULE_TYPE);
    }
    @Test void intrazoneAndInterzoneRulesNeverCoverAndStayAsBefore() {
        // An earlier intrazone A rule matches exactly the later universal A -> A rule, but is not used to cover (the
        // reverse direction is not implemented); the later rule keeps the former verdict.
        var snapshot = pan(zoned("r1", "net", "allow", List.of(A), List.of(A), "intrazone"), zoned("r2", "host", "allow", List.of(A), List.of(A)));
        assertReason(snapshot, "r1", RULE_TYPE);
        assertNotEquals("REDUNDANT", at(snapshot, "r2").shadowStatus());
        assertEquals(LegacyPolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, Integer.MAX_VALUE).rules().get("r2"), PartialPriorOracle.withoutBlocker(at(snapshot, "r2")));
        // An earlier interzone rule does not cover a later interzone rule either.
        snapshot = pan(zoned("r1", "net", "allow", List.of(A, B), List.of(C), "interzone"), zoned("r2", "host", "allow", List.of(A), List.of(C), "interzone"));
        assertReason(snapshot, "r2", RULE_TYPE);
        // A rule type the documentation does not define, or two values, stays as before.
        assertReason(pan(zoned("r1", "any", "allow", List.of("any"), List.of("any")), zoned("r2", "host", "allow", List.of(A), List.of(A), "intrazone", "interzone")), "r2", RULE_TYPE);
        assertReason(pan(zoned("r1", "any", "allow", List.of("any"), List.of("any")), zoned("r2", "host", "allow", List.of(A), List.of(A), "OBJ-TYPE-01")), "r2", RULE_TYPE);
        // A typed rule with another unsupported constraint is not covered (its reason is whichever the former analysis met first).
        var odd = with(zoned("r2", "host", "allow", List.of(A), List.of(A), "intrazone"), Map.of("target/negate", List.of("yes")));
        snapshot = pan(zoned("r1", "any", "allow", List.of("any"), List.of("any")), odd);
        assertEquals(LegacyPolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, Integer.MAX_VALUE).rules().get("r2"), at(snapshot, "r2"));
        assertEquals("UNKNOWN", at(snapshot, "r2").shadowStatus());
    }
    @Test void installOnStaysExactEquality() {
        var objects = partialObjects();
        objects.put("gw-grp", new PolicyObject("gw-grp", "OBJ-GROUP-09", "group", List.of("gw-a", "gw-b"), List.of(), "RESOLVED"));
        var first = with(zoned("r1", "any", "allow", List.of(A, B), List.of(C)), Map.of("install-on", List.of("gw-grp")));
        var second = with(zoned("r2", "host", "allow", List.of(A), List.of(C)), Map.of("install-on", List.of("gw-a")));
        var snapshot = new PolicySnapshot(pan(first, second).metadata(), pan(first, second).sections(), objects);
        assertReason(snapshot, "r2", UNPROVEN);
        second = with(second, Map.of("install-on", List.of("gw-grp")));
        snapshot = new PolicySnapshot(snapshot.metadata(), pan(first, second).sections(), objects);
        assertCovered(snapshot, "r2", "r1", "REDUNDANT");
    }
    @Test void checkPointPlacementsAreUnchanged() {
        // Check Point has no zones; install-on "any" versus a gateway is still unproven, as before.
        var snapshot = cp(cp("r1", 1, "Accept", "any", "any", "any"), with(cp("r2", 2, "Accept", "host", "any", "port"), Map.of("install-on", List.of("gw-a"))));
        assertEquals(LegacyPolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, Integer.MAX_VALUE).rules(),
            PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK).rules());
    }

    /**
     * Seeded Palo Alto snapshots with richer zone sets and every rule type: wherever the former analysis gave neither
     * "zone or install-on coverage is unproven" nor "zone rule type constraint" the result is identical; every rule that
     * leaves one of them is confirmed by the brute-force oracle ({@link PartialPriorOracle#assertZoneChangeIsSafe}).
     */
    @Test void zoneSupersetDifferentialOverEveryRuleType() {
        Map<String, Integer> changes = new TreeMap<>(); int rules = 0, pans = 0;
        for (long seed = 0; pans < 4000; seed++) {
            var base = SyntheticPolicies.random(seed + 700_000, 4 + (int) (seed % 60));
            if (!base.metadata().vendor().equals("PAN")) continue;
            pans++;
            var snapshot = SyntheticPolicies.legacyComparable(ruleTypes(base, seed));
            var reference = LegacyPolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, Integer.MAX_VALUE).rules();
            var actual = PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK);
            assertFalse(actual.budgetReached());
            var universe = new PartialPriorOracle.Universe(snapshot);
            for (var entry : reference.entrySet()) {
                var expected = entry.getValue(); var got = actual.rules().get(entry.getKey());
                String context = "seed " + seed + " rule " + entry.getKey(), reason = expected.shadowReason();
                if ((UNPROVEN.equals(reason) || RULE_TYPE.equals(reason)) && !got.equals(expected))
                    changes.merge(reason + " -> " + PartialPriorOracle.assertZoneChangeIsSafe(universe, expected, got, entry.getKey(), context), 1, Integer::sum);
                else {
                    if (UNPROVEN.equals(reason) || RULE_TYPE.equals(reason)) changes.merge(reason + " kept", 1, Integer::sum);
                    PartialPriorOracle.assertSameOrSafelyReleased(universe, expected, got, entry.getKey(), context);
                }
                rules++;
            }
        }
        for (String change : List.of(UNPROVEN + " -> COVERED", UNPROVEN + " -> RELEASED", UNPROVEN + " -> OTHER_UNKNOWN", UNPROVEN + " kept",
                RULE_TYPE + " -> COVERED", RULE_TYPE + " kept"))
            // Releases also need every partial prior disjoint, which the messy generator rarely allows (158 at these seeds
            // before the selector pass-over, 398 with it).
            assertTrue(changes.getOrDefault(change, 0) >= (change.endsWith("RELEASED") ? 100 : 200), change + " in " + changes);
        System.out.println("ZONE SUPERSETS " + pans + " PAN snapshots, " + rules + " rules: " + changes);
    }
    private static final List<String> ZONES = List.of(A, B, C, D, E);
    /** Each rule's zones redrawn (one to three named zones, any, or any mixed with a name) and a rule type now and then. */
    static PolicySnapshot ruleTypes(PolicySnapshot snapshot, long seed) {
        var random = new Random(seed * 31 + 7);
        List<Section> sections = new ArrayList<>();
        for (Section section : snapshot.sections()) {
            List<Rule> rules = new ArrayList<>();
            for (Rule rule : section.rules()) {
                var extras = new HashMap<String, List<String>>();
                if (rule.extras().containsKey("from")) extras.put("from", zones(random));
                if (rule.extras().containsKey("to")) extras.put("to", zones(random));
                // Typed rules are partial priors without zones for every later rule, so keep them rare enough that
                // releases (which need every partial prior disjoint) still happen.
                int type = random.nextInt(14);
                if (type == 0) extras.put("rule-type", List.of("intrazone"));
                else if (type == 1) extras.put("rule-type", List.of("interzone"));
                else if (type == 2) extras.put("rule-type", List.of("universal"));
                rules.add(SyntheticPolicies.withExtras(rule, rule.action(), extras));
            }
            sections.add(new Section(section.id(), section.name(), section.source(), section.parentRuleId(), rules));
        }
        return new PolicySnapshot(snapshot.metadata(), sections, snapshot.objects(), snapshot.failures());
    }
    private static List<String> zones(Random random) {
        int pick = random.nextInt(12);
        if (pick < 3) return List.of("any");
        if (pick == 3) return List.of("any", ZONES.get(random.nextInt(ZONES.size())));
        var out = new TreeSet<String>();
        // Few zones, biased to the first two, so equal keys, supersets and partial overlaps all recur.
        for (int i = 0, n = 1 + random.nextInt(pick < 8 ? 1 : 3); i < n; i++) out.add(ZONES.get(Math.min(ZONES.size() - 1, (int) Math.abs(random.nextGaussian() * 1.6))));
        return List.copyOf(out);
    }
}
