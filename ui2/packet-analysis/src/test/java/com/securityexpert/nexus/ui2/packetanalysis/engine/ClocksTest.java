package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Clocks.Sample;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Reference clock.py behaviour, negative and unknown cases first (tests/test_analysis.py clock tests). */
class ClocksTest {

    @Test
    void noSamplesIsUnknown() {
        ClockModel m = Clocks.fitClock(List.of());
        assertNull(m.offset);
        assertEquals("unknown", m.confidence);
        assertEquals("Bidirectional matched packets are required", m.reason);
        assertEquals("0.0", String.valueOf(((PyFloat) m.epoch).value()));
    }

    @Test
    void oneWayClockIsUnknown() {
        // test_one_way_clock_unknown
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            samples.add(new Sample(i, i + 0.25, true));
        }
        ClockModel m = Clocks.fitClock(samples);
        assertNull(m.offset);
        assertNull(m.correct(10));
        assertEquals(100, m.samples);
        assertEquals(0.0, ClockModel.value(m.epoch));
    }

    @Test
    void fewerThanThreeSamplesPerDirectionIsUnknown() {
        List<Sample> samples = List.of(new Sample(0, 0.1, true), new Sample(1, 1.1, true), new Sample(2, 2.1, true),
                new Sample(3, 2.9, false), new Sample(4, 3.9, false));
        ClockModel m = Clocks.fitClock(samples);
        assertNull(m.offset);
        assertEquals(5, m.samples);
    }

    @Test
    void inconsistentEnvelopeIsInfeasible() {
        // test_clock_offset_drift_and_inconsistent_envelope (bad part): reverse delay above forward delay
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            samples.add(new Sample(i, i + 0.1, true));
            samples.add(new Sample(i, i + 0.2, false));
        }
        ClockModel m = Clocks.fitClock(samples);
        assertNull(m.offset);
        assertEquals("No feasible jitter-bounded bidirectional percentile envelope", m.reason);
    }

    @Test
    void driftAbove5000PpmIsRejected() {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            double t = i * 0.5;
            samples.add(new Sample(t, t + t * 0.01 + 0.001, true));
            samples.add(new Sample(t, t + t * 0.01 - 0.001, false));
        }
        ClockModel m = Clocks.fitClock(samples);
        assertNull(m.offset);
        assertEquals("Estimated drift exceeds 5000 ppm", m.reason);
    }

    @Test
    void offsetDriftAndUncertainty() {
        // test_clock_offset_drift_and_inconsistent_envelope (good part)
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            double t = i * 0.5;
            for (boolean forward : new boolean[] {true, false}) {
                double jitter = 0.0002 * (i % 3);
                samples.add(new Sample(t, t + 0.4 + t * 30e-6 + (forward ? 1 : -1) * (0.002 + jitter), forward));
            }
        }
        ClockModel m = Clocks.fitClock(samples);
        assertEquals(0.4, ClockModel.value(m.offset), 0.00001);
        assertEquals(30, ClockModel.value(m.drift) * 1e6, 1);
        assertEquals(0.002, ClockModel.value(m.uncertainty), 0.00001);
        assertEquals("estimated", m.confidence);
        assertTrue(m.reason.endsWith("; drift fitted"));
    }

    @Test
    void driftOverAnHourLongWindow() {
        // test_clock_drift_over_hour_long_window
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            for (boolean forward : new boolean[] {true, false}) {
                samples.add(new Sample(i * 40.0, i * 40 + 0.12 + i * 40 * 40e-6 + (forward ? 0.001 : -0.001), forward));
            }
        }
        ClockModel m = Clocks.fitClock(samples);
        assertEquals(0.12, ClockModel.value(m.offset), 1e-5);
        assertEquals(40, ClockModel.value(m.drift) * 1e6, 0.1);
    }

    @Test
    void shortSpanFitsOffsetOnly() {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            samples.add(new Sample(i * 0.1, i * 0.1 + 0.05 + 0.001, true));
            samples.add(new Sample(i * 0.1, i * 0.1 + 0.05 - 0.001, false));
        }
        ClockModel m = Clocks.fitClock(samples);
        assertEquals(0.05, ClockModel.value(m.offset), 1e-9);
        assertEquals(0.0, ClockModel.value(m.drift));
        assertTrue(m.reason.endsWith("; offset-only fit"));
    }

    @Test
    void sharedClockNeedsSpanSamplesAndPositiveNearZeroTransit() {
        assertNull(Clocks.inferSharedClock(List.of()));
        List<Sample> shortSpan = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            shortSpan.add(new Sample(i * 0.1, i * 0.1 + 0.0001, i % 2 == 0));
        }
        assertNull(Clocks.inferSharedClock(shortSpan), "span below 5 s");
        List<Sample> few = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            few.add(new Sample(i, i + 0.0001, i < 7));
        }
        assertNull(Clocks.inferSharedClock(few), "fewer than 8 samples in one direction");
        List<Sample> negative = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            negative.add(new Sample(i, i - 0.0001, true));
            negative.add(new Sample(i, i - 0.0001, false));
        }
        assertNull(Clocks.inferSharedClock(negative), "forward transit negative");
        List<Sample> slow = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            slow.add(new Sample(i, i + 0.001, true));
            slow.add(new Sample(i, i - 0.001, false));
        }
        assertNull(Clocks.inferSharedClock(slow), "transit above 0.25 ms");
        List<Sample> shared = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            shared.add(new Sample(i, i + 0.0001, true));
            shared.add(new Sample(i, i - 0.0001, false));
        }
        Double bound = Clocks.inferSharedClock(shared);
        assertEquals(0.0001, bound, 1e-9);
    }

    @Test
    void transitQualityKeepsNegativeOutliersAsEvidence() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("p50_ms", -0.5);
        stats.put("negative_outliers", 3L);
        Map<String, Object> q = Clocks.transitQuality(stats, 0.0001);
        assertEquals(true, q.get("systematic_negative"));
        assertEquals(0.001, q.get("latency_uncertainty_ms"));
        assertEquals(List.of("3 negative transit outlier(s) exceed ±0.001 ms timestamp/offset uncertainty; retained as quality evidence"),
                q.get("quality_notes"));
        stats.put("p50_ms", null);
        stats.put("negative_outliers", null);
        Map<String, Object> none = Clocks.transitQuality(stats, 2.0);
        assertFalse((Boolean) none.get("systematic_negative"));
        assertEquals(List.of(), none.get("quality_notes"));
    }

    @Test
    void clockJsonKeepsIntAndFloatDistinction() {
        ClockModel ref = ClockModel.of(new com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt(0),
                new com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt(0), new PyFloat(1700000000.0),
                new com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt(0), "reference", "Reference capture clock", 0);
        assertEquals("{\"offset\": 0, \"drift\": 0, \"epoch\": 1700000000.0, \"uncertainty\": 0, \"confidence\": \"reference\", "
                + "\"reason\": \"Reference capture clock\", \"samples\": 0, \"evidence\": [], \"domain\": null, "
                + "\"source_domain\": null, \"timestamp_jitter\": 0.0, \"drift_ppm\": 0.0}",
                com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter.dumps(ref.json(), false));
    }
}
