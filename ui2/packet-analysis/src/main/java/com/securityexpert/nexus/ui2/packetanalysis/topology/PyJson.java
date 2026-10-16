package com.securityexpert.nexus.ui2.packetanalysis.topology;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloatRepr;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reference {@code json.dumps} / {@code json.loads} over the dynamic value model of this package.
 *
 * <p>Values: {@code null}, {@link Boolean}, {@link String}, {@link PyInt} / {@link PyFloat}, {@link List} (a
 * {@link PyTuple} dumps as a list), and {@code Map<String, Object>} kept in insertion order. The writer reproduces
 * the reference defaults byte for byte: {@code ensure_ascii=True} (lowercase {@code \\uXXXX} for every character
 * outside space..tilde), {@code allow_nan=True}, the shortest round-trip float text, separators {@code ", "} and
 * {@code ": "} without indent, {@code ","} plus newline and indent with one. The reader keeps ints and floats
 * apart and accepts {@code NaN} and {@code Infinity} as the reference reader does.
 */
public final class PyJson {

    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .build();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private PyJson() {
    }

    /** {@code json.dumps(value)}. */
    public static String dumps(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, -1, 0);
        return out.toString();
    }

    /** {@code json.dumps(value, indent=indent)}. */
    public static String dumps(Object value, int indent) {
        StringBuilder out = new StringBuilder();
        write(out, value, indent, 0);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, int indent, int level) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean b) {
            out.append(b ? "true" : "false");
        } else if (value instanceof String s) {
            string(out, s);
        } else if (value instanceof PyInt i) {
            out.append(i.value());
        } else if (value instanceof PyFloat f) {
            out.append(PyFloatRepr.json(f.value()));
        } else if (value instanceof PyTuple t) {
            list(out, t.items(), indent, level);
        } else if (value instanceof List<?> l) {
            list(out, l, indent, level);
        } else if (value instanceof Map<?, ?> m) {
            map(out, m, indent, level);
        } else {
            throw new IllegalArgumentException("Object of type " + value.getClass().getSimpleName()
                    + " is not JSON serializable");
        }
    }

    private static void list(StringBuilder out, List<?> items, int indent, int level) {
        if (items.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[');
        boolean first = true;
        for (Object item : items) {
            separator(out, first, indent, level + 1);
            first = false;
            write(out, item, indent, level + 1);
        }
        close(out, indent, level);
        out.append(']');
    }

    private static void map(StringBuilder out, Map<?, ?> m, int indent, int level) {
        if (m.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            separator(out, first, indent, level + 1);
            first = false;
            Object key = e.getKey();
            if (key instanceof String s) {
                string(out, s);
            } else {
                throw new IllegalArgumentException("keys must be str");
            }
            out.append(": ");
            write(out, e.getValue(), indent, level + 1);
        }
        close(out, indent, level);
        out.append('}');
    }

    private static void separator(StringBuilder out, boolean first, int indent, int level) {
        if (indent < 0) {
            if (!first) {
                out.append(", ");
            }
            return;
        }
        if (!first) {
            out.append(',');
        }
        out.append('\n');
        out.append(" ".repeat(indent * level));
    }

    private static void close(StringBuilder out, int indent, int level) {
        if (indent >= 0) {
            out.append('\n');
            out.append(" ".repeat(indent * level));
        }
    }

    private static void string(StringBuilder out, String s) {
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
                    if (c < 0x20 || c > 0x7e) {
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

    /** {@code json.loads(text)}. */
    public static Object loads(String text) {
        try (JsonParser p = FACTORY.createParser(text)) {
            JsonToken t = p.nextToken();
            if (t == null) {
                throw new IllegalArgumentException("Expecting value");
            }
            Object value = read(p, t);
            if (p.nextToken() != null) {
                throw new IllegalArgumentException("Extra data");
            }
            return value;
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static Object read(JsonParser p, JsonToken t) throws IOException {
        switch (t) {
            case VALUE_NULL:
                return null;
            case VALUE_TRUE:
                return Boolean.TRUE;
            case VALUE_FALSE:
                return Boolean.FALSE;
            case VALUE_STRING:
                return p.getText();
            case VALUE_NUMBER_INT:
                if (p.getNumberType() == JsonParser.NumberType.BIG_INTEGER) {
                    throw new ArithmeticException("int wider than 64 bits: " + p.getText());
                }
                return PyNum.of(p.getLongValue());
            case VALUE_NUMBER_FLOAT:
                return PyNum.of(p.getDoubleValue());
            case START_ARRAY: {
                List<Object> list = new ArrayList<>();
                JsonToken n;
                while ((n = p.nextToken()) != JsonToken.END_ARRAY) {
                    list.add(read(p, n));
                }
                return list;
            }
            case START_OBJECT: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String key = p.currentName();
                    map.put(key, read(p, p.nextToken()));
                }
                return map;
            }
            default:
                throw new UncheckedIOException(new IOException("Unexpected token " + t));
        }
    }
}
