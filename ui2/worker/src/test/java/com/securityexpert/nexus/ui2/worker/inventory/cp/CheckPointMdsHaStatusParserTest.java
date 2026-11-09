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
        assertEquals("NO_SERVERS domainType=domain keys=domain-type:string", CheckPointMdsHaStatusParser.parse("{\"domain-type\":\"domain\"}", "192.0.2.10").outcome());
        assertEquals("NO_SERVERS domainType=other keys=domain-type:string", CheckPointMdsHaStatusParser.parse("{\"domain-type\":\"MDS-SYNTH-01\"}", "192.0.2.10").outcome());
        assertEquals("NO_SERVERS domainType=absent keys=message:string,peers[2],meta{name,?}", CheckPointMdsHaStatusParser.parse(
                "{\"message\":\"MDS-SYNTH-01 at 192.0.2.10\",\"peers\":[1,2],\"meta\":{\"name\":\"x\",\"192.0.2.9\":1}}",
                "192.0.2.10").outcome());
        assertEquals("NO_LOCAL_ADDRESS", CheckPointMdsHaStatusParser.parse(TWO, "").outcome());
    }

    /** The live R82 MDS-level reply shape of 2026-11-09 (key names as logged; values synthetic). */
    @Test
    void anMdsLevelReplyWithoutServersIsTheDocumentedActiveActiveCase() {
        var mds = CheckPointMdsHaStatusParser.parse("{\"uid\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"System Data\","
                + "\"domain-type\":\"mds\",\"successfully-synced\":true}", "192.0.2.10");
        assertEquals("ACTIVE", mds.role().orElseThrow());
        assertEquals(0, mds.servers());
        assertEquals("MDS_LEVEL_ACTIVE_ACTIVE", mds.outcome());
        assertTrue(CheckPointMdsHaStatusParser.parse("{\"domain-type\":\"global domain\"}", "192.0.2.10").role().isEmpty(),
                "only the MDS level is Active/Active; a domain without servers stays unknown");
        assertEquals("NO_SERVERS domainType=global_domain keys=domain-type:string",
                CheckPointMdsHaStatusParser.parse("{\"domain-type\":\"global domain\"}", "192.0.2.10").outcome());
    }
}
