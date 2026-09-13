package com.securityexpert.nexus.ui2.worker.discovery.cp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T-7: a management-plane {@code -f json} response is parsed in memory into
 * plain {@link Map}/{@link List}/{@link String}/{@link Double}/{@link
 * Boolean} values and nothing else -- no library dependency (worker adds no
 * new one), no retained reference to the original response text once {@link
 * #parse} returns. Supports the JSON subset this adapter's fixtures use:
 * objects, arrays, strings (with the common escapes), numbers, booleans and
 * {@code null}. Field-name lookups against the returned tree happen only
 * through {@code ManagementApiFieldBinding.forRole(...).apiField()} (FB-2);
 * this class itself never names a field.
 */
final class MinimalJson {

    private final String text;
    private int pos;

    private MinimalJson(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        MinimalJson parser = new MinimalJson(text);
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        return value;
    }

    private Object parseValue() {
        char c = peek();
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return result;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            Object value = parseValue();
            result.put(key, value);
            skipWhitespace();
            char next = text.charAt(pos);
            if (next == ',') {
                pos++;
                continue;
            }
            expect('}');
            break;
        }
        return result;
    }

    private List<Object> parseArray() {
        List<Object> result = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return result;
        }
        while (true) {
            skipWhitespace();
            result.add(parseValue());
            skipWhitespace();
            char next = text.charAt(pos);
            if (next == ',') {
                pos++;
                continue;
            }
            expect(']');
            break;
        }
        return result;
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = text.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c == '\\') {
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        String hex = text.substring(pos, pos + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("unsupported escape: \\" + escaped);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Boolean parseBoolean() {
        if (text.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("invalid literal at " + pos);
    }

    private Object parseNull() {
        if (text.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new IllegalArgumentException("invalid literal at " + pos);
    }

    private Double parseNumber() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        return Double.parseDouble(text.substring(start, pos));
    }

    private void expect(char expected) {
        char actual = text.charAt(pos);
        if (actual != expected) {
            throw new IllegalArgumentException("expected '" + expected + "' but found '" + actual + "' at " + pos);
        }
        pos++;
    }

    private char peek() {
        return text.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }
}
