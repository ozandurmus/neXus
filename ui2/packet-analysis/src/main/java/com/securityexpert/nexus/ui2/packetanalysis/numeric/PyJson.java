package com.securityexpert.nexus.ui2.packetanalysis.numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reference {@code json.dumps} / {@code json.loads} for the value shapes the ingest stores as text.
 *
 * <p>Values: {@code null}, {@link Boolean}, {@link Integer}/{@link Long}/{@link BigInteger}, {@link Double},
 * {@link String}, {@link List} and {@link Map} (insertion-ordered, as the reference dicts are). Strings are written
 * with {@code ensure_ascii=True}: printable ASCII as is, the short escapes {@code \" \\ \n \r \t \b \f}, everything
 * else as lowercase {@code \\uXXXX} per UTF-16 unit (a reference code point above U+FFFF becomes the same surrogate
 * pair). Floats use the reference {@code repr} ({@code NaN}/{@code Infinity} allowed).
 */
public final class PyJson {

    private PyJson() {
    }

    /** {@code json.dumps(value)}: separators {@code ", "} and {@code ": "}. */
    public static String dumps(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, ", ", ": ");
        return out.toString();
    }

    /** {@code json.dumps(value, separators=(",", ":"))}. */
    public static String dumpsCompact(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, ",", ":");
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, String itemSep, String keySep) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean b) {
            out.append(b ? "true" : "false");
        } else if (value instanceof Integer || value instanceof Long || value instanceof BigInteger
                || value instanceof Short || value instanceof Byte) {
            out.append(value);
        } else if (value instanceof Double d) {
            out.append(PyFloatRepr.json(d));
        } else if (value instanceof Float f) {
            out.append(PyFloatRepr.json(f.doubleValue()));
        } else if (value instanceof CharSequence s) {
            string(out, s.toString());
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    out.append(itemSep);
                }
                first = false;
                string(out, key(e.getKey()));
                out.append(keySep);
                write(out, e.getValue(), itemSep, keySep);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(itemSep);
                }
                write(out, list.get(i), itemSep, keySep);
            }
            out.append(']');
        } else if (value instanceof Object[] array) {
            write(out, List.of(array), itemSep, keySep);
        } else {
            throw new IllegalArgumentException("Object of type " + value.getClass().getSimpleName()
                    + " is not JSON serializable");
        }
    }

    /** Reference dict-key coercion: str as is, bool/None/int/float to their JSON text. */
    private static String key(Object k) {
        if (k instanceof String s) {
            return s;
        }
        if (k == null) {
            return "null";
        }
        if (k instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (k instanceof Double d) {
            return PyFloatRepr.json(d);
        }
        if (k instanceof Integer || k instanceof Long || k instanceof BigInteger) {
            return k.toString();
        }
        throw new IllegalArgumentException("keys must be str, int, float, bool or None");
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** {@code py_encode_basestring_ascii}. */
    static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c >= ' ' && c <= '~') {
                        out.append(c);
                    } else {
                        out.append("\\u").append(HEX[(c >> 12) & 0xf]).append(HEX[(c >> 8) & 0xf])
                                .append(HEX[(c >> 4) & 0xf]).append(HEX[c & 0xf]);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * {@code json.loads(text)}: objects become {@link LinkedHashMap} (duplicate keys: last wins, first position),
     * arrays {@link ArrayList}, integers {@link Long} (or {@link BigInteger} when wider), other numbers
     * {@link Double}; {@code NaN}, {@code Infinity} and {@code -Infinity} are accepted.
     */
    public static Object loads(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos != text.length()) {
            throw new IllegalArgumentException("Extra data at char " + p.pos);
        }
        return v;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        void ws() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        IllegalArgumentException error(String what) {
            return new IllegalArgumentException(what + " at char " + pos);
        }

        Object value() {
            if (pos >= s.length()) {
                throw error("Expecting value");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return str();
                default:
                    break;
            }
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("NaN", pos)) {
                pos += 3;
                return Double.NaN;
            }
            if (s.startsWith("Infinity", pos)) {
                pos += 8;
                return Double.POSITIVE_INFINITY;
            }
            if (s.startsWith("-Infinity", pos)) {
                pos += 9;
                return Double.NEGATIVE_INFINITY;
            }
            return number();
        }

        Object number() {
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') {
                pos++;
            }
            if (pos < s.length() && s.charAt(pos) == '0') {
                pos++;
            } else if (pos < s.length() && s.charAt(pos) >= '1' && s.charAt(pos) <= '9') {
                while (pos < s.length() && Character.isDigit(s.charAt(pos)) && s.charAt(pos) < 0x80) {
                    pos++;
                }
            } else {
                pos = start;
                throw error("Expecting value");
            }
            boolean real = false;
            if (pos + 1 < s.length() && s.charAt(pos) == '.' && isDigit(s.charAt(pos + 1))) {
                real = true;
                pos++;
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                int save = pos;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                if (pos < s.length() && isDigit(s.charAt(pos))) {
                    real = true;
                    while (pos < s.length() && isDigit(s.charAt(pos))) {
                        pos++;
                    }
                } else {
                    pos = save;
                }
            }
            String text = s.substring(start, pos);
            if (real) {
                return Double.parseDouble(text);
            }
            BigInteger big = new BigInteger(text);
            return big.bitLength() < 64 ? (Object) big.longValue() : big;
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        Map<String, Object> object() {
            pos++;
            Map<String, Object> map = new LinkedHashMap<>();
            ws();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                ws();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw error("Expecting property name enclosed in double quotes");
                }
                String k = str();
                ws();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw error("Expecting ':' delimiter");
                }
                pos++;
                ws();
                map.put(k, value());
                ws();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                if (pos < s.length() && s.charAt(pos) == '}') {
                    pos++;
                    return map;
                }
                throw error("Expecting ',' delimiter");
            }
        }

        List<Object> array() {
            pos++;
            List<Object> list = new ArrayList<>();
            ws();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                ws();
                list.add(value());
                ws();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                if (pos < s.length() && s.charAt(pos) == ']') {
                    pos++;
                    return list;
                }
                throw error("Expecting ',' delimiter");
            }
        }

        String str() {
            pos++;
            StringBuilder b = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("Unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw error("Unterminated string");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> b.append('"');
                        case '\\' -> b.append('\\');
                        case '/' -> b.append('/');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw error("Invalid \\uXXXX escape");
                            }
                            b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("Invalid \\escape");
                    }
                } else if (c < 0x20) {
                    throw error("Invalid control character");
                } else {
                    b.append(c);
                }
            }
        }
    }
}
