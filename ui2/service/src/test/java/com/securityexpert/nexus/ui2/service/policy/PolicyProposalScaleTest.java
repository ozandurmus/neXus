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
 * one stored-order pass (hygiene, I3, I4, I7), measured in the same JVM after a warm-up, minimum of two samples. Measured
 * on a laptop JVM, 2026-11-26: one pass 2.4-3.0 s, one proposal of 21 accepted moves 50.0 s CPU, ratio 0.8-1.1 (the first
 * pass is the coldest, so the baseline is taken warm). A return to extra passes per step (a pass per refusal, or per
 * subject, instead of per accepted move) or to a copy of the whole policy per move would push it past 2, so the limit is
 * 2.5; the absolute CPU bound below only stops a blow-up.
 */
class PolicyProposalScaleTest {
    static final double MAX_RATIO_TO_ROUNDS = 2.5;
    static final long MAX_CPU_MILLIS = 600_000;

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
}
