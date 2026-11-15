package com.securityexpert.nexus.ui2.worker.supportbundle;

import java.nio.charset.StandardCharsets;

import com.securityexpert.nexus.ui2.platform.DiagnosticText;
import com.securityexpert.nexus.ui2.worker.systemlog.SourceFamily;
import com.securityexpert.nexus.ui2.worker.systemlog.redaction.LogRedactor;

/**
 * Write-time secret scrubbing of the text items of a support bundle (design section 5.4 part 1): every line passes
 * {@link LogRedactor} (rule set {@code cp-text-v1}) and then {@link DiagnosticText#scrubSecrets}. A line over 64 KiB
 * is replaced whole, and any exception fails the whole item closed: the caller stores nothing.
 */
public final class SupportBundleTextRedactor {

    public static final String RULESET = LogRedactor.RULE_SET_ID + "+diagnostic-secrets-v1";
    public static final int MAX_LINE_BYTES = 64 * 1024;
    public static final String OVERLONG = "[LINE_REDACTED_OVERLONG]";

    /** The redaction could not be completed; nothing of the input may be stored. */
    public static final class RedactionFailed extends RuntimeException {
        public RedactionFailed(Throwable cause) { super("REDACTION_FAILED", cause, false, false); }
    }

    private final LogRedactor redactor = new LogRedactor();

    public byte[] redact(byte[] raw) {
        try {
            // An overlong line is replaced whole before any rule runs; the rules then see the document, so a PEM block
            // spanning lines is replaced as one (both rule sets track it across lines).
            String text = new String(raw, StandardCharsets.UTF_8);
            StringBuilder bounded = new StringBuilder(text.length());
            int start = 0;
            while (start <= text.length()) {
                int nl = text.indexOf('\n', start);
                String line = nl < 0 ? text.substring(start) : text.substring(start, nl);
                bounded.append(line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES ? OVERLONG : line);
                if (nl < 0) break;
                bounded.append('\n');
                start = nl + 1;
            }
            byte[] first = redactor.redact(SourceFamily.CP_MESSAGES, bounded.toString().getBytes(StandardCharsets.UTF_8)).bytes();
            return DiagnosticText.scrubSecrets(new String(first, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new RedactionFailed(e);
        }
    }

}
