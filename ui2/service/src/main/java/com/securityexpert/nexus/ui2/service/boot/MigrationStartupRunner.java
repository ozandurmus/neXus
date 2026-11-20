package com.securityexpert.nexus.ui2.service.boot;

import java.nio.file.Path;

import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;

import com.securityexpert.nexus.ui2.persistence.FlywayMigrationRunner;

/**
 * Applies migrations at startup with the migrate credential — or, when
 * {@code ui2.db.migrations-enabled=false} (Step 4 of
 * {@code docs/design/DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md} §3.2 / §4.2),
 * verifies read-only via {@code ui2_schema_version_check(expected_version)}
 * using the application credential so {@code ui2-service} does not need the
 * {@code ui2_migrate} secret mounted.
 *
 * <p>`C1` makes Flyway the sole migration authority and separates the roles:
 * {@code ui2_migrate} owns the schema, {@code ui2_app} / {@code ui2_service}
 * may not run DDL. Those are different credentials, so when migrations are
 * enabled at service startup this runner reads its own pair of secret files
 * rather than reusing the DataSource the application serves requests with.</p>
 *
 * <p>A failed migration or failed schema version check stops the process.
 * Serving requests against a schema that did not fully apply is the situation
 * the fail-closed rule exists to prevent.</p>
 */
public class MigrationStartupRunner implements BeanFactoryPostProcessor, PriorityOrdered {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationStartupRunner.class);

    static final String DEFAULT_EXPECTED_SCHEMA_VERSION = "259";

    private final Environment environment;

    public MigrationStartupRunner(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        migrate();
    }

    void migrate() {
        boolean migrationsEnabled = Boolean.parseBoolean(
                environment.getProperty("ui2.db.migrations-enabled", "true"));
        if (!migrationsEnabled) {
            verifySchemaVersion(
                    environment.getRequiredProperty("ui2.db.url"),
                    Path.of(environment.getRequiredProperty("ui2.db.user-file")),
                    Path.of(environment.getRequiredProperty("ui2.db.password-file")),
                    environment.getProperty("ui2.db.expected-schema-version", DEFAULT_EXPECTED_SCHEMA_VERSION));
            return;
        }
        migrate(
                environment.getRequiredProperty("ui2.db.url"),
                Path.of(environment.getRequiredProperty("ui2.db.migrate-user-file")),
                Path.of(environment.getRequiredProperty("ui2.db.migrate-password-file")),
                environment.getProperty("ui2.db.migration-location", "classpath:db/migration"));
    }

    void migrate(String jdbcUrl, Path userFile, Path passwordFile, String migrationLocation) {
        MigrateResult result = new FlywayMigrationRunner(
                jdbcUrl, userFile, passwordFile, migrationLocation).migrate();
        // Counts and versions only: never a DSN, never a credential.
        LOG.info("ui2 migrations applied: executed={} targetSchemaVersion={}",
                result.migrationsExecuted, result.targetSchemaVersion);
    }

    void verifySchemaVersion(String jdbcUrl, Path appUserFile, Path appPasswordFile, String expectedVersion) {
        if (!FlywayMigrationRunner.verifySchemaVersion(jdbcUrl, appUserFile, appPasswordFile, expectedVersion)) {
            throw new IllegalStateException("ui2 schema version check failed: expectedVersion=" + expectedVersion);
        }
        LOG.info("ui2 schema version verified read-only: expectedSchemaVersion={}", expectedVersion);
    }
}
