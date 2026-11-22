package com.securityexpert.nexus.ui2.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Expand/contract fence for Flyway migrations. Rolling back by image digest only works when the previous image
 * still runs on the newer schema, so every migration above {@link #BASELINE_VERSION} must be additive: no drops,
 * renames, type changes, new NOT NULL, validating constraints on existing tables, truncation, unbounded
 * UPDATE/DELETE, or replacement of a function an earlier migration already defined.
 *
 * <p>Migrations at or below the baseline are grandfathered (V161 drops a column). Raise the baseline only when
 * a barrier release has been deployed, never to silence a finding. A person who really needs a contract step puts
 * {@code -- barrier-release: <reason>} on the first line of the file: the test then passes and lists the file as a
 * barrier release, which requires the drain deploy (no old image running) and a deploy window.</p>
 *
 * <p>Objects created in the same file are exempt (an empty new table cannot break an old image). Comments and
 * string literals never trigger; dollar-quoted bodies are literals except in {@code DO} blocks. Not covered:
 * UNIQUE/PRIMARY KEY on existing tables and CREATE OR REPLACE VIEW; review those by hand.</p>
 */
class MigrationAdditiveFenceTest {

    /** Highest migration version on the main branch when this fence was introduced. */
    static final int BASELINE_VERSION = 261;

    private static final Pattern FILE = Pattern.compile("V(\\d+)__.*\\.sql");
    private static final Pattern BARRIER = Pattern.compile("^--\\s*barrier-release:\\s*(\\S.*)$");
    private static final Pattern CREATE_OBJECT = Pattern.compile(
            "^create\\s+(?:or\\s+replace\\s+)?(?:unique\\s+)?(?:temp(?:orary)?\\s+|unlogged\\s+)?"
                    + "(table|index|view|materialized\\s+view|function|trigger|type|sequence)\\s+"
                    + "(?:if\\s+not\\s+exists\\s+)?(?:concurrently\\s+)?([^\\s(]+)");
    private static final Pattern DROP = Pattern.compile(
            "^drop\\s+(?:materialized\\s+)?(table|index|view|function|trigger|type|sequence|schema|"
                    + "policy|rule|extension|domain|role)\\s+(?:if\\s+exists\\s+)?(?:concurrently\\s+)?([^\\s(,;]+)"
                    + "(?:\\s+on\\s+([^\\s(,;]+))?");
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "^alter\\s+table\\s+(?:if\\s+exists\\s+)?(?:only\\s+)?([^\\s(]+)");
    private static final Pattern ALTER_DROP = Pattern.compile("\\bdrop\\s+(?!not\\s+null)");
    private static final Pattern ALTER_RENAME = Pattern.compile("\\brename\\s+(to|column|constraint)\\b");
    private static final Pattern ALTER_TYPE = Pattern.compile(
            "\\balter\\s+(?:column\\s+)?[^\\s,]+\\s+(?:set\\s+data\\s+)?type\\b");
    private static final Pattern SET_NOT_NULL = Pattern.compile("\\bset\\s+not\\s+null\\b");
    private static final Pattern ADD_CONSTRAINT = Pattern.compile(
            "\\badd\\s+(?:constraint\\s+[^\\s]+\\s+)?(foreign\\s+key|check)\\b");
    private static final Pattern TRUNCATE = Pattern.compile("^truncate\\s+(?:table\\s+)?(?:only\\s+)?([^\\s,;]+)");
    private static final Pattern DELETE_FROM = Pattern.compile(
            "(?:^|[\\s;])delete\\s+from\\s+(?:only\\s+)?([^\\s(;]+)");
    private static final Pattern UPDATE_SET = Pattern.compile(
            "(?:^|[\\s;])update\\s+(?:only\\s+)?([^\\s(;]+)(?:\\s+(?:as\\s+)?\\w+)?\\s+set\\b");
    private static final Pattern WHERE = Pattern.compile("\\bwhere\\b");
    private static final Pattern NOT_VALID = Pattern.compile("\\bnot\\s+valid\\b");
    private static final Pattern DO_TAIL = Pattern.compile("\\bdo\\s*(?:language\\s+\\w+\\s*)?$");

    // ---------------------------------------------------------------- the repository fence

    @Test
    void migrationsAboveBaselineAreAdditive() throws IOException {
        Map<Integer, Path> files = migrations();
        assertTrue(files.containsKey(BASELINE_VERSION), "baseline migration V" + BASELINE_VERSION + " must exist");
        Set<String> definedFunctions = new HashSet<>();
        List<String> failures = new ArrayList<>();
        List<String> barriers = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<Integer, Path> entry : files.entrySet()) {
            String sql = Files.readString(entry.getValue());
            String name = entry.getValue().getFileName().toString();
            if (entry.getKey() > BASELINE_VERSION) {
                checked++;
                String barrier = barrierReason(sql);
                if (barrier != null) {
                    barriers.add(name + " (" + barrier + ")");
                } else {
                    for (String finding : check(sql, definedFunctions)) {
                        failures.add(name + ": " + finding);
                    }
                }
            }
            definedFunctions.addAll(functionsDefined(sql));
        }
        for (String barrier : barriers) {
            System.out.println("BARRIER RELEASE (needs the drain deploy and a deploy window): " + barrier);
        }
        System.out.println("migration additive fence: " + checked + " migration(s) above V" + BASELINE_VERSION
                + ", " + barriers.size() + " barrier release(s)");
        assertTrue(failures.isEmpty(), "Non-additive migration(s); old images must keep working on the new schema "
                + "(split into expand now / contract later, or add a first-line '-- barrier-release: <reason>' "
                + "for a drain-deploy release):\n" + String.join("\n", failures));
    }

    @Test
    void grandfatheredMigrationWithDropColumnIsReallyDetected() throws IOException {
        Path v161 = migrations().get(161);
        assertTrue(v161 != null && Files.readString(v161).toLowerCase(Locale.ROOT).contains("drop column"));
        assertFalse(check(Files.readString(v161), Set.of()).isEmpty(), "checker must flag the V161 drop column");
    }

    private static Map<Integer, Path> migrations() throws IOException {
        Path dir = Paths.get(System.getProperty("user.dir")).getParent()
                .resolve("service/src/main/resources/db/migration");
        Map<Integer, Path> result = new TreeMap<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Matcher m = FILE.matcher(path.getFileName().toString());
                if (m.matches()) {
                    assertTrue(result.put(Integer.parseInt(m.group(1)), path) == null,
                            "duplicate version in " + path.getFileName());
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- the checker

    static String barrierReason(String sql) {
        String first = sql.stripLeading().lines().findFirst().orElse("").strip();
        Matcher m = BARRIER.matcher(first);
        return m.matches() ? m.group(1).strip() : null;
    }

    /** Removes comments and string literals; DO-block bodies stay as code. Statements end with ';'. */
    static String clean(String sql) {
        StringBuilder out = new StringBuilder();
        int n = sql.length();
        String codeTag = null;
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int depth = 1;
                i += 2;
                while (i < n && depth > 0) {
                    if (sql.startsWith("/*", i)) {
                        depth++;
                        i += 2;
                    } else if (sql.startsWith("*/", i)) {
                        depth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                out.append(' ');
            } else if (c == '\'') {
                int len = out.length();
                boolean escapes = len > 0 && (out.charAt(len - 1) == 'e' || out.charAt(len - 1) == 'E')
                        && (len == 1 || !Character.isLetterOrDigit(out.charAt(len - 2)));
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (escapes && d == '\\') {
                        i += 2;
                    } else if (d == '\'' && i + 1 < n && sql.charAt(i + 1) == '\'') {
                        i += 2;
                    } else if (d == '\'') {
                        i++;
                        break;
                    } else {
                        i++;
                    }
                }
                out.append("''");
            } else if (c == '$' && (i == 0 || !Character.isLetterOrDigit(sql.charAt(i - 1)))
                    && dollarTagAt(sql, i) != null) {
                String tag = dollarTagAt(sql, i);
                if (codeTag != null && tag.equals(codeTag)) {
                    codeTag = null;
                    out.append(" ; ");
                    i += tag.length();
                } else if (DO_TAIL.matcher(out.toString().toLowerCase(Locale.ROOT)).find()) {
                    codeTag = tag;
                    out.append(' ');
                    i += tag.length();
                } else {
                    int end = sql.indexOf(tag, i + tag.length());
                    i = end < 0 ? n : end + tag.length();
                    out.append("''");
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String dollarTagAt(String sql, int i) {
        int j = i + 1;
        while (j < sql.length() && (Character.isLetter(sql.charAt(j)) || sql.charAt(j) == '_')) {
            j++;
        }
        return j < sql.length() && sql.charAt(j) == '$' ? sql.substring(i, j + 1) : null;
    }

    static List<String> statements(String sql) {
        List<String> result = new ArrayList<>();
        for (String piece : clean(sql).split(";")) {
            String s = piece.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
            if (s.matches("^(?:do|begin|if|elsif|else|loop|exception|when|for|while|declare)\\b.*")) {
                s = s.replaceFirst("^.*?\\b(?:begin|then|else|loop)\\s+(?=[a-z])", "");
                s = s.replaceFirst("^.*\\b(?:begin|then|else|loop)\\s+(?=(?:drop|delete|update|truncate|alter|create)\\b)", "");
            }
            if (!s.isEmpty()) {
                result.add(s);
            }
        }
        return result;
    }

    static String bare(String identifier) {
        String s = identifier.replace("\"", "");
        int dot = s.lastIndexOf('.');
        return (dot >= 0 ? s.substring(dot + 1) : s).toLowerCase(Locale.ROOT);
    }

    private static String functionKey(String name, String statement, int afterName) {
        int open = statement.indexOf('(', afterName);
        int arity = 0;
        if (open >= 0) {
            int depth = 0;
            boolean any = false;
            for (int k = open; k < statement.length(); k++) {
                char ch = statement.charAt(k);
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                } else if (ch == ',' && depth == 1) {
                    arity++;
                } else if (depth == 1 && !Character.isWhitespace(ch)) {
                    any = true;
                }
            }
            arity = any ? arity + 1 : 0;
        }
        return bare(name) + "/" + arity;
    }

    static Set<String> functionsDefined(String sql) {
        Set<String> result = new HashSet<>();
        for (String s : statements(sql)) {
            Matcher m = CREATE_OBJECT.matcher(s);
            if (m.find() && m.group(1).equals("function")) {
                result.add(functionKey(m.group(2), s, m.start(2)));
            }
        }
        return result;
    }

    /** Findings for one migration file; earlierFunctions are name/arity keys defined by earlier migrations. */
    static List<String> check(String sql, Set<String> earlierFunctions) {
        List<String> statements = statements(sql);
        Set<String> createdHere = new HashSet<>();
        for (String s : statements) {
            Matcher m = CREATE_OBJECT.matcher(s);
            if (m.find()) {
                createdHere.add(bare(m.group(2)));
            }
        }
        List<String> findings = new ArrayList<>();
        for (String s : statements) {
            String finding = classify(s, createdHere, earlierFunctions);
            if (finding != null) {
                findings.add(finding + " -> " + (s.length() > 110 ? s.substring(0, 110) + "..." : s));
            }
        }
        return findings;
    }

    private static String classify(String s, Set<String> createdHere, Set<String> earlierFunctions) {
        Matcher m = DROP.matcher(s);
        if (m.find()) {
            boolean local = createdHere.contains(bare(m.group(2)))
                    || (m.group(3) != null && createdHere.contains(bare(m.group(3))));
            return local ? null : "DROP " + m.group(1).toUpperCase(Locale.ROOT) + " of an existing object";
        }
        if (s.startsWith("drop ")) {
            return "DROP statement";
        }
        m = ALTER_TABLE.matcher(s);
        if (m.find()) {
            if (createdHere.contains(bare(m.group(1)))) {
                return null;
            }
            if (ALTER_DROP.matcher(s).find()) {
                return "ALTER TABLE ... DROP";
            }
            if (ALTER_RENAME.matcher(s).find()) {
                return "ALTER ... RENAME";
            }
            if (ALTER_TYPE.matcher(s).find()) {
                return "ALTER COLUMN ... TYPE";
            }
            if (SET_NOT_NULL.matcher(s).find()) {
                return "ALTER COLUMN ... SET NOT NULL";
            }
            if (ADD_CONSTRAINT.matcher(s).find() && !NOT_VALID.matcher(s).find()) {
                return "ADD CONSTRAINT (FOREIGN KEY/CHECK) without NOT VALID on an existing table";
            }
            return null;
        }
        if (s.startsWith("alter ")) {
            return ALTER_RENAME.matcher(s).find() ? "ALTER ... RENAME" : null;
        }
        m = TRUNCATE.matcher(s);
        if (m.find()) {
            return createdHere.contains(bare(m.group(1))) ? null : "TRUNCATE";
        }
        m = DELETE_FROM.matcher(s);
        if (m.find() && !s.startsWith("create ") && !WHERE.matcher(s).find()) {
            return createdHere.contains(bare(m.group(1))) ? null : "DELETE without WHERE";
        }
        m = UPDATE_SET.matcher(s);
        if (m.find() && !s.startsWith("create ") && !WHERE.matcher(s).find()) {
            return createdHere.contains(bare(m.group(1))) ? null : "UPDATE without WHERE";
        }
        m = CREATE_OBJECT.matcher(s);
        if (m.find() && m.group(1).equals("function") && s.startsWith("create or replace")
                && earlierFunctions.contains(functionKey(m.group(2), s, m.start(2)))) {
            return "CREATE OR REPLACE FUNCTION of a function an earlier migration defined";
        }
        return null;
    }

    // ---------------------------------------------------------------- tests of the checker itself

    private static List<String> flags(String sql) {
        return check(sql, Set.of("ui2_existing/1"));
    }

    private static void assertFlagged(String sql, String expected) {
        List<String> found = flags(sql);
        assertEquals(1, found.size(), sql + " -> " + found);
        assertTrue(found.get(0).startsWith(expected), found.get(0));
    }

    @Test
    void flagsDestructiveAndContractStatements() {
        assertFlagged("DROP TABLE device;", "DROP TABLE");
        assertFlagged("DROP INDEX IF EXISTS idx_old;", "DROP INDEX");
        assertFlagged("DROP VIEW v;", "DROP VIEW");
        assertFlagged("DROP FUNCTION f(int);", "DROP FUNCTION");
        assertFlagged("DROP TRIGGER IF EXISTS t ON device;", "DROP TRIGGER");
        assertFlagged("ALTER TABLE device DROP COLUMN note;", "ALTER TABLE ... DROP");
        assertFlagged("ALTER TABLE device DROP CONSTRAINT device_pk;", "ALTER TABLE ... DROP");
        assertFlagged("ALTER TABLE device ALTER COLUMN a DROP DEFAULT;", "ALTER TABLE ... DROP");
        assertFlagged("ALTER TABLE device RENAME COLUMN a TO b;", "ALTER ... RENAME");
        assertFlagged("ALTER TABLE device RENAME TO device2;", "ALTER ... RENAME");
        assertFlagged("ALTER INDEX i RENAME TO j;", "ALTER ... RENAME");
        assertFlagged("ALTER TABLE device ALTER COLUMN a TYPE bigint;", "ALTER COLUMN ... TYPE");
        assertFlagged("ALTER TABLE device ALTER COLUMN a SET DATA TYPE text;", "ALTER COLUMN ... TYPE");
        assertFlagged("ALTER TABLE device ALTER COLUMN a SET NOT NULL;", "ALTER COLUMN ... SET NOT NULL");
        assertFlagged("ALTER TABLE device ADD CONSTRAINT fk FOREIGN KEY (a) REFERENCES b(id);", "ADD CONSTRAINT");
        assertFlagged("ALTER TABLE device ADD CONSTRAINT ck CHECK (a > 0);", "ADD CONSTRAINT");
        assertFlagged("TRUNCATE TABLE device;", "TRUNCATE");
        assertFlagged("DELETE FROM device;", "DELETE without WHERE");
        assertFlagged("UPDATE device SET a = 1;", "UPDATE without WHERE");
        assertFlagged("CREATE OR REPLACE FUNCTION ui2_existing(x int) RETURNS int AS $$ SELECT 1 $$ LANGUAGE sql;",
                "CREATE OR REPLACE FUNCTION");
        assertFlagged("DO $$ BEGIN DROP TABLE device; END $$;", "DROP TABLE");
        assertFlagged("DO $x$ BEGIN DELETE FROM device; END $x$;", "DELETE without WHERE");
    }

    @Test
    void acceptsAdditiveStatements() {
        String sql = """
                CREATE TABLE new_thing (id bigint PRIMARY KEY, a text NOT NULL);
                CREATE INDEX idx_new ON new_thing (a);
                ALTER TABLE device ADD COLUMN IF NOT EXISTS note text;
                ALTER TABLE device ADD COLUMN flag boolean NOT NULL DEFAULT false;
                ALTER TABLE device ALTER COLUMN a DROP NOT NULL;
                ALTER TABLE device ADD CONSTRAINT fk FOREIGN KEY (a) REFERENCES b(id) NOT VALID;
                ALTER TABLE device ADD CONSTRAINT ck CHECK (a > 0) NOT VALID;
                CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS u ON device (a);
                UPDATE device SET a = 1 WHERE a IS NULL;
                DELETE FROM device WHERE id = 3;
                INSERT INTO device (a) VALUES (1);
                CREATE OR REPLACE FUNCTION ui2_brand_new(x int) RETURNS int AS $$ SELECT 1 $$ LANGUAGE sql;
                CREATE OR REPLACE FUNCTION ui2_existing(x int, y int) RETURNS int AS $$ SELECT 1 $$ LANGUAGE sql;
                CREATE TRIGGER trg BEFORE UPDATE ON device FOR EACH ROW EXECUTE FUNCTION ui2_brand_new_t();
                CREATE TABLE fk_cascade (a int REFERENCES device(id) ON DELETE CASCADE ON UPDATE SET NULL);
                """;
        assertEquals(List.of(), flags(sql));
    }

    @Test
    void ignoresCommentsStringsAndFunctionBodies() {
        String sql = """
                -- DROP TABLE device;
                /* ALTER TABLE device DROP COLUMN a; /* nested TRUNCATE x; */ DELETE FROM device; */
                INSERT INTO log (msg) VALUES ('DROP TABLE device; -- not a comment; it''s text');
                COMMENT ON TABLE device IS 'TRUNCATE; DELETE FROM device';
                CREATE FUNCTION ui2_other() RETURNS void AS $body$ BEGIN DELETE FROM device; DROP TABLE x; END $body$
                    LANGUAGE plpgsql;
                INSERT INTO log (msg) VALUES (E'it\\'s; DROP TABLE device');
                """;
        assertEquals(List.of(), flags(sql));
    }

    @Test
    void objectsCreatedInTheSameFileAreExempt() {
        String sql = """
                CREATE TABLE tmp_new (a int);
                CREATE INDEX idx_tmp ON tmp_new (a);
                DROP INDEX IF EXISTS idx_tmp;
                ALTER TABLE tmp_new ALTER COLUMN a SET NOT NULL;
                ALTER TABLE tmp_new ADD CONSTRAINT ck CHECK (a > 0);
                DELETE FROM tmp_new;
                DROP TABLE tmp_new;
                """;
        assertEquals(List.of(), flags(sql));
        assertEquals(1, flags("DROP INDEX IF EXISTS idx_elsewhere;").size());
    }

    @Test
    void barrierReleaseMarkerMustBeOnTheFirstLineWithAReason() {
        assertEquals("drop legacy column after drain", barrierReason(
                "-- barrier-release: drop legacy column after drain\nALTER TABLE a DROP COLUMN b;"));
        assertEquals(null, barrierReason("-- barrier-release:\nALTER TABLE a DROP COLUMN b;"));
        assertEquals(null, barrierReason("SELECT 1;\n-- barrier-release: late\n"));
        assertEquals(null, barrierReason("-- release barrier\nSELECT 1;"));
    }

    @Test
    void functionKeysDistinguishOverloadsByArity() {
        assertEquals(Set.of("f/0", "g/2"), functionsDefined(
                "CREATE FUNCTION public.f() RETURNS int AS $$ SELECT 1 $$ LANGUAGE sql;"
                        + "CREATE OR REPLACE FUNCTION g(a int, b text DEFAULT 'x') RETURNS int AS $$ SELECT 1 $$ "
                        + "LANGUAGE sql;"));
    }
}
