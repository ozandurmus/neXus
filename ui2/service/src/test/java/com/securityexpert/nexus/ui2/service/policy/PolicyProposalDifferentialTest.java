package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.inLayer;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.edit;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.object;
import static com.securityexpert.nexus.ui2.service.policy.PolicyUnionCoverDifferentialTest.points;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Design section 9, phase P2 "differential tests" for the reorder-only proposal ({@link PolicyProposal}): random small Check
 * Point rulebases over the I3/I4/I7 universe (eight documentation hosts, four TCP ports, one outside value per axis) in one
 * or two layers, a layer sometimes cut into two sections, with anchors (time, unresolved object, Apply Layer), disabled
 * rules, mixed actions, mixed profile and log settings and three install-on placements. (The generator is the idea of
 * {@code PolicyReorderDifferentialTest}, copied here; that file is not touched.) Per rulebase every I7 candidate is proposed
 * in rule order ({@code allOfferedMoves}) and, in a second run, in a random order and subset. A brute-force oracle then
 * compares the ORIGINAL order with the FINAL in-memory snapshot, per gateway and per packet, in two worlds (every anchor
 * matching its cells, or none), and holds the engine to it:
 * <ul>
 * <li>a proposal of SAFE steps only changes no packet's verdict or full effect; with SAFE_INSPECTION_CHANGES steps no
 * packet's verdict changes;</li>
 * <li>no rule crossed an anchor, a layer or a section: sections and layers keep their rules, and the order of an anchor and
 * any other rule of its layer is the stored one;</li>
 * <li>{@code packageVerdict} EQUIVALENT agrees with the oracle (never EQUIVALENT when a full effect changed), and a label
 * "no behaviour change" is only given to SAFE steps only;</li>
 * <li>the final snapshot keeps strictly increasing numbers per layer, so the evaluator still proves its order;</li>
 * <li>with a small budget the run is NOT_PROVEN from some step on, and before that the steps are the full run's: never an
 * accepted move the full budget refuses.</li>
 * </ul>
 * A fixed seed list plus a bounded random loop. Synthetic values only.
 */
class PolicyProposalDifferentialTest {
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
    static List<Packet> universe() {
        Set<List<Integer>> all = new HashSet<>();
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

    static final List<Packet> PACKETS = universe();
    int proposals, accepted, stackedSteps, refusedSteps, eqPackages, changesPackages, inspectionPackages, mixedLabels, noMovePackages;
    final Map<String, Integer> refusedByReason = new TreeMap<>();

    /** The rules of one layer in the order of a snapshot. */
    static List<Placed> layerOrder(PolicySnapshot snapshot, Map<String, Placed> byId, int layer) {
        return snapshot.sections().stream().flatMap(s -> s.rules().stream()).map(r -> byId.get(r.id())).filter(p -> p.layer() == layer).toList();
    }

    void check(List<Placed> rules, PolicySnapshot stored, PolicyProposal.Result result, String label) {
        Map<String, Placed> byId = new HashMap<>();
        Map<String, Set<List<Integer>>> pointsById = new HashMap<>();
        for (var p : rules) { byId.put(p.rule().id(), p); pointsById.put(p.rule().id(), points(p.rule())); }
        proposals++;
        assertEquals(result.accepted(), result.steps().stream().filter(PolicyProposal.Step::accepted).count(), label);
        assertEquals(result.steps().size() - result.accepted(), result.refused().values().stream().mapToInt(Integer::intValue).sum(), label);
        var final_ = result.snapshot();
        if (result.accepted() == 0) { assertSame(stored, final_, label); noMovePackages++; }
        // Sections and layers keep exactly their rules; the stored snapshot is untouched in every other respect.
        assertEquals(stored.sections().size(), final_.sections().size(), label);
        for (int s = 0; s < stored.sections().size(); s++) {
            assertEquals(new TreeSet<>(stored.sections().get(s).rules().stream().map(Rule::id).toList()),
                new TreeSet<>(final_.sections().get(s).rules().stream().map(Rule::id).toList()), "a rule left its section: " + label);
            assertEquals(stored.sections().get(s).id(), final_.sections().get(s).id(), label);
        }
        assertSame(stored.metadata(), final_.metadata()); assertSame(stored.objects(), final_.objects());
        boolean allSafe = result.steps().stream().filter(PolicyProposal.Step::accepted).allMatch(s -> PolicyReorder.SAFE.equals(s.result()));
        assertEquals(result.steps().stream().anyMatch(s -> s.accepted() && PolicyReorder.SAFE_INSPECTION_CHANGES.equals(s.result())), result.inspectionChanges(), label);
        if (PolicyProposal.LABEL_NO_CHANGE.equals(result.label())) { assertTrue(allSafe && result.accepted() > 0, "no behaviour change only for SAFE steps: " + label); assertEquals(PolicyReorder.EQUIVALENT, result.packageVerdict(), label); }
        if (result.inspectionChanges()) assertEquals(PolicyProposal.LABEL_INSPECTION_CHANGES, result.label(), label);
        if (result.accepted() > 0 && allSafe) assertEquals(PolicyReorder.EQUIVALENT, result.packageVerdict(), "SAFE steps compose to EQUIVALENT: " + label);
        if (result.accepted() > 1) stackedSteps += result.accepted();
        accepted += result.accepted();
        for (var step : result.steps()) if (!step.accepted()) { refusedSteps++; refusedByReason.merge(step.result(), 1, Integer::sum); }
        switch (result.packageVerdict()) {
            case PolicyReorder.EQUIVALENT -> { eqPackages++; if (result.inspectionChanges()) mixedLabels++; }
            case PolicyReorder.CHANGES -> changesPackages++;
            default -> {}
        }
        if (result.inspectionChanges()) inspectionPackages++;

        for (int layer : new int[]{0, 1}) {
            List<Placed> before = layerOrder(stored, byId, layer), after = layerOrder(final_, byId, layer);
            assertEquals(new TreeSet<>(before.stream().map(p -> p.rule().id()).toList()), new TreeSet<>(after.stream().map(p -> p.rule().id()).toList()), "a rule left its layer: " + label);
            // Never across an anchor: an anchor and any other rule of the layer keep their relative order.
            for (Placed a : before) {
                if (!a.anchor()) continue;
                for (Placed x : before) {
                    if (x == a) continue;
                    assertEquals(before.indexOf(a) < before.indexOf(x), after.indexOf(a) < after.indexOf(x), "an anchor was crossed: " + label + " " + a.rule().id() + " " + x.rule().id());
                }
            }
            // The numbers of the layer stay strictly increasing, in the final order, and the slots keep their numbers.
            List<Integer> numbers = final_.sections().stream().flatMap(s -> s.rules().stream()).filter(r -> byId.get(r.id()).layer() == layer).map(Rule::number).toList();
            for (int i = 1; i < numbers.size(); i++) assertTrue(numbers.get(i) > numbers.get(i - 1), "numbers: " + label);
            assertEquals(stored.sections().stream().flatMap(s -> s.rules().stream()).filter(r -> byId.get(r.id()).layer() == layer).map(Rule::number).toList(), numbers, "slot numbers: " + label);
            boolean fullEffectKept = true;
            for (boolean anchorsMatch : new boolean[]{false, true}) {
                for (Packet packet : PACKETS) {
                    Placed was = decider(before, packet, anchorsMatch, pointsById), now = decider(after, packet, anchorsMatch, pointsById);
                    assertTrue(sameVerdict(was, now), "a verdict changed: " + label + " " + packet);
                    if (!sameFull(was, now)) {
                        fullEffectKept = false;
                        assertFalse(allSafe && result.accepted() > 0, "a SAFE-only proposal changed a full effect: " + label + " " + packet);
                    }
                }
            }
            if (result.packageVerdict().equals(PolicyReorder.EQUIVALENT)) assertTrue(fullEffectKept, "EQUIVALENT but an effect changed (layer " + layer + "): " + label);
        }
        // Whatever the engine accepted, the in-memory snapshot is one the evaluator still reads in order: a re-run on it
        // finds the layer proven wherever the stored one was.
        var hygieneBefore = PolicyHygiene.analyze(stored, 90, PolicyHygieneTest.NOW, 250000);
        var hygieneAfter = PolicyHygiene.analyze(final_, 90, PolicyHygieneTest.NOW, 250000);
        assertEquals(PolicyUnionCover.analyze(stored, hygieneBefore).notEvaluable().getOrDefault(PolicyUnionCover.ORDER_UNPROVEN, 0) > 0,
            PolicyUnionCover.analyze(final_, hygieneAfter).notEvaluable().getOrDefault(PolicyUnionCover.ORDER_UNPROVEN, 0) > 0, "order proven as before: " + label);
    }

    @Test void everyProposalAgreesWithTheBruteForceOracleOnRandomCheckPointRulebases() {
        long started = System.nanoTime();
        List<Long> seeds = new ArrayList<>(List.of(1126L, 1127L, 1128L, 2026L, 7L));
        var more = new Random(0x2bL);
        for (int i = 0; i < 3; i++) seeds.add(more.nextLong());
        int trials = 0;
        for (long seed : seeds) {
            var random = new Random(seed);
            for (int trial = 0; trial < 160; trial++, trials++) {
                var rules = layout(random, trial % 2 == 0, trial % 3 == 0, trial % 4 == 1);
                var stored = snapshot(rules);
                String label = "seed " + seed + " trial " + trial;
                var all = PolicyProposal.allOfferedMoves(stored);
                check(rules, stored, all, label + " allOfferedMoves");
                // The same candidates in a random order and a random subset, plus ids that are no candidate.
                List<String> subjects = new ArrayList<>(all.steps().stream().map(PolicyProposal.Step::subject).toList());
                rules.stream().filter(p -> random.nextInt(5) == 0).forEach(p -> subjects.add(p.rule().id()));
                Collections.shuffle(subjects, random);
                var picked = subjects.stream().filter(s -> random.nextInt(4) != 0).toList();
                check(rules, stored, PolicyProposal.propose(stored, picked), label + " shuffled " + picked);
            }
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("Proposal differential: " + trials + " rulebases, " + proposals + " proposals, accepted steps " + accepted + " (" + stackedSteps + " in proposals of two or more), refused steps "
            + refusedSteps + " " + refusedByReason + ", EQUIVALENT packages " + eqPackages + " (" + mixedLabels + " inspection-labelled), CHANGES packages " + changesPackages
            + ", no move " + noMovePackages + ", " + millis + " ms");
        assertTrue(trials >= 1000, "rulebases: " + trials);
        assertTrue(accepted >= 300, "accepted steps exercised: " + accepted);
        assertTrue(stackedSteps >= 100, "stacked steps exercised: " + stackedSteps);
        assertTrue(inspectionPackages >= 40, "inspection-changing packages exercised: " + inspectionPackages);
        assertTrue(changesPackages >= 1, "CHANGES packages exercised: " + changesPackages);
        assertTrue(eqPackages >= 100, "EQUIVALENT packages exercised: " + eqPackages);
        assertTrue(refusedByReason.getOrDefault(PolicyReorder.UNSAFE_DIFFERENT_EFFECT, 0) >= 3, "unsafe refusals exercised: " + refusedByReason);
        assertTrue(refusedByReason.getOrDefault(PolicyReorder.ANCHOR_BLOCKED, 0) >= 20, "anchor refusals exercised: " + refusedByReason);
        assertTrue(refusedByReason.getOrDefault(PolicyReorder.CROSSES_SECTION, 0) >= 10, "section refusals exercised: " + refusedByReason);
        assertTrue(refusedByReason.getOrDefault(PolicyProposal.NO_LONGER_A_CANDIDATE, 0) >= 5, "no-longer-a-candidate exercised: " + refusedByReason);
        assertTrue(millis < 120_000, "took " + millis + " ms");
    }

    @Test void smallBudgetsStopWithNotProvenAndNeverAcceptWhatTheFullBudgetRefuses() {
        var random = new Random(1129L);
        int stopped = 0, midRun = 0, complete = 0, comparedSteps = 0;
        for (int trial = 0; trial < 120; trial++) {
            var rules = layout(random, trial % 2 == 0, trial % 3 == 0, trial % 4 == 1);
            var stored = snapshot(rules);
            var full = PolicyProposal.allOfferedMoves(stored);
            for (int work : new int[]{0, 3, 8, 15, 30, 60, 120, 250, 600, 1500, 4000}) {
                for (int window : new int[]{work, Math.max(1, work / 4)}) {
                    var limited = PolicyProposal.allOfferedMoves(stored, work, window);
                    assertEquals(full.steps().size(), limited.steps().size(), "no silent skip: trial " + trial + " work " + work);
                    assertEquals(full.emptyReason(), limited.emptyReason());
                    int firstUnproven = limited.steps().size();
                    for (int i = 0; i < limited.steps().size(); i++) if (limited.steps().get(i).result().equals(PolicyReorder.NOT_PROVEN)) { firstUnproven = i; break; }
                    for (int i = 0; i < firstUnproven; i++) {
                        var a = limited.steps().get(i); var b = full.steps().get(i);
                        comparedSteps++;
                        assertEquals(b.subject(), a.subject());
                        assertEquals(b.accepted(), a.accepted(), "trial " + trial + " work " + work + " step " + i + " " + a + " vs " + b);
                        if (a.accepted()) { assertEquals(b.above(), a.above()); assertEquals(b.result(), a.result()); assertEquals(b.equivalence(), a.equivalence()); }
                    }
                    for (int i = firstUnproven; i < limited.steps().size(); i++) {
                        var step = limited.steps().get(i);
                        assertEquals(PolicyReorder.NOT_PROVEN, step.result()); assertEquals(PolicyReorder.BUDGET, step.reason()); assertFalse(step.accepted());
                    }
                    if (firstUnproven < limited.steps().size()) {
                        stopped++;
                        if (limited.accepted() > 0) midRun++;
                        assertTrue(limited.budgetStopped()); assertEquals(PolicyReorder.NOT_PROVEN, limited.packageVerdict()); assertNull(limited.label());
                        assertTrue(limited.accepted() <= full.accepted());
                    } else {
                        complete++;
                        assertFalse(limited.budgetStopped());
                        assertEquals(full.packageVerdict(), limited.packageVerdict());
                        assertEquals(full.snapshot().sections(), limited.snapshot().sections(), "a run that was not stopped is the full run");
                    }
                }
            }
        }
        assertTrue(stopped >= 100, "budget stops exercised: " + stopped);
        assertTrue(midRun >= 10, "stops after an accepted move exercised: " + midRun);
        assertTrue(complete >= 100 && comparedSteps >= 300, "complete runs " + complete + ", compared steps " + comparedSteps);
    }
}
