package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.packetanalysis.store.AnalysisFailure;
import com.securityexpert.nexus.ui2.packetanalysis.store.MemoryPlan;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine on a frozen synthetic project under a memory limit it cannot live with: the run stops with an
 * exception that is classified as the memory limit (so the worker records a FAILED run with
 * {@code ANALYSIS_MEMORY_LIMIT}); the same project at the default limit is analysed as before.
 */
class EngineMemoryLimitTest {

    @TempDir
    Path dir;

    @AfterEach
    void clearProperties() {
        System.clearProperty(MemoryPlan.MEMORY_LIMIT_PROPERTY);
    }

    @Test
    void aTinyMemoryLimitStopsTheEngineWithAMemoryLimitFailureNotAnUnclassifiedOne() throws Exception {
        JsonNode fixture = Fixtures.load("healthy");
        Path project = Fixtures.project(fixture, dir, "p.duckdb");
        System.setProperty(MemoryPlan.MEMORY_LIMIT_PROPERTY, "2");
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> Fixtures.run(project, Fixtures.topology(fixture), 1));
        assertTrue(AnalysisFailure.isMemoryLimit(failure), failure.toString());
        assertEquals(AnalysisFailure.MEMORY_LIMIT, AnalysisFailure.reasonOf(failure));
    }

    @Test
    void theSameProjectAtTheDefaultLimitIsAnalysedAndCarriesNoWindowRecord() throws Exception {
        JsonNode fixture = Fixtures.load("healthy");
        Path project = Fixtures.project(fixture, dir, "ok.duckdb");
        JsonNode report = Fixtures.parse(Fixtures.run(project, Fixtures.topology(fixture), 1));
        assertFalse(report.has("analysis_window"), "a run within budget has the report it always had");
        assertTrue(report.has("verdict"));
    }
}
