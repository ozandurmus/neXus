package com.securityexpert.nexus.ui2.packetanalysis.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A failure is described by store, constraint kind and name, SQL state and class names; never by a value. */
class FailureDiagnosisTest {

    private static final String SECRET = "192.0.2.77";

    /** The message DuckDB 1.5 raises for {@code statement}, as the engine wraps it. */
    private static RuntimeException duck(String statement) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("create table capture_map(id varchar primary key, name varchar unique, n int not null, k int check (k > 0))");
            s.execute("insert into capture_map values ('" + SECRET + "', '" + SECRET + "', 1, 1)");
            SQLException e = assertThrows(SQLException.class, () -> s.execute(statement));
            return new IllegalStateException("Engine DuckDB failure: " + e.getMessage(), e);
        }
    }

    @Test
    void duckDbConstraintsAreNamedByKindAndTheKeyIsDropped() throws SQLException {
        String[][] cases = {
                {"insert into capture_map values ('" + SECRET + "', 'b', 1, 1)", "ENGINE_FAILED: duckdb primary key constraint"},
                {"insert into capture_map values ('a', '" + SECRET + "', 1, 1)", "ENGINE_FAILED: duckdb unique constraint"},
                {"insert into capture_map values ('a', 'b', null, 1)", "ENGINE_FAILED: duckdb not null constraint capture_map.n"},
                {"insert into capture_map values ('a', 'b', 1, -1)", "ENGINE_FAILED: duckdb check constraint capture_map"},
        };
        for (String[] c : cases) {
            FailureDiagnosis d = FailureDiagnosis.of(duck(c[0]), FailureDiagnosis.DUCKDB);
            assertEquals(c[1], d.reason(AnalysisFailure.ENGINE_FAILED));
            assertTrue(d.constraintViolation());
            assertEquals(List.of("java.lang.IllegalStateException", "java.sql.SQLException"), d.chain());
            assertFalse(d.reason("ENGINE_FAILED").contains(SECRET));
            assertFalse(d.logDetail().contains(SECRET), d.logDetail());
        }
    }

    @Test
    void aPostgresRefusalNamesItsConstraintAndSqlStateButNotTheFailingRow() {
        String server = "ERROR: duplicate key value violates unique constraint \"packet_analysis_capture_storage_key_key\"\n"
                + "  Detail: Key (storage_key)=(" + SECRET + ") already exists.";
        RuntimeException jooqLike = new RuntimeException("SQL [insert ...]; " + server, new SQLException(server, "23505"));
        FailureDiagnosis d = FailureDiagnosis.of(jooqLike, FailureDiagnosis.POSTGRES);
        assertEquals("ENGINE_FAILED: postgres unique constraint packet_analysis_capture_storage_key_key (sqlstate 23505)",
                d.reason("ENGINE_FAILED"));
        assertEquals("23505", d.sqlState());
        assertFalse(d.logDetail().contains(SECRET));
        assertFalse(d.logDetail().contains("Detail"));

        String notNull = "ERROR: null value in column \"sha256\" of relation \"packet_analysis_capture\" violates not-null constraint";
        assertEquals("ENGINE_FAILED: postgres not null constraint packet_analysis_capture.sha256 (sqlstate 23502)",
                FailureDiagnosis.of(new SQLException(notNull, "23502"), FailureDiagnosis.POSTGRES).reason("ENGINE_FAILED"));
    }

    @Test
    void anUnsafeIdentifierIsDroppedAndAFailureOutsideAStoreIsItsClassName() {
        String odd = "ERROR: new row violates check constraint \"" + SECRET + "\"";
        FailureDiagnosis d = FailureDiagnosis.of(new SQLException(odd, "23514"), FailureDiagnosis.POSTGRES);
        assertEquals("ENGINE_FAILED: postgres check constraint (sqlstate 23514)", d.reason("ENGINE_FAILED"));

        FailureDiagnosis plain = FailureDiagnosis.of(new IllegalStateException(SECRET, new java.io.IOException(SECRET)),
                FailureDiagnosis.DUCKDB);
        assertNull(plain.store(), "no SQL failure in the chain: no store is claimed");
        assertEquals("ENGINE_FAILED: IOException", plain.reason("ENGINE_FAILED"));
        assertTrue(plain.logDetail().startsWith("chain java.lang.IllegalStateException <- java.io.IOException"));
        assertFalse(plain.logDetail().contains(SECRET));
        assertTrue(plain.logDetail().contains("FailureDiagnosisTest."), "code locations are kept: " + plain.logDetail());
    }

    @Test
    void aSelfReferencingChainEnds() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertEquals(2, FailureDiagnosis.of(a, null).chain().size());
    }
}
