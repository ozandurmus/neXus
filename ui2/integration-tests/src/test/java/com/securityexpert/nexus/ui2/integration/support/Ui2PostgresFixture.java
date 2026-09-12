package com.securityexpert.nexus.ui2.integration.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.flywaydb.core.api.output.MigrateResult;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.securityexpert.nexus.ui2.persistence.FlywayMigrationRunner;

/**
 * The integration harness's PostgreSQL 16 fixture.
 *
 * <p>{@code UI2_0_B1_02_SCHEMA_V1_CONTRACT.md} §7 specifies these tests as
 * "Testcontainers-backed, PostgreSQL 16". The load-bearing requirement is a
 * <b>real PostgreSQL 16 server</b> — Testcontainers is the carrier the
 * contract names, not the property it is asserting — so this fixture
 * carries two carriers and no third:</p>
 *
 * <ol>
 *   <li>{@code UI2_TEST_JDBC_URL} — a superuser DSN for an already-running
 *       PostgreSQL 16 server. Used when set.</li>
 *   <li>Otherwise a Testcontainers {@link PostgreSQLContainer} running
 *       {@code postgres:16}.</li>
 * </ol>
 *
 * <p>If neither is available the fixture <b>throws</b>. It never skips and
 * never degrades to an in-memory or fake database: a missing server is an
 * unproven assertion, and an unproven assertion fails closed (AGENTS.md
 * "UNKNOWN / fail-closed law"). No method here logs or returns a DSN, a
 * user name or a password — failures name the failure category only
 * (C1 §6.2).</p>
 *
 * <h2>Per-class isolation and the Flyway-before-app ordering</h2>
 *
 * <p>Each fixture instance bootstraps a brand-new database owned by
 * {@code ui2_migrate}, creates the two roles if the server does not already
 * have them (contract §2: role creation is the harness's bootstrap step,
 * never {@code V1}'s), and drops the database in {@link #close()}.</p>
 *
 * <p>The harness enforces §7 test 1's ordering structurally rather than by
 * convention: the fresh database has {@code CONNECT} revoked from
 * {@code PUBLIC} and granted only to {@code ui2_migrate}; {@code ui2_app}
 * receives {@code CONNECT} only after {@link #runFlyway()} returns. A
 * {@code ui2_app} connection therefore <em>cannot</em> be opened before
 * Flyway has run — which is exactly what {@code FlywayPrecedesAppAccessTest}
 * asserts. Database-level {@code CONNECT} grants are harness territory:
 * {@code V1} neither grants nor revokes them.</p>
 */
public final class Ui2PostgresFixture implements AutoCloseable {

    /** Superuser DSN for an already-running PostgreSQL 16 server. */
    public static final String JDBC_URL_ENV = "UI2_TEST_JDBC_URL";

    public static final String MIGRATE_ROLE = "ui2_migrate";
    public static final String APP_ROLE = "ui2_app";

    /**
     * The migration location the packaged image uses. {@code V1..V4} live in
     * {@code service/src/main/resources/db/migration} (B1-1 Amendment
     * B1-1-A item 3) and are packaged into {@code service} from there; this
     * module does not depend on {@code service}, so the same files are
     * handed to Flyway by filesystem path rather than by classpath scan.
     * They are the identical, single copy of the migrations — not a test
     * duplicate.
     */
    private static final String MIGRATION_RELATIVE_DIR = "service/src/main/resources/db/migration";

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile PostgreSQLContainer<?> container;

    private final String adminUrl;
    private final String adminUser;
    private final String adminPassword;
    private final String databaseName;
    private final String migratePassword;
    private final String appPassword;

    private boolean migrated;
    private Path secretDir;

    private Ui2PostgresFixture(String adminUrl, String adminUser, String adminPassword, String databaseName) {
        this.adminUrl = adminUrl;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
        this.databaseName = databaseName;
        this.migratePassword = randomPassword();
        this.appPassword = randomPassword();
    }

    // -----------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------

    /**
     * Bootstraps a fresh, empty database. Flyway has <b>not</b> run and
     * {@code ui2_app} cannot yet connect; the caller drives
     * {@link #runFlyway()} itself. Used by the tests whose whole subject is
     * the migrate-then-connect ordering.
     */
    public static Ui2PostgresFixture create(String label) {
        Ui2PostgresFixture fixture = new Ui2PostgresFixture(
                adminUrl(), adminUser(), adminPassword(), databaseName(label));
        fixture.bootstrap();
        return fixture;
    }

    /** {@link #create(String)} followed by one {@link #runFlyway()}. */
    public static Ui2PostgresFixture createAndMigrate(String label) {
        Ui2PostgresFixture fixture = create(label);
        try {
            fixture.runFlyway();
        } catch (RuntimeException e) {
            fixture.close();
            throw e;
        }
        return fixture;
    }

    // -----------------------------------------------------------------
    // Carrier resolution
    // -----------------------------------------------------------------

    private static String adminUrl() {
        String configured = System.getenv(JDBC_URL_ENV);
        if (configured != null && !configured.isBlank()) {
            return stripCredentials(configured.strip());
        }
        return container().getJdbcUrl();
    }

    private static String adminUser() {
        String configured = System.getenv(JDBC_URL_ENV);
        if (configured != null && !configured.isBlank()) {
            String user = queryParameter(configured, "user");
            return user == null ? "postgres" : user;
        }
        return container().getUsername();
    }

    private static String adminPassword() {
        String configured = System.getenv(JDBC_URL_ENV);
        if (configured != null && !configured.isBlank()) {
            String password = queryParameter(configured, "password");
            return password == null ? "" : password;
        }
        return container().getPassword();
    }

    /**
     * Starts (once per JVM) the Testcontainers carrier. This code path is
     * deliberately kept and kept correct even where no container runtime
     * exists: the environment that has one must not need a code change to
     * use it.
     */
    private static PostgreSQLContainer<?> container() {
        PostgreSQLContainer<?> existing = container;
        if (existing != null) {
            return existing;
        }
        synchronized (Ui2PostgresFixture.class) {
            if (container == null) {
                PostgreSQLContainer<?> started;
                try {
                    started = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));
                    started.start();
                } catch (RuntimeException e) {
                    throw new IllegalStateException(
                            "no real PostgreSQL 16 server is available: " + JDBC_URL_ENV
                                    + " is unset and no container runtime could start a postgres:16 container. "
                                    + "These tests require a real PostgreSQL 16 and must never be skipped or "
                                    + "faked -- set " + JDBC_URL_ENV + " to a superuser DSN or provide a "
                                    + "container runtime. Cause: " + e.getClass().getName(), e);
                }
                container = started;
                Runtime.getRuntime().addShutdownHook(new Thread(started::stop));
            }
            return container;
        }
    }

    // -----------------------------------------------------------------
    // Bootstrap / teardown
    // -----------------------------------------------------------------

    private void bootstrap() {
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
            ensureRole(statement, MIGRATE_ROLE, migratePassword);
            ensureRole(statement, APP_ROLE, appPassword);
            statement.execute("CREATE DATABASE " + quoteIdentifier(databaseName)
                    + " OWNER " + quoteIdentifier(MIGRATE_ROLE));
            // §7 test 1's ordering, enforced by the harness rather than
            // asserted about it: only ui2_migrate may connect until Flyway
            // has finished.
            statement.execute("REVOKE CONNECT ON DATABASE " + quoteIdentifier(databaseName) + " FROM PUBLIC");
            statement.execute("GRANT CONNECT ON DATABASE " + quoteIdentifier(databaseName)
                    + " TO " + quoteIdentifier(MIGRATE_ROLE));
        } catch (SQLException e) {
            throw new IllegalStateException("could not bootstrap the integration test database: "
                    + e.getSQLState(), e);
        }
    }

    private void ensureRole(Statement statement, String role, String password) throws SQLException {
        statement.execute("DO $do$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = "
                + sqlLiteral(role) + ") THEN CREATE ROLE " + quoteIdentifier(role) + " LOGIN; END IF; END $do$;");
        // Set a per-run password so the fixture works identically against a
        // trust-auth server (where it is ignored) and a password-auth one.
        statement.execute("ALTER ROLE " + quoteIdentifier(role) + " LOGIN PASSWORD " + sqlLiteral(password));
    }

    @Override
    public void close() {
        deleteSecretDir();
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
            statement.execute("REVOKE CONNECT ON DATABASE " + quoteIdentifier(databaseName) + " FROM PUBLIC, "
                    + quoteIdentifier(MIGRATE_ROLE) + ", " + quoteIdentifier(APP_ROLE));
            statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = "
                    + sqlLiteral(databaseName));
            statement.execute("DROP DATABASE IF EXISTS " + quoteIdentifier(databaseName));
        } catch (SQLException e) {
            throw new IllegalStateException("could not drop the integration test database: " + e.getSQLState(), e);
        }
    }

    // -----------------------------------------------------------------
    // Flyway
    // -----------------------------------------------------------------

    /**
     * Runs Flyway against the {@code ui2_migrate} DSN through the
     * production {@link FlywayMigrationRunner} — the same code path the
     * packaged image uses, credentials passed as separate file-backed
     * arguments and never concatenated into the URL (C1 §6.2). Safe to call
     * repeatedly; §7 test 2 depends on that.
     */
    public MigrateResult runFlyway() {
        MigrateResult result = new FlywayMigrationRunner(
                jdbcUrl(databaseName), userFile(), passwordFile(), "filesystem:" + migrationDirectory())
                .migrate();
        if (!migrated) {
            grantAppConnect();
            migrated = true;
        }
        return result;
    }

    private void grantAppConnect() {
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
            statement.execute("GRANT CONNECT ON DATABASE " + quoteIdentifier(databaseName)
                    + " TO " + quoteIdentifier(APP_ROLE));
        } catch (SQLException e) {
            throw new IllegalStateException("could not grant CONNECT to the application role: "
                    + e.getSQLState(), e);
        }
    }

    public static Path migrationDirectory() {
        Path directory = ui2Root().resolve(MIGRATION_RELATIVE_DIR);
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("migration directory not found at the expected repository location");
        }
        return directory;
    }

    /**
     * Gradle runs the {@code Test} task with the module's project directory
     * as the working directory, so {@code integration-tests/..} is
     * {@code ui2/} (mirrors {@code SchemaV1Fixture#ui2Root()}).
     */
    private static Path ui2Root() {
        return Paths.get(System.getProperty("user.dir")).getParent();
    }

    // -----------------------------------------------------------------
    // Connections
    // -----------------------------------------------------------------

    public String databaseName() {
        return databaseName;
    }

    public boolean migrated() {
        return migrated;
    }

    public Connection adminConnection() throws SQLException {
        return connect(adminUrl, adminUser, adminPassword);
    }

    /** A {@code ui2_migrate} connection to this fixture's own database. */
    public Connection migrateConnection() throws SQLException {
        return connect(jdbcUrl(databaseName), MIGRATE_ROLE, migratePassword);
    }

    /**
     * A {@code ui2_app} connection to this fixture's own database. Refuses
     * before {@link #runFlyway()} has returned — the in-process half of the
     * §7 test 1 ordering rule, so a test cannot accidentally depend on app
     * access that precedes the migration.
     */
    public Connection appConnection() throws SQLException {
        if (!migrated) {
            throw new IllegalStateException(
                    "ui2_app access was requested before Flyway completed -- contract §7 test 1 forbids it");
        }
        return connect(jdbcUrl(databaseName), APP_ROLE, appPassword);
    }

    /**
     * Attempts a raw {@code ui2_app} connection with no ordering guard, so a
     * test can prove the <em>server</em> refuses it rather than merely that
     * this class does.
     */
    public Connection attemptAppConnectionWithoutOrderingGuard() throws SQLException {
        return connect(jdbcUrl(databaseName), APP_ROLE, appPassword);
    }

    public DataSource appDataSource() {
        if (!migrated) {
            throw new IllegalStateException(
                    "ui2_app access was requested before Flyway completed -- contract §7 test 1 forbids it");
        }
        return dataSource(jdbcUrl(databaseName), APP_ROLE, appPassword);
    }

    public DataSource migrateDataSource() {
        return dataSource(jdbcUrl(databaseName), MIGRATE_ROLE, migratePassword);
    }

    private static Connection connect(String url, String user, String password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        return DriverManager.getConnection(url, properties);
    }

    private static DataSource dataSource(String url, String user, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(user);
        source.setPassword(password);
        return source;
    }

    private String jdbcUrl(String database) {
        int query = adminUrl.indexOf('?');
        String base = query < 0 ? adminUrl : adminUrl.substring(0, query);
        int slash = base.lastIndexOf('/');
        return base.substring(0, slash + 1) + database;
    }

    // -----------------------------------------------------------------
    // Secret files for FlywayMigrationRunner
    // -----------------------------------------------------------------

    private Path userFile() {
        return secretFile("user", MIGRATE_ROLE);
    }

    private Path passwordFile() {
        return secretFile("password", migratePassword);
    }

    private Path secretFile(String name, String value) {
        try {
            if (secretDir == null) {
                secretDir = Files.createTempDirectory("ui2-harness-");
                secretDir.toFile().setReadable(false, false);
                secretDir.toFile().setReadable(true, true);
                secretDir.toFile().setWritable(false, false);
                secretDir.toFile().setWritable(true, true);
                secretDir.toFile().setExecutable(false, false);
                secretDir.toFile().setExecutable(true, true);
            }
            Path file = secretDir.resolve(name);
            if (!Files.exists(file)) {
                Files.writeString(file, value);
                file.toFile().setReadable(false, false);
                file.toFile().setReadable(true, true);
                file.toFile().setWritable(false, false);
                file.toFile().setWritable(true, true);
            }
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("could not materialise the harness credential file", e);
        }
    }

    private void deleteSecretDir() {
        if (secretDir == null) {
            return;
        }
        try (var paths = Files.walk(secretDir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException("could not delete a harness credential file", e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("could not delete the harness credential directory", e);
        }
        secretDir = null;
    }

    // -----------------------------------------------------------------
    // Small helpers
    // -----------------------------------------------------------------

    private static String databaseName(String label) {
        String sanitized = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (sanitized.length() > 24) {
            sanitized = sanitized.substring(0, 24);
        }
        return "ui2_test_" + sanitized + "_" + COUNTER.incrementAndGet() + "_"
                + Long.toString(System.nanoTime() & 0xffffffL, 36);
    }

    private static String randomPassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String stripCredentials(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static String queryParameter(String url, String name) {
        int query = url.indexOf('?');
        if (query < 0) {
            return null;
        }
        for (String pair : url.substring(query + 1).split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return pair.substring(equals + 1);
            }
        }
        return null;
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String sqlLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
