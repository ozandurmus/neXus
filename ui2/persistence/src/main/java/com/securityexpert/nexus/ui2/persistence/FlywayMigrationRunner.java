package com.securityexpert.nexus.ui2.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import com.securityexpert.nexus.ui2.platform.SecretFile;

/**
 * Flyway integration code (contract §2 persistence row; Amendment B1-1-A
 * item 3 — the migration SQL itself lives in
 * {@code service/src/main/resources/db/migration} and is packaged from
 * there, never authored here).
 *
 * <p>The migration DSN carries no credential. The user and password are
 * read from two separate files and passed to Flyway as separate
 * arguments (never string-concatenated into the JDBC URL), so that a
 * JDBC or Flyway exception that prints the URL cannot also print a
 * secret value (C1 §6.2).</p>
 */
public final class FlywayMigrationRunner {

    private final String jdbcUrl;
    private final Path userFile;
    private final Path passwordFile;
    private final String migrationLocation;

    public FlywayMigrationRunner(String jdbcUrl, Path userFile, Path passwordFile, String migrationLocation) {
        this.jdbcUrl = jdbcUrl;
        this.userFile = userFile;
        this.passwordFile = passwordFile;
        this.migrationLocation = migrationLocation;
    }

    public MigrateResult migrate() {
        String user = SecretFile.readRequired(userFile, "ui2_migrate.user");
        String password = SecretFile.readRequired(passwordFile, "ui2_migrate.password");

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations(migrationLocation)
                .load();

        return flyway.migrate();
    }

    /**
     * Verifies read-only via {@code public.ui2_schema_version_check(expected_version)} using the application
     * credential files when {@code ui2-service} runs without {@code ui2_migrate} credentials mounted
     * ({@code docs/design/DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md} §3.2, §4.2 Step 0 / Step 4).
     */
    public static boolean verifySchemaVersion(String jdbcUrl, Path appUserFile, Path appPasswordFile,
            String expectedVersion) {
        String user = SecretFile.readRequired(appUserFile, "ui2_app.user");
        String password = SecretFile.readRequired(appPasswordFile, "ui2_app.password");
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, properties);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT public.ui2_schema_version_check(?)")) {
            statement.setString(1, expectedVersion);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("ui2 schema version check failed: sqlState=" + e.getSQLState(), e);
        }
    }
}
