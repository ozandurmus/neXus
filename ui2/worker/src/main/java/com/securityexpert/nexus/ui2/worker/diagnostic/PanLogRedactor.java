package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secret redaction for Palo Alto log entries, applied on write before the chunk is encrypted
 * (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 12; PO decision 2026-10-17, Q7: config-log details are stored with
 * secret redaction, not dropped). Each secret value becomes {@value #REDACTED} and is counted (the chunk's
 * {@code redaction_count}).
 *
 * <p>Config logs: the fields that can carry configured values are {@code before-change-preview},
 * {@code after-change-preview}, {@code path} and {@code full-path} (observed), and {@code before-change-detail} /
 * {@code after-change-detail} (not observed; handled if one appears). Three layers apply to them:</p>
 * <ol>
 *   <li>a secret-bearing {@code path}/{@code full-path} (a password, hash, key, community or similar element) replaces
 *       the whole before/after change body with {@value #REDACTED};</li>
 *   <li>inside any of the six fields, the text of a secret-named XML element, and the value after a secret keyword in
 *       {@code set}-style text, is replaced;</li>
 *   <li>the generic {@code keyword=value} / {@code keyword: value} rule below.</li>
 * </ol>
 * The generic rule alone applies to every field of both sources. It needs an explicit {@code =} or {@code :} so that
 * ordinary system messages ("password policy applied") are not damaged. The rules are tested with synthetic text only.
 */
final class PanLogRedactor {
    static final String REDACTED = "<redacted>";

    /** The six config-log fields that can carry configured values; the first four are the change bodies. */
    static final List<String> CHANGE_BODY_FIELDS = List.of("before-change-preview", "after-change-preview",
            "before-change-detail", "after-change-detail");
    static final List<String> PATH_FIELDS = List.of("path", "full-path");
    private static final Set<String> CONFIG_FIELDS = Set.of("before-change-preview", "after-change-preview",
            "before-change-detail", "after-change-detail", "path", "full-path");

    /** Substrings that make an element name, keyword or config path secret-bearing. */
    private static final List<String> SECRET_WORDS = List.of("password", "passwd", "passphrase", "phash", "secret", "psk",
            "pre-shared", "community", "token", "private-key", "privatekey", "api-key", "apikey", "auth-key", "authkey",
            "authentication-key", "priv-key", "privkey", "encryption-key");
    private static final String SECRET_WORD_ALTERNATION = "password|passwd|passphrase|phash|secret|psk|pre-shared|community|token"
            + "|private-key|privatekey|api-key|apikey|auth-key|authkey|authentication-key|priv-key|privkey|encryption-key";
    /** A config path that is, or lies under, an element holding secrets. */
    private static final Pattern SECRET_PATH = Pattern.compile("(?i)(" + SECRET_WORD_ALTERNATION
            + "|local-user-database|mgt-config)|(^|[^a-z0-9-])key($|[^a-z0-9-])");
    /** {@code keyword=value} or {@code keyword: value}, value quoted or a single token. */
    private static final Pattern KEYWORD_ASSIGNMENT = Pattern.compile("(?i)\\b((?:[\\w-]*(?:" + SECRET_WORD_ALTERNATION
            + ")[\\w-]*)|key)(\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;\"'<>]+)");
    /** {@code set}-style text: a secret keyword followed by whitespace and its value. */
    private static final Pattern KEYWORD_VALUE = Pattern.compile("(?i)\\b([\\w-]*(?:" + SECRET_WORD_ALTERNATION
            + ")[\\w-]*|key)(\\s+)(\"[^\"]*\"|'[^']*'|[^\\s<>\"']+)");
    /** {@code <tag attrs>text</tag>} with text only (no child element). */
    private static final Pattern ELEMENT_TEXT = Pattern.compile("<([A-Za-z_][\\w.-]*)((?:\\s[^<>]*)?)>([^<>]+)</\\1>");

    record Result(Map<String, String> fields, int redactions) {
    }

    private PanLogRedactor() {}

    /** @return a redacted copy of {@code fields}; {@code source} is {@code pan_system} or {@code pan_config}. */
    static Result redact(String source, Map<String, String> fields) {
        Map<String, String> out = new LinkedHashMap<>(fields);
        int[] count = {0};
        boolean config = "pan_config".equals(source);
        boolean secretPath = config && PATH_FIELDS.stream().anyMatch(f -> out.get(f) != null && SECRET_PATH.matcher(out.get(f)).find());
        for (Map.Entry<String, String> field : out.entrySet()) {
            String value = field.getValue();
            if (value == null || value.isEmpty()) continue;
            boolean configField = config && CONFIG_FIELDS.contains(field.getKey());
            if (secretPath && CHANGE_BODY_FIELDS.contains(field.getKey())) {
                field.setValue(REDACTED);
                count[0]++;
                continue;
            }
            if (configField) value = elementText(value, count);
            if (configField) value = replace(KEYWORD_VALUE, value, count);
            value = replace(KEYWORD_ASSIGNMENT, value, count);
            field.setValue(value);
        }
        return new Result(out, count[0]);
    }

    private static boolean secretElement(String tag) {
        String name = tag.toLowerCase(Locale.ROOT);
        return name.equals("key") || name.endsWith("-key") || SECRET_WORDS.stream().anyMatch(name::contains);
    }

    private static String elementText(String value, int[] count) {
        Matcher matcher = ELEMENT_TEXT.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            if (secretElement(matcher.group(1)) && !REDACTED.equals(matcher.group(3))) {
                count[0]++;
                matcher.appendReplacement(out, Matcher.quoteReplacement(
                        "<" + matcher.group(1) + matcher.group(2) + ">" + REDACTED + "</" + matcher.group(1) + ">"));
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String replace(Pattern pattern, String value, int[] count) {
        Matcher matcher = pattern.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            if (REDACTED.equals(matcher.group(3))) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            count[0]++;
            matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + matcher.group(2) + REDACTED));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
