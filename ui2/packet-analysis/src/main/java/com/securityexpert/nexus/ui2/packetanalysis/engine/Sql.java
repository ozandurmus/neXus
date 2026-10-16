package com.securityexpert.nexus.ui2.packetanalysis.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SQL statements copied verbatim from the reference modules into
 * {@code packetanalysis/engine/sql/<module>.sql}. Each statement starts with a {@code -- name: <id>} line and,
 * when the reference builds it with an f-string, carries the same {@code {placeholder}} names; {@link #fmt}
 * substitutes them exactly as the reference does (string insertion), bound {@code ?} parameters stay bound.
 */
public final class Sql {
    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private Sql() {
    }

    public static String get(String module, String name) {
        Map<String, String> statements = CACHE.computeIfAbsent(module, Sql::load);
        String sql = statements.get(name);
        if (sql == null) {
            throw new IllegalArgumentException("No SQL statement " + module + ":" + name);
        }
        return sql;
    }

    /** Substitutes f-string fields: pairs of placeholder name and replacement text. */
    public static String fmt(String module, String name, Object... pairs) {
        String sql = get(module, name);
        for (int i = 0; i < pairs.length; i += 2) {
            String key = "{" + pairs[i] + "}";
            if (!sql.contains(key)) {
                throw new IllegalArgumentException("Placeholder " + key + " absent in " + module + ":" + name);
            }
            sql = sql.replace(key, String.valueOf(pairs[i + 1]));
        }
        return sql;
    }

    private static Map<String, String> load(String module) {
        String path = "/packetanalysis/engine/sql/" + module + ".sql";
        try (InputStream in = Sql.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing SQL resource " + path);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> out = new LinkedHashMap<>();
            String current = null;
            StringBuilder body = new StringBuilder();
            for (String line : text.split("\n", -1)) {
                if (line.startsWith("-- name: ")) {
                    if (current != null) {
                        out.put(current, body.toString().strip());
                    }
                    current = line.substring("-- name: ".length()).strip();
                    body.setLength(0);
                } else if (current != null) {
                    body.append(line).append('\n');
                }
            }
            if (current != null) {
                out.put(current, body.toString().strip());
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
