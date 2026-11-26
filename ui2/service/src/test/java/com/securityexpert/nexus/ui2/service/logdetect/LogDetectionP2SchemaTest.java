package com.securityexpert.nexus.ui2.service.logdetect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Design section 11 "no text in tables": every column of the V273 tables is listed and classified. A text column passes
 * only with a CHECK in its own definition (a closed vocabulary, a closed id form or the closed character set of the
 * vocabulary-form template text), or when it is an opaque id copied from a P1/phase-B row or an actor fingerprint
 * (listed below with the reason), or a reference to a catalog version. Any other free-text column fails. The new log_finding columns must each be named by a
 * CHECK. And the database holds the severity floor: a layer-B row can never be CRITICAL.
 */
class LogDetectionP2SchemaTest {
    /** Text columns without an own CHECK, with the reason they are not free text. */
    private static final Map<String, String> OPAQUE_TEXT = Map.of(
            "chunk_id", "opaque id of system_log_chunk (phase B)",
            "device_id", "opaque device id of system_log_chunk",
            "job_id", "opaque collection job id of system_log_chunk",
            "source_key", "source key copied from system_log_chunk (closed set of the collector)",
            "created_by", "actor fingerprint, audit-redacted tier 2",
            "frozen_by", "actor fingerprint, audit-redacted tier 2");

    private static String migration() throws IOException {
        try (InputStream in = LogDetectionP2SchemaTest.class.getResourceAsStream("/db/migration/V273__log_detection_p2_catalog.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyNewTableColumnIsClassifiedAndNoneIsFreeText() throws IOException {
        String sql = migration();
        Matcher table = Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+) \\((.*?)\\n\\);", Pattern.DOTALL).matcher(sql);
        List<String> failures = new ArrayList<>();
        int tables = 0;
        int columns = 0;
        while (table.find()) {
            tables++;
            for (String def : splitColumns(table.group(2))) {
                String d = def.strip();
                if (d.isEmpty() || d.startsWith("PRIMARY KEY") || d.startsWith("CONSTRAINT")) continue;
                String name = d.split("\\s+")[0];
                String type = d.split("\\s+")[1];
                columns++;
                if (type.startsWith("TEXT")) {
                    if (d.contains("CHECK (") || d.contains("REFERENCES log_template_catalog") || OPAQUE_TEXT.containsKey(name)) continue;
                    failures.add(table.group(1) + "." + name + " is free text");
                } else if (type.startsWith("JSONB")) {
                    if (!d.contains("jsonb_typeof")) failures.add(table.group(1) + "." + name + " is untyped json");
                }
            }
        }
        assertEquals(7, tables);
        assertTrue(columns > 60, "columns read: " + columns);
        assertTrue(failures.isEmpty(), failures.toString());
    }

    @Test
    void everyNewFindingColumnIsConstrainedAndTheSeverityFloorIsInTheDatabase() throws IOException {
        String sql = migration();
        Matcher added = Pattern.compile("ALTER TABLE log_finding ADD COLUMN IF NOT EXISTS (\\w+) TEXT(?!\\[)").matcher(sql);
        int n = 0;
        while (added.find()) {
            n++;
            String column = added.group(1);
            assertTrue(Pattern.compile("CHECK \\([^;]*\\b" + column + "\\b", Pattern.DOTALL).matcher(sql).find(), column + " has no CHECK");
        }
        assertEquals(5, n, "phase, template_id, template_text, catalog_version, normaliser_version (kinds is text[], checked apart)");
        assertTrue(sql.contains("CHECK (kinds <@ ARRAY["));
        assertTrue(sql.contains("CHECK (layer <> 'B' OR severity <> 'CRITICAL')"));
        assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS p2_enabled BOOLEAN NOT NULL DEFAULT false"), "the P2 switch is off by default");
        assertTrue(sql.contains("trg_log_template_catalog_frozen_guard"), "a frozen catalog is immutable");
        assertTrue(sql.contains("trg_audit_log_template_catalog"), "build and freeze are audited");
    }

    /** Splits a CREATE TABLE body at the top-level commas. */
    private static List<String> splitColumns(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char ch : body.toCharArray()) {
            if (ch == '\'') quoted = !quoted;
            if (!quoted && ch == '(') depth++;
            if (!quoted && ch == ')') depth--;
            if (!quoted && ch == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
