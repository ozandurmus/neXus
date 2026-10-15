package com.securityexpert.nexus.ui2.worker.packetanalysis;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The seam between the product shell and the analysis engine (PACKET_ANALYSIS_PORT_CONTRACT.md §2: the engine is
 * the plain-Java module {@code ui2/packet-analysis}, ported stage by stage under the parity rule). The shell hands
 * over one case directory and its captures; the engine returns the full report and a masked summary.
 *
 * <p>Contract for implementations: the engine is the case directory's only writer (DuckDB is single-writer); it
 * makes no outbound call and no device contact; a stage it cannot evaluate reports {@code unknown} with a reason,
 * never vanishes; and the masked summary carries no address, host name, file name or payload byte -- it is what the
 * replay viewer (aiview) sees.</p>
 */
public interface PacketAnalysisEngine {

    /** One uploaded capture, by its generated id and its location on the analysis volume. */
    record Capture(String captureId, String displayName, String format, long sizeBytes, String sha256, Path file) {
    }

    /** Case options chosen at creation. */
    record Options(boolean payloadZeroing) {
    }

    record Request(String caseId, String jobId, Path caseDir, List<Capture> captures, Options options) {
    }

    /**
     * @param reportJson    the full report, JSON object text (stored on the analysis volume, never in PostgreSQL)
     * @param maskedSummary the masked projection, stored with the run and shown to every reader role
     */
    record Result(String engineVersion, String reportJson, Map<String, Object> maskedSummary) {
    }

    String version();

    Result analyze(Request request) throws Exception;
}
