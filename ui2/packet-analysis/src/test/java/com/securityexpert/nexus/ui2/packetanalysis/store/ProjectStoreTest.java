package com.securityexpert.nexus.ui2.packetanalysis.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.securityexpert.nexus.ui2.packetanalysis.ingest.PyCsv;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Schema creation, settings, resource rules and the CSV batch load's type conversion (DuckDB, no tshark). */
class ProjectStoreTest {

    private static final long GIB = 1024L * 1024 * 1024;

    @TempDir
    Path dir;

    private ProjectStore open() {
        return ProjectStore.open(dir.resolve("p"), new ProjectStore.Settings(null, 8 * GIB, 2));
    }

    @Test
    void memoryAndThreadRules() {
        assertEquals(1024, MemoryBudget.memoryLimitMib(null, 2 * GIB));
        assertEquals(4096, MemoryBudget.memoryLimitMib(null, 16 * GIB));
        assertEquals(8192, MemoryBudget.memoryLimitMib(null, 64 * GIB));
        assertEquals(1536, MemoryBudget.memoryLimitMib(null, 6 * GIB + 1));
        assertEquals(64, MemoryBudget.memoryLimitMib(64, 64 * GIB));
        assertThrows(IllegalArgumentException.class, () -> MemoryBudget.memoryLimitMib(63, 64 * GIB));
        assertEquals(1, MemoryBudget.defaultThreads(1));
        assertEquals(7, MemoryBudget.defaultThreads(8));
        assertEquals(8, MemoryBudget.defaultThreads(32));
    }

    @Test
    void newProjectHasTheVersionSixSchemaAndSettings() {
        ProjectStore project = open();
        open();
        try (Db db = project.connect()) {
            assertEquals(6L, ProjectStore.get(db, "schema_version", null));
            assertEquals(ProjectStore.ANALYSIS_VERSION, ProjectStore.get(db, "analysis_version", null));
            assertEquals("null", db.query("SELECT value FROM settings WHERE key='report'").get(0)[0]);
            List<Object[]> cols = db.query("SELECT column_name, data_type FROM duckdb_columns() WHERE"
                    + " table_name='packets' ORDER BY column_index");
            assertEquals(PacketColumns.NAMES, cols.stream().map(r -> (String) r[0]).toList());
            assertEquals(List.copyOf(PacketColumns.TYPES.values()), cols.stream().map(r -> (String) r[1]).toList());
            Object limit = db.query("SELECT current_setting('memory_limit')").get(0)[0];
            assertEquals("2.0 GiB", limit.toString());
            assertEquals("2", db.query("SELECT current_setting('threads')").get(0)[0].toString());
            assertEquals("false", db.query("SELECT current_setting('enable_external_access')").get(0)[0]
                    .toString().toLowerCase());
        }
    }

    @Test
    void threadPropertyOverridesEveryConnection() {
        ProjectStore project = open();
        String old = System.getProperty(ProjectStore.THREADS_PROPERTY);
        System.setProperty(ProjectStore.THREADS_PROPERTY, "1");
        try (Db db = project.connect()) {
            assertEquals("1", db.query("SELECT current_setting('threads')").get(0)[0].toString());
        } finally {
            if (old == null) {
                System.clearProperty(ProjectStore.THREADS_PROPERTY);
            } else {
                System.setProperty(ProjectStore.THREADS_PROPERTY, old);
            }
        }
        assertEquals(2, project.threads());
    }

    @Test
    void externalAccessIsOffUnlessRequested() throws IOException {
        ProjectStore project = open();
        Path csv = dir.resolve("x.csv");
        Files.writeString(csv, "a\n1\n");
        try (Db db = project.connect()) {
            assertThrows(RuntimeException.class, () -> db.query("SELECT * FROM read_csv(?)", csv.toString()));
        }
        try (Db db = project.connect(true)) {
            assertEquals(1L, db.query("SELECT count(*) FROM read_csv(?)", csv.toString()).get(0)[0]);
        }
    }

    @Test
    void batchLoadConvertsTheReferenceCsvText() throws IOException {
        ProjectStore project = open();
        Object[] row = new Object[PacketColumns.NAMES.size()];
        for (int i = 0; i < row.length; i++) {
            String type = PacketColumns.TYPES.get(PacketColumns.NAMES.get(i));
            row[i] = switch (type) {
                case "VARCHAR" -> "";
                case "BOOLEAN" -> true;
                case "DOUBLE" -> 1700000000.1234567;
                default -> 4294967295L;
            };
        }
        row[PacketColumns.index("capture_id")] = "c1";
        row[PacketColumns.index("frame")] = 1L;
        row[PacketColumns.index("iface")] = 0L;
        for (String c : List.of("sport", "dport", "flags", "length", "wirelen", "caplen", "ttl", "dscp", "mss",
                "icmp_id", "icmp_seq", "icmp_type", "dns_id")) {
            row[PacketColumns.index(c)] = 7L;
        }
        row[PacketColumns.index("rtt")] = null;
        row[PacketColumns.index("fast_retrans")] = false;
        row[PacketColumns.index("tuple_key")] = "[\"TCP\",\"198.51.100.1\",1,\"198.51.100.2\",2]";
        row[PacketColumns.index("vendor")] = null;
        Path csv = dir.resolve("batch.csv");
        try (Writer w = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            PyCsv.writeRow(w, PacketColumns.NAMES.toArray(), ',');
            PyCsv.writeRow(w, row, ',');
        }
        try (Db db = project.connect(true)) {
            db.execute(Sql.load("ingest/load_batch").replace("{columns}", PacketColumns.columnsStructLiteral()),
                    csv.toString(), "");
            Object[] r = db.query("SELECT src, ts, seq, retrans, fast_retrans, rtt, tuple_key, sport, vendor"
                    + " FROM packets").get(0);
            assertNull(r[0]);
            assertEquals(1700000000.1234567, r[1]);
            assertEquals(4294967295L, r[2]);
            assertEquals(true, r[3]);
            assertEquals(false, r[4]);
            assertNull(r[5]);
            assertEquals("[\"TCP\",\"198.51.100.1\",1,\"198.51.100.2\",2]", r[6]);
            assertEquals(7L, r[7]);
            assertNull(r[8]);
        }
    }
}
