package com.securityexpert.nexus.ui2.worker.inventory.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** AC-4: {@code show high-availability state}'s role and mode, and the {@code enabled=no} standalone case. */
class PaloAltoHaStateParserTest {

    @Test
    void enabledYesReadsTheLocalStateAndGroupMode() {
        String body = "<response status=\"success\"><result><enabled>yes</enabled><group><mode>Active-Passive</mode>"
                + "<local-info><state>active</state></local-info></group></result></response>";

        PaloAltoHaStateParser.HaState state = PaloAltoHaStateParser.parse(body);

        assertEquals("ACTIVE", state.role());
        assertEquals("Active-Passive", state.clusterMode().orElseThrow());
    }

    @Test
    void enabledNoReportsStandaloneWithNoClusterMode() {
        String body = "<response status=\"success\"><result><enabled>no</enabled></result></response>";

        PaloAltoHaStateParser.HaState state = PaloAltoHaStateParser.parse(body);

        assertEquals(PaloAltoHaStateParser.STANDALONE, state.role());
        assertTrue(state.clusterMode().isEmpty());
    }

    @Test
    void blankOrUnrecognizedOutputReportsUnknownRatherThanAGuess() {
        assertEquals(PaloAltoHaStateParser.UNKNOWN, PaloAltoHaStateParser.parse("").role());
        assertEquals(PaloAltoHaStateParser.UNKNOWN, PaloAltoHaStateParser.parse(null).role());
        assertEquals(PaloAltoHaStateParser.UNKNOWN,
                PaloAltoHaStateParser.parse("<response status=\"success\"><result/></response>").role());
    }
}
