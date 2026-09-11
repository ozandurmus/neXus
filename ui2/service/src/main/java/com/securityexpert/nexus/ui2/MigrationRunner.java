package com.securityexpert.nexus.ui2;

import org.flywaydb.core.Flyway;

/** Runs the deployment-controlled migration step with the supplied DSN. */
public final class MigrationRunner {
    private MigrationRunner() {
    }

    public static void migrate(String dsn) {
        if (dsn == null || dsn.isBlank() || !dsn.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("migration DSN is missing or unsupported");
        }
        Flyway.configure().dataSource(dsn.strip(), null, null)
                .locations("classpath:db/migration").load().migrate();
    }
}
