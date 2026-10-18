package com.securityexpert.nexus.ui2.worker.packetanalysis;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.TsharkRunner;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.CaptureRow;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.CaseRow;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.RunRow;
import com.securityexpert.nexus.ui2.platform.PacketAnalysisPaths;

/**
 * The first production run (2026-10-18) through the path the worker takes for an uploaded case: captures registered
 * the way the upload API registers them (generated capture id, generated storage key, file in the case's capture
 * directory), {@link PacketAnalysisJobExecutor} with the real engine, and a repository that refuses exactly what the
 * {@code packet_analysis_run} CHECK constraints of the migrations refuse. The run completed in the engine and then
 * failed as {@code ENGINE_FAILED} because the engine version it stored broke {@code
 * packet_analysis_run_engine_version_check}; PostgreSQL's refusal reached the log as a bare class name.
 */
class PacketAnalysisServicePathTest {
    private static final String CLIENT = "198.51.100.10";
    private static final String SERVER = "203.0.113.20";
    private static final String CASE = UUID.randomUUID().toString();
    private static final String JOB = UUID.randomUUID().toString();
    private static final Instant NOW = Instant.parse("2026-10-18T07:15:00Z");

    @TempDir Path root;
    private final JobLeaseRepository leases = mock(JobLeaseRepository.class);
    private final JobStepAttemptRepository attempts = mock(JobStepAttemptRepository.class);
    private final PacketAnalysisRepository repository = mock(PacketAnalysisRepository.class);
    private final List<LogRecord> logged = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord record) { logged.add(record); }
        @Override public void flush() { }
        @Override public void close() { }
    };
    private static String tshark;

    @BeforeEach
    void setUp() throws Exception {
        try {
            tshark = TsharkRunner.find(null); // the engine searches the same way (PATH, then the platform default)
        } catch (IllegalArgumentException missing) {
            tshark = null;
        }
        Logger.getLogger(PacketAnalysisJobExecutor.class.getName()).addHandler(capture);
        when(repository.runForJob(JOB)).thenReturn(Optional.of(new RunRow(JOB, CASE, "REQUESTED", "synthetic-operator", NOW,
                null, null, null, null, null)));
        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString(), anyString())).thenReturn(true);
        when(attempts.insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt())).thenReturn("attempt-1");
        when(attempts.writeOutcome(anyString(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), any())).thenReturn(true);
        when(repository.markRunning(anyString(), anyLong(), anyString())).thenReturn(true);
        RunTable table = RunTable.fromMigrations();
        when(repository.complete(anyString(), anyLong(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            table.checkComplete(call.getArgument(2), call.getArgument(3));
            return true;
        });
        when(repository.fail(anyString(), anyLong(), anyString(), anyString())).thenAnswer(call -> {
            table.checkFailureReason(call.getArgument(2));
            return true;
        });
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(PacketAnalysisJobExecutor.class.getName()).removeHandler(capture);
    }

    @Test void aMultiCaptureCaseUploadedThroughTheServiceCompletesAndStoresItsRun() throws Exception {
        assumeTrue(tshark != null, "tshark not installed");
        List<CaptureRow> captures = upload(chain());
        when(repository.captures(CASE)).thenReturn(captures);
        when(repository.caseForWorker(CASE)).thenReturn(Optional.of(new CaseRow(CASE, "Synthetic case", "synthetic-operator", NOW,
                NOW.plus(Duration.ofDays(7)), "QUEUED", captures.stream().mapToLong(CaptureRow::sizeBytes).sum(), true,
                captures.size(), JOB)));

        JobState state = new PacketAnalysisJobExecutor(leases, attempts, repository, new RealPacketAnalysisEngine(), root)
                .execute(JOB, 7, CASE);

        assertEquals(JobState.COMPLETED, state, "the run completes; logged: " + messages());
        var version = org.mockito.ArgumentCaptor.forClass(String.class);
        var summary = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository).complete(eq(JOB), eq(7L), version.capture(), summary.capture(), eq("system:worker"));
        verify(repository, never()).fail(anyString(), anyLong(), anyString(), anyString());
        assertEquals(new RealPacketAnalysisEngine().version(), version.getValue());
        Map<?, ?> masked = new ObjectMapper().readValue(summary.getValue(), Map.class);
        assertEquals(3, masked.get("capture_count"));
        for (CaptureRow row : captures) assertFalse(summary.getValue().contains(row.displayName()));
        assertTrue(Files.isRegularFile(PacketAnalysisPaths.reportFile(root, CASE, JOB)));
        verify(leases).transitionState(JOB, 7, JobState.EXECUTING, JobState.COMPLETED, "system:worker",
                "packet_analysis_completed", "COMPLETED");
    }

    @Test void aRunTheDatabaseRefusesFailsWithTheConstraintNamedAndNoValueLogged() throws Exception {
        // The engine's result is refused by PostgreSQL when it is saved, as the first production run was.
        PacketAnalysisEngine refused = new PacketAnalysisEngine() {
            public String version() { return "not a token (0.1)"; }
            public Result analyze(Request request) {
                return new Result("not a token (0.1)", "{\"verdict\":\"x\"}", Map.of("verdict", "Inconclusive"));
            }
        };
        List<CaptureRow> captures = upload(List.of(new Capture("synthetic-customer-core-fw.pcap", new byte[] {1, 2, 3})));
        when(repository.captures(CASE)).thenReturn(captures);
        when(repository.caseForWorker(CASE)).thenReturn(Optional.of(new CaseRow(CASE, "t", "synthetic-operator", NOW,
                NOW.plus(Duration.ofDays(7)), "QUEUED", 3, true, 1, JOB)));

        assertEquals(JobState.FAILED, new PacketAnalysisJobExecutor(leases, attempts, repository, refused, root).execute(JOB, 2, CASE));

        verify(repository).fail(JOB, 2, "ENGINE_FAILED", "system:worker");
        verify(attempts).writeOutcome(eq("attempt-1"), eq(2L), eq("EXPECTATION_UNMET"), eq("ENGINE_FAILED"), eq(false),
                isNull(), isNull(), isNull());
        String terminal = "ENGINE_FAILED: postgres check constraint packet_analysis_run_engine_version_check (sqlstate 23514)";
        verify(leases).transitionState(JOB, 2, JobState.EXECUTING, JobState.FAILED, "system:worker", "packet_analysis_failed",
                terminal);
        String log = messages();
        assertTrue(log.contains("org.jooq.exception.IntegrityConstraintViolationException <- java.sql.SQLException"), log);
        assertTrue(log.contains("sqlstate 23514"), log);
        assertTrue(log.contains("constraint check packet_analysis_run_engine_version_check"), log);
        assertTrue(log.contains("failed in persist"), log);
        assertFalse(log.contains("not a token"), "the refused value is never logged");
        assertFalse(log.contains("Failing row"), "the database's row detail is never logged");
        assertFalse(log.contains("synthetic-customer"), log);
    }

    @Test void aDuckDbConstraintInTheEngineIsNamedByKindWithoutTheDuplicateKey() throws Exception {
        PacketAnalysisEngine duplicate = new PacketAnalysisEngine() {
            public String version() { return "dup"; }
            public Result analyze(Request request) throws Exception {
                try (var c = java.sql.DriverManager.getConnection("jdbc:duckdb:"); var s = c.createStatement()) {
                    s.execute("create table capture_map(id varchar primary key)");
                    s.execute("insert into capture_map values ('192.0.2.9')");
                    s.execute("insert into capture_map values ('192.0.2.9')");
                } catch (SQLException e) {
                    throw new IllegalStateException("Engine DuckDB failure: " + e.getMessage(), e);
                }
                throw new AssertionError("the duplicate key must be refused");
            }
        };
        List<CaptureRow> captures = upload(List.of(new Capture("synthetic-customer-core-fw.pcap", new byte[] {1, 2, 3})));
        when(repository.captures(CASE)).thenReturn(captures);
        when(repository.caseForWorker(CASE)).thenReturn(Optional.of(new CaseRow(CASE, "t", "synthetic-operator", NOW,
                NOW.plus(Duration.ofDays(7)), "QUEUED", 3, true, 1, JOB)));

        assertEquals(JobState.FAILED, new PacketAnalysisJobExecutor(leases, attempts, repository, duplicate, root).execute(JOB, 1, CASE));

        verify(repository).fail(JOB, 1, "ENGINE_FAILED", "system:worker");
        verify(leases).transitionState(JOB, 1, JobState.EXECUTING, JobState.FAILED, "system:worker", "packet_analysis_failed",
                "ENGINE_FAILED: duckdb primary key constraint");
        String log = messages();
        assertTrue(log.contains("java.lang.IllegalStateException <- java.sql.SQLException"), log);
        assertTrue(log.contains("failed in analyze"), log);
        assertFalse(log.contains("192.0.2.9"), "the duplicate key is never logged");
    }

    // ---- the run table's CHECK constraints, read from the migrations ----------------------------------------

    /** The latest {@code engine_version} and {@code failure_reason} patterns of {@code packet_analysis_run}. */
    record RunTable(Pattern engineVersion, Pattern failureReason) {
        static RunTable fromMigrations() throws IOException {
            Path migrations = ui2Root().resolve("service/src/main/resources/db/migration");
            String engine = null;
            String reason = null;
            List<Path> files;
            try (var list = Files.list(migrations)) {
                files = list.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                        .sorted(Comparator.comparingInt(p -> Integer.parseInt(p.getFileName().toString().replaceAll("^V(\\d+)__.*", "$1"))))
                        .toList();
            }
            for (Path file : files) {
                String sql = Files.readString(file);
                if (!sql.contains("packet_analysis_run")) continue;
                Matcher e = Pattern.compile("engine_version ~ '([^']+)'").matcher(sql);
                while (e.find()) engine = e.group(1);
                Matcher r = Pattern.compile("failure_reason ~ '([^']+)'").matcher(sql);
                while (r.find()) reason = r.group(1);
            }
            assertNotNull(engine, "packet_analysis_run.engine_version CHECK not found");
            assertNotNull(reason, "packet_analysis_run.failure_reason CHECK not found");
            return new RunTable(Pattern.compile(engine), Pattern.compile(reason));
        }

        void checkComplete(String version, String maskedSummary) {
            if (version != null && !engineVersion.matcher(version).find()) throw refused("engine_version", version + ", " + maskedSummary);
        }

        void checkFailureReason(String reason) {
            if (reason != null && !failureReason.matcher(reason).find()) throw refused("failure_reason", reason);
        }

        /** What jOOQ raises for PostgreSQL's check_violation, with the row detail PostgreSQL attaches. */
        private static RuntimeException refused(String column, String row) {
            String server = "ERROR: new row for relation \"packet_analysis_run\" violates check constraint \"packet_analysis_run_"
                    + column + "_check\"\n  Detail: Failing row contains (" + row + ").";
            return new org.jooq.exception.IntegrityConstraintViolationException("SQL [update packet_analysis_run set ...]; "
                    + server, new SQLException(server, "23514"));
        }
    }

    private static Path ui2Root() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("service/src/main/resources/db/migration"))) dir = dir.getParent();
        assertNotNull(dir, "ui2 root not found");
        return dir;
    }

    // ---- upload: as PacketAnalysisController registers a capture -------------------------------------------

    record Capture(String displayName, byte[] bytes) { }

    private List<CaptureRow> upload(List<Capture> files) throws Exception {
        List<CaptureRow> rows = new ArrayList<>();
        for (Capture file : files) {
            String captureId = UUID.randomUUID().toString();
            String storageKey = UUID.randomUUID() + ".pcap";
            Path target = PacketAnalysisPaths.captureFile(root, CASE, storageKey);
            Files.createDirectories(target.getParent());
            Files.write(target, file.bytes());
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.bytes()));
            rows.add(new CaptureRow(captureId, CASE, file.displayName(), "pcap", file.bytes().length, sha, storageKey,
                    "synthetic-operator", NOW));
        }
        return rows;
    }

    private String messages() {
        StringBuilder out = new StringBuilder();
        for (LogRecord record : logged) {
            String message = record.getMessage();
            Object[] params = record.getParameters();
            out.append(params == null ? message : java.text.MessageFormat.format(message, params)).append('\n');
        }
        return out.toString();
    }

    // ---- synthetic captures: one TCP connection seen at client, firewall and server -------------------------

    private record Frame(double ts, byte[] bytes) { }

    private static List<Capture> chain() throws IOException {
        List<Frame> client = new ArrayList<>();
        List<Frame> fw = new ArrayList<>();
        List<Frame> server = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            double t = 1700000000.0 + i * 0.5;
            boolean syn = i == 0;
            int seqF = syn ? 1000 : 1001 + (i - 1) * 100;
            int ackF = syn ? 0 : 5001 + (i - 1) * 100;
            byte[] req = syn ? new byte[0] : fill(String.format("GET /item/%02d ", i), 'q', 86);
            int seqR = syn ? 5000 : 5001 + (i - 1) * 100;
            int ackR = syn ? 1001 : 1001 + i * 100;
            byte[] rsp = syn ? new byte[0] : fill(String.format("200 item %02d ", i), 'r', 88);
            client.add(new Frame(t, tcp(CLIENT, SERVER, 41000, 443, seqF, ackF, syn ? 2 : 24, req, 100 + i, 64)));
            fw.add(new Frame(t + 0.00003, tcp(CLIENT, SERVER, 41000, 443, seqF, ackF, syn ? 2 : 24, req, 100 + i, 63)));
            server.add(new Frame(t + 0.00006, tcp(CLIENT, SERVER, 41000, 443, seqF, ackF, syn ? 2 : 24, req, 100 + i, 62)));
            double tr = t + 0.002;
            int flagsR = syn ? 18 : 24;
            server.add(new Frame(tr, tcp(SERVER, CLIENT, 443, 41000, seqR, ackR, flagsR, rsp, 300 + i, 64)));
            fw.add(new Frame(tr + 0.00003, tcp(SERVER, CLIENT, 443, 41000, seqR, ackR, flagsR, rsp, 300 + i, 63)));
            client.add(new Frame(tr + 0.00006, tcp(SERVER, CLIENT, 443, 41000, seqR, ackR, flagsR, rsp, 300 + i, 62)));
        }
        return List.of(new Capture("synthetic-client.pcap", pcap(client)), new Capture("synthetic-fw.pcap", pcap(fw)),
                new Capture("synthetic-server.pcap", pcap(server)));
    }

    private static byte[] fill(String prefix, char c, int n) {
        byte[] head = prefix.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[head.length + n];
        System.arraycopy(head, 0, out, 0, head.length);
        java.util.Arrays.fill(out, head.length, out.length, (byte) c);
        return out;
    }

    private static byte[] tcp(String src, String dst, int sport, int dport, long seq, long ack, int flags, byte[] payload,
            int ipid, int ttl) throws IOException {
        byte[] a = InetAddress.getByName(src).getAddress();
        byte[] b = InetAddress.getByName(dst).getAddress();
        ByteBuffer tcp = ByteBuffer.allocate(20 + payload.length).order(ByteOrder.BIG_ENDIAN);
        tcp.putShort((short) sport).putShort((short) dport).putInt((int) seq).putInt((int) ack).put((byte) (5 << 4))
                .put((byte) flags).putShort((short) 65535).putShort((short) 0).putShort((short) 0).put(payload);
        byte[] seg = tcp.array();
        ByteBuffer pseudo = ByteBuffer.allocate(12 + seg.length).order(ByteOrder.BIG_ENDIAN);
        pseudo.put(a).put(b).put((byte) 0).put((byte) 6).putShort((short) seg.length).put(seg);
        int sum = checksum(pseudo.array());
        seg[16] = (byte) (sum >>> 8);
        seg[17] = (byte) sum;
        ByteBuffer ip = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        ip.put((byte) 0x45).put((byte) 0).putShort((short) (20 + seg.length)).putShort((short) (ipid % 65536))
                .putShort((short) 0x4000).put((byte) ttl).put((byte) 6).putShort((short) 0).put(a).put(b);
        byte[] header = ip.array();
        int ipSum = checksum(header);
        header[10] = (byte) (ipSum >>> 8);
        header[11] = (byte) ipSum;
        ByteBuffer frame = ByteBuffer.allocate(14 + header.length + seg.length);
        frame.put(new byte[] {0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88, (byte) 0x99, (byte) 0xAA,
                (byte) 0xBB, 0x08, 0x00});
        frame.put(header).put(seg);
        return frame.array();
    }

    private static int checksum(byte[] data) {
        long sum = 0;
        for (int i = 0; i < data.length; i += 2) {
            int hi = data[i] & 0xFF;
            int lo = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            sum += (hi << 8) | lo;
        }
        while ((sum >>> 16) != 0) sum = (sum & 0xFFFF) + (sum >>> 16);
        return (int) (~sum & 0xFFFF);
    }

    /** Classic pcap, microsecond timestamps, frames in time order. */
    private static byte[] pcap(List<Frame> frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0xA1B2C3D4).putShort((short) 2).putShort((short) 4).putInt(0).putInt(0).putInt(65535).putInt(1);
        out.writeBytes(h.array());
        List<Frame> sorted = new ArrayList<>(frames);
        sorted.sort(Comparator.comparingDouble(Frame::ts));
        for (Frame f : sorted) {
            long sec = (long) f.ts();
            long usec = Math.round((f.ts() - sec) * 1e6);
            if (usec == 1_000_000L) {
                sec += 1;
                usec = 0;
            }
            ByteBuffer r = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            r.putInt((int) sec).putInt((int) usec).putInt(f.bytes().length).putInt(f.bytes().length);
            out.writeBytes(r.array());
            out.writeBytes(f.bytes());
        }
        return out.toByteArray();
    }
}
