package com.securityexpert.nexus.ui2.worker.scriptrunner;

import java.nio.charset.StandardCharsets;

/**
 * Splits the execution pod's log into stdout and stderr by the launcher's line tags ({@code O|} / {@code E|}; any other
 * line, e.g. from the shell itself, counts as stderr). When the log read stopped at its byte limit the last, possibly
 * partial, line is dropped, so a secret cut in half at the read boundary can never escape masking.
 */
public final class ScriptRunLog {

    /** The pod log is read up to this many bytes (each stream is later cut to 256 KiB after masking). */
    public static final long READ_LIMIT_BYTES = 4L * 1024 * 1024;

    private ScriptRunLog() {
    }

    public record Split(String stdout, String stderr, boolean cut) {
    }

    public static Split split(byte[] log, boolean limitReached) {
        String text = new String(log, StandardCharsets.UTF_8);
        if (limitReached) {
            int lastNewline = text.lastIndexOf('\n');
            text = lastNewline < 0 ? "" : text.substring(0, lastNewline + 1);
        }
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            String line = end < 0 ? text.substring(start) : text.substring(start, end);
            boolean newline = end >= 0;
            if (line.startsWith("O|")) {
                out.append(line, 2, line.length());
                if (newline) {
                    out.append('\n');
                }
            } else if (line.startsWith("E|")) {
                err.append(line, 2, line.length());
                if (newline) {
                    err.append('\n');
                }
            } else if (!line.isEmpty()) {
                err.append(line);
                if (newline) {
                    err.append('\n');
                }
            }
            start = end < 0 ? text.length() : end + 1;
        }
        return new Split(out.toString(), err.toString(), limitReached);
    }
}
