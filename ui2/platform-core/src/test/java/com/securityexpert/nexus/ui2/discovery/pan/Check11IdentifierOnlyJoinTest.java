package com.securityexpert.nexus.ui2.discovery.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Contract §11 check 11 (AC-4) / ID-1, ID-2 identifier-only join. Serials
 * padded with surrounding whitespace still pair (check 11's trim-only
 * equality); a serial removed entirely yields {@code NOT_EVALUABLE} with
 * no name- or address-based fallback (there is none in this package — see
 * {@link PeerPairingResolver.PeerClaim}, which carries neither).
 */
class Check11IdentifierOnlyJoinTest {

    private static Map<String, CandidateRow> byTrimmedId(List<CandidateRow> rows) {
        return rows.stream().collect(Collectors.toMap(
                r -> r.stableIdentifier().value().orElseThrow().trim(), Function.identity()));
    }

    @Test
    void whitespacePaddedSerialsPairIdentically() {
        Map<String, CandidateRow> rows = byTrimmedId(CandidateRowAssembler.assemble(Fixtures.check11WhitespacePaddedPair()));

        // ID-1: the join decision trims surrounding whitespace, but the identifier itself is
        // never rewritten — the peer's identifier is carried exactly as that candidate gave it.
        PairingOutcome.Paired pair1 = assertInstanceOf(PairingOutcome.Paired.class, rows.get("fixture-pair-1").pairingOutcome());
        assertEquals("fixture-pair-2", pair1.peerStableIdentifier().trim());
        PairingOutcome.Paired pair2 = assertInstanceOf(PairingOutcome.Paired.class, rows.get("fixture-pair-2").pairingOutcome());
        assertEquals("fixture-pair-1", pair2.peerStableIdentifier().trim());
    }

    @Test
    void removedSerialYieldsNotEvaluableWithNoFallback() {
        List<CandidateRow> rows = CandidateRowAssembler.assemble(Fixtures.check11RemovedSerial());
        assertEquals(1, rows.size());

        PairingOutcome.NotEvaluable outcome = assertInstanceOf(PairingOutcome.NotEvaluable.class, rows.get(0).pairingOutcome());
        assertEquals(PairingOutcome.Reason.PEER_NOT_FOUND, outcome.reason());
    }
}
