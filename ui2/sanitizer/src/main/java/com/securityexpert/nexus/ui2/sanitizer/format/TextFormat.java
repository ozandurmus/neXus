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
import java.util.List;

/**
 * Text and log files, line by line in constant memory (DATA_SANITIZER_CONTRACT.md §1): each line's content goes
 * through the rules, its terminator ({@code \n}, {@code \r\n}, {@code \r} or none) is written back exactly. A line
 * longer than {@link #MAX_LINE} characters is cut at its last whitespace before the limit, so even a file with no
 * newline at all streams.
 */
public final class TextFormat {

    static final int MAX_LINE = 1 << 20;

    private TextFormat() {
    }

    /** @return the number of lines processed */
    public static long process(InputStream in, OutputStream out, TextEncoding encoding, ValueRewriter rewriter)
            throws IOException {
        if (encoding.bomBytes() > 0) {
            out.write(in.readNBytes(encoding.bomBytes()));
        }
        Reader reader = new BufferedReader(new InputStreamReader(in, encoding.charset().newDecoder()), 1 << 16);
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, encoding.charset().newEncoder()), 1 << 16);
        List<Value> batch = new ArrayList<>();
        List<String> terminators = new ArrayList<>();
        long chars = 0;
        long lines = 0;
        StringBuilder line = new StringBuilder();
        char[] buffer = new char[1 << 16];
        boolean pendingCr = false;
        int n;
        while ((n = reader.read(buffer)) > 0) {
            int segment = 0;
            for (int i = 0; i < n; i++) {
                char c = buffer[i];
                if (pendingCr) {
                    pendingCr = false;
                    String terminator = c == '\n' ? "\r\n" : "\r";
                    batch.add(new Value(line.toString(), false));
                    terminators.add(terminator);
                    chars += line.length();
                    line.setLength(0);
                    lines++;
                    if (c == '\n') {
                        segment = i + 1;
                        continue;
                    }
                    segment = i;
                }
                if (c == '\n' || c == '\r') {
                    line.append(buffer, segment, i - segment);
                    segment = i + 1;
                    if (c == '\r') {
                        pendingCr = true;
                        continue;
                    }
                    batch.add(new Value(line.toString(), false));
                    terminators.add("\n");
                    chars += line.length();
                    line.setLength(0);
                    lines++;
                    if (batch.size() >= ValueRewriter.BATCH_VALUES || chars >= ValueRewriter.BATCH_CHARS) {
                        flush(batch, terminators, rewriter, writer);
                        chars = 0;
                    }
                }
            }
            line.append(buffer, segment, n - segment);
            while (line.length() >= MAX_LINE) {
                int cut = lastWhitespace(line);
                batch.add(new Value(line.substring(0, cut), false));
                terminators.add("");
                chars += cut;
                line.delete(0, cut);
            }
            if (batch.size() >= ValueRewriter.BATCH_VALUES || chars >= ValueRewriter.BATCH_CHARS) {
                flush(batch, terminators, rewriter, writer);
                chars = 0;
            }
        }
        if (pendingCr) {
            batch.add(new Value(line.toString(), false));
            terminators.add("\r");
            line.setLength(0);
            lines++;
        }
        if (line.length() > 0) {
            batch.add(new Value(line.toString(), false));
            terminators.add("");
            lines++;
        }
        flush(batch, terminators, rewriter, writer);
        writer.flush();
        return lines;
    }

    private static int lastWhitespace(CharSequence s) {
        int limit = Math.min(s.length(), MAX_LINE);
        for (int i = limit - 1; i > 0; i--) {
            if (Character.isWhitespace(s.charAt(i))) return i + 1;
        }
        for (int i = limit - 1; i > 0; i--) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && "._:-/%@".indexOf(c) < 0) return i + 1;
        }
        return limit;
    }

    private static void flush(List<Value> batch, List<String> terminators, ValueRewriter rewriter, Writer writer)
            throws IOException {
        if (batch.isEmpty()) return;
        List<String> out = rewriter.rewrite(batch);
        for (int i = 0; i < out.size(); i++) {
            writer.write(out.get(i));
            writer.write(terminators.get(i));
        }
        batch.clear();
        terminators.clear();
    }
}
