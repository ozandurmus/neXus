package com.securityexpert.nexus.ui2.packetanalysis.topology;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import com.securityexpert.nexus.ui2.packetanalysis.store.MemoryPlan;
import com.securityexpert.nexus.ui2.packetanalysis.store.SpillGuard;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The case project directory ({@code project.duckdb}) as the topology stage reads and writes it: the reference
 * {@code Project.connect} settings, {@code get}/{@code set} on the {@code settings} table, {@code inventory()} and
 * {@code rows()}. Schema creation and migrations belong to the ingest stage and are not repeated here.
 */
public final class ProjectDb {

    private static final Map<String, String> SQL_CACHE = new ConcurrentHashMap<>();

    /** System property overriding the DuckDB thread count (parity runs). */
    public static final String THREADS_PROPERTY = "packetanalysis.duckdb.threads";

    /** System property with the DuckDB memory limit in MiB (the end-to-end entry sets it from its budget). */
    public static final String MEMORY_LIMIT_PROPERTY = MemoryPlan.MEMORY_LIMIT_PROPERTY;

    private final Path path;

    public ProjectDb(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public Path path() {
        return path;
    }

    /** The reference memory budget: a quarter of RAM, at least 1 GiB, at most 8 GiB (in MiB). */
    static long memoryBudgetMb(Long override) {
        if (override == null) {
            String configured = System.getProperty(MEMORY_LIMIT_PROPERTY);
            if (configured != null && !configured.isBlank()) {
                override = Long.parseLong(configured.strip());
            }
        }
        if (override != null) {
            if (override < 64) {
                throw new IllegalArgumentException("DuckDB memory limit must be at least 64 MiB");
            }
            return MemoryPlan.clampMib(override);
        }
        long total = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                .getTotalMemorySize();
        return MemoryPlan.clampMib(Math.min(8192, Math.max(1024, total / (4L * 1024 * 1024))));
    }

    /**
     * The reference thread count, {@code min(8, max(1, cpus - 1))}. The system property
     * {@value #THREADS_PROPERTY} overrides it: parity runs use 1 on both sides, because DuckDB's parallel
     * aggregation breaks ties ({@code ORDER BY spread_us}, {@code arg_min}) differently from run to run.
     */
    static int threads() {
        Integer forced = Integer.getInteger(THREADS_PROPERTY);
        if (forced != null && forced > 0) {
            return forced;
        }
        int cpus = Runtime.getRuntime().availableProcessors();
        return Math.min(8, Math.max(1, cpus - 1));
    }

    /** Opens a connection with the reference configuration (threads, insertion order, no external access). */
    public Db connect() {
        Properties props = new Properties();
        props.setProperty("memory_limit", memoryBudgetMb(null) + "MiB");
        props.setProperty("threads", Integer.toString(threads()));
        props.setProperty("preserve_insertion_order", "false");
        props.setProperty("enable_external_access", "false");
        MemoryPlan.applySpill(props, MemoryPlan.spillDirectory(path));
        props.setProperty("autoinstall_known_extensions", "false");
        props.setProperty("autoload_known_extensions", "false");
        try {
            Connection c = SpillGuard.track(DriverManager.getConnection("jdbc:duckdb:" + path.resolve("project.duckdb"), props));
            Db db = new Db(c);
            Object hasSettings = db.fetchOne(
                    "SELECT count(*) FROM duckdb_tables() WHERE table_name='settings'", List.of()).get(0);
            if (Py.truthy(hasSettings)) {
                Object prefs = db.get("preferences", Map.of());
                if (prefs instanceof Map<?, ?> m && m.get("memory_limit_mb") != null) {
                    long mb = memoryBudgetMb(Py.toInt(m.get("memory_limit_mb")));
                    db.execute("SET memory_limit='" + mb + "MiB'", List.of());
                }
            }
            return db;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open " + path + ": " + e.getMessage(), e);
        }
    }

    /** {@code Project.inventory()}: captures ordered by name, inventory parsed, caplen stats backfilled. */
    public List<Map<String, Object>> inventory() {
        try (Db db = connect()) {
            List<Map<String, Object>> data = db.rows(
                    "SELECT id,path,name,state,checkpoint,inventory,error FROM captures ORDER BY name", List.of());
            for (Map<String, Object> capture : data) {
                @SuppressWarnings("unchecked")
                Map<String, Object> info = (Map<String, Object>) PyJson.loads((String) capture.get("inventory"));
                if ("ready".equals(capture.get("state")) && !info.containsKey("observed_max_caplen")) {
                    List<Object> stats = db.fetchOne(
                            """
                            SELECT max(caplen),min(caplen) FILTER(WHERE caplen<wirelen),
                                                    max(caplen) FILTER(WHERE caplen<wirelen) FROM packets WHERE capture_id=?""",
                            List.of(capture.get("id")));
                    info.put("observed_max_caplen", stats.get(0));
                    info.put("truncated_caplen_min", stats.get(1));
                    info.put("truncated_caplen_max", stats.get(2));
                    db.execute("UPDATE captures SET inventory=? WHERE id=?",
                            List.of(PyJson.dumps(info), capture.get("id")));
                }
                capture.put("inventory", info);
            }
            return data;
        }
    }

    /** A SQL text resource copied verbatim from the reference, with its f-string fields filled in. */
    static String sql(String name, Map<String, String> fields) {
        String text = SQL_CACHE.computeIfAbsent(name, n -> {
            try (InputStream in = ProjectDb.class.getResourceAsStream("/packetanalysis/topology/sql/" + n + ".sql")) {
                if (in == null) {
                    throw new IllegalStateException("missing SQL resource " + n);
                }
                String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        for (Map.Entry<String, String> f : fields.entrySet()) {
            text = text.replace("{" + f.getKey() + "}", f.getValue());
        }
        return text;
    }

    static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    /** One open connection. */
    public static final class Db implements AutoCloseable {

        private final Connection connection;

        Db(Connection connection) {
            this.connection = connection;
        }

        /** {@code rows(db, sql, params)}: dicts keyed by column name, values in the reference types. */
        public List<Map<String, Object>> rows(String sql, List<?> params) {
            try (PreparedStatement st = prepare(sql, params); ResultSet rs = st.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        row.put(md.getColumnLabel(i), value(rs.getObject(i)));
                    }
                    out.add(row);
                }
                return out;
            } catch (SQLException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }

        /** {@code db.execute(sql, params).fetchone()}; null when there is no row. */
        public List<Object> fetchOne(String sql, List<?> params) {
            try (PreparedStatement st = prepare(sql, params); ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                int n = rs.getMetaData().getColumnCount();
                List<Object> row = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) {
                    row.add(value(rs.getObject(i)));
                }
                return row;
            } catch (SQLException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }

        public void execute(String sql, List<?> params) {
            try (PreparedStatement st = prepare(sql, params)) {
                st.execute();
            } catch (SQLException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }

        /** One result row, read column by column; the cursor must not be kept. */
        @FunctionalInterface
        public interface RowSink {
            void accept(ResultSet row) throws SQLException;
        }

        /** Streams the rows of a query to {@code sink} without building them as maps (for very large result sets). */
        public void scan(String sql, List<?> params, RowSink sink) {
            try (PreparedStatement st = prepare(sql, params); ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    sink.accept(rs);
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }

        /** {@code Project.get(db, key, default)}. */
        public Object get(String key, Object dflt) {
            List<Object> row = fetchOne("SELECT value FROM settings WHERE key=?", List.of(key));
            return row == null ? dflt : PyJson.loads((String) row.get(0));
        }

        /** {@code Project.set(db, key, value)}: the compact {@code json.dumps} text. */
        public void set(String key, Object value) {
            execute("INSERT OR REPLACE INTO settings VALUES (?, ?)", List.of(key, PyJson.dumps(value)));
        }

        private PreparedStatement prepare(String sql, List<?> params) throws SQLException {
            PreparedStatement st = connection.prepareStatement(sql);
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof String s) {
                    st.setString(i + 1, s);
                } else if (p instanceof PyNum num && num.isInt()) {
                    st.setLong(i + 1, ((com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt) num).value());
                } else if (p == null) {
                    st.setNull(i + 1, java.sql.Types.VARCHAR);
                } else {
                    st.setObject(i + 1, p);
                }
            }
            return st;
        }

        @Override
        public void close() {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    /** A JDBC value in the reference client's types: ints, floats, str, bool, lists, None. */
    static Object value(Object v) throws SQLException {
        if (v == null || v instanceof String || v instanceof Boolean) {
            return v;
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return PyNum.of(((Number) v).longValue());
        }
        if (v instanceof BigInteger b) {
            return PyNum.of(b.longValueExact());
        }
        if (v instanceof Double || v instanceof Float) {
            return PyNum.of(((Number) v).doubleValue());
        }
        if (v instanceof BigDecimal) {
            throw new IllegalStateException("DECIMAL results are not JSON serializable in the reference");
        }
        if (v instanceof Array a) {
            Object[] items = (Object[]) a.getArray();
            List<Object> out = new ArrayList<>(items.length);
            for (Object item : items) {
                out.add(value(item));
            }
            return out;
        }
        return v.toString();
    }
}
