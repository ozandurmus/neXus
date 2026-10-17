package com.securityexpert.nexus.ui2.worker.systemlog;

import static com.securityexpert.nexus.ui2.worker.systemlog.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PlannerConfigTest {

    @Test
    void defaultsAreTheContractValues() {
        PlannerConfig c = PlannerConfig.defaults();
        assertEquals(8 * MIB, c.readCapBytes());
        assertEquals(32 * MIB, c.sourceBudgetBytes());
        assertEquals(64 * MIB, c.deviceBudgetBytes());
        assertEquals(8 * MIB, c.backfillBytes());
        assertEquals(64, c.anchorBytes());
        assertEquals("(\\.[0-9]{1,2})?", c.siblingSuffixRegex());
        assertEquals(".gz", c.compressedSuffix());
        assertEquals(9, c.fastRotatingHorizonHours());
        assertEquals(8_388_672L, c.maxRangeBytes(), "C2: cap <= 8388672");
    }

    @Test
    void theEligibleNamePatternIsTheContractsRegex() {
        var p = CFG.eligibleNamePattern("fwd.elg");
        assertTrue(p.matcher("fwd.elg").matches());
        assertTrue(p.matcher("fwd.elg.1").matches());
        assertTrue(p.matcher("fwd.elg.12").matches());
        assertFalse(p.matcher("fwd.elg.123").matches());
        assertFalse(p.matcher("fwd.elg.1.gz").matches());
        assertFalse(p.matcher("fwd.elg.old").matches());
        assertFalse(p.matcher("fwd.elg-20261001").matches());
        assertFalse(p.matcher("fwdXelg.1").matches(), "the dot of the base name is literal");
        assertFalse(p.matcher("xfwd.elg").matches());
    }

    @Test
    void invalidValuesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlannerConfig(0, 1, 1, 1, 64, "", ".gz", 9, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannerConfig(1, 1, 1, 1, 0, "", ".gz", 9, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannerConfig(1, 1, 1, 1, 64, "(", ".gz", 9, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannerConfig(1, 1, 1, 1, 64, "", "", 9, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannerConfig(1, 1, 1, 1, 64, "", ".gz", 0, 1));
    }

    @Test
    void theRetentionHorizonFlagsFastRotation() {
        // 5 files of 10 MiB growing 10 MiB/h hold 5 h: below the 9 h threshold
        double fast = RetentionHorizon.horizonHours(5, 10 * MIB, 10.0 * MIB);
        assertEquals(5.0, fast, 1e-9);
        assertTrue(RetentionHorizon.isFastRotating(fast, CFG));
        assertFalse(RetentionHorizon.isFastRotating(9.0, CFG), "exactly 9 h is not below the threshold");
        assertTrue(RetentionHorizon.isFastRotating(8.999, CFG));
        assertEquals(Double.POSITIVE_INFINITY, RetentionHorizon.horizonHours(5, 10 * MIB, 0));
        assertFalse(RetentionHorizon.isFastRotating(Double.POSITIVE_INFINITY, CFG));
        assertThrows(IllegalArgumentException.class, () -> RetentionHorizon.horizonHours(-1, 1, 1));
    }

    @Test
    void readActionInvariants() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReadAction("/var/log/messages", 1, 10, 5, 11, null, ReadPurpose.GROWTH, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ReadAction("/var/log/messages", 1, 10, 5, 0, "x", ReadPurpose.GROWTH, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ReadAction("/var/log/messages", 1, 10, -1, 0, null, ReadPurpose.GROWTH, false, false));
        ReadAction a = new ReadAction("/var/log/messages", 1, 100, 50, 64, "x", ReadPurpose.GROWTH, false, false);
        assertEquals(36, a.rangeStart());
        assertEquals(114, a.rangeLength());
        assertEquals(150, a.endOffset());
    }

    @Test
    void fileStatRejectsWhatAStatLineCannotBe() {
        assertThrows(IllegalArgumentException.class, () -> new FileStat("relative/path", 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new FileStat("/a", -1, 1, 1));
        FileStat f = new FileStat("/var/log/messages.1", 1, 2, 3);
        assertEquals("messages.1", f.baseName());
        assertEquals("/var/log", f.directory());
    }
}
