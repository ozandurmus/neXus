package com.securityexpert.nexus.ui2.worker.systemlog.redaction;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;

import com.securityexpert.nexus.ui2.worker.systemlog.SourceFamily;
import com.securityexpert.nexus.ui2.worker.systemlog.redaction.SecretRules.Rule;

/**
 * Redaction on write for Check Point text log chunks (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 12), applied to
 * the bytes of a chunk before they are encrypted and stored. Pure: no I/O, no clock, no state between calls; one
 * instance can be shared by any number of threads.
 *
 * <p><b>Only the secret value is replaced.</b> A line that mentions a secret keyword keeps all of its other text, so
 * the 15 % of {@code fwk.elg} lines that carry secret vocabulary stay useful. The value becomes
 * {@value #REDACTED} and each replacement is counted under the id of the rule that made it.</p>
 *
 * <p><b>Bytes in, bytes out, nothing dropped.</b> The chunk is processed as ISO-8859-1, which maps every byte to
 * one character and back, so valid UTF-8, invalid UTF-8, NUL bytes and legacy code pages all pass through exactly
 * (a multi-byte character inside a secret is part of the value and is replaced with it). Line terminators
 * ({@code \n}, {@code \r\n}) are kept as they are; each line is redacted on its own, so a multi-line record
 * ({@code cpd.elg} continuations) is simply several lines. The one cross-line rule is a PEM private key block
 * inside a chunk: its body lines are replaced until the {@code END} line.</p>
 *
 * <p><b>Idempotent.</b> Redacting the output again changes nothing and counts nothing, because a value that is
 * already {@value #REDACTED} is never replaced.</p>
 *
 * <p><b>Versioned.</b> {@link #RULE_SET_ID} names this rule set; any change to a rule changes the id, and the
 * collector stores the id with each chunk.</p>
 */
public final class LogRedactor {

    /** The text that replaces a secret value. */
    public static final String REDACTED = SecretRules.REDACTED;

    /** The version of the rules in this class and {@code SecretRules}. Bump it with any rule change. */
    public static final String RULE_SET_ID = "cp-text-v1";

    /** Redacts the whole array. */
    public RedactionResult redact(SourceFamily family, byte[] chunk) {
        Objects.requireNonNull(chunk, "chunk");
        return redact(family, chunk, 0, chunk.length);
    }

    /**
     * Redacts {@code chunk[offset, offset + length)}.
     *
     * @param family the source the chunk belongs to (carried into the result; every family uses the same rules, a
     *        secret can appear in any of them)
     * @return the redacted bytes, the per-rule counts and the rule-set id
     */
    public RedactionResult redact(SourceFamily family, byte[] chunk, int offset, int length) {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(chunk, "chunk");
        if (offset < 0 || length < 0 || offset > chunk.length - length) {
            throw new IndexOutOfBoundsException("offset " + offset + " length " + length + " of " + chunk.length);
        }
        Map<String, Integer> counts = new TreeMap<>();
        String text = new String(chunk, offset, length, StandardCharsets.ISO_8859_1);
        String redacted = redactText(text, counts);
        byte[] out = redacted == text ? java.util.Arrays.copyOfRange(chunk, offset, offset + length)
                : redacted.getBytes(StandardCharsets.ISO_8859_1);
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        return new RedactionResult(family, out, total, counts, RULE_SET_ID);
    }

    // ---- lines -----------------------------------------------------------------------------------------------

    /** Package-visible for the tests: redacts a string (lines separated by LF or CRLF), counting into {@code counts}. */
    String redactText(String text, Map<String, Integer> counts) {
        StringBuilder out = null;
        int copied = 0;
        boolean inPem = false;
        boolean inPublicPem = false;
        int i = 0;
        int n = text.length();
        while (i < n) {
            int newline = text.indexOf('\n', i);
            int next = newline < 0 ? n : newline + 1;
            int end = newline < 0 ? n : newline;
            if (end > i && text.charAt(end - 1) == '\r') {
                end--;
            }
            String line = text.substring(i, end);
            String result;
            if (inPublicPem) {
                result = line;
                inPublicPem = !SecretRules.PEM_ANY_END.matcher(line).find();
            } else if (inPem) {
                Matcher close = SecretRules.PEM_END.matcher(line);
                if (close.find()) {
                    result = redactPemBody(line.substring(0, close.start()), counts) + line.substring(close.start());
                    inPem = false;
                } else {
                    result = redactPemBody(line, counts);
                }
            } else {
                result = redactLine(line, counts);
                if (SecretRules.PEM_BEGIN.matcher(line).find()) {
                    inPem = !SecretRules.PEM_END.matcher(line).find();
                } else {
                    // a certificate or public key is not secret: its base64 body lines are left alone
                    inPublicPem = SecretRules.PEM_ANY_BEGIN.matcher(line).find() && !SecretRules.PEM_ANY_END.matcher(line).find();
                }
            }
            if (!result.equals(line)) {
                if (out == null) {
                    out = new StringBuilder(n + 64);
                }
                out.append(text, copied, i).append(result);
                copied = end;
            }
            i = next;
        }
        if (out == null) {
            return text;
        }
        return out.append(text, copied, n).toString();
    }

    String redactLine(String line, Map<String, Integer> counts) {
        if (line.isEmpty()) {
            return line;
        }
        String current = line;
        if (SecretRules.BASE64_LINE.matcher(current).matches()) {
            String body = current.strip();
            return replaceSpan(current, current.indexOf(body), current.indexOf(body) + body.length(), counts,
                    RuleId.PEM_PRIVATE_KEY);
        }
        if (!SecretRules.TRIGGER.matcher(current).find()) {
            return current;
        }
        for (Rule rule : SecretRules.RULES) {
            current = apply(rule, current, counts);
        }
        return current;
    }

    private String apply(Rule rule, String line, Map<String, Integer> counts) {
        Matcher m = rule.pattern().matcher(line);
        StringBuilder out = null;
        int copied = 0;
        while (m.find()) {
            for (String group : rule.groups()) {
                int start = m.start(group);
                if (start < 0) {
                    continue;
                }
                int end = m.end(group);
                if (!rule.raw()) {
                    int[] inner = valueSpan(line, start, end);
                    start = inner[0];
                    end = inner[1];
                }
                if (start >= end || start < copied) {
                    continue;
                }
                String value = line.substring(start, end);
                if (SecretRules.isPlaceholder(value) || SecretRules.isPlaceholder(m.group(group))
                        || !rule.check().accept(m, value)) {
                    continue;
                }
                if (out == null) {
                    out = new StringBuilder(line.length() + 16);
                }
                out.append(line, copied, start).append(REDACTED);
                copied = end;
                counts.merge(rule.id().id(), 1, Integer::sum);
            }
        }
        if (out == null) {
            return line;
        }
        return out.append(line, copied, line.length()).toString();
    }

    /**
     * The part of a matched value that is the secret: the inside of a quoted value, or a bare token without its
     * trailing {@code , ; ) ] } " '}.
     */
    private static int[] valueSpan(String line, int start, int end) {
        String v = line.substring(start, end);
        if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'') && v.charAt(v.length() - 1) == v.charAt(0)) {
            return new int[] {start + 1, end - 1};
        }
        if (v.length() >= 12 && v.startsWith("&quot;") && v.endsWith("&quot;")) {
            return new int[] {start + 6, end - 6};
        }
        int s = start;
        int e = end;
        if (v.charAt(0) == '"' || v.charAt(0) == '\'') {
            s++;
        }
        while (e > s && ",;)]}\"'".indexOf(line.charAt(e - 1)) >= 0) {
            e--;
        }
        return new int[] {s, e};
    }

    private static String replaceSpan(String line, int start, int end, Map<String, Integer> counts, RuleId id) {
        if (REDACTED.equals(line.substring(start, end))) {
            return line;
        }
        counts.merge(id.id(), 1, Integer::sum);
        return line.substring(0, start) + REDACTED + line.substring(end);
    }

    /** A line inside a PEM private key block: all of it if it is base64, else its trailing base64 run. */
    private static String redactPemBody(String line, Map<String, Integer> counts) {
        String body = line.strip();
        if (body.isEmpty() || REDACTED.equals(body)) {
            return line;
        }
        if (SecretRules.BASE64_ONLY.matcher(body).matches()) {
            int s = line.indexOf(body);
            return replaceSpan(line, s, s + body.length(), counts, RuleId.PEM_PRIVATE_KEY);
        }
        Matcher tail = SecretRules.BASE64_TAIL.matcher(line);
        if (tail.find() && !line.substring(tail.start()).equals(REDACTED)) {
            return replaceSpan(line, tail.start(), line.length(), counts, RuleId.PEM_PRIVATE_KEY);
        }
        return line;
    }
}
