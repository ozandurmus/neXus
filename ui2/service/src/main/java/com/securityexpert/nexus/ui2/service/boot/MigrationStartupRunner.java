package com.securityexpert.nexus.ui2.service.boot;

import java.nio.file.Path;

import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.securityexpert.nexus.ui2.persistence.FlywayMigrationRunner;

/**
 * Applies migrations at startup, with the migrate credential — never the
 * application one.
 *
 * <p>`C1` makes Flyway the sole migration authority and separates the roles:
 * {@code ui2_migrate} owns the schema, {@code ui2_app} may not run DDL. Those
 * are two different credentials, so this runner reads its own pair of secret
 * files rather than reusing the DataSource the application serves requests
 * with.</p>
 *
 * <p>A failed migration stops the process. Serving requests against a schema
 * that did not fully apply is the situation the fail-closed rule exists to
 * prevent, and a half-migrated database is worse than an unavailable one.</p>
 */
@Component
public class MigrationStartupRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationStartupRunner.class);

    private final String jdbcUrl;
    private final Path userFile;
    private final Path passwordFile;
    private final String migrationLocation;

    public MigrationStartupRunner(
            @Value("${ui2.db.url}") String jdbcUrl,
            @Value("${ui2.db.migrate-user-file}") String userFile,
            @Value("${ui2.db.migrate-password-file}") String passwordFile,
            @Value("${ui2.db.migration-location:classpath:db/migration}") String migrationLocation) {
        this.jdbcUrl = jdbcUrl;
        this.userFile = Path.of(userFile);
        this.passwordFile = Path.of(passwordFile);
        this.migrationLocation = migrationLocation;
    }

    @Override
    public void run(ApplicationArguments args) {
        MigrateResult result = new FlywayMigrationRunner(
                jdbcUrl, userFile, passwordFile, migrationLocation).migrate();
        // Counts and versions only: never a DSN, never a credential.
        LOG.info("ui2 migrations applied: executed={} targetSchemaVersion={}",
                result.migrationsExecuted, result.targetSchemaVersion);
    }
}
