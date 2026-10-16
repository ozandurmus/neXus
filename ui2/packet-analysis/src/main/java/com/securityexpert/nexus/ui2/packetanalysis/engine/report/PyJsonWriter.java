package com.securityexpert.nexus.ui2.packetanalysis.engine.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloatRepr;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The reference JSON text: {@code json.dumps(value, indent=2)} (and the compact {@code separators=(",",":")}
 * form) with {@code ensure_ascii=True}. Floats use the reference repr ({@link PyFloatRepr#json}); ints are
 * decimal; strings escape {@code "} {@code \} {@code \n \r \t \b \f}, other control characters and every
 * non-ASCII UTF-16 unit as lowercase backslash-u hex escapes. Accepts Jackson trees and plain Java values (Map, List,
 * String, Long/Integer, Double, Boolean, null).
 */
public final class PyJsonWriter {
    private PyJsonWriter() {
    }

    public static String dumps(Object value, boolean indent) {
        return dumps(value, indent, true, true, false);
    }

    /** {@code json.dumps(value, indent=..., ensure_ascii=..., allow_nan=..., sort_keys=...)}. */
    public static String dumps(Object value, boolean indent, boolean ensureAscii, boolean allowNan, boolean sortKeys) {
        StringBuilder out = new StringBuilder();
        write(out, value, indent ? 0 : -1, ensureAscii, allowNan, sortKeys);
        return out.toString();
    }

    /** {@code json.dumps(value, separators=(",", ":"))}. */
    public static String compact(Object value) {
        StringBuilder out = new StringBuilder();
        writeCompact(out, value, true, true);
        return out.toString();
    }

    /** {@code json.dumps(value, ensure_ascii=True, allow_nan=False, separators=(",", ":"))}. */
    public static String compactStrict(Object value) {
        StringBuilder out = new StringBuilder();
        writeCompact(out, value, true, false);
        return out.toString();
    }

    public static String string(String s) {
        StringBuilder out = new StringBuilder();
        escape(out, s, true);
        return out.toString();
    }

    private static void writeCompact(StringBuilder out, Object value, boolean ensureAscii, boolean allowNan) {
        if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeCompact(out, list.get(i), ensureAscii, allowNan);
            }
            out.append(']');
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                escape(out, String.valueOf(e.getKey()), ensureAscii);
                out.append(':');
                writeCompact(out, e.getValue(), ensureAscii, allowNan);
            }
            out.append('}');
        } else {
            scalar(out, value, ensureAscii, allowNan);
        }
    }

    private static void write(StringBuilder out, Object value, int level, boolean ensureAscii, boolean allowNan,
            boolean sortKeys) {
        if (value instanceof JsonNode node) {
            writeNode(out, node, level, ensureAscii, allowNan, sortKeys);
            return;
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(level < 0 ? ", " : ",");
                }
                newline(out, level < 0 ? -1 : level + 1);
                write(out, list.get(i), level < 0 ? -1 : level + 1, ensureAscii, allowNan, sortKeys);
            }
            newline(out, level);
            out.append(']');
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append('{');
            boolean first = true;
            Iterable<? extends Map.Entry<?, ?>> entries = map.entrySet();
            if (sortKeys) {
                java.util.List<Map.Entry<?, ?>> sorted = new java.util.ArrayList<>(map.entrySet());
                sorted.sort((a, b) -> com.securityexpert.nexus.ui2.packetanalysis.topology.Py.compareStr(
                        String.valueOf(a.getKey()), String.valueOf(b.getKey())));
                entries = sorted;
            }
            for (Map.Entry<?, ?> e : entries) {
                if (!first) {
                    out.append(level < 0 ? ", " : ",");
                }
                first = false;
                newline(out, level < 0 ? -1 : level + 1);
                escape(out, String.valueOf(e.getKey()), ensureAscii);
                out.append(": ");
                write(out, e.getValue(), level < 0 ? -1 : level + 1, ensureAscii, allowNan, sortKeys);
            }
            newline(out, level);
            out.append('}');
        } else {
            scalar(out, value, ensureAscii, allowNan);
        }
    }

    private static void writeNode(StringBuilder out, JsonNode node, int level) {
        writeNode(out, node, level, true, true, false);
    }

    private static void writeNode(StringBuilder out, JsonNode node, int level, boolean ensureAscii, boolean allowNan,
            boolean sortKeys) {
        if (node.isArray()) {
            if (node.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(level < 0 ? ", " : ",");
                }
                newline(out, level < 0 ? -1 : level + 1);
                writeNode(out, node.get(i), level < 0 ? -1 : level + 1, ensureAscii, allowNan, sortKeys);
            }
            newline(out, level);
            out.append(']');
        } else if (node.isObject()) {
            if (node.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append('{');
            boolean first = true;
            java.util.List<Map.Entry<String, JsonNode>> fields = new java.util.ArrayList<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                fields.add(it.next());
            }
            if (sortKeys) {
                fields.sort((a, b) -> com.securityexpert.nexus.ui2.packetanalysis.topology.Py.compareStr(
                        a.getKey(), b.getKey()));
            }
            for (Map.Entry<String, JsonNode> e : fields) {
                if (!first) {
                    out.append(level < 0 ? ", " : ",");
                }
                first = false;
                newline(out, level < 0 ? -1 : level + 1);
                escape(out, e.getKey(), ensureAscii);
                out.append(": ");
                writeNode(out, e.getValue(), level < 0 ? -1 : level + 1, ensureAscii, allowNan, sortKeys);
            }
            newline(out, level);
            out.append('}');
        } else if (node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isBoolean()) {
            out.append(node.booleanValue() ? "true" : "false");
        } else if (node.isFloatingPointNumber()) {
            double d = node.doubleValue();
            out.append(allowNan ? PyFloatRepr.json(d) : PyFloatRepr.jsonStrict(d));
        } else if (node.isIntegralNumber()) {
            out.append(node.bigIntegerValue().toString());
        } else if (node.isTextual()) {
            escape(out, node.textValue(), ensureAscii);
        } else {
            throw new IllegalArgumentException("Unsupported JSON node " + node.getNodeType());
        }
    }

    private static void newline(StringBuilder out, int level) {
        if (level < 0) {
            return;
        }
        out.append('\n');
        for (int i = 0; i < level; i++) {
            out.append("  ");
        }
    }

    private static void scalar(StringBuilder out, Object value, boolean ensureAscii, boolean allowNan) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean b) {
            out.append(b ? "true" : "false");
        } else if (value instanceof Double d) {
            out.append(allowNan ? PyFloatRepr.json(d) : PyFloatRepr.jsonStrict(d));
        } else if (value instanceof Float f) {
            double d = f.doubleValue();
            out.append(allowNan ? PyFloatRepr.json(d) : PyFloatRepr.jsonStrict(d));
        } else if (value instanceof Number n) {
            out.append(n.toString());
        } else if (value instanceof com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt i) {
            out.append(i.value());
        } else if (value instanceof com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat f) {
            double d = f.value();
            out.append(allowNan ? PyFloatRepr.json(d) : PyFloatRepr.jsonStrict(d));
        } else if (value instanceof String s) {
            escape(out, s, ensureAscii);
        } else if (value instanceof JsonNode node) {
            writeNode(out, node, -1, ensureAscii, allowNan, false);
        } else {
            throw new IllegalArgumentException("Unsupported JSON value " + value.getClass());
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static void escape(StringBuilder out, String s) {
        escape(out, s, true);
    }

    static void escape(StringBuilder out, String s, boolean ensureAscii) {
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
                    if (c < 0x20 || (ensureAscii && c > 0x7e)) {
                        out.append("\\u").append(HEX[(c >> 12) & 15]).append(HEX[(c >> 8) & 15])
                                .append(HEX[(c >> 4) & 15]).append(HEX[c & 15]);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
