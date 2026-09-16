package com.securityexpert.nexus.ui2.service.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }
}
