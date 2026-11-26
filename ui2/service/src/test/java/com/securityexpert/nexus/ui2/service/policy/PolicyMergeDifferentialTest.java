package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.inLayer;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.policyTargets;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.NOW;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.edit;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.object;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Design section 9, phase P2 "differential tests" for I5: 1,760 random Check Point rulebases over the I3/I4/I7 universe
 * (eight documentation hosts, four TCP ports, one outside value per axis) in one or two layers, a layer sometimes cut into
 * two sections, with anchors (time, unresolved object, Apply Layer), disabled rules, mixed actions, mixed profile and log
 * settings (UNKNOWN included) and four install-on placements (two atomic gateways, "Policy Targets" and "any"). A
 * brute-force oracle evaluates, per gateway and per packet, the first-match deciding rule before and after replacing each
 * offered merge pair (a, b) by its merged rule, in two worlds (every anchor matching its cells, or none):
 * <ul>
 * <li>replacing a and b by the merged rule changes no packet's verdict and no decider's full effect on any gateway;</li>
 * <li>the merged rule's match equals the union of the two boxes, both over the packet universe and in {@link PolicyRegion};</li>
 * <li>no pair across an anchor, a section, a layer, a different install-on, a different effect or an UNKNOWN signature part
 * is offered, and every adjacent pair's classification agrees with the oracle;</li>
 * <li>small budgets give NOT_EVALUABLE (NOT_PROVEN) and never a candidate the full budget does not give.</li>
 * </ul>
 * Synthetic documentation values only.
 */
class PolicyMergeDifferentialTest {
    static final String G1 = "fw-tango-04", G2 = "fw-juliet-06", G3 = "fw-bravo-02", TARGETS = "targets", ALL = "any";
    static final Map<String, PolicyObject> OBJECTS = new HashMap<>(PolicyUnionCoverDifferentialTest.OBJECTS);
    static {
        OBJECTS.put(G1, object(G1, "gateway"));
        OBJECTS.put(G2, object(G2, "gateway"));
        OBJECTS.put(G3, object(G3, "gateway"));
        OBJECTS.put(TARGETS, policyTargets(TARGETS));
    }

    enum Kind { READABLE, TIMED, UNRESOLVED, APPLY_LAYER, DISABLED }
    record Placed(Rule rule, Kind kind, int layer, int section) {
        boolean enabled() { return kind != Kind.DISABLED; }
        boolean anchor() { return kind == Kind.TIMED || kind == Kind.UNRESOLVED || kind == Kind.APPLY_LAYER; }
        String installOn() { return rule.extras().get("install-on").get(0); }
        boolean installedOn(String gateway) {
            return switch (installOn()) {
                case ALL -> true;
                case TARGETS -> gateway.equals(G1) || gateway.equals(G2);
                default -> installOn().equals(gateway);
            };
        }
    }

    static Placed placed(Random random, int i, boolean coarse, int layer, int section, Placed previous) {
        if (previous != null && previous.kind() == Kind.READABLE && previous.layer() == layer && random.nextInt(3) == 0) {
            return neighborVariant(random, i, coarse, layer, section, previous);
        }
        var base = PolicyUnionCoverDifferentialTest.rule(random, i, coarse);
        Rule r = base.rule();
        Kind kind = !r.enabled() ? Kind.DISABLED : r.extras().get("time").equals(List.of("t1")) ? Kind.TIMED
            : !base.readable() ? Kind.UNRESOLVED : Kind.READABLE;
        int placement = random.nextInt(9);
        String installOn = placement < 5 ? G1 : placement == 5 ? G2 : placement == 6 ? TARGETS : ALL;
        int logChoice = random.nextInt(12);
        String log = logChoice < 9 ? "Log" : logChoice < 11 ? "None" : "UNKNOWN";
        Map<String, List<String>> extras = new HashMap<>(Map.of("install-on", List.of(installOn)));
        if (random.nextInt(7) == 0) extras.put("profile-setting/group", List.of("PROF-ALPHA"));
        String action = r.action();
        if (kind == Kind.READABLE && random.nextInt(12) == 0) {
            kind = Kind.APPLY_LAYER; action = "Apply Layer"; extras.put("inline-layer", List.of("inline-" + i));
        }
        r = edit(r, r.source(), extras);
        r = new Rule(r.id(), r.uuid(), r.number(), r.name(), r.enabled(), r.source(), r.destination(), r.service(),
            r.application(), action, log, r.comment(), r.extras());
        if (layer == 1) r = inLayer(r, "LAYER-BRAVO");
        return new Placed(r, kind, layer, section);
    }

    /** Often generates a neighbor differing in 0, 1 or 2 cells (or in signature/placement) so every merge outcome is heavily exercised. */
    private static Placed neighborVariant(Random random, int i, boolean coarse, int layer, int section, Placed prev) {
        List<String> addresses = coarse ? List.of("n0", "n1", "n8", "g1") : new ArrayList<>(PolicyUnionCoverDifferentialTest.ADDRESSES.keySet());
        List<String> services = coarse ? List.of("p12", "p34", "p14") : new ArrayList<>(PolicyUnionCoverDifferentialTest.SERVICES.keySet());
        Rule p = prev.rule();
        Cell src = p.source(), dst = p.destination(), svc = p.service();
        int tweak = random.nextInt(6);
        if (tweak <= 1) src = new Cell(List.of(addresses.get(random.nextInt(addresses.size()))), false);
        else if (tweak == 2) dst = new Cell(List.of(addresses.get(random.nextInt(addresses.size()))), false);
        else if (tweak == 3) svc = new Cell(List.of(services.get(random.nextInt(services.size()))), false);
        else if (tweak == 4) {
            src = new Cell(List.of(addresses.get(random.nextInt(addresses.size()))), false);
            svc = new Cell(List.of(services.get(random.nextInt(services.size()))), false);
        }
        String action = random.nextInt(8) == 0 ? (p.action().equals("Accept") ? "Drop" : "Accept") : p.action();
        String log = random.nextInt(8) == 0 ? (p.log().equals("Log") ? "None" : "UNKNOWN") : p.log();
        Map<String, List<String>> extras = new HashMap<>(p.extras());
        if (random.nextInt(9) == 0) extras.put("install-on", List.of(prev.installOn().equals(G1) ? G2 : G1));
        if (random.nextInt(10) == 0) extras.put("profile-setting/group", List.of("PROF-BRAVO"));
        Rule r = new Rule("r" + i, "uuid-r" + i, i + 1, "OBJ-RULE-" + i, true, src, dst, svc, p.application(), action, log, "", extras);
        if (layer == 1) r = inLayer(r, "LAYER-BRAVO");
        return new Placed(r, Kind.READABLE, layer, section);
    }

    static List<Placed> layout(Random random, boolean coarse, boolean twoLayers, boolean twoSections) {
        List<Placed> out = new ArrayList<>();
        int n = 4 + random.nextInt(9), layerCut = twoLayers ? 1 + random.nextInt(n - 1) : n;
        int sectionCut = twoSections && layerCut > 1 ? 1 + random.nextInt(layerCut - 1) : layerCut;
        for (int i = 0; i < n; i++) {
            int layer = i < layerCut ? 0 : 1;
            int section = layer == 1 ? 2 : i < sectionCut ? 0 : 1;
            out.add(placed(random, i, coarse, layer, section, out.isEmpty() ? null : out.get(out.size() - 1)));
        }
        return out;
    }

    static PolicySnapshot snapshot(List<Placed> rules) {
        List<Section> sections = new ArrayList<>();
        for (int s = 0; s < 3; s++) {
            final int section = s;
            var in = rules.stream().filter(p -> p.section() == section).map(Placed::rule).toList();
            if (!in.isEmpty()) sections.add(new Section("s" + s, "OBJ-SECTION-0" + s, "CP access layer", null, in));
        }
        var base = PolicyHygieneTest.sections("CP", sections.toArray(Section[]::new));
        return new PolicySnapshot(base.metadata(), base.sections(), OBJECTS);
    }

    // ---- brute-force packet-universe oracle ----
    record Packet(String gateway, List<Integer> point) {}

    static Set<Integer> axisUnion(Map<String, Set<Integer>> table, Cell cell, int size, boolean ports) {
        Set<Integer> out = new TreeSet<>();
        for (String ref : cell.refs()) {
            var values = table.get(ref);
            if (values == null) {
                out.add(PolicyUnionCoverDifferentialTest.OUTSIDE);
                for (int v = ports ? 1 : 0; v < (ports ? size + 1 : size); v++) out.add(v);
                return out;
            }
            out.addAll(values);
        }
        return out;
    }

    static Set<List<Integer>> pointsOf(Rule rule) {
        Set<List<Integer>> out = new HashSet<>();
        for (int s : axisUnion(PolicyUnionCoverDifferentialTest.ADDRESSES, rule.source(), PolicyUnionCoverDifferentialTest.HOSTS, false))
            for (int d : axisUnion(PolicyUnionCoverDifferentialTest.ADDRESSES, rule.destination(), PolicyUnionCoverDifferentialTest.HOSTS, false))
                for (int p : axisUnion(PolicyUnionCoverDifferentialTest.SERVICES, rule.service(), PolicyUnionCoverDifferentialTest.PORTS, true))
                    out.add(List.of(s, d, p));
        return out;
    }

    static List<Packet> universe() {
        List<List<Integer>> all = new ArrayList<>();
        for (int s = -1; s < PolicyUnionCoverDifferentialTest.HOSTS; s++)
            for (int d = -1; d < PolicyUnionCoverDifferentialTest.HOSTS; d++)
                for (int p : new int[]{-1, 1, 2, 3, 4}) all.add(List.of(s, d, p));
        List<Packet> out = new ArrayList<>();
        for (String g : List.of(G1, G2, G3)) for (var point : all) out.add(new Packet(g, point));
        return out;
    }

    static final List<Packet> UNIVERSE = universe();

    static Placed decider(List<Placed> order, Packet packet, boolean anchorsMatch, Map<String, Set<List<Integer>>> pointsById) {
        for (Placed p : order) {
            if (!p.enabled() || !p.installedOn(packet.gateway())) continue;
            if ((p.kind() == Kind.TIMED || p.kind() == Kind.UNRESOLVED) && !anchorsMatch) continue;
            if (pointsById.get(p.rule().id()).contains(packet.point())) return p;
        }
        return null;
    }

    static boolean sameVerdict(Placed a, Placed b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.rule().action().equals(b.rule().action())
            && a.rule().extras().getOrDefault("inline-layer", List.of()).equals(b.rule().extras().getOrDefault("inline-layer", List.of()));
    }

    static boolean sameFull(Placed a, Placed b) {
        if (a == b) return true;
        if (!sameVerdict(a, b) || a == null || b == null) return false;
        if (a.rule().action().equals("Apply Layer")) return false;
        return a.rule().extras().getOrDefault("profile-setting/group", List.of())
                .equals(b.rule().extras().getOrDefault("profile-setting/group", List.of()))
            && !"UNKNOWN".equals(a.rule().log()) && a.rule().log().equals(b.rule().log());
    }

    int candidates, alreadyShadowed, multiCell, differentEffect, differentInstallOn, anchorBlocked;

    void check(PolicySnapshot snapshot, List<Placed> rules, PolicyMerge.Result result, String label) {
        Map<String, Placed> byId = new HashMap<>();
        Map<String, Set<List<Integer>>> pointsById = new HashMap<>();
        for (var p : rules) {
            byId.put(p.rule().id(), p);
            if (p.kind() != Kind.UNRESOLVED) pointsById.put(p.rule().id(), pointsOf(p.rule()));
            else pointsById.put(p.rule().id(), Set.of(List.of(0, 0, 1)));
        }
        List<Placed[]> expectedAdjacent = new ArrayList<>();
        for (int s = 0; s < 3; s++) {
            final int sec = s;
            var enabledInSection = rules.stream().filter(p -> p.section() == sec && p.enabled()).toList();
            for (int i = 0; i + 1 < enabledInSection.size(); i++) {
                expectedAdjacent.add(new Placed[]{enabledInSection.get(i), enabledInSection.get(i + 1)});
            }
        }
        assertEquals(expectedAdjacent.size(), result.evaluated(), label);
        assertEquals(expectedAdjacent.stream().map(ab -> ab[0].rule().id()).toList(), new ArrayList<>(result.pairs().keySet()), label);

        for (Placed[] ab : expectedAdjacent) {
            Placed a = ab[0], b = ab[1];
            var pair = result.pairs().get(a.rule().id());
            assertNotNull(pair, label);
            assertEquals(a.layer(), b.layer(), label);
            assertEquals(a.section(), b.section(), label);
            if (a.anchor() || b.anchor()) {
                anchorBlocked++;
                assertEquals(PolicyMerge.ANCHOR_BLOCKED, pair.result(), label);
                continue;
            }
            if (!a.installOn().equals(b.installOn())) {
                differentInstallOn++;
                assertEquals(PolicyMerge.DIFFERENT_INSTALL_ON, pair.result(), label);
                continue;
            }
            if (!sameFull(a, b)) {
                differentEffect++;
                assertEquals(PolicyMerge.DIFFERENT_EFFECT, pair.result(), label);
                continue;
            }
            var ptsA = pointsById.get(a.rule().id());
            var ptsB = pointsById.get(b.rule().id());
            if (ptsA.containsAll(ptsB)) {
                alreadyShadowed++;
                assertEquals(PolicyMerge.ALREADY_SHADOWED, pair.result(), label);
                continue;
            }
            var srcA = axisUnion(PolicyUnionCoverDifferentialTest.ADDRESSES, a.rule().source(), PolicyUnionCoverDifferentialTest.HOSTS, false);
            var srcB = axisUnion(PolicyUnionCoverDifferentialTest.ADDRESSES, b.rule().source(), PolicyUnionCoverDifferentialTest.HOSTS, false);
            var dstA = axisUnion(PolicyUnionCoverDifferentialTest.ADDRESSES, a.rule().destination(), PolicyUnionCoverDifferentialTest.HOSTS, false);
            var dstB = axisUnion(PolicyUnionCoverDifferentialTest.ADDRESSES, b.rule().destination(), PolicyUnionCoverDifferentialTest.HOSTS, false);
            var svcA = axisUnion(PolicyUnionCoverDifferentialTest.SERVICES, a.rule().service(), PolicyUnionCoverDifferentialTest.PORTS, true);
            var svcB = axisUnion(PolicyUnionCoverDifferentialTest.SERVICES, b.rule().service(), PolicyUnionCoverDifferentialTest.PORTS, true);
            int diff = (srcA.equals(srcB) ? 0 : 1) + (dstA.equals(dstB) ? 0 : 1) + (svcA.equals(svcB) ? 0 : 1);
            if (diff >= 2) {
                multiCell++;
                assertEquals(PolicyMerge.DIFFERS_IN_SEVERAL_CELLS, pair.result(), label);
                continue;
            }
            assertEquals(1, diff, label);
            candidates++;
            assertEquals(PolicyMerge.MERGE_CANDIDATE, pair.result(), label);
            String expectedCell = !srcA.equals(srcB) ? "source" : !dstA.equals(dstB) ? "destination" : "service";
            assertEquals(expectedCell, pair.differingCell(), label);

            // Property 1: merged box equals the union of the two boxes (both in packet universe and in PolicyRegion).
            Rule mergedRule = PolicyMerge.mergedRule("CP", a.rule(), b.rule(), pair.differingDim());
            Set<List<Integer>> expectedUnion = new HashSet<>(ptsA);
            expectedUnion.addAll(ptsB);
            Set<List<Integer>> mergedPoints = pointsOf(mergedRule);
            assertEquals(expectedUnion, mergedPoints, "merged rule points must equal union: " + label);

            Section sec = snapshot.sections().stream().filter(s -> s.id().equals("s" + a.section())).findFirst().orElseThrow();
            PolicyMergeTest.assertUnionBoxProperty(snapshot, sec, pair);

            // Property 2: replacing a and b by the merged rule changes no verdict and no full effect for any packet per gateway.
            List<Placed> layerBefore = rules.stream().filter(p -> p.layer() == a.layer()).toList();
            Placed mergedPlaced = new Placed(mergedRule, Kind.READABLE, a.layer(), a.section());
            List<Placed> layerAfter = new ArrayList<>();
            for (Placed p : layerBefore) {
                if (p == a) layerAfter.add(mergedPlaced);
                else if (p != b) layerAfter.add(p);
            }
            Map<String, Set<List<Integer>>> afterPoints = new HashMap<>(pointsById);
            afterPoints.put(mergedRule.id(), mergedPoints);
            for (boolean anchorsMatch : new boolean[]{false, true}) {
                for (Packet packet : UNIVERSE) {
                    Placed before = decider(layerBefore, packet, anchorsMatch, pointsById);
                    Placed now = decider(layerAfter, packet, anchorsMatch, afterPoints);
                    assertTrue(sameVerdict(before, now), "verdict changed on " + label + " " + packet);
                    if (before == now) continue;
                    assertTrue(before == a || before == b, "only a or b may hand packets to the merged rule: " + label);
                    assertSame(mergedPlaced, now, "packets of a or b must be decided by the merged rule: " + label);
                    assertTrue(sameFull(before, now), "full effect changed on " + label + " " + packet);
                }
            }
        }
    }

    @Test void everyCandidateAgreesWithTheBruteForceOracleOnRandomCheckPointRulebases() {
        List<Long> seeds = new ArrayList<>(List.of(1126L, 1127L, 1128L, 2026L, 5L));
        var more = new Random(0x15L);
        for (int i = 0; i < 3; i++) seeds.add(more.nextLong());
        int trials = 0;
        for (long seed : seeds) {
            var random = new Random(seed);
            for (int trial = 0; trial < 220; trial++, trials++) {
                var rules = layout(random, trial % 2 == 0, trial % 3 == 0, trial % 4 == 1);
                var snapshot = snapshot(rules);
                var result = PolicyMerge.analyze(snapshot);
                String label = "seed " + seed + " trial " + trial;
                PolicyMergeTest.assertPartsSumToEvaluated(result);
                check(snapshot, rules, result, label);
            }
        }
        System.out.println("I5 differential: " + trials + " rulebases, candidates " + candidates + ", alreadyShadowed " + alreadyShadowed
            + ", multiCell " + multiCell + ", differentEffect " + differentEffect + ", differentInstallOn " + differentInstallOn
            + ", anchorBlocked " + anchorBlocked);
        assertTrue(trials >= 1_000, "rulebases checked: " + trials);
        assertTrue(candidates >= 100, "merge candidates exercised: " + candidates);
        assertTrue(alreadyShadowed >= 50, "already-shadowed pairs exercised: " + alreadyShadowed);
        assertTrue(multiCell >= 100, "multi-cell pairs exercised: " + multiCell);
        assertTrue(differentEffect >= 50, "different-effect pairs exercised: " + differentEffect);
        assertTrue(differentInstallOn >= 30, "different-install-on pairs exercised: " + differentInstallOn);
        assertTrue(anchorBlocked >= 50, "anchor-blocked pairs exercised: " + anchorBlocked);
    }

    @Test void smallBudgetsGiveNotEvaluableAndNeverACandidateTheFullBudgetDoesNotGive() {
        var random = new Random(1130L);
        int notProven = 0, proven = 0;
        for (int trial = 0; trial < 120; trial++) {
            var rules = layout(random, trial % 2 == 0, trial % 3 == 0, trial % 4 == 1);
            var snapshot = snapshot(rules);
            var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250_000);
            var union = PolicyUnionCover.analyze(snapshot, hygiene);
            var partial = PolicyPartialShadow.analyze(snapshot, hygiene, union);
            var full = PolicyMerge.analyze(snapshot);
            for (int work : new int[]{0, 3, 8, 15, 30, 60, 120, 250, 600, 1500}) {
                for (int window : new int[]{work, Math.max(1, work / 4)}) {
                    var limited = PolicyMerge.analyze(snapshot, work, window);
                    PolicyMergeTest.assertPartsSumToEvaluated(limited);
                    assertEquals(full.pairs().keySet(), limited.pairs().keySet());
                    for (var e : limited.pairs().entrySet()) {
                        var pair = e.getValue();
                        if (pair.result().equals(PolicyMerge.NOT_PROVEN)) {
                            notProven++;
                            assertEquals(PolicyMerge.BUDGET, pair.reason());
                            assertNull(pair.differingCell());
                            continue;
                        }
                        var truth = full.pairs().get(e.getKey());
                        assertEquals(truth.result(), pair.result(), "trial " + trial + " work " + work + " " + e.getKey());
                        assertEquals(truth.differingCell(), pair.differingCell(), "trial " + trial + " work " + work);
                        if (pair.result().equals(PolicyMerge.MERGE_CANDIDATE)) proven++;
                    }
                    var report = PolicyInsights.analyze(snapshot, hygiene, null, Map.of(), 90, union, partial, limited);
                    for (var insight : PolicyInsightsTest.of(report, "I5")) {
                        if (insight.grade().equals("NOT_EVALUABLE")) {
                            assertEquals(Map.of("mergeReason", PolicyMerge.BUDGET), insight.counter());
                        } else {
                            assertEquals("PROVEN", insight.grade());
                            var truth = full.pairs().get(insight.rules().get(0).ruleId());
                            assertEquals(PolicyMerge.MERGE_CANDIDATE, truth.result());
                        }
                    }
                }
            }
        }
        assertTrue(notProven >= 50, "budget exhaustion exercised: " + notProven);
        assertTrue(proven >= 50, "candidates under a budget exercised: " + proven);
    }
}
