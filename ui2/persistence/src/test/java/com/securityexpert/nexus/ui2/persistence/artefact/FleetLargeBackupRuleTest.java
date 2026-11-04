package com.securityexpert.nexus.ui2.persistence.artefact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * The server-side fleet-relative rule, with the cases of the frontend test {@code tests/backupSize.test.ts} so the screen's
 * "Large" marker and the alert agree. Synthetic sizes only, shaped like the reviewed estate.
 */
class FleetLargeBackupRuleTest {

    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    private static List<Long> checkPointFleet() {
        List<Long> fleet = new ArrayList<>(IntStream.range(0, 12).mapToObj(i -> (250 + i * 5L) * MIB).toList());
        fleet.add(28_537 * MIB);
        return fleet;
    }

    @Test
    void theTwentyEightGigabyteCheckPointArchiveIsLargeAgainstTheCheckPointMedian() {
        var verdict = FleetLargeBackupRule.evaluate("check_point", 28_537 * MIB, checkPointFleet()).orElseThrow();
        assertEquals(280 * MIB, verdict.medianBytes(), 0.0);
        assertEquals(101.9, verdict.ratio(), 0.05);
        assertEquals(13, verdict.fleetDevices());
        assertEquals("Backup size above fleet norm: 101.9x the check_point fleet median (280 MB -> 27.9 GB, median of 13 devices)",
                verdict.summary());
    }

    @Test
    void aNormalArchiveOfTheSameVendorIsNotLarge() {
        assertFalse(FleetLargeBackupRule.evaluate("check_point", 300 * MIB, checkPointFleet()).isPresent());
    }

    @Test
    void theFactorIsTenAndTheFloorIsOneGibiByte() {
        // exactly 10x of a 1 GiB median: large
        assertTrue(FleetLargeBackupRule.evaluate("check_point", 10 * GIB, List.of(GIB, GIB, 10 * GIB)).isPresent());
        // 9.9x: not large
        assertFalse(FleetLargeBackupRule.evaluate("check_point", 99 * GIB / 10, List.of(GIB, GIB, 99 * GIB / 10)).isPresent());
        // the ratio is met but the archive is under 1 GiB
        List<Long> small = List.of(MIB, MIB, 900 * MIB);
        assertFalse(FleetLargeBackupRule.evaluate("palo_alto", 900 * MIB, small).isPresent());
        assertFalse(FleetLargeBackupRule.evaluate("palo_alto", GIB - 1, List.of(MIB, MIB, GIB - 1)).isPresent());
        assertTrue(FleetLargeBackupRule.evaluate("palo_alto", GIB, List.of(MIB, MIB, GIB)).isPresent());
    }

    @Test
    void aVendorWithASingleDeviceIsItsOwnMedianAndNeverFlags() {
        assertFalse(FleetLargeBackupRule.evaluate("check_point", 28 * GIB, List.of(28 * GIB)).isPresent());
        assertFalse(FleetLargeBackupRule.evaluate("check_point", 28 * GIB, List.of()).isPresent());
    }

    @Test
    void evenCountsAverageTheTwoMiddleValuesAndUnknownSizesAreIgnored() {
        assertEquals(20 * MIB, FleetLargeBackupRule.median(List.of(10 * MIB, 30 * MIB)), 0.0);
        // the negative size is dropped (as the screen does), so the fleet is three devices with a 30 MiB median
        var verdict = FleetLargeBackupRule.evaluate("palo_alto", 28 * GIB, List.of(10 * MIB, 30 * MIB, 28 * GIB, -1L))
                .orElseThrow();
        assertEquals(3, verdict.fleetDevices());
        assertEquals(30 * MIB, verdict.medianBytes(), 0.0);
    }

    @Test
    void aZeroMedianNeverFlagsAndNeverDividesByZero() {
        assertFalse(FleetLargeBackupRule.evaluate("check_point", 5 * GIB, List.of(0L, 0L, 5 * GIB)).isPresent());
    }
}
