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
    void readsPlatformFactsFromTheSameRead() {
        String xml = "<response status=\"success\"><result><system><serial>0011223344</serial><sw-version>11.1.4</sw-version>"
                + "<model>PA-5220</model><family>5200</family><uptime>12 days, 3:04:55</uptime>"
                + "<app-version>8900-9101</app-version><threat-version>8900-9101</threat-version><av-version>5150-5670</av-version>"
                + "<wildfire-version>1010-1020</wildfire-version><url-filtering-version>20260920.20001</url-filtering-version>"
                + "</system></result></response>";
        PaloAltoSystemInfoParser.SystemInfo info = PaloAltoSystemInfoParser.parse(xml);
        assertEquals(Optional.of("5200"), info.family());
        assertEquals(Optional.of("12 days, 3:04:55"), info.uptime());
        assertEquals(java.util.Map.of("app", "8900-9101", "threat", "8900-9101", "av", "5150-5670",
                "wildfire", "1010-1020", "url", "20260920.20001"), info.contentVersions());
    }

    @Test
    void handlesMissingTags() {
        PaloAltoSystemInfoParser.SystemInfo info = PaloAltoSystemInfoParser.parse("<response status=\"success\"><result><system></system></result></response>");
        assertEquals("", info.serial());
        assertEquals(Optional.empty(), info.swVersion());
        assertEquals(java.util.Map.of(), info.contentVersions());
        assertEquals(Optional.empty(), info.uptime());
    }
}
