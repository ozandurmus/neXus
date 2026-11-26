package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.inLayer;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.edit;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.object;
import static com.securityexpert.nexus.ui2.service.policy.PolicyProposalMerges.Candidate;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Design section 9, phase P2 differential tests for {@link PolicyProposalMerges} (reorders followed by I5 merges):
 * random Check Point rulebases built with the generator of {@code PolicyProposalDifferentialTest} (copied here so that
 * existing test file is untouched), proposed both with all offered moves/merges and in random subsets and orders. A
 * brute-force packet oracle per gateway compares the original snapshot, the post-reorder snapshot and the final
 * post-merge snapshot in two worlds (every anchor matching its cells, or none):
 * <ul>
 * <li>merges never change any packet's verdict, profile or log setting relative to the post-reorder snapshot;</li>
 * <li>from original to final: no verdict changes, and when the combined verdict is EQUIVALENT (or SAFE-only) no full
 * effect (verdict, profile or log setting) changes either;</li>
 * <li>no anchor, layer or section boundary is crossed, no anchor is removed or altered, and surviving rule numbers stay
 * strictly increasing per layer;</li>
 * <li>small budgets give {@code NOT_PROVEN} (a prefix of the full run) and never accept what the full budget refuses.</li>
 * </ul>
 * Synthetic documentation values only.
 */
class PolicyProposalMergesDifferentialTest {
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
        Kind kind = !r.enabled() ? Kind.DISABLED : r.extras().get("time").equals(List.of("t1")) ? Kind.TIMED
            : !base.readable() ? Kind.UNRESOLVED : Kind.READABLE;
        int placement = random.nextInt(8);
        String installOn = placement < 6 ? G1 : placement == 6 ? G2 : ALL;
        int logChoice = random.nextInt(12);
        String log = logChoice < 9 ? "Log" : logChoice < 11 ? "None" : "UNKNOWN";
        Map<String, List<String>> extras = new HashMap<>(Map.of("install-on", List.of(installOn)));
        if (random.nextInt(7) == 0) extras.put("profile-setting/group", List.of("PROF-ALPHA"));
        String action = r.action();
        if (kind == Kind.READABLE && random.nextInt(12) == 0) {
            kind = Kind.APPLY_LAYER;
            action = "Apply Layer";
            extras.put("inline-layer", List.of("inline-" + i));
        }
        r = edit(r, r.source(), extras);
        r = new Rule(r.id(), r.uuid(), r.number(), r.name(), r.enabled(), r.source(), r.destination(), r.service(),
            r.application(), action, log, "COMMENT-r" + i, r.extras());
        if (layer == 1) r = inLayer(r, "LAYER-BRAVO");
        return new Placed(r, kind, layer, section);
    }

    static List<Placed> layout(Random random, boolean coarse, boolean twoLayers, boolean twoSections) {
        List<Placed> out = new ArrayList<>();
        int n = 4 + random.nextInt(9), layerCut = twoLayers ? 1 + random.nextInt(n - 1) : n;
        int sectionCut = twoSections && layerCut > 1 ? 1 + random.nextInt(layerCut - 1) : layerCut;
        for (int i = 0; i < n; i++) {
            int layer = i < layerCut ? 0 : 1;
            int section = layer == 1 ? 2 : i < sectionCut ? 0 : 1;
            if (!out.isEmpty() && out.get(out.size() - 1).kind() == Kind.READABLE
                    && out.get(out.size() - 1).layer() == layer && random.nextInt(4) == 0) {
                out.add(neighborVariant(random, i, coarse, layer, section, out.get(out.size() - 1)));
            } else {
                out.add(placed(random, i, coarse, layer, section));
            }
        }
        return out;
    }

    /** Biases occasional neighbors to differ in 0, 1 or 2 cells so chains and refusals are richly exercised alongside plain rules. */
    private static Placed neighborVariant(Random random, int i, boolean coarse, int layer, int section, Placed prev) {
        List<String> addresses = coarse ? List.of("n0", "n1", "n8", "g1")
            : new ArrayList<>(PolicyUnionCoverDifferentialTest.ADDRESSES.keySet());
        List<String> services = coarse ? List.of("p12", "p34", "p14")
            : new ArrayList<>(PolicyUnionCoverDifferentialTest.SERVICES.keySet());
        Rule p = prev.rule();
        Cell src = p.source(), dst = p.destination(), svc = p.service();
        int tweak = random.nextInt(5);
        if (tweak <= 1) src = new Cell(List.of(addresses.get(random.nextInt(addresses.size()))), false);
        else if (tweak == 2) dst = new Cell(List.of(addresses.get(random.nextInt(addresses.size()))), false);
        else if (tweak == 3) svc = new Cell(List.of(services.get(random.nextInt(services.size()))), false);
        else {
            src = new Cell(List.of(addresses.get(random.nextInt(addresses.size()))), false);
            dst = new Cell(List.of(addresses.get(random.nextInt(addresses.size()))), false);
        }
        String action = random.nextInt(9) == 0 ? (p.action().equals("Accept") ? "Drop" : "Accept") : p.action();
        String log = random.nextInt(9) == 0 ? (p.log().equals("Log") ? "None" : "UNKNOWN") : p.log();
        Map<String, List<String>> extras = new HashMap<>(p.extras());
        if (random.nextInt(9) == 0) extras.put("install-on", List.of(prev.installOn().equals(G1) ? G2 : G1));
        Rule r = new Rule("r" + i, "uuid-r" + i, i + 1, "OBJ-RULE-" + i, true, src, dst, svc, p.application(),
            action, log, "COMMENT-r" + i, extras);
        if (layer == 1) r = inLayer(r, "LAYER-BRAVO");
        return new Placed(r, Kind.READABLE, layer, section);
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

    // ---- brute-force packet-universe oracle (supports multi-ref union cells produced by merges) ----
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

    static Set<List<Integer>> pointsOf(Rule rule, Kind kind) {
        if (kind == Kind.UNRESOLVED) return Set.of(List.of(0, 0, 1));
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
        for (String g : List.of(G1, G2)) for (var point : all) out.add(new Packet(g, point));
        return out;
    }

    static final List<Packet> PACKETS = universe();

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
        if (a == null || b == null) return false;
        if (a.rule().id().equals(b.rule().id())) {
            return a.rule().action().equals(b.rule().action())
                && Objects.equals(a.rule().log(), b.rule().log())
                && a.rule().extras().equals(b.rule().extras());
        }
        if (!sameVerdict(a, b) || a.rule().action().equals("Apply Layer")) return false;
        return a.rule().extras().getOrDefault("profile-setting/group", List.of())
                .equals(b.rule().extras().getOrDefault("profile-setting/group", List.of()))
            && !"UNKNOWN".equals(a.rule().log()) && a.rule().log().equals(b.rule().log());
    }

    static List<Placed> layerOrder(PolicySnapshot snapshot, Map<String, Placed> metaById, int layer) {
        return snapshot.sections().stream()
            .flatMap(s -> s.rules().stream())
            .map(r -> {
                Placed base = metaById.get(r.id());
                return new Placed(r, base.kind(), base.layer(), base.section());
            })
            .filter(p -> p.layer() == layer)
            .toList();
    }

    static Map<String, Set<List<Integer>>> pointsMap(PolicySnapshot snapshot, Map<String, Placed> metaById) {
        Map<String, Set<List<Integer>>> map = new HashMap<>();
        for (Section s : snapshot.sections()) {
            for (Rule r : s.rules()) {
                map.put(r.id(), pointsOf(r, metaById.get(r.id()).kind()));
            }
        }
        return map;
    }

    int runs, acceptedMoves, acceptedMerges, chainMerges, postMoveOnlyMerges, refusedMerges;
    final Map<String, Integer> refusedByReason = new TreeMap<>();

    void check(List<Placed> rules, PolicySnapshot stored, PolicyProposal.Result proposal,
            PolicyProposalMerges.Result merges, String label) {
        runs++;
        Map<String, Placed> metaById = new HashMap<>();
        for (Placed p : rules) metaById.put(p.rule().id(), p);

        assertEquals(merges.accepted(), merges.steps().stream().filter(PolicyProposalMerges.Step::accepted).count(), label);
        assertEquals(merges.steps().size() - merges.accepted(),
            merges.refused().values().stream().mapToInt(Integer::intValue).sum(), label);

        PolicySnapshot mid = proposal.snapshot();
        PolicySnapshot end = merges.snapshot();
        if (merges.accepted() == 0) assertSame(mid, end, label);

        var combined = PolicyProposalMerges.combine(proposal, merges);
        assertFalse(combined.budgetStopped(), label);
        assertEquals(proposal.accepted(), combined.acceptedMoves(), label);
        assertEquals(merges.accepted(), combined.acceptedMerges(), label);
        assertSame(end, combined.snapshot(), label);

        acceptedMoves += proposal.accepted();
        acceptedMerges += merges.accepted();
        if (merges.accepted() >= 2) chainMerges += merges.accepted();
        for (var step : merges.steps()) {
            if (!step.accepted()) {
                refusedMerges++;
                refusedByReason.merge(step.result(), 1, Integer::sum);
            }
        }

        // Track merges that only became adjacent because of an accepted reorder move.
        Set<Candidate> preMoveCandidates = new HashSet<>(PolicyProposalMerges.candidatesOf(stored));
        for (var step : merges.steps()) {
            if (step.accepted() && !preMoveCandidates.contains(new Candidate(step.surviving(), step.merged()))) {
                postMoveOnlyMerges++;
            }
        }

        // Map every removed rule back to its ultimate survivor and verify section/layer/identity preservation.
        Map<String, String> mergedInto = new HashMap<>();
        for (var step : merges.steps()) {
            if (step.accepted()) mergedInto.put(step.merged(), step.surviving());
        }
        assertEquals(stored.sections().size(), end.sections().size(), label);
        for (int s = 0; s < stored.sections().size(); s++) {
            Set<String> storedIds = new LinkedHashSet<>(stored.sections().get(s).rules().stream().map(Rule::id).toList());
            Set<String> endIds = new LinkedHashSet<>(end.sections().get(s).rules().stream().map(Rule::id).toList());
            assertTrue(storedIds.containsAll(endIds), "a rule entered another section: " + label);
            Set<String> removedHere = new LinkedHashSet<>(storedIds);
            removedHere.removeAll(endIds);
            for (String rem : removedHere) {
                assertTrue(mergedInto.containsKey(rem), "unaccounted removed rule " + rem + ": " + label);
                String ult = rem;
                while (mergedInto.containsKey(ult)) ult = mergedInto.get(ult);
                assertTrue(endIds.contains(ult), "merged across section boundary: " + label);
            }
            for (Rule r : end.sections().get(s).rules()) {
                Rule orig = metaById.get(r.id()).rule();
                assertEquals(orig.uuid(), r.uuid(), label);
                assertEquals(orig.name(), r.name(), "name kept: " + label);
                assertEquals(orig.comment(), r.comment(), "comment not concatenated: " + label);
            }
        }

        var storedPts = pointsMap(stored, metaById);
        var midPts = pointsMap(mid, metaById);
        var endPts = pointsMap(end, metaById);

        for (int layer : new int[]{0, 1}) {
            List<Placed> before = layerOrder(stored, metaById, layer);
            List<Placed> afterMove = layerOrder(mid, metaById, layer);
            List<Placed> afterMerge = layerOrder(end, metaById, layer);

            // Surviving rule numbers stay strictly increasing inside every layer.
            List<Integer> nums = afterMerge.stream().map(p -> p.rule().number()).toList();
            for (int i = 1; i < nums.size(); i++) {
                assertTrue(nums.get(i) > nums.get(i - 1), "numbers not strictly increasing: " + label + " " + nums);
            }

            // Anchors are never removed, altered, or crossed by any surviving or merged rule.
            Map<String, Integer> beforeIdx = new HashMap<>(), afterIdx = new HashMap<>();
            for (int i = 0; i < before.size(); i++) beforeIdx.put(before.get(i).rule().id(), i);
            for (int i = 0; i < afterMerge.size(); i++) afterIdx.put(afterMerge.get(i).rule().id(), i);
            for (Placed a : before) {
                if (!a.anchor()) continue;
                assertTrue(afterIdx.containsKey(a.rule().id()), "anchor removed: " + label);
                int aBefore = beforeIdx.get(a.rule().id()), aAfter = afterIdx.get(a.rule().id());
                for (Placed x : before) {
                    if (x == a) continue;
                    String rep = x.rule().id();
                    while (mergedInto.containsKey(rep)) rep = mergedInto.get(rep);
                    assertEquals(aBefore < beforeIdx.get(x.rule().id()), aAfter < afterIdx.get(rep),
                        "anchor crossed: " + label + " " + a.rule().id() + " vs " + x.rule().id());
                }
            }

            boolean fullEffectKeptFromOriginal = true;
            for (boolean anchorsMatch : new boolean[]{false, true}) {
                for (Packet packet : PACKETS) {
                    Placed origDecider = decider(before, packet, anchorsMatch, storedPts);
                    Placed midDecider = decider(afterMove, packet, anchorsMatch, midPts);
                    Placed endDecider = decider(afterMerge, packet, anchorsMatch, endPts);

                    // Merges alone NEVER change verdict, profile, or log setting for any packet.
                    assertTrue(sameVerdict(midDecider, endDecider), "merge changed verdict: " + label + " " + packet);
                    assertTrue(sameFull(midDecider, endDecider), "merge changed log/profile effect: " + label + " " + packet);

                    // Across reorder + merges: verdict is always preserved; full effect agrees with combined verdict.
                    assertTrue(sameVerdict(origDecider, endDecider), "combined proposal changed verdict: " + label + " " + packet);
                    if (!sameFull(origDecider, endDecider)) {
                        fullEffectKeptFromOriginal = false;
                        assertTrue(proposal.inspectionChanges(), "full effect changed without inspectionChanges: " + label);
                    }
                }
            }
            if (PolicyReorder.EQUIVALENT.equals(combined.packageVerdict())) {
                assertTrue(fullEffectKeptFromOriginal, "combined EQUIVALENT but full effect changed: " + label);
            }
        }

        // EvaluationScope still proves the order wherever the stored snapshot did.
        var hBefore = PolicyHygiene.analyze(stored, 90, PolicyHygieneTest.NOW, 250_000);
        var hAfter = PolicyHygiene.analyze(end, 90, PolicyHygieneTest.NOW, 250_000);
        assertEquals(
            PolicyUnionCover.analyze(stored, hBefore).notEvaluable().getOrDefault(PolicyUnionCover.ORDER_UNPROVEN, 0) > 0,
            PolicyUnionCover.analyze(end, hAfter).notEvaluable().getOrDefault(PolicyUnionCover.ORDER_UNPROVEN, 0) > 0,
            "order proven status changed: " + label);
    }

    @Test void randomCheckPointRulebasesProposedInFullAndInRandomSubsetsAgreeWithTheBruteForceOracle() {
        List<Long> seeds = new ArrayList<>(List.of(1126L, 1127L, 1128L, 2026L, 7L));
        var more = new Random(0x5dL);
        for (int i = 0; i < 3; i++) seeds.add(more.nextLong());
        int trials = 0;
        for (long seed : seeds) {
            var random = new Random(seed);
            for (int trial = 0; trial < 160; trial++, trials++) {
                var rules = layout(random, trial % 2 == 0, trial % 3 == 0, trial % 4 == 1);
                var stored = snapshot(rules);
                String label = "seed " + seed + " trial " + trial;

                // 1. Full offered reorders, then full offered merges.
                var fullProposal = PolicyProposal.allOfferedMoves(stored);
                var fullMerges = PolicyProposalMerges.allOfferedMerges(fullProposal);
                check(rules, stored, fullProposal, fullMerges, label + " full");

                // 2. Random subset and order of reorders, then random subset and order of merges (plus non-candidates).
                List<String> moveSubjects = new ArrayList<>(fullProposal.steps().stream().map(PolicyProposal.Step::subject).toList());
                rules.stream().filter(p -> random.nextInt(5) == 0).forEach(p -> moveSubjects.add(p.rule().id()));
                Collections.shuffle(moveSubjects, random);
                var pickedMoves = moveSubjects.stream().filter(s -> random.nextInt(4) != 0).toList();
                var subProposal = PolicyProposal.propose(stored, pickedMoves);

                List<Candidate> mergeCandidates = new ArrayList<>(PolicyProposalMerges.candidatesOf(subProposal.snapshot()));
                var subRules = subProposal.snapshot().sections().stream().flatMap(s -> s.rules().stream()).toList();
                for (int i = 0; i + 1 < subRules.size(); i++) {
                    if (random.nextInt(4) == 0) {
                        mergeCandidates.add(new Candidate(subRules.get(i).id(), subRules.get(i + 1).id()));
                    }
                }
                if (subRules.size() >= 3 && random.nextInt(4) == 0) {
                    mergeCandidates.add(new Candidate(subRules.get(0).id(), subRules.get(subRules.size() - 1).id()));
                }
                Collections.shuffle(mergeCandidates, random);
                var pickedMerges = mergeCandidates.stream().filter(c -> random.nextInt(4) != 0).toList();
                var subMerges = PolicyProposalMerges.propose(subProposal, pickedMerges);
                check(rules, stored, subProposal, subMerges, label + " subset");
            }
        }
        System.out.println("Proposal+Merges differential: " + trials + " rulebases, " + runs + " runs, accepted moves "
            + acceptedMoves + ", accepted merges " + acceptedMerges + " (chain steps " + chainMerges
            + ", post-move-only " + postMoveOnlyMerges + "), refused merges " + refusedMerges + " " + refusedByReason);
        assertTrue(trials >= 1_000, "rulebases: " + trials);
        assertTrue(acceptedMoves >= 200, "accepted moves exercised: " + acceptedMoves);
        assertTrue(acceptedMerges >= 150, "accepted merges exercised: " + acceptedMerges);
        assertTrue(chainMerges >= 20, "multi-merge runs exercised: " + chainMerges);
        assertTrue(postMoveOnlyMerges >= 5, "post-move-only merges exercised: " + postMoveOnlyMerges);
        assertTrue(refusedByReason.getOrDefault(PolicyMerge.ANCHOR_BLOCKED, 0) >= 10, "anchor refusals: " + refusedByReason);
        assertTrue(refusedByReason.getOrDefault(PolicyMerge.DIFFERENT_EFFECT, 0) >= 10, "effect refusals: " + refusedByReason);
        assertTrue(refusedByReason.getOrDefault(PolicyMerge.DIFFERENT_INSTALL_ON, 0) >= 5, "install-on refusals: " + refusedByReason);
        assertTrue(refusedByReason.getOrDefault(PolicyMerge.DIFFERS_IN_SEVERAL_CELLS, 0) >= 10, "multi-cell refusals: " + refusedByReason);
        assertTrue(refusedByReason.getOrDefault(PolicyMerge.ALREADY_SHADOWED, 0) >= 5, "already-shadowed refusals: " + refusedByReason);
        assertTrue(refusedByReason.getOrDefault(PolicyProposalMerges.NO_LONGER_A_CANDIDATE, 0) >= 10, "non-adjacent refusals: " + refusedByReason);
    }

    @Test void smallBudgetsGiveNotProvenAndNeverAcceptWhatTheFullBudgetRefuses() {
        var random = new Random(1131L);
        int stopped = 0, midRun = 0, complete = 0, comparedSteps = 0;
        for (int trial = 0; trial < 140; trial++) {
            var rules = layout(random, trial % 2 == 0, trial % 3 == 0, trial % 4 == 1);
            var stored = snapshot(rules);
            var proposal = PolicyProposal.allOfferedMoves(stored);
            var full = PolicyProposalMerges.allOfferedMerges(proposal);
            for (int work : new int[]{0, 4, 12, 30, 55, 80, 105, 135, 170, 220, 280, 360, 500, 800, 2000}) {
                for (int window : new int[]{work, Math.max(1, work / 3)}) {
                    var limited = PolicyProposalMerges.allOfferedMerges(proposal, work, window);
                    assertEquals(full.steps().size(), limited.steps().size(), "trial " + trial + " work " + work);
                    assertEquals(full.emptyReason(), limited.emptyReason());
                    int firstUnproven = limited.steps().size();
                    for (int i = 0; i < limited.steps().size(); i++) {
                        if (PolicyMerge.NOT_PROVEN.equals(limited.steps().get(i).result())) {
                            firstUnproven = i;
                            break;
                        }
                    }
                    for (int i = 0; i < firstUnproven; i++) {
                        comparedSteps++;
                        assertEquals(full.steps().get(i), limited.steps().get(i),
                            "trial " + trial + " work " + work + " step " + i);
                    }
                    for (int i = firstUnproven; i < limited.steps().size(); i++) {
                        var step = limited.steps().get(i);
                        assertFalse(step.accepted());
                        assertEquals(PolicyMerge.NOT_PROVEN, step.result());
                        assertEquals(PolicyProposalMerges.BUDGET, step.reason());
                        assertEquals(PolicyMerge.NOT_PROVEN, step.equivalence());
                    }
                    if (firstUnproven < limited.steps().size()) {
                        stopped++;
                        if (limited.accepted() > 0) midRun++;
                        assertTrue(limited.budgetStopped());
                        var combined = PolicyProposalMerges.combine(proposal, limited);
                        assertEquals(PolicyReorder.NOT_PROVEN, combined.packageVerdict());
                        assertNull(combined.label());
                    } else {
                        complete++;
                        assertFalse(limited.budgetStopped());
                        assertEquals(full.snapshot().sections(), limited.snapshot().sections());
                    }
                }
            }
        }
        assertTrue(stopped >= 50, "budget stops exercised: " + stopped);
        assertTrue(midRun >= 5, "mid-run stops after an accepted merge exercised: " + midRun);
        assertTrue(complete >= 50 && comparedSteps >= 100, "complete " + complete + ", compared " + comparedSteps);
    }
}
