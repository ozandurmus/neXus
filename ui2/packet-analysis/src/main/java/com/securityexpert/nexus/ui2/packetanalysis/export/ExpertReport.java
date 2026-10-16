package com.securityexpert.nexus.ui2.packetanalysis.export;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

/**
 * The Packet Analysis expert report: an Excel workbook (Özet, Zaman Çizelgesi, Hop Analizi, Örnek Olaylar, Yöntem ve
 * Veri) generated deterministically from a finished case database. The same case and options give the same bytes,
 * whatever the DuckDB thread count. No free text is generated: findings are fixed templates filled with computed values
 * ({@link ExpertFindings}), and the report states numbers, never a verdict.
 */
public final class ExpertReport {
    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private ExpertReport() {
    }

    /**
     * Builds the workbook from an open case database.
     *
     * @throws IllegalArgumentException when the case has no analysis yet, or the explicit window lies outside the
     *                                  analysed capture range
     */
    public static byte[] generate(Db db, ExpertReportOptions options) {
        ExpertReportOptions effective = options == null ? ExpertReportOptions.defaults() : options;
        return ExpertWorkbook.render(ExpertReportBuilder.build(db, effective));
    }

    /** As {@link #generate(Db, ExpertReportOptions)} on a project directory or {@code .duckdb} file, opened read-only. */
    public static byte[] generate(Path project, ExpertReportOptions options) {
        return generate(project, options, null);
    }

    public static byte[] generate(Path project, ExpertReportOptions options, Integer threads) {
        Path dbFile = Files.isDirectory(project) ? project.resolve("project.duckdb") : project;
        if (!Files.isRegularFile(dbFile)) {
            throw new IllegalArgumentException("No analysis database at " + dbFile);
        }
        try (Db db = new Db(threads == null ? Engine.openReadOnly(dbFile) : Engine.openReadOnly(dbFile, threads))) {
            return generate(db, options);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open DuckDB project", e);
        }
    }
}
