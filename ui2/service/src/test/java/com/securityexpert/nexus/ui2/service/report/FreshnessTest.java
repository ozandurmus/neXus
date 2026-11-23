package com.securityexpert.nexus.ui2.service.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * Unit level for {@link Freshness}: staleness boundary, compact ages, percentages and the per-plane "data as of"
 * summary. All times are fixed {@link Instant}s; no wall-clock reads. Synthetic data only.
 */
class FreshnessTest {

    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");

    private static Instant ago(Duration d) {
        return NOW.minus(d);
    }

    @Test
    void staleIsFalseForNullTime() {
        assertFalse(Freshness.stale(null, NOW));
    }

    @Test
    void staleIsFalseAtExactlyTheThreshold() {
        assertFalse(Freshness.stale(ago(Duration.ofHours(24)), NOW));
    }

    @Test
    void staleIsTrueOneSecondPastTheThreshold() {
        assertTrue(Freshness.stale(ago(Duration.ofHours(24).plusSeconds(1)), NOW));
    }

    @Test
    void ageIsUnknownWithoutTime() {
        assertEquals("UNKNOWN", Freshness.age(null, NOW));
    }

    @Test
    void ageInMinutesBelowAnHour() {
        assertEquals("45 min", Freshness.age(ago(Duration.ofMinutes(45)), NOW));
    }

    @Test
    void ageInHoursBelowTwoDays() {
        assertEquals("5 h", Freshness.age(ago(Duration.ofHours(5)), NOW));
        assertEquals("47 h", Freshness.age(ago(Duration.ofHours(47)), NOW));
    }

    @Test
    void ageSwitchesToDaysAtFortyEightHours() {
        assertEquals("2 d", Freshness.age(ago(Duration.ofHours(48)), NOW));
    }

    @Test
    void ageOfFutureTimeClampsToZeroMinutes() {
        assertEquals("0 min", Freshness.age(NOW.plus(Duration.ofHours(3)), NOW));
    }

    @Test
    void pctWithEmptyDenominatorIsUnknown() {
        assertEquals("UNKNOWN", Freshness.pct(0, 0));
        assertEquals("UNKNOWN", Freshness.pct(5, 0));
    }

    @Test
    void pctRoundsToOneDecimal() {
        assertEquals("33.3%", Freshness.pct(1, 3));
    }

    @Test
    void pctOfFullCoverageIsHundred() {
        assertEquals("100.0%", Freshness.pct(3, 3));
    }

    @Test
    void planeSummarisesMixedEvidenceSkippingNulls() {
        Instant fresh = ago(Duration.ofHours(1));
        Instant stale = ago(Duration.ofHours(25));
        var evidence = Arrays.asList(null, stale, fresh);

        ReportDocument.PlaneAsOf plane = Freshness.plane("Synthetic plane", evidence, 3, NOW, "device");

        assertEquals("Synthetic plane", plane.plane());
        assertEquals(fresh, plane.newest());
        assertEquals(stale, plane.oldest());
        assertEquals(2, plane.covered());
        assertEquals(3, plane.total());
        assertEquals(1, plane.stale());
        assertEquals("device", plane.unit());
        assertTrue(plane.anyStale());
    }

    @Test
    void planeWithNoEvidenceHasNoTimesAndNoStale() {
        ReportDocument.PlaneAsOf plane = Freshness.plane("Empty plane", Arrays.asList(null, null), 2, NOW, "device");

        assertNull(plane.newest());
        assertNull(plane.oldest());
        assertEquals(0, plane.covered());
        assertEquals(2, plane.total());
        assertEquals(0, plane.stale());
        assertFalse(plane.anyStale());
    }

    @Test
    void planeRejectsNullEvidenceCollection() {
        assertThrows(NullPointerException.class, () -> Freshness.plane("Plane", null, 1, NOW, "device"));
    }
}
