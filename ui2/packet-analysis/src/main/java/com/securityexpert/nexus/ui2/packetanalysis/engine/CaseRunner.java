package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.BatchIngest;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.Cancellation;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.IngestOptions;
import com.securityexpert.nexus.ui2.packetanalysis.store.AnalysisFailure;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import com.securityexpert.nexus.ui2.packetanalysis.store.SpillGuard;
import com.securityexpert.nexus.ui2.packetanalysis.topology.AnalysisWindowGuard;
import com.securityexpert.nexus.ui2.packetanalysis.topology.Assemble;
import com.securityexpert.nexus.ui2.packetanalysis.topology.Point;
import com.securityexpert.nexus.ui2.packetanalysis.topology.ProjectDb;
import com.securityexpert.nexus.ui2.packetanalysis.topology.Py;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * End-to-end case runner on a case directory ({@code project.duckdb}): ingests captures via {@link BatchIngest},
 * scaffolds topology points and auto-assembly via {@link Assemble}, runs {@link Engine#analyze}, pre-materializes
 * {@link Transport} and {@link Dns} tables so read-only connections never require DDL, and builds the v11
 * {@code FindingsExport}.
 */
public final class CaseRunner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CASE_NOTE = "File names do not establish path order, device identity or client direction. "
            + "Draw the path and assign ingress/egress explicitly.";

    public record CaptureSpec(String captureId, String displayName, Path filePath) {
    }

    public record RunOutcome(Map<String, Object> report, Map<String, Object> findingsExport) {
    }

    private CaseRunner() {
    }

    /**
     * Ingests, assembles and analyses the case. The spill of the case directory is watched while it runs
     * ({@link SpillGuard}): a run that spills past the hard limit ends as {@code ANALYSIS_MEMORY_LIMIT}.
     */
    public static RunOutcome runCase(Path caseDir, List<CaptureSpec> captures, boolean payloadZeroing) {
        return SpillGuard.run(caseDir, () -> runCaseUnguarded(caseDir, captures, payloadZeroing));
    }

    @SuppressWarnings("unchecked")
    private static RunOutcome runCaseUnguarded(Path caseDir, List<CaptureSpec> captures, boolean payloadZeroing) {
        ProjectStore store = ProjectStore.open(caseDir, ProjectStore.Settings.defaults(null));
        Path realCaseDir = store.path();
        List<Path> paths = new ArrayList<>();
        for (CaptureSpec spec : captures) {
            paths.add(spec.filePath());
        }
        if (!paths.isEmpty()) {
            int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
            long ram = Math.max(4L * 1024L * 1024L * 1024L, store.settings().totalMemoryBytes());
            BatchIngest.Settings ingestSettings = new BatchIngest.Settings(
                    IngestOptions.defaults(),
                    false,
                    1,
                    true,
                    cores,
                    ram);
            List<String> captureIds = BatchIngest.ingestMany(
                    store, paths, ingestSettings, Cancellation.NONE, null, null);
            try (com.securityexpert.nexus.ui2.packetanalysis.store.Db storeDb = store.connect()) {
                for (int i = 0; i < Math.min(captures.size(), captureIds.size()); i++) {
                    String cid = captureIds.get(i);
                    String displayName = captures.get(i).displayName();
                    if (cid != null && displayName != null && !displayName.isBlank()) {
                        storeDb.execute("UPDATE captures SET name=? WHERE id=?", displayName, cid);
                    }
                }
                if (payloadZeroing) {
                    storeDb.execute("UPDATE packets SET prefix=''");
                }
            }
        }

        ProjectDb project = new ProjectDb(realCaseDir);
        List<Map<String, Object>> inventory = project.inventory();
        Map<String, Object> prefilterByFile = new LinkedHashMap<>();
        List<Object> summaryFiles = new ArrayList<>();
        List<Map<String, Object>> readyCaptures = new ArrayList<>();
        for (Map<String, Object> c : inventory) {
            if ("ready".equals(c.get("state"))) {
                readyCaptures.add(c);
            }
            Map<String, Object> inv = (Map<String, Object>) c.get("inventory");
            prefilterByFile.put((String) c.get("name"),
                    inv.containsKey("prefilter_excluded") ? inv.get("prefilter_excluded") : Py.i(0));
            Map<String, Object> sf = new LinkedHashMap<>();
            sf.put("file", c.get("name"));
            sf.put("packet_count", inv.containsKey("packet_count") ? inv.get("packet_count") : Py.i(0));
            sf.put("prefilter_total", inv.get("prefilter_total"));
            sf.put("prefilter_retained", inv.get("prefilter_retained"));
            sf.put("prefilter_excluded", inv.containsKey("prefilter_excluded") ? inv.get("prefilter_excluded") : Py.i(0));
            sf.put("prefilter_skipped", Py.truthy(inv.get("prefilter_skipped")));
            summaryFiles.add(sf);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("file_count", Py.i(inventory.size()));
        summary.put("prefilter_excluded", prefilterByFile);
        summary.put("files", summaryFiles);

        Object saved;
        com.securityexpert.nexus.ui2.packetanalysis.topology.Topology topology;
        try (ProjectDb.Db db = project.connect()) {
            saved = db.get("topology", null);
            topology = Py.truthy(saved)
                    ? com.securityexpert.nexus.ui2.packetanalysis.topology.Topology.validate(saved)
                    : new com.securityexpert.nexus.ui2.packetanalysis.topology.Topology();
            Set<String> assigned = new HashSet<>();
            for (Point p : topology.points) {
                assigned.add(p.captureId);
            }
            for (Map<String, Object> capture : inventory) {
                String cid = (String) capture.get("id");
                if (!assigned.contains(cid) && topology.points.size() < 64) {
                    int n = topology.points.size();
                    String label = Py.prefix((String) capture.get("name"), 100);
                    topology.points.add(Point.of("p_" + cid, label, label, cid,
                            Py.i((n % 4) * 240L), Py.i((n / 4) * 180L)));
                }
            }
        }
        AnalysisWindowGuard.apply(project, topology, AnalysisWindowGuard.Budget.fromSystemProperties());
        try (ProjectDb.Db db = project.connect()) {
            db.set("topology", topology.dump());
        }

        Assemble.Analyzer analyzer = (proj, rawTop) -> runEngineOnTopology(realCaseDir, rawTop);
        Map<String, Object> report = null;
        Object suggested = null;
        boolean savedHasPath = Py.truthy(saved) && Py.truthy(((Map<?, ?>) saved).get("forward"))
                && ((List<?>) ((Map<?, ?>) saved).get("forward")).size() >= 2;
        if (!savedHasPath) {
            Map<String, Object> assembled = Assemble.assembleAndAnalyze(project, topology, analyzer);
            suggested = assembled.get("suggested_topology");
            report = (Map<String, Object>) assembled.get("report");
        } else {
            report = analyzer.analyze(project, topology.dump());
        }

        if (report == null && readyCaptures.size() >= 2) {
            com.securityexpert.nexus.ui2.packetanalysis.topology.Topology fallback = topology.copy();
            List<String> fwd = new ArrayList<>();
            for (Point p : fallback.points) {
                boolean isReady = readyCaptures.stream().anyMatch(c -> p.captureId.equals(c.get("id")));
                if (isReady) {
                    fwd.add(p.id);
                }
            }
            if (fwd.size() >= 2) {
                fallback.forward = fwd;
                List<String> rev = new ArrayList<>(fwd);
                Collections.reverse(rev);
                fallback.reverse = rev;
                report = runEngineOnTopology(realCaseDir, fallback.dump());
                if (suggested != null) {
                    report.put("suggested_topology", suggested);
                }
                try (ProjectDb.Db db = project.connect()) {
                    db.set("topology", fallback.dump());
                    db.set("report", report);
                }
            }
        }

        if (report == null) {
            report = buildInconclusiveSingleCaptureReport(topology.dump(), suggested);
            try (ProjectDb.Db db = project.connect()) {
                db.set("export_findings", List.of());
                db.set("report", report);
            }
        }

        try (ProjectDb.Db db = project.connect()) {
            if (suggested == null) {
                suggested = db.get("suggested_topology", null);
            }
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("folder", realCaseDir.toString());
            context.put("files", Py.i(inventory.size()));
            context.put("warning", null);
            context.put("prefilter", true);
            context.put("prefilter_excluded", prefilterByFile);
            context.put("summary", summary);
            context.put("suggested_topology", suggested);
            context.put("issues", List.of());
            context.put("note", CASE_NOTE);
            db.set("case_context", context);
        }

        Path dbPath = realCaseDir.resolve("project.duckdb");
        try (Connection conn = Engine.open(dbPath);
             Db engineDb = new Db(conn)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> storedReport = (Map<String, Object>) CaseQueries.settingGet(engineDb, "report");
            Map<String, Object> finalReport = toStandardMap(storedReport != null ? storedReport : report);
            Map<String, Object> findingsExport = toStandardMap(prepareAndExport(engineDb));
            return new RunOutcome(finalReport, findingsExport);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to finalize project.duckdb: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> runEngineOnTopology(Path caseDir, Object rawTopology) {
        Map<String, Object> topMap = rawTopology instanceof com.securityexpert.nexus.ui2.packetanalysis.topology.Topology t
                ? t.dump()
                : (Map<String, Object>) rawTopology;
        Topology engineTop;
        try {
            engineTop = Topology.parse(MAPPER.readTree(PyJsonWriter.dumps(topMap, false)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path dbPath = caseDir.resolve("project.duckdb");
        try (Connection conn = Engine.open(dbPath);
             Db db = new Db(conn)) {
            Map<String, Object> report = Engine.analyze(db, engineTop, null);
            recordGuardedWindow(db, report);
            String reportJson = PyJsonWriter.dumps(report, false);
            db.exec("INSERT OR REPLACE INTO settings VALUES ('topology', ?)", PyJsonWriter.dumps(topMap, false));
            db.exec("INSERT OR REPLACE INTO settings VALUES ('report', ?)", reportJson);
            Transport.prepare(db, engineTop, report);
            Transport.exportProfiles(db, engineTop, report, 200);
            Dns.prepare(db, engineTop, report);
            return (Map<String, Object>) com.securityexpert.nexus.ui2.packetanalysis.topology.PyJson.loads(reportJson);
        } catch (SQLException e) {
            throw AnalysisFailure.classify(new IllegalStateException("Engine DuckDB failure: " + e.getMessage(), e));
        } catch (RuntimeException e) {
            throw AnalysisFailure.classify(e);
        }
    }

    /**
     * A window the guard chose is part of the result the reader sees: {@code analysis_window} (mode, window, reason
     * {@code budget}, the budget) is added to the report when the analysed window is the stored guarded one. Absent
     * otherwise, so a run within budget has the report it always had.
     */
    @SuppressWarnings("unchecked")
    static void recordGuardedWindow(Db db, Map<String, Object> report) {
        Object stored = CaseQueries.settingGet(db, AnalysisWindowGuard.SETTING);
        Object window = report.get("window");
        if (stored instanceof Map<?, ?> m && "guarded".equals(m.get("mode")) && window instanceof Map<?, ?> w
                && m.get("start") instanceof Number s && m.get("end") instanceof Number e
                && w.get("start") instanceof Number ws && w.get("end") instanceof Number we
                && s.doubleValue() == ws.doubleValue() && e.doubleValue() == we.doubleValue()) {
            report.put("analysis_window", new LinkedHashMap<>((Map<String, Object>) m));
        }
    }

    public static Map<String, Object> saveTopologyAndAnalyze(Path caseDir, Map<String, Object> rawTopology) {
        return SpillGuard.run(caseDir, () -> saveTopologyAndAnalyzeUnguarded(caseDir, rawTopology));
    }

    private static Map<String, Object> saveTopologyAndAnalyzeUnguarded(Path caseDir, Map<String, Object> rawTopology) {
        Object pyRaw = com.securityexpert.nexus.ui2.packetanalysis.topology.PyJson.loads(
                PyJsonWriter.dumps(rawTopology, false));
        com.securityexpert.nexus.ui2.packetanalysis.topology.Topology validated =
                com.securityexpert.nexus.ui2.packetanalysis.topology.Topology.validate(pyRaw);
        ProjectDb project = new ProjectDb(caseDir);
        if (validated.forward.size() >= 2) {
            AnalysisWindowGuard.apply(project, validated, AnalysisWindowGuard.Budget.fromSystemProperties());
        }
        Map<String, Object> dumped = validated.dump();
        try (ProjectDb.Db db = project.connect()) {
            db.set("topology", dumped);
        }
        if (validated.forward.size() >= 2) {
            runEngineOnTopology(caseDir, dumped);
        }
        try (Connection conn = Engine.openReadOnly(caseDir.resolve("project.duckdb"));
             Db db = new Db(conn)) {
            return toStandardMap(CaseQueries.state(db, caseDir.getFileName().toString()));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read updated state: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toStandardMap(Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        try {
            return MAPPER.readValue(PyJsonWriter.dumps(input, false), LinkedHashMap.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildInconclusiveSingleCaptureReport(
            Map<String, Object> topology, Object suggested) {
        String now = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("schema_version", 2L);
        r.put("prefilter_excluded", Map.of());
        r.put("analysis_scope", List.of());
        r.put("tunnel_points", List.of());
        r.put("fragment_points", List.of());
        r.put("microbursts", Map.of("note", "At least two capture points in a forward path are required",
                "window_ms", 10L, "total", 0L, "points", List.of(), "items", List.of()));
        r.put("sessions", Map.of("note", "At least two capture points in a forward path are required",
                "scope", "unknown", "timeout_seconds", 120L, "points", List.of(), "buckets", List.of(),
                "failed_handshake_count", 0L, "failed_handshakes", List.of()));
        r.put("session_events", Map.of("note", "At least two capture points in a forward path are required",
                "scope", "unknown", "lifecycle", List.of(), "resets", List.of(), "storms", List.of(),
                "flows_by_volume", List.of()));
        r.put("security", Map.of("note", "At least two capture points in a forward path are required",
                "thresholds", Map.of(), "quality", List.of(), "items", List.of()));
        r.put("udp", Map.of("note", "At least two capture points in a forward path are required",
                "scope", "unknown", "total", 0L, "summary", List.of(), "items", List.of()));
        r.put("rtp", Map.of("note", "At least two capture points in a forward path are required",
                "scope", "unknown", "streams", List.of(), "one_way", List.of()));
        r.put("sip", Map.of("note", "At least two capture points in a forward path are required",
                "scope", "unknown", "total", 0L, "comparisons", List.of(), "one_way", List.of()));
        r.put("host_path", Map.of("note", "At least two capture points in a forward path are required",
                "scope", "unknown", "local_service", List.of(), "items", List.of(), "streams", List.of(), "series", List.of()));
        r.put("cycles", Map.of("note", "At least two capture points in a forward path are required",
                "scope", "unknown", "stall_ms", 200L, "series", List.of(), "flow_ports", List.of(),
                "periods", List.of(), "pairs", List.of(), "top", List.of()));
        Map<String, Object> incidentWindow = new LinkedHashMap<>();
        incidentWindow.put("start", null);
        incidentWindow.put("end", null);
        incidentWindow.put("bucket_seconds", 1.0);
        r.put("incident", Map.of("window", incidentWindow,
                "headline", "At least two capture points in a forward path are required",
                "loss_groups", List.of(), "timeline", List.of(), "cycle_bars", List.of(),
                "hold_release", List.of(), "delivery", List.of(), "quality", List.of(), "quality_note", ""));
        r.put("delivery", Map.of("note", "At least two capture points in a forward path are required",
                "scope", "unknown", "series", List.of(), "items", List.of()));
        r.put("application_status", List.of());
        r.put("proxies", Map.of("note", "At least two capture points in a forward path are required",
                "transaction_count", 0L, "transactions", List.of(), "legs", List.of(), "reset_notes", List.of()));
        r.put("tls", List.of());
        r.put("integrity_notes", List.of());
        r.put("f5", Map.of("note", "At least two capture points in a forward path are required",
                "connection_count", 0L, "pairs", List.of(), "requests", List.of(), "resets", List.of()));
        r.put("vendor_device_events", List.of());
        r.put("vendor_device_event_count", 0L);
        r.put("confirmed_device_drops", List.of());
        r.put("confirmed_device_drop_count", 0L);
        r.put("sequence_translations", List.of());
        r.put("offload_points", List.of());
        Map<String, Object> ts = new LinkedHashMap<>();
        ts.put("bucket_seconds", 1.0);
        ts.put("start", null);
        ts.put("end", null);
        ts.put("bucket_count", 0L);
        ts.put("metrics", Map.of());
        r.put("timeseries", ts);
        r.put("onsets", Map.of("summary", "At least two capture points in a forward path are required",
                "status", "unknown", "items", List.of(), "directions", Map.of()));
        r.put("engine_version", Engine.VERSION);
        r.put("generated_at", now);
        r.put("verdict", "Inconclusive");
        r.put("scope", "Pilot diagnostics; stage clocks, host boundary delay and evidence-led path analysis");
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("start", null);
        window.put("end", null);
        window.put("common_start", null);
        window.put("common_end", null);
        r.put("window", window);
        r.put("clocks", Map.of());
        r.put("coverage", List.of());
        r.put("quality", List.of());
        r.put("auto_order_suggestion", Map.of("points", List.of(), "scores", List.of(),
                "reason", "At least two capture points in a forward path are required"));
        r.put("nat_suggestions", List.of());
        r.put("segments", List.of());
        r.put("findings", List.of());
        r.put("finding_count", 0L);
        r.put("flow_count", 0L);
        r.put("limitations", CoreReport.LIMITATIONS);
        r.put("unknown_sections", Map.of());
        r.put("engine_notes", List.of("Connect at least two capture points in a forward path"));
        if (suggested != null) {
            r.put("suggested_topology", suggested);
        }
        return (Map<String, Object>) com.securityexpert.nexus.ui2.packetanalysis.topology.PyJson.loads(
                PyJsonWriter.dumps(r, false));
    }

    /**
     * The last step of a run, shared by the service and the end-to-end corpus run: prepares the transport and DNS
     * tables of the stored report, then builds the FindingsExport from the stored report, topology and findings. The
     * export is part of the run's result: a failure fails the run instead of storing an empty export.
     */
    public static Map<String, Object> prepareAndExport(Db engineDb) {
        Topology engineTopology = CaseQueries.loadTopology(engineDb);
        @SuppressWarnings("unchecked")
        Map<String, Object> storedReport = (Map<String, Object>) CaseQueries.settingGet(engineDb, "report");
        if (storedReport != null && !engineTopology.forward.isEmpty()) {
            try {
                Transport.prepare(engineDb, engineTopology, storedReport);
                Transport.exportProfiles(engineDb, engineTopology, storedReport, 200);
                Dns.prepare(engineDb, engineTopology, storedReport);
            } catch (RuntimeException e) {
                // Single-capture inconclusive reports may not have obs/flow_filters tables; a memory limit is not that.
                if (AnalysisFailure.isMemoryLimit(e)) {
                    throw AnalysisFailure.classify(e);
                }
            }
        }
        return CaseQueries.exportData(engineDb, false);
    }
}
