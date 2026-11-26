package com.securityexpert.nexus.ui2.service.logdetect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.EventRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.FindingRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.IndexState;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.PendingChunk;
import com.securityexpert.nexus.ui2.service.logdetect.LogDetectionService.PassSummary;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadFixture;

/**
 * The pass as a whole: a chunk that cannot be stored never stalls the chunks behind it, a stored key always fits the
 * V272 character set, and the fleet-background verdict of a backlog is made on the whole cohort, not on the part of it
 * that an early pass had indexed. Synthetic lines and masked device names only.
 */
class LogDetectionServiceTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);
    private static final DateTimeFormatter GAIA = DateTimeFormatter.ofPattern("MMM d HH:mm:ss yyyy", Locale.ENGLISH);
    /** The V272 {@code key_token} CHECK. */
    private static final Pattern STORED_KEY = Pattern.compile("[A-Za-z0-9#_.:-]{0,64}");

    private static String line(int minute, String text) {
        return lineOn(DAY, minute, text);
    }

    private static String lineOn(LocalDate day, int minute, String text) {
        LocalDateTime t = day.atStartOfDay().plusHours(2).plusMinutes(minute);
        return GAIA.format(t) + " edge-fw-01 " + text + "\n";
    }

    /** A repository that refuses what the database refuses (the key CHECK) and can be told to fail for a chunk. */
    private static final class StrictRepository extends InMemoryLogDetectionRepository {
        Set<String> failStore = Set.of();
        boolean failStateWrite;
        /** When true, storing a finding fails like a lost connection or a refused CHECK during evaluation. */
        boolean failEvaluation;
        /** Storing a finding of this device fails (the other devices are stored). */
        String failEvaluationOf;

        @Override
        public void upsertFinding(FindingRow row) {
            if (failEvaluation || row.deviceId().equals(failEvaluationOf)) {
                throw new IllegalStateException("finding store failure (synthetic)");
            }
            super.upsertFinding(row);
        }

        @Override
        public void replaceChunkIndex(IndexState state, List<EventRow> rows) {
            if (failStore.contains(state.chunkId())) throw new IllegalStateException("store failure (synthetic)");
            for (EventRow e : rows) {
                if (!STORED_KEY.matcher(e.keyToken()).matches()) {
                    throw new IllegalStateException("violates check constraint log_event_count key_token");
                }
            }
            super.replaceChunkIndex(state, rows);
        }

        @Override
        public void recordIndexState(IndexState state) {
            if (failStateWrite && failStore.contains(state.chunkId())) throw new IllegalStateException("state write failure (synthetic)");
            super.recordIndexState(state);
        }
    }

    private static LogDetectionService service(StrictRepository repo, SystemLogReadFixture fixture) {
        return new LogDetectionService(repo, fixture.service(), new HardwareMarkers(new LogKeyToken(SystemLogReadFixture.PRIVACY_KEY)));
    }

    private static PendingChunk pending(String chunk, String device) {
        return new PendingChunk(chunk, device, "cp_messages", "job-" + chunk, DAY);
    }

    @Test
    void anOomLineWithAPlusInTheProcessNameIsIndexedAndStoresOnlyAKeyedToken(@TempDir Path dir) throws Exception {
        var fixture = new SystemLogReadFixture(dir);
        fixture.chunk("c-oom", SystemLogReadFixture.bytes(line(1, "kernel: Out of memory: Killed process 123 (g++) score 900 or sacrifice child")));
        var repo = new StrictRepository();
        repo.pending.add(pending("c-oom", "FW-TANGO-04"));
        PassSummary pass = service(repo, fixture).runOnce(10, Duration.ofMinutes(1));
        assertEquals(1, pass.chunksIndexed());
        assertEquals(0, pass.chunksRefused());
        assertEquals("INDEXED", repo.states.get("c-oom").status());
        assertEquals(1, repo.events.size());
        String key = repo.events.get(0).keyToken();
        assertTrue(key.matches("k-[0-9a-f]{12}"), key);
        assertFalse(repo.events.toString().contains("g++"), "the process name is not stored");
    }

    @Test
    void aChunkThatThrowsIsRecordedUnreadableAndTheQueueBehindItIsStillProcessed(@TempDir Path dir) throws Exception {
        var fixture = new SystemLogReadFixture(dir);
        fixture.chunk("c-bad", SystemLogReadFixture.bytes(line(1, "kernel: Out of memory: Killed process 1 (fwk_wd) score 900")));
        fixture.chunk("c-good", SystemLogReadFixture.bytes(line(2, "kernel: BUG: unable to handle kernel paging request at 0x0")));
        var repo = new StrictRepository();
        repo.failStore = Set.of("c-bad");
        repo.pending.add(pending("c-bad", "FW-TANGO-04"));
        repo.pending.add(pending("c-good", "FW-SIERRA-06"));
        PassSummary pass = service(repo, fixture).runOnce(10, Duration.ofMinutes(1));
        assertTrue(pass.ran());
        assertEquals(1, pass.chunksIndexed());
        assertEquals(1, pass.chunksRefused());
        assertEquals("UNREADABLE", repo.states.get("c-bad").status());
        assertEquals("INDEXED", repo.states.get("c-good").status());
        assertTrue(repo.events.stream().noneMatch(e -> e.chunkId().equals("c-bad")), "no evidence of the failed chunk remains");
        assertEquals(1, repo.findings.size(), "the chunk behind the bad one reached its finding");
        var failed = repo.coverage.get("FW-TANGO-04|cp_messages|job-c-bad");
        assertEquals("UNPARSED_HIGH", failed.state(), "an unreadable window is UNKNOWN to the reader, never evaluated");
        assertEquals("CHUNK_UNREADABLE", failed.reason());
    }

    @Test
    void aChunkWhoseFailureCannotEvenBeRecordedStillDoesNotStopThePass(@TempDir Path dir) throws Exception {
        var fixture = new SystemLogReadFixture(dir);
        fixture.chunk("c-bad", SystemLogReadFixture.bytes(line(1, "kernel: BUG: unable to handle kernel paging request at 0x0")));
        fixture.chunk("c-good", SystemLogReadFixture.bytes(line(2, "kernel: BUG: unable to handle kernel paging request at 0x0")));
        var repo = new StrictRepository();
        repo.failStore = Set.of("c-bad");
        repo.failStateWrite = true;
        repo.pending.add(pending("c-bad", "FW-TANGO-04"));
        repo.pending.add(pending("c-good", "FW-SIERRA-06"));
        PassSummary pass = service(repo, fixture).runOnce(10, Duration.ofMinutes(1));
        assertEquals(1, pass.chunksIndexed());
        assertEquals(1, pass.chunksRefused());
        assertEquals("INDEXED", repo.states.get("c-good").status());
    }

    @Test
    void aBacklogIndexedAcrossPassesGetsTheFleetBackgroundVerdictOfTheWholeCohort(@TempDir Path dir) throws Exception {
        var fixture = new SystemLogReadFixture(dir);
        var repo = new StrictRepository();
        repo.computeFleet = true;
        repo.historyOverride = 30;
        int devices = LogSeverity.MIN_COHORT; // every device shows the same kernel oops: fleet background once all are seen
        for (int i = 0; i < devices; i++) {
            String chunk = "c-" + i;
            fixture.chunk(chunk, SystemLogReadFixture.bytes(line(i, "kernel: BUG: unable to handle kernel paging request at 0x0")));
            repo.pending.add(pending(chunk, "FW-DEV-" + i));
        }
        LogDetectionService service = service(repo, fixture);
        service.runOnce(4, Duration.ofMinutes(1));
        assertTrue(repo.findings.values().stream().allMatch(f -> f.severity().equals("ATTENTION") && !f.fleetBackground()),
                "after the first pass the cohort is still smaller than the minimum: no prevalence judgement");
        service.runOnce(4, Duration.ofMinutes(1));
        service.runOnce(4, Duration.ofMinutes(1));
        assertEquals(devices, repo.findings.size());
        for (FindingRow f : repo.findings.values()) {
            assertTrue(f.fleetBackground(), "device " + f.deviceId() + " is judged on the whole cohort, whatever pass indexed it");
            assertEquals("INFO", f.severity());
            assertEquals(devices, f.cohortDevices());
        }
    }

    @Test
    void aDeviceDayWhoseEvaluationFailsIsUnknownNeverNoFindingsAndIsEvaluatedAgainOnTheNextPass(@TempDir Path dir) throws Exception {
        var fixture = new SystemLogReadFixture(dir);
        fixture.chunk("c-ev", SystemLogReadFixture.bytes(line(1, "kernel: BUG: unable to handle kernel paging request at 0x0")));
        var repo = new StrictRepository();
        repo.historyOverride = 30; // a month of history: a clean window would read EVALUATED
        repo.failEvaluation = true;
        repo.pending.add(pending("c-ev", "FW-TANGO-04"));
        LogDetectionService service = service(repo, fixture);

        PassSummary first = service.runOnce(10, Duration.ofMinutes(1));
        assertTrue(first.ran(), "an evaluation failure is isolated to its device-day, the pass itself completes");
        assertEquals("INDEXED", repo.states.get("c-ev").status());
        assertTrue(repo.findings.isEmpty());
        var window = repo.coverage.get("FW-TANGO-04|cp_messages|job-c-ev");
        assertEquals(LogCoverage.EVALUATION_FAILED, window.state(), "a day whose findings were not evaluated is never EVALUATED");
        assertFalse(LogCoverage.evaluated(window.state()));
        var chip = LogFindingReadService.chip("FW-TANGO-04", List.of(), List.copyOf(repo.coverage.values()));
        assertEquals("UNKNOWN", chip.get("state"), "UNKNOWN is never shown as NO_FINDINGS");

        repo.failEvaluation = false;
        assertTrue(repo.pendingChunks("r", "m", 10).isEmpty(), "the chunk is INDEXED and no longer pending");
        PassSummary second = service.runOnce(10, Duration.ofMinutes(1));
        assertEquals(0, second.chunksIndexed());
        assertEquals(1, second.findings(), "the failed day is evaluated again although none of its chunks is pending");
        assertEquals(1, repo.findings.size());
        assertEquals("EVALUATED", repo.coverage.get("FW-TANGO-04|cp_messages|job-c-ev").state());
        assertEquals("FINDINGS", LogFindingReadService.chip("FW-TANGO-04", List.copyOf(repo.findings.values()),
                List.copyOf(repo.coverage.values())).get("state"));
        assertTrue(repo.failedEvaluations(10).isEmpty());
    }

    @Test
    void oneDeviceDayThatFailsDoesNotStopTheOtherDevicesOfTheDay(@TempDir Path dir) throws Exception {
        var fixture = new SystemLogReadFixture(dir);
        fixture.chunk("c-a", SystemLogReadFixture.bytes(line(1, "kernel: BUG: unable to handle kernel paging request at 0x0")));
        fixture.chunk("c-b", SystemLogReadFixture.bytes(line(2, "kernel: BUG: unable to handle kernel paging request at 0x0")));
        var repo = new StrictRepository();
        repo.failEvaluationOf = "FW-ALPHA-01";
        repo.historyOverride = 30;
        repo.pending.add(pending("c-a", "FW-ALPHA-01"));
        repo.pending.add(pending("c-b", "FW-BRAVO-02"));
        service(repo, fixture).runOnce(10, Duration.ofMinutes(1));
        assertEquals(LogCoverage.EVALUATION_FAILED, repo.coverage.get("FW-ALPHA-01|cp_messages|job-c-a").state());
        assertEquals("EVALUATED", repo.coverage.get("FW-BRAVO-02|cp_messages|job-c-b").state());
        assertEquals(1, repo.findings.size());
        assertEquals("FW-BRAVO-02", repo.findings.values().iterator().next().deviceId());
    }

    @Test
    void newestDaysAreIndexedFirstAndVerdictsStayHonestAcrossOutOfOrderBackfill(@TempDir Path dir) throws Exception {
        var fixture = new SystemLogReadFixture(dir);
        var repo = new StrictRepository();
        String slot = "CPU_SrcID#1_MC#0_Chan#2_DIMM#0";
        // Insert 15 days in ascending collection order (DAY - 14 .. DAY):
        //   - DAY - 14 is quiet (0 markers);
        //   - kern.oops occurs on DAY - 13 .. DAY (13 of 14 baseline days before DAY = 92.9% >= 80%: chronic once backfilled);
        //   - io.error occurs on DAY - 10 .. DAY (10 of 14 baseline days before DAY = 71.4% < 80%: NOT chronic);
        //   - hw.edac.ce occurs only on DAY (0 of 14 baseline days: NOT chronic).
        for (int offset = 14; offset >= 0; offset--) {
            LocalDate d = DAY.minusDays(offset);
            String chunk = "c-day-" + d;
            StringBuilder text = new StringBuilder();
            text.append(lineOn(d, 0, "sshd[100]: session closed for user admin"));
            if (offset <= 13) {
                text.append(lineOn(d, 5, "kernel: BUG: unable to handle kernel paging request at 0x0"));
                text.append(lineOn(d, 6, "kernel: BUG: unable to handle kernel paging request at 0x0"));
            }
            if (offset <= 10) {
                text.append(lineOn(d, 25, "kernel: blk_update_request: I/O error, dev sda, sector 42"));
                text.append(lineOn(d, 26, "kernel: blk_update_request: I/O error, dev sda, sector 43"));
            }
            if (offset == 0) {
                for (int i = 0; i < 12; i++) {
                    text.append(lineOn(d, 40 + i, "kernel: EDAC MC0: 1 CE memory read error on " + slot
                            + " (channel:2 slot:0 page:0x1000 offset:0x0 grain:32)"));
                }
            }
            fixture.chunk(chunk, SystemLogReadFixture.bytes(text.toString()));
            repo.pending.add(new PendingChunk(chunk, "FW-TANGO-04", "cp_messages", "job-" + d, d));
        }

        LogDetectionService service = service(repo, fixture);
        // Pass 1 (11 chunks): indexes DAY down to DAY - 10 newest-first; DAY - 14 .. DAY - 11 stay pending.
        PassSummary first = service.runOnce(11, Duration.ofMinutes(1));
        assertEquals(11, first.chunksIndexed());
        assertTrue(repo.states.containsKey("c-day-" + DAY), "newest day is indexed first");
        assertTrue(repo.states.containsKey("c-day-" + DAY.minusDays(10)));
        assertFalse(repo.states.containsKey("c-day-" + DAY.minusDays(11)), "older days wait for backfill");

        // While DAY - 14 .. DAY - 11 are still unprocessed, DAY is evaluated for Layer A markers immediately, with
        // NOT_EVALUABLE (HISTORY_UNDER_7_DAYS) coverage, and io.error is NOT falsely suppressed as CHRONIC from the
        // newest-only 10/10 slice; DAY - 10 never counts future days DAY - 9 .. DAY as its history.
        var covDayAfterFirst = repo.coverage.get("FW-TANGO-04|cp_messages|job-" + DAY);
        assertEquals("NOT_EVALUABLE", covDayAfterFirst.state());
        assertEquals("HISTORY_UNDER_7_DAYS", covDayAfterFirst.reason());
        assertEquals(1, covDayAfterFirst.historyDays());
        var covMinus10AfterFirst = repo.coverage.get("FW-TANGO-04|cp_messages|job-" + DAY.minusDays(10));
        assertEquals("NOT_EVALUABLE", covMinus10AfterFirst.state());
        assertEquals(1, covMinus10AfterFirst.historyDays(), "an older day never counts already-indexed newer days");

        FindingRow ceFirst = findingOn(repo, DAY, "hw.edac.ce");
        FindingRow ioFirst = findingOn(repo, DAY, "io.error");
        FindingRow oopsFirst = findingOn(repo, DAY, "kern.oops");
        assertEquals("ATTENTION", ceFirst.severity());
        assertEquals("ATTENTION", ioFirst.severity(), "io.error must not guess CHRONIC from a partial newest-only slice");
        assertFalse(ioFirst.chronic());
        assertEquals("ATTENTION", oopsFirst.severity());
        assertTrue(ceFirst.labels().contains("HISTORY_UNDER_7_DAYS"));
        assertEquals("NOT_EVALUABLE", ceFirst.coverageState());

        // Pass 2: backfills DAY - 11 .. DAY - 14 (DAY - 14 has 0 marker lines); refreshes coverage and re-evaluates DAY.
        PassSummary second = service.runOnce(10, Duration.ofMinutes(1));
        assertEquals(4, second.chunksIndexed());

        var covDayAfterSecond = repo.coverage.get("FW-TANGO-04|cp_messages|job-" + DAY);
        assertEquals("EVALUATED", covDayAfterSecond.state());
        assertEquals("LAYER_A_ONLY", covDayAfterSecond.reason());
        assertEquals(15, covDayAfterSecond.historyDays());
        var covOldest = repo.coverage.get("FW-TANGO-04|cp_messages|job-" + DAY.minusDays(14));
        assertEquals("NOT_EVALUABLE", covOldest.state());
        assertEquals(1, covOldest.historyDays(), "the oldest day has 1 day of history even when indexed last");

        FindingRow ceFinal = findingOn(repo, DAY, "hw.edac.ce");
        FindingRow ioFinal = findingOn(repo, DAY, "io.error");
        FindingRow oopsFinal = findingOn(repo, DAY, "kern.oops");
        assertEquals("ATTENTION", ceFinal.severity());
        assertFalse(ceFinal.labels().contains("HISTORY_UNDER_7_DAYS"));
        assertEquals("EVALUATED", ceFinal.coverageState());
        assertEquals(14, ceFinal.baselineDays());
        assertEquals(0, ceFinal.presenceDays());

        assertEquals("ATTENTION", ioFinal.severity(), "10 of 14 baseline days (71.4%) is below 80%: stays ATTENTION");
        assertFalse(ioFinal.chronic());
        assertEquals(14, ioFinal.baselineDays());
        assertEquals(10, ioFinal.presenceDays());

        assertEquals("INFO", oopsFinal.severity(), "13 of 14 baseline days (92.9%) at baseline rate: becomes CHRONIC INFO");
        assertTrue(oopsFinal.chronic());
        assertEquals(List.of("CHRONIC"), oopsFinal.labels());
        assertEquals("EVALUATED", oopsFinal.coverageState());
    }

    private static FindingRow findingOn(StrictRepository repo, LocalDate day, String marker) {
        return repo.findings.values().stream()
                .filter(f -> f.windowDay().equals(day) && f.markerId().equals(marker))
                .findFirst().orElseThrow(() -> new AssertionError("missing " + marker + " on " + day));
    }
}
