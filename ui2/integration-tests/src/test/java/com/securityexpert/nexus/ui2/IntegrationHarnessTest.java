package com.securityexpert.nexus.ui2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class IntegrationHarnessTest {
    private static PostgreSQLContainer<?> postgres;
    private static String jdbcUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String databaseName;

    @BeforeAll
    static void startDatabase() {
        String externalUrl = System.getenv("UI2_TEST_POSTGRES_JDBC_URL");
        if (externalUrl != null && !externalUrl.isBlank()) {
            jdbcUrl = externalUrl;
            adminUser = requiredEnvironment("UI2_TEST_POSTGRES_ADMIN_USER");
            adminPassword = requiredEnvironment("UI2_TEST_POSTGRES_ADMIN_PASSWORD");
            databaseName = requiredEnvironment("UI2_TEST_POSTGRES_DATABASE");
            return;
        }
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        jdbcUrl = postgres.getJdbcUrl();
        adminUser = postgres.getUsername();
        adminPassword = postgres.getPassword();
        databaseName = postgres.getDatabaseName();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for CRC-backed integration tests");
        }
        return value;
    }

    @Test
    void appRoleCannotApplyDdl() throws Exception {
        String adminUrl = jdbcUrl;
        String migratePassword = "migrate-" + UUID.randomUUID();
        String appPassword = "app-" + UUID.randomUUID();
        try (Connection admin = DriverManager.getConnection(adminUrl, adminUser, adminPassword);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE ROLE ui2_migrate LOGIN PASSWORD '" + migratePassword + "'");
            statement.execute("CREATE ROLE ui2_app LOGIN PASSWORD '" + appPassword + "'");
            statement.execute("GRANT CONNECT ON DATABASE \"" + databaseName + "\" TO ui2_migrate, ui2_app");
            statement.execute("GRANT USAGE ON SCHEMA public TO ui2_migrate, ui2_app");
            statement.execute("GRANT CREATE ON SCHEMA public TO ui2_migrate");
            statement.execute("CREATE TABLE public.ui2_harness_marker (id integer primary key)");
            statement.execute("REVOKE CREATE ON SCHEMA public FROM ui2_app");
        }

        String separator = jdbcUrl.contains("?") ? "&" : "?";
        String migrationDsn = jdbcUrl + separator + "user=ui2_migrate&password=" + migratePassword;
        assertDoesNotThrow(() -> MigrationRunner.migrate(migrationDsn));
        assertDoesNotThrow(() -> MigrationRunner.migrate(migrationDsn));

        assertThrows(SQLException.class, () -> {
            try (Connection app = DriverManager.getConnection(jdbcUrl, "ui2_app", appPassword);
                 Statement statement = app.createStatement()) {
                statement.execute("CREATE TABLE public.ui2_app_must_not_ddl (id integer)");
            }
        });
    }
}
