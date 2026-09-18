package com.securityexpert.nexus.ui2.worker.discovery.cp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T-7: parses the {@code cpmiquerybin object} tree-format dump this
 * transport reads (record §10 row 4, replacing the guessed {@code -f json}
 * shape). One or more objects are concatenated in the response text; each
 * begins with an opening parenthesis followed by its own name at column 0,
 * then a body of {@code :key (value)} leaves or {@code :key (} ... {@code )}
 * nested blocks -- a value may be quoted or empty. Column position carries
 * no meaning (hundreds of keys exist per object and their order is not a
 * contract): every value is read back out of the returned map, by key, the
 * same way the JSON tree it replaces was. A key this object's dump never
 * sent is simply absent from the map (contract CR-0); a key sent with an
 * empty value is present with an empty string (one arm of HR-2, never
 * repaired). Parsed in a single pass over the full response text; no
 * reference to that text survives past {@link #parseObjects} returning
 * (T-7's "parsed in memory... discarded").
 */
final class CpObjectDumpParser {

    private final String text;
    private int pos;

    private CpObjectDumpParser(String text) {
        this.text = text;
    }

    /** One map per object dumped; a nested block (e.g. {@code AdminInfo}, {@code cluster_object}) is a nested map value. */
    static List<Map<String, Object>> parseObjects(String text) {
        CpObjectDumpParser parser = new CpObjectDumpParser(text);
        List<Map<String, Object>> objects = new ArrayList<>();
        parser.skipToObjectStart();
        if (parser.pos == parser.text.length()) {
            return Collections.emptyList();
        }
        while (parser.pos < parser.text.length()) {
            objects.add(parser.parseObject());
            parser.skipToObjectStart();
        }
        return objects;
    }

    private Map<String, Object> parseObject() {
        expect('(');
        skipWhitespace();
        String objectName = readToken();
        Map<String, Object> fields = parseFields();
        fields.putIfAbsent("name", objectName);
        skipWhitespace();
        expect(')');
        return fields;
    }

    /** Reads zero or more {@code :key (value)} pairs until the next non-{@code :} character (the enclosing ')'). */
    private Map<String, Object> parseFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        skipWhitespace();
        while (pos < text.length() && peek() == ':') {
            pos++;
            String key = readToken();
            skipWhitespace();
            expect('(');
            Object value = parseParenBody();
            skipWhitespace();
            expect(')');
            fields.put(key, value);
            skipWhitespace();
        }
        return fields;
    }

    /** Inside {@code :key ( ... )}: a nested block if another field starts here, otherwise a leaf value. */
    private Object parseParenBody() {
        skipWhitespace();
        if (pos < text.length() && peek() == ':') {
            return parseFields();
        }
        return readLeafValue();
    }

    /** Everything up to the matching ')', trimmed, with a single pair of surrounding quotes stripped. */
    private String readLeafValue() {
        int start = pos;
        if (pos < text.length() && peek() == '"') {
            pos++;
            while (pos < text.length()) {
                if (peek() == '\\' && pos + 1 < text.length()) {
                    pos += 2;
                } else if (peek() == '"') {
                    String value = text.substring(start + 1, pos);
                    pos++;
                    skipWhitespace();
                    return value;
                } else {
                    pos++;
                }
            }
            throw new ManagementPlaneQueryFailedException("unterminated quoted value at position " + start);
        }
        int parenDepth = 0;
        while (pos < text.length()) {
            if (peek() == '(') {
                parenDepth++;
            } else if (peek() == ')') {
                if (parenDepth == 0) {
                    break;
                }
                parenDepth--;
            }
            pos++;
        }
        if (parenDepth != 0) {
            throw new ManagementPlaneQueryFailedException("unterminated parenthesized value at position " + start);
        }
        String raw = text.substring(start, pos).trim();
        return raw;
    }

    private String readToken() {
        int start = pos;
        while (pos < text.length() && !Character.isWhitespace(peek())
                && peek() != '(' && peek() != ')' && peek() != ':') {
            pos++;
        }
        return text.substring(start, pos);
    }

    private char peek() {
        return text.charAt(pos);
    }

    private void expect(char expected) {
        if (pos >= text.length()) {
            throw new ManagementPlaneQueryFailedException("expected '" + expected + "' at position " + pos + " but reached end of input");
        }
        if (text.charAt(pos) != expected) {
            throw new ManagementPlaneQueryFailedException("expected '" + expected + "' at position " + pos
                    + " but found '" + text.charAt(pos) + "'");
        }
        pos++;
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private void skipToObjectStart() {
        while (pos < text.length() && text.charAt(pos) != '(') {
            pos++;
        }
    }
}
