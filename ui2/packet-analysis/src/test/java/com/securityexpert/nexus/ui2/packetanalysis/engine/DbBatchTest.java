package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Db#execMany} with a large batch of single-row inserts on a file-backed database with several DuckDB
 * threads (the canonical tuple lookup of a real capture has about 90 000 rows). Before the batch was executed in
 * chunks the JDBC driver kept a native result per batched execution (about 140 KB each at threads=4), so this
 * insert needed several GB and failed under a 256 MiB memory limit.
 */
class DbBatchTest {
    @Test
    void largeBatchOfSingleRowInsertsStaysWithinTheMemoryLimit(@TempDir Path dir) throws Exception {
        Properties props = new Properties();
        props.setProperty("threads", "4");
        props.setProperty("memory_limit", "256MiB");
        props.setProperty("preserve_insertion_order", "false");
        int n = 40_037; // not a multiple of the chunk size: the last, partial chunk is executed too
        try (Db db = new Db(DriverManager.getConnection("jdbc:duckdb:" + dir.resolve("batch.duckdb"), props))) {
            db.exec("CREATE OR REPLACE TEMP TABLE tuple_lookup(tuple_key VARCHAR,canon VARCHAR,canon_reverse VARCHAR,direction VARCHAR)");
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String key = "[\"TCP\",\"10.0." + (i % 250) + "." + (i % 200) + "\"," + i + ",\"10.1.0.1\",443]";
                rows.add(new Object[] {key, key, "reverse-" + i, i % 2 == 0 ? "forward" : "reverse"});
            }
            db.execMany("INSERT INTO tuple_lookup VALUES (?,?,?,?)", rows);
            List<Db.Row> count = db.rows("SELECT count(*) AS n, count(DISTINCT tuple_key) AS k, "
                    + "count(*) FILTER (WHERE direction='forward') AS f FROM tuple_lookup");
            assertEquals((long) n, count.get(0).lng("n"));
            assertEquals((long) n, count.get(0).lng("k"));
            assertEquals((long) ((n + 1) / 2), count.get(0).lng("f"));
            List<Db.Row> last = db.rows("SELECT canon_reverse FROM tuple_lookup WHERE canon_reverse = ?", "reverse-" + (n - 1));
            assertEquals(1, last.size());
        }
    }
}
