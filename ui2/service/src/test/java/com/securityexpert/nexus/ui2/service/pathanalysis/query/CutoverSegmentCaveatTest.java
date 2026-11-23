package com.securityexpert.nexus.ui2.service.pathanalysis.query;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The cutover-segment matcher over synthetic stored segment JSON (documentation addresses only). */
class CutoverSegmentCaveatTest {

    private static String segment(String blocks, String peers) {
        return "{\"segmentId\":\"seg-0001\",\"name\":\"SEG-ALPHA\",\"revision\":1,\"blocks\":" + blocks + ",\"peerBlocks\":" + peers
                + ",\"createdBy\":\"synthetic\"}";
    }

    private static final String ALPHA = segment("[\"192.0.2.0/24\"]", "[\"198.51.100.0/24\"]");

    private static CutoverSegmentCaveat.Outcome check(String source, String destination, String... segments) {
        return CutoverSegmentCaveat.check(List.of(segments), source, destination);
    }

    @Test
    void anAddressInsideABlockMatchesAsSourceOrDestination() {
        assertTrue(check("192.0.2.77", "203.0.113.5", ALPHA).matched());
        assertTrue(check("203.0.113.5", "192.0.2.77", ALPHA).matched());
        assertFalse(check("192.0.2.77", "192.0.2.78", ALPHA).unreadable());
    }

    @Test
    void theNetworkAndBroadcastEdgesAreInsideTheBlock() {
        assertTrue(check("192.0.2.0", "203.0.113.5", ALPHA).matched());
        assertTrue(check("203.0.113.5", "192.0.2.255", ALPHA).matched());
    }

    @Test
    void theAddressJustOutsideTheEdgesDoesNotMatch() {
        assertEquals(CutoverSegmentCaveat.Outcome.NONE, check("192.0.1.255", "192.0.3.0", ALPHA));
    }

    @Test
    void aPeerBlockOnlyMatchIsNotRedirected() {
        assertEquals(CutoverSegmentCaveat.Outcome.NONE, check("198.51.100.20", "203.0.113.5", ALPHA));
    }

    @Test
    void aHostBlockMatchesOnlyThatHost() {
        String host = segment("[\"192.0.2.9/32\"]", "[]");
        assertTrue(check("192.0.2.9", "203.0.113.5", host).matched());
        assertFalse(check("192.0.2.10", "203.0.113.5", host).matched());
    }

    @Test
    void anyOfSeveralSegmentsMatches() {
        String beta = segment("[\"203.0.113.0/25\"]", "[]");
        assertTrue(check("198.51.100.1", "203.0.113.100", ALPHA, beta).matched());
    }

    @Test
    void anUnparseableBlockIsUnreadableNeverNoMatch() {
        for (String bad : List.of("[\"not-an-address\"]", "[\"192.0.2.0\"]", "[\"192.0.2.0/33\"]", "[\"2001:db8::/32\"]", "[7]", "[]",
                "\"192.0.2.0/24\"")) {
            var outcome = check("203.0.113.5", "203.0.113.6", segment(bad, "[]"));
            assertTrue(outcome.unreadable(), bad);
            assertFalse(outcome.matched(), bad);
        }
        assertTrue(check("203.0.113.5", "203.0.113.6", "{not json").unreadable());
        assertTrue(check("203.0.113.5", "203.0.113.6", "[]").unreadable());
        assertTrue(check("203.0.113.5", "203.0.113.6", "{\"name\":\"SEG-ALPHA\"}").unreadable());
    }

    @Test
    void anUnreadableSegmentDoesNotHideAMatchInAnother() {
        var outcome = check("192.0.2.5", "203.0.113.6", segment("[\"junk\"]", "[]"), ALPHA);
        assertTrue(outcome.matched());
        assertTrue(outcome.unreadable());
    }

    @Test
    void ipv6AndNonAddressInputMatchNothingAndNeverThrow() {
        for (String input : List.of("2001:db8::1", "192.0.2.5/24", "192.0.2", "", " ", "host.invalid", "999.0.2.5", "192.0.02.5")) {
            assertEquals(CutoverSegmentCaveat.Outcome.NONE, check(input, input, ALPHA), input);
        }
        assertEquals(CutoverSegmentCaveat.Outcome.NONE, check(null, null, ALPHA));
    }

    @Test
    void noSegmentsStoredIsNothing() {
        assertEquals(CutoverSegmentCaveat.Outcome.NONE, CutoverSegmentCaveat.check(List.of(), "192.0.2.5", "192.0.2.6"));
        assertEquals(CutoverSegmentCaveat.Outcome.NONE, CutoverSegmentCaveat.check(null, "192.0.2.5", "192.0.2.6"));
    }
}
