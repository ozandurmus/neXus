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

    @Test void aRunThatReachesTheDuckDbMemoryLimitFailsCleanlyWithItsReason() {
        // A real DuckDB out-of-memory error from a 64 MiB limit, escaping the engine as an unchecked exception.
        PacketAnalysisEngine tiny = new PacketAnalysisEngine() {
            public String version() { return "tiny"; }
            public Result analyze(Request request) {
                var store = com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore.open(request.caseDir(),
                        new com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore.Settings(64, 8L << 30, 1));
                try (var db = store.connect()) {
                    db.query("SELECT length(list(md5(i::VARCHAR))) FROM range(20000000) t(i)");
                }
                throw new AssertionError("the 64 MiB limit must stop this query");
            }
        };
        assertEquals(JobState.FAILED, executor(tiny).execute(JOB, 1, CASE));
        verify(repository).fail(JOB, 1, "ANALYSIS_MEMORY_LIMIT", "system:worker");
        verify(attempts).writeOutcome(eq("attempt-1"), eq(1L), eq("EXPECTATION_UNMET"), eq("ANALYSIS_MEMORY_LIMIT"),
                eq(false), isNull(), isNull(), isNull());
        verify(leases).transitionState(JOB, 1, JobState.EXECUTING, JobState.FAILED, "system:worker", "packet_analysis_failed",
                "ANALYSIS_MEMORY_LIMIT");
        verify(repository, never()).complete(anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test void anExhaustedJavaHeapEndsTheRunTheSameWayInsteadOfEscaping() {
        PacketAnalysisEngine exhausted = new PacketAnalysisEngine() {
            public String version() { return "heap"; }
            public Result analyze(Request request) { throw new OutOfMemoryError("Java heap space"); }
        };
        assertEquals(JobState.FAILED, executor(exhausted).execute(JOB, 1, CASE));
        verify(repository).fail(JOB, 1, "ANALYSIS_MEMORY_LIMIT", "system:worker");
        verify(leases).transitionState(JOB, 1, JobState.EXECUTING, JobState.FAILED, "system:worker", "packet_analysis_failed",
                "ANALYSIS_MEMORY_LIMIT");
    }

    @Test void aRangeWithNoWorkableWindowFailsWithTheReasonThatAsksForOne() {
        PacketAnalysisEngine unbounded = new PacketAnalysisEngine() {
            public String version() { return "window"; }
            public Result analyze(Request request) {
                throw new com.securityexpert.nexus.ui2.packetanalysis.store.AnalysisLimitException(
                        "ANALYSIS_WINDOW_REQUIRED", "x " + PRIVATE_FILE, null);
            }
        };
        assertEquals(JobState.FAILED, executor(unbounded).execute(JOB, 1, CASE));
        verify(repository).fail(JOB, 1, "ANALYSIS_WINDOW_REQUIRED", "system:worker");
    }

    @Test void theMaskedSummaryCarriesTheGuardsWindowAsNumbersAndFixedWordsOnly() {
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("mode", "guarded");
        window.put("reason", "budget");
        window.put("start", 1700000000.5);
        window.put("end", 1700000060.5);
        window.put("seconds", 60.0);
        window.put("range_seconds", 3600.0);
        window.put("range_max_packets_per_capture", 4000000L);
        window.put("window_max_packets_per_capture", 120000L);
        window.put("window_captures", 3L);
        window.put("budget", Map.of("max_packets_per_capture", 150000L, "max_seconds", 0.0));
        window.put("note", "analysed window: " + PRIVATE_FILE);
        Map<String, Object> masked = RealPacketAnalysisEngine.analysisWindow(window);
        assertEquals("guarded", masked.get("mode"));
        assertEquals("budget", masked.get("reason"));
        assertEquals(150000L, masked.get("max_packets_per_capture"));
        assertFalse(masked.containsKey("note"), "free text stays out of the masked summary");
        assertFalse(masked.toString().contains(PRIVATE_FILE));
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

    @Test void realEngineRunIngestsPairRunsStageGraphAndWritesReportAndDuckDb() throws Exception {
        String tshark;
        try {
            tshark = com.securityexpert.nexus.ui2.packetanalysis.ingest.TsharkRunner.find(System.getenv("TSHARK"));
        } catch (IllegalArgumentException unavailable) {
            tshark = null;
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(tshark != null, "tshark not installed");

        String capture2 = "77777777-8888-4999-8aaa-bbbbbbbbbbbb";
        byte[] clientIp = {(byte) 192, 0, 2, 10};
        byte[] serverIp = {(byte) 192, 0, 2, 20};
        byte[] reqBytes = "GET / HTTP/1.1\r\nHost: example.test\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] respBytes = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        byte[] clientPcap = buildPcap(
                List.of(
                        tcpIpFrame(clientIp, serverIp, 40000, 80, 1000, 0, 0x02, 64, 101, new byte[0]),
                        tcpIpFrame(serverIp, clientIp, 80, 40000, 2000, 1001, 0x12, 63, 201, new byte[0]),
                        tcpIpFrame(clientIp, serverIp, 40000, 80, 1001, 2001, 0x10, 64, 102, new byte[0]),
                        tcpIpFrame(clientIp, serverIp, 40000, 80, 1001, 2001, 0x18, 64, 103, reqBytes),
                        tcpIpFrame(serverIp, clientIp, 80, 40000, 2001, 1001 + reqBytes.length, 0x10, 63, 202, new byte[0]),
                        tcpIpFrame(serverIp, clientIp, 80, 40000, 2001, 1001 + reqBytes.length, 0x18, 63, 203, respBytes)
                ),
                new long[] {1_700_000_000L, 1_700_000_000L, 1_700_000_000L, 1_700_000_001L, 1_700_000_001L, 1_700_000_001L},
                new int[] {100_000, 120_000, 130_000, 100_000, 150_000, 200_000});

        byte[] serverPcap = buildPcap(
                List.of(
                        tcpIpFrame(clientIp, serverIp, 40000, 80, 1000, 0, 0x02, 63, 101, new byte[0]),
                        tcpIpFrame(serverIp, clientIp, 80, 40000, 2000, 1001, 0x12, 64, 201, new byte[0]),
                        tcpIpFrame(clientIp, serverIp, 40000, 80, 1001, 2001, 0x10, 63, 102, new byte[0]),
                        tcpIpFrame(clientIp, serverIp, 40000, 80, 1001, 2001, 0x18, 63, 103, reqBytes),
                        tcpIpFrame(serverIp, clientIp, 80, 40000, 2001, 1001 + reqBytes.length, 0x10, 64, 202, new byte[0]),
                        tcpIpFrame(serverIp, clientIp, 80, 40000, 2001, 1001 + reqBytes.length, 0x18, 64, 203, respBytes)
                ),
                new long[] {1_700_000_000L, 1_700_000_000L, 1_700_000_000L, 1_700_000_001L, 1_700_000_001L, 1_700_000_001L},
                new int[] {105_000, 115_000, 135_000, 105_000, 145_000, 195_000});

        Path cap1Path = root.resolve("cases").resolve(CASE).resolve("captures").resolve(CAPTURE + ".pcap");
        Path cap2Path = root.resolve("cases").resolve(CASE).resolve("captures").resolve(capture2 + ".pcap");
        Files.write(cap1Path, clientPcap);
        Files.write(cap2Path, serverPcap);

        when(repository.caseForWorker(CASE)).thenReturn(Optional.of(new CaseRow(CASE, "t", "synthetic-operator", now,
                now.plus(Duration.ofDays(7)), "QUEUED", clientPcap.length + serverPcap.length, false, 2, JOB)));
        when(repository.captures(CASE)).thenReturn(List.of(
                new CaptureRow(CAPTURE, CASE, "client-edge.pcap", "pcap", clientPcap.length, "a".repeat(64),
                        CAPTURE + ".pcap", "synthetic-operator", now),
                new CaptureRow(capture2, CASE, "server-edge.pcap", "pcap", serverPcap.length, "b".repeat(64),
                        capture2 + ".pcap", "synthetic-operator", now)));

        assertEquals(JobState.COMPLETED, executor(new RealPacketAnalysisEngine()).execute(JOB, 1, CASE));

        Path reportPath = root.resolve("cases").resolve(CASE).resolve("runs").resolve(JOB).resolve("report.json");
        Path exportPath = root.resolve("cases").resolve(CASE).resolve("runs").resolve(JOB).resolve("findings-v11.json");
        Path duckDbPath = root.resolve("cases").resolve(CASE).resolve("project.duckdb");
        assertTrue(Files.isRegularFile(reportPath));
        assertTrue(Files.isRegularFile(exportPath));
        assertTrue(Files.isRegularFile(duckDbPath));

        Map<?, ?> report = new ObjectMapper().readValue(reportPath.toFile(), Map.class);
        assertNotNull(report.get("verdict"));
        assertNotEquals("unknown", report.get("verdict"), "Real engine must produce a non-stub verdict");
        assertEquals("No supported network loss in the selected window", report.get("verdict"));

        Map<?, ?> findingsExport = new ObjectMapper().readValue(exportPath.toFile(), Map.class);
        assertEquals("packetbreaker.findings", findingsExport.get("schema_name"));
        assertEquals(11, ((Number) findingsExport.get("schema_version")).intValue());

        try (var conn = com.securityexpert.nexus.ui2.packetanalysis.engine.Engine.openReadOnly(duckDbPath);
             var db = new com.securityexpert.nexus.ui2.packetanalysis.engine.Db(conn)) {
            Map<String, Object> flows = com.securityexpert.nexus.ui2.packetanalysis.engine.CaseQueries.flowPage(
                    db, 0L, 50L, "", "", "bytes", null, null, null);
            assertEquals(1L, ((Number) flows.get("total")).longValue());
        }
    }

    private static byte[] tcpIpFrame(
            byte[] srcIp,
            byte[] dstIp,
            int sport,
            int dport,
            int seq,
            int ack,
            int flags,
            int ttl,
            int ipid,
            byte[] payload) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(14 + 20 + 20 + payload.length)
                .order(java.nio.ByteOrder.BIG_ENDIAN);
        b.put(new byte[] {2, 0, 0, 0, 0, 2, 2, 0, 0, 0, 0, 1}).putShort((short) 0x0800);
        b.put((byte) 0x45).put((byte) 0).putShort((short) (40 + payload.length)).putShort((short) ipid)
                .putShort((short) 0x4000).put((byte) ttl).put((byte) 6).putShort((short) 0);
        b.put(srcIp).put(dstIp);
        b.putShort((short) sport).putShort((short) dport).putInt(seq).putInt(ack).put((byte) 0x50)
                .put((byte) flags).putShort((short) 65535).putShort((short) 0).putShort((short) 0);
        b.put(payload);
        return b.array();
    }

    private static byte[] buildPcap(List<byte[]> frames, long[] seconds, int[] micros) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.putInt(0xa1b2c3d4).putShort((short) 2).putShort((short) 4).putInt(0).putInt(0).putInt(65535).putInt(1);
        out.writeBytes(h.array());
        for (int i = 0; i < frames.size(); i++) {
            java.nio.ByteBuffer r = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            r.putInt((int) seconds[i]).putInt(micros[i]).putInt(frames.get(i).length).putInt(frames.get(i).length);
            out.writeBytes(r.array());
            out.writeBytes(frames.get(i));
        }
        return out.toByteArray();
    }
}
