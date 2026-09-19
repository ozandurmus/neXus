package com.securityexpert.nexus.ui2.worker.inventory.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PaloAltoHaStateParserTest {

    @Test
    void enabledYesReadsTheLocalStateAndGroupMode() {
        String body = "<response status=\"success\"><result><enabled>yes</enabled><group><mode>Active-Passive</mode>"
                + "<local-info><state>active</state></local-info></group></result></response>";

        PaloAltoHaStateParser.HaState state = PaloAltoHaStateParser.parse(body);

        assertEquals("ACTIVE", state.role());
        assertEquals("Active-Passive", state.clusterMode().orElseThrow());
        assertTrue(state.localSerial().isEmpty());
        assertTrue(state.peerSerial().isEmpty());
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

    @Test
    void parsesSerialsWithNormalization() {
        String body = "<response status=\"success\"><result><enabled>yes</enabled><group><mode>Active-Passive</mode>"
                + "<local-info><state>active</state><serial-num> 00123456 </serial-num></local-info>"
                + "<peer-info><serial-num>0123456</serial-num></peer-info></group></result></response>";

        PaloAltoHaStateParser.HaState state = PaloAltoHaStateParser.parse(body);

        assertEquals("00123456", state.localSerial().orElseThrow());
        assertEquals("0123456", state.peerSerial().orElseThrow());
    }
    
    @Test
    void normalizesZeroesCorrectly() {
        assertEquals("0000", PaloAltoHaStateParser.normalizeSerial("0000"));
        assertEquals("0001", PaloAltoHaStateParser.normalizeSerial("0001"));
        assertEquals("0123", PaloAltoHaStateParser.normalizeSerial("0123"));
        assertEquals("0123", PaloAltoHaStateParser.normalizeSerial("  0123  "));
    }
}
