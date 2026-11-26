package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.support.CpuTimeGuard;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;

/**
 * Design section 9, phase P2 scale gate for {@link PolicyProposalMerges}: on the 10,000-rule Check Point policy of
 * {@link PolicyProposalScaleTest} (plus planted adjacent merge chains so multi-step merges run at 10,000-rule scale),
 * thread CPU time is bounded as a ratio against a measured single pass rather than a wall-clock constant, proving that
 * merges add no more than one extra {@link PolicyMerge} pass per accepted merge.
 */
class PolicyProposalMergesScaleTest {
    static final double MAX_RATIO_TO_ROUNDS = 2.5;
    static final long MAX_CPU_MILLIS = 600_000;

    private static void fullProposalPass(PolicySnapshot snapshot) {
        var hygiene = PolicyHygiene.analyze(snapshot, 90, Instant.EPOCH, PolicyHygiene.TOTAL_WORK);
        var union = PolicyUnionCover.analyze(snapshot, hygiene);
        PolicyReorder.analyze(snapshot, PolicyPartialShadow.analyze(snapshot, hygiene, union));
        PolicyMerge.analyze(snapshot);
    }

    /** Replaces four non-Drop slots with two adjacent pairs and one 3-rule chain (4 accepted merges at 10,000-rule scale). */
    private static PolicySnapshot withMergePairsAtScale(PolicySnapshot base) {
        Section section = base.sections().get(0);
        List<Rule> rules = new ArrayList<>(section.rules());
        // Slots 101..102 (pair 1), 2501..2502 (pair 2), and 7001..7003 (chain of 3 -> 2 merges): all Accept (not % 5 == 0).
        setMergeable(rules, 101, "192.0.2.1", "198.51.100.1", "svc1");
        setMergeable(rules, 102, "192.0.2.2", "198.51.100.1", "svc1");
        setMergeable(rules, 2501, "192.0.2.3", "198.51.100.2", "svc2");
        setMergeable(rules, 2502, "192.0.2.4", "198.51.100.2", "svc2");
        setMergeable(rules, 7001, "192.0.2.10", "198.51.100.3", "svc3");
        setMergeable(rules, 7002, "192.0.2.11", "198.51.100.3", "svc3");
        setMergeable(rules, 7003, "192.0.2.12", "198.51.100.3", "svc3");
        Section updated = new Section(section.id(), section.name(), section.source(), section.parentRuleId(), rules);
        return new PolicySnapshot(base.metadata(), List.of(updated), base.objects(), base.failures());
    }

    private static void setMergeable(List<Rule> rules, int index, String src, String dst, String svc) {
        Rule old = rules.get(index);
        rules.set(index, new Rule(old.id(), old.uuid(), old.number(), old.name(), true,
            new Cell(List.of(src), false), new Cell(List.of(dst), false), new Cell(List.of(svc), false),
            old.application(), "Accept", "Log", old.comment(), old.extras(), old.hitCounts()));
    }

    @Test void tenThousandRuleProposalWithMergesStaysWithinOneExtraPassPerAcceptedMerge() {
        var base = SyntheticPolicies.large("CP", 10_000, 7);

        // 1. Check the merge step alone at 10,000 rules with 4 accepted merges (including a 3-rule chain) vs one merge pass.
        var withMerges = withMergePairsAtScale(base);
        long singleMergePass = CpuTimeGuard.minNanos(3, () -> PolicyMerge.analyze(withMerges));
        long mergeStart = CpuTimeGuard.nanos();
        var mergeAtScale = PolicyProposalMerges.allOfferedMerges(withMerges);
        long mergeNanos = CpuTimeGuard.nanos() - mergeStart;
        int expectedMergePasses = 1 + mergeAtScale.accepted();
        double mergeRatio = (double) mergeNanos / Math.max(1L, expectedMergePasses * singleMergePass);

        assertTrue(mergeAtScale.accepted() >= 4, "planted merges accepted: " + mergeAtScale.accepted());
        assertFalse(mergeAtScale.budgetStopped());
        assertEquals(10_000 - mergeAtScale.accepted(), mergeAtScale.snapshot().sections().get(0).rules().size());
        List<Integer> mergedNumbers = mergeAtScale.snapshot().sections().get(0).rules().stream().map(Rule::number).toList();
        for (int i = 1; i < mergedNumbers.size(); i++) {
            assertTrue(mergedNumbers.get(i) > mergedNumbers.get(i - 1));
        }
        assertTrue(mergeRatio < MAX_RATIO_TO_ROUNDS,
            "merges must cost about one merge pass per accepted merge: ratio " + String.format("%.2f", mergeRatio)
                + " (" + CpuTimeGuard.millis(mergeNanos) + " ms vs " + expectedMergePasses + " * "
                + CpuTimeGuard.millis(singleMergePass) + " ms)");

        // 2. Full end-to-end reorder + merges on the standard 10,000-rule policy against a measured single full pass.
        long singleFullPass = CpuTimeGuard.minNanos(2, () -> fullProposalPass(base));
        long fullStart = CpuTimeGuard.nanos();
        var proposal = PolicyProposal.allOfferedMoves(base);
        var postMoveMerges = PolicyProposalMerges.allOfferedMerges(proposal);
        var combined = PolicyProposalMerges.combine(proposal, postMoveMerges);
        long fullNanos = CpuTimeGuard.nanos() - fullStart;
        int totalRounds = Math.max(1, proposal.accepted() + postMoveMerges.accepted());
        double fullRatio = (double) fullNanos / Math.max(1L, totalRounds * singleFullPass);

        System.out.println("SCALE proposal+merges CP 10000 rules: " + CpuTimeGuard.millis(fullNanos) + " ms "
            + (CpuTimeGuard.cpuTimed() ? "thread CPU" : "wall clock")
            + ", accepted moves " + proposal.accepted() + ", accepted merges " + postMoveMerges.accepted()
            + ", verdict " + combined.packageVerdict() + ", one pass " + CpuTimeGuard.millis(singleFullPass)
            + " ms, ratio " + String.format("%.2f", fullRatio)
            + "; merge-only 10000 rules (" + mergeAtScale.accepted() + " accepted): " + CpuTimeGuard.millis(mergeNanos)
            + " ms vs 1 pass " + CpuTimeGuard.millis(singleMergePass) + " ms, ratio " + String.format("%.2f", mergeRatio));

        assertEquals(21, proposal.accepted());
        assertFalse(proposal.budgetStopped());
        assertFalse(postMoveMerges.budgetStopped());
        assertEquals(PolicyReorder.EQUIVALENT, combined.packageVerdict());
        assertEquals(PolicyProposal.LABEL_NO_CHANGE, combined.label());
        assertEquals(10_000 - postMoveMerges.accepted(), combined.snapshot().sections().get(0).rules().size());
        assertSame(base.metadata(), combined.snapshot().metadata());
        assertSame(base.objects(), combined.snapshot().objects());
        assertTrue(CpuTimeGuard.millis(fullNanos) < MAX_CPU_MILLIS, "took " + CpuTimeGuard.millis(fullNanos) + " ms CPU");
        assertTrue(fullRatio < MAX_RATIO_TO_ROUNDS,
            "proposal + merges exceeded ratio bound: " + String.format("%.2f", fullRatio));
    }
}
