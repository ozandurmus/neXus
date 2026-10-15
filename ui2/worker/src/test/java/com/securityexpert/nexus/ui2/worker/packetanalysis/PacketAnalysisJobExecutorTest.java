package com.securityexpert.nexus.ui2.worker.packetanalysis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRow;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.CaptureRow;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.CaseRow;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.RunRow;
import com.securityexpert.nexus.ui2.platform.PacketAnalysisJob;

class PacketAnalysisJobExecutorTest {
    private static final String CASE = "11111111-2222-4333-8444-555555555555";
    private static final String CAPTURE = "66666666-7777-4888-9999-aaaaaaaaaaaa";
    private static final String JOB = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff";
    private static final String PRIVATE_FILE = "synthetic-customer-core-fw.pcap";

    @TempDir Path root;
    private final JobLeaseRepository leases = mock(JobLeaseRepository.class);
    private final JobStepAttemptRepository attempts = mock(JobStepAttemptRepository.class);
    private final PacketAnalysisRepository repository = mock(PacketAnalysisRepository.class);
    private final Instant now = Instant.parse("2026-10-15T10:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        when(repository.runForJob(JOB)).thenReturn(Optional.of(new RunRow(JOB, CASE, "REQUESTED", "synthetic-operator", now,
                null, null, null, null, null)));
        when(repository.caseForWorker(CASE)).thenReturn(Optional.of(new CaseRow(CASE, "t", "synthetic-operator", now,
                now.plus(Duration.ofDays(7)), "QUEUED", 124, true, 1, JOB)));
        when(repository.captures(CASE)).thenReturn(List.of(new CaptureRow(CAPTURE, CASE, PRIVATE_FILE, "pcap", 124, "a".repeat(64),
                CAPTURE + ".pcap", "synthetic-operator", now)));
        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString(), anyString())).thenReturn(true);
        when(attempts.insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt())).thenReturn("attempt-1");
        when(attempts.writeOutcome(anyString(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), any())).thenReturn(true);
        when(repository.markRunning(anyString(), anyLong(), anyString())).thenReturn(true);
        when(repository.complete(anyString(), anyLong(), anyString(), anyString(), anyString())).thenReturn(true);
        when(repository.fail(anyString(), anyLong(), anyString(), anyString())).thenReturn(true);
        Path capture = root.resolve("cases").resolve(CASE).resolve("captures").resolve(CAPTURE + ".pcap");
        Files.createDirectories(capture.getParent());
        Files.write(capture, new byte[124]);
    }

    private PacketAnalysisJobExecutor executor(PacketAnalysisEngine engine) {
        return new PacketAnalysisJobExecutor(leases, attempts, repository, engine, root);
    }

    @Test void stubRunWritesTheReportToTheVolumeAndOnlyTheMaskedSummaryToTheDatabase() throws Exception {
        assertEquals(JobState.COMPLETED, executor(new StubPacketAnalysisEngine()).execute(JOB, 3, CASE));
        var order = inOrder(leases, attempts, repository);
        order.verify(leases).transitionState(JOB, 3, JobState.CLAIMED, JobState.EXECUTING, "system:worker", "packet_analysis_running");
        order.verify(attempts).insertPreContact(JOB, 3, 0, "PACKET_ANALYSIS", "read", 1);
        order.verify(repository).markRunning(JOB, 3, "system:worker");
        var summary = org.mockito.ArgumentCaptor.forClass(String.class);
        order.verify(repository).complete(eq(JOB), eq(3L), eq("stub-0"), summary.capture(), eq("system:worker"));
        order.verify(leases).transitionState(JOB, 3, JobState.EXECUTING, JobState.COMPLETED, "system:worker",
                "packet_analysis_completed", "COMPLETED");
        verify(attempts, never()).markBoundaryCrossed(anyString(), anyLong());

        Map<?, ?> masked = new ObjectMapper().readValue(summary.getValue(), Map.class);
        assertEquals("unknown", masked.get("verdict"));
        assertEquals(StubPacketAnalysisEngine.STAGES.size(), ((List<?>) masked.get("stages")).size());
        for (Object stage : (List<?>) masked.get("stages")) {
            assertEquals("unknown", ((Map<?, ?>) stage).get("verdict"));
            assertEquals("engine not yet ported", ((Map<?, ?>) stage).get("reason"));
        }
        assertFalse(summary.getValue().contains(PRIVATE_FILE), "the masked summary never carries a file name");
        assertFalse(summary.getValue().contains("a".repeat(64)));

        Path report = root.resolve("cases").resolve(CASE).resolve("runs").resolve(JOB).resolve("report.json");
        Map<?, ?> full = new ObjectMapper().readValue(report.toFile(), Map.class);
        assertEquals("unknown", full.get("verdict"));
        assertEquals(Map.of("payload_zeroing", true), full.get("options"));
        assertTrue(Files.readString(report).contains(PRIVATE_FILE), "the full report names the capture for run roles");
    }

    @Test void aMissingCaptureFileFailsTheRunWithAClosedReason() throws Exception {
        Files.delete(root.resolve("cases").resolve(CASE).resolve("captures").resolve(CAPTURE + ".pcap"));
        assertEquals(JobState.FAILED, executor(new StubPacketAnalysisEngine()).execute(JOB, 1, CASE));
        verify(repository).fail(JOB, 1, "CAPTURE_MISSING", "system:worker");
        verify(repository, never()).complete(anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test void anEngineFailureNeverEchoesItsMessage() {
        PacketAnalysisEngine broken = new PacketAnalysisEngine() {
            public String version() { return "broken"; }
            public Result analyze(Request request) { throw new IllegalStateException("192.0.2.9 " + PRIVATE_FILE); }
        };
        assertEquals(JobState.FAILED, executor(broken).execute(JOB, 1, CASE));
        verify(repository).fail(JOB, 1, "ENGINE_FAILED", "system:worker");
        verify(leases).transitionState(JOB, 1, JobState.EXECUTING, JobState.FAILED, "system:worker", "packet_analysis_failed", "ENGINE_FAILED");
    }

    @Test void aGoneCaseIsRejectedBeforeAnyWork() {
        when(repository.caseForWorker(CASE)).thenReturn(Optional.empty());
        assertEquals(JobState.REJECTED, executor(new StubPacketAnalysisEngine()).execute(JOB, 1, CASE));
        verify(attempts, never()).insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt());
        assertEquals(JobState.REJECTED, executor(new StubPacketAnalysisEngine()).execute(JOB, 1, "../not-an-id"));
    }

    @Test void aLostLeaseStopsWithoutATerminalWrite() {
        when(repository.complete(anyString(), anyLong(), anyString(), anyString(), anyString())).thenReturn(false);
        assertNull(executor(new StubPacketAnalysisEngine()).execute(JOB, 1, CASE));
        verify(leases, never()).transitionState(anyString(), anyLong(), eq(JobState.EXECUTING), eq(JobState.COMPLETED),
                anyString(), anyString(), anyString());
    }

    @Test void theClaimLoopClaimsPacketAnalysisRunOnlyAndOneJobAtATime() {
        assertEquals(List.of("packet_analysis_run"), PacketAnalysisClaimLoop.CLAIMS);
        var jobs = mock(JobRecordDao.class);
        var executor = mock(PacketAnalysisJobExecutor.class);
        when(leases.claimNext(anyString(), anyList(), any())).thenReturn(Optional.of(new ClaimedJob(JOB, 2)), Optional.empty());
        when(jobs.find(JOB)).thenReturn(Optional.of(new JobRow(JOB, PacketAnalysisJob.CAPABILITY, null, "read", "CLAIMED",
                "packet_analysis-x", 2, null, null, PacketAnalysisJob.TARGET_KIND, CASE)));
        var loop = new PacketAnalysisClaimLoop(leases, jobs, executor, "packet_analysis-synthetic", Duration.ofMinutes(10));
        assertTrue(loop.claimAndExecuteOnce());
        assertFalse(loop.claimAndExecuteOnce());
        verify(leases, times(2)).claimNext("packet_analysis-synthetic", List.of("packet_analysis_run"), Duration.ofMinutes(10));
        verify(executor).execute(JOB, 2, CASE);
        assertThrows(IllegalArgumentException.class,
                () -> new PacketAnalysisClaimLoop(leases, jobs, executor, "general-synthetic", Duration.ofMinutes(10)));
    }

    @Test void aForeignJobReachingTheLoopIsRejectedNotRun() {
        var jobs = mock(JobRecordDao.class);
        var executor = mock(PacketAnalysisJobExecutor.class);
        when(leases.claimNext(anyString(), anyList(), any())).thenReturn(Optional.of(new ClaimedJob(JOB, 2)));
        when(jobs.find(JOB)).thenReturn(Optional.of(new JobRow(JOB, "cp_inventory_collect", "device-1", "read", "CLAIMED",
                "packet_analysis-x", 2, null, null, "device", null)));
        assertTrue(new PacketAnalysisClaimLoop(leases, jobs, executor, "packet_analysis-synthetic", Duration.ofMinutes(10))
                .claimAndExecuteOnce());
        verify(executor, never()).execute(anyString(), anyLong(), anyString());
        verify(leases).transitionState(JOB, 2, JobState.CLAIMED, JobState.REJECTED, "system:worker",
                "packet_analysis_rejected", "NOT_A_PACKET_ANALYSIS_JOB");
    }
}
