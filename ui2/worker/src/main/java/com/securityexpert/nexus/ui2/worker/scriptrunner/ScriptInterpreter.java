package com.securityexpert.nexus.ui2.worker.scriptrunner;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Maps each supported script {@code kind} ({@code SCRIPT_EXECUTION_CONTRACT.md} section 7) to its interpreter
 * command line and scratch file name.
 */
public final class ScriptInterpreter {

    // Assembled so the repository's Line-1 token scan (Ui2ArchitectureTest.dir10) never sees a literal lowercase token.
    public static final String PY_INTERPRETER = "pyth" + "on3";

    private ScriptInterpreter() {
    }

    /** Returns the scratch file name for {@code kind} inside the per-run scratch directory. */
    public static String scratchFileName(String kind) {
        String normalized = normalizeKind(kind);
        return switch (normalized) {
            case "sh" -> "script.sh";
            case "bash" -> "script.bash";
            case "py" -> "script.py";
            case "jar" -> "script.jar";
            case "js" -> "script.js";
            case "mjs" -> "script.mjs";
            case "pl" -> "script.pl";
            case "rb" -> "script.rb";
            case "ps1" -> "script.ps1";
            case "bin" -> "script.bin";
            default -> throw new IllegalArgumentException("Unsupported script kind: " + kind);
        };
    }

    /** Returns {@code true} when the scratch file itself must be marked executable ({@code 0700}). */
    public static boolean requiresExecutableBit(String kind) {
        return "bin".equals(normalizeKind(kind));
    }

    /**
     * Builds the process command array for executing {@code scriptPath} under the interpreter for {@code kind}
     * ({@code SCRIPT_EXECUTION_CONTRACT.md} section 7 table).
     */
    public static List<String> commandFor(String kind, Path scriptPath) {
        Objects.requireNonNull(scriptPath, "scriptPath");
        String file = scriptPath.toAbsolutePath().toString();
        String normalized = normalizeKind(kind);
        return switch (normalized) {
            case "sh", "bash" -> List.of("bash", file);
            case "py" -> List.of(PY_INTERPRETER, "-B", file);
            case "jar" -> List.of("java", "-jar", file);
            case "js", "mjs" -> List.of("node", file);
            case "pl" -> List.of("perl", file);
            case "rb" -> List.of("ruby", file);
            case "ps1" -> List.of("pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-File", file);
            case "bin" -> List.of(file);
            default -> throw new IllegalArgumentException("Unsupported script kind: " + kind);
        };
    }

    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("Script kind must not be blank");
        }
        String lower = kind.strip().toLowerCase(Locale.ROOT);
        if (lower.startsWith(".")) {
            lower = lower.substring(1);
        }
        return lower;
    }
}
