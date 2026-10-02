package com.securityexpert.nexus.ui2.service.boot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.core.env.Environment;

import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's database wiring, under `C1` §6's secret-file rule.
 *
 * <p>The URL is configuration; the user and password are **files**, read at
 * startup and never taken from a property, an environment default or a build
 * argument. A missing, unreadable or empty file is a startup failure, not a
 * fallback — an application that silently starts with no credential is the
 * failure mode the rule exists to prevent.</p>
 *
 * <p>The value read from a credential file is never logged, and never appears
 * in an exception message: the messages below name the <em>path</em> and the
 * <em>purpose</em>, which is what an operator needs to fix it.</p>
 */
@Configuration
public class DatabaseConfiguration {

    private final String jdbcUrl;
    private final Path userFile;
    private final Path passwordFile;

    public DatabaseConfiguration(
            @Value("${ui2.db.url}") String jdbcUrl,
            @Value("${ui2.db.user-file}") String userFile,
            @Value("${ui2.db.password-file}") String passwordFile) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "ui2.db.url");
        this.userFile = Path.of(Objects.requireNonNull(userFile, "ui2.db.user-file"));
        this.passwordFile = Path.of(Objects.requireNonNull(passwordFile, "ui2.db.password-file"));
    }

    /** Reads one secret file, failing closed. Never returns an empty value. */
    static String readSecretFile(Path path, String purpose) {
        String value;
        try {
            value = Files.readString(path).strip();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "ui2_secret_file_unreadable: purpose=" + purpose + " path=" + path, e);
        }
        if (value.isEmpty()) {
            throw new IllegalStateException(
                    "ui2_secret_file_empty: purpose=" + purpose + " path=" + path);
        }
        return value;
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(Environment environment) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(jdbcUrl);
        source.setUser(readSecretFile(userFile, "db_app_user"));
        source.setPassword(readSecretFile(passwordFile, "db_app_password"));
        HikariDataSource pool = new HikariDataSource();
        pool.setDataSource(source);
        pool.setPoolName("service-database");
        pool.setMaximumPoolSize(environment.getProperty("ui2.db.pool.maximum-pool-size", Integer.class, 20));
        pool.setMinimumIdle(environment.getProperty("ui2.db.pool.minimum-idle", Integer.class, 2));
        pool.setConnectionTimeout(environment.getProperty("ui2.db.pool.connection-timeout", Long.class, 10_000L));
        pool.setMaxLifetime(environment.getProperty("ui2.db.pool.max-lifetime", Long.class, 1_800_000L));
        pool.setLeakDetectionThreshold(environment.getProperty("ui2.db.pool.leak-detection-threshold", Long.class, 60_000L));
        return pool;
    }
}
