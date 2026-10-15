package com.securityexpert.nexus.ui2.sanitizer.format;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import com.securityexpert.nexus.ui2.sanitizer.core.HostHeaders;

/**
 * CSV, record by record in bounded memory (DATA_SANITIZER_CONTRACT.md §1 "CSV / XLSX: cell by cell"). Quoted fields
 * may hold delimiters, doubled quotes and line breaks. A field whose value the rules leave unchanged is written back
 * byte for byte; a changed one is written with the quoting it had (quoted fields stay quoted, an unquoted field is
 * quoted only if its new value needs it). Delimiters and record terminators are copied exactly. A header cell such
 * as {@code hostname} or {@code device} makes the bare values of its column host names.
 */
public final class CsvFormat {

    static final int MAX_FIELD = 16 * 1024 * 1024;

    private record Field(String raw, String value, boolean quoted) {
    }

    private CsvFormat() {
    }

    /** Picks the delimiter whose field count is the same (and above one) on the first lines, or {@code 0}. */
    public static char sniffDelimiter(String head) {
        String[] lines = head.split("\r\n|\n|\r", -1);
        int usable = Math.min(lines.length - (head.endsWith("\n") || head.endsWith("\r") ? 1 : 0), 50);
        if (usable >= lines.length) usable = lines.length - 1; // the last line may be cut mid-way
        if (usable < 2) return 0;
        char best = 0;
        int bestCount = 1;
        for (char d : new char[] {',', ';', '\t', '|'}) {
            int expected = -1;
            boolean consistent = true;
            int nonEmpty = 0;
            for (int i = 0; i < usable; i++) {
                if (lines[i].isEmpty()) continue;
                nonEmpty++;
                int count = fieldCount(lines[i], d);
                if (count < 0 || (expected >= 0 && count != expected)) {
                    consistent = false;
                    break;
                }
                expected = count;
            }
            if (consistent && nonEmpty >= 2 && expected > bestCount) {
                best = d;
                bestCount = expected;
            }
        }
        return best;
    }

    /** Fields on one physical line, or -1 when a quote is left open (a multi-line record: not used to sniff). */
    private static int fieldCount(String line, char d) {
        int count = 1;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == d && !quoted) count++;
        }
        return quoted ? -1 : count;
    }

    /** @return the number of records processed */
    public static long process(InputStream in, OutputStream out, TextEncoding encoding, char delimiter,
            ValueRewriter rewriter) throws IOException {
        if (encoding.bomBytes() > 0) out.write(in.readNBytes(encoding.bomBytes()));
        Reader reader = new BufferedReader(new InputStreamReader(in, encoding.charset().newDecoder()), 1 << 16);
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, encoding.charset().newEncoder()), 1 << 16);
        List<List<Field>> records = new ArrayList<>();
        List<String> terminators = new ArrayList<>();
        BitSet hostColumns = null;
        long chars = 0;
        long count = 0;
        Parser parser = new Parser(reader, delimiter);
        List<Field> record;
        while ((record = parser.next()) != null) {
            records.add(record);
            terminators.add(parser.terminator);
            for (Field f : record) chars += f.raw().length();
            count++;
            if (hostColumns == null) {
                hostColumns = new BitSet();
                for (int i = 0; i < record.size(); i++) {
                    if (HostHeaders.isHostHeader(record.get(i).value())) hostColumns.set(i);
                }
                flush(records, terminators, rewriter, writer, delimiter, new BitSet());
                chars = 0;
                continue;
            }
            if (records.size() >= ValueRewriter.BATCH_VALUES / 4 || chars >= ValueRewriter.BATCH_CHARS) {
                flush(records, terminators, rewriter, writer, delimiter, hostColumns);
                chars = 0;
            }
        }
        flush(records, terminators, rewriter, writer, delimiter, hostColumns == null ? new BitSet() : hostColumns);
        writer.flush();
        return count;
    }

    private static void flush(List<List<Field>> records, List<String> terminators, ValueRewriter rewriter, Writer writer,
            char delimiter, BitSet hostColumns) throws IOException {
        if (records.isEmpty()) return;
        List<Value> values = new ArrayList<>();
        for (List<Field> record : records) {
            for (int i = 0; i < record.size(); i++) values.add(new Value(record.get(i).value(), hostColumns.get(i)));
        }
        List<String> rewritten = rewriter.rewrite(values);
        int k = 0;
        for (int r = 0; r < records.size(); r++) {
            List<Field> record = records.get(r);
            for (int i = 0; i < record.size(); i++) {
                if (i > 0) writer.write(delimiter);
                Field field = record.get(i);
                String value = rewritten.get(k++);
                writer.write(value.equals(field.value()) ? field.raw() : encode(value, field.quoted(), delimiter));
            }
            writer.write(terminators.get(r));
        }
        records.clear();
        terminators.clear();
    }

    static String encode(String value, boolean quoted, char delimiter) {
        boolean needs = quoted || value.indexOf(delimiter) >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        return needs ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    /** A character-level CSV reader that keeps each field's raw text next to its value. */
    private static final class Parser {
        private final Reader reader;
        private final char delimiter;
        private int pending = -2;
        String terminator = "";

        Parser(Reader reader, char delimiter) {
            this.reader = reader;
            this.delimiter = delimiter;
        }

        private int read() throws IOException {
            if (pending != -2) {
                int c = pending;
                pending = -2;
                return c;
            }
            return reader.read();
        }

        List<Field> next() throws IOException {
            int c = read();
            if (c < 0) return null;
            List<Field> fields = new ArrayList<>();
            StringBuilder raw = new StringBuilder();
            StringBuilder value = new StringBuilder();
            boolean quoted = false;
            boolean inQuotes = false;
            boolean fieldStart = true;
            while (true) {
                if (c < 0) {
                    fields.add(new Field(raw.toString(), value.toString(), quoted));
                    terminator = "";
                    return fields;
                }
                char ch = (char) c;
                if (inQuotes) {
                    raw.append(ch);
                    if (ch == '"') {
                        int next = read();
                        if (next == '"') {
                            raw.append('"');
                            value.append('"');
                        } else {
                            inQuotes = false;
                            pending = next;
                        }
                    } else {
                        value.append(ch);
                    }
                } else if (ch == '"' && fieldStart) {
                    raw.append(ch);
                    quoted = true;
                    inQuotes = true;
                } else if (ch == delimiter) {
                    fields.add(new Field(raw.toString(), value.toString(), quoted));
                    raw.setLength(0);
                    value.setLength(0);
                    quoted = false;
                    fieldStart = true;
                    c = read();
                    continue;
                } else if (ch == '\n' || ch == '\r') {
                    fields.add(new Field(raw.toString(), value.toString(), quoted));
                    if (ch == '\r') {
                        int next = read();
                        if (next == '\n') terminator = "\r\n";
                        else {
                            terminator = "\r";
                            pending = next;
                        }
                    } else {
                        terminator = "\n";
                    }
                    return fields;
                } else {
                    raw.append(ch);
                    // Text after a closing quote (malformed) belongs to the raw field; the value keeps it too.
                    value.append(ch);
                }
                fieldStart = false;
                if (raw.length() > MAX_FIELD) {
                    throw new UnsupportedInputException("CSV_FIELD_TOO_LARGE", "a CSV field exceeds 16 M characters");
                }
                c = read();
            }
        }
    }
}
