package com.securityexpert.nexus.ui2.packetanalysis.export;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * CLI entry point for {@code packetbreaker export}:
 * {@code ExportCli --project <path> --format <html|json|csv|xlsx> [--output|-o <path>] [--memory-limit-mb <int>]}.
 *
 * <p>Accepts either a project directory (containing {@code project.duckdb}) or a direct {@code .duckdb} file path.
 */
public final class ExportCli {
    private ExportCli() {
    }

    public static void main(String[] args) {
        try {
            run(System.out, args);
        } catch (Exception e) {
            System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            System.exit(1);
        }
    }

    public static void run(PrintStream out, String... args) throws IOException {
        Path projectPath = null;
        String format = null;
        Path outputPath = null;
        Integer memoryLimitMb = null;
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for option " + args[i]);
            }
            switch (args[i]) {
                case "--project" -> projectPath = Path.of(args[i + 1]);
                case "--format" -> format = args[i + 1];
                case "--output", "-o" -> outputPath = Path.of(args[i + 1]);
                case "--memory-limit-mb" -> memoryLimitMb = Integer.valueOf(args[i + 1]);
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        if (projectPath == null) {
            throw new IllegalArgumentException("--project is required");
        }
        if (format == null) {
            throw new IllegalArgumentException("--format is required");
        }
        switch (format) {
            case "csv", "xlsx" -> {
                if (outputPath == null) {
                    throw new IllegalArgumentException(
                            "CSV/XLSX engine exports require --output (CSV is a ZIP of tables)");
                }
                byte[] content = exportTables(projectPath, memoryLimitMb, format);
                Files.write(outputPath, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                out.println("Exported " + outputPath);
            }
            case "html", "json" -> {
                String content = exportReport(projectPath, memoryLimitMb, format);
                if (outputPath != null) {
                    Files.writeString(outputPath, content, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    out.println("Exported " + outputPath);
                } else {
                    out.print(content);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported format: " + format);
        }
    }

    private static byte[] exportTables(Path projectPath, Integer memoryLimitMb, String format) {
        if (Files.isRegularFile(projectPath)) {
            try (Db db = new Db(Engine.open(projectPath))) {
                return EngineExport.exportTables(db, format);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Failed to open DuckDB project", e);
            }
        }
        ProjectStore project = ProjectStore.open(projectPath, ProjectStore.Settings.defaults(memoryLimitMb));
        return EngineExport.exportTables(project, format);
    }

    private static String exportReport(Path projectPath, Integer memoryLimitMb, String format) {
        if (Files.isRegularFile(projectPath)) {
            try (Db db = new Db(Engine.open(projectPath))) {
                return Export.exportReport(db, format);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Failed to open DuckDB project", e);
            }
        }
        ProjectStore project = ProjectStore.open(projectPath, ProjectStore.Settings.defaults(memoryLimitMb));
        return Export.exportReport(project, format);
    }
}
