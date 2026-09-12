package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * Contract §7 test 8 / C1 §9 AC-6 check 8, proved against a real PostgreSQL
 * 16 server: a {@code NULL} and a dangling {@code provenance_id} are both
 * rejected on {@code cp_inventory_projection} — the first by
 * {@code NOT NULL} ({@code 23502}), the second by the FOREIGN KEY itself
 * ({@code 23503}).
 *
 * <p>A well-formed row is inserted first, so the test proves the two
 * rejections are about {@code provenance_id} specifically and not about some
 * other column of the seed being wrong — "collection success != semantic
 * correctness" applies to test seeds too.</p>
 */
class ProvenanceForeignKeyTest {

    private static Ui2PostgresFixture fixture;
    private static String deviceId;
    private static String endpointId;
    private static String jobId;
    private static String provenanceId;

    @BeforeAll
    static void migrate() throws SQLException {
        fixture = Ui2PostgresFixture.createAndMigrate("provenance_fk");
        try (Connection app = fixture.appConnection()) {
            String credentialReferenceId = Ui2Rows.insertCredentialReference(app);
            deviceId = Ui2Rows.insertDevice(app, credentialReferenceId, "ENROLLED");
            endpointId = Ui2Rows.insertEndpoint(app, deviceId);
            jobId = Ui2Rows.insertJob(app, deviceId);
            provenanceId = Ui2Rows.insertProvenanceRecord(app);
        }
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void aWellFormedProjectionRowIsAccepted() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            String projectionId = insertProjection(app, quoted(provenanceId));
            assertEquals(1L, Ui2Rows.count(app,
                    "SELECT count(*) FROM cp_inventory_projection WHERE projection_id = '" + projectionId + "'"));
        }
    }

    @Test
    void nullProvenanceIdIsRejectedByNotNull() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            SQLException rejected = assertThrows(SQLException.class, () -> insertProjection(app, "NULL"));
            assertEquals("23502", rejected.getSQLState(), "expected not_null_violation on provenance_id");
        }
    }

    @Test
    void danglingProvenanceIdIsRejectedByTheForeignKey() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            SQLException rejected = assertThrows(SQLException.class,
                    () -> insertProjection(app, "'prov-does-not-exist'"));
            assertEquals("23503", rejected.getSQLState(),
                    "expected foreign_key_violation -- the FK must exist, not merely be satisfiable");
        }
    }

    /** @param provenanceExpression raw SQL for the provenance_id value. */
    private static String insertProjection(Connection app, String provenanceExpression) throws SQLException {
        String projectionId = Ui2Rows.opaqueId("proj");
        boolean previousAutoCommit = app.getAutoCommit();
        app.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(app, Ui2Rows.ACTOR, "harness.projection");
            try (Statement statement = app.createStatement()) {
                statement.execute("INSERT INTO cp_inventory_projection(projection_id, device_id, endpoint_id, "
                        + "job_id, provenance_id, collected_at) VALUES ("
                        + quoted(projectionId) + ", " + quoted(deviceId) + ", " + quoted(endpointId) + ", "
                        + quoted(jobId) + ", " + provenanceExpression + ", now())");
            }
            app.commit();
            return projectionId;
        } catch (SQLException e) {
            app.rollback();
            throw e;
        } finally {
            app.setAutoCommit(previousAutoCommit);
        }
    }

    private static String quoted(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
