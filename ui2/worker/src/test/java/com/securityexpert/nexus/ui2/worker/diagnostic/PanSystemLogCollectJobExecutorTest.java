package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.diagnostic.PanSystemLogCollect;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository.PanCursor;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;

/**
 * Scripted XML API only: no socket, host or device contact. Synthetic values only (FW-TANGO-04, 192.0.2.x). The artefact
 * store is a real encrypting {@link FileArtefactStore} on a temp directory so the stored chunk is read back through it.
 */
class PanSystemLogCollectJobExecutorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter PAN_TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 15, 10, 0, 0);
    /** Values that identify users, addresses, hosts or secrets; none may reach a job reason or a run row. */
    private static final List<String> RAW = List.of("192.0.2.", "FW-TANGO-04", "synthetic-admin", "synthetic-user",
            "SYNTHSERIAL0001", "synthetic-pass", "synthetic-key", "rule-alpha");

    static String ts(int secondsFromBase) {
        return BASE.plusSeconds(secondsFromBase).format(PAN_TIME);
    }

    static String entry(String time, String seqno, String... extra) {
        StringBuilder xml = new StringBuilder("<entry logid=\"7000000000000000001\"><receive_time>").append(time)
                .append("</receive_time><serial>SYNTHSERIAL0001</serial><seqno>").append(seqno)
                .append("</seqno><type>SYSTEM</type><device_name>FW-TANGO-04</device_name><eventid>general</eventid>")
                .append("<opaque>User synthetic-user logged in from 192.0.2.15</opaque>");
        for (int i = 0; i + 1 < extra.length; i += 2) xml.append('<').append(extra[i]).append('>').append(extra[i + 1])
                .append("</").append(extra[i]).append('>');
        return xml.append("</entry>").toString();
    }

    static String fin(List<String> entries) {
        return "<response status=\"success\"><result><job><tenq>2026/10/15 10:15:00</tenq><id>41</id><status>FIN</status></job>"
                + "<log><logs count=\"" + entries.size() + "\" progress=\"100\">" + String.join("", entries)
                + "</logs></log></result></response>";
    }

    static final String ACTIVE = "<response status=\"success\"><result><job><id>41</id><status>ACT</status></job>"
            + "<log><logs count=\"0\" progress=\"20\"/></log></result></response>";
    static final String ENQUEUED = "<response status=\"success\"><result><msg><line>query job enqueued with jobid 41</line></msg>"
            + "<job>41</job></result></response>";

    /** Entries i..j-1 of a long synthetic run, one second apart, seqno = firstSeqno + (i - from). */
    static List<String> run(int from, int to, int seqnoOfFrom) {
        List<String> entries = new ArrayList<>();
        for (int i = from; i < to; i++) entries.add(entry(ts(i), String.valueOf(seqnoOfFrom + i - from)));
        return entries;
    }

    private static final class FakeRepository implements SystemLogCollectionRepository {
        final Map<String, PanCursor> cursors = new HashMap<>();
        final Map<String, SourceWrite> writes = new LinkedHashMap<>();
        boolean accept = true;

        @Override public Optional<PanCursor> findPanCursor(String deviceId, String sourceKey) {
            return Optional.ofNullable(cursors.get(sourceKey));
        }
        @Override public Settings settings() { return Settings.DEFAULTS; }
        @Override public boolean record(String jobId, long leaseEpoch, String deviceId, String sourceKey, SourceWrite write) {
            if (!accept) return false;
            writes.put(sourceKey, write);
            write.cursor().ifPresent(c -> cursors.put(sourceKey, c));
            return true;
        }
    }

    private final class Harness {
        final JobLeaseRepository leases = mock(JobLeaseRepository.class);
        final JobStepAttemptRepository attempts = mock(JobStepAttemptRepository.class);
        final DeviceRepository devices = mock(DeviceRepository.class);
        final DeviceTransport transport = mock(DeviceTransport.class);
        final FakeRepository repository = new FakeRepository();
        final List<XmlApiSpec> sent = new ArrayList<>();
        final List<String> gateIds = new ArrayList<>();
        final List<ArtefactRef> discarded = new ArrayList<>();
        final AtomicLong now = new AtomicLong(Instant.parse("2026-10-17T09:00:00Z").toEpochMilli());
        final AtomicInteger sleeps = new AtomicInteger();
        final Map<String, Deque<String>> pages = new HashMap<>();
        double freeFraction = 0.5;
        int activeBeforeFin = 1;
        Function<XmlApiSpec, XmlApiResult> override = spec -> null;
        String finalState, finalReason;
        String currentType;
        int actCount;
        final Path storeDir;
        final FileArtefactStore store;

        Harness(Path storeDir, String role, String vendor) {
            this.storeDir = storeDir;
            this.store = new FileArtefactStore(storeDir, ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])));
            var device = new DeviceRecord("device-1", role, vendor, "manual", Instant.now(), false,
                    DeviceEnrollmentState.ENROLLED, false, "credential-ref");
            when(devices.find("device-1")).thenReturn(Optional.of(device));
            when(devices.findEndpointByDeviceId("device-1")).thenReturn(Optional.of(
                    new EndpointRecord("endpoint-1", "device-1", "pan_xml_api", "192.0.2.1", Instant.now())));
            when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
            when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString(), any())).thenAnswer(call -> {
                if (call.getArgument(2) == JobState.EXECUTING) {
                    finalState = call.getArgument(3).toString(); finalReason = call.getArgument(6);
                } else if (call.getArgument(3) == JobState.REJECTED) {
                    finalState = "REJECTED"; finalReason = call.getArgument(6);
                }
                return true;
            });
            when(attempts.findByJobAndStep(anyString(), anyInt())).thenReturn(List.of());
            AtomicInteger ids = new AtomicInteger();
            when(attempts.insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt()))
                    .thenAnswer(call -> { gateIds.add(call.getArgument(3)); return "attempt-" + ids.incrementAndGet(); });
            when(attempts.markBoundaryCrossed(anyString(), anyLong())).thenReturn(true);
            when(attempts.writeOutcome(anyString(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), any())).thenReturn(true);
            when(transport.xmlApiCall(any(), any(), any())).thenAnswer(call -> {
                XmlApiSpec spec = call.getArgument(1);
                sent.add(spec);
                if ("keygen".equals(spec.type()))
                    return new XmlApiResult.Completed(200, "<response status=\"success\"><result><key>synthetic-key</key></result></response>");
                XmlApiResult overridden = override.apply(spec);
                if (overridden != null) return overridden;
                if (spec.formParams().containsKey("log-type")) {
                    currentType = spec.formParams().get("log-type");
                    actCount = 0;
                    return new XmlApiResult.Completed(200, ENQUEUED);
                }
                if (actCount++ < activeBeforeFin) return new XmlApiResult.Completed(200, ACTIVE);
                Deque<String> queue = pages.get(currentType);
                return new XmlApiResult.Completed(200, queue == null || queue.isEmpty() ? fin(List.of()) : queue.poll());
            });
        }

        void page(String logType, List<String> entries) {
            pages.computeIfAbsent(logType, k -> new ArrayDeque<>()).add(fin(entries));
        }

        void cursor(String source, String time, String... seqnos) {
            repository.cursors.put(source, new PanCursor(time, List.of(seqnos)));
        }

        void run() { run(GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml")).stream().toList()); }

        void run(List<com.securityexpert.nexus.ui2.capability.GateRow> rows) {
            GateRegistryPort gates = key -> rows.stream().filter(r -> r.key().equals(key)).toList();
            Clock clock = new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
            };
            SystemLogArtefactVolume volume = new SystemLogArtefactVolume() {
                @Override public double freeFraction() { return freeFraction; }
                @Override public void discard(ArtefactRef ref) { discarded.add(ref); }
            };
            new PanSystemLogCollectJobExecutor(leases, attempts, devices, transport,
                    ref -> new PanCredentialMaterial("synthetic-user", "synthetic-pass".toCharArray()), gates, repository, store,
                    volume, HostnameFingerprint.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])),
                    d -> { sleeps.incrementAndGet(); now.addAndGet(d.toMillis()); }, clock)
                    .execute("job-1", 1, "device-1");
        }

        SystemLogCollectionRepository.SourceWrite write(String source) {
            var w = repository.writes.get(source);
            assertNotNull(w, "no run recorded for " + source);
            return w;
        }

        List<XmlApiSpec> logCalls() { return sent.stream().filter(s -> "log".equals(s.type())).toList(); }

        List<XmlApiSpec> enqueues(String logType) {
            return logCalls().stream().filter(s -> logType.equals(s.formParams().get("log-type"))).toList();
        }

        /** The stored chunk, read back through the encrypting store: one JSON object per line. */
        List<Map<String, String>> chunkLines(String source) throws IOException {
            var chunk = write(source).chunk().orElseThrow();
            var manifest = chunk.manifest();
            String text = new String(store.retrieve(new ArtefactRef(manifest.recoveryVolumePath()), manifest.wrappedDataKey(), true)
                    .readAllBytes(), StandardCharsets.UTF_8);
            List<Map<String, String>> lines = new ArrayList<>();
            for (String line : text.split("\n")) {
                @SuppressWarnings("unchecked") Map<String, String> m = JSON.readValue(line, Map.class);
                lines.add(m);
            }
            return lines;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> detail(String source) throws Exception {
            return JSON.readValue(write(source).run().detailJson(), Map.class);
        }

        /** Neither the job reason nor any run row or gap row may carry device text. */
        void assertNoDeviceTextInRecords() {
            List<String> records = new ArrayList<>();
            records.add(String.valueOf(finalReason));
            for (var w : repository.writes.values()) {
                records.add(w.run().detailJson());
                w.gaps().forEach(g -> records.add(g.detailJson()));
            }
            for (String record : records) for (String raw : RAW) assertFalse(record.contains(raw), raw + " leaked into " + record);
        }
    }

    // ---------------------------------------------------------------- first run (seed)

    @Test
    void theFirstRunStoresTheSeedOldestFirstAndSetsTheCursorToItsNewestSecond(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway", "palo_alto");
        // The seed answers newest first; two entries share the newest second.
        h.page("system", List.of(entry(ts(2), "104"), entry(ts(2), "103"), entry(ts(1), "102"), entry(ts(0), "101")));
        h.page("config", List.of(entry(ts(5), "11", "cmd", "set"), entry(ts(4), "10", "cmd", "commit")));
        h.run();

        assertEquals("COMPLETED", h.finalState);
        assertEquals("SYSTEM_LOG_COLLECTED", h.finalReason);
        // P1/P2: the seed shapes only, with the seed sizes of section 6; no dir/query on a seed.
        assertEquals(Map.of("log-type", "system", "nlogs", "2000"), h.logCalls().get(0).formParams());
        assertEquals(Map.of("log-type", "config", "nlogs", "100"), h.enqueues("config").get(0).formParams());
        assertEquals(List.of("pan_syslog_collect_system_seed", "pan_syslog_collect_job_get", "pan_syslog_collect_job_get",
                "pan_syslog_collect_config_seed", "pan_syslog_collect_job_get", "pan_syslog_collect_job_get"), h.gateIds);
        assertEquals("synthetic-key", h.logCalls().get(1).headers().get("X-PAN-KEY"));

        var system = h.write("pan_system");
        assertEquals("COLLECTED", system.run().outcome());
        assertEquals(new PanCursor(ts(2), List.of("103", "104")), system.cursor().orElseThrow());
        assertTrue(system.gaps().isEmpty(), "a seed smaller than its size holds the whole log");
        var chunk = system.chunk().orElseThrow();
        assertEquals(4, chunk.lines());
        assertEquals("2026-10-15 10:00:00", chunk.firstLineTime());
        assertEquals("2026-10-15 10:00:02", chunk.lastLineTime());
        assertEquals("system_log", chunk.manifest().artefactClass());
        assertEquals("palo_alto", chunk.manifest().vendor());
        // 30-day raw retention from the moment the chunk is stored (two 2 s polls after 09:00:00 on the test clock).
        assertEquals(Instant.parse("2026-10-17T09:00:04Z").plusSeconds(30L * 86400), chunk.manifest().expiresAt().orElseThrow());
        assertEquals(java.time.LocalDate.of(2026, 10, 17), chunk.logDay());
        var lines = h.chunkLines("pan_system");
        assertEquals(List.of("101", "102", "103", "104"), lines.stream().map(l -> l.get("seqno")).toList(), "oldest first");
        assertEquals("7000000000000000001", lines.get(0).get("@logid"));
        assertEquals("User synthetic-user logged in from 192.0.2.15", lines.get(0).get("opaque"));

        var config = h.write("pan_config");
        assertEquals(new PanCursor(ts(5), List.of("11")), config.cursor().orElseThrow());
        assertEquals(2, h.write("pan_config").chunk().orElseThrow().lines());

        // The measured number the rollout needs: per page, enqueue to FIN (one ACT poll first = two 2 s waits).
        @SuppressWarnings("unchecked") var pages = (List<Map<String, Object>>) h.detail("pan_system").get("pages");
        assertEquals(1, pages.size());
        assertEquals(4, pages.get(0).get("entries"));
        assertEquals(4000, ((Number) pages.get(0).get("enqueue_to_fin_ms")).intValue());
        assertEquals(2, pages.get(0).get("polls"));
        assertTrue(((Number) pages.get(0).get("result_bytes")).longValue() > 0);
        assertEquals(true, h.detail("pan_system").get("seed"));
        h.assertNoDeviceTextInRecords();
    }

    @Test
    void aFullSeedLeavesOlderEntriesBehindAndSaysSoWithAnInitialBaselineGap(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway", "palo_alto");
        List<String> newestFirst = new ArrayList<>(run(0, 2000, 500));
        java.util.Collections.reverse(newestFirst);
        h.page("system", newestFirst);
        h.run();
        var system = h.write("pan_system");
        assertEquals("GAP", system.run().outcome());
        assertEquals(List.of("INITIAL_BASELINE"), system.gaps().stream().map(g -> g.kind()).toList());
        assertTrue(system.gaps().get(0).extentUnknown(), "the number of older entries is not known");
        assertEquals(0, system.gaps().get(0).knownLostBytes());
        assertEquals(2000, system.chunk().orElseThrow().lines());
        assertEquals("COMPLETED", h.finalState, "GAP is a clean outcome");
        assertEquals("NO_NEW_DATA", h.write("pan_config").run().outcome(), "an empty config log seeds nothing and sets no cursor");
        assertTrue(h.write("pan_config").cursor().isEmpty());
    }

    // ---------------------------------------------------------------- incremental

    @Test
    void anIncrementalPageDropsTheEntriesAlreadyStoredAtTheBoundarySecond(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway", "palo_alto");
        h.cursor("pan_system", ts(2), "103", "104");
        h.cursor("pan_config", ts(5), "11");
        h.page("system", List.of(entry(ts(2), "103"), entry(ts(2), "104"), entry(ts(2), "105"), entry(ts(3), "106")));
        h.page("config", List.of(entry(ts(5), "11", "cmd", "set")));
        h.run();

        assertEquals(Map.of("log-type", "system", "nlogs", "2000", "dir", "forward",
                "query", "(receive_time geq '" + ts(2) + "')"), h.logCalls().get(0).formParams());
        assertEquals(List.of("pan_syslog_collect_system_enqueue", "pan_syslog_collect_job_get", "pan_syslog_collect_job_get",
                "pan_syslog_collect_config_enqueue", "pan_syslog_collect_job_get", "pan_syslog_collect_job_get"), h.gateIds);
        var system = h.write("pan_system");
        assertEquals("COLLECTED", system.run().outcome());
        assertEquals(List.of("105", "106"), h.chunkLines("pan_system").stream().map(l -> l.get("seqno")).toList());
        assertEquals(new PanCursor(ts(3), List.of("106")), system.cursor().orElseThrow());
        assertTrue(system.gaps().isEmpty(), "104 -> 105 -> 106 is contiguous");
        // Everything of the config page was a boundary duplicate: nothing new, nothing stored, cursor untouched.
        var config = h.write("pan_config");
        assertEquals("NO_NEW_DATA", config.run().outcome());
        assertTrue(config.chunk().isEmpty());
        assertTrue(config.cursor().isEmpty());
        assertEquals("COMPLETED", h.finalState);
    }

    @Test
    void newEntriesAtTheBoundarySecondGrowTheBoundarySetAndKeepTheCursorTime(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway", "palo_alto");
        h.cursor("pan_system", ts(2), "103", "104");
        h.cursor("pan_config", ts(5), "11");
        h.page("system", List.of(entry(ts(2), "103"), entry(ts(2), "104"), entry(ts(2), "105")));
        h.run();
        assertEquals(new PanCursor(ts(2), List.of("103", "104", "105")), h.write("pan_system").cursor().orElseThrow());
        assertEquals(1, h.write("pan_system").chunk().orElseThrow().lines());
    }

    @Test
    void aSecondPageStartsAtTheFirstPagesNewestSecondAndItsBoundaryEntryIsDroppedOnce(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway", "palo_alto");
        h.cursor("pan_system", ts(-1), "99");
        h.cursor("pan_config", ts(-1), "9");
        h.page("system", run(0, 2000, 100));
        h.page("system", run(1999, 2005, 2099)); // the boundary entry again, then five new ones
        h.run();

        var calls = h.enqueues("system");
        assertEquals(2, calls.size());
        assertEquals("(receive_time geq '" + ts(-1) + "')", calls.get(0).formParams().get("query"));
        assertEquals("(receive_time geq '" + ts(1999) + "')", calls.get(1).formParams().get("query"));
        var system = h.write("pan_system");
        assertEquals("COLLECTED", system.run().outcome());
        assertEquals(2005, system.chunk().orElseThrow().lines());
        assertEquals(new PanCursor(ts(2004), List.of("2104")), system.cursor().orElseThrow());
        @SuppressWarnings("unchecked") var pages = (List<Map<String, Object>>) h.detail("pan_system").get("pages");
        assertEquals(List.of(2000, 6), pages.stream().map(p -> p.get("entries")).toList());
        assertTrue(system.gaps().isEmpty());
    }

    @Test
    void moreDataThanFourPagesIsBacklogAndTheFifthPageIsNeverAsked(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway", "palo_alto");
        h.cursor("pan_system", ts(-1), "99");
        h.cursor("pan_config", ts(-1), "9");
        h.page("system", run(0, 2000, 100));
        h.page("system", run(1999, 3999, 2099));
        h.page("system", run(3998, 5998, 4098));
        h.page("system", run(5997, 7997, 6097));
        h.page("system", run(7996, 8001, 8096)); // would be page five
        h.run();

        assertEquals(PanSystemLogCollectJobExecutor.MAX_PAGES, h.enqueues("system").size());
        var system = h.write("pan_system");
        assertEquals("BACKLOG", system.run().outcome());
        assertEquals(List.of("BACKLOG"), system.gaps().stream().map(g -> g.kind()).toList());
        assertEquals(7997, system.chunk().orElseThrow().lines());
        assertEquals(ts(7996), system.cursor().orElseThrow().lastReceiveTime(), "the cursor stops at the last stored entry");
        assertEquals("COMPLETED", h.finalState, "BACKLOG is reported, not a failure");
        assertEquals(1, h.pages.get("system").size(), "page five stayed unread on the device");
    }

    @Test
    void moreThanAPageInOneSecondIsAPageStallThatStepsOverTheSecond(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway", "palo_alto");
        h.cursor("pan_system", ts(10), "5");
        h.cursor("pan_config", ts(10), "5");
        List<String> sameSecond = new ArrayList<>();
        for (int i = 0; i < 2000; i++) sameSecond.add(entry(ts(10), String.valueOf(6 + i)));
        h.page("system", sameSecond);
        h.run();
        var system = h.write("pan_system");
        assertEquals("GAP", system.run().outcome());
        assertEquals(List.of("PAGE_STALL"), system.gaps().stream().map(g -> g.kind()).toList());
        assertTrue(system.gaps().get(0).extentUnknown());
        assertEquals(new PanCursor(ts(11), List.of()), system.cursor().orElseThrow(), "one second forward, empty boundary");
        assertEquals(2000, system.chunk().orElseThrow().lines(), "what the page did hold is stored");
        assertEquals(1, h.enqueues("system").size(), "paging cannot advance, so it stops");
    }

    @Test
    void aSeqnoJumpIsOnlyEverASuspectedGapWithItsSize(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway", "palo_alto");
        h.cursor("pan_system", ts(2), "103");
        h.cursor("pan_config", ts(2), "103");
        h.page("system", List.of(entry(ts(3), "105"), entry(ts(4), "106"), entry(ts(5), "110")));
        h.run();
        var system = h.write("pan_system");
        assertEquals("GAP", system.run().outcome());
        var gap = system.gaps().get(0);
        assertEquals("SEQ_GAP_SUSPECTED", gap.kind());
        assertEquals(0, gap.knownLostBytes(), "never a confirmed loss");
        assertTrue(gap.extentUnknown());
        @SuppressWarnings("unchecked") Map<String, Object> detail = JSON.readValue(gap.detailJson(), Map.class);
        assertEquals(Map.of("jumps", 2, "missing_seqnos", 1 + 3, "largest_jump", 3), detail);
        assertEquals(3, system.chunk().orElseThrow().lines(), "the entries are stored all the same");
    }

    // ---------------------------------------------------------------- failures

    @Test
    void aLogJobThatNeverFinishesIsUnknownAfterTheBoundedWaitAndTheCursorStays(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway", "palo_alto");
        h.cursor("pan_system", ts(2), "103");
        h.cursor("pan_config", ts(2), "103");
        h.activeBeforeFin = Integer.MAX_VALUE;
        h.run();
        assertEquals("FAILED", h.finalState);
        assertEquals("partial:LOG_JOB_NOT_FINISHED", h.finalReason);
        assertEquals(2 * PanSystemLogCollectJobExecutor.MAX_POLLS, h.sent.stream().filter(s -> "get".equals(s.formParams().get("action"))).count(),
                "each log job is polled exactly the bound");
        assertEquals(2 * PanSystemLogCollectJobExecutor.MAX_POLLS, h.sleeps.get());
        for (String source : List.of("pan_system", "pan_config")) {
            var w = h.write(source);
            assertEquals("UNKNOWN", w.run().outcome());
            assertTrue(w.chunk().isEmpty() && w.cursor().isEmpty() && w.gaps().isEmpty());
            assertEquals("LOG_JOB_NOT_FINISHED", h.detail(source).get("reason"));
        }
        assertEquals(new PanCursor(ts(2), List.of("103")), h.repository.cursors.get("pan_system"));
        h.assertNoDeviceTextInRecords();
    }

    @Test
    void anApiErrorOrAFailedTransportIsUnknownAndNeverNoNewData(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway", "palo_alto");
        h.cursor("pan_system", ts(2), "103");
        h.cursor("pan_config", ts(2), "103");
        h.override = spec -> {
            String type = spec.formParams().get("log-type");
            if ("system".equals(type)) return new XmlApiResult.Completed(200,
                    "<response status=\"error\" code=\"17\"><msg><line>Invalid query FW-TANGO-04</line></msg></response>");
            if ("config".equals(type)) return new XmlApiResult.Failed("synthetic connection reset 192.0.2.1");
            return null;
        };
        h.run();
        assertEquals("FAILED", h.finalState);
        assertEquals("partial:API_ERROR", h.finalReason);
        assertEquals("API_ERROR", h.detail("pan_system").get("reason"));
        assertEquals("TRANSPORT_FAILED", h.detail("pan_config").get("reason"));
        assertEquals("UNKNOWN", h.write("pan_system").run().outcome());
        assertEquals("UNKNOWN", h.write("pan_config").run().outcome());
        h.assertNoDeviceTextInRecords();
    }

    @Test
    void anUnreadablePageIsUnknownWithTheCursorUnchanged(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway", "palo_alto");
        h.cursor("pan_system", ts(2), "103");
        h.cursor("pan_config", ts(2), "103");
        h.page("system", List.of("<entry><receive_time>" + ts(3) + "</receive_time><seqno>not-a-number</seqno></entry>"));
        h.page("config", List.of("<entry><receive_time>yesterday</receive_time><seqno>7</seqno></entry>"));
        h.run();
        assertEquals("LOG_ENTRY_SEQNO_UNREADABLE", h.detail("pan_system").get("reason"));
        assertEquals("LOG_ENTRY_TIME_UNREADABLE", h.detail("pan_config").get("reason"));
        assertEquals("FAILED", h.finalState);
        h.assertNoDeviceTextInRecords();
    }

    @Test
    void aPageHoldingEntriesOlderThanTheCursorMeansTheQueryWasNotHonoured(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway", "palo_alto");
        h.cursor("pan_system", ts(100), "103");
        h.cursor("pan_config", ts(100), "103");
        h.page("system", List.of(entry(ts(50), "40"), entry(ts(101), "104")));
        h.run();
        assertEquals("UNKNOWN", h.write("pan_system").run().outcome());
        assertEquals("QUERY_NOT_HONORED", h.detail("pan_system").get("reason"));
        assertTrue(h.write("pan_system").chunk().isEmpty());
    }

    @Test
    void withoutAnApiKeyNothingIsReadAndBothSourcesAreUnknown(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway", "palo_alto");
        doReturn(new XmlApiResult.Completed(200, "<response status=\"error\"><msg><line>Invalid credentials</line></msg></response>"))
                .when(h.transport).xmlApiCall(any(), any(), any());
        h.run();
        assertEquals("partial:AUTH_UNAVAILABLE", h.finalReason);
        assertEquals("UNKNOWN", h.write("pan_system").run().outcome());
        assertEquals("UNKNOWN", h.write("pan_config").run().outcome());
        verify(h.transport, times(1)).xmlApiCall(any(), any(), any());
    }

    // ---------------------------------------------------------------- storage

    @Test
    void aLowFreeSpaceVolumeRefusesStorageBeforeAnythingIsAskedOfTheDevice(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway", "palo_alto");
        h.cursor("pan_system", ts(2), "103");
        h.freeFraction = 0.14;
        h.run();
        assertEquals("STORAGE_REFUSED", h.write("pan_system").run().outcome());
        assertEquals("STORAGE_REFUSED", h.write("pan_config").run().outcome());
        assertTrue(h.logCalls().isEmpty());
        assertEquals("FAILED", h.finalState);
        assertEquals("partial:ARTEFACT_VOLUME_LOW_SPACE", h.finalReason);
        assertEquals(new PanCursor(ts(2), List.of("103")), h.repository.cursors.get("pan_system"));
    }

    @Test
    void aWriteTheDatabaseRefusesLeavesNoOrphanChunkAndTheJobOutcomeUnknown(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway", "palo_alto");
        h.repository.accept = false;
        h.page("system", List.of(entry(ts(0), "101")));
        h.run();
        assertEquals("OUTCOME_UNKNOWN", h.finalState);
        assertEquals("SYSTEM_LOG_NOT_RECORDED", h.finalReason);
        assertTrue(h.repository.writes.isEmpty());
        assertEquals(1, h.discarded.size(), "the stored chunk is removed again");
        assertTrue(h.enqueues("config").isEmpty(), "the second source never ran");
    }

    @Test
    void theChunkIsEncryptedAtRestAndNeverHoldsPlaintext(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway", "palo_alto");
        h.page("system", List.of(entry(ts(0), "101")));
        h.run();
        var ref = h.write("pan_system").chunk().orElseThrow().manifest().recoveryVolumePath();
        byte[] onDisk = java.nio.file.Files.readAllBytes(dir.resolve(ref));
        assertFalse(new String(onDisk, StandardCharsets.ISO_8859_1).contains("synthetic-user"));
        assertFalse(new String(onDisk, StandardCharsets.ISO_8859_1).contains("receive_time"));
        var manifest = h.write("pan_system").chunk().orElseThrow().manifest();
        assertEquals(manifest.ciphertextBytes(), onDisk.length);
        assertTrue(manifest.wrappedDataKey().length > 0);
    }

    // ---------------------------------------------------------------- redaction

    @Test
    void configLogSecretFieldsAreMaskedBeforeStorage(@TempDir Path dir) throws Exception {
        var h = new Harness(dir, "gateway", "palo_alto");
        h.cursor("pan_system", ts(0), "1");
        h.cursor("pan_config", ts(0), "1");
        h.page("config", List.of(
                entry(ts(1), "2", "cmd", "set", "path", "/config/mgt-config/users/entry[@name='synth-admin']/phash",
                        "full-path", "/config/mgt-config/users/entry[@name='synth-admin']/phash",
                        "before-change-preview", "&lt;entry name=\"synth-admin\"&gt;&lt;phash&gt;$1$SYNTHBEFORE$abc&lt;/phash&gt;&lt;/entry&gt;",
                        "after-change-preview", "&lt;entry name=\"synth-admin\"&gt;&lt;phash&gt;$1$SYNTHAFTER$xyz&lt;/phash&gt;&lt;/entry&gt;"),
                entry(ts(2), "3", "cmd", "set", "path", " vsys  vsys1 rulebase security rules rule-alpha",
                        "before-change-preview", "&lt;entry name=\"rule-alpha\"&gt;&lt;description&gt;keep this&lt;/description&gt;&lt;/entry&gt;",
                        "after-change-preview", "&lt;entry name=\"rule-alpha\"&gt;&lt;description&gt;keep this&lt;/description&gt;"
                                + "&lt;snmp-community&gt;SYNTHCOMMUNITY&lt;/snmp-community&gt;&lt;/entry&gt;"),
                entry(ts(3), "4", "cmd", "set", "full-path", "deviceconfig system snmp-setting access-setting snmp-community-string SYNTHSTRING",
                        "result", "Succeeded password=SYNTHPASS")));
        h.run();

        var lines = h.chunkLines("pan_config");
        assertEquals(3, lines.size());
        String stored = lines.toString();
        for (String secret : List.of("SYNTHBEFORE", "SYNTHAFTER", "SYNTHCOMMUNITY", "SYNTHSTRING", "SYNTHPASS"))
            assertFalse(stored.contains(secret), secret + " reached storage");
        assertEquals("<redacted>", lines.get(0).get("before-change-preview"), "a secret-bearing path hides the whole change body");
        assertEquals("<redacted>", lines.get(0).get("after-change-preview"));
        assertTrue(lines.get(1).get("before-change-preview").contains("keep this"), "ordinary configuration stays as evidence");
        assertTrue(lines.get(1).get("after-change-preview").contains("<snmp-community><redacted></snmp-community>"));
        assertTrue(lines.get(1).get("path").contains("rule-alpha"), "paths of ordinary elements stay");
        assertTrue(lines.get(2).get("full-path").contains("snmp-community-string <redacted>"));
        assertEquals("Succeeded password=<redacted>", lines.get(2).get("result"));
        int count = h.write("pan_config").chunk().orElseThrow().redactionCount();
        assertEquals(2 + 1 + 1 + 1, count);
        assertEquals(count, ((Number) h.detail("pan_config").get("redactions")).intValue());
        assertEquals("pan-config-v2", h.detail("pan_config").get("redaction_rule_set"));
        @SuppressWarnings("unchecked")
        Map<String, Number> byRule = (Map<String, Number>) h.detail("pan_config").get("redaction_by_rule");
        assertEquals(count, byRule.values().stream().mapToInt(Number::intValue).sum());
        assertEquals(2, byRule.get("secret_path_body").intValue());
    }

    // ---------------------------------------------------------------- admission of the job itself

    @Test
    void aManagementServerIsNeverASourceAndNothingIsSent(@TempDir Path dir) {
        var h = new Harness(dir, "management_server", "palo_alto");
        h.run();
        assertEquals("REJECTED", h.finalState);
        assertEquals("SYSTEM_LOG_DISABLED_ON_MANAGEMENT", h.finalReason);
        assertTrue(h.sent.isEmpty());
        assertTrue(h.repository.writes.isEmpty());
    }

    @Test
    void anotherVendorIsRejectedBeforeAnyContact(@TempDir Path dir) {
        var h = new Harness(dir, "gateway", "check_point");
        h.run();
        assertEquals("REJECTED", h.finalState);
        assertEquals("SYSTEM_LOG_COLLECT_UNAVAILABLE", h.finalReason);
        assertTrue(h.sent.isEmpty());
    }

    @Test
    void theV151MeasurementRowsNeverAuthorizeCollection(@TempDir Path dir) {
        var all = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        var onlyMeasurement = all.stream().filter(r -> r.gateId().startsWith("pan_syslog_measure")).toList();
        assertEquals(3, onlyMeasurement.size());
        var h = new Harness(dir, "gateway", "palo_alto");
        h.run(onlyMeasurement);
        assertEquals("REJECTED", h.finalState);
        assertEquals("SYSTEM_LOG_COLLECT_UNAVAILABLE", h.finalReason);
        assertTrue(h.sent.isEmpty(), "no keygen, no query");

        // And a single missing collection row (here the job-get row) refuses the whole job as well.
        var withoutJobGet = all.stream().filter(r -> !r.gateId().equals("pan_syslog_collect_job_get")).toList();
        var again = new Harness(dir, "gateway", "palo_alto");
        again.run(withoutJobGet);
        assertEquals("REJECTED", again.finalState);
        assertTrue(again.sent.isEmpty());
    }

    @Test
    void theFiveRequestShapesResolveSignedOffReadGatesFromTheFixtureAndTheCapabilityIsEligible() {
        var rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        GateRegistryPort gates = key -> rows.stream().filter(r -> r.key().equals(key)).toList();
        for (String command : PanSystemLogCollect.COMMANDS)
            assertTrue(PanSystemLogCollect.gate(command, gates).isPresent(), command);
        assertTrue(PanSystemLogCollect.gate("type=log&log-type=traffic&nlogs=<n>", gates).isEmpty());
        assertTrue(PanSystemLogCollect.capability(gates).executionEligible());
        GateRegistryPort none = key -> List.of();
        assertFalse(PanSystemLogCollect.capability(none).executionEligible());
    }
}
