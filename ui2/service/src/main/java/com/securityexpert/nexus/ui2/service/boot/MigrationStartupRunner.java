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
 *
 * <p>A highest-priority {@link BeanFactoryPostProcessor} runs migrations
 * before Spring creates ordinary application beans. This is earlier than
 * every {@code ApplicationRunner}, including
 * {@link FirstBootIdentitySeedingRunner}, so {@code local_credentials}
 * exists before first-boot seeding queries it.</p>
 */
public class MigrationStartupRunner implements BeanFactoryPostProcessor, PriorityOrdered {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationStartupRunner.class);

    private final String jdbcUrl;
    private final Path userFile;
    private final Path passwordFile;
    private final String migrationLocation;

    public MigrationStartupRunner(
            String jdbcUrl,
            String userFile,
            String passwordFile,
            String migrationLocation) {
        this.jdbcUrl = jdbcUrl;
        this.userFile = Path.of(userFile);
        this.passwordFile = Path.of(passwordFile);
        this.migrationLocation = migrationLocation;
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
        MigrateResult result = new FlywayMigrationRunner(
                jdbcUrl, userFile, passwordFile, migrationLocation).migrate();
        // Counts and versions only: never a DSN, never a credential.
        LOG.info("ui2 migrations applied: executed={} targetSchemaVersion={}",
                result.migrationsExecuted, result.targetSchemaVersion);
    }
}
