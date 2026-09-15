package com.securityexpert.nexus.ui2.service.boot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class MigrationStartupRunnerTest {

    @Test
    void emptySchemaIsMigratedBeforeApplicationBeansAreCreated() {
        AtomicBoolean schemaExists = new AtomicBoolean();

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MigrationStartupRunner.class,
                    () -> new RecordingMigrationStartupRunner(schemaExists));
            context.registerBean("schemaReadingBean", Object.class, () -> {
                assertTrue(schemaExists.get(), "application bean was created before the empty schema was migrated");
                return new Object();
            });

            context.refresh();

            assertTrue(context.isActive(), "application context must reach its serving state");
        }
    }

    private static final class RecordingMigrationStartupRunner extends MigrationStartupRunner {
        private final AtomicBoolean schemaExists;

        private RecordingMigrationStartupRunner(AtomicBoolean schemaExists) {
            super("unused", "unused", "unused", "unused");
            this.schemaExists = schemaExists;
        }

        @Override
        void migrate() {
            schemaExists.set(true);
        }
    }
}
