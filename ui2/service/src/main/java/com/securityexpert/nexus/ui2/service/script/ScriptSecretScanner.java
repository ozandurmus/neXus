package com.securityexpert.nexus.ui2.service.script;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.script.ScriptSecretFinding;

/**
 * Scans uploaded script bodies with the same secret-detection patterns and safe-literal rules as the repository
 * privacy gate ({@code tools/privacy/repository_privacy.py}: {@code _PRIVATE_KEY_RE}, {@code _HIGH_RISK_ASSIGNMENT_RE},
 * {@code _SAFE_LITERAL_VALUES}, {@code _is_prose_not_secret}).
 *
 * <p>Per Product Owner decision 2026-11-18 ({@code PO_DECISION_RECORD_2026_11_18_SCRIPT_EXECUTION_NO_REFUSALS.md}),
 * secret matches produce a structured warning (line numbers and pattern classes, never the matched value) and are
 * recorded on {@code script_version.secret_findings}; they never refuse the upload.</p>
 */
public final class ScriptSecretScanner {

    public static final String RULE_PRIVATE_KEY_MATERIAL = "PRIVATE_KEY_MATERIAL";
    public static final String RULE_CREDENTIAL_LITERAL = "CREDENTIAL_LITERAL";

    private static final Pattern PRIVATE_KEY_RE = Pattern.compile(
            "-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----");

    private static final Pattern HIGH_RISK_ASSIGNMENT_RE = Pattern.compile(
            "(?i)\\b(password|passwd|api[_-]?key|access[_-]?token|secret|community|psk)\\b\\s*[:=]\\s*[\"']([^\"']+)[\"']");

    private static final Set<String> SAFE_LITERAL_VALUES = Set.of(
            "", "none", "null", "redacted", "[redacted]", "[auth_secret:redacted]",
            "synthetic-secret", "synthetic-password", "example", "test", "changeme");

    private ScriptSecretScanner() {
    }

    /**
     * Scans a script body (decoded as UTF-8 for text kinds, or ISO-8859-1 line scan for binary kinds) and returns
     * deduplicated findings ordered by line number and pattern class. Matched values are never retained.
     */
    public static List<ScriptSecretFinding> scan(byte[] bytes, boolean textKind) {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        String text = new String(bytes, textKind ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
        return scanText(text);
    }

    public static List<ScriptSecretFinding> scanText(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        Set<ScriptSecretFinding> findings = new LinkedHashSet<>();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = lines[i];
            if (PRIVATE_KEY_RE.matcher(line).find()) {
                findings.add(new ScriptSecretFinding(lineNo, RULE_PRIVATE_KEY_MATERIAL));
            }
            Matcher assignment = HIGH_RISK_ASSIGNMENT_RE.matcher(line);
            while (assignment.find()) {
                String rawValue = assignment.group(2);
                String normalized = rawValue.strip().toLowerCase(Locale.ROOT);
                if (!SAFE_LITERAL_VALUES.contains(normalized)
                        && !normalized.startsWith("[")
                        && !isProseNotSecret(rawValue)) {
                    findings.add(new ScriptSecretFinding(lineNo, RULE_CREDENTIAL_LITERAL));
                    break;
                }
            }
        }
        List<ScriptSecretFinding> sorted = new ArrayList<>(findings);
        sorted.sort(Comparator.comparingInt(ScriptSecretFinding::line)
                .thenComparing(ScriptSecretFinding::patternClass));
        return List.copyOf(sorted);
    }

    /**
     * Mirrors {@code tools/privacy/repository_privacy.py::_is_prose_not_secret} verbatim: true when the captured
     * value has no alphanumeric character, starts or ends with whitespace, or contains four or more words.
     */
    static boolean isProseNotSecret(String value) {
        String stripped = value.strip();
        boolean hasAlnum = false;
        for (int i = 0; i < stripped.length(); i++) {
            if (Character.isLetterOrDigit(stripped.charAt(i))) {
                hasAlnum = true;
                break;
            }
        }
        if (!hasAlnum) {
            return true;
        }
        if (!value.equals(stripped)) {
            return true;
        }
        return stripped.split("\\s+").length >= 4;
    }

    /** Formats the non-blocking uploader warning for non-empty secret findings (never includes matched values). */
    public static String formatWarning(List<ScriptSecretFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return "";
        }
        List<Integer> lines = findings.stream().map(ScriptSecretFinding::line).distinct().sorted().toList();
        List<String> classes = findings.stream().map(ScriptSecretFinding::patternClass).distinct().sorted().toList();
        return "Secret-pattern match(es) detected on line(s) " + lines + " (" + String.join(", ", classes)
                + "); the script body is stored encrypted at rest, but declared credential-store references "
                + "(credential_refs) are recommended instead of inline credentials.";
    }
}
