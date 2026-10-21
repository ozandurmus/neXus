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
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSpikeLayoutReview;
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
import com.securityexpert.nexus.ui2.persistence.diagnostic.CheckPointSystemLogRepository.SpikeDirectory;
import com.securityexpert.nexus.ui2.persistence.diagnostic.CheckPointSystemLogRepository.SpikeDirectoryState;
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
            "hcp_report_", "fwk.4242", "fwtango");
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
        /** C4 output (null: the directory listing is not scripted and C4 is an unexpected command). */
        String spikeListing;
        int spikeExit;
        ExecResult spikeListingResult;
        /** C5 output per data-directory name; a name without one answers as a missing directory. */
        final Map<String, String> spikeDirectories = new LinkedHashMap<>();
        final List<String> spikeDirectoriesListed = new ArrayList<>();
        final Map<String, byte[]> wholeFiles = new HashMap<>();
        long epoch;
        boolean statFails;
        boolean connectFails;
        int connects;
        RangedFetchStatus failRangeWith;
        /** Called when a ranged read arrives; the harness uses it to move the test clock. */
        Runnable onRangeRead;

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
            if (spec.command().equals(CpSpikeLayoutReview.LS_SPIKE_COMMAND) && (spikeListing != null || spikeListingResult != null)) {
                assertEquals(CpSpikeLayoutReview.LS_SPIKE_MAX_BYTES, spec.outputLimitBytes());
                return spikeListingResult != null ? spikeListingResult : new ExecResult.Completed(spikeListing, spikeExit);
            }
            String c5Prefix = "bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/";
            if (spec.command().startsWith(c5Prefix) && spec.command().endsWith("/'") && spikeListing != null) {
                assertEquals(CpSpikeLayoutReview.LS_SPIKE_DIR_MAX_BYTES, spec.outputLimitBytes());
                String name = spec.command().substring(c5Prefix.length(), spec.command().length() - 2);
                spikeDirectoriesListed.add(name);
                String listing = spikeDirectories.get(name);
                return listing == null ? new ExecResult.Completed("", 2) : new ExecResult.Completed(listing, 0);
            }
            throw new AssertionError("unexpected command");
        }

        @Override public RangedFetchResult fetchRange(TransportSession session, RangedFetchSpec spec, Duration timeout, OutputStream sink) {
            rangeReads.add(spec.remotePath() + "@" + spec.startOffset() + "+" + spec.maxBytes());
            if (onRangeRead != null) onRangeRead.run();
            if (failRangeWith != null) return new RangedFetchResult(failRangeWith, spec.startOffset(), spec.maxBytes(), 0, OptionalLong.empty(), false, "SYNTH");
            RemoteFile file = files.get(spec.remotePath());
            if (file == null) return new RangedFetchResult(RangedFetchStatus.FILE_MISSING, spec.startOffset(), spec.maxBytes(), 0, OptionalLong.empty(), false, "SFTP_STATUS_2");
            if (file.content.length < spec.startOffset())  // what the real transport reports for a file below the range start
                return new RangedFetchResult(RangedFetchStatus.SIZE_BELOW_OFFSET, spec.startOffset(), spec.maxBytes(), 0,
                        OptionalLong.of(file.content.length), false, "");
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
        final Map<String, SpikeDirectory> spike = new LinkedHashMap<>();
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

        @Override public boolean spikeBaselineTaken(String d) {
            return !spike.isEmpty() || history.stream().anyMatch(h -> h.startsWith("cp_spike_data=")
                    && !h.endsWith("=UNKNOWN") && !h.endsWith("=ABSENT") && !h.endsWith("=UNSUPPORTED"));
        }

        @Override public java.util.Set<String> spikeDirectoryHmacs(String d) { return new java.util.HashSet<>(spike.keySet()); }

        @Override public SpikeWrite recordSpikeDirectory(String j, long e, String d, SpikeDirectory row, Optional<FileRow> file) {
            if (!fence) return SpikeWrite.NOT_RECORDED;
            if (spike.containsKey(row.nameHmac())) return SpikeWrite.DUPLICATE;
            file.ifPresent(files::add);
            spike.put(row.nameHmac(), row);
            return SpikeWrite.STORED;
        }

        @Override public boolean recordSpikeListing(String j, long e, String d, Run run, List<SpikeDirectory> listed, List<Gap> g) {
            if (!fence) return false;
            runs.put("cp_spike_data", run); history.add("cp_spike_data=" + run.outcome());
            for (SpikeDirectory row : listed) spike.putIfAbsent(row.nameHmac(), row);
            gaps.computeIfAbsent("cp_spike_data", k -> new ArrayList<>()).addAll(g);
            return true;
        }

        long spikeIn(SpikeDirectoryState state) { return spike.values().stream().filter(r -> r.state() == state).count(); }

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

        /** Every ranged read takes this long on the test clock. */
        void readTakes(long millis) { device.onRangeRead = () -> now = now.plusMillis(millis); }

        void at(Instant instant) { now = instant; device.epoch = instant.getEpochSecond(); }

        void run() {
            job++;
            device.commands.clear(); device.rangeReads.clear(); device.wholeFetches.clear(); gateIds.clear();
            device.spikeDirectoriesListed.clear();
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
            repo.spike.values().forEach(r -> { records.add(r.nameClass()); records.add(r.membersJson()); });
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
    void aSourceRunRecordsTheRealElapsedTimeOfItsReadsNotZero(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.config = PlannerConfig.defaults();
        h.device.put(MESSAGES, lines("timed", 0, 5), 10);
        h.readTakes(250);
        h.run();

        assertEquals("COLLECTED", h.repo.outcome("cp_messages"));
        assertEquals(1, h.repo.runs.get("cp_messages").readsIssued());
        assertEquals(250, h.repo.runs.get("cp_messages").durationMs(), "first read sent to last chunk stored, one 250 ms read");
        assertEquals(0, h.repo.runs.get("cp_fwd_elg").durationMs(), "a source with no read has no elapsed time");
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

    /** The file is replaced by {@code content} (same inode) when the next ranged read arrives: stat OK, read finds it shrunk. */
    private static void shrinkOnNextRead(Harness h, String path, String content, long inode) {
        h.device.onRangeRead = () -> { h.device.onRangeRead = null; h.device.put(path, content, inode); };
    }

    @Test
    void aFileTruncatedBetweenTheStatAndTheReadDefersTheSourceAndTheNextRunsStatBooksTheTruncation(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.config = PlannerConfig.defaults();
        String old = lines("old", 0, 4);
        h.device.put(MESSAGES, old, 10);
        h.run();
        var before = h.repo.cursors.get("cp_messages");
        h.device.put(MESSAGES, old + lines("grown", 0, 2), 10);   // the stat shows growth ...
        String fresh = lines("fresh", 0, 2);
        shrinkOnNextRead(h, MESSAGES, fresh, 10);                  // ... the read finds a smaller file
        h.run();

        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertEquals("SYSTEM_LOG_COLLECTED", h.finalReason, "a deferred source does not fail the job");
        assertEquals("BACKLOG", h.repo.outcome("cp_messages"));
        assertEquals("FILE_CHANGED_DURING_READ", h.repo.detail("cp_messages").get("reason"));
        assertEquals(Boolean.TRUE, h.repo.detail("cp_messages").get("pending_bytes_unknown"), "unknown, not 0");
        assertEquals(before, h.repo.cursors.get("cp_messages"), "cursor and anchor untouched");
        assertTrue(h.repo.gapKinds("cp_messages").isEmpty(), "no gap and no COLLECTION_FAILED window");
        assertEquals(1, h.repo.chunks.size(), "nothing stored for the deferred run");
        assertEquals(1, h.device.rangeReads.size(), "no re-read from 0 in the same run");
        h.assertNoDeviceTextInRecords();

        h.run();                                                   // the next window's fresh stat shows the smaller file
        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertEquals("GAP", h.repo.outcome("cp_messages"));
        var truncated = h.repo.gap("cp_messages", "TRUNCATED");
        assertEquals(old.length(), truncated.knownLostBytes(), "the cursor offset: an upper bound");
        assertTrue(truncated.extentUnknown());
        assertEquals(1, h.device.rangeReads.size());
        assertTrue(h.device.rangeReads.get(0).startsWith(MESSAGES + "@0+"));
        assertEquals(old + fresh, messagesOnly(h), "the new file's lines once");
        assertEquals(fresh.length(), h.repo.cursors.get("cp_messages").offsetBytes());

        h.run();                                                   // and nothing is stored twice afterwards
        assertEquals("NO_NEW_DATA", h.repo.outcome("cp_messages"));
        assertEquals(old + fresh, messagesOnly(h));
        assertEquals(2, h.repo.chunks.size());
        assertEquals(1, h.repo.gapKinds("cp_messages").stream().filter("TRUNCATED"::equals).count(), "the gap is recorded once");
    }

    @Test
    void aFileRenamedAndReplacedBetweenTheStatAndTheReadIsFollowedAsARotationByTheNextRun(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.config = PlannerConfig.defaults();
        String old = lines("old", 0, 4);
        h.device.put(MESSAGES, old, 10);
        h.run();
        var before = h.repo.cursors.get("cp_messages");
        h.device.put(MESSAGES, old + lines("old", 4, 6), 10);
        shrinkOnNextRead(h, MESSAGES, lines("new", 0, 1), 11);     // the read meets a new, smaller file under the same name
        h.run();

        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertEquals("BACKLOG", h.repo.outcome("cp_messages"));
        assertEquals(before, h.repo.cursors.get("cp_messages"));
        assertTrue(h.repo.gapKinds("cp_messages").isEmpty());

        String tail = lines("old", 4, 6);
        h.device.files.clear();                                    // the next stat shows the rename
        h.device.put(MESSAGES, lines("new", 0, 1), 11);
        h.device.put(MESSAGES + ".1", old + tail, 10);
        h.run();
        assertEquals("COLLECTED", h.repo.outcome("cp_messages"));
        assertEquals(old + tail + lines("new", 0, 1), messagesOnly(h), "no byte lost, none twice");
        assertTrue(h.repo.gapKinds("cp_messages").isEmpty());
    }

    @Test
    void aFileEmptiedBetweenTheStatAndTheReadIsDeferredAndTheNextRunResetsTheCursorToZero(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.config = PlannerConfig.defaults();
        String old = lines("old", 0, 4);
        h.device.put(MESSAGES, old, 10);
        h.run();
        var before = h.repo.cursors.get("cp_messages");
        h.device.put(MESSAGES, old + lines("grown", 0, 2), 10);
        shrinkOnNextRead(h, MESSAGES, "", 10);
        h.run();
        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertEquals("BACKLOG", h.repo.outcome("cp_messages"));
        assertEquals(before, h.repo.cursors.get("cp_messages"));

        h.run();                                                   // the stat now shows an empty file
        assertEquals("GAP", h.repo.outcome("cp_messages"));
        assertEquals(old.length(), h.repo.gap("cp_messages", "TRUNCATED").knownLostBytes());
        var cursor = h.repo.cursors.get("cp_messages");
        assertEquals(0, cursor.offsetBytes());
        assertNull(cursor.anchorHmac(), "offset 0 carries no anchor");

        String fresh = lines("fresh", 0, 2);
        h.device.put(MESSAGES, fresh, 10);
        h.run();
        assertEquals("COLLECTED", h.repo.outcome("cp_messages"));
        assertEquals(old + fresh, messagesOnly(h));
    }

    @Test
    void aFirstRunWhoseFileShrinksBelowTheBaselineStartReadsTheWholeFileWithoutAnyGap(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.device.put(MESSAGES, lines("x", 0, 30), 10);             // ~1.4 KiB: the baseline starts well inside the file
        String fresh = lines("fresh", 0, 4);                       // ~200 B: below that start
        shrinkOnNextRead(h, MESSAGES, fresh, 10);
        h.run();

        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertEquals("COLLECTED", h.repo.outcome("cp_messages"));
        assertTrue(h.repo.gapKinds("cp_messages").isEmpty(), "nothing was read before, so nothing is lost");
        assertEquals(fresh, messagesOnly(h));
        assertEquals(fresh.length(), h.repo.cursors.get("cp_messages").offsetBytes());
    }

    @Test
    void aFirstRunShrunkFileIsReadOnlyUpToTheSourceBudgetAndTheRestIsBacklog(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");                       // SMALL: 256 B reads, 1024 B per source
        h.device.put(MESSAGES, lines("x", 0, 60), 10);             // ~3.3 KiB: the baseline starts near 2.8 KiB
        String fresh = lines("fresh", 0, 24);                      // ~1.3 KiB: below that start, above the 1024 B budget
        shrinkOnNextRead(h, MESSAGES, fresh, 10);
        h.run();

        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertEquals("BACKLOG", h.repo.outcome("cp_messages"));
        assertEquals(List.of("BACKLOG"), h.repo.gapKinds("cp_messages"), "no TRUNCATED and no INITIAL_BASELINE");
        long cursorOffset = h.repo.cursors.get("cp_messages").offsetBytes();
        assertTrue(cursorOffset > 0 && cursorOffset <= 1024, "the cursor stays inside the budgeted bytes");
        assertEquals(fresh.length() - cursorOffset, h.repo.gap("cp_messages", "BACKLOG").pendingBytes());
        assertEquals(fresh.substring(0, (int) cursorOffset), messagesOnly(h), "whole lines, once");
    }

    @Test
    void aShrunkRotatedSiblingDefersTheSourceWithTheCursorUnchanged(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.config = PlannerConfig.defaults();
        String old = lines("old", 0, 3);
        h.device.put(MESSAGES, old, 10);
        h.run();
        var before = h.repo.cursors.get("cp_messages");
        h.device.files.clear();
        h.device.put(MESSAGES, lines("new", 0, 2), 11);
        h.device.put(MESSAGES + ".1", old + lines("old", 3, 5), 10);
        shrinkOnNextRead(h, MESSAGES + ".1", lines("other", 0, 1), 10);
        h.run();

        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertEquals("BACKLOG", h.repo.outcome("cp_messages"));
        assertEquals("FILE_CHANGED_DURING_READ", h.repo.detail("cp_messages").get("reason"));
        assertEquals(before, h.repo.cursors.get("cp_messages"));
        assertTrue(h.repo.gapKinds("cp_messages").isEmpty());
    }

    /** The file is replaced by {@code content} when the {@code nth} ranged read of the run arrives. */
    private static void changeOnRead(Harness h, int nth, String path, String content, long inode) {
        int[] seen = {0};
        h.device.onRangeRead = () -> { if (++seen[0] == nth) { h.device.onRangeRead = null; h.device.put(path, content, inode); } };
    }

    @Test
    void aFileThatEndsInsideTheAnchorRegionDefersTheSourceAndTheNextRunsStatBooksTheTruncation(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.config = PlannerConfig.defaults();                       // 64 B anchor region
        String old = lines("old", 0, 4);
        h.device.put(MESSAGES, old, 10);
        h.run();
        var before = h.repo.cursors.get("cp_messages");
        h.device.put(MESSAGES, old + lines("grown", 0, 2), 10);
        // offset - 64 <= size < offset: the read opens fine, the file ends before the anchor region is complete
        shrinkOnNextRead(h, MESSAGES, lines("cut", 0, 10).substring(0, old.length() - 30), 10);
        h.run();

        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertEquals("BACKLOG", h.repo.outcome("cp_messages"));
        assertEquals("FILE_CHANGED_DURING_READ", h.repo.detail("cp_messages").get("reason"));
        assertEquals(Boolean.TRUE, h.repo.detail("cp_messages").get("pending_bytes_unknown"));
        assertEquals(before, h.repo.cursors.get("cp_messages"));
        assertTrue(h.repo.gapKinds("cp_messages").isEmpty());
        assertEquals(1, h.repo.chunks.size());

        String fresh = lines("fresh", 0, 2);                      // the next stat shows a smaller file
        h.device.put(MESSAGES, fresh, 10);
        h.run();
        assertEquals("GAP", h.repo.outcome("cp_messages"));
        assertEquals(old.length(), h.repo.gap("cp_messages", "TRUNCATED").knownLostBytes());
        assertEquals(old + fresh, messagesOnly(h));
        assertEquals(fresh.length(), h.repo.cursors.get("cp_messages").offsetBytes());
    }

    @Test
    void aFirstRunWhoseFileEndsInsideTheBaselineAnchorRegionReadsTheWholeFileWithoutAnyGap(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");                       // SMALL: 512 B backfill, 16 B anchor region
        String big = lines("x", 0, 30);
        h.device.put(MESSAGES, big, 10);
        int start = big.length() - 512;                            // where the baseline read begins
        String shrunk = lines("fresh", 0, 30).substring(0, start - 8);   // start - 16 <= size < start
        shrinkOnNextRead(h, MESSAGES, shrunk, 10);
        h.run();

        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertTrue(h.repo.gapKinds("cp_messages").stream().noneMatch(k -> k.equals("INITIAL_BASELINE") || k.equals("TRUNCATED")));
        assertTrue(h.device.rangeReads.get(1).startsWith(MESSAGES + "@0+"), "the smaller file is read from 0");
        String whole = shrunk.substring(0, shrunk.lastIndexOf('\n') + 1);
        assertEquals(whole, messagesOnly(h), "whole lines of the smaller file, once");
        assertEquals(whole.length(), h.repo.cursors.get("cp_messages").offsetBytes());
    }

    @Test
    void aFileChangedAfterTheRunStoredAChunkKeepsTheChunkDefersTheSourceAndTheNextRunReplans(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");                       // SMALL: 256 B reads, 16 B anchor, 1024 B per source
        String old = lines("old", 0, 4);
        h.device.put(MESSAGES, old, 10);
        h.run();
        String grown = old + lines("grown", 0, 10);                // 560 B of growth: several reads
        h.device.put(MESSAGES, grown, 10);
        String changed = lines("other", 0, 12);                    // same name and inode, other bytes
        changeOnRead(h, 2, MESSAGES, changed, 10);
        h.run();

        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertEquals("SYSTEM_LOG_COLLECTED", h.finalReason, "a deferred source does not fail the job");
        assertEquals("BACKLOG", h.repo.outcome("cp_messages"));
        assertEquals("CONTENT_CHANGED_DURING_RUN", h.repo.detail("cp_messages").get("reason"));
        assertEquals(Boolean.TRUE, h.repo.detail("cp_messages").get("pending_bytes_unknown"));
        assertEquals(2, h.repo.chunks.size(), "the chunk read before the change stays stored");
        var kept = h.repo.chunks.get(1);
        String stored = messagesOnly(h);
        assertTrue(grown.startsWith(stored), "only bytes of the file as it was when read, each once");
        assertEquals(stored.length(), h.repo.cursors.get("cp_messages").offsetBytes(), "cursor at the last stored byte");
        assertEquals(kept.chunk().endOffset(), h.repo.cursors.get("cp_messages").offsetBytes());
        assertTrue(h.repo.gapKinds("cp_messages").isEmpty(), "no guessed gap and no COLLECTION_FAILED window");
        h.assertNoDeviceTextInRecords();

        h.run();                                                   // the next run: fresh stat, anchor check, planner decides
        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertEquals("GAP", h.repo.outcome("cp_messages"));
        assertTrue(h.repo.gapKinds("cp_messages").contains("ANCHOR_MISMATCH"));
        assertEquals(changed.length(), h.repo.cursors.get("cp_messages").offsetBytes());
        assertTrue(messagesOnly(h).endsWith(changed), "the changed file is read from 0 once");
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

    // ================================================================== Spike Detective data (section 8.3, PO 2026-10-20)

    private static final String SPIKE = "/var/log/spike_detective";
    /** Synthetic data-directory names of the observed class X_spike_X_NNN_NNN-NN-NN_NN-NN-NN; "fwtango" stands for a host. */
    private static String spikeName(int n) { return String.format("fwtango_spike_alpha_%03d_2026-10-20_10-%02d-00", n, n % 60); }

    private static void spikeOn(Harness h) {
        h.device.put(MESSAGES, lines("spike", 0, 3), 10);
        h.repo.settings = new SystemLogCollectionRepository.Settings(30, false,
                SystemLogCollectionRepository.Settings.DEFAULT_DEVICE_CAP_BYTES, 7, 30,
                SystemLogCollectionRepository.Settings.DEFAULT_SPIKE_DATA_CAP_BYTES, 180, true);
    }

    /** The C4 output: banner, total line, the data directories (name, mtime) and the two plain files. */
    private static String spikeParent(Map<String, Long> directories) {
        StringBuilder out = new StringBuilder(BANNER).append("\ntotal 312\n");
        for (var d : directories.entrySet()) out.append("drwxr-xr-x 2 4096 ").append(d.getValue()).append(' ').append(d.getKey()).append('\n');
        out.append("-rw-r--r-- 1 23678 1790000000 spike_detective.csv\n-rw-r--r-- 1 1661 1790000000 spike_detective.log\n");
        return out.toString();
    }

    /** One data directory as the review saw it: the perf sample, the profiling summary, plus one other file and a subdirectory. */
    private static void spikeDirectory(Harness h, String name, byte[] perf, byte[] profiling) {
        long t = h.device.epoch - 3600;
        h.device.spikeDirectories.put(name, "total 16\n-rw-r--r-- 1 " + perf.length + " " + t + " perf_fwtango_123.log\n"
                + "-rw-r--r-- 1 " + profiling.length + " " + t + " profiling_summary.elg\n"
                + "-rw-r--r-- 1 77 " + t + " notes_fwtango04.txt\n"
                + "drwxr-xr-x 2 4096 " + t + " extra\n");
        h.device.wholeFiles.put(SPIKE + "/" + name + "/perf_fwtango_123.log", perf);
        h.device.wholeFiles.put(SPIKE + "/" + name + "/profiling_summary.elg", profiling);
        h.device.wholeFiles.put(SPIKE + "/" + name + "/notes_fwtango04.txt", new byte[77]);
    }

    private static List<String> c4AndC5(Harness h) {
        return h.device.commands.stream().filter(c -> c.startsWith("bash -lc 'ls -lgo") && c.contains("spike_detective/")).toList();
    }

    /** The stored archive of one file row, decrypted: tar member name to bytes, in archive order. */
    private static Map<String, byte[]> archive(Harness h, CheckPointSystemLogRepository.FileRow row) throws IOException {
        var m = row.manifest();
        byte[] gz = h.store.retrieve(new ArtefactRef(m.recoveryVolumePath()), m.wrappedDataKey(), false).readAllBytes();
        assertTrue(TarGzIntegrity.readable(gz, gz.length), "a readable tar.gz");
        byte[] tar;
        try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gz))) { tar = in.readAllBytes(); }
        Map<String, byte[]> out = new LinkedHashMap<>();
        int at = 0;
        while (at + 512 <= tar.length) {
            String name = new String(tar, at, 100, StandardCharsets.US_ASCII).replace("\0", "");
            if (name.isEmpty()) break;
            int size = Integer.parseInt(new String(tar, at + 124, 11, StandardCharsets.US_ASCII).trim(), 8);
            out.put(name, java.util.Arrays.copyOfRange(tar, at + 512, at + 512 + size));
            at += 512 + (size + 511) / 512 * 512;
        }
        return out;
    }

    @Test
    void spikeDataStaysOffByDefaultNoListingIsSentAndNoRowIsWritten(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.device.put(MESSAGES, lines("spike", 0, 3), 10);
        h.device.spikeListing = spikeParent(Map.of(spikeName(1), h.device.epoch - 7200));
        h.run();
        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertFalse(h.repo.runs.containsKey("cp_spike_data"));
        assertTrue(c4AndC5(h).isEmpty(), "no C4 or C5 while spike_data_enabled is off (the default)");
        assertTrue(h.repo.spike.isEmpty());
        assertFalse(SystemLogCollectionRepository.Settings.DEFAULTS.spikeDataEnabled(), "the default stays off");
    }

    @Test
    void theFirstListingIsABaselineStoredAsHmacsAndNothingIsFetched(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        spikeOn(h);
        Map<String, Long> dirs = new LinkedHashMap<>();
        for (int i = 1; i <= 3; i++) dirs.put(spikeName(i), h.device.epoch - 86_400L * i);
        h.device.spikeListing = spikeParent(dirs);
        for (String name : dirs.keySet()) spikeDirectory(h, name, "perf".getBytes(StandardCharsets.US_ASCII), "p".getBytes(StandardCharsets.US_ASCII));
        h.run();

        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertEquals(List.of(CpSpikeLayoutReview.LS_SPIKE_COMMAND), c4AndC5(h), "C4 only: no C5 on the baseline");
        assertEquals(List.of(CpSystemLogCollect.STAT_COMMAND, CpSystemLogCollect.LS_CRASH_COMMAND, CpSpikeLayoutReview.LS_SPIKE_COMMAND),
                h.device.commands, "section 3.2 order: C1, (C2 reads), C3, C4");
        assertTrue(h.device.wholeFetches.isEmpty(), "a baseline directory is never fetched");
        assertEquals(3, h.repo.spikeIn(SpikeDirectoryState.BASELINE));
        assertEquals("NO_NEW_DATA", h.repo.outcome("cp_spike_data"));
        assertEquals(true, h.repo.detail("cp_spike_data").get("baseline"));
        assertEquals(3, h.repo.detail("cp_spike_data").get("baseline_directories"));
        assertEquals(1, h.repo.detail("cp_spike_data").get("ignored_lines"), "the banner line is ignored and counted");
        for (var row : h.repo.spike.values()) {
            assertTrue(row.nameHmac().matches("[0-9a-f]{64}"));
            assertEquals("X_spike_X_NNN_NNN-NN-NN_NN-NN-NN", row.nameClass(), "a class, never the name");
            assertNull(row.fileId());
        }
        assertTrue(h.repo.files.isEmpty());
        h.assertNoDeviceTextInRecords();

        h.run();
        assertEquals(List.of(CpSpikeLayoutReview.LS_SPIKE_COMMAND), c4AndC5(h), "the same directories again: not new");
        assertEquals("NO_NEW_DATA", h.repo.outcome("cp_spike_data"));
        assertEquals(false, h.repo.detail("cp_spike_data").get("baseline"));
        assertEquals(0, h.repo.detail("cp_spike_data").get("new_directories"));
    }

    @Test
    void aNewSettledDirectoryIsListedAndItsTwoFetchListMembersAreStoredWhileAnUnsettledOneWaits(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        h.config = PlannerConfig.defaults();   // spike bytes count against the 64 MiB device budget (section 3.4)
        spikeOn(h);
        Map<String, Long> dirs = new LinkedHashMap<>();
        dirs.put(spikeName(1), h.device.epoch - 86_400);
        h.device.spikeListing = spikeParent(dirs);
        h.run();                                          // baseline
        assertEquals(1, h.repo.spikeIn(SpikeDirectoryState.BASELINE));

        byte[] perf = "cpu 1 synthetic sample FW-TANGO-04\n".repeat(300).getBytes(StandardCharsets.US_ASCII);
        byte[] profiling = "synthetic profiling summary\n".getBytes(StandardCharsets.US_ASCII);
        String settled = spikeName(2), fresh = spikeName(3);
        dirs.put(settled, h.device.epoch - 3600);
        dirs.put(fresh, h.device.epoch - 120);               // younger than 10 minutes on the device clock (C1)
        h.device.spikeListing = spikeParent(dirs);
        spikeDirectory(h, settled, perf, profiling);
        spikeDirectory(h, fresh, perf, profiling);
        h.run();

        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertEquals(List.of(CpSpikeLayoutReview.LS_SPIKE_COMMAND,
                CpSpikeLayoutReview.LS_SPIKE_DIR_TEMPLATE.replace("<dir>", settled)), c4AndC5(h),
                "one C5, for the settled directory only; byte-identical to the gate template");
        assertEquals(List.of("cp_spike_data:" + SPIKE + "/" + settled + "/perf_fwtango_123.log:120",
                "cp_spike_data:" + SPIKE + "/" + settled + "/profiling_summary.elg:120"), h.device.wholeFetches,
                "one C8 per fetch-list member (120 s); the other file and the subdirectory are not fetched");
        assertEquals(List.of("cp_syslog_collect_stat", "cp_syslog_collect_ls_crash", "cp_syslog_collect_ls_spike",
                "cp_syslog_collect_ls_spike_dir", "cp_syslog_collect_fetch", "cp_syslog_collect_fetch"),
                h.gateIds.stream().filter(g -> !g.equals(CpSyslogRangedRead.GATE_ID)).toList(), "every step ledgered in order");
        assertEquals("COLLECTED", h.repo.outcome("cp_spike_data"));
        var detail = h.repo.detail("cp_spike_data");
        assertEquals(2, detail.get("new_directories"));
        assertEquals(1, detail.get("new_unsettled"));
        assertEquals(1, detail.get("collected_directories"));
        assertEquals(2, detail.get("fetched_files"));
        assertEquals(2, detail.get("skipped_members"));
        assertEquals(perf.length + profiling.length, ((Number) detail.get("stored_bytes")).intValue());

        assertEquals(1, h.repo.spikeIn(SpikeDirectoryState.COLLECTED));
        var row = h.repo.spike.values().stream().filter(r -> r.state() == SpikeDirectoryState.COLLECTED).findFirst().orElseThrow();
        assertEquals(4, row.memberCount());
        assertEquals(2, row.fetchedFiles());
        assertEquals(perf.length + profiling.length, row.fetchedBytes());
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Number>> members = JSON.readValue(row.membersJson(), Map.class);
        assertEquals(java.util.Set.of("file:perf_X_NNN.log", "file:profiling_summary.elg", "file:X_XNN.txt", "dir:X"), members.keySet());
        assertEquals(1, members.get("file:perf_X_NNN.log").get("fetched").intValue());
        assertEquals(0, members.get("file:X_XNN.txt").get("fetched").intValue(), "listed, not fetched");

        var file = h.repo.files.stream().filter(f -> f.kind().equals("cp_spike_data")).findFirst().orElseThrow();
        assertEquals(row.fileId(), file.fileId());
        assertEquals(row.nameHmac(), file.remoteNameHmac(), "the archive and the directory share one name identity");
        assertEquals(dirs.get(settled), file.mtime());
        assertEquals(h.now.plus(Duration.ofDays(30)), file.expiresAt(), "retention 30 days (section 2.1)");
        assertTrue(file.contentHmac().matches("[0-9a-f]{64}"));
        Map<String, byte[]> archive = archive(h, file);
        assertEquals(List.of(CpSpikeData.MANIFEST, "perf_fwtango_123.log", "profiling_summary.elg"), List.copyOf(archive.keySet()));
        assertArrayEquals(perf, archive.get("perf_fwtango_123.log"), "stored as received (section 12: not redacted)");
        assertArrayEquals(profiling, archive.get("profiling_summary.elg"));
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = JSON.readValue(archive.get(CpSpikeData.MANIFEST), Map.class);
        assertEquals(settled, manifest.get("directory"), "the clear name lives only inside the encrypted archive");
        assertEquals(4, ((List<?>) manifest.get("members")).size());
        assertEquals(CpSpikeData.sha256(perf), ((Map<?, ?>) ((List<?>) manifest.get("members")).get(0)).get("sha256"));
        h.assertNoDeviceTextInRecords();

        // Ten minutes later the young directory has settled: it is collected; the collected one is not listed again.
        h.at(h.now.plus(Duration.ofMinutes(10)));
        h.run();
        assertEquals(List.of(CpSpikeLayoutReview.LS_SPIKE_COMMAND, CpSpikeLayoutReview.LS_SPIKE_DIR_TEMPLATE.replace("<dir>", fresh)),
                c4AndC5(h));
        assertEquals("COLLECTED", h.repo.outcome("cp_spike_data"));
        assertEquals(2, h.repo.spikeIn(SpikeDirectoryState.COLLECTED));
    }

    @Test
    void aDirectoryNameOutsideTheSafeFormGetsNoC5AndIsCountedAsRejected(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        spikeOn(h);
        h.device.spikeListing = spikeParent(Map.of());
        h.run();                                          // baseline of an empty parent
        String unsafe = "fwtango04 x;touch q";
        h.device.spikeListing = spikeParent(Map.of(unsafe, h.device.epoch - 3600));
        h.run();
        assertEquals(List.of(CpSpikeLayoutReview.LS_SPIKE_COMMAND), c4AndC5(h), "no C5 carries an unsafe name");
        assertTrue(h.device.spikeDirectoriesListed.isEmpty());
        assertEquals(1, h.repo.spikeIn(SpikeDirectoryState.NAME_REJECTED));
        assertEquals("NO_NEW_DATA", h.repo.outcome("cp_spike_data"));
        assertEquals(1, h.repo.detail("cp_spike_data").get("name_rejected"));
        h.run();
        assertEquals(0, h.repo.detail("cp_spike_data").get("new_directories"), "a rejected name is decided once");
        h.assertNoDeviceTextInRecords();
    }

    @Test
    void moreThanSixteenNewSettledDirectoriesLeaveABacklogWithTheExactCount(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        spikeOn(h);
        h.device.spikeListing = spikeParent(Map.of());
        h.run();
        Map<String, Long> dirs = new LinkedHashMap<>();
        for (int i = 1; i <= 18; i++) {
            dirs.put(spikeName(i), h.device.epoch - 7200 + i);
            spikeDirectory(h, spikeName(i), "perf".getBytes(StandardCharsets.US_ASCII), "p".getBytes(StandardCharsets.US_ASCII));
        }
        h.device.spikeListing = spikeParent(dirs);
        h.run();
        assertEquals("COMPLETED", h.finalState, "BACKLOG is not a failure");
        assertEquals(16, h.device.spikeDirectoriesListed.size(), "at most 16 C5 per run");
        assertEquals(List.copyOf(dirs.keySet()).subList(0, 16), h.device.spikeDirectoriesListed, "oldest first");
        assertEquals("BACKLOG", h.repo.outcome("cp_spike_data"));
        assertEquals(2, h.repo.detail("cp_spike_data").get("pending_directories"));
        assertEquals("DIRECTORY_LIMIT", h.repo.detail("cp_spike_data").get("backlog_reason"));
        var gap = h.repo.gap("cp_spike_data", "BACKLOG");
        assertTrue(gap.detailJson().contains("\"pending_directories\":2"));
        assertEquals(16, h.repo.spikeIn(SpikeDirectoryState.COLLECTED));

        h.run();
        assertEquals(List.copyOf(dirs.keySet()).subList(16, 18), h.device.spikeDirectoriesListed, "the next run continues");
        assertEquals("COLLECTED", h.repo.outcome("cp_spike_data"));
        assertEquals(18, h.repo.spikeIn(SpikeDirectoryState.COLLECTED));
    }

    @Test
    void spikeBytesCountAgainstTheDeviceBudgetAndWhatDoesNotFitIsBacklog(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");          // SMALL: 2,048 B per device and run
        spikeOn(h);
        h.device.spikeListing = spikeParent(Map.of());
        h.run();
        String name = spikeName(9);
        h.device.spikeListing = spikeParent(Map.of(name, h.device.epoch - 3600));
        spikeDirectory(h, name, new byte[4096], "p".getBytes(StandardCharsets.US_ASCII));
        h.run();
        assertEquals("BACKLOG", h.repo.outcome("cp_spike_data"));
        assertEquals("DEVICE_BUDGET", h.repo.detail("cp_spike_data").get("backlog_reason"));
        assertEquals(1, h.repo.detail("cp_spike_data").get("pending_directories"));
        assertTrue(h.device.wholeFetches.isEmpty(), "nothing is fetched beyond the budget");
        assertTrue(h.repo.spike.values().stream().noneMatch(r -> r.state() != SpikeDirectoryState.BASELINE), "stays new");
    }

    @Test
    void aDirectoryWhoseFetchListExceedsSixteenMebibytesIsTooLargeAndNotFetched(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        spikeOn(h);
        h.device.spikeListing = spikeParent(Map.of());
        h.run();
        String name = spikeName(4);
        h.device.spikeListing = spikeParent(Map.of(name, h.device.epoch - 3600));
        h.device.spikeDirectories.put(name, "total 1\n-rw-r--r-- 1 " + (16L * 1024 * 1024 + 1) + " " + (h.device.epoch - 3600)
                + " perf_fwtango_7.log\n");
        h.run();
        assertEquals("COMPLETED", h.finalState, "TOO_LARGE is reported, not a failure");
        assertEquals("TOO_LARGE", h.repo.outcome("cp_spike_data"));
        assertTrue(h.device.wholeFetches.isEmpty(), "nothing is fetched");
        assertEquals(1, h.repo.spikeIn(SpikeDirectoryState.TOO_LARGE));
        assertTrue(h.repo.files.isEmpty());
        h.run();
        assertTrue(h.device.spikeDirectoriesListed.isEmpty(), "decided once: no second C5");
    }

    @Test
    void aMemberThatGrewPastTheBoundWhileFetchedMakesTheDirectoryTooLargeAndStoresNothing(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        spikeOn(h);
        h.device.spikeListing = spikeParent(Map.of());
        h.run();
        String name = spikeName(5);
        h.device.spikeListing = spikeParent(Map.of(name, h.device.epoch - 3600));
        spikeDirectory(h, name, "small".getBytes(StandardCharsets.US_ASCII), "p".getBytes(StandardCharsets.US_ASCII));
        h.device.wholeFiles.put(SPIKE + "/" + name + "/perf_fwtango_123.log", new byte[16 * 1024 * 1024 + 1]);
        h.run();
        assertEquals("TOO_LARGE", h.repo.outcome("cp_spike_data"));
        assertEquals(1, h.repo.spikeIn(SpikeDirectoryState.TOO_LARGE));
        assertTrue(h.repo.files.isEmpty());
    }

    /** PO 2026-10-21 (option A): the C4 bound is 1 MiB, as the amended gate row says (was 256 KiB). */
    @Test
    void theListingBoundIsOneMebibyteAsTheAmendedGateRowSays() {
        assertEquals(1_048_576, CpSpikeLayoutReview.LS_SPIKE_MAX_BYTES);
        assertEquals(65_536, CpSpikeLayoutReview.LS_SPIKE_DIR_MAX_BYTES, "C5 keeps its 64 KiB bound");
    }

    @Test
    void aListingOneByteBelowTheOneMebibyteBoundIsProcessedInFull(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        spikeOn(h);
        int bound = CpSpikeLayoutReview.LS_SPIKE_MAX_BYTES;
        String stamp = " " + (h.device.epoch - 86_400) + " ";
        String prefix = "drwxr-xr-x 2 4096";
        int fixed = prefix.length() + stamp.length() + 1;   // everything on a line except the name
        StringBuilder out = new StringBuilder();
        int count = 0;
        // 100-character names (far beyond the 256 KiB the old bound allowed in total) until two last lines close the gap.
        while ((bound - 1) - out.length() > 2 * (fixed + 128)) out.append(spikeLine(prefix, stamp, count++, 100));
        int rest = (bound - 1) - out.length() - 2 * fixed;
        int first = rest / 2;
        out.append(spikeLine(prefix, stamp, count++, first)).append(spikeLine(prefix, stamp, count++, rest - first));
        assertEquals(bound - 1, out.toString().getBytes(StandardCharsets.UTF_8).length);
        assertTrue(out.length() > 256 * 1024, "this listing would have been UNKNOWN under the old bound");
        h.device.spikeListing = out.toString();
        h.run();

        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertEquals("NO_NEW_DATA", h.repo.outcome("cp_spike_data"));
        assertEquals(true, h.repo.detail("cp_spike_data").get("baseline"));
        assertEquals(count, h.repo.detail("cp_spike_data").get("baseline_directories"));
        assertEquals(count, h.repo.spikeIn(SpikeDirectoryState.BASELINE), "processed in full, as a baseline");
        assertNotEquals("LISTING_AT_BOUND", h.repo.detail("cp_spike_data").get("reason"));
    }

    private static String spikeLine(String prefix, String stamp, int n, int nameLength) {
        String head = String.format("d%05d", n);
        return prefix + stamp + head + "x".repeat(nameLength - head.length()) + "\n";
    }

    @Test
    void aListingAtItsBoundOrOverItIsUnknownAndNoDirectoryIsListed(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        spikeOn(h);
        StringBuilder big = new StringBuilder("total 9\n");
        int i = 0;
        while (big.length() < CpSpikeLayoutReview.LS_SPIKE_MAX_BYTES)
            big.append("drwxr-xr-x 2 4096 ").append(h.device.epoch - 86_400).append(' ').append(spikeName(i++)).append('\n');
        h.device.spikeListing = big.substring(0, CpSpikeLayoutReview.LS_SPIKE_MAX_BYTES);
        h.run();
        assertEquals("UNKNOWN", h.repo.outcome("cp_spike_data"));
        assertEquals("LISTING_AT_BOUND", h.repo.detail("cp_spike_data").get("reason"));
        assertTrue(h.repo.spike.isEmpty(), "never processed partially, not even as a baseline");
        assertEquals("FAILED", h.finalState);
        assertEquals("partial:LISTING_AT_BOUND", h.finalReason);

        h.device.spikeListing = null;
        h.device.spikeListingResult = new ExecResult.ChannelFailed("OUTPUT_LIMIT_EXCEEDED");
        h.run();
        assertEquals("UNKNOWN", h.repo.outcome("cp_spike_data"));
        assertEquals("LISTING_OUTPUT_LIMIT_EXCEEDED", h.repo.detail("cp_spike_data").get("reason"));
        assertTrue(h.repo.spike.isEmpty());
        h.assertNoDeviceTextInRecords();
    }

    @Test
    void aMissingParentIsAbsentAndDoesNotTakeTheBaseline(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        spikeOn(h);
        h.device.spikeListing = "";
        h.device.spikeExit = 2;
        h.run();
        assertEquals("COMPLETED", h.finalState, h.finalReason);
        assertEquals("ABSENT", h.repo.outcome("cp_spike_data"));
        h.device.spikeListing = spikeParent(Map.of(spikeName(1), h.device.epoch - 3600));
        h.device.spikeExit = 0;
        h.run();
        assertEquals(1, h.repo.spikeIn(SpikeDirectoryState.BASELINE), "the first present listing is the baseline");
        assertTrue(h.device.spikeDirectoriesListed.isEmpty());
    }

    @Test
    void aFailedDirectoryListingIsUnknownAndTheDirectoryStaysNewForTheNextRun(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        spikeOn(h);
        h.device.spikeListing = spikeParent(Map.of());
        h.run();
        String name = spikeName(6);
        h.device.spikeListing = spikeParent(Map.of(name, h.device.epoch - 3600));
        h.device.spikeDirectories.put(name, "ls: cannot open directory '/var/log/spike_detective/x/': Permission denied\n");
        h.run();
        assertEquals("UNKNOWN", h.repo.outcome("cp_spike_data"));
        assertEquals("DIRECTORY_PERMISSION_DENIED", h.repo.detail("cp_spike_data").get("reason"));
        assertTrue(h.repo.spike.isEmpty(), "not decided: retried next run");
        spikeDirectory(h, name, "perf".getBytes(StandardCharsets.US_ASCII), "p".getBytes(StandardCharsets.US_ASCII));
        h.run();
        assertEquals("COLLECTED", h.repo.outcome("cp_spike_data"));
    }

    @Test
    void aWithdrawnDirectoryGateStopsTheSourceBeforeC4AndTheOtherSourcesStillRun(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway");
        spikeOn(h);
        h.device.spikeListing = spikeParent(Map.of());
        h.rows = fixture().stream().filter(r -> !r.gateId().equals("cp_syslog_collect_ls_spike_dir")).toList();
        h.run();
        assertEquals("UNKNOWN", h.repo.outcome("cp_spike_data"));
        assertEquals("GATE_NOT_KNOWN", h.repo.detail("cp_spike_data").get("reason"));
        assertTrue(c4AndC5(h).isEmpty());
        assertTrue(h.repo.runs.containsKey("cp_crash_listing"));
    }

    @Test
    void theSpikeCommandsAreByteIdenticalToTheirGateRows() {
        var rows = fixture();
        var c4 = rows.stream().filter(r -> r.gateId().equals(CpSpikeLayoutReview.LS_SPIKE_GATE)).findFirst().orElseThrow();
        var c5 = rows.stream().filter(r -> r.gateId().equals(CpSpikeLayoutReview.LS_SPIKE_DIR_GATE)).findFirst().orElseThrow();
        assertEquals(c4.canonicalCommandKey(), CpSpikeLayoutReview.LS_SPIKE_COMMAND);
        assertEquals(c5.canonicalCommandKey(), CpSpikeLayoutReview.LS_SPIKE_DIR_TEMPLATE);
        assertEquals(30, c4.timeoutS());
        assertEquals(30, c5.timeoutS());
        assertTrue(CpSystemLogCollect.spikeMemberFetchable("perf_cpu_7.log"));
        assertTrue(CpSystemLogCollect.spikeMemberFetchable("perf_fwtango_123.log"));
        assertFalse(CpSystemLogCollect.spikeMemberFetchable("perf_fw2_123.log"), "the middle part is one lowercase word");
        assertTrue(CpSystemLogCollect.spikeMemberFetchable("profiling_summary.elg"));
        assertFalse(CpSystemLogCollect.spikeMemberFetchable("perf_X_1.log"), "the amended form: a lowercase word");
        assertFalse(CpSystemLogCollect.spikeMemberFetchable("perf_cpu_7.log.1"));
        assertFalse(CpSystemLogCollect.spikeMemberFetchable("../perf_cpu_7.log"));
        assertFalse(CpSystemLogCollect.spikeMemberFetchable("profiling_summary.elg.gz"));
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
