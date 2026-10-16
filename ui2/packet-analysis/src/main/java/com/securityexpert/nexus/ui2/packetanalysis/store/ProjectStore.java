package com.securityexpert.nexus.ui2.packetanalysis.store;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One case project: a directory holding {@code project.duckdb} (single writer) and its spill directory.
 *
 * <p>Opening runs the schema creation and migrations 1..6 exactly as the reference project does. Every
 * {@link #connect} opens a fresh DuckDB database handle with explicit {@code memory_limit} and {@code threads},
 * insertion order not preserved, external access disabled unless requested (only the batch CSV load needs it), and
 * automatic extension install/load disabled. Connections are serialised by one lock, as in the reference.
 */
public final class ProjectStore {

    /** The engine version recorded as {@code analysis_version}; a change clears the saved report. */
    public static final String ANALYSIS_VERSION = "0.3.10";

    /** The current schema version. */
    public static final int SCHEMA_VERSION = 6;

    /** Resource settings of one project. */
    public record Settings(Integer memoryLimitMib, long totalMemoryBytes, int threads) {

        public Settings {
            MemoryBudget.validateOverride(memoryLimitMib);
            if (threads < 1) {
                throw new IllegalArgumentException("DuckDB threads must be at least 1");
            }
        }

        /** Defaults from this JVM's view of memory and processors. */
        public static Settings defaults(Integer memoryLimitMib) {
            return new Settings(memoryLimitMib, MemoryBudget.systemTotalMemoryBytes(),
                    MemoryBudget.defaultThreads(Runtime.getRuntime().availableProcessors()));
        }
    }

    private static final String[] V1_V2_COLUMNS = {"frame_hash", "icmp_id", "icmp_seq", "icmp_type", "dns_id",
        "dns_response"};

    private final Path path;
    private final Settings settings;
    private final ReentrantLock lock = new ReentrantLock();

    private ProjectStore(Path path, Settings settings) {
        this.path = path;
        this.settings = settings;
    }

    /** Create or open the project directory and bring its schema to version 6. */
    public static ProjectStore open(Path dir, Settings settings) {
        try {
            Files.createDirectories(dir);
            Path real = dir.toRealPath();
            ProjectStore project = new ProjectStore(real, settings);
            project.initialise();
            return project;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path path() {
        return path;
    }

    public Settings settings() {
        return settings;
    }

    private void initialise() {
        try (Db db = connect()) {
            db.execute(Sql.load("store/01_create_settings"));
            db.execute(Sql.load("store/02_create_captures"));
            db.execute(Sql.load("store/03_create_packets"));
            db.execute(Sql.load("store/04_interrupted_ingests"));
            Object version = get(db, "schema_version", null);
            if (version != null && !Set.of(1L, 2L, 3L, 4L, 5L, 6L).contains(version)) {
                throw new IllegalArgumentException("Unsupported project schema version");
            }
            long v = version == null ? -1 : (Long) version;
            if (v == 1 || v == 2) {
                for (String column : V1_V2_COLUMNS) {
                    db.execute(Sql.load("store/05_v1_v2_add_column").replace("{column}", column)
                            .replace("{type}", PacketColumns.TYPES.get(column)));
                }
                db.execute(Sql.load("store/06_v1_v2_stale"));
                set(db, "report", null);
            }
            db.execute(Sql.load("store/07_create_excluded_frames"));
            if (v == 1 || v == 2 || v == 3) {
                db.execute(Sql.load("store/08_v1_v3_stale"));
                set(db, "report", null);
            }
            List<Object[]> legacyOffload = db.query(Sql.load("store/09_legacy_offload_stale"));
            if (!legacyOffload.isEmpty()) {
                set(db, "report", null);
            }
            db.execute(Sql.load("store/10_add_vendor"));
            db.execute(Sql.load("store/11_add_path_fields"));
            if (version != null && v < 6) {
                db.execute(Sql.load("store/12_pre_v6_stale"));
                set(db, "report", null);
            }
            set(db, "schema_version", (long) SCHEMA_VERSION);
            if (!ANALYSIS_VERSION.equals(get(db, "analysis_version", null))) {
                set(db, "report", null);
                set(db, "analysis_version", ANALYSIS_VERSION);
            }
        }
    }

    /**
     * System property that overrides the DuckDB thread count of every connection. Parity runs set it to 1: with
     * several threads, tie order (ORDER BY ... LIMIT, arg_min/arg_max, GROUP BY output order) is not deterministic
     * in the reference either.
     */
    public static final String THREADS_PROPERTY = "packetanalysis.duckdb.threads";

    /** The DuckDB thread count used for connections: the system property if set, else the settings. */
    public int threads() {
        Integer forced = Integer.getInteger(THREADS_PROPERTY);
        if (forced != null && forced < 1) {
            throw new IllegalArgumentException("DuckDB threads must be at least 1");
        }
        return forced != null ? forced : settings.threads();
    }

    /** A connection with external file access disabled. */
    public Db connect() {
        return connect(false);
    }

    /** A connection; {@code allowExternal} enables file access for the batch CSV load only. */
    public Db connect(boolean allowExternal) {
        Properties p = new Properties();
        int base = MemoryBudget.memoryLimitMib(settings.memoryLimitMib(), settings.totalMemoryBytes());
        p.setProperty("memory_limit", MemoryPlan.memoryLimitMib(settings.memoryLimitMib(), base) + "MiB");
        p.setProperty("threads", Integer.toString(threads()));
        p.setProperty("preserve_insertion_order", "false");
        p.setProperty("enable_external_access", Boolean.toString(allowExternal));
        MemoryPlan.applySpill(p, MemoryPlan.spillDirectory(path));
        p.setProperty("autoinstall_known_extensions", "false");
        p.setProperty("autoload_known_extensions", "false");
        lock.lock();
        Db db;
        try {
            db = new Db(path.resolve("project.duckdb"), p, lock);
        } catch (SQLException e) {
            lock.unlock();
            throw Db.translate(e);
        } catch (RuntimeException e) {
            lock.unlock();
            throw e;
        }
        try {
            if (settings.memoryLimitMib() == null
                    && ((Number) db.query(Sql.load("store/settings_table_exists")).get(0)[0]).longValue() != 0) {
                Object prefs = get(db, "preferences", Map.of());
                if (prefs instanceof Map<?, ?> m && m.get("memory_limit_mb") != null) {
                    Object configured = m.get("memory_limit_mb");
                    if (!(configured instanceof Long l) || l > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException("DuckDB memory limit must be at least 64 MiB");
                    }
                    int mib = MemoryPlan.clampMib(MemoryBudget.memoryLimitMib((int) (long) l, settings.totalMemoryBytes()));
                    db.execute("SET memory_limit='" + mib + "MiB'");
                }
            }
        } catch (RuntimeException e) {
            db.close();
            throw e;
        }
        return db;
    }

    /** {@code Project.get}: the JSON-decoded setting, or the default. */
    public static Object get(Db db, String key, Object dflt) {
        List<Object[]> rows = db.query(Sql.load("store/settings_get"), key);
        return rows.isEmpty() ? dflt : PyJson.loads((String) rows.get(0)[0]);
    }

    /** {@code Project.set}: store the JSON text of a value. */
    public static void set(Db db, String key, Object value) {
        db.execute(Sql.load("store/settings_set"), key, PyJson.dumps(value));
    }
}
