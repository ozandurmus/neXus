package com.securityexpert.nexus.ui2.integration.device;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;

/**
 * Row seeding local to the device-workspace test suite, for the two shapes
 * neither {@code Ui2Rows} nor a production repository already exposes: an
 * {@code endpoints} row whose {@code address_ref} the caller controls (the
 * leak-proof test needs a distinctive marker; {@code Ui2Rows.insertEndpoint}
 * hardcodes one), and an out-of-vocabulary {@code enrollment_state} forced
 * past the {@code CHECK} constraint. Kept in this package rather than added
 * to {@code integration/support}, which is outside this task's edit scope.
 * Everything else this suite needs (role bindings, actor_authz_state,
 * sessions, authz_decisions) is read/written through the real production
 * repositories, not raw SQL.
 */
final class DeviceWorkspaceTestRows {

    private DeviceWorkspaceTestRows() {
    }

    static String opaqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** Removes a device's only endpoint, so the device presents as {@code MISSING} transport. */
    static void deleteEndpoint(Connection connection, String endpointId) throws SQLException {
        audited(connection, statement -> statement.execute(
                "DELETE FROM endpoints WHERE endpoint_id = " + literal(endpointId)));
    }

    /** Forces an enrollment_state value outside the four-value vocabulary, past the CHECK constraint. */
    static void forceOutOfVocabularyEnrollmentState(Connection connection, String deviceId, String bogusValue)
            throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE devices DROP CONSTRAINT chk_devices_enrollment_state");
            Ui2Rows.setAuditContext(connection, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            statement.execute("UPDATE devices SET enrollment_state = " + literal(bogusValue)
                    + " WHERE device_id = " + literal(deviceId));
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    static GroupReferenceCipher newCipher() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return GroupReferenceCipher.fromBase64Key(Base64.getEncoder().encodeToString(key));
    }

    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static void audited(Connection connection, SqlWork work) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(connection, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            try (Statement statement = connection.createStatement()) {
                work.run(statement);
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Statement statement) throws SQLException;
    }
}
