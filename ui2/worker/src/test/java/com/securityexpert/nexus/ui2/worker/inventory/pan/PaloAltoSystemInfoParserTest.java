package com.securityexpert.nexus.ui2.worker.inventory.pan;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PaloAltoSystemInfoParserTest {

    @Test
    void parsesSystemInfo() {
        String xml = "<response status=\"success\"><result><system><serial>0011223344</serial><sw-version>10.1.0</sw-version><model>PA-VM</model></system></result></response>";
        PaloAltoSystemInfoParser.SystemInfo info = PaloAltoSystemInfoParser.parse(xml);
        assertEquals("0011223344", info.serial());
        assertEquals(Optional.of("10.1.0"), info.swVersion());
        assertEquals(Optional.of("PA-VM"), info.model());
    }

    @Test
    void handlesMissingTags() {
        PaloAltoSystemInfoParser.SystemInfo info = PaloAltoSystemInfoParser.parse("<response status=\"success\"><result><system></system></result></response>");
        assertEquals("", info.serial());
        assertEquals(Optional.empty(), info.swVersion());
    }
}
