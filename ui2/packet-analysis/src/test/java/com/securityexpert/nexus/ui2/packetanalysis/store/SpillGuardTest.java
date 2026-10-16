package com.securityexpert.nexus.ui2.packetanalysis.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DuckDB's {@code max_temp_directory_size} is a soft bound (several times exceeded in measurements); the guard is the
 * hard one: past its limit the run's connections are interrupted and the run ends as a memory-limit failure.
 */
class SpillGuardTest {

    private static final long GIB = 1024L * 1024 * 1024;

    @TempDir
    Path dir;

    @AfterEach
    void clearProperties() {
        System.clearProperty(SpillGuard.HARD_TEMP_PROPERTY);
        System.clearProperty(SpillGuard.POLL_PROPERTY);
        System.clearProperty(MemoryPlan.MAX_TEMP_PROPERTY);
    }

    @Test
    void aRunThatSpillsPastTheHardLimitIsInterruptedAndEndsAsAMemoryLimitFailure() throws Exception {
        // DuckDB's own spill ceiling is the smallest it takes (256 MiB) and it does not hold: the sort writes far
        // more. The guard's hard limit is just above it.
        System.setProperty(MemoryPlan.MAX_TEMP_PROPERTY, "256");
        System.setProperty(SpillGuard.HARD_TEMP_PROPERTY, "300");
        System.setProperty(SpillGuard.POLL_PROPERTY, "50");
        Path caseDir = dir.resolve("case");
        ProjectStore store = ProjectStore.open(caseDir, new ProjectStore.Settings(64, 8 * GIB, 2));
        AnalysisLimitException e = assertThrows(AnalysisLimitException.class, () -> SpillGuard.run(caseDir, () -> {
            try (Db db = store.connect()) {
                db.query("SELECT count(*) FROM (SELECT i, md5(i::VARCHAR) || md5((i+1)::VARCHAR) AS m"
                        + " FROM range(60000000) t(i) ORDER BY m)");
            }
            return null;
        }));
        assertEquals(AnalysisFailure.MEMORY_LIMIT, e.reason());
        assertTrue(SpillGuard.tripped());
        assertTrue(AnalysisFailure.isMemoryLimit(e));
        assertTrue(e.getMessage().contains("Narrow the analysis window"));
        assertTrue(e.getCause().toString().contains("INTERRUPT"), "the query was interrupted by the guard: " + e.getCause());
    }

    @Test
    void aRunWithinTheLimitReturnsItsResultAndTheNextRunStartsClean() {
        System.setProperty(SpillGuard.HARD_TEMP_PROPERTY, "300");
        System.setProperty(SpillGuard.POLL_PROPERTY, "20");
        Path caseDir = dir.resolve("quiet");
        ProjectStore store = ProjectStore.open(caseDir, new ProjectStore.Settings(null, 8 * GIB, 1));
        Object count = SpillGuard.run(caseDir, () -> {
            try (Db db = store.connect()) {
                return db.query("SELECT count(*) FROM range(1000)").get(0)[0];
            }
        });
        assertEquals(1000L, ((Number) count).longValue());
        assertFalse(SpillGuard.tripped());
    }

    @Test
    void aTripIsStickyUntilTheNextRunSoTheFailureStaysClassified() throws Exception {
        System.setProperty(MemoryPlan.MAX_TEMP_PROPERTY, "256");
        System.setProperty(SpillGuard.POLL_PROPERTY, "20");
        Path caseDir = dir.resolve("sticky");
        Path spill = MemoryPlan.spillDirectory(caseDir);
        Files.createDirectories(spill);
        // A body that does not touch DuckDB at all, while the spill directory is over the limit.
        AnalysisLimitException e = assertThrows(AnalysisLimitException.class, () -> SpillGuard.run(caseDir, () -> {
            try {
                try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(
                        spill.resolve("duckdb_temp_storage_DEFAULT-0.tmp").toFile(), "rw")) {
                    f.setLength(600L * 1024 * 1024); // sparse: 600 MiB of apparent size
                }
                Thread.sleep(400);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            return "finished normally";
        }), "a body that returns after the guard tripped is still a failed run");
        assertEquals(AnalysisFailure.MEMORY_LIMIT, e.reason());
        assertTrue(SpillGuard.tripped());
        Files.delete(spill.resolve("duckdb_temp_storage_DEFAULT-0.tmp"));
        assertEquals("ok", SpillGuard.run(dir.resolve("another"), () -> "ok"));
        assertFalse(SpillGuard.tripped(), "the next run clears the trip");
    }

    @Test
    void theHardLimitDefaultsToTwiceTheSoftOneAndNeverBelowIt() {
        System.setProperty(MemoryPlan.MAX_TEMP_PROPERTY, "1000");
        Path spill = dir.resolve("x/.spill");
        long soft = MemoryPlan.maxTempMib(spill);
        assertEquals(1000, soft);
        assertEquals(2000, SpillGuard.hardLimitMib(spill));
        System.setProperty(SpillGuard.HARD_TEMP_PROPERTY, "10");
        assertEquals(1000, SpillGuard.hardLimitMib(spill), "a hard limit below the soft one is raised to it");
        assertInstanceOf(Long.class, SpillGuard.sizeMib(spill) + 0L);
        assertEquals(0, SpillGuard.sizeMib(spill), "a spill directory that does not exist yet is empty");
    }
}
