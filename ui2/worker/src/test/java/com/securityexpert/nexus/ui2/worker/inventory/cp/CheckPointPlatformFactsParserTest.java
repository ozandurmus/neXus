package com.securityexpert.nexus.ui2.worker.inventory.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Shapes from docs/design/CP_PLATFORM_IDENTITY_MEASUREMENTS.md (an R81.20 management server, 2026-09-22). */
class CheckPointPlatformFactsParserTest {

    private static final String CPINFO = """
            This is Check Point CPinfo Build 914000274 for GAIA
            [MGMT]
                    HOTFIX_R81_20_JUMBO_HF_MAIN     Take:  122
            [IDA]
                    No hotfixes..
            [FW1]
                    HOTFIX_INEXT_NANO_EGG_AUTOUPDATE
                    HOTFIX_R81_20_JUMBO_HF_MAIN     Take:  122
            [VSEC]
                    HOTFIX_R81_20_JUMBO_HF_MAIN
            [CPUpdates]
                    BUNDLE_R81_20_JUMBO_HF_MAIN     Take:  122
            """;

    @Test
    void theMainJumboTakeIsTheHotfixLevel() {
        assertEquals(Optional.of("R81.20 Jumbo Take 122"), CheckPointPlatformFactsParser.jumboTake(CPINFO));
    }

    @Test
    void aJumboLineWithoutATakeOrNoJumboAtAllIsEmpty() {
        assertEquals(Optional.empty(), CheckPointPlatformFactsParser.jumboTake("[VSEC]\n        HOTFIX_R81_20_JUMBO_HF_MAIN\n"));
        assertEquals(Optional.empty(), CheckPointPlatformFactsParser.jumboTake("[IDA]\n        No hotfixes..\n"));
        assertEquals(Optional.empty(), CheckPointPlatformFactsParser.jumboTake(""));
    }

    @Test
    void assetSystemGivesSerialAndFamily() {
        String asset = """
                Platform: ST-4150-00
                Model: Smart-1 5150
                Serial Number: 0000000
                CPU Model: Intel(R) Xeon(R) Gold 5118 CPU
                Number of Cores: 24
                """;
        assertEquals(Optional.of("0000000"), CheckPointPlatformFactsParser.assetSerial(asset));
        assertEquals(Optional.of("Smart-1 5150 (ST-4150-00)"), CheckPointPlatformFactsParser.assetFamily(asset));
        assertEquals(Optional.empty(), CheckPointPlatformFactsParser.assetSerial("Serial Number: N/A\n"));
        assertEquals(Optional.empty(), CheckPointPlatformFactsParser.assetSerial("CLINFR0329  Invalid command"));
    }

    @Test
    void uptimeIsTheSpanBetweenUpAndTheUserCount() {
        assertEquals(Optional.of("237 days, 21:10"),
                CheckPointPlatformFactsParser.uptime(" 21:43:46 up 237 days, 21:10,  2 users,  load average: 0.87, 0.69, 0.58"));
        assertEquals(Optional.of("3:04"), CheckPointPlatformFactsParser.uptime(" 09:00:01 up 3:04,  1 user,  load average: 0.00, 0.01, 0.05"));
        assertEquals(Optional.empty(), CheckPointPlatformFactsParser.uptime("bash: uptime: command not found"));
    }

    @org.junit.jupiter.api.Test
    void noJumboShapeMasksDigitsAndKeepsOnlyPackageTokens() {
        String output = "This is Check Point CPinfo Build 914000248 for GAIA\n[MGMT]\n    HOTFIX_R82_JUMBO_HF_MAIN_X   Take:  44\n"
            + "    HOTFIX_TEX_ENGINE_R82_AUTOUPDATE   Take:  12\n[IDA]\n    No hotfixes..\n";
        String shape = CheckPointPlatformFactsParser.noJumboShape(output);
        org.junit.jupiter.api.Assertions.assertTrue(shape.contains("hotfixLines=2"), shape);
        org.junit.jupiter.api.Assertions.assertTrue(shape.contains("HOTFIX_R9_JUMBO_HF_MAIN_X Take: 9"), shape);
        org.junit.jupiter.api.Assertions.assertFalse(shape.contains("44") || shape.contains("82") || shape.contains("914000248"), shape);
        org.junit.jupiter.api.Assertions.assertEquals("len=0 lines=0", CheckPointPlatformFactsParser.noJumboShape(null));
    }
}
