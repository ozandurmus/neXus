package com.securityexpert.nexus.ui2.service.logdetect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.logdetect.LogSeverity.Decision;
import com.securityexpert.nexus.ui2.service.logdetect.LogSeverity.Input;

/** Layer A severity by rule (design sections 3.1, 3.4, 7.1). */
class LogSeverityTest {
    @Test
    void panVendorSeverityMapsToTheDocumentedWeights() {
        assertEquals(1.0, LogSeverity.panWeight("critical").getAsDouble());
        assertEquals(0.8, LogSeverity.panWeight("HIGH").getAsDouble());
        assertEquals(0.5, LogSeverity.panWeight("medium").getAsDouble());
        assertEquals(0.3, LogSeverity.panWeight("low").getAsDouble());
        assertEquals(0.15, LogSeverity.panWeight("informational").getAsDouble());
        assertTrue(LogSeverity.panWeight("surprising").isEmpty(), "an unknown value is not a weight");
        assertTrue(LogSeverity.panWeight(null).isEmpty());
    }

    @Test
    void syslogPriorityMapsLikeTheDesignTable() {
        assertEquals(1.0, LogSeverity.syslogWeight("crit").getAsDouble());
        assertEquals(0.5, LogSeverity.syslogWeight("err").getAsDouble());
        assertEquals(0.3, LogSeverity.syslogWeight("warning").getAsDouble());
        assertEquals(0.15, LogSeverity.syslogWeight("info").getAsDouble());
        assertEquals(0.05, LogSeverity.syslogWeight("debug").getAsDouble());
    }

    @Test
    void aProvisionalWeightOneIsCappedToAttentionAndSaysSo() {
        Decision d = LogSeverity.decide(new Input(1.0, LogProvenance.PROVISIONAL, false, false));
        assertEquals("ATTENTION", d.severity());
        assertEquals("WEIGHT_1_PROVISIONAL_CAPPED", d.rule());
        assertEquals(List.of("PROVISIONAL_CAP"), d.labels());
    }

    @Test
    void aCitedWeightOneIsCritical() {
        assertEquals("CRITICAL", LogSeverity.decide(new Input(1.0, LogProvenance.MEASURED, false, false)).severity());
        assertEquals("CRITICAL", LogSeverity.decide(new Input(1.0, LogProvenance.VENDOR_DOC, false, false)).severity());
    }

    @Test
    void aStatisticalOrLowerWeightFindingIsNeverCritical() {
        for (double w : new double[] {0.05, 0.15, 0.3, 0.5, 0.8, 0.99}) {
            for (LogProvenance p : LogProvenance.values()) {
                assertFalse(LogSeverity.decide(new Input(w, p, false, false)).severity().equals("CRITICAL"), w + " " + p);
            }
        }
    }

    @Test
    void weightsFromHalfAreAttentionAndBelowAreInfo() {
        assertEquals("ATTENTION", LogSeverity.decide(new Input(0.8, LogProvenance.PROVISIONAL, false, false)).severity());
        assertEquals("ATTENTION", LogSeverity.decide(new Input(0.5, LogProvenance.PROVISIONAL, false, false)).severity());
        assertEquals("INFO", LogSeverity.decide(new Input(0.3, LogProvenance.PROVISIONAL, false, false)).severity());
        assertEquals("INFO", LogSeverity.decide(new Input(0.15, LogProvenance.PROVISIONAL, false, false)).severity());
    }

    @Test
    void chronicAndFleetBackgroundAreInfoWithTheirLabel() {
        Decision chronic = LogSeverity.decide(new Input(0.8, LogProvenance.PROVISIONAL, true, false));
        assertEquals("INFO", chronic.severity());
        assertEquals(List.of("CHRONIC"), chronic.labels());
        Decision fleet = LogSeverity.decide(new Input(0.8, LogProvenance.PROVISIONAL, false, true));
        assertEquals("INFO", fleet.severity());
        assertEquals(List.of("FLEET_BACKGROUND"), fleet.labels());
    }

    @Test
    void rateChangeNeedsFiveTimesTheMedianAndTheMinimumCount() {
        assertTrue(LogSeverity.rateChange(45, 0, true));
        assertFalse(LogSeverity.rateChange(2, 0, true), "below the marker minimum of 3");
        assertFalse(LogSeverity.rateChange(9, 2, true), "below 5 x median");
        assertTrue(LogSeverity.rateChange(10, 2, true));
        assertFalse(LogSeverity.rateChange(4, 0, false), "5 is the minimum for other templates");
    }

    @Test
    void medianOfCountsIncludesZeroDays() {
        assertEquals(0, LogSeverity.median(List.of()));
        assertEquals(2, LogSeverity.median(List.of(0L, 2L, 9L)));
        assertEquals(1.5, LogSeverity.median(List.of(0L, 1L, 2L, 9L)));
    }

    @Test
    void severityRankOrdersCriticalFirst() {
        assertTrue(LogSeverity.rank("CRITICAL") > LogSeverity.rank("ATTENTION"));
        assertTrue(LogSeverity.rank("ATTENTION") > LogSeverity.rank("UNKNOWN"));
        assertTrue(LogSeverity.rank("UNKNOWN") > LogSeverity.rank("INFO"));
    }
}
