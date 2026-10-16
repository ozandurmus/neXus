package com.securityexpert.nexus.ui2.platform;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The analysis volume's layout, shared by the service (writes captures) and the analysis worker (writes run output
 * and, once the engine is ported, the case DuckDB file -- one writer per case). Every path is built from generated
 * ids only; an id that is not an opaque UUID, or a storage key that is not a generated file name, is refused, so no
 * uploaded name or request value ever becomes a path segment.
 *
 * <pre>
 * &lt;root&gt;/cases/&lt;caseId&gt;/captures/&lt;storageKey&gt;      service
 * &lt;root&gt;/cases/&lt;caseId&gt;/telemetry/&lt;storageKey&gt;     service (host telemetry text files)
 * &lt;root&gt;/cases/&lt;caseId&gt;/runs/&lt;jobId&gt;/report.json   analysis worker
 * &lt;root&gt;/cases/&lt;caseId&gt;/case.duckdb                 analysis worker (engine port)
 * </pre>
 */
public final class PacketAnalysisPaths {
    public static final Pattern ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    public static final Pattern STORAGE_KEY = Pattern.compile(ID.pattern() + "\\.(pcap|pcapng)");
    public static final Pattern TELEMETRY_KEY = Pattern.compile(ID.pattern() + "\\.txt");

    private PacketAnalysisPaths() {
    }

    public static boolean isId(String value) {
        return value != null && ID.matcher(value).matches();
    }

    public static Path caseDir(Path root, String caseId) {
        return root.resolve("cases").resolve(requireId(caseId));
    }

    public static Path capturesDir(Path root, String caseId) {
        return caseDir(root, caseId).resolve("captures");
    }

    public static Path captureFile(Path root, String caseId, String storageKey) {
        if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) throw new IllegalArgumentException("STORAGE_KEY_INVALID");
        return capturesDir(root, caseId).resolve(storageKey);
    }

    public static Path telemetryDir(Path root, String caseId) {
        return caseDir(root, caseId).resolve("telemetry");
    }

    public static Path telemetryFile(Path root, String caseId, String storageKey) {
        if (storageKey == null || !TELEMETRY_KEY.matcher(storageKey).matches()) throw new IllegalArgumentException("STORAGE_KEY_INVALID");
        return telemetryDir(root, caseId).resolve(storageKey);
    }

    public static Path runDir(Path root, String caseId, String jobId) {
        return caseDir(root, caseId).resolve("runs").resolve(requireId(jobId));
    }

    public static Path reportFile(Path root, String caseId, String jobId) {
        return runDir(root, caseId, jobId).resolve("report.json");
    }

    public static Path findingsExportFile(Path root, String caseId, String jobId) {
        return runDir(root, caseId, jobId).resolve("findings-v11.json");
    }

    public static Path projectDuckDb(Path root, String caseId) {
        return caseDir(root, caseId).resolve("project.duckdb");
    }

    private static String requireId(String value) {
        if (!isId(value)) throw new IllegalArgumentException("PACKET_ANALYSIS_ID_INVALID");
        return value;
    }
}
