package com.securityexpert.nexus.ui2.worker.inventory.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Shapes follow the Management API reply object of show-ha-status (API v2); every value is synthetic. */
class CheckPointMdsHaStatusParserTest {
    private static final String TWO = "{\n \"domain-type\" : \"mds\",\n \"successfully-synced\" : true,\n \"servers\" : [ {\n"
            + "  \"name\" : \"MDS-SYNTH-01\", \"ip-address\" : \"192.0.2.10\", \"ha-state\" : \"active\" }, {\n"
            + "  \"name\" : \"MDS-SYNTH-02\", \"ip-address\" : \"192.0.2.11\", \"ha-state\" : \"standby\", \"sync-state\" : \"Ok\" } ]\n}";

    @Test
    void theLocalServerIsTheOneWithTheDeviceAddress() {
        var a = CheckPointMdsHaStatusParser.parse(TWO, "192.0.2.10");
        assertEquals("ACTIVE", a.role().orElseThrow());
        assertEquals(2, a.servers());
        assertEquals("OK", a.outcome());
        assertEquals("STANDBY", CheckPointMdsHaStatusParser.parse("[Expert@x:0]# " + TWO + "\n[Expert@x:0]#", "192.0.2.11").role().orElseThrow());
    }

    @Test
    void anythingUnprovenIsUnknown() {
        assertEquals("NO_MATCH", CheckPointMdsHaStatusParser.parse(TWO, "192.0.2.99").outcome());
        assertTrue(CheckPointMdsHaStatusParser.parse(TWO, "192.0.2.99").role().isEmpty());
        assertTrue(CheckPointMdsHaStatusParser.parse(TWO, "mds-synth-01").role().isEmpty(), "a name never matches");
        assertEquals("AMBIGUOUS", CheckPointMdsHaStatusParser.parse(TWO.replace("192.0.2.11", "192.0.2.10"), "192.0.2.10").outcome());
        assertEquals("UNKNOWN_STATE", CheckPointMdsHaStatusParser.parse(TWO.replace("\"active\"", "\"unknown\""), "192.0.2.10").outcome());
        assertEquals("VENDOR_ERROR", CheckPointMdsHaStatusParser.parse("{\"code\":\"generic_err_command_not_found\",\"message\":\"x\"}", "192.0.2.10").outcome());
        assertEquals("NOT_JSON", CheckPointMdsHaStatusParser.parse("mgmt_cli: command not found", "192.0.2.10").outcome());
        assertEquals("EMPTY", CheckPointMdsHaStatusParser.parse("", "192.0.2.10").outcome());
        assertEquals("NO_SERVERS", CheckPointMdsHaStatusParser.parse("{\"domain-type\":\"mds\"}", "192.0.2.10").outcome());
        assertEquals("NO_LOCAL_ADDRESS", CheckPointMdsHaStatusParser.parse(TWO, "").outcome());
    }
}
