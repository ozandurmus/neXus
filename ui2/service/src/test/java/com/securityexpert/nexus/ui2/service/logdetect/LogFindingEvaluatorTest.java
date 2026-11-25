package com.securityexpert.nexus.ui2.service.logdetect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.Baseline;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.CoverageRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.EventRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.FindingRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.IndexState;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.Pointer;

/** The noise rules, the hardware exemption and the acknowledgement rule of layer A (design sections 3.4 and 7.3). */
class LogFindingEvaluatorTest {
    private static final String DEV = "FW-TANGO-04";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);

    private final InMemoryLogDetectionRepository repo = new InMemoryLogDetectionRepository();
    private final LogFindingEvaluator evaluator = new LogFindingEvaluator(repo);

    {
        repo.historyOverride = 30; // a device with a month of history unless a test says otherwise
    }

    private void evidence(String source, String marker, String key, int count, double weight) {
        String chunk = "c-" + marker + key + count;
        LocalDateTime t = DAY.atTime(10, 0);
        repo.events.add(new EventRow("e-" + chunk, chunk, DEV, source, marker, key, t, weight, count, t, t.plusMinutes(5),
                List.of(new Pointer(chunk, 0, 80))));
        repo.states.put(chunk, new IndexState(chunk, "logmask-v8", "markers-p1-1", DEV, source, "job", DAY, "INDEXED", 10, 1, 0));
        repo.coverage.put(DEV + source + chunk, new CoverageRow(DEV, source, chunk, DAY, "EVALUATED", "LAYER_A_ONLY", 30));
    }

    private FindingRow only() {
        assertEquals(1, repo.findings.size());
        return repo.findings.values().iterator().next();
    }

    @Test
    void aMarkerSeenForTheFirstTimeIsAnAttentionFinding() {
        evidence("cp_messages", "kern.oops", "", 3, 0.8);
        assertEquals(1, evaluator.evaluate(DEV, DAY));
        assertEquals("ATTENTION", only().severity());
        assertEquals("EVALUATED", only().coverageState());
    }

    @Test
    void aChronicMarkerWithinItsBaselineIsInfoButARateChangeRaisesIt() {
        repo.baselines.put("cp_messages|kern.oops", new Baseline(20, 19, List.of(2L, 3L, 2L, 2L, 3L, 2L, 2L, 3L, 2L, 2L, 3L, 2L, 2L, 3L, 2L, 2L, 3L, 2L, 2L, 0L)));
        evidence("cp_messages", "kern.oops", "", 3, 0.8);
        evaluator.evaluate(DEV, DAY);
        assertEquals("INFO", only().severity());
        assertTrue(only().chronic());
        assertEquals(List.of("CHRONIC"), only().labels());

        repo.events.clear();
        repo.findings.clear();
        evidence("cp_messages", "kern.oops", "", 200, 0.8);
        evaluator.evaluate(DEV, DAY);
        assertEquals("ATTENTION", only().severity(), "a rate change leaves the chronic state");
        assertFalse(only().chronic());
    }

    @Test
    void chronicNeedsSevenUsableBaselineDays() {
        repo.baselines.put("cp_messages|kern.oops", new Baseline(5, 5, List.of(2L, 2L, 2L, 2L, 2L)));
        evidence("cp_messages", "kern.oops", "", 3, 0.8);
        evaluator.evaluate(DEV, DAY);
        assertFalse(only().chronic(), "five days is not a baseline");
        assertEquals("ATTENTION", only().severity());
    }

    @Test
    void aWeightOneMarkerIsExemptFromChronicAndFleetBackgroundAndStaysAnAttentionFindingWhileProvisional() {
        repo.baselines.put("cp_messages|hw.edac.ue", new Baseline(20, 20, List.of(5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L)));
        repo.cohort = 40;
        repo.prevalence = 30;
        evidence("cp_messages", "hw.edac.ue", "CPU_SrcID#0_MC#0_Chan#0_DIMM#0", 5, 1.0);
        evaluator.evaluate(DEV, DAY);
        FindingRow f = only();
        assertEquals("ATTENTION", f.severity());
        assertEquals("WEIGHT_1_PROVISIONAL_CAPPED", f.severityRule());
        assertEquals(List.of("PROVISIONAL_CAP"), f.labels());
        assertFalse(f.chronic());
        assertFalse(f.fleetBackground());
    }

    @Test
    void fleetBackgroundAppliesToOopsButNotToCorrectedMemoryErrors() {
        repo.cohort = 20;
        repo.prevalence = 12;
        evidence("cp_messages", "kern.oops", "", 3, 0.8);
        evidence("cp_messages", "hw.edac.ce", "CPU_SrcID#0_MC#0_Chan#0_DIMM#0", 12, 0.8);
        evaluator.evaluate(DEV, DAY);
        FindingRow oops = repo.findings.values().stream().filter(f -> f.markerId().equals("kern.oops")).findFirst().orElseThrow();
        FindingRow ce = repo.findings.values().stream().filter(f -> f.markerId().equals("hw.edac.ce")).findFirst().orElseThrow();
        assertEquals("INFO", oops.severity());
        assertTrue(oops.fleetBackground());
        assertEquals("ATTENTION", ce.severity(), "hw.edac.ce is exempt from fleet background (rule 5)");
    }

    @Test
    void aSmallCohortGivesNoPrevalenceJudgement() {
        repo.cohort = 4;
        repo.prevalence = 4;
        evidence("cp_messages", "kern.oops", "", 3, 0.8);
        evaluator.evaluate(DEV, DAY);
        assertFalse(only().fleetBackground());
        assertEquals("ATTENTION", only().severity());
    }

    @Test
    void panVendorSeverityIsAFindingKindWithTheSameRules() {
        evidence("pan_system", "sev.pan.critical", "general", 6, 1.0);
        evidence("pan_system", "sev.pan.medium", "auth", 2, 0.5);
        evaluator.evaluate(DEV, DAY);
        FindingRow critical = repo.findings.values().stream().filter(f -> f.markerId().equals("sev.pan.critical")).findFirst().orElseThrow();
        assertEquals("VENDOR_SEVERITY", critical.kind());
        assertEquals("ATTENTION", critical.severity(), "a vendor value without cited provenance is capped");
        assertTrue(repo.findings.values().stream().noneMatch(f -> f.severity().equals("CRITICAL")));
    }

    @Test
    void anUnknownMarkerIdIsNotAFinding() {
        evidence("cp_messages", "made.up", "", 9, 0.8);
        assertEquals(0, evaluator.evaluate(DEV, DAY));
        assertTrue(repo.findings.isEmpty());
    }

    @Test
    void findingsWithinTenMinutesShareAnIncidentGroupAndFarOnesDoNot() {
        LocalDateTime t = DAY.atTime(2, 0);
        repo.events.add(new EventRow("a", "ca", DEV, "cp_messages", "hw.mce", "", t, 0.8, 2, t, t.plusMinutes(1), List.of(new Pointer("ca", 0, 5))));
        repo.events.add(new EventRow("b", "cb", DEV, "cp_messages", "kern.oops", "", t.plusMinutes(8), 0.8, 2, t.plusMinutes(8), t.plusMinutes(9), List.of(new Pointer("cb", 0, 5))));
        repo.events.add(new EventRow("c", "cc", DEV, "cp_messages", "io.error", "sda", t.plusHours(5), 0.8, 2, t.plusHours(5), t.plusHours(5).plusMinutes(1), List.of(new Pointer("cc", 0, 5))));
        evaluator.evaluate(DEV, DAY);
        var by = repo.findings.values().stream().collect(java.util.stream.Collectors.toMap(FindingRow::markerId, FindingRow::incidentGroup));
        assertEquals(by.get("hw.mce"), by.get("kern.oops"));
        assertFalse(by.get("hw.mce").equals(by.get("io.error")));
    }

    @Test
    void anAcknowledgedFindingStaysAcknowledgedUntilItsSeverityRisesOrNewEvidenceComesLater() {
        evidence("cp_messages", "kern.oops", "", 3, 0.8);
        evaluator.evaluate(DEV, DAY);
        String id = only().findingId();
        repo.acknowledge("actor", id, "known");
        evaluator.evaluate(DEV, DAY); // same evidence again
        assertEquals("ACKNOWLEDGED", repo.findings.get(id).reviewState());

        // New evidence more than 24 hours after the evidence it was acknowledged on.
        LocalDateTime late = DAY.atTime(10, 0).plusHours(30);
        repo.events.add(new EventRow("late", "late", DEV, "cp_messages", "kern.oops", "", late, 0.8, 1, late, late, List.of(new Pointer("late", 0, 5))));
        repo.states.put("late", new IndexState("late", "logmask-v8", "markers-p1-1", DEV, "cp_messages", "job", DAY, "INDEXED", 1, 1, 0));
        // dayEvidence groups by device-local day of the bucket: the late row belongs to the next day, a new finding.
        evaluator.evaluate(DEV, DAY.plusDays(1));
        assertEquals(2, repo.findings.size());
        assertEquals("ACKNOWLEDGED", repo.findings.get(id).reviewState());
        assertEquals("OPEN", repo.findings.values().stream().filter(f -> !f.findingId().equals(id)).findFirst().orElseThrow().reviewState());
    }

    @Test
    void aSeverityRiseReopensTheAcknowledgedFindingAndRecordsAReopen() {
        repo.baselines.put("cp_messages|kern.oops", new Baseline(20, 19, List.of(2L, 3L, 2L, 2L, 3L, 2L, 2L, 3L, 2L, 2L, 3L, 2L, 2L, 3L, 2L, 2L, 3L, 2L, 2L, 0L)));
        evidence("cp_messages", "kern.oops", "", 3, 0.8);
        evaluator.evaluate(DEV, DAY);
        assertEquals("INFO", only().severity());
        String id = only().findingId();
        repo.acknowledge("actor", id, "known");
        evaluator.evaluate(DEV, DAY);
        assertEquals("ACKNOWLEDGED", repo.findings.get(id).reviewState());
        assertTrue(repo.reopens.isEmpty(), "no change, no reopen");

        repo.events.clear();
        evidence("cp_messages", "kern.oops", "", 200, 0.8); // a rate change: INFO -> ATTENTION
        evaluator.evaluate(DEV, DAY);
        assertEquals("ATTENTION", repo.findings.get(id).severity());
        assertEquals("OPEN", repo.findings.get(id).reviewState());
        assertEquals(1, repo.reopens.size());
        assertEquals(LogFindingEvaluator.SYSTEM_ACTOR + "|" + id + "|severity rose from INFO to ATTENTION", repo.reopens.get(0));
    }

    @Test
    void anOpenFindingThatRisesIsNotAReopen() {
        repo.baselines.put("cp_messages|kern.oops", new Baseline(20, 19, List.of(2L, 3L, 2L, 2L, 3L, 2L, 2L, 3L, 2L, 2L, 3L, 2L, 2L, 3L, 2L, 2L, 3L, 2L, 2L, 0L)));
        evidence("cp_messages", "kern.oops", "", 3, 0.8);
        evaluator.evaluate(DEV, DAY);
        repo.events.clear();
        evidence("cp_messages", "kern.oops", "", 200, 0.8);
        evaluator.evaluate(DEV, DAY);
        assertEquals("ATTENTION", only().severity());
        assertTrue(repo.reopens.isEmpty(), "nothing was reviewed, so nothing is reopened");
    }

    @Test
    void carryReviewReopensOnASeverityRiseAndKeepsOtherwise() {
        evidence("cp_messages", "kern.oops", "", 3, 0.8);
        evaluator.evaluate(DEV, DAY);
        FindingRow open = only();
        FindingRow acked = repo.acknowledge("actor", open.findingId(), null).orElseThrow();
        // The same finding with a higher severity raises it again.
        FindingRow higher = new FindingRow(acked.findingId(), acked.deviceId(), acked.sourceKey(), acked.ruleId(), acked.ruleVersion(),
                acked.kind(), acked.markerId(), acked.keyToken(), acked.windowDay(), acked.windowStart(), acked.windowEnd(), "CRITICAL",
                acked.severityRule(), acked.severityWeight(), "MEASURED", acked.eventCount(), acked.peakBucketCount(), acked.historyDays(),
                acked.baselineDays(), acked.presenceDays(), acked.cohortDevices(), acked.prevalenceDevices(), false, false,
                List.of(), acked.incidentGroup(), acked.coverageState(), acked.pointers(), acked.maskingRuleSet(),
                acked.markerListVersion(), acked.configVersion(), "OPEN", null, null, null, null);
        assertEquals("OPEN", LogFindingEvaluator.carryReview(higher, Optional.of(acked)).reviewState());
        FindingRow same = new FindingRow(acked.findingId(), acked.deviceId(), acked.sourceKey(), acked.ruleId(), acked.ruleVersion(),
                acked.kind(), acked.markerId(), acked.keyToken(), acked.windowDay(), acked.windowStart(), acked.windowEnd(), "ATTENTION",
                acked.severityRule(), acked.severityWeight(), "PROVISIONAL", acked.eventCount() + 1, acked.peakBucketCount(), acked.historyDays(),
                acked.baselineDays(), acked.presenceDays(), acked.cohortDevices(), acked.prevalenceDevices(), false, false,
                List.of(), acked.incidentGroup(), acked.coverageState(), acked.pointers(), acked.maskingRuleSet(),
                acked.markerListVersion(), acked.configVersion(), "OPEN", null, null, null, null);
        FindingRow kept = LogFindingEvaluator.carryReview(same, Optional.of(acked));
        assertEquals("ACKNOWLEDGED", kept.reviewState());
        assertEquals(acked.eventCount() + 1, kept.eventCount(), "the counts keep accumulating on the open finding");
        assertTrue(kept.reviewAt() != null && kept.reviewAt().isBefore(Instant.now().plusSeconds(5)));
    }

    @Test
    void theCoverageStateOfAFindingIsTheFirstUnevaluatedWindowNeverAHealthyDefault() {
        evidence("cp_messages", "kern.oops", "", 3, 0.8);
        repo.coverage.put("gap", new CoverageRow(DEV, "cp_messages", "job-gap", DAY, "GAP", "GAP_ROTATED_AWAY", 30));
        evaluator.evaluate(DEV, DAY);
        assertEquals("GAP", only().coverageState());
    }

    @Test
    void anAcknowledgementMadeBetweenTheEnginesReadAndItsWriteSurvivesTheWrite() {
        evidence("cp_messages", "kern.oops", "", 3, 0.8);
        evaluator.evaluate(DEV, DAY);
        String id = only().findingId();
        assertEquals("OPEN", only().reviewState());

        // The operator acknowledges after the engine read the finding (still OPEN) and before the engine stores it.
        var racing = new InMemoryLogDetectionRepository() {
            boolean acknowledged;

            @Override
            public Optional<FindingRow> finding(String findingId) {
                Optional<FindingRow> read = super.finding(findingId);
                if (!acknowledged) {
                    acknowledged = true;
                    acknowledge("actor-synthetic", findingId, "known");
                }
                return read;
            }
        };
        racing.historyOverride = 30;
        racing.events.addAll(repo.events);
        racing.states.putAll(repo.states);
        racing.coverage.putAll(repo.coverage);
        racing.findings.putAll(repo.findings);
        assertEquals(1, new LogFindingEvaluator(racing).evaluate(DEV, DAY));
        FindingRow after = racing.findings.get(id);
        assertEquals("ACKNOWLEDGED", after.reviewState(), "the evidence write never puts the review back to OPEN");
        assertEquals("actor-synthetic", after.reviewBy());
        assertTrue(racing.reopens.isEmpty(), "only a severity rise reopens, through reopenFinding");
    }
}
