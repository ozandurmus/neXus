package com.securityexpert.nexus.ui2.worker.inventory.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.DeviceInstallEventRepository.Kind;

class CheckPointInstallLogParserTest {

    @Test
    void parsesTheBaseInstallJumboWithTakeAndJumboWithoutTake() {
        var events = CheckPointInstallLogParser.parse(
                "2024-02-23T12:33:58 install_fw1_R81.20.elg \n"
                + "2024-07-24T22:02:57 install_SecurePlatform_HOTFIX_R81_20_JUMBO_HF_MAIN.log Bundle_T65\n"
                + "2026-01-24T03:10:51 install_SecurePlatform_HOTFIX_R81_20_JUMBO_HF_MAIN_3.log \n");
        assertEquals(3, events.size());
        assertEquals(Kind.BASE_INSTALL, events.get(0).kind());
        assertEquals("R81.20", events.get(0).productVersion());
        assertEquals(LocalDateTime.parse("2024-02-23T12:33:58"), events.get(0).installedAtLocal());
        assertEquals("install_fw1_R81.20.elg", events.get(0).fileName());
        assertEquals(Optional.empty(), events.get(0).jumboTake());
        assertEquals(Kind.JUMBO_INSTALL, events.get(1).kind());
        assertEquals("R81.20", events.get(1).productVersion());
        assertEquals(Optional.of(65), events.get(1).jumboTake());
        assertEquals(Optional.empty(), events.get(2).jumboTake());
        assertEquals("install_SecurePlatform_HOTFIX_R81_20_JUMBO_HF_MAIN_3.log", events.get(2).fileName());
    }

    @Test
    void anR82FileNameGivesR82() {
        var events = CheckPointInstallLogParser.parse("2026-05-01T01:02:03 install_SecurePlatform_HOTFIX_R82_JUMBO_HF_MAIN.log \n");
        assertEquals(1, events.size());
        assertEquals("R82", events.get(0).productVersion());
    }

    @Test
    void garbageEmptyAndNullAnswersGiveNoEvents() {
        assertTrue(CheckPointInstallLogParser.parse("").isEmpty());
        assertTrue(CheckPointInstallLogParser.parse(null).isEmpty());
        assertTrue(CheckPointInstallLogParser.parse("not a line\n2026-13-45T99:99:99 install_fw1_R81.20.elg \n"
                + "2026-01-01T00:00:00 something_else.log\nbash: warning\n").isEmpty());
    }

    @Test
    void atMostTwentyEventsAreKept() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            out.append("2026-01-").append(String.format("%02d", i + 1)).append("T00:00:00 install_SecurePlatform_HOTFIX_R81_20_JUMBO_HF_MAIN_")
                    .append(i).append(".log Bundle_T").append(100 + i).append("\n");
        }
        assertEquals(20, CheckPointInstallLogParser.parse(out.toString()).size());
    }
}
