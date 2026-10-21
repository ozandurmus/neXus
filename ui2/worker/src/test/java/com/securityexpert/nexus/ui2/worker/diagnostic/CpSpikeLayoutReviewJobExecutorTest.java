package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSpikeLayoutReview;
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
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogMeasurementRepository;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;

/**
 * The {@code cp_spike_layout_review} executor against a scripted transport: no socket, host or device contact. Every
 * device value is synthetic (FW-TANGO-04, 192.0.2.x, synthetic directory and file names). It proves that C4 is sent
 * exactly as approved, that C5 is sent at most once and only with a name C4 printed, that nothing else is sent, and that
 * the stored summary holds no name, path, host or line.
 */
class CpSpikeLayoutReviewJobExecutorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-19T09:10:00Z");   // 12:10 Europe/Istanbul
    private static final long T = NOW.getEpochSecond();
    private static final String BANNER = "This system is for authorized use only. FW-TANGO-04";
    private static final String NEWEST = "sd_20261019_090501";
    private static final String MIDDLE = "sd_20261019_080001";
    private static final String OLDEST = "sd_20261012_230001";
    /** Synthetic names and identities: none may reach the stored summary or a job reason. */
    private static final List<String> RAW = List.of("FW-TANGO-04", "192.0.2.", "/var/log", "spike_detective/",
            "sd_2026", "perf_cpu_0.log", "perf_cpu_12.log", "extra_notes", "authorized use", "spike_detective.log");

    static String entry(char type, int links, long size, long mtime, String name) {
        String mode = type == 'd' ? "drwxr-xr-x" : type == 'l' ? "lrwxrwxrwx" : "-rw-r--r--";
        return mode + " " + links + " " + size + " " + mtime + " " + name + "\n";
    }

    /** The measured parent shape: data directories (link count 2) and the log/csv plain files (link count 1). */
    static String parent(boolean banner) {
        return (banner ? BANNER + "\n" : "") + "total 1480\n"
                + entry('d', 2, 57, T - 3 * 86_400, OLDEST)
                + entry('d', 2, 114, T - 2 * 3_600, MIDDLE)
                + entry('d', 2, 57, T - 1_200, NEWEST)
                + entry('-', 1, 17_770, T - 30, "spike_detective.log")
                + entry('-', 1, 60_099, T - 30, "spike_detective.csv");
    }

    static String members(boolean banner) {
        return (banner ? BANNER + "\n" : "") + "total 2048\n"
                + entry('-', 1, 400_000, T - 1_300, "perf_cpu_0.log")
                + entry('-', 1, 700_000, T - 1_300, "perf_cpu_12.log")
                + entry('-', 1, 9_000, T - 1_250, "profiling_summary.elg")
                + entry('-', 1, 12, T - 1_250, "extra_notes.txt");
    }

    // ------------------------------------------------------------------ the scripted device

    static final class Device implements DeviceTransport {
        final List<String> commands = new ArrayList<>();
        final List<Integer> limits = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();
        Function<String, ExecResult> answer = command -> { throw new AssertionError("no answer scripted"); };
        boolean connectFails;
        int connects, disconnects;

        @Override public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
            connects++;
            return connectFails ? new ConnectResult.AuthenticationFailed("synthetic") : new ConnectResult.Authenticated(() -> "s1");
        }

        @Override public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
            commands.add(spec.command());
            limits.add(spec.outputLimitBytes());
            timeouts.add(timeout);
            return answer.apply(spec.command());
        }

        @Override public ExecResult execInteractive(TransportSession session, ExecSpec spec, Duration timeout) {
            throw new AssertionError("interactive");
        }

        @Override public RangedFetchResult fetchRange(TransportSession s, RangedFetchSpec spec, Duration t, OutputStream sink) {
            throw new AssertionError("no file is read");
        }

        @Override public RangedFetchResult fetchBounded(TransportSession s, RangedFetchSpec spec, Duration t, OutputStream sink,
                String nameClass) {
            throw new AssertionError("no file is fetched");
        }

        @Override public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) { throw new AssertionError("fetch"); }
        @Override public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) { throw new AssertionError("xml"); }
        @Override public void disconnect(TransportSession session) { disconnects++; }
    }

    static final class Measurements implements SystemLogMeasurementRepository {
        final List<String[]> rows = new ArrayList<>();
        boolean fence = true;

        @Override public boolean record(String jobId, long epoch, String deviceId, String vendor, String source, String summary) {
            if (!fence) return false;
            rows.add(new String[] {vendor, source, summary});
            return true;
        }

        @Override public List<Measurement> list(Optional<String> d, Optional<String> v, Optional<String> s, int limit) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ the harness

    static final class Harness {
        final JobLeaseRepository leases = mock(JobLeaseRepository.class);
        final JobStepAttemptRepository attempts = mock(JobStepAttemptRepository.class);
        final DeviceRepository devices = mock(DeviceRepository.class);
        final Device device = new Device();
        final Measurements measurements = new Measurements();
        final List<String> gateIds = new ArrayList<>();
        List<GateRow> rows = fixture();
        DevicePlatformFactsRepository facts = DevicePlatformFactsRepository.NONE;
        String finalState, finalReason;

        Harness() { this("gateway"); }

        Harness(String role) {
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
        }

        /** C4 answers {@code parent}; a C5 for any directory answers {@code members}. */
        Harness script(String parent, String members) {
            device.answer = command -> command.equals(CpSpikeLayoutReview.LS_SPIKE_COMMAND)
                    ? new ExecResult.Completed(parent, 0)
                    : new ExecResult.Completed(members, 0);
            return this;
        }

        void run() {
            GateRegistryPort gates = key -> rows.stream().filter(r -> r.key().equals(key)).toList();
            new CpSpikeLayoutReviewJobExecutor(leases, attempts, devices, device, gates, measurements,
                    HostnameFingerprint.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])), facts,
                    Clock.fixed(NOW, ZoneOffset.UTC))
                    .execute("job-1", 1, "device-1", "192.0.2.1", 22, "credential-ref");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> summary() throws Exception {
            assertEquals(1, measurements.rows.size(), "exactly one summary row per job");
            assertEquals("check_point", measurements.rows.get(0)[0]);
            assertEquals(CpSpikeLayoutReview.SOURCE, measurements.rows.get(0)[1]);
            String text = measurements.rows.get(0)[2];
            for (String raw : RAW) assertFalse(text.contains(raw), "the summary holds no name, path, host or line: " + raw);
            for (String key : List.of("entry", "logs", "lines", "messages", "opaque", "raw"))
                assertFalse(JSON.readValue(text, Map.class).containsKey(key), "V151 refuses the raw-body key " + key);
            return JSON.readValue(text, Map.class);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> part(String name) throws Exception { return (Map<String, Object>) summary().get(name); }

        void assertReasonClean() {
            if (finalReason != null) for (String raw : RAW) assertFalse(finalReason.contains(raw), raw);
        }
    }

    private static List<GateRow> fixture() {
        return GateRegistryFixtureLoader.loadFromStream(CpSpikeLayoutReviewJobExecutorTest.class.getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml")).stream().toList();
    }

    private static String c5(String name) {
        return "bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/" + name + "/'";
    }

    // ------------------------------------------------------------------ tests

    @Test
    void theNewestSettledDirectoryIsListedOnceAndOnlyItsSummaryIsStored() throws Exception {
        var h = new Harness().script(parent(true), members(true));
        h.run();
        assertEquals(List.of(CpSpikeLayoutReview.LS_SPIKE_COMMAND, c5(NEWEST)), h.device.commands,
                "C4 exactly as approved, then ONE C5 with the name C4 printed; nothing else");
        assertEquals(List.of(CpSpikeLayoutReview.LS_SPIKE_GATE, CpSpikeLayoutReview.LS_SPIKE_DIR_GATE), h.gateIds,
                "both reads are ledgered under their own V160 gate");
        assertEquals(List.of(1024 * 1024, 64 * 1024), h.device.limits, "bounded, transcript-free execs (section 10.1 max bytes)");
        assertEquals(List.of(Duration.ofSeconds(30), Duration.ofSeconds(30)), h.device.timeouts, "the gate rows' timeouts");
        assertEquals(1, h.device.connects);
        assertEquals(1, h.device.disconnects);
        assertEquals("COMPLETED", h.finalState);
        assertEquals("SPIKE_LAYOUT_RECORDED", h.finalReason);

        Map<String, Object> summary = h.summary();
        assertEquals("MEASURED", summary.get("status"));
        assertEquals("REVIEWED", summary.get("outcome"));
        assertEquals("NEXUS_CLOCK", summary.get("ageBasis"));
        assertEquals(2, summary.get("commandsSent"));
        Map<String, Object> parent = h.part("parent");
        assertEquals(3, parent.get("dataDirectories"));
        assertEquals(2, parent.get("plainFiles"));
        assertEquals(2, parent.get("plainFilesLinkCountOne"));
        assertEquals(0, parent.get("dataDirectoriesUnsettled"));
        assertEquals(1, parent.get("ignoredLines"), "the banner is counted, never parsed");
        Map<String, Object> directory = h.part("directory");
        assertEquals("X_YYYYMMDD_NNN", directory.get("nameClass"));
        assertEquals("10M_TO_1H", directory.get("ageClass"));
        assertEquals(0, directory.get("unsettledNewerSkipped"));
        assertEquals(4, directory.get("memberCount"));
        assertEquals(4, directory.get("files"));
        assertEquals(1_109_012, ((Number) directory.get("fileBytes")).intValue());
        assertEquals(4, ((Map<?, ?>) directory.get("nameClasses")).size());
    }

    @Test
    void digitRunsOfDifferentLengthAreSeparateClassesAndTheContractFormsAreCounted() throws Exception {
        var h = new Harness().script(parent(false), members(false));
        h.run();
        Map<String, Object> directory = h.part("directory");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> classes = (Map<String, Map<String, Object>>) directory.get("nameClasses");
        assertEquals(java.util.Set.of("perf_cpu_N.log", "perf_cpu_NN.log", "profiling_summary.elg", "X_X.txt"), classes.keySet());
        assertEquals(Map.of("count", 1, "minBytes", 400_000, "maxBytes", 400_000, "bytes", 400_000), classes.get("perf_cpu_N.log"));
        assertEquals(Map.of("count", 1, "minBytes", 700_000, "maxBytes", 700_000, "bytes", 700_000), classes.get("perf_cpu_NN.log"));
        assertEquals(Map.of("perfCpuLog", 2, "profilingSummaryElg", 1, "otherMembers", 1), directory.get("contractForms"));
        assertEquals(1_109_000, ((Number) directory.get("contractFormBytes")).intValue());
        assertEquals(true, directory.get("contractFormBytesWithinBound"));
        assertEquals(0, h.part("parent").get("ignoredLines"), "no banner on this device");
        assertEquals(List.of(CpSpikeLayoutReview.LS_SPIKE_COMMAND, c5(NEWEST)), h.device.commands);
    }

    @Test
    void anUnsettledNewestDirectoryIsSkippedForTheNextNewest() throws Exception {
        String listing = parent(true) + entry('d', 2, 57, T - 120, "sd_20261019_090801");
        var h = new Harness().script(listing, members(true));
        h.run();
        assertEquals(List.of(CpSpikeLayoutReview.LS_SPIKE_COMMAND, c5(NEWEST)), h.device.commands);
        assertEquals(1, h.part("parent").get("dataDirectoriesUnsettled"));
        assertEquals(1, h.part("directory").get("unsettledNewerSkipped"));
        assertEquals("REVIEWED", h.summary().get("outcome"));
    }

    @Test
    void aNameOutsideTheSafeFormIsNeverListedAndIsRecordedNameRejected() throws Exception {
        for (String unsafe : List.of("sd 2026 x", "sd_2026'x", "sd_2026;x", "sd_2026$x", "x".repeat(129))) {
            String listing = "total 8\n" + entry('d', 2, 57, T - 3_600, unsafe) + entry('d', 2, 57, T - 7_200, MIDDLE);
            var h = new Harness().script(listing, members(false));
            h.run();
            assertEquals(List.of(CpSpikeLayoutReview.LS_SPIKE_COMMAND), h.device.commands, "no C5 for an unsafe name");
            Map<String, Object> summary = h.summary();
            assertEquals("MEASURED", summary.get("status"));
            assertEquals("NAME_REJECTED", summary.get("outcome"));
            assertEquals(1, summary.get("commandsSent"));
            assertFalse(h.part("directory").containsKey("memberCount"));
            assertEquals("COMPLETED", h.finalState);
            assertFalse(h.measurements.rows.get(0)[2].contains(unsafe.length() > 10 ? unsafe.substring(0, 10) : unsafe));
        }
    }

    @Test
    void anEmptyDirectoryIsReviewedWithNoMembers() throws Exception {
        var h = new Harness().script(parent(true), BANNER + "\ntotal 0\n");
        h.run();
        assertEquals(2, h.device.commands.size());
        Map<String, Object> directory = h.part("directory");
        assertEquals("PRESENT", directory.get("state"));
        assertEquals(0, directory.get("memberCount"));
        assertEquals(Map.of(), directory.get("nameClasses"));
        assertEquals("REVIEWED", h.summary().get("outcome"));
        assertEquals("COMPLETED", h.finalState);
    }

    @Test
    void aMissingParentDirectoryIsAbsentAndNothingElseIsSent() throws Exception {
        for (String missing : List.of("", BANNER + "\n",
                "ls: cannot access '/var/log/spike_detective/': No such file or directory\n")) {
            var h = new Harness();
            h.device.answer = command -> new ExecResult.Completed(missing, 2);
            h.run();
            assertEquals(List.of(CpSpikeLayoutReview.LS_SPIKE_COMMAND), h.device.commands);
            Map<String, Object> summary = h.summary();
            assertEquals("MEASURED", summary.get("status"));
            assertEquals("ABSENT", summary.get("outcome"));
            assertEquals("ABSENT", h.part("parent").get("state"));
            assertEquals("COMPLETED", h.finalState);
        }
    }

    @Test
    void aDirectoryGoneBeforeItsListingIsDirectoryAbsent() throws Exception {
        var h = new Harness();
        h.device.answer = command -> command.equals(CpSpikeLayoutReview.LS_SPIKE_COMMAND)
                ? new ExecResult.Completed(parent(false), 0)
                : new ExecResult.Completed("ls: cannot access '/var/log/spike_detective/" + NEWEST
                        + "/': No such file or directory\n", 2);
        h.run();
        assertEquals(List.of(CpSpikeLayoutReview.LS_SPIKE_COMMAND, c5(NEWEST)), h.device.commands);
        assertEquals("DIRECTORY_ABSENT", h.summary().get("outcome"));
        assertEquals("ABSENT", h.part("directory").get("state"));
    }

    @Test
    void onlyTypeDLinkCountTwoEntriesAreDataDirectories() throws Exception {
        String listing = "total 12\n"
                + entry('d', 3, 4096, T - 3_600, "nested_dir")          // a directory with sub-directories: not a data directory
                + entry('-', 2, 100, T - 3_600, "hardlinked_file")      // link count 2 but a plain file: never listed
                + entry('l', 1, 10, T - 3_600, "a_link -> elsewhere")
                + entry('-', 1, 100, T - 30, "spike_detective.log");
        var h = new Harness().script(listing, members(false));
        h.run();
        assertEquals(List.of(CpSpikeLayoutReview.LS_SPIKE_COMMAND), h.device.commands);
        assertEquals("NO_DATA_DIRECTORY", h.summary().get("outcome"));
        Map<String, Object> parent = h.part("parent");
        assertEquals(0, parent.get("dataDirectories"));
        assertEquals(1, parent.get("otherDirectories"));
        assertEquals(1, parent.get("linkCountTwoNotDirectory"));
        assertEquals(1, parent.get("otherEntries"));
        assertEquals(2, parent.get("plainFiles"));
    }

    @Test
    void onlyUnsettledDirectoriesMeanNoListing() throws Exception {
        String listing = "total 8\n" + entry('d', 2, 57, T - 60, NEWEST) + entry('d', 2, 57, T + 30, MIDDLE);
        var h = new Harness().script(listing, members(false));
        h.run();
        assertEquals(List.of(CpSpikeLayoutReview.LS_SPIKE_COMMAND), h.device.commands);
        assertEquals("NO_SETTLED_DIRECTORY", h.summary().get("outcome"));
        assertEquals(2, h.part("parent").get("dataDirectoriesUnsettled"));
        assertEquals(1, h.part("parent").get("dataDirectoriesMtimeAheadOfClock"));
    }

    @Test
    void aFailedParentListingIsUnknownAndNoDirectoryIsListed() throws Exception {
        for (ExecResult failed : List.of(new ExecResult.TimedOut(), new ExecResult.ChannelFailed("OUTPUT_LIMIT_EXCEEDED"),
                new ExecResult.ChannelFailed("synthetic"), new ExecResult.Completed("x".repeat(1024 * 1024 + 1), 0))) {
            var h = new Harness();
            h.device.answer = command -> failed;
            h.run();
            assertEquals(List.of(CpSpikeLayoutReview.LS_SPIKE_COMMAND), h.device.commands, "no retry, no C5");
            Map<String, Object> summary = h.summary();
            assertEquals("UNKNOWN", summary.get("status"));
            assertTrue(((String) summary.get("reason")).startsWith("PARENT_"));
            assertEquals("FAILED", h.finalState);
            h.assertReasonClean();
        }
    }

    @Test
    void aFailedDirectoryListingIsUnknownWithTheParentCountsKept() throws Exception {
        var h = new Harness();
        h.device.answer = command -> command.equals(CpSpikeLayoutReview.LS_SPIKE_COMMAND)
                ? new ExecResult.Completed(parent(true), 0) : new ExecResult.TimedOut();
        h.run();
        assertEquals(2, h.device.commands.size(), "C5 is sent once and never retried");
        Map<String, Object> summary = h.summary();
        assertEquals("UNKNOWN", summary.get("status"));
        assertEquals("DIRECTORY_TIMEOUT", summary.get("reason"));
        assertEquals(3, h.part("parent").get("dataDirectories"));
        assertEquals("FAILED", h.finalState);
    }

    @Test
    void aConnectionFailureIsRecordedUnknownWithNoCommand() throws Exception {
        var h = new Harness();
        h.device.connectFails = true;
        h.run();
        assertTrue(h.device.commands.isEmpty());
        assertEquals("CONNECTION_FAILED", h.summary().get("reason"));
        assertEquals("FAILED", h.finalState);
    }

    @Test
    void withoutBothSignedOffGatesOrOnTheWrongDeviceNothingIsContacted() {
        for (String missingGate : List.of(CpSpikeLayoutReview.LS_SPIKE_GATE, CpSpikeLayoutReview.LS_SPIKE_DIR_GATE)) {
            var h = new Harness().script(parent(true), members(true));
            h.rows = fixture().stream().filter(r -> !r.gateId().equals(missingGate)).toList();
            h.run();
            assertEquals("REJECTED", h.finalState);
            assertEquals("SPIKE_REVIEW_UNAVAILABLE", h.finalReason);
            assertEquals(0, h.device.connects);
            assertTrue(h.measurements.rows.isEmpty());
        }
        var measurementOnly = new Harness().script(parent(true), members(true));
        measurementOnly.rows = fixture().stream().filter(r -> !r.gateId().startsWith("cp_syslog_collect_")).toList();
        measurementOnly.run();
        assertEquals("REJECTED", measurementOnly.finalState, "the V157 measurement row never authorizes the review");

        var management = new Harness("management_server").script(parent(true), members(true));
        management.run();
        assertEquals("SYSTEM_LOG_DISABLED_ON_MANAGEMENT", management.finalReason);
        assertEquals(0, management.device.connects);

        var embedded = new Harness().script(parent(true), members(true));
        var facts = new DevicePlatformFacts("device-1", Optional.empty(), Optional.empty(), Optional.of("gaia_embedded"),
                Map.of(), Optional.empty(), "synthetic", Optional.empty());
        embedded.facts = new DevicePlatformFactsRepository() {
            @Override public void record(DevicePlatformFacts f) { }
            @Override public Optional<DevicePlatformFacts> find(String id) { return Optional.of(facts); }
            @Override public Map<String, DevicePlatformFacts> findAll() { return Map.of(); }
        };
        embedded.run();
        assertEquals("SPIKE_REVIEW_UNSUPPORTED_PLATFORM", embedded.finalReason);
        assertEquals(0, embedded.device.connects);
    }

    @Test
    void aRecordedAttemptIsNeverReplayedAndALostFenceStopsTheJob() throws Exception {
        var replay = new Harness().script(parent(true), members(true));
        when(replay.attempts.findByJobAndStep("job-1", 0)).thenReturn(List.of(mock(
                com.securityexpert.nexus.ui2.jobs.stepattempt.StepAttempt.class)));
        replay.run();
        assertTrue(replay.device.commands.isEmpty());
        assertEquals("OUTCOME_UNKNOWN", replay.finalState);
        assertEquals("PRIOR_ATTEMPT_NOT_REPLAYED", replay.finalReason);

        var fenced = new Harness().script(parent(true), members(true));
        fenced.measurements.fence = false;
        fenced.run();
        assertEquals("OUTCOME_UNKNOWN", fenced.finalState);
        assertEquals("MEASUREMENT_NOT_RECORDED", fenced.finalReason);
    }

    @Test
    void theSelectionIsDeterministicAndOnlyEverBuildsACommandFromAPrintedSafeName() {
        var parent = CpSpikeLayoutSummary.parse("total 4\n" + entry('d', 2, 57, T - 3_600, "b_dir")
                + entry('d', 2, 57, T - 3_600, "a_dir"), 0, CpSpikeLayoutSummary.PARENT);
        var selection = CpSpikeLayoutSummary.select(parent, T);
        assertEquals("a_dir", selection.chosen().orElseThrow().name(), "an mtime tie is broken by name");
        assertEquals(Optional.of(c5("a_dir")), selection.directoryCommand());
        assertTrue(CpSpikeLayoutReview.directoryCommand("..").isEmpty());
        assertTrue(CpSpikeLayoutReview.directoryCommand(".").isEmpty());
        assertTrue(CpSpikeLayoutReview.directoryCommand("a/b").isEmpty());
        assertTrue(CpSpikeLayoutReview.directoryCommand("a b").isEmpty());
        assertTrue(CpSpikeLayoutReview.directoryCommand("").isEmpty());
        assertTrue(CpSpikeLayoutReview.directoryCommand(null).isEmpty());
        assertEquals("30D_OR_MORE", CpSpikeLayoutSummary.ageClass(40L * 86_400));
        assertEquals("1D_TO_7D", CpSpikeLayoutSummary.ageClass(3L * 86_400));
    }
}
