package com.securityexpert.nexus.ui2.packetanalysis.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A run that reaches its memory budget is classified, not echoed; DuckDB's own out-of-memory error is the proof. */
class AnalysisFailureTest {

    private static final long GIB = 1024L * 1024 * 1024;

    @TempDir
    Path dir;

    @AfterEach
    void clearProperties() {
        System.clearProperty(MemoryPlan.MAX_TEMP_PROPERTY);
        System.clearProperty(MemoryPlan.MEMORY_LIMIT_PROPERTY);
    }

    @Test
    void duckDbOutOfMemoryAtTheMemoryAndSpillLimitIsAMemoryLimitFailure() {
        ProjectStore store = ProjectStore.open(dir.resolve("p"), new ProjectStore.Settings(64, 8 * GIB, 1));
        RuntimeException failure;
        try (Db db = store.connect()) {
            // A sort that needs far more than the 64 MiB limit and may spill only 1 MiB: DuckDB stops with its
            // out-of-memory error (the spill ceiling of the plan, here made tiny so the test is quick).
            db.execute("SET max_temp_directory_size='1MiB'");
            failure = assertThrows(RuntimeException.class, () -> db.query(
                    "SELECT count(*) FROM (SELECT i, md5(i::VARCHAR) || md5((i+1)::VARCHAR) AS m FROM range(4000000) t(i) ORDER BY m)"));
        }
        assertTrue(AnalysisFailure.isMemoryLimit(failure), failure.toString());
        assertEquals(AnalysisFailure.MEMORY_LIMIT, AnalysisFailure.reasonOf(failure));
        RuntimeException classified = AnalysisFailure.classify(failure);
        AnalysisLimitException limit = assertInstanceOf(AnalysisLimitException.class, classified);
        assertEquals("ANALYSIS_MEMORY_LIMIT", limit.reason());
        assertSame(failure, limit.getCause());
        assertTrue(limit.getMessage().contains("Narrow the analysis window"));
        assertFalse(limit.getMessage().contains("md5"), "the user message never quotes the failing statement");
    }

    @Test
    void anOperatorThatCannotSpillFailsAtOnceWithTheSameClassification() {
        ProjectStore store = ProjectStore.open(dir.resolve("nospill"), new ProjectStore.Settings(64, 8 * GIB, 1));
        try (Db db = store.connect()) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> db.query("SELECT length(list(md5(i::VARCHAR))) FROM range(20000000) t(i)"));
            assertTrue(AnalysisFailure.isMemoryLimit(failure), failure.toString());
            assertEquals("ANALYSIS_MEMORY_LIMIT", AnalysisFailure.reasonOf(failure));
        }
    }

    @Test
    void spillKeepsAQueryWithinTheMemoryLimitRunning() {
        // The same limit with room to spill finishes: the limit decides spill, not failure.
        System.setProperty(MemoryPlan.MAX_TEMP_PROPERTY, "4096");
        ProjectStore store = ProjectStore.open(dir.resolve("spill"), new ProjectStore.Settings(64, 8 * GIB, 1));
        try (Db db = store.connect()) {
            Object count = db.query(
                    "SELECT count(*) FROM (SELECT i, md5(i::VARCHAR) AS m FROM range(3000000) t(i) ORDER BY m)").get(0)[0];
            assertEquals(3000000L, ((Number) count).longValue());
        }
    }

    @Test
    void classificationWalksTheCauseChainAndKnowsTheJvmHeapLimit() {
        RuntimeException wrapped = new IllegalStateException("Engine DuckDB failure: x",
                new RuntimeException(new java.sql.SQLException("Out of Memory Error: failed to pin block of size 256.0 KiB (1.0 GiB/1.0 GiB used)")));
        assertTrue(AnalysisFailure.isMemoryLimit(wrapped));
        assertEquals("ANALYSIS_MEMORY_LIMIT", AnalysisFailure.reasonOf(wrapped));
        assertTrue(AnalysisFailure.isMemoryLimit(new RuntimeException(new OutOfMemoryError("Java heap space"))));
        assertTrue(AnalysisFailure.isMemoryLimit(new OutOfMemoryError("Java heap space")));
        RuntimeException other = new IllegalStateException("Catalog Error: table obs does not exist");
        assertFalse(AnalysisFailure.isMemoryLimit(other));
        assertEquals("ENGINE_FAILED", AnalysisFailure.reasonOf(other));
        assertSame(other, AnalysisFailure.classify(other), "any other failure is returned unchanged");
        assertNull(AnalysisFailure.messageFor("ENGINE_FAILED"));
        assertEquals(AnalysisFailure.WINDOW_REQUIRED, AnalysisFailure.reasonOf(
                new RuntimeException("x", new AnalysisLimitException(AnalysisFailure.WINDOW_REQUIRED, "m", null))));
        // A cycle in the causes does not loop.
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertFalse(AnalysisFailure.isMemoryLimit(a));
    }
}
