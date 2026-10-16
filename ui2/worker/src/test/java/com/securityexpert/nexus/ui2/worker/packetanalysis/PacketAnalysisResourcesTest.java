package com.securityexpert.nexus.ui2.worker.packetanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class PacketAnalysisResourcesTest {

    private static final Map<String, String> POD = Map.of(
            "UI2_PACKET_ANALYSIS_DUCKDB_MEMORY_MB", "14336",
            "UI2_PACKET_ANALYSIS_DUCKDB_THREADS", "4",
            "UI2_PACKET_ANALYSIS_MAX_TEMP_MB", "40960",
            "UI2_PACKET_ANALYSIS_HARD_TEMP_MB", "61440",
            "UI2_PACKET_ANALYSIS_MAX_PACKETS_PER_CAPTURE", "150000",
            "UI2_PACKET_ANALYSIS_MAX_WINDOW_SECONDS", "0");

    @Test void theDeploymentEnvironmentBecomesTheEnginesProperties() {
        Properties p = new Properties();
        Map<String, String> applied = PacketAnalysisResources.apply(POD, p);
        assertEquals(6, applied.size());
        assertEquals("14336", p.getProperty("packetanalysis.duckdb.memory_limit_mb"));
        assertEquals("4", p.getProperty("packetanalysis.duckdb.threads"));
        assertEquals("40960", p.getProperty("packetanalysis.duckdb.max_temp_mb"));
        assertEquals("61440", p.getProperty("packetanalysis.duckdb.hard_temp_mb"));
        assertEquals("150000", p.getProperty("packetanalysis.window.max_packets_per_capture"));
        assertEquals("0", p.getProperty("packetanalysis.window.max_seconds"));
    }

    @Test void aPropertyGivenOnTheCommandLineWins() {
        Properties p = new Properties();
        p.setProperty("packetanalysis.duckdb.memory_limit_mb", "2048");
        PacketAnalysisResources.apply(POD, p);
        assertEquals("2048", p.getProperty("packetanalysis.duckdb.memory_limit_mb"));
    }

    @Test void nothingSetMeansNothingApplied() {
        Properties p = new Properties();
        assertEquals(0, PacketAnalysisResources.apply(Map.of(), p).size());
        assertNull(p.getProperty("packetanalysis.duckdb.memory_limit_mb"));
    }

    @Test void aValueThatIsNotAWholeNumberOrIsNotPositiveRefusesToStart() {
        assertThrows(IllegalArgumentException.class, () -> PacketAnalysisResources.apply(
                Map.of("UI2_PACKET_ANALYSIS_DUCKDB_MEMORY_MB", "14g"), new Properties()));
        assertThrows(IllegalArgumentException.class, () -> PacketAnalysisResources.apply(
                Map.of("UI2_PACKET_ANALYSIS_DUCKDB_MEMORY_MB", "0"), new Properties()));
        assertThrows(IllegalArgumentException.class, () -> PacketAnalysisResources.apply(
                Map.of("UI2_PACKET_ANALYSIS_MAX_WINDOW_SECONDS", "-1"), new Properties()));
    }
}
