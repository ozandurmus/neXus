package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSyslogRangedRead;
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSystemLogCollect;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.FetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchStatus;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.diagnostic.CheckPointSystemLogRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;
import com.securityexpert.nexus.ui2.worker.systemlog.PlannerConfig;
import com.securityexpert.nexus.ui2.worker.systemlog.redaction.LogRedactor;

/**
 * The {@code cp_system_log_collect} executor against a scripted transport: no socket, host or device contact. Every
 * device value is synthetic (FW-TANGO-04, 192.0.2.x, synth paths). The artefact store is a real encrypting
 * {@link FileArtefactStore}, so stored chunks are read back through it; the repository is an in-memory double of the
 * fenced transactions.
 */
class CpSystemLogCollectJobExecutorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant SIX_AM = Instant.parse("2026-10-17T03:10:00Z");    // 06:10 Europe/Istanbul
    private static final Instant NOON = Instant.parse("2026-10-17T09:10:00Z");      // 12:10 Europe/Istanbul
    private static final String MESSAGES = "/var/log/messages";
    private static final String FWD = "/opt/synth-cpsuite/fw1/log/fwd.elg";
    private static final String BANNER = "This system is for authorized use only. FW-TANGO-04";
    /** Values that identify hosts, paths or secrets; none may reach a job reason, run detail or gap detail. */
    private static final List<String> RAW = List.of("FW-TANGO-04", "192.0.2.", "synth-cpsuite", "/var/log", "synthetic-pass",
            "hcp_report_", "fwk.4242");
    /** Small caps so budgets, splits and rotations are exercised with short synthetic files. */
    private static final PlannerConfig SMALL = new PlannerConfig(256, 1024, 2048, 512, 16, "(\\.[0-9]{1,2})?", ".gz", 9, 1);

    static String lines(String prefix, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++)
            out.append("Oct 17 10:").append(String.format("%02d", i % 60)).append(":00 2026 FW-TANGO-04 kernel: ")
                    .append(prefix).append(" event ").append(i).append('\n');
        return out.toString();
    }

    // ------------------------------------------------------------------ the scripted device

    static final class RemoteFile {
        byte[] content; long inode; long mtime;
        RemoteFile(String content, long inode, long mtime) { this.content = content.getBytes(StandardCharsets.ISO_8859_1); this.inode = inode; this.mtime = mtime; }
    }

    final class Device implements DeviceTransport {
        final Map<String, RemoteFile> files = new LinkedHashMap<>();
        final List<String> commands = new ArrayList<>();
        final List<String> rangeReads = new ArrayList<>();
        final List<String> wholeFetches = new ArrayList<>();
        String crashListing = "/var/log/crash/:\ntotal 0\n\n/var/log/dump/usermode/:\ntotal 0\n";
        String saListing = "";
        String hcpListing = "";
        final Map<String, byte[]> wholeFiles = new HashMap<>();
        long epoch;
        boolean statFails;
        boolean connectFails;
        int connects;
        RangedFetchStatus failRangeWith;

        void put(String path, String content, long inode) { files.put(path, new RemoteFile(content, inode, epoch - 60)); }

        String stat() {
            StringBuilder out = new StringBuilder(BANNER).append('\n').append(epoch).append('\n');
            for (var e : files.entrySet())
                out.append(e.getKey()).append(' ').append(e.getValue().inode).append(' ').append(e.getValue().content.length)
                        .append(' ').append(e.getValue().mtime).append('\n');
            return out.toString();
        }

        @Override public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
            connects++;
            return connectFails ? new ConnectResult.AuthenticationFailed("synthetic") : new ConnectResult.Authenticated(() -> "s1");
        }

        @Override public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
            commands.add(spec.command());
            assertTrue(spec.outputLimitBytes() > 0, "every listing is bounded");
            if (spec.command().equals(CpSystemLogCollect.STAT_COMMAND))
                return statFails ? new ExecResult.TimedOut() : new ExecResult.Completed(stat(), 1);
            if (spec.command().equals(CpSystemLogCollect.LS_CRASH_COMMAND)) return new ExecResult.Completed(BANNER + "\n" + crashListing, 0);
            if (spec.command().equals(CpSystemLogCollect.LS_SA_COMMAND)) return new ExecResult.Completed(saListing, 0);
            if (spec.command().equals(CpSystemLogCollect.LS_HCP_COMMAND)) return new ExecResult.Completed(hcpListing, 0);
            throw new AssertionError("unexpected command");
        }

        @Override public RangedFetchResult fetchRange(TransportSession session, RangedFetchSpec spec, Duration timeout, OutputStream sink) {
            rangeReads.add(spec.remotePath() + "@" + spec.startOffset() + "+" + spec.maxBytes());
            if (failRangeWith != null) return new RangedFetchResult(failRangeWith, spec.startOffset(), spec.maxBytes(), 0, OptionalLong.empty(), false, "SYNTH");
            RemoteFile file = files.get(spec.remotePath());
            if (file == null) return new RangedFetchResult(RangedFetchStatus.FILE_MISSING, spec.startOffset(), spec.maxBytes(), 0, OptionalLong.empty(), false, "SFTP_STATUS_2");
            int start = (int) spec.startOffset();
            int end = (int) Math.min(file.content.length, spec.startOffset() + spec.maxBytes());
            try { sink.write(file.content, start, Math.max(0, end - start)); } catch (IOException e) { throw new AssertionError(e); }
            boolean eof = end == file.content.length && end - start < spec.maxBytes();
            return new RangedFetchResult(eof ? RangedFetchStatus.END_OF_FILE : RangedFetchStatus.CAP_REACHED, start, spec.maxBytes(),
                    Math.max(0, end - start), OptionalLong.of(file.content.length), eof, "");
        }

        @Override public RangedFetchResult fetchBounded(TransportSession session, RangedFetchSpec spec, Duration timeout,
                OutputStream sink, String nameClass) {
            wholeFetches.add(nameClass + ":" + spec.remotePath() + ":" + timeout.toSeconds());
            byte[] content = wholeFiles.get(spec.remotePath());
            if (content == null) return new RangedFetchResult(RangedFetchStatus.FILE_MISSING, 0, spec.maxBytes(), 0, OptionalLong.empty(), false, "SFTP_STATUS_2");
            int n = (int) Math.min(content.length, spec.maxBytes());
            try { sink.write(content, 0, n); } catch (IOException e) { throw new AssertionError(e); }
            boolean eof = n == content.length && n < spec.maxBytes();
            return new RangedFetchResult(eof ? RangedFetchStatus.END_OF_FILE : RangedFetchStatus.CAP_REACHED, 0, spec.maxBytes(), n,
                    OptionalLong.of(content.length), eof, "");
        }

        @Override public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) { throw new AssertionError("fetch"); }
        @Override public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) { throw new AssertionError("xml"); }
        @Override public void disconnect(TransportSession session) { }
    }

    // ------------------------------------------------------------------ the in-memory repository

    static final class Repo implements CheckPointSystemLogRepository {
        final Map<String, CpCursor> cursors = new HashMap<>();
        final List<CpChunk> chunks = new ArrayList<>();
        final List<String> chunkSourceKeys = new ArrayList<>();
        final Map<String, List<Gap>> gaps = new HashMap<>();
        final Map<String, Run> runs = new LinkedHashMap<>();
        final List<String> history = new ArrayList<>();
        final Map<String, CrashEntry> crash = new LinkedHashMap<>();
        final List<CrashAlert> alerts = new ArrayList<>();
        final List<FileRow> files = new ArrayList<>();
        Settings settings = Settings.DEFAULTS;
        boolean fence = true;

        @Override public Optional<PanCursor> findPanCursor(String d, String s) { return Optional.empty(); }
        @Override public Settings settings() { return settings; }
        @Override public boolean record(String j, long e, String d, String s, SourceWrite w) { throw new AssertionError("pan write"); }
        @Override public Optional<CpCursor> findCpCursor(String d, String s) { return Optional.ofNullable(cursors.get(s)); }

        @Override public ChunkWrite recordChunk(String j, long e, String d, String s, CpChunk chunk, CpCursor cursor, List<Gap> g) {
            if (!fence) return ChunkWrite.NOT_RECORDED;
            for (CpChunk c : chunks)
                if (c.inode().equals(chunk.inode()) && c.chunk().startOffset() == chunk.chunk().startOffset()
                        && c.chunk().plaintextHmac().equals(chunk.chunk().plaintextHmac())) {
                    cursors.put(s, cursor); return ChunkWrite.DUPLICATE;
                }
            chunks.add(chunk);
            chunkSourceKeys.add(s);
            cursors.put(s, cursor);
            gaps.computeIfAbsent(s, k -> new ArrayList<>()).addAll(g);
            return ChunkWrite.STORED;
        }

        @Override public boolean recordCpSource(String j, long e, String d, String s, Run run, Optional<CpCursor> cursor, List<Gap> g) {
            if (!fence) return false;
            runs.put(s, run); history.add(s + "=" + run.outcome());
            cursor.ifPresent(c -> cursors.put(s, c));
            gaps.computeIfAbsent(s, k -> new ArrayList<>()).addAll(g);
            return true;
        }

        @Override public Optional<String> lastOutcome(String d, String s) {
            return Optional.ofNullable(runs.get(s)).map(Run::outcome);
        }

        @Override public boolean crashBaselineTaken(String d) {
            return history.stream().anyMatch(h -> h.startsWith("cp_crash_listing=") && !h.endsWith("UNKNOWN"));
        }

        @Override public List<CrashEntry> liveCrashEntries(String d) { return List.copyOf(crash.values()); }

        @Override public boolean recordCrashListing(String j, long e, String d, Run run, CrashListingWrite w) {
            runs.put("cp_crash_listing", run); history.add("cp_crash_listing=" + run.outcome());
            for (NewCrashEntry n : w.inserted())
                crash.put(n.entryId(), new CrashEntry(n.entryId(), n.directory(), n.nameHmac(), n.process(), n.size(), n.mtime()));
            for (CrashEntry s : w.seen()) crash.put(s.entryId(), s);
            for (String r : w.removedEntryIds()) crash.remove(r);
            alerts.addAll(w.alerts());
            return true;
        }

        @Override public boolean fileStored(String d, String kind, String hmac, long mtime) {
            return files.stream().anyMatch(f -> f.kind().equals(kind) && f.remoteNameHmac().equals(hmac) && f.mtime() == mtime);
        }

        @Override public boolean recordFile(String j, long e, String d, String s, Run run, Optional<FileRow> file, List<Gap> g) {
            runs.put(s, run); history.add(s + "=" + run.outcome());
            file.ifPresent(files::add);
            gaps.computeIfAbsent(s, k -> new ArrayList<>()).addAll(g);
            return true;
        }

        String outcome(String source) { return runs.get(source).outcome(); }

        @SuppressWarnings("unchecked")
        Map<String, Object> detail(String source) throws IOException { return JSON.readValue(runs.get(source).detailJson(), Map.class); }

        List<String> gapKinds(String source) { return gaps.getOrDefault(source, List.of()).stream().map(Gap::kind).toList(); }

        Gap gap(String source, String kind) {
            return gaps.getOrDefault(source, List.of()).stream().filter(g -> g.kind().equals(kind)).findFirst().orElseThrow();
        }
    }

    // ------------------------------------------------------------------ the harness

    final class Harness {
        final JobLeaseRepository leases = mock(JobLeaseRepository.class);
        final JobStepAttemptRepository attempts = mock(JobStepAttemptRepository.class);
        final DeviceRepository devices = mock(DeviceRepository.class);
        final Device device = new Device();
        final Repo repo = new Repo();
        final FileArtefactStore store;
        final List<String> gateIds = new ArrayList<>();
        final List<ArtefactRef> discarded = new ArrayList<>();
        DevicePlatformFactsRepository facts = DevicePlatformFactsRepository.NONE;
        ChunkRedactor redactor = ChunkRedactor.of(new LogRedactor());
        PlannerConfig config = SMALL;
        double free = 0.5;
        Instant now = NOON;
        List<GateRow> rows = fixture();
        String finalState, finalReason;
        int job;

        Harness(Path dir, String role) {
            store = new FileArtefactStore(dir, ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])));
            when(devices.find("device-1")).thenReturn(Optional.of(new DeviceRecord("device-1", role, "check_point", "manual",
                    Instant.now(), false, DeviceEnrollmentState.ENROLLED, false, "credential-ref")));
            when(devices.findEndpointByDeviceId("device-1")).thenReturn(Optional.of(
                    new EndpointRecord("endpoint-1", "device-1", "ssh_exec", "192.0.2.1", Instant.now())));
            when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
            when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString(), any())).thenAnswer(call -> {
                if (call.getArgument(2) == JobState.EXECUTING) { finalState = call.getArgument(3).toString(); finalReason = call.getArgument(6); }
                else if (call.getArgument(3) == JobState.REJECTED) { finalState = "REJECTED"; finalReason = call.getArgument(6); }
                return true;
            });
            when(attempts.findByJobAndStep(anyString(), anyInt())).thenReturn(List.of());
            AtomicInteger ids = new AtomicInteger();
            when(attempts.insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt()))
                    .thenAnswer(call -> { gateIds.add(call.getArgument(3)); return "attempt-" + ids.incrementAndGet(); });
            when(attempts.markBoundaryCrossed(anyString(), anyLong())).thenReturn(true);
            when(attempts.writeOutcome(anyString(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), any())).thenReturn(true);
            device.epoch = now.getEpochSecond();
        }

        void at(Instant instant) { now = instant; device.epoch = instant.getEpochSecond(); }

        void run() {
            job++;
            device.commands.clear(); device.rangeReads.clear(); device.wholeFetches.clear(); gateIds.clear();
            GateRegistryPort gates = key -> rows.stream().filter(r -> r.key().equals(key)).toList();
            Clock clock = new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return now; }
            };
            SystemLogArtefactVolume volume = new SystemLogArtefactVolume() {
                @Override public double freeFraction() { return free; }
                @Override public void discard(ArtefactRef ref) { discarded.add(ref); }
            };
            new CpSystemLogCollectJobExecutor(leases, attempts, devices, device, gates, repo, store, volume,
                    HostnameFingerprint.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])), facts, redactor, clock, config)
                    .execute("job-" + job, 1, "device-1", "192.0.2.1", 22, "credential-ref");
        }

        /** Every stored chunk of a source, decrypted, in storage order. */
        String stored(String source) throws IOException {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < repo.chunks.size(); i++) {
                var c = repo.chunks.get(i);
                if (!source.equals(repo.chunkSourceKeys.get(i))) continue;
                var m = c.chunk().manifest();
                out.append(new String(store.retrieve(new ArtefactRef(m.recoveryVolumePath()), m.wrappedDataKey(), true).readAllBytes(),
                        StandardCharsets.ISO_8859_1));
            }
            return out.toString();
        }


        byte[] storedBytes(int index) throws IOException {
            var m = repo.chunks.get(index).chunk().manifest();
            return store.retrieve(new ArtefactRef(m.recoveryVolumePath()), m.wrappedDataKey(), true).readAllBytes();
        }

        void assertNoDeviceTextInRecords() {
            List<String> records = new ArrayList<>();
            records.add(String.valueOf(finalReason));
            repo.runs.values().forEach(r -> records.add(r.detailJson()));
            repo.gaps.values().forEach(list -> list.forEach(g -> records.add(g.detailJson())));
            repo.cursors.values().forEach(c -> c.familySnapshot().forEach(e -> records.add(e.name())));
            for (String record : records) for (String raw : RAW) assertFalse(record.contains(raw), raw + " leaked into " + record);
        }
    }

    private static List<GateRow> fixture() {
        return GateRegistryFixtureLoader.loadFromStream(CpSystemLogCollectJobExecutorTest.class.getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml")).stream().toList();
    }

    private static String messagesOnly(Harness h) throws IOException {
        return h.stored("cp_messages");
    }

    // ================================================================== run order, first run, growth

    @Test
    void oneSessionRunsC1ThenTheReadsThenC3InOrderAndAFirstRunStoresWholeLinesOnly(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.config = PlannerConfig.defaults();
        String content = lines("first", 0, 5) + "partial line without newline";
        h.device.put(MESSAGES, content, 10);
        h.run();

        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertEquals(1, h.device.connects, "one SSH session");
        assertEquals(List.of(CpSystemLogCollect.STAT_COMMAND, CpSystemLogCollect.LS_CRASH_COMMAND), h.device.commands,
                "C1, then C3; no daily items outside the 06:00 window, no spike listing");
        assertEquals(List.of("cp_syslog_collect_stat", CpSyslogRangedRead.GATE_ID, "cp_syslog_collect_ls_crash"), h.gateIds,
                "every device step is ledgered in run order");
        assertEquals("COLLECTED", h.repo.outcome("cp_messages"));
        assertEquals(lines("first", 0, 5), messagesOnly(h), "the partial last line is carried to the next run");
        var cursor = h.repo.cursors.get("cp_messages");
        assertEquals("10", cursor.inode());
        assertEquals(lines("first", 0, 5).length(), cursor.offsetBytes());
        assertNotNull(cursor.anchorHmac());
        assertEquals("ABSENT", h.repo.outcome("cp_fwd_elg"), "a family with no file is ABSENT, not a failure");
        assertEquals(0, h.repo.chunks.get(0).chunk().redactionCount(), "no secret in these lines");
        assertEquals(LogRedactor.RULE_SET_ID, h.repo.chunks.get(0).redactionRuleSet());
        h.assertNoDeviceTextInRecords();
    }

    @Test
    void growthIsReadFromTheCursorWithTheAnchorCheckedAndSecretsRedactedBeforeStorage(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.config = PlannerConfig.defaults();
        String first = lines("a", 0, 3);
        h.device.put(MESSAGES, first, 10);
        h.run();
        String more = "Oct 17 10:30:00 2026 FW-TANGO-04 clish[1]: cmd password=synthetic-pass done\n" + lines("b", 3, 5);
        h.device.put(MESSAGES, first + more, 10);
        h.run();

        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertEquals("COLLECTED", h.repo.outcome("cp_messages"));
        assertEquals(2, h.repo.chunks.size());
        var growth = h.repo.chunks.get(1);
        assertEquals(first.length(), growth.chunk().startOffset());
        assertEquals((first + more).length(), growth.chunk().endOffset());
        assertTrue(growth.chunk().redactionCount() >= 1, "the secret value is counted");
        String stored = messagesOnly(h);
        assertFalse(stored.contains("synthetic-pass"), "redacted on write, before encryption");
        assertTrue(stored.contains(LogRedactor.REDACTED));
        var fingerprint = HostnameFingerprint.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
        byte[] redacted = h.storedBytes(1);
        assertEquals(fingerprint.ofContent(SystemLogCollectionRepository.CHUNK_CONTENT_DOMAIN, redacted, 0, redacted.length),
                growth.chunk().plaintextHmac(), "the content identity is the keyed digest of the stored, redacted bytes");
        assertNotEquals(growth.chunk().manifest().plaintextSha256(), growth.chunk().plaintextHmac(), "never the bare hash");
        assertEquals(List.of(MESSAGES + "@" + (first.length() - 64) + "+" + (64 + more.length())), h.device.rangeReads,
                "the read starts K = 64 bytes early for the anchor and asks only for the new bytes");
        h.assertNoDeviceTextInRecords();
    }

    @Test
    void aFirstRunOnALargeFileSkipsTheOldPrefixAsAnInitialBaselineGapAndSplitsReadsAtTheCap(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        String content = lines("x", 0, 30); // ~1.4 KiB; backfill 512 B, read cap 256 B
        h.device.put(MESSAGES, content, 10);
        h.run();

        assertEquals("GAP", h.repo.outcome("cp_messages"));
        var baseline = h.repo.gap("cp_messages", "INITIAL_BASELINE");
        String stored = messagesOnly(h);
        assertTrue(content.endsWith(stored), "the newest whole lines");
        assertEquals(content.length() - stored.length(), baseline.knownLostBytes(),
                "the skipped prefix plus the trimmed partial first line, exactly");
        assertTrue(h.device.rangeReads.size() >= 2, "512 B in 256 B reads");
        for (String read : h.device.rangeReads) assertTrue(Long.parseLong(read.substring(read.indexOf('+') + 1)) <= 16 + 256);
        assertEquals(content.length(), h.repo.cursors.get("cp_messages").offsetBytes());
    }

    // ================================================================== rotations

    @Test
    void aRenameRotationReadsTheRemainderOfTheRotatedFileThenTheNewFileFromZero(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.config = PlannerConfig.defaults();
        String old = lines("old", 0, 3);
        h.device.put(MESSAGES, old, 10);
        h.run();
        String tail = lines("old", 3, 5);
        h.device.files.clear();
        h.device.put(MESSAGES, lines("new", 0, 2), 11);
        h.device.put(MESSAGES + ".1", old + tail, 10);
        h.run();

        assertEquals("COLLECTED", h.repo.outcome("cp_messages"));
        assertEquals(old + tail + lines("new", 0, 2), messagesOnly(h), "no byte lost, none twice");
        assertTrue(h.repo.chunks.get(1).chunk().flags().contains("ROTATION_FOLLOWED"));
        assertEquals("11", h.repo.cursors.get("cp_messages").inode());
        assertTrue(h.repo.gapKinds("cp_messages").isEmpty());
    }

    @Test
    void aCopytruncateIsFollowedThroughTheAnchorVerifiedCopy(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.config = PlannerConfig.defaults();
        String old = lines("old", 0, 4);
        h.device.put(MESSAGES, old, 10);
        h.run();
        h.device.files.clear();
        h.device.put(MESSAGES, lines("new", 0, 1), 10);              // same inode, truncated in place
        h.device.put(MESSAGES + ".1", old + lines("old", 4, 6), 20); // the copy holds what came after the cursor
        h.run();

        assertEquals("COLLECTED", h.repo.outcome("cp_messages"));
        assertEquals(old + lines("old", 4, 6) + lines("new", 0, 1), messagesOnly(h));
        assertTrue(h.repo.gapKinds("cp_messages").isEmpty());
    }

    @Test
    void aTruncationWithNoContinuingCopyIsAReportedGapAndTheCursorRestartsOnTheLiveFile(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.config = PlannerConfig.defaults();
        String old = lines("old", 0, 4);
        h.device.put(MESSAGES, old, 10);
        h.run();
        h.device.put(MESSAGES, lines("new", 0, 1), 10);
        h.run();

        assertEquals("GAP", h.repo.outcome("cp_messages"));
        var truncated = h.repo.gap("cp_messages", "TRUNCATED");
        assertEquals(old.length(), truncated.knownLostBytes(), "offset - 0: an upper bound");
        assertTrue(truncated.extentUnknown());
        assertEquals(lines("new", 0, 1).length(), h.repo.cursors.get("cp_messages").offsetBytes());
        h.assertNoDeviceTextInRecords();
    }

    @Test
    void aCursorFileThatRotatedAwayIsAReportedGapWithItsKnownLostBytes(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.config = PlannerConfig.defaults();
        String first = lines("a", 0, 3);
        h.device.put(MESSAGES, first + "half", 10);                  // the cursor stops before the partial line
        h.run();
        h.device.files.clear();
        h.device.put(MESSAGES, lines("c", 0, 1), 30);
        h.device.put(MESSAGES + ".1", lines("b", 0, 2), 31);         // never seen; inode 10 is gone
        h.run();

        assertEquals("GAP", h.repo.outcome("cp_messages"));
        var gap = h.repo.gap("cp_messages", "ROTATED_AWAY");
        assertEquals(4, gap.knownLostBytes(), "last seen size minus the cursor offset");
        assertTrue(gap.extentUnknown(), "whole files may have come and gone unseen");
        assertEquals(first + lines("b", 0, 2) + lines("c", 0, 1), messagesOnly(h));
        assertEquals("30", h.repo.cursors.get("cp_messages").inode());
        h.assertNoDeviceTextInRecords();
    }

    @Test
    void anAnchorMismatchRePlansTheSourceAndRestartsTheFileFromZero(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.config = PlannerConfig.defaults();
        h.device.put(MESSAGES, lines("old", 0, 3), 10);
        h.run();
        String replaced = lines("other", 0, 6);                      // same inode, different content, larger
        h.device.put(MESSAGES, replaced, 10);
        h.run();

        assertEquals("GAP", h.repo.outcome("cp_messages"));
        assertTrue(h.repo.gap("cp_messages", "ANCHOR_MISMATCH").extentUnknown());
        assertEquals(2, h.device.rangeReads.size(), "the mismatched read, then the restart from 0");
        assertTrue(h.device.rangeReads.get(1).startsWith(MESSAGES + "@0+"));
        assertEquals(replaced.length(), h.repo.cursors.get("cp_messages").offsetBytes());
        assertEquals(1, ((Number) h.repo.detail("cp_messages").get("anchor_mismatches")).intValue());
    }

    // ================================================================== budgets, failures, fail-closed

    @Test
    void theSourceBudgetLeavesABacklogWithTheExactPendingBytesAndTheNextRunContinues(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        String first = lines("a", 0, 2);
        h.device.put(MESSAGES, first, 10);
        h.run();
        String grown = first + lines("b", 2, 40);                    // ~1.7 KiB more; source budget 1 KiB
        h.device.put(MESSAGES, grown, 10);
        h.run();

        assertEquals("BACKLOG", h.repo.outcome("cp_messages"));
        var cursor = h.repo.cursors.get("cp_messages");
        var backlog = h.repo.gap("cp_messages", "BACKLOG");
        assertEquals(grown.length() - cursor.offsetBytes(), backlog.pendingBytes(), "exact unread bytes still on the device");
        assertEquals(0, backlog.knownLostBytes());
        h.run();
        h.run();
        assertEquals(grown, messagesOnly(h), "BACKLOG never skips ahead");
        assertEquals("NO_NEW_DATA", h.repo.outcome("cp_messages"));
    }

    @Test
    void withoutARedactorNothingIsReadOrStoredAndTheSourceIsUnknown(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.redactor = null; // the fail-closed default
        h.device.put(MESSAGES, lines("a", 0, 3), 10);
        h.run();

        assertEquals("UNKNOWN", h.repo.outcome("cp_messages"));
        assertEquals("REDACTOR_MISSING", h.repo.detail("cp_messages").get("reason"));
        assertTrue(h.device.rangeReads.isEmpty(), "no read is sent for bytes that could not be stored");
        assertTrue(h.repo.chunks.isEmpty());
        assertNull(h.repo.cursors.get("cp_messages"), "the cursor does not move");
        assertEquals("FAILED", h.finalState);
        assertEquals("partial:REDACTOR_MISSING", h.finalReason);
    }

    @Test
    void aTransportFailureIsUnknownWithTheCursorUnchangedAndACollectionFailedWindow(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.config = PlannerConfig.defaults();
        h.device.put(MESSAGES, lines("a", 0, 3), 10);
        h.run();
        var before = h.repo.cursors.get("cp_messages");
        h.device.put(MESSAGES, lines("a", 0, 6), 10);
        h.device.failRangeWith = RangedFetchStatus.TIMEOUT;
        h.run();

        assertEquals("UNKNOWN", h.repo.outcome("cp_messages"));
        assertEquals("READ_TIMEOUT", h.repo.detail("cp_messages").get("reason"));
        assertEquals(before, h.repo.cursors.get("cp_messages"));
        assertTrue(h.repo.gapKinds("cp_messages").contains("COLLECTION_FAILED"));
        assertEquals("partial:READ_TIMEOUT", h.finalReason);
    }

    @Test
    void lowFreeSpaceRefusesStorageWithoutReading(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.free = 0.10;
        h.device.put(MESSAGES, lines("a", 0, 3), 10);
        h.run();
        assertEquals("STORAGE_REFUSED", h.repo.outcome("cp_messages"));
        assertTrue(h.device.rangeReads.isEmpty());
        assertEquals("FAILED", h.finalState);
    }

    @Test
    void anUnreadableStatMakesEverySourceUnknownNeverAbsent(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.device.statFails = true;
        h.run();
        for (var family : com.securityexpert.nexus.ui2.worker.systemlog.SourceFamily.runOrder())
            assertEquals("UNKNOWN", h.repo.outcome(family.sourceKey()), family.sourceKey());
        assertEquals("STAT_TIMEOUT", h.repo.detail("cp_messages").get("reason"));
    }

    @Test
    void withoutTheCollectionGatesTheJobIsRefusedBeforeAnyDeviceContact(@TempDir Path dir) {
        var h = new Harness(dir, "gateway");
        h.rows = fixture().stream().filter(r -> !r.gateId().startsWith("cp_syslog_collect_")).toList();
        h.run();
        assertEquals("REJECTED", h.finalState);
        assertEquals("SYSTEM_LOG_COLLECT_UNAVAILABLE", h.finalReason);
        assertEquals(0, h.device.connects);
    }

    @Test
    void aWithdrawnC2SignOffRefusesEveryReadBeforeAnyIo(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.rows = fixture().stream().filter(r -> !r.gateId().equals(CpSyslogRangedRead.GATE_ID)).toList();
        h.device.put(MESSAGES, lines("a", 0, 3), 10);
        h.run();
        assertEquals("UNKNOWN", h.repo.outcome("cp_messages"));
        assertEquals("READ_REFUSED", h.repo.detail("cp_messages").get("reason"));
        assertTrue(h.device.rangeReads.isEmpty());
        assertFalse(h.repo.gapKinds("cp_messages").contains("COLLECTION_FAILED"), "a refusal is not a transport failure");
    }

    @Test
    void aManagementServerIsRefusedAndNeverContacted(@TempDir Path dir) {
        var h = new Harness(dir, "management_server");
        h.run();
        assertEquals("REJECTED", h.finalState);
        assertEquals("SYSTEM_LOG_DISABLED_ON_MANAGEMENT", h.finalReason);
        assertEquals(0, h.device.connects);
    }

    @Test
    void aGaiaEmbeddedDeviceRecordsUnsupportedForEverySourceWithoutContact(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        var facts = new DevicePlatformFacts("device-1", Optional.empty(), Optional.empty(), Optional.of("gaia_embedded"),
                Map.of(), Optional.empty(), "synthetic", Optional.empty());
        h.facts = new DevicePlatformFactsRepository() {
            @Override public void record(DevicePlatformFacts f) { }
            @Override public Optional<DevicePlatformFacts> find(String id) { return Optional.of(facts); }
            @Override public Map<String, DevicePlatformFacts> findAll() { return Map.of(); }
        };
        h.run();
        assertEquals(0, h.device.connects);
        assertEquals("COMPLETED", h.finalState);
        assertEquals("UNSUPPORTED", h.repo.outcome("cp_messages"));
        assertEquals("UNSUPPORTED", h.repo.outcome("cp_crash_listing"));
    }

    @Test
    void aConnectionFailureIsUnknownForEverySource(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.device.connectFails = true;
        h.run();
        assertEquals("UNKNOWN", h.repo.outcome("cp_messages"));
        assertEquals("partial:CONNECTION_FAILED", h.finalReason);
    }

    @Test
    void aStaleLeaseStopsTheJobAndRemovesTheUnrecordedChunk(@TempDir Path dir) {
        var h = new Harness(dir, "gateway");
        h.device.put(MESSAGES, lines("a", 0, 3), 10);
        h.repo.fence = false;
        h.run();
        assertEquals("OUTCOME_UNKNOWN", h.finalState);
        assertEquals("SYSTEM_LOG_NOT_RECORDED", h.finalReason);
        assertEquals(1, h.discarded.size());
    }

    // ================================================================== crash and dump listing

    @Test
    void theFirstCrashListingIsABaselineAndALaterNewFileRaisesOneAlertPerProcess(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.device.crashListing = "/var/log/crash/:\ntotal 0\n\n/var/log/dump/usermode/:\ntotal 400\n"
                + "-rw-r--r-- 1 120000 1760600000 fwd.1111.core.gz\n";
        h.run();
        assertEquals("COLLECTED", h.repo.outcome("cp_crash_listing"));
        assertTrue(h.repo.alerts.isEmpty(), "present before monitoring: no alert");
        assertEquals(1, h.repo.crash.size());

        h.device.crashListing = "/var/log/crash/:\ntotal 4\ndrwxr-xr-x 2 4096 1760700000 kdump-synthetic\n\n"
                + "/var/log/dump/usermode/:\ntotal 900\n"
                + "-rw-r--r-- 1 120000 1760600000 fwd.1111.core.gz\n"
                + "-rw-r--r-- 1 230000 1760700100 fwk.4242.core.gz\n"
                + "-rw-r--r-- 1 99 1760700200 fwk.4243.core.gz\n"
                + "-rw-r--r-- 1 10 1760700300 FW-TANGO-04.dump\n";
        h.run();
        Map<String, CheckPointSystemLogRepository.CrashAlert> byProcess = new HashMap<>();
        h.repo.alerts.forEach(a -> byProcess.put(a.process(), a));
        assertEquals(java.util.Set.of("kernel", "fwk", "UNKNOWN"), byProcess.keySet());
        assertEquals(2, byProcess.get("fwk").fileCount());
        assertEquals(1760700200L, byProcess.get("fwk").newestMtime());
        assertEquals(1, byProcess.get("UNKNOWN").fileCount(), "an unrecognised name is still alerted, as UNKNOWN");
        for (var e : h.repo.crash.values()) assertTrue(e.nameHmac().matches("[0-9a-f]{64}"), "names only as HMACs");

        h.device.crashListing = "/var/log/crash/:\ntotal 0\n\n/var/log/dump/usermode/:\ntotal 0\n";
        int alerts = h.repo.alerts.size();
        h.run();
        assertEquals(alerts, h.repo.alerts.size(), "a removed file raises no alert");
        assertTrue(h.repo.crash.isEmpty(), "removed entries are marked");
        h.assertNoDeviceTextInRecords();
    }

    @Test
    void missingCrashDirectoriesAreAbsentNotAFailure(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.device.crashListing = "";
        h.run();
        assertEquals("ABSENT", h.repo.outcome("cp_crash_listing"));
    }

    // ================================================================== daily whole files

    private static final String SAR_YESTERDAY = "sar16";
    private static final String HCP = "hcp_report_FW-TANGO-04_17_10_26_03_00.tar.gz";

    private static void daily(Harness h, long sarSize, byte[] hcp) {
        long epoch = h.device.epoch;
        h.device.put(MESSAGES, lines("a", 0, 1), 10);
        h.device.saListing = BANNER + "\ntotal 99\n"
                + "-rw-r--r-- 1 " + sarSize + " " + (epoch - 6 * 3600) + " " + SAR_YESTERDAY + "\n"
                + "-rw-r--r-- 1 500000 " + (epoch - 6 * 3600) + " sa16\n"
                + "-rw-r--r-- 1 400000 " + (epoch - 31L * 86400) + " sar17\n";
        h.device.hcpListing = "total 10\n-rw-r--r-- 1 " + hcp.length + " " + (epoch - 3 * 3600) + " " + HCP + "\n";
        byte[] sar = new byte[(int) Math.min(sarSize, 4096)];
        java.util.Arrays.fill(sar, (byte) 'x');
        h.device.wholeFiles.put("/var/log/sa/" + SAR_YESTERDAY, sar);
        h.device.wholeFiles.put("/var/log/hcp/last/" + HCP, hcp);
    }

    @Test
    void theSixOClockRunFetchesYesterdaysSarAndTheNewestSettledHcpArchiveOnce(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.config = PlannerConfig.defaults();
        h.at(SIX_AM);
        byte[] hcp = tarGz("hcp/summary.txt", "synthetic report body");
        daily(h, 4096, hcp);
        h.run();

        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertEquals(List.of(CpSystemLogCollect.STAT_COMMAND, CpSystemLogCollect.LS_CRASH_COMMAND,
                CpSystemLogCollect.LS_SA_COMMAND, CpSystemLogCollect.LS_HCP_COMMAND), h.device.commands);
        assertEquals(List.of("cp_sar_daily:/var/log/sa/sar16:120", "cp_hcp_report:/var/log/hcp/last/" + HCP + ":300"),
                h.device.wholeFetches, "C8 timeouts per kind (120 s sar, 300 s HCP)");
        assertEquals("COLLECTED", h.repo.outcome("cp_sar_daily"));
        assertEquals("COLLECTED", h.repo.outcome("cp_hcp_report"));
        var hcpRow = h.repo.files.stream().filter(f -> f.kind().equals("cp_hcp_report")).findFirst().orElseThrow();
        assertEquals("STORED", hcpRow.status());
        assertEquals("2026-10-17 03:00:00", hcpRow.nameTime());
        assertTrue(hcpRow.flags().isEmpty());
        assertEquals(SIX_AM.plus(Duration.ofDays(7)), hcpRow.expiresAt(), "HCP: 7 days");
        byte[] storedHcp = h.store.retrieve(new ArtefactRef(hcpRow.manifest().recoveryVolumePath()),
                hcpRow.manifest().wrappedDataKey(), false).readAllBytes();
        assertArrayEquals(hcp, storedHcp, "stored as received, no redaction");
        assertEquals(HostnameFingerprint.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]))
                .ofContent(SystemLogCollectionRepository.FILE_CONTENT_DOMAIN, hcp, 0, hcp.length), hcpRow.contentHmac());
        var sarRow = h.repo.files.stream().filter(f -> f.kind().equals("cp_sar_daily")).findFirst().orElseThrow();
        byte[] sar = h.device.wholeFiles.get("/var/log/sa/" + SAR_YESTERDAY);
        assertEquals(HostnameFingerprint.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]))
                .ofContent(SystemLogCollectionRepository.FILE_CONTENT_DOMAIN, sar, 0, sar.length), sarRow.contentHmac(),
                "the streamed sar is keyed over exactly the bytes received");

        h.at(SIX_AM.plusSeconds(600));
        h.device.epoch = SIX_AM.getEpochSecond(); // the same listing (same mtimes) in a second 06:00 job
        daily(h, 4096, hcp);
        h.run();
        assertTrue(h.device.wholeFetches.isEmpty(), "a whole file is fetched once");
        assertEquals("NO_NEW_DATA", h.repo.outcome("cp_sar_daily"));
        h.assertNoDeviceTextInRecords();
    }

    @Test
    void aSarLeftAsBacklogByTheDeviceBudgetIsFetchedInTheNextWindowOfTheDay(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.at(SIX_AM);
        daily(h, 4096, tarGz("a", "b")); // the SMALL device budget is 2 KiB
        h.run();
        assertEquals("BACKLOG", h.repo.outcome("cp_sar_daily"));
        assertEquals(4096, h.repo.gap("cp_sar_daily", "BACKLOG").pendingBytes());

        h.config = PlannerConfig.defaults();
        h.at(NOON);
        long epoch = h.device.epoch;
        h.device.saListing = "-rw-r--r-- 1 4096 " + (SIX_AM.getEpochSecond() - 6 * 3600) + " " + SAR_YESTERDAY + "\n";
        h.run();
        assertEquals("COLLECTED", h.repo.outcome("cp_sar_daily"));
        assertFalse(h.device.commands.contains(CpSystemLogCollect.LS_HCP_COMMAND), "HCP stays a 06:00 item");
        assertTrue(epoch > 0);
    }

    @Test
    void outsideTheSixOClockWindowNoDailyItemIsListedOrFetched(@TempDir Path dir) {
        var h = new Harness(dir, "gateway");
        h.at(NOON);
        daily(h, 4096, new byte[] {1});
        h.run();
        assertFalse(h.device.commands.contains(CpSystemLogCollect.LS_SA_COMMAND));
        assertFalse(h.device.commands.contains(CpSystemLogCollect.LS_HCP_COMMAND));
        assertTrue(h.device.wholeFetches.isEmpty());
    }

    @Test
    void aSarFileOverItsBoundIsTooLargeAndNeverFetched(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.at(SIX_AM);
        daily(h, 32L * 1024 * 1024 + 1, tarGz("a", "b"));
        h.run();
        assertEquals("TOO_LARGE", h.repo.outcome("cp_sar_daily"));
        assertTrue(h.device.wholeFetches.stream().noneMatch(f -> f.startsWith("cp_sar_daily")));
    }

    @Test
    void aBrokenHcpArchiveIsStoredAsCorruptAndAnUnexpectedNameIsFlagged(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.at(SIX_AM);
        daily(h, 10, "not a gzip archive".getBytes(StandardCharsets.US_ASCII));
        h.device.hcpListing = h.device.hcpListing.replace(HCP, "report-synthetic.tar.gz");
        h.device.wholeFiles.put("/var/log/hcp/last/report-synthetic.tar.gz", "not a gzip archive".getBytes(StandardCharsets.US_ASCII));
        h.run();
        var row = h.repo.files.stream().filter(f -> f.kind().equals("cp_hcp_report")).findFirst().orElseThrow();
        assertEquals("CORRUPT", row.status());
        assertEquals(List.of("NAME_SHAPE_UNEXPECTED"), row.flags());
        assertNull(row.nameTime());
    }

    @Test
    void anHcpArchiveYoungerThanTenMinutesWaitsForTheNextDay(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.at(SIX_AM);
        byte[] hcp = tarGz("a", "b");
        daily(h, 10, hcp);
        h.device.hcpListing = "-rw-r--r-- 1 " + hcp.length + " " + (h.device.epoch - 120) + " " + HCP + "\n";
        h.run();
        assertEquals("NO_NEW_DATA", h.repo.outcome("cp_hcp_report"));
        assertEquals(true, h.repo.detail("cp_hcp_report").get("not_settled"));
    }

    @Test
    void spikeDataStaysOffByDefaultAndIsReportedUnsupportedWhenSwitchedOn(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.run();
        assertFalse(h.repo.runs.containsKey("cp_spike_data"));
        h.repo.settings = new SystemLogCollectionRepository.Settings(30, false,
                SystemLogCollectionRepository.Settings.DEFAULT_DEVICE_CAP_BYTES, 7, 30,
                SystemLogCollectionRepository.Settings.DEFAULT_SPIKE_DATA_CAP_BYTES, 180, true);
        h.run();
        assertEquals("UNSUPPORTED", h.repo.outcome("cp_spike_data"));
        assertEquals("SPIKE_DATA_NOT_IMPLEMENTED", h.repo.detail("cp_spike_data").get("reason"));
        assertFalse(h.device.commands.stream().anyMatch(c -> c.contains("spike_detective/'")), "no C4 is sent");
    }

    @Test
    void theCapabilityIsExecutionEligibleOnlyWithItsGates() {
        GateRegistryPort gates = key -> fixture().stream().filter(r -> r.key().equals(key)).toList();
        assertTrue(CpSystemLogCollect.capability(gates).executionEligible());
        GateRegistryPort none = key -> List.of();
        assertFalse(CpSystemLogCollect.capability(none).executionEligible());
    }

    @Test
    void theProcessParseNamesOnlyKnownDaemonsInTheRecognisedForm() {
        assertEquals("fwk", CpSystemLogCollectJobExecutor.process("fwk.4242.core.gz"));
        assertEquals("cpd", CpSystemLogCollectJobExecutor.process("cpd.17.core"));
        assertEquals("UNKNOWN", CpSystemLogCollectJobExecutor.process("FW-TANGO-04.12.core.gz"), "never a host name");
        assertEquals("UNKNOWN", CpSystemLogCollectJobExecutor.process("core.1234"));
    }

    /** A one-member ustar archive in gzip, written by hand (synthetic content only). */
    static byte[] tarGz(String name, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.US_ASCII);
        byte[] header = new byte[512];
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(n, 0, header, 0, n.length);
        put(header, 100, "0000644");
        put(header, 108, "0000000");
        put(header, 116, "0000000");
        put(header, 124, String.format("%011o", data.length));
        put(header, 136, String.format("%011o", 1_760_000_000L));
        java.util.Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = '0';
        put(header, 257, "ustar");
        long sum = 0;
        for (byte b : header) sum += b & 0xff;
        put(header, 148, String.format("%06o", sum));
        header[154] = 0;
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        tar.write(header);
        tar.write(data);
        tar.write(new byte[(512 - data.length % 512) % 512]);
        tar.write(new byte[1024]);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) { out.write(tar.toByteArray()); }
        return gz.toByteArray();
    }

    private static void put(byte[] header, int offset, String text) {
        byte[] b = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, header, offset, b.length);
    }
}
