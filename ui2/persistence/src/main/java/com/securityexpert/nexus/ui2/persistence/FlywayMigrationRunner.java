package com.securityexpert.nexus.ui2.persistence;

import java.nio.file.Path;

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
}
