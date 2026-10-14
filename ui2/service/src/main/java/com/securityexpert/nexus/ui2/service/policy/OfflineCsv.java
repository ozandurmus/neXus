package com.securityexpert.nexus.ui2.service.policy;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

/** UTF-8 comma-separated records, including escaped quotes and embedded newlines; never spools to disk. */
public final class OfflineCsv {
    public static final long MAX_BYTES = 100_000_000;
    public static final int MAX_ROWS = 2_000_000;
    public static final int MAX_RECORD = 65_536;
    public static final int MAX_COLUMNS = 256;
    public static final Set<String> REQUIRED = Set.of("source", "destination", "port", "protocol");
    public static final Set<String> FIELDS = Set.of("source", "destination", "port", "protocol", "time", "action",
        "sourceZone", "destinationZone", "rule", "count");
    private final PushbackReader reader;
    public OfflineCsv(InputStream input, long limit) {
        reader = new PushbackReader(new BufferedReader(new InputStreamReader(limited(input, limit),
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))), 1);
    }
    public static InputStream limited(InputStream input, long limit) {
        return new FilterInputStream(input) {
            long bytes;
            private void count(long n) { if (n > 0 && (bytes += n) > limit) throw new IllegalArgumentException("CSV_SIZE_LIMIT: maximum 100 MB"); }
            @Override public int read() throws IOException { int b = in.read(); if (b >= 0) count(1); return b; }
            @Override public int read(byte[] b, int off, int len) throws IOException { int n = in.read(b, off, len); count(n); return n; }
        };
    }
    public List<String> next() throws IOException {
        var row = new ArrayList<String>(); var field = new StringBuilder();
        boolean quoted = false, closed = false, touched = false; int size = 0;
        while (true) {
            int ch = reader.read();
            if (ch < 0) {
                if (quoted) throw new IllegalArgumentException("CSV_SYNTAX: unterminated quoted field");
                if (!touched) return null;
                row.add(field.toString());
                if (row.size() > MAX_COLUMNS) throw new IllegalArgumentException("CSV_COLUMN_LIMIT: maximum 256 columns");
                return row;
            }
            touched = true;
            if (++size > MAX_RECORD) throw new IllegalArgumentException("CSV_RECORD_LIMIT: maximum 65536 characters per record");
            if (ch == 0) throw new IllegalArgumentException("CSV_SYNTAX: binary input is not supported");
            if (quoted) {
                if (ch == '"') { quoted = false; closed = true; } else field.append((char) ch);
                continue;
            }
            if (closed && ch == '"') { field.append('"'); quoted = true; closed = false; continue; }
            if (ch == ',' || ch == '\r' || ch == '\n') {
                row.add(field.toString()); field.setLength(0); closed = false;
                if (row.size() > MAX_COLUMNS) throw new IllegalArgumentException("CSV_COLUMN_LIMIT: maximum 256 columns");
                if (ch != ',') {
                    if (ch == '\r') { int next = reader.read(); if (next >= 0 && next != '\n') reader.unread(next); }
                    return row;
                }
                continue;
            }
            if (closed) throw new IllegalArgumentException("CSV_SYNTAX: unexpected character after quote");
            if (ch == '"') {
                if (!field.isEmpty()) throw new IllegalArgumentException("CSV_SYNTAX: unexpected quote");
                quoted = true;
            } else field.append((char) ch);
        }
    }
    public static Map<String, Integer> mapping(List<String> headers, Map<String, String> requested) {
        if (headers == null || headers.isEmpty()) throw new IllegalArgumentException("CSV_HEADER: header is required");
        if (headers.get(0).startsWith("\uFEFF")) headers.set(0, headers.get(0).substring(1));
        if (headers.size() > MAX_COLUMNS || headers.stream().anyMatch(String::isBlank) || new HashSet<>(headers).size() != headers.size())
            throw new IllegalArgumentException("CSV_HEADER: headers must be unique and nonempty");
        if (requested == null || !FIELDS.containsAll(requested.keySet()) || !requested.keySet().containsAll(REQUIRED))
            throw new IllegalArgumentException("CSV_MAPPING: source, destination, port and protocol are required");
        var result = new LinkedHashMap<String, Integer>(); var selected = new HashSet<String>();
        requested.forEach((field, header) -> {
            if (header == null || !headers.contains(header) || !selected.add(header))
                throw new IllegalArgumentException("CSV_MAPPING: select a different existing column for each field");
            result.put(field, headers.indexOf(header));
        });
        return result;
    }
}
