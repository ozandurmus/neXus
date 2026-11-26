package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.support.CpuTimeGuard;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

/**
 * Design section 9, phase P2 gate: the reorder-only proposal ({@link PolicyProposal}) over the same fully resolvable
 * 10,000-rule Check Point policy the shadow engine's, the I3, the I4 and the I7 scale tests pin, with every I7 candidate of
 * the stored order stacked. It re-runs the I3/I4/I7 passes on the current order after each accepted move (the proof of a
 * later move reads the earlier ones, design 6.1), so its cost is the number of accepted moves times one pass.
 *
 * Counts, exact: 21 I4 rules, 21 steps, all 21 accepted (SAFE, EQUIVALENT), no refusal, no budget stop; the final snapshot
 * holds the same 10,000 rules in one section with the slot numbers 1..10,000 and the stored snapshot is untouched.
 *
 * Timing: this thread's CPU time (the proposal runs on the calling thread; a loaded host stretches wall time, not CPU
 * time). The check does not depend on machine speed: one proposal is compared with the rounds it ran times the CPU time of
 * one stored-order pass (hygiene, I3, I4, I7), measured in the same JVM after a warm-up, minimum of two samples.
 *
 * <p>One layer (this test): after an accepted move only the scope the move touched is analysed again, and the whole
 * 10,000-rule policy is one scope, so the proposal still costs about one whole pass per accepted move. Measured on a laptop
 * JVM, 2026-11-26: one pass 2.2-2.6 s, one proposal of 21 accepted moves 45-49 s CPU (50.0 s before the scope-local
 * passes), ratio 0.8-1.1: no gain on a single-layer policy, and no loss. A return to extra passes per step (a pass per
 * refusal, or per subject, instead of per accepted move) or to a copy of the whole policy per move would push it past 1.5.
 *
 * <p>Five layers ({@link #theSameRulesInFiveLayersCostClearlyLessThanAPassPerAcceptedMove}): the same rules in five layers
 * of two sections, five I4 pairs planted per layer so that moves fall in every layer. The scope-local proposal is compared
 * with the whole-snapshot passes on the same snapshot ({@link PolicyProposal#proposeReference}), which must give the same
 * result. Measured: 27 accepted moves, one whole pass 0.7 s, scope-local 4.2 s against 18.4 s, ratio 0.23 (the layers were
 * analysed 6, 8, 6, 6 and 5 times against 28 each). The limit 0.5 only stops a return to whole passes.
 */
class PolicyProposalScaleTest {
    static final double MAX_RATIO_TO_ROUNDS = 1.5;
    static final long MAX_CPU_MILLIS = 600_000;
    /** Five layers, moves spread over all of them: about (1 + moves / 5) / (1 + moves), measured 0.23 for 27 moves. */
    static final double MAX_RATIO_FIVE_LAYERS = 0.5;

    private static void pass(PolicySnapshot snapshot) {
        var hygiene = PolicyHygiene.analyze(snapshot, 90, Instant.EPOCH, PolicyHygiene.TOTAL_WORK);
        var union = PolicyUnionCover.analyze(snapshot, hygiene);
        PolicyReorder.analyze(snapshot, PolicyPartialShadow.analyze(snapshot, hygiene, union));
    }

    @Test void everyOfferedMoveOfTenThousandCheckPointRulesStacksWithinTheBudget() {
        var stored = SyntheticPolicies.large("CP", 10_000, 7);
        var sections = stored.sections(); var rules = sections.get(0).rules();
        long pass = CpuTimeGuard.minNanos(2, () -> pass(stored)); // warm-up included: the second sample is the warm one
        long started = CpuTimeGuard.nanos();
        var result = PolicyProposal.allOfferedMoves(stored);
        long nanos = CpuTimeGuard.nanos() - started;
        int rounds = result.accepted();
        double ratio = (double) nanos / Math.max(1L, rounds * pass);
        System.out.println("SCALE proposal CP 10000 rules: " + CpuTimeGuard.millis(nanos) + " ms " + (CpuTimeGuard.cpuTimed() ? "thread CPU" : "wall clock")
            + ", " + result.steps().size() + " steps, " + result.accepted() + " accepted, refused " + result.refused() + ", verdict " + result.packageVerdict()
            + ", one pass " + CpuTimeGuard.millis(pass) + " ms, ratio to " + rounds + " passes " + String.format("%.2f", ratio));
        assertNull(result.emptyReason());
        assertEquals(21, result.steps().size(), "one step per I4 rule");
        assertEquals(21, result.accepted(), "every move of this policy is SAFE alone and stays SAFE stacked");
        assertTrue(result.steps().stream().allMatch(s -> s.accepted() && PolicyReorder.SAFE.equals(s.result()) && PolicyReorder.EQUIVALENT.equals(s.equivalence())));
        assertTrue(result.refused().values().stream().allMatch(n -> n == 0), result.refused().toString());
        assertFalse(result.budgetStopped());
        assertFalse(result.inspectionChanges());
        assertEquals(PolicyReorder.EQUIVALENT, result.packageVerdict());
        assertEquals(PolicyProposal.LABEL_NO_CHANGE, result.label());
        // The final in-memory snapshot: the same rules, one section, slot numbers; the stored snapshot untouched.
        var finalRules = result.snapshot().sections().get(0).rules();
        assertEquals(1, result.snapshot().sections().size());
        assertEquals(10_000, finalRules.size());
        assertEquals(new HashSet<>(rules.stream().map(PolicySnapshot.Rule::id).toList()), new HashSet<>(finalRules.stream().map(PolicySnapshot.Rule::id).toList()));
        assertEquals(IntStream.rangeClosed(1, 10_000).boxed().toList(), finalRules.stream().map(PolicySnapshot.Rule::number).toList());
        assertSame(stored.metadata(), result.snapshot().metadata()); assertSame(stored.objects(), result.snapshot().objects());
        assertSame(sections, stored.sections()); assertSame(rules, stored.sections().get(0).rules());
        assertEquals("r0", rules.get(0).id()); assertEquals(1, rules.get(0).number());
        assertTrue(CpuTimeGuard.millis(nanos) < MAX_CPU_MILLIS, "took " + CpuTimeGuard.millis(nanos) + " ms CPU"); // blow-up guard
        assertTrue(ratio < MAX_RATIO_TO_ROUNDS, "one proposal must cost about one pass per accepted move: " + String.format("%.2f", ratio)
            + " (" + CpuTimeGuard.millis(nanos) + " ms for " + rounds + " passes of " + CpuTimeGuard.millis(pass) + " ms)");
    }
    /**
     * The 10,000 rules of the scale policy, cut into {@code layers} Check Point layers of two sections each (numbers still
     * strictly increasing, no rule added or dropped). The random rules of one 2,000-rule layer overlap too little to give a
     * layer more than a move or two, so {@code pairsPerLayer} I4 pairs per layer are planted in the second section of each
     * layer by replacing 2 x pairs of its rules: a narrow rule at one place and, 400 rules later, the broader rule (the
     * network the narrow host sits in) with the same destination, service and action, on addresses (198.18.0.0/16, a range
     * the random rules never use) that no other rule overlaps, so every promote is SAFE and crosses only disjoint rules.
     */
    static PolicySnapshot splitIntoLayers(PolicySnapshot single, int layers, int pairsPerLayer) {
        var rules = single.sections().get(0).rules();
        var objects = new HashMap<>(single.objects());
        int per = rules.size() / layers;
        List<PolicySnapshot.Section> sections = new ArrayList<>();
        int pair = 0;
        for (int layer = 0; layer < layers; layer++) {
            int from = layer * per, to = layer == layers - 1 ? rules.size() : from + per, cut = from + (to - from) / 2;
            Map<Integer, PolicySnapshot.Rule> planted = new HashMap<>();
            for (int j = 0; j < pairsPerLayer; j++, pair++) {
                objects.put("pl-host-" + pair, SyntheticPolicies.object("pl-host-" + pair, "host", "ipv4-address: 198.18.0." + (4 * pair + 1)));
                objects.put("pl-net-" + pair, SyntheticPolicies.object("pl-net-" + pair, "network", "ip-netmask: 198.18.0." + (4 * pair) + "/30"));
                objects.put("pl-dst-" + pair, SyntheticPolicies.object("pl-dst-" + pair, "host", "ipv4-address: 198.18.1." + pair));
                int narrow = cut + 20 + j * 7, broad = narrow + 400;
                var narrowSlot = rules.get(narrow); var broadSlot = rules.get(broad);
                planted.put(narrow, plantedRule(narrowSlot, "pl-host-" + pair, "pl-dst-" + pair, narrowSlot.service()));
                planted.put(broad, plantedRule(broadSlot, "pl-net-" + pair, "pl-dst-" + pair, narrowSlot.service()));
            }
            for (int part = 0; part < 2; part++) {
                List<PolicySnapshot.Rule> own = new ArrayList<>();
                for (int i = part == 0 ? from : cut; i < (part == 0 ? cut : to); i++) {
                    var r = planted.getOrDefault(i, rules.get(i));
                    var extras = new HashMap<>(r.extras()); extras.put("layer-name", List.of("LAYER-" + layer));
                    own.add(new PolicySnapshot.Rule(r.id(), r.uuid(), r.number(), r.name(), r.enabled(), r.source(), r.destination(), r.service(), r.application(), r.action(), r.log(), r.comment(), extras));
                }
                sections.add(new PolicySnapshot.Section("sec-" + layer + "-" + part, "OBJ-SECTION-" + layer + part, "CP access layer", null, own));
            }
        }
        return new PolicySnapshot(single.metadata(), sections, objects);
    }
    private static PolicySnapshot.Rule plantedRule(PolicySnapshot.Rule slot, String source, String destination, PolicySnapshot.Cell service) {
        return new PolicySnapshot.Rule(slot.id(), slot.uuid(), slot.number(), slot.name(), true, new PolicySnapshot.Cell(List.of(source), false),
            new PolicySnapshot.Cell(List.of(destination), false), service, slot.application(), "Accept", "Log", "", slot.extras());
    }

    @Test void theSameRulesInFiveLayersCostClearlyLessThanAPassPerAcceptedMove() {
        var stored = splitIntoLayers(SyntheticPolicies.large("CP", 10_000, 7), 5, 5);
        long pass = CpuTimeGuard.minNanos(2, () -> pass(stored));
        // The reference first (it also warms the JVM), then the scope-local run, each timed on this thread.
        long referenceStarted = CpuTimeGuard.nanos();
        var reference = PolicyProposal.proposeReference(stored, null, PolicyProposal.TOTAL_WORK, PolicyProposal.RULE_WORK);
        long referenceNanos = CpuTimeGuard.nanos() - referenceStarted;
        var stats = new PolicyProposal.Stats();
        long started = CpuTimeGuard.nanos();
        var result = PolicyProposal.propose(stored, null, PolicyProposal.TOTAL_WORK, PolicyProposal.RULE_WORK, true, stats);
        long nanos = CpuTimeGuard.nanos() - started;
        int rounds = result.accepted();
        double ratioToPasses = (double) nanos / Math.max(1L, rounds * pass), ratioToReference = (double) nanos / Math.max(1L, referenceNanos);
        int candidateRuns = Arrays.stream(stats.candidateRuns).sum(), reorderRuns = Arrays.stream(stats.reorderRuns).sum();
        System.out.println("SCALE proposal CP 10000 rules in 5 layers: scope-local " + CpuTimeGuard.millis(nanos) + " ms, reference " + CpuTimeGuard.millis(referenceNanos) + " ms ("
            + (CpuTimeGuard.cpuTimed() ? "thread CPU" : "wall clock") + "), " + result.steps().size() + " steps, " + rounds + " accepted, one whole pass " + CpuTimeGuard.millis(pass)
            + " ms, ratio to " + rounds + " whole passes " + String.format("%.2f", ratioToPasses) + ", ratio to the reference " + String.format("%.2f", ratioToReference)
            + ", I3/I4 scope runs " + Arrays.toString(stats.candidateRuns) + ", I7 scope runs " + Arrays.toString(stats.reorderRuns) + ", fallbacks " + stats.fallbacks);
        assertEquals(reference, result, "the same result as the whole-snapshot passes");
        assertEquals(5, stats.scopes);
        assertEquals(0, stats.fallbacks); assertEquals(0, stats.wholeCandidatePasses + stats.wholeReorderPasses);
        assertTrue(rounds >= 5, "enough accepted moves for the reuse to show: " + rounds);
        assertTrue(candidateRuns < (rounds + 1) * 5 && reorderRuns < (rounds + 1) * 5, "layers were reused: " + candidateRuns + " / " + reorderRuns);
        assertTrue(ratioToReference < MAX_RATIO_FIVE_LAYERS, "five layers: the scope-local proposal must cost clearly less than the whole-snapshot passes: " + String.format("%.2f", ratioToReference));
        assertTrue(ratioToPasses < MAX_RATIO_FIVE_LAYERS, "and less than half of one whole pass per accepted move: " + String.format("%.2f", ratioToPasses));
    }
}
