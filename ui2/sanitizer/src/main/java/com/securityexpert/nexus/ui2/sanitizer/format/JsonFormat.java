package com.securityexpert.nexus.ui2.sanitizer.format;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.securityexpert.nexus.ui2.sanitizer.core.HostHeaders;

/**
 * JSON (one document, or several back to back as in NDJSON), streamed (DATA_SANITIZER_CONTRACT.md §1 "JSON: string
 * values with the text rules; keys and structure kept"). A strict lexer copies every byte outside string values
 * verbatim -- whitespace, numbers, keys, punctuation -- so an unchanged document is byte-identical. A string value
 * the rules change keeps its raw text up to the first and from the last changed character; only the changed middle
 * is re-escaped. A value under a host-like key ({@code "hostname"}, {@code "device"}) is a host name when bare.
 */
public final class JsonFormat {

    static final int MAX_STRING = 64 * 1024 * 1024;
    static final int MAX_DEPTH = 512;

    private JsonFormat() {
    }

    /** One decoded string: its value and, per decoded character, where its raw text starts. */
    record Decoded(String raw, String value, int[] rawStart) {
    }

    /** Validates a whole input without writing anything; true when it is well-formed JSON. */
    public static boolean isValid(InputStream in, TextEncoding encoding) {
        try {
            if (encoding.bomBytes() > 0) in.readNBytes(encoding.bomBytes());
            Reader reader = new BufferedReader(new InputStreamReader(in, encoding.charset().newDecoder()), 1 << 16);
            return new Lexer(reader, null, null).run() > 0;
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    /** @return the number of string values seen */
    public static long process(InputStream in, OutputStream out, TextEncoding encoding, ValueRewriter rewriter)
            throws IOException {
        if (encoding.bomBytes() > 0) out.write(in.readNBytes(encoding.bomBytes()));
        Reader reader = new BufferedReader(new InputStreamReader(in, encoding.charset().newDecoder()), 1 << 16);
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, encoding.charset().newEncoder()), 1 << 16);
        Lexer lexer = new Lexer(reader, writer, rewriter);
        lexer.run();
        lexer.flush();
        writer.flush();
        return lexer.values;
    }

    /** The changed string literal: raw prefix and suffix kept, the middle re-escaped. */
    static String reencode(Decoded d, String value) {
        String old = d.value();
        int prefix = 0;
        int max = Math.min(old.length(), value.length());
        while (prefix < max && old.charAt(prefix) == value.charAt(prefix)) prefix++;
        // Never split a surrogate pair.
        if (prefix > 0 && Character.isHighSurrogate(old.charAt(prefix - 1))) prefix--;
        int suffix = 0;
        while (suffix < max - prefix && old.charAt(old.length() - 1 - suffix) == value.charAt(value.length() - 1 - suffix)) suffix++;
        if (suffix > 0 && Character.isLowSurrogate(old.charAt(old.length() - suffix))) suffix--;
        String raw = d.raw(); // without the quotes
        int rawPrefixEnd = prefix < old.length() ? d.rawStart()[prefix] : raw.length();
        int rawSuffixStart = old.length() - suffix < old.length() ? d.rawStart()[old.length() - suffix] : raw.length();
        return raw.substring(0, rawPrefixEnd) + escape(value.substring(prefix, value.length() - suffix))
                + raw.substring(rawSuffixStart);
    }

    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }

    /** Strict RFC 8259 lexer that copies to {@code writer} (when set) and batches string values. */
    private static final class Lexer {
        private final PushbackReader in;
        private final Writer out;
        private final ValueRewriter rewriter;
        private final Deque<Character> stack = new ArrayDeque<>();
        private final List<Object> pending = new ArrayList<>(); // String (raw) or Pend (a value awaiting rewrite)
        private final List<Value> batch = new ArrayList<>();
        private long batchChars;
        long values;
        private String lastKey;

        private record Pend(Decoded decoded, int index) {
        }

        Lexer(Reader in, Writer out, ValueRewriter rewriter) {
            this.in = new PushbackReader(in, 2);
            this.out = out;
            this.rewriter = rewriter;
        }

        /** @return the number of top-level values */
        int run() throws IOException {
            int roots = 0;
            while (true) {
                int c = skipWhitespace();
                if (c < 0) break;
                in.unread(c);
                value();
                roots++;
            }
            if (roots == 0) throw new IOException("empty");
            return roots;
        }

        private int skipWhitespace() throws IOException {
            StringBuilder ws = null;
            int c;
            while ((c = in.read()) == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (out != null) {
                    if (ws == null) ws = new StringBuilder();
                    ws.append((char) c);
                }
            }
            if (ws != null) emit(ws.toString());
            return c;
        }

        private void value() throws IOException {
            int c = skipWhitespace();
            switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> {
                    Decoded d = string();
                    values++;
                    boolean host = lastKey != null && HostHeaders.isHostHeader(lastKey);
                    if (out != null) {
                        pending.add(new Pend(d, batch.size()));
                        batch.add(new Value(d.value(), host));
                        batchChars += d.value().length();
                        if (batch.size() >= ValueRewriter.BATCH_VALUES || batchChars >= ValueRewriter.BATCH_CHARS) flush();
                    }
                }
                case 't' -> literal("rue", "true");
                case 'f' -> literal("alse", "false");
                case 'n' -> literal("ull", "null");
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) number(c);
                    else throw new IOException("unexpected character");
                }
            }
        }

        private void object() throws IOException {
            if (stack.size() >= MAX_DEPTH) throw new IOException("too deep");
            stack.push('{');
            emit("{");
            int c = skipWhitespace();
            if (c == '}') {
                emit("}");
                stack.pop();
                return;
            }
            while (true) {
                if (c != '"') throw new IOException("key expected");
                Decoded key = string();
                emit("\"" + key.raw() + "\"");
                c = skipWhitespace();
                if (c != ':') throw new IOException("colon expected");
                emit(":");
                String outer = lastKey;
                lastKey = key.value();
                value();
                lastKey = outer;
                c = skipWhitespace();
                if (c == ',') {
                    emit(",");
                    c = skipWhitespace();
                    continue;
                }
                if (c == '}') {
                    emit("}");
                    stack.pop();
                    return;
                }
                throw new IOException("comma or brace expected");
            }
        }

        private void array() throws IOException {
            if (stack.size() >= MAX_DEPTH) throw new IOException("too deep");
            stack.push('[');
            emit("[");
            int c = skipWhitespace();
            if (c == ']') {
                emit("]");
                stack.pop();
                return;
            }
            in.unread(c);
            while (true) {
                value();
                c = skipWhitespace();
                if (c == ',') {
                    emit(",");
                    continue;
                }
                if (c == ']') {
                    emit("]");
                    stack.pop();
                    return;
                }
                throw new IOException("comma or bracket expected");
            }
        }

        private void literal(String rest, String whole) throws IOException {
            for (int i = 0; i < rest.length(); i++) {
                if (in.read() != rest.charAt(i)) throw new IOException("bad literal");
            }
            emit(whole);
        }

        private void number(int first) throws IOException {
            StringBuilder b = new StringBuilder();
            b.append((char) first);
            int c;
            while ((c = in.read()) >= 0 && (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-')) {
                b.append((char) c);
                if (b.length() > 512) throw new IOException("number too long");
            }
            if (c >= 0) in.unread(c);
            if (!b.toString().matches("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")) throw new IOException("bad number");
            emit(b.toString());
        }

        /** Reads a string after its opening quote; returns raw (without quotes), value and the index map. */
        private Decoded string() throws IOException {
            StringBuilder raw = new StringBuilder();
            StringBuilder value = new StringBuilder();
            int[] starts = new int[64];
            while (true) {
                int c = in.read();
                if (c < 0) throw new IOException("unterminated string");
                if (c == '"') break;
                if (c < 0x20) throw new IOException("control character in string");
                int rawAt = raw.length();
                if (c == '\\') {
                    raw.append('\\');
                    int e = in.read();
                    if (e < 0) throw new IOException("bad escape");
                    raw.append((char) e);
                    char decoded;
                    switch (e) {
                        case '"' -> decoded = '"';
                        case '\\' -> decoded = '\\';
                        case '/' -> decoded = '/';
                        case 'b' -> decoded = '\b';
                        case 'f' -> decoded = '\f';
                        case 'n' -> decoded = '\n';
                        case 'r' -> decoded = '\r';
                        case 't' -> decoded = '\t';
                        case 'u' -> {
                            int code = 0;
                            for (int i = 0; i < 4; i++) {
                                int h = in.read();
                                int d = h < 0 ? -1 : Character.digit(h, 16);
                                if (d < 0) throw new IOException("bad unicode escape");
                                raw.append((char) h);
                                code = code * 16 + d;
                            }
                            decoded = (char) code;
                        }
                        default -> throw new IOException("bad escape");
                    }
                    starts = put(starts, value.length(), rawAt);
                    value.append(decoded);
                } else {
                    raw.append((char) c);
                    starts = put(starts, value.length(), rawAt);
                    value.append((char) c);
                }
                if (raw.length() > MAX_STRING) throw new UnsupportedInputException("JSON_STRING_TOO_LARGE", "a JSON string exceeds 64 M characters");
            }
            return new Decoded(raw.toString(), value.toString(), java.util.Arrays.copyOf(starts, value.length()));
        }

        private static int[] put(int[] array, int index, int value) {
            if (index >= array.length) array = java.util.Arrays.copyOf(array, Math.max(index + 1, array.length * 2));
            array[index] = value;
            return array;
        }

        private void emit(String raw) throws IOException {
            if (out == null) return;
            if (batch.isEmpty()) out.write(raw);
            else pending.add(raw);
        }

        void flush() throws IOException {
            if (out == null) return;
            List<String> rewritten = batch.isEmpty() ? List.of() : rewriter.rewrite(batch);
            for (Object item : pending) {
                if (item instanceof String raw) {
                    out.write(raw);
                } else {
                    Pend p = (Pend) item;
                    String value = rewritten.get(p.index());
                    out.write('"');
                    out.write(value.equals(p.decoded().value()) ? p.decoded().raw() : reencode(p.decoded(), value));
                    out.write('"');
                }
            }
            pending.clear();
            batch.clear();
            batchChars = 0;
        }
    }
}
