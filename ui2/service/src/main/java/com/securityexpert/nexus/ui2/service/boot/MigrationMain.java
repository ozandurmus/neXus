package com.securityexpert.nexus.ui2.service.boot;

import java.nio.file.Path;

import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.securityexpert.nexus.ui2.persistence.FlywayMigrationRunner;

/** Short-lived migration entry point; never starts the service or worker. */
public final class MigrationMain {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationMain.class);

    private MigrationMain() {
    }

    public static void main(String[] args) {
        MigrateResult result = new FlywayMigrationRunner(
                requiredEnv("UI2_DB_URL"),
                Path.of(requiredEnv("UI2_DB_MIGRATE_USER_FILE")),
                Path.of(requiredEnv("UI2_DB_MIGRATE_PASSWORD_FILE")),
                "classpath:db/migration").migrate();
        LOG.info("ui2 migrations applied: executed={} targetSchemaVersion={}",
                result.migrationsExecuted, result.targetSchemaVersion);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return value;
    }
}
