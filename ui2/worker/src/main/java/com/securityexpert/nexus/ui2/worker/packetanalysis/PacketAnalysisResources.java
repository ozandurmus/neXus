package com.securityexpert.nexus.ui2.worker.packetanalysis;

import com.securityexpert.nexus.ui2.packetanalysis.store.MemoryPlan;
import com.securityexpert.nexus.ui2.packetanalysis.store.SpillGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The analysis worker's resource configuration: the environment variables of the deployment mapped onto the engine's
 * system properties ({@code ui2/packet-analysis/README.md}, "Memory plan"). A property already given on the command
 * line ({@code -D...}) wins. The JVM heap itself is sized by {@code JAVA_TOOL_OPTIONS} in the manifest.
 *
 * <pre>
 *   UI2_PACKET_ANALYSIS_DUCKDB_MEMORY_MB          DuckDB memory_limit, MiB
 *   UI2_PACKET_ANALYSIS_DUCKDB_THREADS            DuckDB threads
 *   UI2_PACKET_ANALYSIS_MAX_TEMP_MB               DuckDB's (soft) spill ceiling on the analysis volume, MiB
 *   UI2_PACKET_ANALYSIS_HARD_TEMP_MB              the hard spill limit enforced by the spill guard, MiB
 *   UI2_PACKET_ANALYSIS_MAX_PACKETS_PER_CAPTURE   window guard: packets per capture before the range is bounded
 *   UI2_PACKET_ANALYSIS_MAX_WINDOW_SECONDS        window guard: seconds before the range is bounded (0 = off)
 * </pre>
 */
public final class PacketAnalysisResources {

    /** Environment variable to engine system property. */
    static final Map<String, String> MAPPING = new LinkedHashMap<>();

    static {
        MAPPING.put("UI2_PACKET_ANALYSIS_DUCKDB_MEMORY_MB", MemoryPlan.MEMORY_LIMIT_PROPERTY);
        MAPPING.put("UI2_PACKET_ANALYSIS_DUCKDB_THREADS", "packetanalysis.duckdb.threads");
        MAPPING.put("UI2_PACKET_ANALYSIS_MAX_TEMP_MB", MemoryPlan.MAX_TEMP_PROPERTY);
        MAPPING.put("UI2_PACKET_ANALYSIS_HARD_TEMP_MB", SpillGuard.HARD_TEMP_PROPERTY);
        MAPPING.put("UI2_PACKET_ANALYSIS_MAX_PACKETS_PER_CAPTURE", "packetanalysis.window.max_packets_per_capture");
        MAPPING.put("UI2_PACKET_ANALYSIS_MAX_WINDOW_SECONDS", "packetanalysis.window.max_seconds");
    }

    private PacketAnalysisResources() {
    }

    /**
     * Sets the engine properties from {@code env}; returns the names it set.
     *
     * @throws IllegalArgumentException for a value that is not a whole number, or not positive where a limit must
     *                                  be (the window limits may be 0: off)
     */
    public static Map<String, String> apply(Map<String, String> env, Properties properties) {
        Map<String, String> applied = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : MAPPING.entrySet()) {
            String value = env.get(e.getKey());
            if (value == null || value.isBlank() || properties.getProperty(e.getValue()) != null) {
                continue;
            }
            long parsed;
            try {
                parsed = Long.parseLong(value.strip());
            } catch (NumberFormatException bad) {
                throw new IllegalArgumentException(e.getKey() + " must be a whole number", bad);
            }
            boolean mayBeZero = e.getKey().endsWith("MAX_WINDOW_SECONDS") || e.getKey().endsWith("MAX_PACKETS_PER_CAPTURE");
            if (parsed < (mayBeZero ? 0 : 1)) {
                throw new IllegalArgumentException(e.getKey() + " must be " + (mayBeZero ? "not negative" : "positive"));
            }
            properties.setProperty(e.getValue(), Long.toString(parsed));
            applied.put(e.getKey(), Long.toString(parsed));
        }
        return applied;
    }

    /** The plan in one line for the startup log: container = heap + DuckDB ceiling + reserve. */
    public static String plan() {
        return MemoryPlan.describe();
    }
}
