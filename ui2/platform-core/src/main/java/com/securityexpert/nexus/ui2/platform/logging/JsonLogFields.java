package com.securityexpert.nexus.ui2.platform.logging;

import com.securityexpert.nexus.ui2.platform.OverwatchEnv;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Single configurable definition of the JSON console log field names, optional field filter/renames (so the enterprise
 * ARK console log format can drop in from one place when its field table arrives), and the request/job MDC keys shared
 * by the service and every worker role.
 */
public final class JsonLogFields {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final Pattern REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    public static final String CORRELATION_ID = "correlation_id";
    public static final String JOB_ID = "job_id";
    public static final String JOB_KIND = "job_kind";
    public static final String REQUEST_CORRELATION_ID = "request_correlation_id";

    public static final String TIMESTAMP = "@timestamp";
    public static final String LEVEL = "level";
    public static final String LOGGER_NAME = "logger_name";
    public static final String THREAD_NAME = "thread_name";
    public static final String MESSAGE = "message";
    public static final String EVENT = "event";
    public static final String STACK_TRACE = "stack_trace";

    private static final Set<String> REDACTION_TOKENS = Set.of(
            "REDACTED",
            "REDACTED_IP",
            "REDACTED_MAC",
            "REDACTED_PATH",
            "MASKED",
            "LINE_REDACTED_OVERLONG");

    public static final String RENAME_ENV = "NEXUS_LOG_FIELD_RENAMES";
    public static final String INCLUDE_ENV = "NEXUS_LOG_FIELD_INCLUDES";
    public static final String EXCLUDE_ENV = "NEXUS_LOG_FIELD_EXCLUDES";

    public static final JsonLogFields DEFAULT = new JsonLogFields(Map.of(), Set.of(), Set.of());

    private final Map<String, String> renames;
    private final Set<String> includes;
    private final Set<String> excludes;

    public JsonLogFields(Map<String, String> renames, Set<String> includes, Set<String> excludes) {
        Objects.requireNonNull(renames, "renames");
        Objects.requireNonNull(includes, "includes");
        Objects.requireNonNull(excludes, "excludes");
        this.renames = Collections.unmodifiableMap(new LinkedHashMap<>(renames));
        this.includes = Collections.unmodifiableSet(new LinkedHashSet<>(includes));
        this.excludes = Collections.unmodifiableSet(new LinkedHashSet<>(excludes));
    }

    /** True when {@code candidate} is a well-formed inbound {@code X-Request-Id} header value. */
    public static boolean isValidRequestId(String candidate) {
        return candidate != null && REQUEST_ID_PATTERN.matcher(candidate).matches();
    }

    /** Returns {@code candidate} when it matches {@link #REQUEST_ID_PATTERN}; otherwise generates a fresh UUID. */
    public static String resolveRequestId(String candidate) {
        return isValidRequestId(candidate) ? candidate : UUID.randomUUID().toString();
    }

    /** Formats the job correlation id value {@code job:<jobId>}. */
    public static String jobCorrelationId(String jobId) {
        return "job:" + jobId;
    }

    /**
     * Extracts the leading bracketed event code (for example {@code PAN_CONFIG_SYNC} from
     * {@code "[PAN_CONFIG_SYNC] synced"} or {@code NOTIFY_SYSLOG_FAILED} from
     * {@code "[NOTIFY_SYSLOG_FAILED] peer unreachable"}) when {@code message} starts with one, or returns
     * {@code null} when the message has no leading bracketed event code.
     */
    public static String extractEventCode(String message) {
        if (message == null || message.length() < 3 || message.charAt(0) != '[') {
            return null;
        }
        int close = message.indexOf(']');
        if (close < 2) {
            return null;
        }
        if (close + 1 < message.length()) {
            char next = message.charAt(close + 1);
            if (!Character.isWhitespace(next) && next != ':') {
                return null;
            }
        }
        char first = message.charAt(1);
        if (first < 'A' || first > 'Z') {
            return null;
        }
        for (int i = 2; i < close; i++) {
            char c = message.charAt(i);
            boolean valid = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            if (!valid) {
                return null;
            }
        }
        String code = message.substring(1, close);
        return REDACTION_TOKENS.contains(code) ? null : code;
    }

    /** Reads optional field renames/includes/excludes from environment variables or system properties. */
    public static JsonLogFields fromEnvironment() {
        return fromEnvironment(key -> {
            String value = OverwatchEnv.get(key);
            if (value == null || value.isBlank()) {
                value = System.getProperty(key);
            }
            return value;
        });
    }

    public static JsonLogFields fromEnvironment(UnaryOperator<String> lookup) {
        Map<String, String> renames = parseRenames(lookup.apply(RENAME_ENV));
        Set<String> includes = parseSet(lookup.apply(INCLUDE_ENV));
        Set<String> excludes = parseSet(lookup.apply(EXCLUDE_ENV));
        if (renames.isEmpty() && includes.isEmpty() && excludes.isEmpty()) {
            return DEFAULT;
        }
        return new JsonLogFields(renames, includes, excludes);
    }

    /** Output JSON member name for {@code canonicalField}. */
    public String fieldName(String canonicalField) {
        return renames.getOrDefault(canonicalField, canonicalField);
    }

    /** True when {@code canonicalField} is enabled in the output JSON object. */
    public boolean includes(String canonicalField) {
        if (excludes.contains(canonicalField)) {
            return false;
        }
        return includes.isEmpty() || includes.contains(canonicalField);
    }

    private static Map<String, String> parseRenames(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && eq < pair.length() - 1) {
                String from = pair.substring(0, eq).trim();
                String to = pair.substring(eq + 1).trim();
                if (!from.isEmpty() && !to.isEmpty()) {
                    map.put(from, to);
                }
            }
        }
        return map;
    }

    private static Set<String> parseSet(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String item : raw.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                set.add(trimmed);
            }
        }
        return set;
    }
}
