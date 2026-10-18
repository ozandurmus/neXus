package com.securityexpert.nexus.ui2.worker.packetanalysis;

import com.securityexpert.nexus.ui2.packetanalysis.engine.CaseRunner;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Production {@link PacketAnalysisEngine} implementation that runs the Java engine ({@code ui2/packet-analysis}:
 * ingest -> case setup / auto-assemble -> stage graph engine) on the case directory, writing {@code project.duckdb},
 * the full report JSON, and the v11 {@code FindingsExport} ({@code findings-v11.json}) alongside {@code report.json}.
 */
public final class RealPacketAnalysisEngine implements PacketAnalysisEngine {

    @Override
    public String version() {
        return Engine.VERSION_ID;
    }

    @Override
    public Result analyze(Request request) throws Exception {
        List<CaseRunner.CaptureSpec> specs = new ArrayList<>();
        long totalBytes = 0L;
        for (Capture capture : request.captures()) {
            specs.add(new CaseRunner.CaptureSpec(capture.captureId(), capture.displayName(), capture.file()));
            totalBytes += capture.sizeBytes();
        }
        CaseRunner.RunOutcome outcome = CaseRunner.runCase(
                request.caseDir(), specs, request.options().payloadZeroing());

        Path runDir = request.caseDir().resolve("runs").resolve(request.jobId());
        Files.createDirectories(runDir);
        Path exportPath = runDir.resolve("findings-v11.json");
        Path exportPartial = runDir.resolve(".findings-v11.json.partial");
        String exportJson = PyJsonWriter.dumps(outcome.findingsExport(), false);
        Files.writeString(exportPartial, exportJson, StandardCharsets.UTF_8);
        Files.move(exportPartial, exportPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        Map<String, Object> report = outcome.report();
        String rawVerdict = report.get("verdict") instanceof String s ? s : "Inconclusive";
        String safeVerdict = sanitizeVerdictForSummary(rawVerdict, report);
        long findingCount = report.get("finding_count") instanceof Number n ? n.longValue() : 0L;
        long flowCount = report.get("flow_count") instanceof Number n ? n.longValue() : 0L;

        List<Map<String, Object>> stages = List.of(
                stageEntry("ingest", "completed", null),
                stageEntry("core_flow_loss", "completed", null),
                stageEntry("topology", "completed", null),
                stageEntry("path_integrity", "completed", null),
                stageEntry("application_security_hints", "completed", null),
                stageEntry("nexus_correlation", "unknown", "no estate correlation input attached to standalone case")
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("verdict", safeVerdict);
        summary.put("reason", null);
        summary.put("stages", stages);
        summary.put("capture_count", request.captures().size());
        summary.put("total_bytes", totalBytes);
        summary.put("finding_count", findingCount);
        summary.put("flow_count", flowCount);
        summary.put("schema_version", 11);
        // A window the budget guard chose is part of the run (numbers and fixed words only); absent within budget.
        if (report.get("analysis_window") instanceof Map<?, ?> window) {
            summary.put("analysis_window", analysisWindow(window));
        }

        String reportJson = PyJsonWriter.dumps(report, false);
        return new Result(Engine.VERSION_ID, reportJson, summary);
    }

    /** The masked projection of the guard's decision: mode, reason, window bounds and the budget. */
    static Map<String, Object> analysisWindow(Map<?, ?> window) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("mode", "reason", "start", "end", "seconds", "range_seconds",
                "range_max_packets_per_capture", "window_max_packets_per_capture", "window_captures")) {
            out.put(key, window.get(key));
        }
        if (window.get("budget") instanceof Map<?, ?> budget) {
            out.put("max_packets_per_capture", budget.get("max_packets_per_capture"));
        }
        return out;
    }

    private static Map<String, Object> stageEntry(String stage, String verdict, String reason) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("stage", stage);
        entry.put("verdict", verdict);
        entry.put("reason", reason);
        return entry;
    }

    /**
     * Ensures the PostgreSQL-persisted {@code maskedSummary} never carries capture filenames or raw device labels
     * even when {@code leadDegradation} promotes a headline into {@code report.verdict}.
     */
    @SuppressWarnings("unchecked")
    private static String sanitizeVerdictForSummary(String rawVerdict, Map<String, Object> report) {
        List<String> safeConstantVerdicts = List.of(
                "No supported network loss in the selected window",
                "Loss observed",
                "Impactful loss observed",
                "Confirmed device drops observed",
                "Handshake failure observed",
                "Path integrity findings observed",
                "Inconclusive",
                "Host receive/transmit path delay observed; application timing reported separately",
                "Security heuristic patterns observed; interpretation required"
        );
        if (safeConstantVerdicts.contains(rawVerdict)) {
            return rawVerdict;
        }
        List<Map<String, Object>> findings = (List<Map<String, Object>>) report.getOrDefault("findings", List.of());
        if (!findings.isEmpty()) {
            Object type = findings.get(0).get("type");
            if (type instanceof String s && !s.isBlank()) {
                return "Supported finding: " + s.replace('_', ' ');
            }
        }
        return "Evaluated";
    }
}
