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
        LocalDateTime t = DAY.atStartOfDay().plusHours(2).plusMinutes(minute);
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
}
