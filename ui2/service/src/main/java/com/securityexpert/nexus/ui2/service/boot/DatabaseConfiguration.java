package com.securityexpert.nexus.ui2.service.boot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import javax.sql.DataSource;

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

    @Bean
    public DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(jdbcUrl);
        source.setUser(readSecretFile(userFile, "db_app_user"));
        source.setPassword(readSecretFile(passwordFile, "db_app_password"));
        return source;
    }
}
