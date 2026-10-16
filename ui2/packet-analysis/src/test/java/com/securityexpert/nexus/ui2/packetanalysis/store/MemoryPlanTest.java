package com.securityexpert.nexus.ui2.packetanalysis.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The memory plan's arithmetic, and that every connection of a case carries the same limit and spill settings. */
class MemoryPlanTest {

    private static final long GIB = 1024L * 1024 * 1024;
    private static final String[] PROPERTIES = {MemoryPlan.MEMORY_LIMIT_PROPERTY, MemoryPlan.CONTAINER_PROPERTY,
        MemoryPlan.RESERVE_PROPERTY, MemoryPlan.MAX_TEMP_PROPERTY};

    @TempDir
    Path dir;

    @AfterEach
    void clearProperties() {
        for (String p : PROPERTIES) {
            System.clearProperty(p);
        }
    }

    @Test
    void theDeploymentAddsUp() {
        // HOST-A worker: pod 24 GiB, heap 6 GiB, DuckDB 14 GiB, threads 4.
        long pod = 24 * 1024;
        long heap = 6 * 1024;
        long duckdb = 14 * 1024;
        long reserve = Math.max(2048, pod / 8);
        assertEquals(3072, reserve);
        assertTrue(MemoryPlan.fits(pod, heap, duckdb, reserve), "14 + 6 + 3 = 23 GiB <= 24 GiB");
        assertEquals(1024, pod - heap - duckdb - reserve, "1 GiB margin beyond the engine's own reserve");
        assertEquals(15 * 1024, MemoryPlan.ceilingMib(pod * 1024 * 1024, heap * 1024 * 1024, reserve));
        // The old pod: 10 GiB with the heap at half left DuckDB with nothing.
        assertFalse(MemoryPlan.fits(10 * 1024, 5 * 1024, 10 * 1024, 2048));
        assertEquals(MemoryPlan.MIN_LIMIT_MIB, MemoryPlan.ceilingMib(10 * GIB, 9 * GIB, 2048), "never below the floor");
    }

    @Test
    void reserveIsAnEighthOfTheContainerButAtLeastTwoGiB() {
        assertEquals(2048, MemoryPlan.reserveMib(8 * 1024));
        assertEquals(2048, MemoryPlan.reserveMib(16 * 1024));
        assertEquals(3072, MemoryPlan.reserveMib(24 * 1024));
        assertEquals(8192, MemoryPlan.reserveMib(64 * 1024));
        System.setProperty(MemoryPlan.RESERVE_PROPERTY, "1000");
        assertEquals(1000, MemoryPlan.reserveMib(24 * 1024));
    }

    @Test
    void aConfiguredLimitAboveTheCeilingIsLoweredToIt() {
        System.setProperty(MemoryPlan.CONTAINER_PROPERTY, Long.toString(24 * 1024));
        long ceiling = MemoryPlan.ceilingMib();
        long heap = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        assertEquals(Math.max(MemoryPlan.MIN_LIMIT_MIB, 24 * 1024 - heap - 3072), ceiling);
        System.setProperty(MemoryPlan.MEMORY_LIMIT_PROPERTY, "500000");
        assertEquals(ceiling, MemoryPlan.memoryLimitMib(null, 1024));
        assertEquals(ceiling, MemoryPlan.memoryLimitMib(900000, 1024), "an explicit value is lowered too");
        System.setProperty(MemoryPlan.MEMORY_LIMIT_PROPERTY, "256");
        assertEquals(256, MemoryPlan.memoryLimitMib(null, 1024), "a value that fits is kept");
        System.clearProperty(MemoryPlan.MEMORY_LIMIT_PROPERTY);
        assertEquals(256, MemoryPlan.memoryLimitMib(null, 256), "the default applies without a property");
        System.setProperty(MemoryPlan.MEMORY_LIMIT_PROPERTY, "0");
        assertThrows(IllegalArgumentException.class, () -> MemoryPlan.memoryLimitMib(null, 1024));
    }

    @Test
    void spillIsBoundedByTheConfiguredSizeAndByTheFreeSpaceOfTheVolume() {
        assertEquals(40 * 1024, MemoryPlan.maxTempMib(40 * 1024, 500 * 1024), "plenty of room: the configured size");
        assertEquals(10 * 1024 - MemoryPlan.FREE_SPACE_GUARD_MIB, MemoryPlan.maxTempMib(40 * 1024, 10 * 1024),
                "less free than configured: free space less the guard");
        assertEquals(256, MemoryPlan.maxTempMib(40 * 1024, 100), "a nearly full volume still gets a small, bounded spill");
        assertEquals(40 * 1024, MemoryPlan.maxTempMib(40 * 1024, -1), "unknown free space: the configured size");
        System.setProperty(MemoryPlan.MAX_TEMP_PROPERTY, "512");
        assertEquals(512, MemoryPlan.maxTempMib(dir.resolve("not-created-yet/.spill")), "the nearest existing parent is probed");
    }

    @Test
    void everyConnectionOfACaseSpillsToItsSpillDirectoryWithTheSameBoundedSize() throws Exception {
        System.setProperty(MemoryPlan.MEMORY_LIMIT_PROPERTY, "300");
        System.setProperty(MemoryPlan.MAX_TEMP_PROPERTY, "512");
        Path caseDir = dir.resolve("case");
        ProjectStore store = ProjectStore.open(caseDir, new ProjectStore.Settings(null, 8 * GIB, 1));
        Path spill = store.path().resolve(".spill");
        try (Db db = store.connect()) {
            assertSettings(db.query("SELECT current_setting('memory_limit'), current_setting('max_temp_directory_size'),"
                    + " current_setting('temp_directory')").get(0), spill);
        }
        try (Connection c = Engine.open(caseDir.resolve("project.duckdb"), 1)) {
            Object[] row = settings(c);
            assertSettings(row, spill);
        }
        try (Connection c = Engine.openReadOnly(caseDir.resolve("project.duckdb"), 1)) {
            assertSettings(settings(c), spill);
        }
        var project = new com.securityexpert.nexus.ui2.packetanalysis.topology.ProjectDb(caseDir);
        try (var db = project.connect()) {
            var row = db.fetchOne("SELECT current_setting('memory_limit'), current_setting('max_temp_directory_size'),"
                    + " current_setting('temp_directory')", java.util.List.of());
            assertSettings(row.toArray(), spill);
        }
    }

    @Test
    void anExplicitSettingIsLoweredButNeverRaisedByTheContainer() {
        System.setProperty(MemoryPlan.CONTAINER_PROPERTY, "4096");
        System.setProperty(MemoryPlan.RESERVE_PROPERTY, "1024");
        long heap = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        long ceiling = Math.max(MemoryPlan.MIN_LIMIT_MIB, 4096 - heap - 1024);
        ProjectStore store = ProjectStore.open(dir.resolve("small"), new ProjectStore.Settings(10240, 4 * GIB, 1));
        try (Db db = store.connect()) {
            String limit = db.query("SELECT current_setting('memory_limit')").get(0)[0].toString();
            assertTrue(limit.endsWith("iB"), limit);
            Properties p = new Properties();
            MemoryPlan.applySpill(p, dir.resolve("x/.spill"));
            assertEquals(dir.resolve("x/.spill").toString(), p.getProperty("temp_directory"));
            assertEquals(ceiling >= 10240, limit.startsWith("10.0 GiB"), "ceiling " + ceiling + " limit " + limit);
        }
        assertTrue(Files.isDirectory(store.path()));
    }

    private static Object[] settings(Connection c) throws Exception {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_setting('memory_limit'), current_setting('max_temp_directory_size'),"
                     + " current_setting('temp_directory')")) {
            rs.next();
            return new Object[] {rs.getObject(1), rs.getObject(2), rs.getObject(3)};
        }
    }

    private static void assertSettings(Object[] row, Path spill) {
        assertEquals("300.0 MiB", row[0].toString().replace("MiB", "MiB"));
        assertEquals("512.0 MiB", row[1].toString());
        assertEquals(spill.toString(), row[2].toString());
    }
}
