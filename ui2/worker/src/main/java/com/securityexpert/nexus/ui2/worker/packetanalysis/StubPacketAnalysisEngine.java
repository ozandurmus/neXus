package com.securityexpert.nexus.ui2.worker.packetanalysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The shell's engine until the port lands: reads nothing from the captures and claims nothing about them. Every
 * stage is {@code unknown} with the reason {@code engine not yet ported} (handover: "detectors not yet ported report
 * unknown, never vanish"), so the end-to-end path -- upload, queue, claim, report, masked summary, retention -- is
 * exercised without inventing a finding.
 */
public final class StubPacketAnalysisEngine implements PacketAnalysisEngine {

    public static final String VERSION = "stub-0";
    public static final String REASON = "engine not yet ported";
    /** The decision record's phases, in order. */
    public static final List<String> STAGES = List.of("ingest", "core_flow_loss", "topology", "path_integrity",
            "application_security_hints", "nexus_correlation");

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Result analyze(Request request) throws JsonProcessingException {
        List<Map<String, Object>> stages = new ArrayList<>();
        for (String stage : STAGES) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("stage", stage);
            entry.put("verdict", "unknown");
            entry.put("reason", REASON);
            stages.add(entry);
        }
        long totalBytes = request.captures().stream().mapToLong(Capture::sizeBytes).sum();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("engine", "stub");
        report.put("engine_version", VERSION);
        report.put("case_id", request.caseId());
        report.put("job_id", request.jobId());
        report.put("verdict", "unknown");
        report.put("reason", REASON);
        report.put("stages", stages);
        List<Map<String, Object>> captures = new ArrayList<>();
        for (Capture capture : request.captures()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("capture_id", capture.captureId());
            entry.put("display_name", capture.displayName());
            entry.put("format", capture.format());
            entry.put("size_bytes", capture.sizeBytes());
            entry.put("sha256", capture.sha256());
            entry.put("findings", List.of());
            captures.add(entry);
        }
        report.put("captures", captures);
        report.put("options", Map.of("payload_zeroing", request.options().payloadZeroing()));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("verdict", "unknown");
        summary.put("reason", REASON);
        summary.put("stages", stages);
        summary.put("capture_count", request.captures().size());
        summary.put("total_bytes", totalBytes);
        summary.put("finding_count", 0);
        return new Result(VERSION, json.writeValueAsString(report), summary);
    }
}
