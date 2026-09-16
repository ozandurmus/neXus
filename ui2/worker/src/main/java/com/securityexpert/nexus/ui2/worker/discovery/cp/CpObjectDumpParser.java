package com.securityexpert.nexus.ui2.worker.discovery.cp;

import java.util.ArrayList;
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
        parser.skipWhitespace();
        while (parser.pos < parser.text.length()) {
            objects.add(parser.parseObject());
            parser.skipWhitespace();
        }
        return objects;
    }

    private Map<String, Object> parseObject() {
        expect('(');
        // The object's own name, right after '('. Never carried into the field
        // map or any candidate row -- fields are read only by role, through
        // ManagementApiFieldBinding, and AC-8/T-7 forbid retaining a raw name.
        readToken();
        Map<String, Object> fields = parseFields();
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
        boolean inQuotes = false;
        boolean escaped = false;
        while (pos < text.length()) {
            char c = peek();
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inQuotes = !inQuotes;
            } else if (!inQuotes && c == ')') {
                break;
            }
            pos++;
        }
        String raw = text.substring(start, pos).trim();
        if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            return raw.substring(1, raw.length() - 1);
        }
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
        if (pos >= text.length() || text.charAt(pos) != expected) {
            String snippet = text.substring(Math.max(0, pos - 20), Math.min(text.length(), pos + 100));
            java.util.logging.Logger.getLogger(CpObjectDumpParser.class.getName())
                    .warning(String.format("Parser error! Expected '%c' but found '%s' at pos %d. Snippet: %s",
                            expected, pos < text.length() ? text.charAt(pos) : "EOF", pos, snippet));
            throw new ManagementPlaneQueryFailedException();
        }
        pos++;
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }
}
