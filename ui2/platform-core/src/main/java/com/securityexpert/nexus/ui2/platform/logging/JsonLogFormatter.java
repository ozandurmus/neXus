package com.securityexpert.nexus.ui2.platform.logging;

import com.securityexpert.nexus.ui2.platform.OverwatchEnv;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * java.util.logging formatter that writes one JSON object per line (the worker's counterpart of the service's
 * {@code NEXUS_LOG_FORMAT=json}). Standard fields ({@link JsonLogFields}): {@code @timestamp} (ISO-8601 UTC),
 * {@code level}, {@code logger_name}, {@code thread_name}, {@code message}, top-level MDC keys ({@code correlation_id},
 * {@code job_id}, {@code job_kind}, {@code request_correlation_id}, etc. from {@link JobLogContext#mdcSnapshot()}),
 * and {@code stack_trace} (only with an exception). The message, MDC values and stack trace pass through
 * {@link LogAddressMasker} before they are escaped. Non-ASCII characters are written as {@code \}{@code uXXXX} escapes
 * so the line is valid whatever the console encoding is.
 */
public final class JsonLogFormatter extends Formatter {

    private static final Set<String> CORE_FIELDS = Set.of(
            JsonLogFields.TIMESTAMP,
            JsonLogFields.LEVEL,
            JsonLogFields.LOGGER_NAME,
            JsonLogFields.THREAD_NAME,
            JsonLogFields.MESSAGE,
            JsonLogFields.EVENT,
            JsonLogFields.STACK_TRACE);

    private final boolean rawAddresses;
    private final JsonLogFields fields;

    public JsonLogFormatter() {
        this(LogAddressMasker.rawAddressesEnabled(OverwatchEnv::get), JsonLogFields.fromEnvironment());
    }

    JsonLogFormatter(boolean rawAddresses) {
        this(rawAddresses, JsonLogFields.fromEnvironment());
    }

    public JsonLogFormatter(boolean rawAddresses, JsonLogFields fields) {
        this.rawAddresses = rawAddresses;
        this.fields = Objects.requireNonNull(fields, "fields");
    }

    @Override
    public String format(LogRecord record) {
        StringBuilder out = new StringBuilder(256);
        out.append('{');
        boolean first = true;
        first = appendField(out, first, JsonLogFields.TIMESTAMP, record.getInstant().toString());
        first = appendField(out, first, JsonLogFields.LEVEL, levelName(record.getLevel()));
        first = appendField(out, first, JsonLogFields.LOGGER_NAME,
                record.getLoggerName() == null ? "" : record.getLoggerName());
        first = appendField(out, first, JsonLogFields.THREAD_NAME, threadName(record));
        String rawMessage = formatMessage(record);
        first = appendField(out, first, JsonLogFields.MESSAGE,
                LogAddressMasker.mask(rawMessage, rawAddresses));
        String eventCode = JsonLogFields.extractEventCode(rawMessage);
        if (eventCode != null) {
            first = appendField(out, first, JsonLogFields.EVENT, eventCode);
        }

        Map<String, String> mdc = JobLogContext.mdcSnapshot();
        for (Map.Entry<String, String> entry : mdc.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key != null && value != null && !CORE_FIELDS.contains(key)) {
                first = appendField(out, first, key, LogAddressMasker.mask(value, rawAddresses));
            }
        }

        Throwable thrown = record.getThrown();
        if (thrown != null) {
            StringWriter trace = new StringWriter();
            try (PrintWriter writer = new PrintWriter(trace)) {
                thrown.printStackTrace(writer);
            }
            appendField(out, first, JsonLogFields.STACK_TRACE, LogAddressMasker.mask(trace.toString(), rawAddresses));
        }
        return out.append("}\n").toString();
    }

    private boolean appendField(StringBuilder out, boolean first, String canonicalName, String value) {
        if (!fields.includes(canonicalName)) {
            return first;
        }
        if (!first) {
            out.append(',');
        }
        quote(out, fields.fieldName(canonicalName));
        out.append(':');
        quote(out, value);
        return false;
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
