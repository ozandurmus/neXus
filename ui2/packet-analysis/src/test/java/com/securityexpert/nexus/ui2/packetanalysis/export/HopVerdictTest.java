package com.securityexpert.nexus.ui2.packetanalysis.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.securityexpert.nexus.ui2.packetanalysis.export.HopVerdict.Kind;
import com.securityexpert.nexus.ui2.packetanalysis.export.HopVerdict.Mark;
import com.securityexpert.nexus.ui2.packetanalysis.export.HopVerdict.Reason;
import com.securityexpert.nexus.ui2.packetanalysis.export.HopVerdict.Signal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The approved hop marker rule at each of its boundaries (PO decision 2026-10-18): 4.9 vs 5.0 ms, 1.9x vs 2.0x, 9 vs 10
 * samples and late packets, the clock-uncertainty floor for a delay and for a clean hop, the 1 ms clean margin and the
 * 1-point share growth. Signals are built from exact integer sums, as the report builds them.
 */
class HopVerdictTest {
    private static final double NS = 1_000_000.0;

    /** A transit signal with n samples per window at the given means, late counts and clock uncertainty. */
    private static Signal sig(long n0, double mean0, long n1, double mean1, long late0, long late1, long clean0, long clean1,
            Double u) {
        return new Signal(Kind.FORWARD_TRANSIT, NS, new long[] {n0, n1},
                new long[] {Math.round(mean0 * NS) * n0, Math.round(mean1 * NS) * n1}, new long[] {late0, late1},
                new long[] {clean0, clean1}, u);
    }

    /** A step delay: every later sample is late, none before. */
    private static Signal step(long n, double before, double after, Double u) {
        return sig(n, before, n, after, 0, n, 0, n, u);
    }

    /** A hop with nothing moving: no late sample in either window. */
    private static Signal flat(long n, double before, double after, Double u) {
        return sig(n, before, n, after, 0, 0, 0, 0, u);
    }

    private static Signal as(Kind kind, Signal s) {
        return new Signal(kind, s.unit(), s.n(), s.sum(), s.lateDelay(), s.lateClean(), s.uncertaintyMs());
    }

    private static Mark mark(Signal s) {
        return HopVerdict.judge(s).mark();
    }

    private static Reason reason(Signal s) {
        return HopVerdict.judge(s).reason();
    }

    // ------------------------------------------------------------------------------------------ delay threshold

    @Test
    void anIncreaseOf49MsIsNotADelayAnd50MsIs() {
        assertEquals(Mark.UNDECIDED, mark(step(10, 1.0, 5.9, 0.1)));
        assertEquals(Reason.BETWEEN, reason(step(10, 1.0, 5.9, 0.1)), "above the clean margin, below the delay threshold");
        assertEquals(Mark.DELAY, mark(step(10, 1.0, 6.0, 0.1)));
        // one nanosecond short of 5 ms in the mean is still short
        Signal shortByOneNs = new Signal(Kind.FORWARD_TRANSIT, NS, new long[] {10, 10}, new long[] {10_000_000L, 59_999_990L},
                new long[] {0, 10}, new long[] {0, 10}, 0.1);
        assertEquals(Mark.UNDECIDED, mark(shortByOneNs));
    }

    @Test
    void theLaterMeanMustBeTwiceTheEarlierOne() {
        assertEquals(Mark.UNDECIDED, mark(step(10, 6.0, 11.4, 0.1)), "+5.4 ms but 1.9x");
        assertEquals(Reason.RATIO, reason(step(10, 6.0, 11.4, 0.1)));
        assertEquals(Mark.DELAY, mark(step(10, 6.0, 12.0, 0.1)), "+6 ms and 2.0x");
    }

    @Test
    void anEarlierMeanBelowPointOneMsIsTakenAsPointOneForTheRatio() {
        // a negative earlier mean (clock offset residue) still gives a defined ratio: 5.0 / 0.1 = 50x
        Signal s = step(10, -0.2, 4.8, 0.1);
        assertEquals(Mark.DELAY, mark(s));
        assertEquals(48.0, s.ratio(), 1e-9);
    }

    // ------------------------------------------------------------------------------------------ sample counts

    @Test
    void nineSamplesInAWindowAreNotJudgedAndTenAre() {
        assertTrue(!step(9, 1.0, 10.0, 0.1).usable());
        assertTrue(!sig(10, 1.0, 9, 10.0, 0, 9, 0, 9, 0.1).usable(), "nine in the later window");
        HopVerdict.Result nine = HopVerdict.hop(List.of(sig(9, 1.0, 30, 10.0, 0, 30, 0, 30, 0.1)), true, null);
        assertEquals(Mark.UNDECIDED, nine.mark());
        assertEquals(Reason.FEW_SAMPLES, nine.reason());
        assertEquals("belirsiz: örnek yetersiz; önce 9, sonra 30 (en az 10 gerekir).", nine.text());
        assertEquals(Mark.DELAY, HopVerdict.hop(List.of(sig(10, 1.0, 30, 10.0, 0, 30, 0, 30, 0.1)), true, null).mark());
    }

    @Test
    void aDelayNeedsTenLatePacketsNotNine() {
        // the mean rises by 10 ms; 9 vs 10 of the 1000 later packets are 5 ms above the earlier median (share +0.9 / +1.0 point)
        assertEquals(Reason.BREADTH, reason(sig(1000, 1.0, 1000, 11.0, 0, 9, 0, 9, 0.1)));
        assertEquals(Mark.DELAY, mark(sig(1000, 1.0, 1000, 11.0, 0, 10, 0, 10, 0.1)));
    }

    @Test
    void theLateShareMustGrowByOnePoint() {
        // 10 late packets after, but 1 of 1000 was already late before: the share grows by 0.9 point only
        assertEquals(Reason.BREADTH, reason(sig(1000, 1.0, 1000, 11.0, 1, 10, 1, 10, 0.1)));
        // the same growth over smaller windows: 0 of 100 -> 1 of 100 would be one point but only one packet
        assertEquals(Reason.BREADTH, reason(sig(100, 1.0, 100, 11.0, 0, 1, 0, 1, 0.1)));
        assertEquals(Mark.DELAY, mark(sig(1000, 1.0, 1000, 11.0, 1, 11, 1, 11, 0.1)), "+1.0 point and 11 packets");
    }

    @Test
    void aFewHugeOutliersRaiseTheMeanButNotTheMarker() {
        // 2 of 100 packets 500 ms late lift the mean by ~10 ms: a plain mean rule would accuse the hop
        Signal spikes = new Signal(Kind.FORWARD_TRANSIT, NS, new long[] {100, 100},
                new long[] {100 * 1_000_000L, 98 * 1_000_000L + 2 * 500_000_000L}, new long[] {0, 2}, new long[] {0, 2}, 0.1);
        assertTrue(spikes.delta() > 9);
        assertEquals(Reason.BREADTH, reason(spikes));
        HopVerdict.Result r = HopVerdict.hop(List.of(spikes), true, null);
        assertTrue(r.text().startsWith("belirsiz: ortalama +9,98 ms arttı ama yalnız 2 paket (%2,0; önce %0,0)"), r.text());
    }

    // ------------------------------------------------------------------------------------------ clock floor

    @Test
    void anIncreaseMustExceedTwiceTheClockUncertainty() {
        assertEquals(Reason.CLOCK_INCREASE, reason(step(10, 1.0, 7.0, 3.0)), "+6 ms = 2 x 3 ms is not more than 2u");
        assertEquals(Mark.DELAY, mark(step(10, 1.0, 7.0, 2.99)));
        HopVerdict.Result r = HopVerdict.hop(List.of(step(10, 1.0, 8.0, 4.0)), true, null);
        assertEquals("belirsiz: artış +7,00 ms, hop saat belirsizliğinin 2 katından (8,0 ms) büyük değil.", r.text());
    }

    @Test
    void aHopIsCleanOnlyWhenTwiceItsClockUncertaintyIsBelowTheDelayThreshold() {
        assertEquals(Reason.CLOCK_FLOOR, reason(flat(10, 1.0, 1.0, 2.5)), "2 x 2.5 = 5 ms: a 5 ms change cannot be seen");
        assertEquals(Mark.CLEAN, mark(flat(10, 1.0, 1.0, 2.49)));
        HopVerdict.Result r = HopVerdict.hop(List.of(flat(10, 1.0, 1.0, 3.0)), true, null);
        assertEquals("belirsiz: saat belirsizliği ±3,0 ms; 2× = 6,0 ms, 5 ms'lik bir değişim bu hop'ta ölçülemez.", r.text());
    }

    @Test
    void anUnknownClockUncertaintyGivesNoMarkerEitherWay() {
        assertEquals(Reason.CLOCK_UNKNOWN, reason(step(10, 1.0, 50.0, null)), "no delay word without an uncertainty");
        assertEquals(Reason.CLOCK_UNKNOWN, reason(flat(10, 1.0, 1.0, null)), "no clean word without an uncertainty");
    }

    // ------------------------------------------------------------------------------------------ clean margin

    @Test
    void cleanMeansTheMeanMovedByLessThanOneMillisecond() {
        assertEquals(Mark.CLEAN, mark(flat(10, 1.0, 1.999, 0.1)));
        assertEquals(Reason.BETWEEN, reason(flat(10, 1.0, 2.0, 0.1)), "exactly 1 ms is not clean");
        assertEquals(Mark.CLEAN, mark(flat(10, 2.0, 1.001, 0.1)));
        assertEquals(Reason.BETWEEN, reason(flat(10, 2.0, 1.0, 0.1)), "a 1 ms decrease is not clean either");
    }

    @Test
    void cleanAlsoNeedsTheShareAboveTheMedianPlusOneMsToGrowByLessThanOnePoint() {
        // the mean does not move, but 1 of 100 later packets is 1 ms above the earlier median (+1.0 point)
        assertEquals(Reason.LATE_SHARE, reason(sig(100, 1.0, 100, 1.0, 0, 0, 0, 1, 0.1)));
        // 9 of 1000 (+0.9 point) is clean
        assertEquals(Mark.CLEAN, mark(sig(1000, 1.0, 1000, 1.0, 0, 0, 0, 9, 0.1)));
    }

    @Test
    void aMeanThatFellByTheDelayThresholdSaysTheEarlierWindowWasDelayed() {
        assertEquals(Reason.DECREASE, reason(flat(10, 22.0, 3.8, 0.1)));
        assertEquals(Reason.BETWEEN, reason(flat(10, 6.0, 1.1, 0.1)), "a 4.9 ms decrease is between the margins");
        assertEquals("belirsiz: ortalama -18,20 ms azaldı; önceki pencerede gecikme vardı.",
                HopVerdict.hop(List.of(flat(10, 22.0, 3.8, 0.1)), true, null).text());
    }

    // ------------------------------------------------------------------------------------------ one hop

    @Test
    void oneDelayedSignalMarksTheHopAndTheReasonLineCarriesTheNumbersTheRuleUsed() {
        Signal reverse = new Signal(Kind.REVERSE_TRANSIT, NS, new long[] {50, 50}, new long[] {50_000_000L, 50_000_000L},
                new long[] {0, 0}, new long[] {0, 0}, 0.13);
        Signal forward = sig(71_228, 0.29, 18_746, 31.59, 71, 2_229, 100, 2_300, 0.13);
        HopVerdict.Result r = HopVerdict.hop(List.of(forward, reverse), true, null);
        assertEquals(Mark.DELAY, r.mark());
        assertEquals("gecikme burada (ileri yön geçiş süresi): ortalama 0,29 → 31,59 ms (+31,30 ms, 109×); önceki ortancadan 5 ms "
                + "fazla geç kalan paketler önce %0,1, sonra %11,9 (2.229 paket); örnek sayısı 71.228 / 18.746; hop saat "
                + "belirsizliği ±0,13 ms.", r.text());
    }

    @Test
    void cleanNeedsEveryJudgedSignalCleanAndIgnoresSignalsWithTooFewSamples() {
        Signal fwd = flat(100, 1.0, 1.0, 0.1);
        Signal rev = as(Kind.REVERSE_TRANSIT, flat(100, 1.0, 1.0, 0.1));
        Signal fewRx = new Signal(Kind.RX, 1000.0, new long[] {3, 3}, new long[] {3000, 300_000}, new long[] {0, 3},
                new long[] {0, 3}, 0.1);
        HopVerdict.Result clean = HopVerdict.hop(List.of(fwd, rev, fewRx), true, new double[] {0.006, 0.001});
        assertEquals(Mark.CLEAN, clean.mark(), "the RX component has 3 samples: not judged");
        assertEquals("temiz (ileri ve geri yön geçiş süresi): ortalama fark 0,00 ms (sınır ±1 ms); geç kalan paket payı artmadı; "
                + "örnek sayısı 100 / 100; eşleşmeyen gözlem önce %0,6, sonra %0,1 (bu oran tek başına kayıp anlamına gelmez).",
                clean.text());
        // one judged signal between the margins makes the hop belirsiz
        HopVerdict.Result mixed = HopVerdict.hop(List.of(fwd, flat(100, 1.0, 3.4, 0.1)), true, null);
        assertEquals(Mark.UNDECIDED, mixed.mark());
        assertEquals("belirsiz: ortalama +2,40 ms değişti; temiz sınırının (1 ms) üstünde, gecikme eşiğinin (5 ms) altında.", mixed.text());
    }

    @Test
    void aBelirsizHopShowsTheFirstMatchingReasonLine() {
        // a clock-floor signal (line 3) and a between-margins signal (line 4): line 3 is shown
        HopVerdict.Result r = HopVerdict.hop(List.of(flat(100, 1.0, 3.4, 0.1), flat(100, 1.0, 1.0, 3.0)), true, null);
        assertEquals(Reason.CLOCK_FLOOR, r.reason());
    }

    @Test
    void noWindowNoSamplesAndNoMatchedPacketsGiveNoMarker() {
        HopVerdict.Result noWindow = HopVerdict.hop(List.of(sig(500, 1.0, 0, 0, 0, 0, 0, 0, 0.1)), false, null);
        assertEquals(Reason.NO_WINDOW, noWindow.reason());
        assertNull(noWindow.mark().stripWord());
        HopVerdict.Result none = HopVerdict.hop(List.of(sig(0, 0, 0, 0, 0, 0, 0, 0, 0.1)), true, null);
        assertEquals(Mark.NO_DATA, none.mark());
        assertEquals("ölçüm yok", none.mark().word);
        assertNull(none.mark().stripWord());
        assertEquals("gecikme burada", Mark.DELAY.stripWord());
        assertEquals("temiz", Mark.CLEAN.stripWord());
    }

    @Test
    void theRuleValuesAreTheApprovedOnes() {
        assertEquals(5.0, HopVerdict.DELAY_MS);
        assertEquals(2, HopVerdict.RATIO);
        assertEquals(10, HopVerdict.MIN_SAMPLES);
        assertEquals(1, HopVerdict.SHARE_GROWTH_PER_HUNDRED);
        assertEquals(2.0, HopVerdict.CLOCK_FACTOR);
        assertEquals(1.0, HopVerdict.CLEAN_MS);
        assertThrows(IllegalArgumentException.class,
                () -> HopVerdict.judge(new Signal(Kind.RX, 1.5, new long[] {10, 10}, new long[] {1, 1}, new long[2], new long[2], 0.1)));
    }
}
