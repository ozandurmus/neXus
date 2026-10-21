package com.securityexpert.nexus.ui2.service.packetanalysis;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

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
import com.securityexpert.nexus.ui2.platform.PacketAnalysisPaths;
import com.securityexpert.nexus.ui2.service.changes.ChangeEvent;
import com.securityexpert.nexus.ui2.service.changes.ChunkTextReader;
import com.securityexpert.nexus.ui2.service.changes.CpwdEvents;
import com.securityexpert.nexus.ui2.service.changes.DefaultProjection;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * Packet Analysis > case > Device events ("what was the device saying at the moment of the delay?"; mockup screen 4
 * of 2026-10-17, PO-approved log mockup). For one stored finding of an analysed case
 * it names the finding's hop, its delay and loss figures and its start, and shows, for the window
 * {@code start +- window_seconds}, the system-log facts the programme already stores for the firewalls on the
 * capture path. It issues no device command and stores nothing.
 *
 * <p><b>Mapping a capture point to a device.</b> The only stored link between a capture point and a neXus device is
 * the case's {@code nexus_correlation} (the inventory correlation the service stores after an analysis): a hop whose
 * status is {@code MATCH} names one {@code device_id}. The topology's free-text {@code device} label is shown and
 * used to group capture points of one box, but it is never a join key (a label is presentation identity): a device
 * label without a MATCH is "device not linked", with the correlation's own reason, and no name lookup is attempted.</p>
 *
 * <p><b>Window facts per linked device</b> (all from stored rows and stored chunk text):
 * cpwd restart/termination lines of {@code cp_cpwd_elg} chunks ({@link CpwdEvents}, the same recogniser as the
 * change feed), crash/dump alerts whose newest device mtime lies in the window, gap markers whose window overlaps,
 * and per source the chunks whose first/last line times overlap the window with their line counts. The result word
 * is {@code CLEAN} (a stored chunk covers the window and holds no event), {@code EVENTS} (n), {@code NOT_COLLECTED}
 * (no source, no chunk covering the window, not a managed firewall, or not linked), {@code NOT_PERMITTED} (the
 * session lacks {@code system_log_read}) or {@code UNKNOWN} (no artefact store, masking unavailable, a read failed
 * or a chunk could not be decrypted). No event is ever inferred.</p>
 *
 * <p><b>Time basis.</b> A finding's start is the capture clock (epoch seconds, as corrected by the engine). cpwd line
 * times and chunk line times are the device's own wall clock without a zone; they are read as Europe/Istanbul for
 * the overlap test, as the change feed does, and the body says so. A crash alert's time is the file mtime, device
 * epoch seconds.</p>
 */
public final class PacketDeviceEventsService {
    public static final int DEFAULT_WINDOW_SECONDS = 30;
    public static final int MIN_WINDOW_SECONDS = 5;
    public static final int MAX_WINDOW_SECONDS = 600;
    /** A chunk collected at the end of a job window holds lines from the window before it. */
    static final Duration CHUNK_SLACK = Duration.ofHours(6);
    static final int CHUNK_CAP = 200;
    static final ZoneId DEVICE_TEXT_ZONE = ZoneId.of("Europe/Istanbul");
    static final Set<String> MANAGED_VENDORS = Set.of("check_point", "palo_alto");
    static final Set<String> ANALYSING = Set.of("QUEUED", "ANALYZING", "RUNNING", "REQUESTED");
    static final String TIME_BASIS_NOTE = "Finding times are the capture clock (UTC). cpwd lines and chunk line times are the "
            + "device's own wall clock without a zone, read as Europe/Istanbul for the window test; crash alert times are the "
            + "file's device mtime.";
    static final String EVENTS_NOTE = "Events are stored lines and rows only: cpwd restart/termination lines (rule cpwd-restart-v1, "
            + "PROVISIONAL), crash/dump alerts and collection gap markers. Nothing is inferred from a silence.";

    /** The request as the route and the CLI pass it: an optional finding id and an optional half-window in seconds (0 = default). */
    public record Request(String caseId, String findingId, int windowSeconds) {
    }

    /** Whether the caller may read the stored system logs ({@code system_log_read}); the route decides. */
    public record Permissions(boolean systemLogs) {
    }

    public record Refusal(int status, String code, String message) {
    }

    public static final class RefusedException extends RuntimeException {
        public final Refusal refusal;

        public RefusedException(int status, String code, String message) {
            super(code);
            this.refusal = new Refusal(status, code, message);
        }
    }

    private final PacketAnalysisRepository cases;
    private final PacketCaseFacts.Reader facts;
    private final SystemLogReadRepository logs;
    private final ChangeFeedRepository feed;
    private final ChunkTextReader chunks;
    private final DeviceRepository devices;
    private final TopologyNamePseudonymizer names;
    private final Supplier<DefaultProjection> plain;
    private final Supplier<DefaultProjection> masked;

    public PacketDeviceEventsService(PacketAnalysisRepository cases, PacketCaseFacts.Reader facts, SystemLogReadRepository logs,
            ChangeFeedRepository feed, ChunkTextReader chunks, DeviceRepository devices, TopologyNamePseudonymizer names,
            Supplier<DefaultProjection> plain, Supplier<DefaultProjection> masked) {
        this.cases = Objects.requireNonNull(cases, "cases");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.logs = Objects.requireNonNull(logs, "logs");
        this.feed = Objects.requireNonNull(feed, "feed");
        this.chunks = Objects.requireNonNull(chunks, "chunks");
        this.devices = Objects.requireNonNull(devices, "devices");
        this.names = Objects.requireNonNull(names, "names");
        this.plain = Objects.requireNonNull(plain, "plain");
        this.masked = Objects.requireNonNull(masked, "masked");
    }

    public PacketDeviceEventsResponse deviceEvents(Request request, boolean maskedView, Permissions permissions) {
        if (request.caseId() == null || !PacketAnalysisPaths.isId(request.caseId())) {
            throw new RefusedException(400, "INVALID_ID", "The case id is not a valid identifier");
        }
        int half = request.windowSeconds() == 0 ? DEFAULT_WINDOW_SECONDS : request.windowSeconds();
        if (half < MIN_WINDOW_SECONDS || half > MAX_WINDOW_SECONDS) {
            throw new RefusedException(400, "WINDOW_OUT_OF_RANGE",
                    "window_seconds must be between " + MIN_WINDOW_SECONDS + " and " + MAX_WINDOW_SECONDS);
        }
        Optional<CaseRow> found = cases.findCase(request.caseId());
        if (found.isEmpty()) throw new RefusedException(404, "CASE_NOT_FOUND", "No such case");
        if (analysing(found.get())) {
            throw new RefusedException(409, "ANALYSIS_RUNNING", "Case is currently being analysed; detail views are unavailable while a writer is active");
        }
        Optional<PacketCaseFacts> stored;
        try {
            stored = facts.read(request.caseId());
        } catch (IOException e) {
            throw new RefusedException(500, "CASE_DB_READ_FAILED", "Could not read the case analysis database");
        }
        if (stored.isEmpty() || stored.get().report() == null) {
            throw new RefusedException(409, "ANALYSIS_NOT_READY", "No completed analysis report is available for this case");
        }
        DefaultProjection projection = maskedView ? masked.get() : plain.get();
        return new PacketDeviceEventsResponse(maskedView, assemble(request.caseId(), stored.get(), request.findingId(), half, projection, permissions));
    }

    private boolean analysing(CaseRow row) {
        if (ANALYSING.contains(row.state())) return true;
        for (RunRow run : cases.runs(row.caseId())) {
            if (ANALYSING.contains(run.state())) return true;
        }
        return false;
    }

    // ---- assembly over the stored facts ----------------------------------------------------------------------

    /** The body for one case's facts; package-private so the rule is testable without a case database. */
    @SuppressWarnings("unchecked")
    Map<String, Object> assemble(String caseId, PacketCaseFacts stored, String findingId, int half, DefaultProjection p, Permissions permissions) {
        Map<String, Object> report = stored.report();
        Map<String, Object> topology = stored.topology() == null ? Map.of() : stored.topology();
        List<Map<String, Object>> findings = listOfMaps(report.get("findings"));
        List<Map<String, Object>> segments = listOfMaps(report.get("segments"));
        Map<String, Map<String, Object>> segmentById = new LinkedHashMap<>();
        for (Map<String, Object> s : segments) segmentById.put(str(s.get("id")), s);
        Map<String, Map<String, Object>> pointById = new LinkedHashMap<>();
        for (Map<String, Object> pt : listOfMaps(topology.get("points"))) pointById.put(str(pt.get("id")), pt);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("case_id", caseId);
        body.put("masked", p.masked());
        body.put("window_seconds", half);
        body.put("findings", findingChoices(findings, segmentById, pointById, p));

        Map<String, Object> finding = chooseFinding(findings, findingId);
        if (finding == null) {
            body.put("finding", null);
            body.put("hop", null);
            body.put("window", null);
            body.put("devices", List.of());
            body.put("notes", List.of(findings.isEmpty() ? "The stored report holds no finding." : "The stored report holds no finding with a start time."));
            return body;
        }
        Instant start = instant(firstTime(finding));
        Instant from = start.minusSeconds(half);
        Instant to = start.plusSeconds(half);
        Map<String, Object> segment = segmentById.get(str(finding.get("hop")));
        body.put("finding", findingSheet(finding, segment, pointById, p));
        body.put("hop", hopSheet(segment, pointById, p));
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("from", from.toString());
        window.put("to", to.toString());
        window.put("start", start.toString());
        window.put("half_seconds", half);
        body.put("window", window);

        Set<String> hopPoints = new LinkedHashSet<>();
        if (segment != null) {
            if (segment.get("point_a") != null) hopPoints.add(str(segment.get("point_a")));
            if (segment.get("point_b") != null) hopPoints.add(str(segment.get("point_b")));
        }
        Map<String, Map<String, Object>> hopCorrelation = new LinkedHashMap<>();
        boolean correlationStored = stored.correlation() != null;
        if (correlationStored) {
            for (Map<String, Object> h : listOfMaps(stored.correlation().get("hops"))) hopCorrelation.put(str(h.get("point_id")), h);
        }
        Map<String, DeviceSummaryRecord> inventory = new LinkedHashMap<>();
        for (DeviceSummaryRecord d : devices.listAll()) inventory.put(d.deviceId(), d);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<Map<String, Object>> group : pathGroups(topology, pointById)) {
            rows.add(deviceRow(group, hopPoints, hopCorrelation, correlationStored, inventory, from, to, p, permissions));
        }
        body.put("devices", rows);
        List<String> notes = new ArrayList<>();
        notes.add(TIME_BASIS_NOTE);
        notes.add(EVENTS_NOTE);
        if (!correlationStored) notes.add("No inventory correlation is stored for this case; no capture point is linked to a device.");
        if (!chunks.available()) notes.add("SYSTEM_LOG_STORE_UNAVAILABLE: no artefact store is mounted; stored log text cannot be read.");
        body.put("notes", notes);
        return body;
    }

    private static Map<String, Object> chooseFinding(List<Map<String, Object>> findings, String findingId) {
        if (findingId != null && !findingId.isBlank()) {
            for (Map<String, Object> f : findings) {
                if (findingId.equals(str(f.get("id")))) {
                    if (firstTime(f) == null) throw new RefusedException(409, "FINDING_WITHOUT_TIME", "The finding carries no start time");
                    return f;
                }
            }
            throw new RefusedException(404, "FINDING_NOT_FOUND", "No such finding in the stored report");
        }
        for (Map<String, Object> f : findings) {
            if (firstTime(f) != null) return f;
        }
        return null;
    }

    private List<Map<String, Object>> findingChoices(List<Map<String, Object>> findings, Map<String, Map<String, Object>> segments,
            Map<String, Map<String, Object>> points, DefaultProjection p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> f : findings) {
            Double t0 = firstTime(f);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", str(f.get("id")));
            m.put("type", str(f.get("type")));
            m.put("severity", str(f.get("severity")));
            m.put("hop", str(f.get("hop")));
            m.put("hop_label", hopLabel(segments.get(str(f.get("hop"))), points, p));
            m.put("start", t0 == null ? null : instant(t0).toString());
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> findingSheet(Map<String, Object> f, Map<String, Object> segment, Map<String, Map<String, Object>> points, DefaultProjection p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(f.get("id")));
        m.put("type", str(f.get("type")));
        m.put("severity", str(f.get("severity")));
        m.put("confidence", str(f.get("confidence")));
        m.put("hop", str(f.get("hop")));
        m.put("hop_label", hopLabel(segment, points, p));
        m.put("direction", str(f.get("direction")));
        Double t0 = firstTime(f);
        Double t1 = secondTime(f);
        m.put("start", t0 == null ? null : instant(t0).toString());
        m.put("end", t1 == null ? null : instant(t1).toString());
        Map<String, Object> metrics = f.get("metrics") instanceof Map<?, ?> mm ? (Map<String, Object>) mm : Map.of();
        Map<String, Object> figures = new LinkedHashMap<>();
        figures.put("event_count", number(metrics.get("count")));
        figures.put("max_recovery_ms", number(metrics.get("max_recovery_ms")));
        figures.put("p95_ms", segment == null ? null : number(segment.get("p95_ms")));
        figures.put("max_ms", segment == null ? null : number(segment.get("max_ms")));
        figures.put("loss_percent", segment == null ? null : number(segment.get("loss_percent")));
        m.put("figures", figures);
        return m;
    }

    private Map<String, Object> hopSheet(Map<String, Object> segment, Map<String, Map<String, Object>> points, DefaultProjection p) {
        if (segment == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(segment.get("id")));
        m.put("label", hopLabel(segment, points, p));
        m.put("point_a", str(segment.get("point_a")));
        m.put("point_b", str(segment.get("point_b")));
        m.put("direction", str(segment.get("direction")));
        m.put("location", str(segment.get("location")));
        m.put("reason", str(segment.get("reason")));
        return m;
    }

    private String hopLabel(Map<String, Object> segment, Map<String, Map<String, Object>> points, DefaultProjection p) {
        if (segment == null) return null;
        Map<String, Object> a = points.get(str(segment.get("point_a")));
        Map<String, Object> b = points.get(str(segment.get("point_b")));
        String la = a == null ? str(segment.get("point_a")) : pointLabel(a, p);
        String lb = b == null ? str(segment.get("point_b")) : pointLabel(b, p);
        return la + " → " + lb;
    }

    /** Capture points in forward-path order, grouped by the topology's device label (first appearance keeps the order). */
    private static List<List<Map<String, Object>>> pathGroups(Map<String, Object> topology, Map<String, Map<String, Object>> pointById) {
        List<String> order = new ArrayList<>();
        for (Object id : topology.get("forward") instanceof List<?> l ? l : List.of()) {
            if (id instanceof String s && pointById.containsKey(s)) order.add(s);
        }
        for (String id : pointById.keySet()) {
            if (!order.contains(id)) order.add(id);
        }
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (String id : order) {
            Map<String, Object> pt = pointById.get(id);
            String device = str(pt.get("device"));
            groups.computeIfAbsent(device == null || device.isBlank() ? "point:" + id : device, k -> new ArrayList<>()).add(pt);
        }
        return new ArrayList<>(groups.values());
    }

    private Map<String, Object> deviceRow(List<Map<String, Object>> group, Set<String> hopPoints, Map<String, Map<String, Object>> hopCorrelation,
            boolean correlationStored, Map<String, DeviceSummaryRecord> inventory, Instant from, Instant to, DefaultProjection p,
            Permissions permissions) {
        Map<String, Object> first = group.get(0);
        String label = str(first.get("device"));
        String kind = str(first.get("kind"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label == null ? pointLabel(first, p) : deviceLabel(label, p));
        row.put("kind", kind);
        boolean onHop = false;
        List<Map<String, Object>> points = new ArrayList<>();
        for (Map<String, Object> pt : group) {
            String id = str(pt.get("id"));
            boolean hop = hopPoints.contains(id);
            onHop |= hop;
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", id);
            pm.put("label", pointLabel(pt, p));
            pm.put("side", str(pt.get("side")));
            pm.put("on_hop", hop);
            points.add(pm);
        }
        row.put("on_hop", onHop);
        row.put("points", points);

        Link link = resolveLink(group, hopCorrelation, correlationStored, kind, inventory, p);
        row.put("link", link.json());
        if (link.deviceId == null) {
            row.put("result", "NOT_COLLECTED");
            row.put("result_reason", link.reason);
            row.put("event_count", null);
            row.put("events", List.of());
            row.put("sources", List.of());
            row.put("coverage", "NONE");
            return row;
        }
        if (!permissions.systemLogs()) {
            row.put("result", "NOT_PERMITTED");
            row.put("result_reason", "needs system_log_read");
            row.put("event_count", null);
            row.put("events", List.of());
            row.put("sources", List.of());
            row.put("coverage", "NONE");
            return row;
        }
        try {
            windowFacts(row, link.deviceId, from, to, p);
        } catch (RuntimeException e) {
            row.put("result", "UNKNOWN");
            row.put("result_reason", "read failed: " + e.getClass().getSimpleName());
            row.put("event_count", null);
            row.putIfAbsent("events", List.of());
            row.putIfAbsent("sources", List.of());
            row.putIfAbsent("coverage", "NONE");
        }
        return row;
    }

    private record Link(String state, String reason, String deviceId, String deviceName, String vendor, String status) {
        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", state);
            m.put("reason", reason);
            m.put("device_id", deviceId);
            m.put("device_name", deviceName);
            m.put("vendor", vendor);
            m.put("correlation_status", status);
            return m;
        }
    }

    /** The stored correlation of the group's points decides; a label never does. */
    private Link resolveLink(List<Map<String, Object>> group, Map<String, Map<String, Object>> hopCorrelation, boolean correlationStored,
            String kind, Map<String, DeviceSummaryRecord> inventory, DefaultProjection p) {
        if (!correlationStored) {
            return new Link("NOT_LINKED", "device not linked: no inventory correlation is stored for this case", null, null, null, null);
        }
        Set<String> matched = new LinkedHashSet<>();
        String worst = null;
        String reason = null;
        for (Map<String, Object> pt : group) {
            Map<String, Object> h = hopCorrelation.get(str(pt.get("id")));
            if (h == null) continue;
            String status = str(h.get("status"));
            if ("MATCH".equals(status) && h.get("device_id") != null) {
                matched.add(str(h.get("device_id")));
            } else if (worst == null || "AMBIGUOUS".equals(status)) {
                worst = status;
                reason = str(h.get("reason"));
            }
        }
        if (matched.size() > 1) {
            return new Link("NOT_LINKED", "device not linked: the capture points of this label matched different inventory devices", null, null, null, "AMBIGUOUS");
        }
        if (matched.isEmpty()) {
            String why = worst == null ? "device not linked: no correlation row for its capture points"
                    : "device not linked: " + worst + (reason == null ? "" : " (" + reason + ")");
            if (kind != null && !"Firewall".equals(kind)) why = "log not collected: " + kind + " is not a managed firewall; " + why;
            return new Link("NOT_LINKED", why, null, null, null, worst);
        }
        String deviceId = matched.iterator().next();
        DeviceSummaryRecord d = inventory.get(deviceId);
        if (d == null) {
            return new Link("NOT_LINKED", "log not collected: the matched device is not in the neXus inventory", null, null, null, "MATCH");
        }
        if (!MANAGED_VENDORS.contains(d.vendorHint()) || !"gateway".equals(d.role())) {
            return new Link("NOT_LINKED", "log not collected: " + d.role() + " of vendor " + d.vendorHint() + " is not a managed firewall",
                    null, p.device(deviceId), d.vendorHint(), "MATCH");
        }
        return new Link("LINKED", "inventory MATCH", deviceId, p.device(deviceId), d.vendorHint(), "MATCH");
    }

    /** Fills the row with the stored facts of one linked device for the window; throws on a failed read. */
    private void windowFacts(Map<String, Object> row, String deviceId, Instant from, Instant to, DefaultProjection p) {
        List<SourceRow> sources = logs.sources(deviceId);
        if (sources.isEmpty()) {
            row.put("result", "NOT_COLLECTED");
            row.put("result_reason", "no log source has been collected for this device");
            row.put("event_count", null);
            row.put("events", List.of());
            row.put("sources", List.of());
            row.put("coverage", "NONE");
            return;
        }
        Map<String, Map<String, Object>> perSource = new LinkedHashMap<>();
        for (SourceRow s : sources) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source_key", s.sourceKey());
            m.put("last_outcome", s.lastOutcome());
            m.put("last_run_at", s.lastRunAt() == null ? null : s.lastRunAt().toString());
            m.put("chunks_overlapping", 0L);
            m.put("lines_in_overlapping_chunks", 0L);
            m.put("chunks_without_line_time", 0L);
            perSource.put(s.sourceKey(), m);
        }
        boolean covered = false;
        for (String day : daysOf(from, to)) {
            for (ChunkRow c : logs.chunks(deviceId, Optional.empty(), Optional.of(day), 1000)) {
                if (c.prunedAt() != null) continue;
                Map<String, Object> m = perSource.computeIfAbsent(c.sourceKey(), k -> {
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("source_key", k); n.put("last_outcome", null); n.put("last_run_at", null);
                    n.put("chunks_overlapping", 0L); n.put("lines_in_overlapping_chunks", 0L); n.put("chunks_without_line_time", 0L);
                    return n;
                });
                Instant first = deviceText(c.firstLineTime());
                Instant last = deviceText(c.lastLineTime());
                if (first == null && last == null) {
                    m.put("chunks_without_line_time", (Long) m.get("chunks_without_line_time") + 1);
                    continue;
                }
                boolean overlaps = (first == null || !first.isAfter(to)) && (last == null || !last.isBefore(from));
                if (!overlaps) continue;
                covered = true;
                m.put("chunks_overlapping", (Long) m.get("chunks_overlapping") + 1);
                m.put("lines_in_overlapping_chunks", (Long) m.get("lines_in_overlapping_chunks") + c.lines());
            }
        }
        row.put("sources", new ArrayList<>(perSource.values()));
        row.put("coverage", covered ? "COVERED" : "NONE");

        List<Map<String, Object>> events = new ArrayList<>();
        String unknownReason = null;
        // cpwd restart/termination lines: stored chunk text, read through the artefact store.
        if (!chunks.available()) {
            unknownReason = "SYSTEM_LOG_STORE_UNAVAILABLE: no artefact store mounted";
        } else if (!p.textAvailable()) {
            unknownReason = "SYSTEM_LOG_MASKING_UNAVAILABLE: " + p.textUnavailableReason();
        } else {
            List<ChangeFeedRepository.ChunkRow> rows = feed.chunks("cp_cpwd_elg", from, to.plus(CHUNK_SLACK), Optional.of(deviceId), CHUNK_CAP + 1);
            boolean capped = rows.size() > CHUNK_CAP;
            if (capped) rows = rows.subList(0, CHUNK_CAP);
            int unreadable = 0;
            for (ChangeFeedRepository.ChunkRow chunk : rows) {
                try {
                    for (ChangeEvent e : CpwdEvents.parse(chunks.lines(chunk), deviceId, chunk.chunkId(), chunk.collectedAt(), p).events()) {
                        if (e.time().isBefore(from) || e.time().isAfter(to)) continue;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("kind", "CPWD_LINE");
                        m.put("source", "cp_cpwd_elg");
                        m.put("time", e.time().toString());
                        m.put("time_basis", e.timeBasis());
                        m.put("device_time", e.deviceTime());
                        m.put("text", e.what());
                        m.put("chunk_id", chunk.chunkId());
                        events.add(m);
                    }
                } catch (IOException | IllegalStateException e) {
                    unreadable++;
                }
            }
            if (unreadable > 0) unknownReason = unreadable + " cpwd chunk" + (unreadable == 1 ? "" : "s") + " could not be read";
            if (capped) unknownReason = "more than " + CHUNK_CAP + " cpwd chunks in range: not all were read";
        }
        // Crash/dump alerts by the newest file's device mtime.
        for (CrashAlertRow a : logs.crashAlerts(Optional.of(deviceId), 200)) {
            Instant at = Instant.ofEpochSecond(a.newestMtime());
            if (at.isBefore(from) || at.isAfter(to)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "CRASH_ALERT");
            m.put("source", "cp_crash_listing");
            m.put("time", at.toString());
            m.put("time_basis", "DEVICE_MTIME");
            m.put("device_time", null);
            m.put("text", "crash/dump files listed: " + a.process() + " × " + a.fileCount());
            m.put("alert_id", a.alertId());
            events.add(m);
        }
        // Gap markers whose collection window overlaps; a gap of unknown extent recorded right after the window is named too.
        for (GapRow g : logs.gaps(Optional.of(deviceId), 1000)) {
            boolean overlaps;
            Instant at;
            if (g.windowStartEpoch() != null || g.windowEndEpoch() != null) {
                Instant gs = g.windowStartEpoch() == null ? null : Instant.ofEpochSecond(g.windowStartEpoch());
                Instant ge = g.windowEndEpoch() == null ? null : Instant.ofEpochSecond(g.windowEndEpoch());
                overlaps = (gs == null || !gs.isAfter(to)) && (ge == null || !ge.isBefore(from));
                at = gs != null ? gs : ge;
            } else {
                overlaps = g.extentUnknown() && g.createdAt() != null && !g.createdAt().isBefore(from) && !g.createdAt().isAfter(to.plus(CHUNK_SLACK));
                at = g.createdAt();
            }
            if (!overlaps) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "GAP");
            m.put("source", g.sourceKey());
            m.put("time", at == null ? null : at.toString());
            m.put("time_basis", g.windowStartEpoch() != null || g.windowEndEpoch() != null ? "DEVICE_EPOCH" : "UTC");
            m.put("device_time", null);
            m.put("text", "collection gap " + g.kind() + (g.extentUnknown() ? " (extent unknown)" : " (" + g.knownLostBytes() + " bytes known lost)"));
            m.put("gap_id", g.gapId());
            events.add(m);
        }
        events.sort(Comparator.comparing((Map<String, Object> m) -> m.get("time") == null ? Instant.MIN : Instant.parse(str(m.get("time")))));
        row.put("events", events);
        row.put("event_count", events.size());
        if (!events.isEmpty()) {
            row.put("result", "EVENTS");
            row.put("result_reason", unknownReason);
        } else if (unknownReason != null) {
            row.put("result", "UNKNOWN");
            row.put("result_reason", unknownReason);
            row.put("event_count", null);
        } else if (!covered) {
            row.put("result", "NOT_COLLECTED");
            row.put("result_reason", "no stored log line of any source overlaps the window");
        } else {
            row.put("result", "CLEAN");
            row.put("result_reason", null);
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private String pointLabel(Map<String, Object> pt, DefaultProjection p) {
        String label = str(pt.get("label"));
        if (label == null || label.isBlank()) label = str(pt.get("id"));
        return p.masked() ? names.maskPolicyName("packet_point", label) : label;
    }

    private String deviceLabel(String label, DefaultProjection p) {
        return p.masked() ? names.maskDeviceName(label, null) : label;
    }

    /** The Europe/Istanbul log days a window touches (one or two). */
    static List<String> daysOf(Instant from, Instant to) {
        LocalDate a = from.atZone(DEVICE_TEXT_ZONE).toLocalDate();
        LocalDate b = to.atZone(DEVICE_TEXT_ZONE).toLocalDate();
        List<String> days = new ArrayList<>();
        for (LocalDate d = a; !d.isAfter(b); d = d.plusDays(1)) days.add(d.toString());
        return days;
    }

    /** A stored {@code yyyy-MM-dd HH:mm:ss} device-local text as an ordering instant; null when absent or unparseable. */
    static Instant deviceText(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDateTime.parse(text.strip().replace(' ', 'T')).atZone(DEVICE_TEXT_ZONE).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static Instant instant(double epochSeconds) {
        return Instant.ofEpochMilli(Math.round(epochSeconds * 1000.0));
    }

    @SuppressWarnings("unchecked")
    private static Double firstTime(Map<String, Object> f) {
        return f.get("time_range") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Number n ? n.doubleValue() : null;
    }

    private static Double secondTime(Map<String, Object> f) {
        return f.get("time_range") instanceof List<?> l && l.size() > 1 && l.get(1) instanceof Number n ? n.doubleValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> l) {
            for (Object item : l) {
                if (item instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Number number(Object value) {
        return value instanceof Number n ? n : null;
    }
}
