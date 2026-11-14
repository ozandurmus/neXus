package com.securityexpert.nexus.ui2.platform.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * java.util.logging formatter that writes one JSON object per line (the worker's counterpart of the service's
 * {@code NEXUS_LOG_FORMAT=json}). Fields: {@code @timestamp} (ISO-8601 UTC), {@code level}, {@code logger_name},
 * {@code thread_name}, {@code message}, {@code stack_trace} (only with an exception) and {@code job_id} (only while
 * {@link JobLogContext} holds one). The message and the stack trace pass through {@link LogAddressMasker} before they
 * are escaped. JDK only. Non-ASCII characters are written as {@code \}{@code uXXXX} escapes so the line is valid
 * whatever the console encoding is.
 */
public final class JsonLogFormatter extends Formatter {

    private final boolean rawAddresses;

    public JsonLogFormatter() {
        this(LogAddressMasker.rawAddressesEnabled(System::getenv));
    }

    JsonLogFormatter(boolean rawAddresses) {
        this.rawAddresses = rawAddresses;
    }

    @Override
    public String format(LogRecord record) {
        StringBuilder out = new StringBuilder(256);
        out.append("{\"@timestamp\":");
        quote(out, record.getInstant().toString());
        out.append(",\"level\":");
        quote(out, levelName(record.getLevel()));
        out.append(",\"logger_name\":");
        quote(out, record.getLoggerName() == null ? "" : record.getLoggerName());
        out.append(",\"thread_name\":");
        quote(out, threadName(record));
        out.append(",\"message\":");
        quote(out, LogAddressMasker.mask(formatMessage(record), rawAddresses));
        String jobId = JobLogContext.current();
        if (jobId != null) {
            out.append(",\"job_id\":");
            quote(out, LogAddressMasker.mask(jobId, rawAddresses));
        }
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            StringWriter trace = new StringWriter();
            try (PrintWriter writer = new PrintWriter(trace)) {
                thrown.printStackTrace(writer);
            }
            out.append(",\"stack_trace\":");
            quote(out, LogAddressMasker.mask(trace.toString(), rawAddresses));
        }
        return out.append("}\n").toString();
    }

    @Override
    public String formatMessage(LogRecord record) {
        try {
            return super.formatMessage(record);
        } catch (RuntimeException badPattern) {
            return String.valueOf(record.getMessage());
        }
    }

    static String levelName(Level level) {
        int value = level.intValue();
        if (value >= Level.SEVERE.intValue()) return "ERROR";
        if (value >= Level.WARNING.intValue()) return "WARN";
        if (value >= Level.INFO.intValue()) return "INFO";
        if (value >= Level.FINE.intValue()) return "DEBUG";
        return "TRACE";
    }

    private static String threadName(LogRecord record) {
        Thread current = Thread.currentThread();
        // The console handler formats on the publishing thread, so the ids match; a record formatted elsewhere
        // falls back to the numeric id.
        return record.getLongThreadID() == current.threadId() ? current.getName() : "thread-" + record.getLongThreadID();
    }

    /** Appends {@code value} as a JSON string literal (RFC 8259). */
    static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
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
                        out.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
