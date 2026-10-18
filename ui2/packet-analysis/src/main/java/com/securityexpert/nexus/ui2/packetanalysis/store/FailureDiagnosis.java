package com.securityexpert.nexus.ui2.packetanalysis.store;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A value-free description of why an analysis failed, safe to log and to store as a job's terminal reason.
 *
 * <p>An exception message from DuckDB or PostgreSQL may quote the row it refused ({@code Duplicate key "id: ..."},
 * {@code Failing row contains (...)}), and an engine message may quote a path or capture content. None of that is
 * kept: this class reads the messages in memory only to recognise the store, the constraint kind and the constraint
 * or column identifier, and keeps only those, the exception class names, the SQL state and code locations. Every
 * identifier kept must look like a schema identifier ({@code [A-Za-z_][A-Za-z0-9_$]*}, at most 63 characters, with
 * at most one dot); anything else is dropped.</p>
 *
 * @param store      {@code duckdb}, {@code postgres} or {@code null} when no SQL failure is in the chain
 * @param kind       the constraint kind ({@code unique}, {@code primary key}, {@code check}, {@code not null},
 *                   {@code foreign key}, {@code exclusion}, {@code integrity}) or {@code null}
 * @param constraint the constraint name (PostgreSQL), or the table or {@code table.column} it is on, or {@code null}
 * @param sqlState   the first SQL state in the chain, or {@code null}
 * @param chain      the class names of the chain, outermost first
 * @param where      code locations: the outermost exception's first frame and the root cause's first frames
 */
public record FailureDiagnosis(String store, String kind, String constraint, String sqlState, List<String> chain,
        List<String> where) {

    public static final String DUCKDB = "duckdb";
    public static final String POSTGRES = "postgres";

    private static final int MAX_DEPTH = 24;
    private static final int ROOT_FRAMES = 4;
    private static final int MAX_REASON = 200;
    private static final String IDENT = "[A-Za-z_][A-Za-z0-9_$]{0,62}";
    private static final Pattern SAFE_IDENT = Pattern.compile(IDENT + "(\\." + IDENT + ")?");
    private static final Pattern SAFE_SQL_STATE = Pattern.compile("[0-9A-Z]{5}");
    private static final Pattern PG_CONSTRAINT = Pattern.compile("constraint \"(" + IDENT + ")\"");
    private static final Pattern PG_NOT_NULL = Pattern.compile(
            "null value in column \"(" + IDENT + ")\"(?: of relation \"(" + IDENT + ")\")? violates not-null constraint");
    private static final Pattern DUCK_NOT_NULL = Pattern.compile("NOT NULL constraint failed: (" + IDENT + "\\." + IDENT + ")");
    private static final Pattern DUCK_CHECK = Pattern.compile("CHECK constraint failed on table (" + IDENT + ")");

    public FailureDiagnosis {
        chain = List.copyOf(chain);
        where = List.copyOf(where);
    }

    /**
     * Diagnoses {@code failure}. {@code storeHint} names the store of the phase that failed ({@link #DUCKDB} while
     * the engine runs, {@link #POSTGRES} while the result is saved); it is used only when the chain carries an SQL
     * failure whose driver cannot be recognised from its class (DuckDB raises plain {@link SQLException}s).
     */
    public static FailureDiagnosis of(Throwable failure, String storeHint) {
        List<Throwable> links = links(failure);
        List<String> chain = new ArrayList<>();
        String store = null;
        boolean sql = false;
        String sqlState = null;
        for (Throwable t : links) {
            String name = t.getClass().getName();
            chain.add(name);
            if (name.startsWith("org.postgresql.") || name.startsWith("org.jooq.")) {
                store = store == null ? POSTGRES : store;
                sql = true;
            } else if (name.startsWith("org.duckdb.")) {
                store = store == null ? DUCKDB : store;
                sql = true;
            }
            if (t instanceof SQLException e) {
                sql = true;
                if (sqlState == null && e.getSQLState() != null && SAFE_SQL_STATE.matcher(e.getSQLState()).matches()) {
                    sqlState = e.getSQLState();
                }
            }
        }
        if (store == null && sql) {
            store = DUCKDB.equals(storeHint) || POSTGRES.equals(storeHint) ? storeHint : null;
        }
        String kind = null;
        String constraint = null;
        if (sql) {
            String[] found = constraintOf(links, sqlState);
            kind = found[0];
            constraint = found[1];
        }
        return new FailureDiagnosis(store, kind, constraint, sqlState, chain, where(links));
    }

    /** True when the failure is a constraint violation in a store. */
    public boolean constraintViolation() {
        return kind != null;
    }

    /**
     * The terminal reason for a closed {@code code}: {@code "ENGINE_FAILED: postgres check constraint
     * packet_analysis_run_engine_version_check (sqlstate 23514)"}, {@code "ENGINE_FAILED: duckdb unique constraint"},
     * {@code "ENGINE_FAILED: duckdb sql error"}, or {@code "ENGINE_FAILED: IllegalStateException"} for a failure
     * outside a store. At most {@value #MAX_REASON} characters.
     */
    public String reason(String code) {
        StringBuilder out = new StringBuilder(code).append(": ");
        if (store != null || kind != null || sqlState != null) {
            out.append(store == null ? "sql" : store);
            if (kind != null) {
                out.append(' ').append(kind).append(" constraint");
                if (constraint != null) out.append(' ').append(constraint);
            } else {
                out.append(" sql error");
            }
            if (sqlState != null) out.append(" (sqlstate ").append(sqlState).append(')');
        } else {
            out.append(simpleName(chain.get(chain.size() - 1)));
        }
        return out.length() <= MAX_REASON ? out.toString() : out.substring(0, MAX_REASON);
    }

    /** The log form: chain, SQL state, constraint and code locations; no message, no value. */
    public String logDetail() {
        return "chain " + String.join(" <- ", chain)
                + " | store " + (store == null ? "-" : store)
                + " | sqlstate " + (sqlState == null ? "-" : sqlState)
                + " | constraint " + (kind == null ? "-" : kind + (constraint == null ? "" : " " + constraint))
                + " | at " + String.join(" ; ", where);
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private static List<Throwable> links(Throwable failure) {
        List<Throwable> out = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && out.size() < MAX_DEPTH && seen.add(t); t = t.getCause()) {
            out.add(t);
        }
        return out;
    }

    /** {kind, constraint}: from the SQL state first (PostgreSQL), then from the message shape (DuckDB). */
    private static String[] constraintOf(List<Throwable> links, String sqlState) {
        String kind = sqlState == null ? null : switch (sqlState) {
            case "23505" -> "unique";
            case "23514" -> "check";
            case "23502" -> "not null";
            case "23503" -> "foreign key";
            case "23P01" -> "exclusion";
            default -> sqlState.startsWith("23") ? "integrity" : null;
        };
        String name = null;
        for (Throwable t : links) {
            String message = t.getMessage();
            if (message == null) continue;
            if (kind == null) kind = duckKind(message);
            if (name == null) name = nameIn(message);
            if (kind != null && name != null) break;
        }
        if (kind == null) {
            for (Throwable t : links) {
                if (t.getClass().getSimpleName().contains("IntegrityConstraintViolation")) {
                    kind = "integrity";
                    break;
                }
            }
        }
        return new String[] {kind, kind == null ? null : safe(name)};
    }

    private static String duckKind(String message) {
        if (!message.contains("Constraint Error")) return null;
        String m = message.toLowerCase(Locale.ROOT);
        if (m.contains("violates primary key constraint")) return "primary key";
        if (m.contains("violates unique constraint")) return "unique";
        if (m.contains("foreign key constraint")) return "foreign key";
        if (m.contains("not null constraint failed")) return "not null";
        if (m.contains("check constraint failed")) return "check";
        return "integrity";
    }

    private static String nameIn(String message) {
        Matcher m = PG_CONSTRAINT.matcher(message);
        if (m.find()) return m.group(1);
        m = PG_NOT_NULL.matcher(message);
        if (m.find()) return m.group(2) == null ? m.group(1) : m.group(2) + "." + m.group(1);
        m = DUCK_NOT_NULL.matcher(message);
        if (m.find()) return m.group(1);
        m = DUCK_CHECK.matcher(message);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String safe(String identifier) {
        return identifier != null && SAFE_IDENT.matcher(identifier).matches() ? identifier : null;
    }

    private static List<String> where(List<Throwable> links) {
        List<String> out = new ArrayList<>();
        if (links.isEmpty()) return out;
        StackTraceElement[] top = links.get(0).getStackTrace();
        if (top.length > 0) out.add(frame(top[0]));
        Throwable root = links.get(links.size() - 1);
        if (root != links.get(0)) {
            StackTraceElement[] frames = root.getStackTrace();
            for (int i = 0; i < Math.min(ROOT_FRAMES, frames.length); i++) out.add(frame(frames[i]));
        }
        return out;
    }

    private static String frame(StackTraceElement e) {
        return e.getClassName() + "." + e.getMethodName() + ":" + e.getLineNumber();
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        String simple = dot < 0 ? className : className.substring(dot + 1);
        int dollar = simple.lastIndexOf('$');
        return dollar < 0 ? simple : simple.substring(dollar + 1);
    }
}
