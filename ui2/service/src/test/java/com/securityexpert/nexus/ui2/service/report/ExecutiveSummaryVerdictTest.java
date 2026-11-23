package com.securityexpert.nexus.ui2.service.report;

import static com.securityexpert.nexus.ui2.service.report.ExecutiveSummaryBuilder.AT_RISK;
import static com.securityexpert.nexus.ui2.service.report.ExecutiveSummaryBuilder.HEALTHY;
import static com.securityexpert.nexus.ui2.service.report.ExecutiveSummaryBuilder.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.report.ExecutiveSummaryBuilder.Pillar;

/**
 * RPT-01 verdict mapping, unit level: {@link ExecutiveSummaryBuilder#estate(List)} and
 * {@link ExecutiveSummaryBuilder#tone(String)} against synthetic pillars. The document-level cases stay in
 * {@link ReportBuildersTest}; this class pins the pure mapping rules directly.
 */
class ExecutiveSummaryVerdictTest {

    private static Pillar pillar(String verdict) {
        return new Pillar("Synthetic pillar", verdict, "synthetic reason");
    }

    @Test
    void anyAtRiskPillarWinsOverUnknownAndHealthy() {
        assertEquals(AT_RISK, ExecutiveSummaryBuilder.estate(List.of(pillar(UNKNOWN), pillar(AT_RISK), pillar(HEALTHY))));
        assertEquals(AT_RISK, ExecutiveSummaryBuilder.estate(List.of(pillar(HEALTHY), pillar(AT_RISK))));
        assertEquals(AT_RISK, ExecutiveSummaryBuilder.estate(List.of(pillar(AT_RISK))));
    }

    @Test
    void unknownWinsOverHealthyWhenNoPillarIsAtRisk() {
        assertEquals(UNKNOWN, ExecutiveSummaryBuilder.estate(List.of(pillar(HEALTHY), pillar(UNKNOWN), pillar(HEALTHY))));
        assertEquals(UNKNOWN, ExecutiveSummaryBuilder.estate(List.of(pillar(UNKNOWN))));
    }

    @Test
    void allHealthyGivesHealthy() {
        assertEquals(HEALTHY, ExecutiveSummaryBuilder.estate(List.of(pillar(HEALTHY), pillar(HEALTHY), pillar(HEALTHY),
                pillar(HEALTHY))));
    }

    @Test
    void emptyPillarListCurrentlyGivesHealthy() {
        // Pinned as the actual behaviour (no pillar means no unknown evidence is seen). The production build always
        // passes four pillars, so this is not reachable from RPT-01 today; see the report that added this class.
        assertEquals(HEALTHY, ExecutiveSummaryBuilder.estate(List.of()));
    }

    @Test
    void toneMapsHealthyToOkAtRiskToBadAndEverythingElseToUnknown() {
        assertEquals("OK", ExecutiveSummaryBuilder.tone(HEALTHY));
        assertEquals("BAD", ExecutiveSummaryBuilder.tone(AT_RISK));
        assertEquals("UNKNOWN", ExecutiveSummaryBuilder.tone(UNKNOWN));
        assertEquals("UNKNOWN", ExecutiveSummaryBuilder.tone("SYNTHETIC_OTHER"));
        assertEquals("UNKNOWN", ExecutiveSummaryBuilder.tone(""));
    }

    @Test
    void toneIsExactMatchNotCaseInsensitive() {
        // Verdict strings are produced only by the builder constants; a lower-case value is not a known verdict.
        assertEquals("UNKNOWN", ExecutiveSummaryBuilder.tone("healthy"));
        assertEquals("UNKNOWN", ExecutiveSummaryBuilder.tone("at risk"));
    }
}
