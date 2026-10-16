package com.securityexpert.nexus.ui2.packetanalysis.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One open DuckDB connection of a {@link ProjectStore}, holding the project lock until closed.
 *
 * <p>Statements run in auto-commit mode unless {@link #begin()} was called, like the reference's
 * {@code db.execute}. Query results keep DuckDB's Java types, with every integer type widened to {@link Long}.
 */
public final class Db implements AutoCloseable {

    /** An error raised by the store, other than running out of memory. */
    public static final class StoreException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final String OUT_OF_MEMORY = "Analysis exceeded the DuckDB memory budget. Temporary spill storage is"
            + " enabled; check free disk space, select the shared incident flow or narrow the capture window, and"
            + " retry. Captures and ingest checkpoints are preserved.";

    private final Connection connection;
    private final ReentrantLock lock;
    private boolean closed;

    Db(Path file, Properties config, ReentrantLock lock) throws SQLException {
        this.connection = SpillGuard.track(DriverManager.getConnection("jdbc:duckdb:" + file, config));
        this.lock = lock;
    }

    static RuntimeException translate(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("Out of Memory Error")) {
            return new IllegalArgumentException(OUT_OF_MEMORY, e);
        }
        return new StoreException(message, e);
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        PreparedStatement st = connection.prepareStatement(sql);
        bind(st, params);
        return st;
    }

    private static void bind(PreparedStatement st, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object v = params[i];
            if (v == null) {
                st.setObject(i + 1, null);
            } else if (v instanceof Boolean b) {
                st.setBoolean(i + 1, b);
            } else if (v instanceof Integer || v instanceof Long) {
                st.setLong(i + 1, ((Number) v).longValue());
            } else if (v instanceof Double d) {
                st.setDouble(i + 1, d);
            } else if (v instanceof String s) {
                st.setString(i + 1, s);
            } else {
                throw new IllegalArgumentException("unsupported parameter type " + v.getClass().getSimpleName());
            }
        }
    }

    /** Run a statement; returns the update count (or -1 when it produced a result set). */
    public long execute(String sql, Object... params) {
        try (PreparedStatement st = prepare(sql, params)) {
            boolean result = st.execute();
            return result ? -1 : st.getUpdateCount();
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    /** Run a statement once per parameter row ({@code executemany}). */
    public void executeMany(String sql, List<Object[]> rows) {
        try (PreparedStatement st = connection.prepareStatement(sql)) {
            for (Object[] row : rows) {
                bind(st, row);
                st.execute();
            }
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    /** All rows of a query. */
    public List<Object[]> query(String sql, Object... params) {
        try (PreparedStatement st = prepare(sql, params); ResultSet rs = st.executeQuery()) {
            int n = rs.getMetaData().getColumnCount();
            List<Object[]> out = new ArrayList<>();
            while (rs.next()) {
                Object[] row = new Object[n];
                for (int i = 0; i < n; i++) {
                    Object v = rs.getObject(i + 1);
                    if (v instanceof Integer || v instanceof Short || v instanceof Byte) {
                        v = ((Number) v).longValue();
                    }
                    row[i] = v;
                }
                out.add(row);
            }
            return out;
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    /** {@code BEGIN}. */
    public void begin() {
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    /** {@code COMMIT} and back to auto-commit. */
    public void commit() {
        try {
            connection.commit();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    /** {@code ROLLBACK} and back to auto-commit. */
    public void rollback() {
        try {
            connection.rollback();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.close();
        } catch (SQLException e) {
            throw translate(e);
        } finally {
            lock.unlock();
        }
    }
}
