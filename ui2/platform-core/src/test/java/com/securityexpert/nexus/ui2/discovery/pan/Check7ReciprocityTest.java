package com.securityexpert.nexus.ui2.discovery.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Contract §11 check 7 (AC-1) — THE DECISIVE TEST. Five separate cases over
 * one constructed input set: HA-1's reciprocal pair is {@code PAIRED};
 * HA-2's dangling claim, HA-3's self-referential claim and HA-4's one-sided
 * claim are each {@code NOT_EVALUABLE} with the matching reason; and the
 * fifth case proves HA-4a/HA-4b — a one-sided claim aimed at a member of an
 * otherwise reciprocal pair leaves that pair {@code PAIRED} and surfaces
 * the inbound claim separately, rather than collapsing the pair.
 */
class Check7ReciprocityTest {

    private static Map<String, CandidateRow> byId(List<CandidateRow> rows) {
        return rows.stream().collect(Collectors.toMap(r -> r.stableIdentifier().value().orElseThrow(), Function.identity()));
    }

    @Test
    void case1ReciprocalPairIsPaired() {
        Map<String, CandidateRow> rows = byId(CandidateRowAssembler.assemble(Fixtures.check7Inputs()));

        PairingOutcome.Paired pair1 = assertInstanceOf(PairingOutcome.Paired.class, rows.get("fixture-pair-1").pairingOutcome());
        assertEquals("fixture-pair-2", pair1.peerStableIdentifier());
        PairingOutcome.Paired pair2 = assertInstanceOf(PairingOutcome.Paired.class, rows.get("fixture-pair-2").pairingOutcome());
        assertEquals("fixture-pair-1", pair2.peerStableIdentifier());
    }

    @Test
    void case2DanglingClaimIsNotEvaluablePeerNotFound() {
        Map<String, CandidateRow> rows = byId(CandidateRowAssembler.assemble(Fixtures.check7Inputs()));

        PairingOutcome.NotEvaluable outcome =
                assertInstanceOf(PairingOutcome.NotEvaluable.class, rows.get("fixture-dangling").pairingOutcome());
        assertEquals(PairingOutcome.Reason.PEER_NOT_FOUND, outcome.reason());
    }

    @Test
    void case3SelfReferentialClaimIsNotEvaluableSelfReferential() {
        Map<String, CandidateRow> rows = byId(CandidateRowAssembler.assemble(Fixtures.check7Inputs()));

        PairingOutcome.NotEvaluable outcome =
                assertInstanceOf(PairingOutcome.NotEvaluable.class, rows.get("fixture-self").pairingOutcome());
        assertEquals(PairingOutcome.Reason.SELF_REFERENTIAL, outcome.reason());
    }

    @Test
    void case4OneSidedClaimIsNotEvaluableOneSidedForTheClaimantOnly() {
        Map<String, CandidateRow> rows = byId(CandidateRowAssembler.assemble(Fixtures.check7Inputs()));

        PairingOutcome.NotEvaluable claimantOutcome =
                assertInstanceOf(PairingOutcome.NotEvaluable.class, rows.get("fixture-onesided-claimant").pairingOutcome());
        assertEquals(PairingOutcome.Reason.ONE_SIDED_CLAIM, claimantOutcome.reason());

        // The target never claimed anyone — it carries no peer-serial claim at all, and no pair
        // formed from the claimant's side alone (HA-1 has no fallback).
        PairingOutcome.NotEvaluable targetOutcome =
                assertInstanceOf(PairingOutcome.NotEvaluable.class, rows.get("fixture-onesided-target").pairingOutcome());
        assertEquals(PairingOutcome.Reason.NO_PEER_SERIAL, targetOutcome.reason());
    }

    /**
     * Case 5, THE DECISIVE CASE (HA-4a/HA-4b). An implementation that marks
     * {@code fixture-reciprocal-b} {@code NOT_EVALUABLE} merely because it was
     * named by {@code fixture-inbound-claimant} has let an uncorroborated
     * claim override a corroborated one — exactly the defect §7's review
     * corrected HA-4 to forbid.
     */
    @Test
    void case5UncorroboratedInboundClaimDoesNotCollapseTheReciprocalPair() {
        Map<String, CandidateRow> rows = byId(CandidateRowAssembler.assemble(Fixtures.check7Inputs()));

        // The claimant's own outcome is ONE_SIDED_CLAIM: a fact about the claimant, not about B (HA-4).
        PairingOutcome.NotEvaluable claimantOutcome =
                assertInstanceOf(PairingOutcome.NotEvaluable.class, rows.get("fixture-inbound-claimant").pairingOutcome());
        assertEquals(PairingOutcome.Reason.ONE_SIDED_CLAIM, claimantOutcome.reason());

        // HA-4a: the B-C pair stands.
        CandidateRow b = rows.get("fixture-reciprocal-b");
        CandidateRow c = rows.get("fixture-reciprocal-c");
        PairingOutcome.Paired bOutcome = assertInstanceOf(PairingOutcome.Paired.class, b.pairingOutcome());
        assertEquals("fixture-reciprocal-c", bOutcome.peerStableIdentifier());
        PairingOutcome.Paired cOutcome = assertInstanceOf(PairingOutcome.Paired.class, c.pairingOutcome());
        assertEquals("fixture-reciprocal-b", cOutcome.peerStableIdentifier());

        // HA-4b: B additionally, separately, carries the unreciprocated inbound claim.
        assertEquals(List.of("fixture-inbound-claimant"), b.unreciprocatedInboundClaimantSerials());
        // C was never claimed, so it carries none.
        assertTrue(c.unreciprocatedInboundClaimantSerials().isEmpty());
    }

    @Test
    void everyCandidateIsReturnedWithAnOutcome() {
        List<CandidateRow> rows = CandidateRowAssembler.assemble(Fixtures.check7Inputs());
        assertEquals(Fixtures.check7Inputs().size(), rows.size());
        for (CandidateRow row : rows) {
            assertTrue(row.pairingOutcome() instanceof PairingOutcome.Paired
                    || row.pairingOutcome() instanceof PairingOutcome.NotEvaluable);
        }
    }
}
