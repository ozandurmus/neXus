package com.securityexpert.nexus.ui2.platform;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Masking of captured script output ({@code SCRIPT_EXECUTION_CONTRACT.md} §3.3, Amendment 2026-11-18 item 5 and
 * Amendment 2026-11-18 (b) item 6), shared by the broker (which masks before anything leaves it) and the service (which
 * masks again before storing, as a second layer).
 *
 * <p>Order is fixed: the <em>whole</em> captured text of a stream is masked first and only then truncated to
 * {@link ScriptExecutionJob#MAX_STREAM_BYTES}, so a secret that straddles the cut can never leave a readable prefix.</p>
 *
 * <p>For each injected value (secret, username, session cookie, CSRF value) these forms are replaced: the exact value,
 * its stripped value, each line of a multi-line value, Base64 of the value with and without a trailing newline (standard
 * and URL-safe alphabets, with and without padding), URL-encoding, lower- and upper-case hex, and the JSON-escaped form.
 * Values shorter than {@link #MIN_MASKED_LENGTH} characters are not masked (masking a two-letter value would destroy the
 * output and protect nothing). Honest limit: a script that transforms a value any other way (reversed, split, encrypted,
 * printed character by character with separators) can still print it; masking is a safety net, not a boundary.</p>
 */
public final class ScriptOutputMasking {

    public static final String REDACTED_PLACEHOLDER = "[REDACTED]";

    /** Values shorter than this are not masked. */
    public static final int MIN_MASKED_LENGTH = 3;

    private ScriptOutputMasking() {
    }

    /** The masked, truncated output of one run. */
    public record MaskedRunOutput(
            String maskedStdout,
            String maskedStderr,
            int stdoutBytes,
            int stderrBytes,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            byte[] combinedUtf8Bytes,
            String inlineTail) {
        public MaskedRunOutput {
            Objects.requireNonNull(maskedStdout, "maskedStdout");
            Objects.requireNonNull(maskedStderr, "maskedStderr");
            combinedUtf8Bytes = combinedUtf8Bytes == null ? new byte[0] : combinedUtf8Bytes.clone();
            Objects.requireNonNull(inlineTail, "inlineTail");
        }

        @Override
        public byte[] combinedUtf8Bytes() {
            return combinedUtf8Bytes.clone();
        }
    }

    /**
     * Masks each full stream, then truncates each to {@link ScriptExecutionJob#MAX_STREAM_BYTES}. {@code stdoutCut} /
     * {@code stderrCut} say the capture itself was already cut short (the log read hit its byte limit).
     */
    public static MaskedRunOutput maskThenTruncate(
            String rawStdout, String rawStderr, boolean stdoutCut, boolean stderrCut, List<String> injectedValues) {
        List<String> variants = variants(injectedValues);
        String maskedOutFull = maskWithVariants(rawStdout == null ? "" : rawStdout, variants);
        String maskedErrFull = maskWithVariants(rawStderr == null ? "" : rawStderr, variants);
        String maskedOut = truncateUtf8Bytes(maskedOutFull, ScriptExecutionJob.MAX_STREAM_BYTES);
        String maskedErr = truncateUtf8Bytes(maskedErrFull, ScriptExecutionJob.MAX_STREAM_BYTES);
        boolean outTruncated = stdoutCut || maskedOut.length() != maskedOutFull.length();
        boolean errTruncated = stderrCut || maskedErr.length() != maskedErrFull.length();
        String combined = formatCombined(maskedOut, maskedErr, outTruncated, errTruncated);
        return new MaskedRunOutput(
                maskedOut,
                maskedErr,
                maskedOut.getBytes(StandardCharsets.UTF_8).length,
                maskedErr.getBytes(StandardCharsets.UTF_8).length,
                outTruncated,
                errTruncated,
                combined.getBytes(StandardCharsets.UTF_8),
                tailUtf8Bytes(combined, ScriptExecutionJob.MAX_TAIL_BYTES));
    }

    /** Masks one text with every form of every injected value, then with {@link DiagnosticText#scrubSecrets}. */
    public static String mask(String text, List<String> injectedValues) {
        return maskWithVariants(text == null ? "" : text, variants(injectedValues));
    }

    private static String maskWithVariants(String text, List<String> variants) {
        if (text.isEmpty()) {
            return "";
        }
        String scrubbed = text;
        for (String variant : variants) {
            if (scrubbed.contains(variant)) {
                scrubbed = scrubbed.replace(variant, REDACTED_PLACEHOLDER);
            }
        }
        scrubbed = DiagnosticText.scrubSecrets(scrubbed);
        for (String variant : variants) {
            if (scrubbed.contains(variant)) {
                scrubbed = scrubbed.replace(variant, REDACTED_PLACEHOLDER);
            }
        }
        return scrubbed;
    }

    /** Every masked form of every value, longest first so a full form is replaced before any shorter one. */
    public static List<String> variants(List<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return List.of();
        }
        Set<String> variants = new LinkedHashSet<>();
        for (String value : rawValues) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            addForms(variants, value);
            String stripped = value.strip();
            if (!stripped.equals(value)) {
                addForms(variants, stripped);
            }
            for (String line : value.split("\\r?\\n")) {
                String trimmedLine = line.strip();
                if (trimmedLine.length() >= 6
                        && !trimmedLine.startsWith("-----BEGIN ")
                        && !trimmedLine.startsWith("-----END ")) {
                    variants.add(trimmedLine);
                }
            }
        }
        variants.removeIf(v -> v.length() < MIN_MASKED_LENGTH);
        List<String> sorted = new ArrayList<>(variants);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(sorted);
    }

    private static void addForms(Set<String> out, String value) {
        if (value.length() < MIN_MASKED_LENGTH) {
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] withNewline = (value + "\n").getBytes(StandardCharsets.UTF_8);
        out.add(value);
        for (byte[] b : List.of(bytes, withNewline)) {
            String std = Base64.getEncoder().encodeToString(b);
            String url = Base64.getUrlEncoder().encodeToString(b);
            String mimeLf = Base64.getMimeEncoder(76, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(b);
            String mimeCrlf = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.UTF_8)).encodeToString(b);
            out.add(std);
            out.add(stripPadding(std));
            out.add(url);
            out.add(stripPadding(url));
            out.add(mimeLf);
            out.add(stripPadding(mimeLf));
            out.add(mimeCrlf);
            out.add(stripPadding(mimeCrlf));
        }
        out.add(URLEncoder.encode(value, StandardCharsets.UTF_8));
        out.add(URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"));
        String hex = HexFormat.of().formatHex(bytes);
        out.add(hex);
        out.add(hex.toUpperCase(Locale.ROOT));
        out.add(jsonEscape(value));
    }

    private static String stripPadding(String base64) {
        int end = base64.length();
        while (end > 0 && base64.charAt(end - 1) == '=') {
            end--;
        }
        return base64.substring(0, end);
    }

    /** JSON string escaping (without the quotes), as {@code json.dumps} and Jackson write it. */
    public static String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String formatCombined(String out, String err, boolean outTruncated, boolean errTruncated) {
        StringBuilder sb = new StringBuilder(out.length() + err.length() + 128);
        sb.append("=== STDOUT ===");
        if (outTruncated) {
            sb.append(" [TRUNCATED AT 256 KiB]");
        }
        sb.append('\n').append(out);
        if (!out.isEmpty() && !out.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("=== STDERR ===");
        if (errTruncated) {
            sb.append(" [TRUNCATED AT 256 KiB]");
        }
        sb.append('\n').append(err);
        if (!err.isEmpty() && !err.endsWith("\n")) {
            sb.append('\n');
        }
        return sb.toString();
    }

    /** At most {@code maxBytes} UTF-8 bytes of {@code text}, never splitting a character. */
    public static String truncateUtf8Bytes(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /** The last {@code maxBytes} UTF-8 bytes of {@code text}, never splitting a character. */
    public static String tailUtf8Bytes(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int start = bytes.length - maxBytes;
        while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) {
            start++;
        }
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }
}
