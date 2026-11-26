package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.inLayer;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.NOW;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.edit;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.object;
import static com.securityexpert.nexus.ui2.service.policy.PolicyUnionCoverDifferentialTest.points;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Design section 9, phase P2 "differential tests" for I7: random small Check Point rulebases over the I3/I4 universe
 * (eight documentation hosts, four TCP ports, one outside value per axis) in one or two layers, a layer sometimes cut into
 * two sections, with anchors (time, unresolved object, Apply Layer), disabled rules, mixed actions, mixed profile and log
 * settings and three install-on placements (two atomic gateways and "any"). A brute-force oracle evaluates, per gateway and
 * per packet, the first-match deciding rule of the layer before and after each move the engine evaluated, in two worlds
 * (every anchor matching its cells, or none), and holds the engine to it:
 * <ul>
 * <li>a SAFE move changes no packet's verdict nor its decider's full effect; a SAFE_INSPECTION_CHANGES move changes no
 * verdict;</li>
 * <li>no move across an anchor, a section, or an overlapping rule of another verdict (on a placement not proven disjoint)
 * is ever offered, and every refusal names a reason the oracle sees;</li>
 * <li>the rules the engine says decide part of the promoted rule's match today are exactly the oracle's old deciders of the
 * packets whose deciding rule changes (a superset where a non-equal, not provably disjoint placement makes the region an
 * upper bound), and EQUIVALENT means every such packet keeps its full effect;</li>
 * <li>small budgets give NOT_PROVEN and never a result the full budget does not give.</li>
 * </ul>
 * A fixed seed list plus a bounded random loop. Synthetic values only.
 */
class PolicyReorderDifferentialTest {
    static final String G1 = "fw-tango-04", G2 = "fw-juliet-06", ALL = "any";
    static final Map<String, PolicyObject> OBJECTS = new HashMap<>(PolicyUnionCoverDifferentialTest.OBJECTS);
    static {
        OBJECTS.put(G1, object(G1, "gateway"));
        OBJECTS.put(G2, object(G2, "gateway"));
    }
    enum Kind { READABLE, TIMED, UNRESOLVED, APPLY_LAYER, DISABLED }
    record Placed(Rule rule, Kind kind, int layer, int section) {
        boolean enabled() { return kind != Kind.DISABLED; }
        boolean anchor() { return kind == Kind.TIMED || kind == Kind.UNRESOLVED || kind == Kind.APPLY_LAYER; }
        String installOn() { return rule.extras().get("install-on").get(0); }
        boolean installedOn(String gateway) { return installOn().equals(ALL) || installOn().equals(gateway); }
    }

    static Placed placed(Random random, int i, boolean coarse, int layer, int section) {
        var base = PolicyUnionCoverDifferentialTest.rule(random, i, coarse);
        Rule r = base.rule();
        Kind kind = !r.enabled() ? Kind.DISABLED : r.extras().get("time").equals(List.of("t1")) ? Kind.TIMED : !base.readable() ? Kind.UNRESOLVED : Kind.READABLE;
        int placement = random.nextInt(8);
        String installOn = placement < 6 ? G1 : placement == 6 ? G2 : ALL;
        int logChoice = random.nextInt(12);
        String log = logChoice < 9 ? "Log" : logChoice < 11 ? "None" : "UNKNOWN";
        Map<String, List<String>> extras = new HashMap<>(Map.of("install-on", List.of(installOn)));
        if (random.nextInt(7) == 0) extras.put("profile-setting/group", List.of("PROF-ALPHA"));
        String action = r.action();
        if (kind == Kind.READABLE && random.nextInt(12) == 0) { kind = Kind.APPLY_LAYER; action = "Apply Layer"; extras.put("inline-layer", List.of("inline-" + i)); }
        r = edit(r, r.source(), extras);
        r = new Rule(r.id(), r.uuid(), r.number(), r.name(), r.enabled(), r.source(), r.destination(), r.service(), r.application(), action, log, r.comment(), r.extras());
        if (layer == 1) r = inLayer(r, "LAYER-BRAVO");
        return new Placed(r, kind, layer, section);
    }
    /** Layer 0 is cut into two sections at a random point when asked; layer 1 (when present) is one section of another layer. */
    static List<Placed> layout(Random random, boolean coarse, boolean twoLayers, boolean twoSections) {
        List<Placed> out = new ArrayList<>();
        int n = 3 + random.nextInt(9), layerCut = twoLayers ? 1 + random.nextInt(n - 1) : n;
        int sectionCut = twoSections && layerCut > 1 ? 1 + random.nextInt(layerCut - 1) : layerCut;
        for (int i = 0; i < n; i++) {
            int layer = i < layerCut ? 0 : 1;
            out.add(placed(random, i, coarse, layer, layer == 1 ? 2 : i < sectionCut ? 0 : 1));
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

    // ---- the oracle ----
    record Packet(String gateway, List<Integer> point) {}
    static List<Packet> universe(Map<String, Set<List<Integer>>> pointsById) {
        Set<List<Integer>> all = new HashSet<>();
        pointsById.values().forEach(all::addAll);
        // Every point of the universe: points of "any" rules cover it; add them explicitly.
        for (int s = -1; s < PolicyUnionCoverDifferentialTest.HOSTS; s++) for (int d = -1; d < PolicyUnionCoverDifferentialTest.HOSTS; d++)
            for (int p : new int[]{-1, 1, 2, 3, 4}) all.add(List.of(s, d, p));
        List<Packet> out = new ArrayList<>();
        for (String g : List.of(G1, G2)) for (var point : all) out.add(new Packet(g, point));
        return out;
    }
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
        return a.rule().action().equals(b.rule().action()) && a.rule().extras().getOrDefault("inline-layer", List.of()).equals(b.rule().extras().getOrDefault("inline-layer", List.of()));
    }
    /** Full effect: the verdict, the profile references and a collected, equal log setting. */
    static boolean sameFull(Placed a, Placed b) {
        if (a == b) return true;
        if (!sameVerdict(a, b) || a.rule().action().equals("Apply Layer")) return false;
        return a.rule().extras().getOrDefault("profile-setting/group", List.of()).equals(b.rule().extras().getOrDefault("profile-setting/group", List.of()))
            && !"UNKNOWN".equals(a.rule().log()) && a.rule().log().equals(b.rule().log());
    }
    static boolean provenDisjoint(Placed a, Placed b) { return !a.installOn().equals(ALL) && !b.installOn().equals(ALL) && !a.installOn().equals(b.installOn()); }

    int safe, inspection, unsafe, anchorBlocked, crossesSection, exactChecks, changes;

    void check(List<Placed> rules, PolicyReorder.Result result, String label) {
        Map<String, Placed> byId = new HashMap<>();
        Map<String, Set<List<Integer>>> pointsById = new HashMap<>();
        for (var p : rules) { byId.put(p.rule().id(), p); pointsById.put(p.rule().id(), points(p.rule())); }
        var packets = universe(pointsById);
        for (var e : result.moves().entrySet()) {
            var move = e.getValue();
            Placed r = byId.get(e.getKey()), b = byId.get(move.above().rule().id());
            assertEquals(r.layer(), b.layer(), label);
            List<Placed> layer = rules.stream().filter(p -> p.layer() == r.layer()).toList();
            int ib = layer.indexOf(b), ir = layer.indexOf(r);
            assertTrue(ib < ir, label);
            var between = layer.subList(ib + 1, ir).stream().filter(Placed::enabled).toList();
            boolean anchorBetween = between.stream().anyMatch(Placed::anchor);
            boolean otherSection = b.section() != r.section();
            boolean differentEffect = between.stream().anyMatch(c -> !c.anchor() && !provenDisjoint(c, r)
                && !Collections.disjoint(pointsById.get(c.rule().id()), pointsById.get(r.rule().id())) && !PolicyEffectSignature.sameVerdict(c.rule(), r.rule()));
            switch (move.result()) {
                case PolicyReorder.ANCHOR_BLOCKED -> { anchorBlocked++; assertTrue(anchorBetween, label + " " + e.getKey()); continue; }
                case PolicyReorder.CROSSES_SECTION -> { crossesSection++; assertTrue(otherSection, label); assertFalse(anchorBetween, label); continue; }
                case PolicyReorder.UNSAFE_DIFFERENT_EFFECT -> { unsafe++; assertTrue(differentEffect, label + " " + e.getKey()); continue; }
                case PolicyReorder.NOT_PROVEN -> fail("no budget limit in this pass: " + label);
                default -> {}
            }
            assertFalse(anchorBetween, "never across an anchor: " + label);
            assertFalse(otherSection, "never across a section: " + label);
            assertFalse(differentEffect, "never across an overlapping rule of another verdict: " + label);
            assertTrue(PolicyReorder.OFFERED.contains(move.result()));
            if (move.result().equals(PolicyReorder.SAFE)) { safe++; assertEquals(PolicyReorder.EQUIVALENT, move.equivalence(), "SAFE implies EQUIVALENT"); }
            else inspection++;
            List<Placed> after = new ArrayList<>(layer);
            after.remove(r); after.add(after.indexOf(b), r);
            boolean exact = layer.subList(0, ir).stream().filter(Placed::enabled)
                .allMatch(p -> p.installOn().equals(r.installOn()) || provenDisjoint(p, r));
            for (boolean anchorsMatch : new boolean[]{false, true}) {
                Set<String> oldDeciders = new TreeSet<>();
                boolean fullEffectKept = true;
                for (Packet packet : packets) {
                    Placed before = decider(layer, packet, anchorsMatch, pointsById), now = decider(after, packet, anchorsMatch, pointsById);
                    assertTrue(sameVerdict(before, now), "a verdict changed: " + label + " " + e.getKey() + " " + packet);
                    if (before == now) continue;
                    assertSame(r, now, "only the promoted rule takes packets over: " + label);
                    oldDeciders.add(before.rule().id());
                    if (!sameFull(before, now)) fullEffectKept = false;
                    if (move.result().equals(PolicyReorder.SAFE)) assertTrue(sameFull(before, now), "SAFE changed an effect: " + label + " " + packet);
                }
                Set<String> deciding = new TreeSet<>(move.deciding().stream().map(Rule::id).toList());
                assertTrue(deciding.containsAll(oldDeciders), "every old decider is reported: " + label + " " + oldDeciders + " " + deciding);
                if (move.equivalence().equals(PolicyReorder.EQUIVALENT)) assertTrue(fullEffectKept, "EQUIVALENT but an effect changed: " + label);
                if (!anchorsMatch && exact) {
                    exactChecks++;
                    assertEquals(oldDeciders, deciding, "the changed-deciding-rule set: " + label + " " + e.getKey());
                    assertEquals(fullEffectKept, move.equivalence().equals(PolicyReorder.EQUIVALENT), "equivalence: " + label + " " + e.getKey());
                    if (!fullEffectKept) changes++;
                }
            }
        }
    }

    static PolicyReorder.Result analyze(PolicySnapshot snapshot) {
        var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250000);
        var union = PolicyUnionCover.analyze(snapshot, hygiene);
        return PolicyReorder.analyze(snapshot, PolicyPartialShadow.analyze(snapshot, hygiene, union));
    }

    @Test void everyMoveAgreesWithTheBruteForceOracleOnRandomCheckPointRulebases() {
        long started = System.nanoTime();
        List<Long> seeds = new ArrayList<>(List.of(1126L, 1127L, 1128L, 2026L, 7L));
        var more = new Random(0x17L);
        for (int i = 0; i < 3; i++) seeds.add(more.nextLong());
        int trials = 0;
        for (long seed : seeds) {
            var random = new Random(seed);
            for (int trial = 0; trial < 220; trial++, trials++) {
                var rules = layout(random, trial % 2 == 0, trial % 3 == 0, trial % 4 == 1);
                var snapshot = snapshot(rules);
                var result = analyze(snapshot);
                String label = "seed " + seed + " trial " + trial;
                // One move per I4 rule, and the counts add up.
                var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250000);
                var partial = PolicyPartialShadow.analyze(snapshot, hygiene, PolicyUnionCover.analyze(snapshot, hygiene));
                var i4 = partial.rules().entrySet().stream().filter(x -> x.getValue().status().equals(PolicyPartialShadow.PARTIAL) && x.getValue().sameAction()).map(Map.Entry::getKey).sorted().toList();
                assertEquals(i4, result.moves().keySet().stream().sorted().toList(), label);
                assertEquals(result.evaluated(), result.safe() + result.safeInspectionChanges() + result.unsafeDifferentEffect() + result.anchorBlocked()
                    + result.crossesSection() + result.notProven(), label);
                check(rules, result, label);
            }
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("I7 differential: " + trials + " rulebases, safe " + safe + ", inspection changes " + inspection + ", unsafe " + unsafe
            + ", anchor blocked " + anchorBlocked + ", crosses section " + crossesSection + ", exact region checks " + exactChecks + " (" + changes + " CHANGES), " + millis + " ms");
        assertTrue(safe >= 100, "SAFE moves exercised: " + safe);
        assertTrue(inspection >= 30, "inspection-change moves exercised: " + inspection);
        assertTrue(unsafe >= 5, "unsafe moves exercised: " + unsafe);
        assertTrue(anchorBlocked >= 20, "anchor-blocked moves exercised: " + anchorBlocked);
        assertTrue(crossesSection >= 10, "section-crossing moves exercised: " + crossesSection);
        assertTrue(changes >= 10, "CHANGES verdicts checked exactly: " + changes);
        assertTrue(millis < 20_000, "took " + millis + " ms");
    }

    @Test void smallBudgetsGiveNotProvenAndNeverAWrongSafeMove() {
        var random = new Random(1129L);
        int notProven = 0, proven = 0;
        for (int trial = 0; trial < 120; trial++) {
            var rules = layout(random, trial % 2 == 0, trial % 3 == 0, trial % 4 == 1);
            var snapshot = snapshot(rules);
            var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250000);
            var partial = PolicyPartialShadow.analyze(snapshot, hygiene, PolicyUnionCover.analyze(snapshot, hygiene));
            var full = PolicyReorder.analyze(snapshot, partial);
            for (int work : new int[]{0, 3, 8, 15, 30, 60, 120, 250, 600, 1500}) {
                for (int window : new int[]{work, Math.max(1, work / 4)}) {
                    var limited = PolicyReorder.analyze(snapshot, partial, work, window);
                    assertEquals(full.moves().keySet(), limited.moves().keySet());
                    for (var e : limited.moves().entrySet()) {
                        var move = e.getValue();
                        if (move.result().equals(PolicyReorder.NOT_PROVEN)) {
                            notProven++;
                            assertEquals(PolicyReorder.BUDGET, move.reason());
                            assertEquals(PolicyReorder.NOT_PROVEN, move.equivalence());
                            assertTrue(move.crossed().isEmpty());
                            continue;
                        }
                        proven++;
                        var truth = full.moves().get(e.getKey());
                        if (PolicyReorder.OFFERED.contains(move.result()) || PolicyReorder.OFFERED.contains(truth.result())) {
                            assertEquals(truth.result(), move.result(), "trial " + trial + " work " + work + " " + e.getKey());
                            assertEquals(truth.equivalence(), move.equivalence(), "trial " + trial + " work " + work);
                            assertEquals(truth.deciding(), move.deciding(), "trial " + trial + " work " + work);
                        } else if (!truth.result().equals(move.result())) {
                            // Both refuse. An anchor left unread for the budget is not seen, so the structural section check
                            // may speak first: still a refusal, never an offer.
                            assertEquals(PolicyReorder.ANCHOR_BLOCKED, truth.result(), "trial " + trial + " work " + work);
                            assertEquals(PolicyReorder.CROSSES_SECTION, move.result(), "trial " + trial + " work " + work);
                        }
                    }
                    assertEquals(limited.moves().values().stream().filter(m -> m.result().equals(PolicyReorder.NOT_PROVEN)).count(), limited.notProven());
                }
            }
        }
        assertTrue(notProven >= 50, "budget exhaustion exercised: " + notProven);
        assertTrue(proven >= 100, "proven moves under a budget exercised: " + proven);
    }
}
