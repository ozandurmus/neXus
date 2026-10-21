package com.securityexpert.nexus.ui2.service.packetanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.changes.ChangeFeedRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository.ChunkRow;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository.CrashAlertRow;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository.GapRow;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository.SourceRow;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.CaseRow;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.RunRow;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.PacketAnalysisPaths;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.service.changes.ChunkTextReader;
import com.securityexpert.nexus.ui2.service.changes.DefaultProjection;
import com.securityexpert.nexus.ui2.service.packetanalysis.PacketDeviceEventsService.Permissions;
import com.securityexpert.nexus.ui2.service.packetanalysis.PacketDeviceEventsService.RefusedException;
import com.securityexpert.nexus.ui2.service.packetanalysis.PacketDeviceEventsService.Request;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadFixture;

/**
 * Device events over synthetic facts: a stored report with one loss finding on the hop of a Check Point gateway, a
 * topology of four capture points on three device labels, a stored correlation that links one label to one inventory
 * device, and a real encrypted cpwd chunk. Example names, RFC 5737 addresses and opaque ids only.
 */
class PacketDeviceEventsServiceTest {
    private static final String CASE = "11111111-2222-4333-8444-555555555555";
    private static final String DEVICE = "dev-cp-1";
    private static final String HOST = "gw-lab-07";
    /** The finding starts at 2023-11-14T22:13:20.5Z, which is 2023-11-15 01:13:20 in Europe/Istanbul. */
    private static final double START = 1700000000.5;
    private static final Instant FROM = Instant.parse("2023-11-14T22:12:50.500Z");
    private static final Instant TO = Instant.parse("2023-11-14T22:13:50.500Z");

    @TempDir
    Path root;

    final PacketAnalysisRepository cases = mock(PacketAnalysisRepository.class);
    final SystemLogReadRepository logs = mock(SystemLogReadRepository.class);
    final DeviceRepository devices = mock(DeviceRepository.class);
    final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(SystemLogReadFixture.PRIVACY_KEY);
    final List<ChangeFeedRepository.ChunkRow> cpwdChunks = new ArrayList<>();
    final ChangeFeedRepository feed = new FeedStub();
    ArtefactStore store;
    PacketCaseFacts facts;

    /** Only the cpwd chunk read is used here; the other sources of the change feed are not part of this screen. */
    final class FeedStub implements ChangeFeedRepository {
        @Override public List<JobRow> jobs(Instant from, Instant to, Optional<String> deviceId, int limit) { return List.of(); }
        @Override public List<PolicyInstallRow> policyInstalls(Instant from, Instant to, Optional<String> deviceId, int limit) { return List.of(); }
        @Override public List<ChangeFeedRepository.CrashAlertRow> crashAlerts(Instant from, Instant to, Optional<String> deviceId, int limit) { return List.of(); }
        @Override public List<EvidenceRow> evidence(Instant from, Instant to, int limit) { return List.of(); }
        @Override public long evidenceCount(String evidenceKind) { return 0; }
        @Override public List<ChangeFeedRepository.ChunkRow> chunks(String sourceKey, Instant from, Instant to, Optional<String> deviceId, int limit) {
            return cpwdChunks.stream().filter(c -> sourceKey.equals(c.sourceKey()) && deviceId.map(c.deviceId()::equals).orElse(true))
                    .filter(c -> !c.collectedAt().isBefore(from) && c.collectedAt().isBefore(to)).limit(limit).toList();
        }
    }

    @BeforeEach
    void setUp() {
        store = new FileArtefactStore(root.resolve("artefacts"),
                ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])));
        when(cases.findCase(CASE)).thenReturn(Optional.of(caseRow("ANALYZED")));
        when(cases.runs(CASE)).thenReturn(List.of(run("COMPLETED")));
        when(devices.listAll()).thenReturn(List.of(device(DEVICE, "gateway", "check_point", HOST, "CLS-SYN-01")));
        when(logs.sources(DEVICE)).thenReturn(List.of(source("cp_cpwd_elg", "COLLECTED"), source("cp_messages", "COLLECTED")));
        when(logs.chunks(eq(DEVICE), eq(Optional.empty()), any(), anyInt())).thenReturn(List.of(
                indexChunk("chunk-m1", "cp_messages", "2023-11-15 01:10:00", "2023-11-15 01:20:00", 120, null),
                indexChunk("chunk-m0", "cp_messages", "2023-11-14 23:00:00", "2023-11-15 00:59:59", 400, null),
                indexChunk("chunk-pruned", "cp_messages", "2023-11-15 01:12:00", "2023-11-15 01:15:00", 9, Instant.parse("2023-11-20T00:00:00Z"))));
        when(logs.crashAlerts(eq(Optional.of(DEVICE)), anyInt())).thenReturn(List.of(
                new CrashAlertRow("alert-1", DEVICE, "fwk", 2, 1700000005L, "job-c", Instant.parse("2023-11-15T03:00:00Z"), null),
                new CrashAlertRow("alert-old", DEVICE, "cpd", 1, 1699990000L, "job-b", Instant.parse("2023-11-14T22:00:00Z"), null)));
        when(logs.gaps(eq(Optional.of(DEVICE)), anyInt())).thenReturn(List.of(
                new GapRow("gap-1", DEVICE, "cp_fwd_elg", "job-g", "ROTATED_AWAY", 2048, 0, false, 1699999990L, 1700000005L, Instant.parse("2023-11-15T03:00:00Z")),
                new GapRow("gap-far", DEVICE, "cp_messages", "job-g", "TRUNCATED", 10, 0, false, 1690000000L, 1690000100L, Instant.parse("2023-08-01T00:00:00Z"))));
        facts = new PacketCaseFacts(report(), topology(), correlation(true));
    }

    // ---- fixtures -----------------------------------------------------------------------------------------

    static CaseRow caseRow(String state) {
        return new CaseRow(CASE, "Synthetic delay case", "synthetic-operator", Instant.parse("2023-11-14T20:00:00Z"),
                Instant.parse("2023-11-21T20:00:00Z"), state, 1000, false, 2, "job-1");
    }

    static RunRow run(String state) {
        return new RunRow("job-1", CASE, state, "synthetic-operator", Instant.parse("2023-11-14T21:00:00Z"),
                Instant.parse("2023-11-14T21:00:05Z"), Instant.parse("2023-11-14T21:01:00Z"), "java-engine-1", "{}", null);
    }

    static DeviceSummaryRecord device(String id, String role, String vendor, String host, String cluster) {
        return new DeviceSummaryRecord(id, role, vendor, DeviceEnrollmentState.ENROLLED, Optional.ofNullable(host), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.ofNullable(cluster), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    static SourceRow source(String key, String outcome) {
        return new SourceRow(key, true, 10L, null, null, false, null, Instant.parse("2023-11-15T03:05:00Z"), outcome,
                Instant.parse("2023-11-15T03:05:00Z"), "job-s", 10L, 10L, 0, 1, 10, 10, Instant.parse("2023-11-15T03:05:00Z"));
    }

    static ChunkRow indexChunk(String id, String source, String first, String last, long lines, Instant prunedAt) {
        return new ChunkRow(id, source, "job-s", Instant.parse("2023-11-15T03:05:00Z"), "2023-11-15", 0, 1000, 1000, lines, first, last,
                0, null, List.of(), 900, prunedAt, null, "COLLECTED");
    }

    static Map<String, Object> point(String id, String label, String device, String kind, String side, String capture) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("label", label); m.put("device", device); m.put("kind", kind); m.put("side", side); m.put("capture_id", capture);
        return m;
    }

    static Map<String, Object> topology() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("points", List.of(point("p1", "Inside capture", "edge-fw", "Firewall", "ingress", "c1"),
                point("p2", "Outside capture", "edge-fw", "Firewall", "egress", "c2"),
                point("p3", "Core span", "core-sw", "Router/Switch", "both", "c3"),
                point("p4", "Second firewall", "second-fw", "Firewall", "both", "c4")));
        t.put("forward", List.of("p1", "p2", "p3", "p4"));
        t.put("reverse", List.of("p4", "p3", "p2", "p1"));
        return t;
    }

    static Map<String, Object> report() {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", "forward:p1:p2"); segment.put("point_a", "p1"); segment.put("point_b", "p2"); segment.put("direction", "forward");
        segment.put("label", "Inside capture → Outside capture"); segment.put("location", "device"); segment.put("device", "edge-fw");
        segment.put("p95_ms", 180.5); segment.put("max_ms", 900.0); segment.put("loss_percent", 1.2); segment.put("reason", null);
        Map<String, Object> loss = new LinkedHashMap<>();
        loss.put("id", "forward:p1:p2:impactful_loss"); loss.put("type", "impactful_loss"); loss.put("severity", "high");
        loss.put("hop", "forward:p1:p2"); loss.put("direction", "forward"); loss.put("confidence", "supported");
        loss.put("time_range", List.of(START, START + 0.5)); loss.put("summary", "3 impactful loss events");
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("count", 3L); metrics.put("max_recovery_ms", 420.0);
        loss.put("metrics", metrics);
        Map<String, Object> untimed = new LinkedHashMap<>();
        untimed.put("id", "quality:p3"); untimed.put("type", "capture_quality"); untimed.put("severity", "quality"); untimed.put("hop", null);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("findings", List.of(untimed, loss));
        r.put("segments", List.of(segment));
        r.put("window", Map.of("start", START - 10, "end", START + 10));
        return r;
    }

    static Map<String, Object> correlation(boolean linkEdge) {
        List<Map<String, Object>> hops = new ArrayList<>();
        for (String id : List.of("p1", "p2")) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("point_id", id); h.put("status", linkEdge ? "MATCH" : "NO_MATCH"); h.put("reason", linkEdge ? "Unique inventory interface match" : "none");
            h.put("device_id", linkEdge ? DEVICE : null); h.put("hostname", linkEdge ? HOST : null);
            hops.add(h);
        }
        Map<String, Object> p3 = new LinkedHashMap<>();
        p3.put("point_id", "p3"); p3.put("status", "NO_MATCH"); p3.put("reason", "No neXus inventory interface matched the hop's observed MACs or addresses/subnets");
        hops.add(p3);
        Map<String, Object> p4 = new LinkedHashMap<>();
        p4.put("point_id", "p4"); p4.put("status", "AMBIGUOUS"); p4.put("reason", "2 candidate inventory interfaces matched with equal evidence");
        hops.add(p4);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("case_id", CASE); c.put("hops", hops); c.put("flows", List.of());
        return c;
    }

    /** Stores {@code text} as an encrypted cpwd chunk of the device and lists it in the feed stub. */
    void cpwdChunk(String chunkId, Instant collectedAt, String text) throws IOException {
        try (ArtefactStore.ArtefactHandle handle = store.open(DEVICE, "job-w", "check_point", true)) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            handle.sink().write(bytes);
            var meta = handle.finish();
            cpwdChunks.add(new ChangeFeedRepository.ChunkRow(chunkId, DEVICE, "cp_cpwd_elg", "job-w", collectedAt, bytes.length,
                    text.split("\n").length, meta.ref().value(), meta.wrappedDataKey(), null));
        }
    }

    DefaultProjection plain() {
        return new DefaultProjection(false, devices.listAll(), names, null, null);
    }

    DefaultProjection masked() {
        LogMasking masking = new LogMasking(SystemLogReadFixture.PRIVACY_KEY, () -> KnownNames.of(Map.of(HOST, "FW-TANGO-04")));
        return new DefaultProjection(true, devices.listAll(), names, masking.session(), null);
    }

    PacketDeviceEventsService service(ArtefactStore artefacts, DefaultProjection maskedProjection) {
        return new PacketDeviceEventsService(cases, id -> Optional.ofNullable(facts), logs, feed, new ChunkTextReader(artefacts), devices, names,
                this::plain, () -> maskedProjection);
    }

    PacketDeviceEventsService service() {
        return service(store, masked());
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("devices");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    // ---- the sheet, the window and the linked device -------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void theFindingSheetNamesTheHopItsFiguresAndTheWindowAroundItsStart() throws IOException {
        cpwdChunk("chunk-w1", TO.plusSeconds(3600),
                "[cpd 1234 5678]@" + HOST + "[15 Nov 01:13:30] Process cpd restarted by watchdog\n"
                        + "[fwd 1 2]@" + HOST + "[15 Nov 03:00:00] Process fwd terminated\n"
                        + "[cpd 1234 5678]@" + HOST + "[15 Nov 01:13:31] Reading configuration\n");
        var response = service().deviceEvents(new Request(CASE, "", 0), false, new Permissions(true));
        assertFalse(response.masked());
        Map<String, Object> body = response.body();
        assertEquals(30, body.get("window_seconds"));
        Map<String, Object> finding = map(body.get("finding"));
        assertEquals("forward:p1:p2:impactful_loss", finding.get("id"), "the first finding with a start time, in report order");
        assertEquals("Inside capture → Outside capture", finding.get("hop_label"));
        assertEquals("2023-11-14T22:13:20.500Z", finding.get("start"));
        Map<String, Object> figures = map(finding.get("figures"));
        assertEquals(180.5, figures.get("p95_ms"));
        assertEquals(900.0, figures.get("max_ms"));
        assertEquals(1.2, figures.get("loss_percent"));
        assertEquals(3L, figures.get("event_count"));
        assertEquals(420.0, figures.get("max_recovery_ms"));
        Map<String, Object> window = map(body.get("window"));
        assertEquals(FROM.toString(), window.get("from"));
        assertEquals(TO.toString(), window.get("to"));
        List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("findings");
        assertEquals(2, choices.size());
        assertNull(choices.get(0).get("start"), "a finding without a time range is listed without a start, never with 0");

        List<Map<String, Object>> rows = rows(body);
        assertEquals(List.of("edge-fw", "core-sw", "second-fw"), rows.stream().map(r -> r.get("label")).toList(), "forward-path order, one row per device label");
        Map<String, Object> edge = rows.get(0);
        assertEquals(true, edge.get("on_hop"));
        assertEquals("LINKED", map(edge.get("link")).get("state"));
        assertEquals(DEVICE, map(edge.get("link")).get("device_id"));
        assertEquals(HOST, map(edge.get("link")).get("device_name"));
        assertEquals("EVENTS", edge.get("result"));
        assertEquals(3, edge.get("event_count"));
        assertEquals("COVERED", edge.get("coverage"));
        List<Map<String, Object>> events = (List<Map<String, Object>>) edge.get("events");
        assertEquals(List.of("GAP", "CRASH_ALERT", "CPWD_LINE"), events.stream().map(e -> e.get("kind")).toList(), "sorted by time");
        Map<String, Object> cpwd = events.get(2);
        assertEquals("cpwd: Process cpd restarted by watchdog", cpwd.get("text"));
        assertEquals("DEVICE_LOCAL", cpwd.get("time_basis"));
        assertEquals("15 Nov 01:13:30", cpwd.get("device_time"));
        assertEquals("2023-11-14T22:13:30Z", cpwd.get("time"));
        assertEquals("crash/dump files listed: fwk × 2", events.get(1).get("text"));
        assertEquals("collection gap ROTATED_AWAY (2048 bytes known lost)", events.get(0).get("text"));
        List<Map<String, Object>> sources = (List<Map<String, Object>>) edge.get("sources");
        Map<String, Object> messages = sources.stream().filter(s -> "cp_messages".equals(s.get("source_key"))).findFirst().orElseThrow();
        assertEquals(1L, messages.get("chunks_overlapping"), "the pruned chunk and the earlier chunk do not count");
        assertEquals(120L, messages.get("lines_in_overlapping_chunks"));
        assertEquals(0L, sources.stream().filter(s -> "cp_cpwd_elg".equals(s.get("source_key"))).findFirst().orElseThrow().get("chunks_overlapping"));

        Map<String, Object> core = rows.get(1);
        assertEquals(false, core.get("on_hop"));
        assertEquals("NOT_LINKED", map(core.get("link")).get("state"));
        assertTrue(String.valueOf(core.get("result_reason")).startsWith("log not collected: Router/Switch is not a managed firewall"), core.get("result_reason").toString());
        assertEquals("NOT_COLLECTED", core.get("result"));
        assertNull(core.get("event_count"), "an unlinked device has no count, not 0");
        Map<String, Object> second = rows.get(2);
        assertEquals("NOT_COLLECTED", second.get("result"));
        assertEquals("AMBIGUOUS", map(second.get("link")).get("correlation_status"));
        assertTrue(String.valueOf(second.get("result_reason")).contains("device not linked: AMBIGUOUS"), second.get("result_reason").toString());
        List<String> notes = (List<String>) body.get("notes");
        assertTrue(notes.get(0).contains("Europe/Istanbul"));
    }

    @Test
    void aCoveredWindowWithoutAnyStoredEventIsClean() throws IOException {
        cpwdChunk("chunk-w2", TO.plusSeconds(3600), "[fwd 1 2]@" + HOST + "[15 Nov 03:00:00] Process fwd terminated\n");
        when(logs.crashAlerts(eq(Optional.of(DEVICE)), anyInt())).thenReturn(List.of());
        when(logs.gaps(eq(Optional.of(DEVICE)), anyInt())).thenReturn(List.of());
        Map<String, Object> edge = rows(service().deviceEvents(new Request(CASE, "", 0), false, new Permissions(true)).body()).get(0);
        assertEquals("CLEAN", edge.get("result"));
        assertEquals(0, edge.get("event_count"));
        assertNull(edge.get("result_reason"));
    }

    @Test
    void aWindowNoStoredChunkCoversIsNotCollectedNotClean() {
        when(logs.chunks(eq(DEVICE), eq(Optional.empty()), any(), anyInt())).thenReturn(List.of(
                indexChunk("chunk-m0", "cp_messages", "2023-11-14 23:00:00", "2023-11-15 00:59:59", 400, null)));
        when(logs.crashAlerts(eq(Optional.of(DEVICE)), anyInt())).thenReturn(List.of());
        when(logs.gaps(eq(Optional.of(DEVICE)), anyInt())).thenReturn(List.of());
        Map<String, Object> edge = rows(service().deviceEvents(new Request(CASE, "", 0), false, new Permissions(true)).body()).get(0);
        assertEquals("NOT_COLLECTED", edge.get("result"));
        assertEquals("no stored log line of any source overlaps the window", edge.get("result_reason"));
        assertEquals("NONE", edge.get("coverage"));
    }

    @Test
    void aDeviceWithoutAnyLogSourceIsNotCollected() {
        when(logs.sources(DEVICE)).thenReturn(List.of());
        Map<String, Object> edge = rows(service().deviceEvents(new Request(CASE, "", 0), false, new Permissions(true)).body()).get(0);
        assertEquals("NOT_COLLECTED", edge.get("result"));
        assertEquals("no log source has been collected for this device", edge.get("result_reason"));
        assertEquals("LINKED", map(edge.get("link")).get("state"));
    }

    @Test
    void withoutSystemLogReadTheLinkedRowSaysNotPermittedAndReadsNothing() {
        when(logs.sources(DEVICE)).thenThrow(new IllegalStateException("must not be called"));
        Map<String, Object> edge = rows(service().deviceEvents(new Request(CASE, "", 0), false, new Permissions(false)).body()).get(0);
        assertEquals("NOT_PERMITTED", edge.get("result"));
        assertEquals("needs system_log_read", edge.get("result_reason"));
        assertEquals("LINKED", map(edge.get("link")).get("state"));
    }

    // ---- UNKNOWN: store, masking, read failure, unreadable chunk ------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void withoutAnArtefactStoreTheResultIsUnknownAndTheIndexFactsStillShow() {
        Map<String, Object> body = service(null, masked()).deviceEvents(new Request(CASE, "", 0), false, new Permissions(true)).body();
        Map<String, Object> edge = rows(body).get(0);
        assertEquals("EVENTS", edge.get("result"), "the crash alert and the gap are index rows and still count");
        assertEquals(2, edge.get("event_count"));
        assertTrue(String.valueOf(edge.get("result_reason")).startsWith("SYSTEM_LOG_STORE_UNAVAILABLE"));
        assertTrue(((List<String>) body.get("notes")).stream().anyMatch(n -> n.startsWith("SYSTEM_LOG_STORE_UNAVAILABLE")));
        when(logs.crashAlerts(eq(Optional.of(DEVICE)), anyInt())).thenReturn(List.of());
        when(logs.gaps(eq(Optional.of(DEVICE)), anyInt())).thenReturn(List.of());
        edge = rows(service(null, masked()).deviceEvents(new Request(CASE, "", 0), false, new Permissions(true)).body()).get(0);
        assertEquals("UNKNOWN", edge.get("result"));
        assertNull(edge.get("event_count"), "UNKNOWN is never 0");
        assertEquals("COVERED", edge.get("coverage"));
    }

    @Test
    void whenTheMaskedViewCannotMaskTextTheResultIsUnknown() {
        when(logs.crashAlerts(eq(Optional.of(DEVICE)), anyInt())).thenReturn(List.of());
        when(logs.gaps(eq(Optional.of(DEVICE)), anyInt())).thenReturn(List.of());
        var closed = new DefaultProjection(true, devices.listAll(), names, null, new IllegalStateException("dictionary unavailable"));
        Map<String, Object> edge = rows(service(store, closed).deviceEvents(new Request(CASE, "", 0), true, new Permissions(true)).body()).get(0);
        assertEquals("UNKNOWN", edge.get("result"));
        assertTrue(String.valueOf(edge.get("result_reason")).startsWith("SYSTEM_LOG_MASKING_UNAVAILABLE"));
    }

    @Test
    void aFailedIndexReadIsUnknownForThatDeviceOnly() {
        when(logs.sources(DEVICE)).thenThrow(new IllegalStateException("db down"));
        List<Map<String, Object>> rows = rows(service().deviceEvents(new Request(CASE, "", 0), false, new Permissions(true)).body());
        assertEquals("UNKNOWN", rows.get(0).get("result"));
        assertEquals("read failed: IllegalStateException", rows.get(0).get("result_reason"));
        assertEquals("NOT_COLLECTED", rows.get(1).get("result"));
    }

    @Test
    void anUnreadableCpwdChunkWithNoOtherEventIsUnknownNotClean() {
        cpwdChunks.add(new ChangeFeedRepository.ChunkRow("chunk-bad", DEVICE, "cp_cpwd_elg", "job-w", TO.plusSeconds(60), 100, 1, "missing-ref",
                new byte[16], null));
        when(logs.crashAlerts(eq(Optional.of(DEVICE)), anyInt())).thenReturn(List.of());
        when(logs.gaps(eq(Optional.of(DEVICE)), anyInt())).thenReturn(List.of());
        Map<String, Object> edge = rows(service().deviceEvents(new Request(CASE, "", 0), false, new Permissions(true)).body()).get(0);
        assertEquals("UNKNOWN", edge.get("result"));
        assertEquals("1 cpwd chunk could not be read", edge.get("result_reason"));
    }

    // ---- linking: the correlation decides, never a label ---------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void withoutAStoredCorrelationNoLabelIsLinkedEvenWhenItEqualsAHostname() {
        Map<String, Object> t = topology();
        ((List<Map<String, Object>>) t.get("points")).get(0).put("device", HOST);
        facts = new PacketCaseFacts(report(), t, null);
        Map<String, Object> body = service().deviceEvents(new Request(CASE, "", 0), false, new Permissions(true)).body();
        for (Map<String, Object> row : rows(body)) {
            assertEquals("NOT_LINKED", map(row.get("link")).get("state"), row.toString());
            assertEquals("NOT_COLLECTED", row.get("result"));
        }
        assertTrue(((List<String>) body.get("notes")).stream().anyMatch(n -> n.startsWith("No inventory correlation is stored")));
    }

    @Test
    void aMatchedDeviceThatIsNotAManagedFirewallIsNotCollected() {
        when(devices.listAll()).thenReturn(List.of(device(DEVICE, "management_server", "check_point", "mgmt-lab-01", null)));
        Map<String, Object> edge = rows(service().deviceEvents(new Request(CASE, "", 0), false, new Permissions(true)).body()).get(0);
        assertEquals("NOT_LINKED", map(edge.get("link")).get("state"));
        assertEquals("log not collected: management_server of vendor check_point is not a managed firewall", edge.get("result_reason"));
        when(devices.listAll()).thenReturn(List.of());
        edge = rows(service().deviceEvents(new Request(CASE, "", 0), false, new Permissions(true)).body()).get(0);
        assertEquals("log not collected: the matched device is not in the neXus inventory", edge.get("result_reason"));
    }

    @Test
    void capturePointsOfOneLabelMatchingTwoDevicesAreNotLinked() {
        Map<String, Object> c = correlation(true);
        map(((List<?>) c.get("hops")).get(1)).put("device_id", "dev-cp-2");
        facts = new PacketCaseFacts(report(), topology(), c);
        Map<String, Object> edge = rows(service().deviceEvents(new Request(CASE, "", 0), false, new Permissions(true)).body()).get(0);
        assertEquals("NOT_LINKED", map(edge.get("link")).get("state"));
        assertEquals("AMBIGUOUS", map(edge.get("link")).get("correlation_status"));
    }

    // ---- masking -------------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void theMaskedViewShowsPseudonymsAndNeverARawNameOrLabel() throws IOException {
        cpwdChunk("chunk-w3", TO.plusSeconds(3600), "[cpd 1 2]@" + HOST + "[15 Nov 01:13:30] Process cpd restarted on " + HOST + " 192.0.2.17\n");
        var response = service().deviceEvents(new Request(CASE, "", 0), true, new Permissions(true));
        assertTrue(response.masked());
        Map<String, Object> body = response.body();
        String text = body.toString();
        assertFalse(text.contains(HOST), "no raw hostname anywhere in the masked body");
        assertFalse(text.contains("edge-fw") || text.contains("Inside capture") || text.contains("core-sw"), "topology labels are pseudonymised");
        Map<String, Object> edge = rows(body).get(0);
        assertEquals(names.maskDeviceName(HOST, "CLS-SYN-01", DEVICE), map(edge.get("link")).get("device_name"));
        assertEquals(names.maskDeviceName("edge-fw", null), edge.get("label"));
        assertEquals(DEVICE, map(edge.get("link")).get("device_id"), "the opaque id stays");
        Map<String, Object> cpwd = ((List<Map<String, Object>>) edge.get("events")).stream().filter(e -> "CPWD_LINE".equals(e.get("kind"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(cpwd.get("text")).contains("FW-TANGO-04"), cpwd.get("text").toString());
        assertNotEquals(map(body.get("finding")).get("hop_label"), "Inside capture → Outside capture");
        assertEquals(names.maskPolicyName("packet_point", "Inside capture") + " → " + names.maskPolicyName("packet_point", "Outside capture"),
                map(body.get("finding")).get("hop_label"));
    }

    // ---- selection and refusals ----------------------------------------------------------------------------

    @Test
    void aFindingIsChosenByIdAndTheWindowIsConfigurable() {
        Map<String, Object> body = service().deviceEvents(new Request(CASE, "forward:p1:p2:impactful_loss", 120), false, new Permissions(true)).body();
        assertEquals(120, body.get("window_seconds"));
        assertEquals("2023-11-14T22:11:20.500Z", map(body.get("window")).get("from"));
        assertEquals("2023-11-14T22:15:20.500Z", map(body.get("window")).get("to"));
        assertEquals(404, assertThrows(RefusedException.class, () -> service().deviceEvents(new Request(CASE, "no-such", 0), false, new Permissions(true))).refusal.status());
        assertEquals("FINDING_WITHOUT_TIME", assertThrows(RefusedException.class, () -> service().deviceEvents(new Request(CASE, "quality:p3", 0), false, new Permissions(true))).refusal.code());
        assertEquals("WINDOW_OUT_OF_RANGE", assertThrows(RefusedException.class, () -> service().deviceEvents(new Request(CASE, "", 4), false, new Permissions(true))).refusal.code());
        assertEquals("WINDOW_OUT_OF_RANGE", assertThrows(RefusedException.class, () -> service().deviceEvents(new Request(CASE, "", 601), false, new Permissions(true))).refusal.code());
    }

    @Test
    void aReportWithoutATimedFindingAnswersWithoutRowsAndSaysSo() {
        Map<String, Object> r = report();
        r.put("findings", List.of());
        facts = new PacketCaseFacts(r, topology(), correlation(true));
        Map<String, Object> body = service().deviceEvents(new Request(CASE, "", 0), false, new Permissions(true)).body();
        assertNull(body.get("finding"));
        assertNull(body.get("window"));
        assertEquals(List.of(), body.get("devices"));
        assertEquals(List.of("The stored report holds no finding."), body.get("notes"));
    }

    @Test
    void theCaseStateRefusalsMatchTheOtherCaseReads() {
        assertEquals("INVALID_ID", refusal(new Request("../x", "", 0)).code());
        when(cases.findCase(CASE)).thenReturn(Optional.empty());
        assertEquals(404, refusal(new Request(CASE, "", 0)).status());
        when(cases.findCase(CASE)).thenReturn(Optional.of(caseRow("ANALYZING")));
        assertEquals("ANALYSIS_RUNNING", refusal(new Request(CASE, "", 0)).code());
        when(cases.findCase(CASE)).thenReturn(Optional.of(caseRow("ANALYZED")));
        when(cases.runs(CASE)).thenReturn(List.of(run("RUNNING")));
        assertEquals("ANALYSIS_RUNNING", refusal(new Request(CASE, "", 0)).code());
        when(cases.runs(CASE)).thenReturn(List.of(run("COMPLETED")));
        facts = null;
        assertEquals("ANALYSIS_NOT_READY", refusal(new Request(CASE, "", 0)).code());
        facts = new PacketCaseFacts(null, topology(), null);
        assertEquals("ANALYSIS_NOT_READY", refusal(new Request(CASE, "", 0)).code());
    }

    private PacketDeviceEventsService.Refusal refusal(Request request) {
        return assertThrows(RefusedException.class, () -> service().deviceEvents(request, false, new Permissions(true))).refusal;
    }

    // ---- the facts reader over a real case database ----------------------------------------------------------

    @Test
    void theVolumeReaderReadsTheThreeSettingsOfACaseDatabaseAndIsEmptyWithoutOne() throws Exception {
        Path volume = root.resolve("volume");
        assertTrue(PacketCaseFacts.onVolume(volume).read(CASE).isEmpty());
        Path caseDir = PacketAnalysisPaths.caseDir(volume, CASE);
        var projectStore = ProjectStore.open(caseDir, ProjectStore.Settings.defaults(null));
        try (var db = projectStore.connect()) {
            // Only the settings table is needed; the schema comes from the store.
        }
        try (var conn = Engine.open(PacketAnalysisPaths.projectDuckDb(volume, CASE)); var db = new Db(conn)) {
            db.exec("INSERT OR REPLACE INTO settings VALUES ('report', ?)", PyJsonWriter.dumps(report(), false));
            db.exec("INSERT OR REPLACE INTO settings VALUES ('topology', ?)", PyJsonWriter.dumps(topology(), false));
            db.exec("INSERT OR REPLACE INTO settings VALUES ('nexus_correlation', ?)", PyJsonWriter.dumps(correlation(true), false));
        }
        PacketCaseFacts read = PacketCaseFacts.onVolume(volume).read(CASE).orElseThrow();
        assertNotNull(read.report());
        assertEquals(2, ((List<?>) read.report().get("findings")).size());
        assertEquals(List.of("p1", "p2", "p3", "p4"), read.topology().get("forward"));
        assertEquals(4, ((List<?>) read.correlation().get("hops")).size());
        facts = read;
        Map<String, Object> body = service().deviceEvents(new Request(CASE, "", 0), false, new Permissions(true)).body();
        assertEquals("forward:p1:p2:impactful_loss", map(body.get("finding")).get("id"));
        assertEquals("LINKED", map(rows(body).get(0).get("link")).get("state"));
    }

    @Test
    void theHelpersReadDeviceLocalTextAndTheDaysOfAWindow() {
        assertEquals(List.of("2023-11-15"), PacketDeviceEventsService.daysOf(FROM, TO));
        assertEquals(List.of("2023-11-14", "2023-11-15"), PacketDeviceEventsService.daysOf(Instant.parse("2023-11-14T20:59:50Z"), Instant.parse("2023-11-14T21:00:10Z")));
        assertEquals(Instant.parse("2023-11-14T22:10:00Z"), PacketDeviceEventsService.deviceText("2023-11-15 01:10:00"));
        assertNull(PacketDeviceEventsService.deviceText(null));
        assertNull(PacketDeviceEventsService.deviceText("Nov 15 01:10"));
        assertEquals(Instant.parse("2023-11-14T22:13:20.500Z"), PacketDeviceEventsService.instant(START));
    }
}
