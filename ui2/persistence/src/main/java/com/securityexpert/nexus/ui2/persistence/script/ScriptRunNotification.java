package com.securityexpert.nexus.ui2.persistence.script;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The run-end notification outcome recorded on {@code script_run} (V264, {@code SCRIPT_EXECUTION_CONTRACT.md} section
 * 3.5): one state per channel and a short closed reason code for a FAILED or SKIPPED channel -- never a message body,
 * an address or a host.
 */
public record ScriptRunNotification(
        String syslog,
        Optional<String> syslogCode,
        String smtp,
        Optional<String> smtpCode,
        Instant notifiedAt) {

    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";
    public static final String NOT_REQUESTED = "NOT_REQUESTED";
    public static final Set<String> STATES = Set.of(SENT, FAILED, SKIPPED, NOT_REQUESTED);

    public static final String CODE_SYSLOG_NOT_CONFIGURED = "syslog_not_configured";
    public static final String CODE_SMTP_NOT_CONFIGURED = "smtp_not_configured";
    public static final String CODE_NO_RECIPIENTS = "no_recipients";
    public static final String CODE_FAILURE_ONLY = "failure_only";
    public static final String CODE_SEND_FAILED = "send_failed";
    public static final Set<String> SYSLOG_CODES = Set.of(CODE_SYSLOG_NOT_CONFIGURED, CODE_SEND_FAILED);
    public static final Set<String> SMTP_CODES = Set.of(CODE_SMTP_NOT_CONFIGURED, CODE_NO_RECIPIENTS, CODE_FAILURE_ONLY,
            CODE_SEND_FAILED);

    public ScriptRunNotification {
        requireState(syslog, "syslog");
        requireState(smtp, "smtp");
        syslogCode = syslogCode == null ? Optional.empty() : syslogCode;
        smtpCode = smtpCode == null ? Optional.empty() : smtpCode;
        requireCode(syslog, syslogCode, SYSLOG_CODES, "syslog");
        requireCode(smtp, smtpCode, SMTP_CODES, "smtp");
        Objects.requireNonNull(notifiedAt, "notifiedAt");
    }

    /** One channel's outcome before it is written. */
    public record Channel(String state, Optional<String> code) {
        public static Channel sent() {
            return new Channel(SENT, Optional.empty());
        }

        public static Channel notRequested() {
            return new Channel(NOT_REQUESTED, Optional.empty());
        }

        public static Channel skipped(String code) {
            return new Channel(SKIPPED, Optional.of(code));
        }

        public static Channel failed(String code) {
            return new Channel(FAILED, Optional.of(code));
        }
    }

    public static ScriptRunNotification of(Channel syslog, Channel smtp, Instant notifiedAt) {
        return new ScriptRunNotification(syslog.state(), syslog.code(), smtp.state(), smtp.code(), notifiedAt);
    }

    private static void requireState(String state, String channel) {
        if (state == null || !STATES.contains(state)) {
            throw new IllegalArgumentException(channel + " notification state must be one of " + STATES);
        }
    }

    private static void requireCode(String state, Optional<String> code, Set<String> allowed, String channel) {
        boolean needsCode = FAILED.equals(state) || SKIPPED.equals(state);
        if (needsCode != code.isPresent() || (code.isPresent() && !allowed.contains(code.get()))) {
            throw new IllegalArgumentException(channel + " notification code does not fit state " + state);
        }
    }
}
