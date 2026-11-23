package com.securityexpert.nexus.ui2.service.notification;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRunNotification;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRunNotification.Channel;
import com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics;

/**
 * Script Execution slice 3 (SCRIPT_EXECUTION_CONTRACT.md section 3.5, V266): the run-end notifications a script record
 * asks for ({@code script.notify}).
 *
 * <ul>
 * <li>syslog: one RFC 5424 line per finished run with script name, version, outcome, exit code, duration and run id --
 * never output;</li>
 * <li>SMTP: one mail per finished run (or only for a run that did not succeed, when the script says "mail on failure
 * only") with the same fields and at most {@value #MAX_MAIL_OUTPUT_BYTES} bytes of {@code script_run.output_tail}, which
 * the runner already masked and capped; the encrypted full output is never read.</li>
 * </ul>
 *
 * <p>Best-effort: a send failure is recorded on the run ({@code script_run_notification}, append-only) and never touches
 * the run row. A channel the script asks for that is not configured in Settings &gt; Notifications is recorded
 * {@code SKIPPED} with a code. At most {@value #MAX_RUNS_PER_TICK} runs per tick, oldest first. The watermark in
 * {@code notification_state} advances only to a run whose outcome is recorded; the scan reaches back
 * {@link #LATE_COMMIT_GRACE} before it (never before the V266 floor) so a run committed late is still found, and the
 * recorded row, not the watermark, is what prevents a second send. Logs carry the run id and outcome codes only.</p>
 */
final class ScriptRunNotifier {

    private static final System.Logger LOG = System.getLogger(ScriptRunNotifier.class.getName());

    static final String WATERMARK_KEY = "script_run_notify_watermark";
    static final String SINCE_KEY = "script_run_notify_since";
    static final String SYSLOG_TAG = "SCRIPT_RUN";
    static final int MAX_RUNS_PER_TICK = 50;
    static final int MAX_MAIL_OUTPUT_BYTES = 4096;
    static final Duration LATE_COMMIT_GRACE = Duration.ofMinutes(10);

    /** Mail send, replaceable in tests. */
    @FunctionalInterface
    interface Mailer {
        void send(NotificationSettings settings, List<String> recipients, String subject, String body)
                throws java.io.IOException;
    }

    /** What a finished run looks like to the notifier; no output beyond the already-masked tail. */
    record FinishedRun(String jobId, String scriptName, int version, String state, String outcome, Integer exitCode,
            Long durationMs, Instant finishedAt, String outputTail, String notifyMode) {
    }

    private final TransactionBoundary transactionBoundary;
    private final NotificationForwarder.SyslogSink syslog;
    private final Mailer mailer;

    ScriptRunNotifier(TransactionBoundary transactionBoundary, NotificationForwarder.SyslogSink syslog, Mailer mailer) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.syslog = Objects.requireNonNull(syslog, "syslog");
        this.mailer = Objects.requireNonNull(mailer, "mailer");
    }

    /** One pass; returns the number of runs whose outcome was recorded. */
    int forward(NotificationSettings settings, NotificationIdentity identity) {
        List<FinishedRun> runs;
        Instant watermark;
        try {
            Instant[] mark = new Instant[1];
            runs = transactionBoundary.inTransaction(dsl -> {
                Instant since = state(dsl, SINCE_KEY).orElse(Instant.EPOCH);
                mark[0] = state(dsl, WATERMARK_KEY).orElse(since);
                Instant floor = mark[0].minus(LATE_COMMIT_GRACE);
                if (floor.isBefore(since)) floor = since;
                identity.warm(dsl);
                return dsl.fetch("select r.job_id, s.name, r.version, r.state, r.outcome, r.exit_code, r.duration_ms, "
                        + "r.finished_at, r.output_tail, s.notify from script_run r join script s on s.script_id = r.script_id "
                        + "where r.finished_at is not null and r.finished_at >= {0} "
                        + "and r.state in ('COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED') "
                        + "and not exists (select 1 from script_run_notification n where n.job_id = r.job_id) "
                        + "order by r.finished_at, r.job_id limit {1}", Timestamp.from(floor), MAX_RUNS_PER_TICK)
                        .stream().map(ScriptRunNotifier::finishedRun).toList();
            });
            watermark = mark[0];
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[NOTIFY_SCRIPT_RUN_SCAN_FAILED] {0}", e.getClass().getSimpleName());
            return 0;
        }
        int recorded = 0;
        Instant recordedUpTo = null;
        for (FinishedRun run : runs) {
            ScriptRunNotification outcome = notify(run, settings, identity);
            try {
                transactionBoundary.inTransaction(dsl -> dsl.execute(
                        "insert into script_run_notification(job_id, syslog, syslog_code, smtp, smtp_code, notified_at) "
                                + "values ({0}, {1}, {2}, {3}, {4}, {5}) on conflict (job_id) do nothing",
                        run.jobId(), outcome.syslog(), outcome.syslogCode().orElse(null), outcome.smtp(),
                        outcome.smtpCode().orElse(null), Timestamp.from(outcome.notifiedAt())));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "[NOTIFY_SCRIPT_RUN_RECORD_FAILED] run={0}: {1}", run.jobId(),
                        e.getClass().getSimpleName());
                break;
            }
            recorded++;
            recordedUpTo = run.finishedAt();
            LOG.log(System.Logger.Level.INFO, "[NOTIFY_SCRIPT_RUN] run={0} syslog={1}{2} smtp={3}{4}", run.jobId(),
                    outcome.syslog(), outcome.syslogCode().map(c -> "/" + c).orElse(""), outcome.smtp(),
                    outcome.smtpCode().map(c -> "/" + c).orElse(""));
        }
        if (recordedUpTo != null && recordedUpTo.isAfter(watermark)) {
            String value = recordedUpTo.toString();
            try {
                transactionBoundary.inTransaction(dsl -> dsl.execute("insert into notification_state(key, value) "
                        + "values ({0}, {1}) on conflict (key) do update set value = excluded.value, updated_at = now()",
                        WATERMARK_KEY, value));
            } catch (RuntimeException e) {
                // The recorded rows still prevent a second send; the next pass rescans from the old watermark.
                LOG.log(System.Logger.Level.WARNING, "[NOTIFY_SCRIPT_RUN_WATERMARK_FAILED] {0}",
                        e.getClass().getSimpleName());
            }
        }
        if (recorded > 0) {
            LOG.log(System.Logger.Level.INFO, "[NOTIFY_SCRIPT_RUN_PASS] recorded={0} scanned={1}", recorded, runs.size());
        }
        return recorded;
    }

    /** Sends what the run's script asks for and returns the outcome to record; never throws. */
    ScriptRunNotification notify(FinishedRun run, NotificationSettings settings, NotificationIdentity identity) {
        String notify = run.notifyMode() == null ? "none" : run.notifyMode();
        boolean wantsSyslog = notify.equals("syslog") || notify.startsWith("both");
        boolean wantsMail = notify.startsWith("smtp") || notify.startsWith("both");
        boolean mailOnFailureOnly = notify.endsWith("_mail_on_failure");
        String name = scriptName(run, identity);

        Channel syslogOutcome;
        if (!wantsSyslog) {
            syslogOutcome = Channel.notRequested();
        } else if (!settings.syslogEnabled() || settings.syslogHost() == null || settings.syslogHost().isBlank()) {
            syslogOutcome = Channel.skipped(ScriptRunNotification.CODE_SYSLOG_NOT_CONFIGURED);
        } else {
            try {
                syslog.send(settings, severity(run), SYSLOG_TAG, syslogMessage(run, name));
                NexusMetrics.recordNotificationSend("syslog_script_run", "success");
                syslogOutcome = Channel.sent();
            } catch (Exception e) {
                NexusMetrics.recordNotificationSend("syslog_script_run", "failure");
                syslogOutcome = Channel.failed(ScriptRunNotification.CODE_SEND_FAILED);
            }
        }

        Channel mailOutcome;
        List<String> recipients = settings.recipients();
        if (!wantsMail) {
            mailOutcome = Channel.notRequested();
        } else if (mailOnFailureOnly && "SUCCESS".equals(run.outcome())) {
            mailOutcome = Channel.skipped(ScriptRunNotification.CODE_FAILURE_ONLY);
        } else if (!settings.smtpEnabled() || settings.smtpHost() == null || settings.smtpHost().isBlank()
                || settings.smtpFrom() == null || settings.smtpFrom().isBlank()) {
            mailOutcome = Channel.skipped(ScriptRunNotification.CODE_SMTP_NOT_CONFIGURED);
        } else if (recipients.isEmpty()) {
            mailOutcome = Channel.skipped(ScriptRunNotification.CODE_NO_RECIPIENTS);
        } else {
            try {
                NotificationMailTemplate.ScriptRunMail mail = NotificationMailTemplate.renderScriptRun(
                        MailBrand.name(), MailBrand.environment(), name, run.version(), outcomeLabel(run),
                        run.exitCode(), run.durationMs(), run.jobId(), run.finishedAt(), mailOutput(run, identity));
                mailer.send(settings, recipients, mail.subject(), mail.text());
                NexusMetrics.recordNotificationSend("script_run_mail", "success");
                mailOutcome = Channel.sent();
            } catch (Exception e) {
                NexusMetrics.recordNotificationSend("script_run_mail", "failure");
                mailOutcome = Channel.failed(ScriptRunNotification.CODE_SEND_FAILED);
            }
        }
        return ScriptRunNotification.of(syslogOutcome, mailOutcome, Instant.now());
    }

    /** The syslog line: fields only, never output. */
    static String syslogMessage(FinishedRun run, String name) {
        return "script run finished script=\"" + name + "\" version=" + run.version() + " outcome=" + outcomeLabel(run)
                + " exit_code=" + (run.exitCode() == null ? "-" : run.exitCode())
                + " duration_ms=" + (run.durationMs() == null ? "-" : run.durationMs())
                + " run_id=" + run.jobId();
    }

    static SyslogSender.Severity severity(FinishedRun run) {
        if ("SUCCESS".equals(run.outcome())) return SyslogSender.Severity.INFORMATIONAL;
        if ("CANCELLED".equals(run.outcome())) return SyslogSender.Severity.NOTICE;
        return SyslogSender.Severity.ERROR;
    }

    /** A run without a recorded outcome is UNKNOWN, never shown as success. */
    static String outcomeLabel(FinishedRun run) {
        return run.outcome() == null || run.outcome().isBlank() ? "UNKNOWN" : run.outcome();
    }

    /** The operator-chosen name, masked like any free text, one line, quotes removed. */
    static String scriptName(FinishedRun run, NotificationIdentity identity) {
        String masked = identity.text(run.scriptName());
        String flat = masked == null ? "" : masked.replaceAll("[\\r\\n\"]", " ").strip();
        return flat.isEmpty() ? "-" : flat;
    }

    /**
     * The already-masked output tail, masked again for the notification identity mode (names and addresses), then clamped
     * to {@value #MAX_MAIL_OUTPUT_BYTES} UTF-8 bytes. Mask first, then clamp: clamping first could cut a name in half.
     */
    static String mailOutput(FinishedRun run, NotificationIdentity identity) {
        String tail = run.outputTail();
        if (tail == null || tail.isEmpty()) return null;
        String masked = identity.text(tail);
        return masked == null ? null : clampUtf8(masked, MAX_MAIL_OUTPUT_BYTES);
    }

    static String clampUtf8(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return text;
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static Optional<Instant> state(DSLContext dsl, String key) {
        Record r = dsl.fetchOne("select value from notification_state where key = {0}", key);
        if (r == null) return Optional.empty();
        try {
            return Optional.of(Instant.parse(r.get("value", String.class)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static FinishedRun finishedRun(Record r) {
        return new FinishedRun(r.get("job_id", String.class), r.get("name", String.class),
                r.get("version", Integer.class), r.get("state", String.class), r.get("outcome", String.class),
                r.get("exit_code", Integer.class), r.get("duration_ms", Long.class),
                r.get("finished_at", Timestamp.class).toInstant(), r.get("output_tail", String.class),
                r.get("notify", String.class));
    }
}
