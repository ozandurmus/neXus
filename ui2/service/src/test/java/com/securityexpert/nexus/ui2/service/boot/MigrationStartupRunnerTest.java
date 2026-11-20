package com.securityexpert.nexus.ui2.service.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

class MigrationStartupRunnerTest {

    @Test
    void emptySchemaIsMigratedBeforeApplicationBeansAreCreated() {
        AtomicBoolean schemaExists = new AtomicBoolean();

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(migrationEnvironment());
            context.registerBean(MigrationStartupRunner.class,
                    () -> new RecordingMigrationStartupRunner(context.getEnvironment(), schemaExists));
            context.registerBean("schemaReadingBean", Object.class, () -> {
                assertTrue(schemaExists.get(), "application bean was created before the empty schema was migrated");
                return new Object();
            });

            context.refresh();

            assertTrue(context.isActive(), "application context must reach its serving state");
        }
    }

    @Test
    void migrationConfigurationPlaceholdersAreResolvedWhenMigrationRuns() {
        RecordingMigrationStartupRunner runner =
                new RecordingMigrationStartupRunner(migrationEnvironment(), new AtomicBoolean());

        runner.migrate();

        assertEquals("jdbc:postgresql://db/ui2", runner.jdbcUrl);
        assertEquals(Path.of("/run/secrets/ui2-db/migrate-user"), runner.userFile);
        assertEquals(Path.of("/run/secrets/ui2-db/migrate-password"), runner.passwordFile);
        assertEquals("classpath:db/migration", runner.migrationLocation);
    }

    @Test
    void disabledMigrationsVerifySchemaVersionWithAppCredentialsWithoutRequiringMigrateFiles() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("ui2.db.url", "jdbc:postgresql://db/ui2")
                .withProperty("ui2.db.migrations-enabled", "false")
                .withProperty("ui2.db.user-file", "/run/secrets/ui2-db/app-user")
                .withProperty("ui2.db.password-file", "/run/secrets/ui2-db/app-password")
                .withProperty("ui2.db.expected-schema-version", "259");
        AtomicBoolean schemaReady = new AtomicBoolean();
        RecordingMigrationStartupRunner runner = new RecordingMigrationStartupRunner(environment, schemaReady);

        runner.migrate();

        assertTrue(schemaReady.get());
        assertNull(runner.userFile, "migrate-user-file must not be read when migrations are disabled");
        assertEquals("jdbc:postgresql://db/ui2", runner.verifiedJdbcUrl);
        assertEquals(Path.of("/run/secrets/ui2-db/app-user"), runner.verifiedAppUserFile);
        assertEquals(Path.of("/run/secrets/ui2-db/app-password"), runner.verifiedAppPasswordFile);
        assertEquals("259", runner.verifiedExpectedVersion);
    }

    private static MockEnvironment migrationEnvironment() {
        return new MockEnvironment()
                .withProperty("configured.jdbc-url", "jdbc:postgresql://db/ui2")
                .withProperty("configured.user-file", "/run/secrets/ui2-db/migrate-user")
                .withProperty("configured.password-file", "/run/secrets/ui2-db/migrate-password")
                .withProperty("ui2.db.url", "${configured.jdbc-url}")
                .withProperty("ui2.db.migrate-user-file", "${configured.user-file}")
                .withProperty("ui2.db.migrate-password-file", "${configured.password-file}");
    }

    private static final class RecordingMigrationStartupRunner extends MigrationStartupRunner {
        private final AtomicBoolean schemaExists;
        private String jdbcUrl;
        private Path userFile;
        private Path passwordFile;
        private String migrationLocation;
        private String verifiedJdbcUrl;
        private Path verifiedAppUserFile;
        private Path verifiedAppPasswordFile;
        private String verifiedExpectedVersion;

        private RecordingMigrationStartupRunner(Environment environment, AtomicBoolean schemaExists) {
            super(environment);
            this.schemaExists = schemaExists;
        }

        @Override
        void migrate(String jdbcUrl, Path userFile, Path passwordFile, String migrationLocation) {
            this.jdbcUrl = jdbcUrl;
            this.userFile = userFile;
            this.passwordFile = passwordFile;
            this.migrationLocation = migrationLocation;
            schemaExists.set(true);
        }

        @Override
        void verifySchemaVersion(String jdbcUrl, Path appUserFile, Path appPasswordFile, String expectedVersion) {
            this.verifiedJdbcUrl = jdbcUrl;
            this.verifiedAppUserFile = appUserFile;
            this.verifiedAppPasswordFile = appPasswordFile;
            this.verifiedExpectedVersion = expectedVersion;
            schemaExists.set(true);
        }
    }
}
