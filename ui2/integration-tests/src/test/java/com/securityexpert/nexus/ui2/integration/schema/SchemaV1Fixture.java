package com.securityexpert.nexus.ui2.integration.schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static, container-free access to the literal {@code V1__initial_schema.sql}
 * text, for the tests that can be proved without a live PostgreSQL instance
 * (contract §7 tests 9 and 10: {@code NoRawOutputColumnTest},
 * {@code MigrationLintTest}). This class parses the file's own text; it
 * never connects to a database.
 */
final class SchemaV1Fixture {

    static final String MIGRATION_RELATIVE_PATH =
            "service/src/main/resources/db/migration/V1__initial_schema.sql";

    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE TABLE\\s+(\\w+)\\s*\\(", Pattern.CASE_INSENSITIVE);

    private SchemaV1Fixture() {
    }

    /**
     * Gradle's {@code Test} task runs with the module's project directory
     * as the working directory by default (mirrored from
     * {@code Ui2ArchitectureTest#ui2Root()}): {@code integration-tests/../}
     * is {@code ui2/}.
     */
    static Path ui2Root() {
        return Paths.get(System.getProperty("user.dir")).getParent();
    }

    static String migrationText() {
        Path path = ui2Root().resolve(MIGRATION_RELATIVE_PATH);
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }

    /**
     * Maps every table this migration creates to the exact list of column
     * names it declares, in declaration order. Parsing respects
     * parenthesis nesting (e.g. {@code secrets_metadata.backend_kind}'s
     * {@code CHECK (backend_kind IN (...))}) so a nested comma is never
     * mistaken for a column separator.
     */
    static Map<String, List<String>> tableColumns() {
        String sql = migrationText();
        Map<String, List<String>> result = new LinkedHashMap<>();
        Matcher matcher = CREATE_TABLE.matcher(sql);
        while (matcher.find()) {
            String tableName = matcher.group(1);
            int bodyStart = matcher.end(); // just after the opening '('
            int depth = 1;
            int i = bodyStart;
            while (i < sql.length() && depth > 0) {
                char c = sql.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
                i++;
            }
            String body = sql.substring(bodyStart, i - 1);
            result.put(tableName, splitTopLevelColumns(body));
        }
        return result;
    }

    private static List<String> splitTopLevelColumns(String body) {
        List<String> entries = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                entries.add(body.substring(start, i));
                start = i + 1;
            }
        }
        entries.add(body.substring(start));

        List<String> columnNames = new ArrayList<>();
        for (String entry : entries) {
            String trimmed = entry.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            // Every entry in every CREATE TABLE this migration writes is a
            // column definition (no inline table-level CONSTRAINT/PRIMARY
            // KEY/UNIQUE line is used anywhere in V1); the first token is
            // the column's identifier.
            String firstToken = trimmed.split("\\s+", 2)[0];
            columnNames.add(firstToken);
        }
        return columnNames;
    }
}
