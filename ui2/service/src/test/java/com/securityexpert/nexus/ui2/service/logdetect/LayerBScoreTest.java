package com.securityexpert.nexus.ui2.service.logdetect;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Design section 4.4: R, C with the MAD floor, the product; and the sparse robust statistics. */
class LayerBScoreTest {
    @Test
    void rarityMatchesTheDesignTableForTheCheckPointCohort() {
        int n = 57;
        assertEquals(1.00, LayerBScore.rarity(n, 0), 0.005);
        assertEquals(0.83, LayerBScore.rarity(n, 1), 0.005);
        assertEquals(0.73, LayerBScore.rarity(n, 2), 0.005);
        assertEquals(0.56, LayerBScore.rarity(n, 5), 0.005);
        assertEquals(0.41, LayerBScore.rarity(n, 10), 0.005);
        assertEquals(0.16, LayerBScore.rarity(n, 29), 0.005);
        assertEquals(0.00, LayerBScore.rarity(n, 57), 0.005);
        assertEquals(1.00, LayerBScore.rarity(0, 0), 1e-9, "no cohort: no fleet evidence, R = 1");
    }

    @Test
    void theScaleFloorStopsAZeroMadFromGivingAnInfiniteZ() {
        assertEquals(1.0, LayerBScore.sigma(0, 0), 1e-9);
        assertEquals(2.0, LayerBScore.sigma(4, 0), 1e-9, "sqrt(m) is the Poisson floor");
        assertEquals(1.4826 * 3, LayerBScore.sigma(1, 3), 1e-9);
        assertEquals(45.0, LayerBScore.z(45, 0, 0), 1e-9);
        assertEquals(0.0, LayerBScore.rateChange(3), 1e-9);
        assertEquals(1.0, LayerBScore.rateChange(10), 1e-9);
        assertEquals(0.5, LayerBScore.rateChange(6.5), 1e-9);
    }

    @Test
    void changeIsTheMaximumWithTheChronicFloorAndTheScoreIsTheProduct() {
        assertEquals(0.1, LayerBScore.change(false, 0, false, false), 1e-9);
        assertEquals(1.0, LayerBScore.change(true, 0, false, false), 1e-9);
        assertEquals(0.4, LayerBScore.change(false, 0.4, false, false), 1e-9);
        assertEquals(1.0, LayerBScore.change(false, 0, false, true), 1e-9);
        assertEquals(58.4, LayerBScore.score(0.8, 0.73, 1.0), 1e-9);
        assertEquals(0.0, LayerBScore.score(1.0, 0.0, 1.0), 1e-9, "a severe template on every device is background");
    }

    @Test
    void sparseMedianAndMadAreExact() {
        // 24 buckets, 4 with counts: median 0, MAD 0
        var a = LogRobustStats.of(24, new long[] {5, 1, 2, 9});
        assertEquals(0.0, a.median(), 1e-9);
        assertEquals(0.0, a.mad(), 1e-9);
        // 4 buckets all counted: {1, 2, 3, 10} -> median 2.5, deviations {1.5, 0.5, 0.5, 7.5} -> MAD 1.0
        var b = LogRobustStats.of(4, new long[] {10, 3, 1, 2});
        assertEquals(2.5, b.median(), 1e-9);
        assertEquals(1.0, b.mad(), 1e-9);
        // 5 buckets, one zero: {0, 2, 2, 2, 8} -> median 2, deviations {2, 0, 0, 0, 6} -> MAD 0
        var c = LogRobustStats.of(5, new long[] {2, 2, 8, 2});
        assertEquals(2.0, c.median(), 1e-9);
        assertEquals(0.0, c.mad(), 1e-9);
        assertEquals(0.0, LogRobustStats.of(0, new long[0]).median(), 1e-9);
    }
}
