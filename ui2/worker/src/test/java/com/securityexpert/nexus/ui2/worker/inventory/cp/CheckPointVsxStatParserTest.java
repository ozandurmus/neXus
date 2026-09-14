package com.securityexpert.nexus.ui2.worker.inventory.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.inventory.Fixtures;

/** AC-2: {@code vsx stat -v} -- VSIDs only, including VS0 (the caller decides not to re-enter it). */
class CheckPointVsxStatParserTest {

    @Test
    void parsesEveryVsidIncludingVs0() {
        List<String> vsids = CheckPointVsxStatParser.parseVsids(Fixtures.read("cp/vsx_stat_v.txt"));

        assertEquals(List.of("0", "2", "5"), vsids);
    }

    @Test
    void nonVsxHostHasNoVsids() {
        assertEquals(List.of(), CheckPointVsxStatParser.parseVsids("vsx stat not applicable on this platform\n"));
    }
}
