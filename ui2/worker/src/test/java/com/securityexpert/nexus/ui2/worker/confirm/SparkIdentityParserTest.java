package com.securityexpert.nexus.ui2.worker.confirm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SparkIdentityParserTest {
    @Test
    void parsesOnlyIdentityFieldsFromGuideShapedDiagnostics() {
        String diag = "Current system info\nCurrent image name: R81_SYNTHETIC_20_35\n"
                + "Current image version: R81.10.10\nPrevious image version: R80.20\n"
                + "HW version : H2\nSerial number : SYNTHETIC-REDACTED\nUnit model: V0\n"
                + "CPU Temperature: 47.0000C - OK";
        assertTrue(SparkIdentityParser.model(diag).isEmpty());
        assertEquals("R81.10.10", SparkIdentityParser.version("unrecognized", diag).orElseThrow());
        assertEquals("R81.10.10", SparkIdentityParser.version(
                "This is Check Point's Synthetic Appliance R81.10.10 - Build 123", diag).orElseThrow());
        assertEquals("FW-TANGO-04", SparkIdentityParser.hostname("FW-TANGO-04>").orElseThrow());
        assertTrue(SparkIdentityParser.model("HW version : H2").isEmpty());
        assertTrue(SparkIdentityParser.model("Unit model: V1\nHW version: H2").isEmpty());
        assertEquals("1590", SparkIdentityParser.model("Unit model: 1590").orElseThrow());
        assertEquals("R81_SYNTHETIC_20_35", SparkIdentityParser.version("", "Current image name: R81_SYNTHETIC_20_35").orElseThrow());
    }

    @Test
    void unknownOutputLeavesAllFieldsEmpty() {
        assertTrue(SparkIdentityParser.model("Serial number: SYNTHETIC-REDACTED").isEmpty());
        assertTrue(SparkIdentityParser.version("Bad parameter", "Previous image version: R80.20").isEmpty());
        assertTrue(SparkIdentityParser.hostname("user@device#").isEmpty());
    }
}
