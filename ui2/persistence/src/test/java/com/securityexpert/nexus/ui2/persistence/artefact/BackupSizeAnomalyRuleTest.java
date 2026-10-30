package com.securityexpert.nexus.ui2.persistence.artefact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** PO decision 2026-10-30: the growth / shrink thresholds, the 1 GiB floor and the minimum history. */
class BackupSizeAnomalyRuleTest {

    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long MIB = 1024L * 1024L;

    @Test
    void theMeasuredCaseOneToTwentyNineGigabytesIsAGrowthAnomaly() {
        var verdict = BackupSizeAnomalyRule.evaluate(29 * GIB, List.of(GIB, GIB + 10 * MIB, GIB - 10 * MIB)).orElseThrow();
        assertEquals(BackupSizeAnomalyRule.Direction.GROWTH, verdict.direction());
        assertEquals(GIB, verdict.medianBytes(), "the median of the three, not the mean");
        assertEquals(29.0, verdict.ratio(), 0.001);
        assertTrue(verdict.summary().contains("grew 29.0x"), verdict.summary());
        assertTrue(verdict.summary().contains("1.0 GB -> 29.0 GB"), verdict.summary());
    }

    @Test
    void growthNeedsBothTheFactorAndTheFloor() {
        // exactly 5x and 4 GiB larger: anomaly
        assertTrue(BackupSizeAnomalyRule.evaluate(5 * GIB, List.of(GIB, GIB, GIB)).isPresent());
        // 4.9x: not an anomaly however large
        assertFalse(BackupSizeAnomalyRule.evaluate(49 * GIB, List.of(10 * GIB, 10 * GIB, 10 * GIB)).isPresent());
        // 10x but under the 1 GiB floor (a small device): not an anomaly
        assertFalse(BackupSizeAnomalyRule.evaluate(100 * MIB, List.of(10 * MIB, 10 * MIB, 10 * MIB)).isPresent());
        // 6x and just at the floor
        assertTrue(BackupSizeAnomalyRule.evaluate(GIB + GIB / 5, List.of(GIB / 5, GIB / 5, GIB / 5)).isPresent());
    }

    @Test
    void aShrinkToTwentyPercentOrLessWithTheFloorIsAnAnomaly() {
        var verdict = BackupSizeAnomalyRule.evaluate(2 * GIB, List.of(10 * GIB, 10 * GIB, 10 * GIB)).orElseThrow();
        assertEquals(BackupSizeAnomalyRule.Direction.SHRINK, verdict.direction());
        assertTrue(verdict.summary().startsWith("Backup size anomaly: shrank to 0.2x"), verdict.summary());
        // 21%: not an anomaly
        assertFalse(BackupSizeAnomalyRule.evaluate(21 * GIB / 10, List.of(10 * GIB, 10 * GIB, 10 * GIB)).isPresent());
        // 10% but under the floor
        assertFalse(BackupSizeAnomalyRule.evaluate(50 * MIB, List.of(500 * MIB, 500 * MIB, 500 * MIB)).isPresent());
    }

    @Test
    void tooLittleHistoryIsNoAnomaly() {
        assertFalse(BackupSizeAnomalyRule.evaluate(29 * GIB, List.of()).isPresent());
        assertFalse(BackupSizeAnomalyRule.evaluate(29 * GIB, List.of(GIB)).isPresent());
        assertFalse(BackupSizeAnomalyRule.evaluate(29 * GIB, List.of(GIB, GIB)).isPresent());
    }

    @Test
    void oneOutlierInTheHistoryDoesNotMoveTheMedian() {
        // the newest earlier backup was itself large (an accepted one, say); the median still says 1 GiB
        assertTrue(BackupSizeAnomalyRule.evaluate(29 * GIB, List.of(30 * GIB, GIB, GIB)).isPresent());
        assertEquals(GIB, BackupSizeAnomalyRule.median(List.of(30 * GIB, GIB, GIB)));
    }

    @Test
    void anUnchangedSizeIsNeverAnAnomaly() {
        assertFalse(BackupSizeAnomalyRule.evaluate(29 * GIB, List.of(29 * GIB, 29 * GIB, 29 * GIB)).isPresent());
        assertFalse(BackupSizeAnomalyRule.evaluate(0, List.of(0L, 0L, 0L)).isPresent());
    }
}
