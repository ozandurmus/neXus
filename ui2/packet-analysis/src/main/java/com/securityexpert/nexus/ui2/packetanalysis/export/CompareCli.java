package com.securityexpert.nexus.ui2.packetanalysis.export;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Evidence;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CLI entry point for {@code packetbreaker compare}:
 * {@code CompareCli [--project <path>] [--report <path>] [--reference <path>] [--against <path>] [--template <path>] [--memory-limit-mb <int>]}.
 */
public final class CompareCli {
    private CompareCli() {
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
        Path reportPath = null;
        Path referencePath = null;
        Path againstPath = null;
        Path templatePath = null;
        Integer memoryLimitMb = null;
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for option " + args[i]);
            }
            switch (args[i]) {
                case "--project" -> projectPath = Path.of(args[i + 1]);
                case "--report" -> reportPath = Path.of(args[i + 1]);
                case "--reference" -> referencePath = Path.of(args[i + 1]);
                case "--against" -> againstPath = Path.of(args[i + 1]);
                case "--template" -> templatePath = Path.of(args[i + 1]);
                case "--memory-limit-mb" -> memoryLimitMb = Integer.valueOf(args[i + 1]);
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        if (referencePath == null && againstPath == null && templatePath == null) {
            throw new IllegalArgumentException("Choose --reference, --against or --template");
        }
        Object savedRaw;
        if (reportPath != null) {
            savedRaw = Evidence.loads(Files.readString(reportPath, StandardCharsets.UTF_8));
        } else {
            if (projectPath == null) {
                throw new IllegalArgumentException("--project or --report is required");
            }
            savedRaw = loadSavedReport(projectPath, memoryLimitMb);
        }
        if (savedRaw == null || (savedRaw instanceof Map<?, ?> m && m.isEmpty())) {
            throw new IllegalArgumentException("Run analysis first");
        }
        Map<String, Object> saved = ReferenceCompare.unwrap(savedRaw);
        if (templatePath != null) {
            String tpl = PyJsonWriter.dumps(ReferenceCompare.referenceTemplate(saved), true);
            Files.writeString(templatePath, tpl, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (referencePath != null) {
            Object refObj = Evidence.loads(Files.readString(referencePath, StandardCharsets.UTF_8));
            result.putAll(ReferenceCompare.compareReference(saved, refObj));
        }
        if (againstPath != null) {
            Object againstObj = Evidence.loads(Files.readString(againstPath, StandardCharsets.UTF_8));
            result.put("diff", ReferenceCompare.compareRuns(againstObj, saved));
        }
        out.println(PyJsonWriter.dumps(result, true));
    }

    private static Object loadSavedReport(Path projectPath, Integer memoryLimitMb) {
        if (Files.isRegularFile(projectPath)) {
            try (Db db = new Db(Engine.open(projectPath))) {
                return Export.getSetting(db, "report");
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Failed to open DuckDB project", e);
            }
        }
        ProjectStore project = ProjectStore.open(projectPath, ProjectStore.Settings.defaults(memoryLimitMb));
        try (var conn = project.connect()) {
            return project.get(conn, "report", null);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read project report setting", e);
        }
    }
}
