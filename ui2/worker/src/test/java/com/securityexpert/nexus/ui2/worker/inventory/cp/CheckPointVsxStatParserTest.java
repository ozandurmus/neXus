package com.securityexpert.nexus.ui2.worker.inventory.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.inventory.Fixtures;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointVsxStatParser.VsxDevice;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointVsxStatParser.VsxStatResult;

/** AC-4: {@code vsx stat -v} -- VSX detection by text (CF-4), VSIDs including VS0, type letter kept. */
class CheckPointVsxStatParserTest {

    @Test
    void parsesEveryVsidIncludingVs0WithItsTypeLetter() {
        VsxStatResult result = CheckPointVsxStatParser.parse(Fixtures.read("cp/vsx_stat_v.txt"));

        assertTrue(result.vsx());
        assertEquals(List.of(new VsxDevice("0", "S", "VS0"), new VsxDevice("2", "S", "vs-finance"), new VsxDevice("5", "S", "vs-hr")),
                result.devices());
        assertEquals(List.of("0", "2", "5"), CheckPointVsxStatParser.parseVsids(Fixtures.read("cp/vsx_stat_v.txt")));
    }

    @Test
    void nonVsxTextYieldsNotVsxWithoutError() {
        VsxStatResult result = CheckPointVsxStatParser.parse(Fixtures.read("cp/vsx_stat_v_not_vsx.txt"));

        assertFalse(result.vsx());
        assertEquals(List.of(), result.devices());
    }
}
