package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Reads text lines from a byte stream without ever holding more than {@value #MAX_LINE} characters of one line: a longer
 * line is cut and reported as truncated. Bytes that are not valid UTF-8 become U+FFFD. A leading byte-order mark and the
 * carriage return of a CRLF are dropped.
 */
final class BoundedLines {
    static final int MAX_LINE = 16_384;

    private final Reader in;
    private final char[] buffer = new char[1 << 16];
    private int pos;
    private int limit;
    private boolean first = true;
    private boolean truncated;
    private boolean eof;

    BoundedLines(InputStream stream) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        this.in = new InputStreamReader(stream, decoder);
    }

    /** True when the line last returned was longer than the cap (the rest of it was dropped). */
    boolean truncated() {
        return truncated;
    }

    /** The next line, or {@code null} at the end of the stream. */
    String next() throws IOException {
        if (eof && pos >= limit) {
            return null;
        }
        StringBuilder line = new StringBuilder(128);
        truncated = false;
        boolean any = false;
        while (true) {
            if (pos >= limit) {
                limit = in.read(buffer, 0, buffer.length);
                pos = 0;
                if (limit < 0) {
                    limit = 0;
                    eof = true;
                    break;
                }
            }
            char ch = buffer[pos++];
            any = true;
            if (ch == '\n') {
                return finish(line);
            }
            if (line.length() < MAX_LINE) {
                line.append(ch);
            } else {
                truncated = true;
            }
        }
        return any ? finish(line) : null;
    }

    private String finish(StringBuilder line) {
        int end = line.length();
        if (end > 0 && line.charAt(end - 1) == '\r') {
            line.setLength(end - 1);
        }
        if (first) {
            first = false;
            if (line.length() > 0 && line.charAt(0) == '﻿') {
                line.deleteCharAt(0);
            }
        }
        return line.toString();
    }
}
