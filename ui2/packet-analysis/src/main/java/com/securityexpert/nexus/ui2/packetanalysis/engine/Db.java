package com.securityexpert.nexus.ui2.packetanalysis.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin JDBC wrapper with the reference driver's value model: every integer type (INTEGER, BIGINT, HUGEINT)
 * comes back as {@link Long} (a reference int), DOUBLE as {@link Double}, LIST as {@link List}, STRUCT as an
 * insertion-ordered {@link Map}; SQL NULL is {@code null}. Bound parameters follow the reference binding:
 * {@code Long}/{@code Integer} BIGINT, {@code Double} DOUBLE, {@code String} VARCHAR, {@code Boolean} BOOLEAN,
 * {@code List<String>} VARCHAR[] (for {@code ?::VARCHAR[]}), {@code List<Long>} BIGINT[], {@code null} NULL.
 */
public final class Db implements AutoCloseable {
    private final Connection connection;

    public Db(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }

    /** One result row: column name to value, in select-list order (the reference {@code rows()} dict). */
    public static final class Row extends LinkedHashMap<String, Object> {
        public Row() {
        }

        public Row(Map<String, Object> source) {
            super(source);
        }

        public String str(String key) {
            return (String) get(key);
        }

        public Long lng(String key) {
            return (Long) get(key);
        }

        public Double dbl(String key) {
            Object v = get(key);
            return v == null ? null : ((Number) v).doubleValue();
        }

        public boolean bool(String key) {
            return Evidence.truthy(get(key));
        }
    }

    public void exec(String sql, Object... params) {
        try {
            if (params.length == 0) {
                try (Statement st = connection.createStatement()) {
                    st.execute(sql);
                }
                return;
            }
            try (PreparedStatement st = prepare(sql, params)) {
                st.execute();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage() + "\nSQL: " + sql, e);
        }
    }

    /**
     * Rows per {@code executeBatch}. The DuckDB JDBC driver keeps a native result per batched execution until the
     * statement is closed, about 140 KB each with several DuckDB threads: one batch of 92 000 single-row inserts
     * (the canonical tuple lookup of a 240 000-packet capture) grew the DuckDB memory to 12 GB at threads=4 and
     * failed with an out-of-memory error at the default 8 GiB limit. Executing the batch in chunks bounds it.
     */
    static final int BATCH_CHUNK = 100;

    /** {@code executemany}: one prepared statement, one row of parameters per batch entry. */
    public void execMany(String sql, List<Object[]> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try (PreparedStatement st = connection.prepareStatement(sql)) {
            int pending = 0;
            for (Object[] params : batch) {
                bind(st, params);
                st.addBatch();
                if (++pending == BATCH_CHUNK) {
                    st.executeBatch();
                    st.clearBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                st.executeBatch();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage() + "\nSQL: " + sql, e);
        }
    }

    public List<Row> rows(String sql, Object... params) {
        try (PreparedStatement st = prepare(sql, params); ResultSet rs = st.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            String[] names = new String[n];
            for (int i = 0; i < n; i++) {
                names[i] = md.getColumnLabel(i + 1);
            }
            List<Row> out = new ArrayList<>();
            while (rs.next()) {
                Row row = new Row();
                for (int i = 0; i < n; i++) {
                    row.put(names[i], convert(rs.getObject(i + 1)));
                }
                out.add(row);
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage() + "\nSQL: " + sql, e);
        }
    }

    /** Column labels and converted rows for a query, preserving column names even when empty. */
    public record QueryResult(List<String> columns, List<Object[]> rows) {
    }

    public QueryResult query(String sql, Object... params) {
        try (PreparedStatement st = prepare(sql, params); ResultSet rs = st.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            List<String> cols = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) {
                cols.add(md.getColumnLabel(i));
            }
            List<Object[]> out = new ArrayList<>();
            while (rs.next()) {
                Object[] row = new Object[n];
                for (int i = 0; i < n; i++) {
                    row[i] = convert(rs.getObject(i + 1));
                }
                out.add(row);
            }
            return new QueryResult(cols, out);
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage() + "\nSQL: " + sql, e);
        }
    }

    /** {@code fetchall()} as positional tuples (duplicate column names stay distinct). */
    public List<Object[]> tuples(String sql, Object... params) {
        return query(sql, params).rows();
    }

    /** {@code fetchone()}; null when there is no row. */
    public Object[] one(String sql, Object... params) {
        List<Object[]> all = tuples(sql, params);
        return all.isEmpty() ? null : all.get(0);
    }

    /** {@code fetchone()[0]}. */
    public Object scalar(String sql, Object... params) {
        Object[] row = one(sql, params);
        return row == null ? null : row[0];
    }

    public long count(String sql, Object... params) {
        return ((Number) scalar(sql, params)).longValue();
    }

    public boolean tableExists(String name) {
        return count("SELECT count(*) FROM information_schema.tables WHERE table_name=?", name) > 0;
    }

    private PreparedStatement prepare(String sql, Object[] params) throws SQLException {
        PreparedStatement st = connection.prepareStatement(sql);
        bind(st, params);
        return st;
    }

    private void bind(PreparedStatement st, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            int at = i + 1;
            if (p == null) {
                st.setNull(at, Types.NULL);
            } else if (p instanceof Long || p instanceof Integer) {
                st.setLong(at, ((Number) p).longValue());
            } else if (p instanceof Double d) {
                st.setDouble(at, d);
            } else if (p instanceof String s) {
                st.setString(at, s);
            } else if (p instanceof Boolean b) {
                st.setBoolean(at, b);
            } else if (p instanceof List<?> list) {
                boolean numeric = !list.isEmpty() && list.get(0) instanceof Number;
                Array array = connection.createArrayOf(numeric ? "BIGINT" : "VARCHAR", list.toArray());
                st.setArray(at, array);
            } else {
                throw new IllegalArgumentException("Unsupported parameter type " + p.getClass());
            }
        }
    }

    static Object convert(Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return ((Number) v).longValue();
        }
        if (v instanceof BigInteger b) {
            return b.longValueExact();
        }
        if (v instanceof BigDecimal b) {
            return b.scale() <= 0 ? (Object) b.longValueExact() : (Object) b.doubleValue();
        }
        if (v instanceof Float f) {
            return f.doubleValue();
        }
        if (v instanceof Array a) {
            Object[] items = (Object[]) a.getArray();
            List<Object> out = new ArrayList<>(items.length);
            for (Object item : items) {
                out.add(convert(item));
            }
            return out;
        }
        if (v instanceof java.sql.Struct s) {
            return structToMap(s);
        }
        return v;
    }

    private static Object structToMap(java.sql.Struct s) throws SQLException {
        // DuckDBStruct exposes getMap() with field order preserved.
        try {
            Object map = s.getClass().getMethod("getMap").invoke(s);
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) map).entrySet()) {
                out.put(String.valueOf(e.getKey()), convert(e.getValue()));
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new SQLException("Cannot read STRUCT value", e);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
