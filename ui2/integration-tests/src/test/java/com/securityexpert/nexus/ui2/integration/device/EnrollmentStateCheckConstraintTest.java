package com.securityexpert.nexus.ui2.integration.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * B1-4b contract §9 AC-2, proved against a real PostgreSQL 16 server:
 * {@code devices.enrollment_state} is a closed, checked vocabulary of exactly
 * {@code {DRAFT, ENROLLED, UNREACHABLE, DEGRADED}} ({@code V3}'s
 * {@code chk_devices_enrollment_state}), plus the separate {@code disabled}
 * boolean. The application-level fail-closed half is proved without a
 * database at {@code DeviceEnrollmentStateTest}; this is the database half —
 * the CHECK constraint must reject a value the application would never
 * produce but a bypassing writer might attempt.
 */
class EnrollmentStateCheckConstraintTest {

    private static final List<String> VOCABULARY = List.of("DRAFT", "ENROLLED", "UNREACHABLE", "DEGRADED");

    @Test
    void v3AppliesCleanlyOverV1AndV2AndTheCheckConstraintRejectsAFifthValue() throws SQLException {
        // (a) V1, V2, V3 (and V4) apply in sequence against a fresh database.
        try (Ui2PostgresFixture fixture = Ui2PostgresFixture.create("enrollment_state")) {
            MigrateResult result = fixture.runFlyway();
            assertTrue(result.migrationsExecuted >= 3,
                    "expected at least V1, V2 and V3 to apply to a fresh database");
            assertTrue(result.migrations.stream().anyMatch(m -> "3".equals(m.version)),
                    "V3 must be among the applied migrations");

            try (Connection app = fixture.appConnection()) {
                String credentialReferenceId = Ui2Rows.insertCredentialReference(app);

                // (b) every one of the four values is insertable.
                for (String state : VOCABULARY) {
                    String deviceId = Ui2Rows.insertDevice(app, credentialReferenceId, state);
                    assertEquals(1L, Ui2Rows.count(app, "SELECT count(*) FROM devices WHERE device_id = '"
                            + deviceId + "' AND enrollment_state = '" + state + "'"),
                            state + " must be an accepted enrollment_state");
                }

                // (c) a fifth value is rejected by the CHECK constraint, not
                // by application code. 'DISABLED' is used deliberately: it is
                // the value someone would reach for if they mistook the
                // separate `disabled` boolean for a fifth state.
                SQLException rejected = assertThrows(SQLException.class,
                        () -> Ui2Rows.insertDevice(app, credentialReferenceId, "DISABLED"));
                assertEquals("23514", rejected.getSQLState(), "expected check_violation");
                assertTrue(rejected.getMessage().contains("chk_devices_enrollment_state"),
                        "the rejection must come from chk_devices_enrollment_state specifically");

                // An UPDATE to a fifth value is rejected too -- the constraint
                // is not an INSERT-only gate.
                String enrolled = Ui2Rows.insertDevice(app, credentialReferenceId, "ENROLLED");
                SQLException updateRejected = assertThrows(SQLException.class,
                        () -> audited(app, "UPDATE devices SET enrollment_state = 'RETIRED' WHERE device_id = '"
                                + enrolled + "'"));
                assertEquals("23514", updateRejected.getSQLState());

                // (d) `disabled` is a separate column: it accepts true and
                // false independently of enrollment_state, which keeps its own
                // value across the change (contract §3's "any -> disabled").
                audited(app, "UPDATE devices SET disabled = true WHERE device_id = '" + enrolled + "'");
                assertEquals(1L, Ui2Rows.count(app, "SELECT count(*) FROM devices WHERE device_id = '"
                        + enrolled + "' AND disabled = true AND enrollment_state = 'ENROLLED'"),
                        "disabling must not disturb enrollment_state");

                audited(app, "UPDATE devices SET disabled = false WHERE device_id = '" + enrolled + "'");
                assertEquals(1L, Ui2Rows.count(app, "SELECT count(*) FROM devices WHERE device_id = '"
                        + enrolled + "' AND disabled = false AND enrollment_state = 'ENROLLED'"),
                        "re-enabling must not disturb enrollment_state either");

                // And a device in any of the four states can be disabled.
                for (String state : VOCABULARY) {
                    String deviceId = Ui2Rows.insertDevice(app, credentialReferenceId, state);
                    audited(app, "UPDATE devices SET disabled = true WHERE device_id = '" + deviceId + "'");
                    assertEquals(1L, Ui2Rows.count(app, "SELECT count(*) FROM devices WHERE device_id = '"
                            + deviceId + "' AND disabled = true AND enrollment_state = '" + state + "'"),
                            state + " must be disable-able without losing its enrollment_state");
                }
            }
        }
    }

    private static void audited(Connection app, String sql) throws SQLException {
        boolean previousAutoCommit = app.getAutoCommit();
        app.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(app, Ui2Rows.ACTOR, "harness.enrollment");
            try (Statement statement = app.createStatement()) {
                statement.execute(sql);
            }
            app.commit();
        } catch (SQLException e) {
            app.rollback();
            throw e;
        } finally {
            app.setAutoCommit(previousAutoCommit);
        }
    }
}
