package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.cp;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.inLayer;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.cpSection;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Reuse is real: after an accepted move only the evaluation scope (layer) the move touched is analysed again, counted per
 * scope by {@link PolicyProposal.Stats}; a move in one section of a layer still analyses the later sections of that layer,
 * because what they are compared with changed; and the result is the whole-snapshot reference's either way.
 */
class PolicyProposalScopeLocalTest {
    static final int TOTAL = PolicyProposal.TOTAL_WORK, RULE = PolicyProposal.RULE_WORK;

    @Test void aMoveInLayerOneNeverAnalysesLayerTwoAgain() {
        var stored = PolicyProposalScopeLocalDifferentialTest.twoLayers();
        var stats = new PolicyProposal.Stats();
        var result = PolicyProposal.propose(stored, List.of("x", "sB"), TOTAL, RULE, true, stats);
        assertEquals(List.of(PolicyReorder.SAFE, PolicyReorder.SAFE), PolicyProposalTest.results(result), "sB is SAFE only after x moved: the proof reads the new order");
        assertEquals(2, stats.scopes);
        // Layer one: the first pass, then once more for the step after its accepted move. Layer two: the first pass only.
        assertArrayEquals(new int[]{2, 1}, stats.candidateRuns);
        assertArrayEquals(new int[]{2, 1}, stats.reorderRuns);
        assertEquals(0, stats.wholeCandidatePasses); assertEquals(0, stats.wholeReorderPasses); assertEquals(0, stats.fallbacks);
        assertEquals(PolicyProposal.proposeReference(stored, List.of("x", "sB"), TOTAL, RULE), result);
    }

    @Test void everyScopeIsAnalysedOnlyWhenItsOwnOrderChanged() {
        var stored = PolicyProposalScopeLocalDifferentialTest.twoLayers();
        var stats = new PolicyProposal.Stats();
        var result = PolicyProposal.propose(stored, null, TOTAL, RULE, true, stats);
        assertEquals(4, result.accepted());
        // Reference: a pass over both layers after each of the first three accepted moves and at the start (4 passes x 2 layers = 8).
        // Layer one moved twice and layer two twice; each layer is analysed once more per step after one of its own moves, and the
        // step after layer one's last move (layer two's first) reads layer one again.
        assertArrayEquals(new int[]{3, 2}, stats.candidateRuns);
        assertArrayEquals(new int[]{3, 2}, stats.reorderRuns);
        assertEquals(0, stats.wholeCandidatePasses + stats.wholeReorderPasses + stats.fallbacks);
        assertEquals(PolicyProposal.proposeReference(stored, null, TOTAL, RULE), result);
    }

    @Test void aMoveInASectionStillAnalysesTheLaterSectionsOfItsLayerAndNotTheOtherLayer() {
        // Layer ALPHA is two sections. sA (in s1) is promoted above bA; t (the whole /24, in s2) overlaps both, so its first
        // overlapping rule is bA before the move and sA after it: t's refusal (it would cross the section boundary) names the new one.
        var stored = PolicyProposalTest.snapshot(
            cpSection("s1", null, cp("bA", 1, "Accept", "lo-a", "fw-tango-04"), cp("sA", 2, "Accept", "lo", "fw-tango-04")),
            cpSection("s2", null, cp("t", 3, "Accept", "net", "fw-tango-04")),
            cpSection("b1", null, inLayer(cp("bB", 4, "Accept", "lo-a", "fw-tango-04"), "LAYER-BRAVO"), inLayer(cp("sB", 5, "Accept", "lo", "fw-tango-04"), "LAYER-BRAVO")));
        var stats = new PolicyProposal.Stats();
        var result = PolicyProposal.propose(stored, List.of("sA", "t"), TOTAL, RULE, true, stats);
        assertEquals(List.of(PolicyReorder.SAFE, PolicyReorder.CROSSES_SECTION), PolicyProposalTest.results(result));
        assertEquals("bA", result.steps().get(0).above());
        assertEquals("sA", result.steps().get(1).above(), "s2 was compared with s1's new order, not the stale one");
        assertEquals(2, stats.scopes);
        assertArrayEquals(new int[]{2, 1}, stats.candidateRuns, "the layer of the move again (both its sections), the other layer not");
        assertArrayEquals(new int[]{2, 1}, stats.reorderRuns);
        assertEquals(PolicyProposal.proposeReference(stored, List.of("sA", "t"), TOTAL, RULE), result);
        var before = PolicyProposal.propose(stored, List.of("t"), TOTAL, RULE);
        assertEquals("bA", before.steps().get(0).above(), "without the move, t's first overlapping rule is bA");
    }

    @Test void aSingleLayerIsOneScopeSoEveryAcceptedMoveAnalysesItAgain() {
        var stored = PolicyProposalTest.stackedRelease();
        var stats = new PolicyProposal.Stats();
        var result = PolicyProposal.propose(stored, null, TOTAL, RULE, true, stats);
        assertEquals(2, result.accepted());
        assertEquals(1, stats.scopes);
        assertArrayEquals(new int[]{2}, stats.candidateRuns, "the one layer: the first pass and the pass after the first accepted move");
        assertArrayEquals(new int[]{2}, stats.reorderRuns);
    }

    @Test void theReferenceNeverUsesScopes() {
        var stats = new PolicyProposal.Stats();
        PolicyProposal.propose(PolicyProposalScopeLocalDifferentialTest.twoLayers(), null, TOTAL, RULE, false, stats);
        assertEquals(0, stats.scopes);
        assertEquals(4, stats.wholeCandidatePasses, "a pass at the start and one after each of the first three accepted moves, which a later subject reads");
        assertEquals(4, stats.wholeReorderPasses);
    }
}
