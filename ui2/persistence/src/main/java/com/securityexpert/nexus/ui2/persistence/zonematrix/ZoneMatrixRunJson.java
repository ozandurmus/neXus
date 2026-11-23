package com.securityexpert.nexus.ui2.persistence.zonematrix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON writer and reader for the JSONB columns of {@code zone_matrix_run} / {@code zone_matrix_finding}
 * (persistence carries no JSON library). Values are {@link Map}, {@link List}, {@link String}, {@link Long},
 * {@link Boolean} or {@code null}. Strings round-trip byte-exact (identity law): every character is kept, only the JSON
 * escapes are applied and undone.
 */
final class ZoneMatrixRunJson {
    private ZoneMatrixRunJson() {}

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            string(s, out);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                string(String.valueOf(e.getKey()), out);
                out.append(':');
                write(e.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(',');
                write(list.get(i), out);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("unsupported JSON value type");
        }
    }

    private static void string(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    static Object parse(String json) {
        if (json == null) return null;
        Parser p = new Parser(json);
        Object value = p.value();
        p.skipWhitespace();
        if (p.pos != json.length()) throw new IllegalArgumentException("trailing JSON content");
        return value;
    }

    /** A JSON array of strings as a list; {@code null} JSON or SQL NULL reads as an empty list. */
    static List<String> strings(String json) {
        Object value = parse(json);
        if (value == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) value) out.add((String) o);
        return List.copyOf(out);
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object value() {
            skipWhitespace();
            if (pos >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) throw new IllegalArgumentException("invalid JSON literal");
            pos += word.length();
            return value;
        }

        private Long number() {
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            if (start == pos) throw new IllegalArgumentException("invalid JSON value");
            return Long.parseLong(s.substring(start, pos));
        }

        private Map<String, Object> object() {
            Map<String, Object> out = new LinkedHashMap<>();
            pos++;
            skipWhitespace();
            if (s.charAt(pos) == '}') { pos++; return out; }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                out.put(key, value());
                skipWhitespace();
                if (s.charAt(pos) == ',') { pos++; continue; }
                expect('}');
                return out;
            }
        }

        private List<Object> array() {
            List<Object> out = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (s.charAt(pos) == ']') { pos++; return out; }
            while (true) {
                out.add(value());
                skipWhitespace();
                if (s.charAt(pos) == ',') { pos++; continue; }
                expect(']');
                return out;
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') return out.toString();
                if (c != '\\') { out.append(c); continue; }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> { out.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16)); pos += 4; }
                    default -> throw new IllegalArgumentException("invalid JSON escape");
                }
            }
        }

        private void expect(char c) {
            if (pos >= s.length() || s.charAt(pos) != c) throw new IllegalArgumentException("malformed JSON");
            pos++;
        }
    }
}
