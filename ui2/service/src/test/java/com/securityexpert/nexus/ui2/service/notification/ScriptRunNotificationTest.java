package com.securityexpert.nexus.ui2.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRunNotification;

/**
 * Script Execution slice 3 (contract section 3.5, V264): the run-end syslog line and mail a script asks for, recorded
 * on the run, best-effort, with a watermark that never passes an unrecorded run.
 */
class ScriptRunNotificationTest {

    private static final String[] RUN_COLUMNS = {"job_id", "name", "version", "state", "outcome", "exit_code",
            "duration_ms", "finished_at", "output_tail", "notify"};
    private static final String FIRST_FINISH = "2026-11-23 10:00:00";
    private static final String SECOND_FINISH = "2026-11-23 10:05:00";

    private static Result<Record> rows(String[] columns, String[]... values) {
        String[][] data = new String[values.length + 1][];
        data[0] = columns;
        System.arraycopy(values, 0, data, 1, values.length);
        return DSL.using(SQLDialect.POSTGRES).fetchFromStringData(data);
    }

    /** An in-memory notification_state, a scripted finished-run scan and the recorded outcomes. */
    private static final class Fixture {
        final Map<String, String> state = new HashMap<>();
        final List<String[]> runs = new ArrayList<>();
        final Map<String, Object[]> recorded = new HashMap<>();
        final Set<String> failRecordFor = new HashSet<>();
        final List<Object> scanFloors = new ArrayList<>();
        final List<String> statements = new ArrayList<>();
        final DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            String sql = ctx.sql();
            statements.add(sql);
            Result<Record> result;
            if (sql.contains("select value from notification_state")) {
                String value = state.get(String.valueOf(ctx.bindings()[0]));
                result = value == null ? rows(new String[] {"value"}) : rows(new String[] {"value"}, new String[] {value});
            } else if (sql.contains("insert into notification_state")) {
                state.put(String.valueOf(ctx.bindings()[0]), String.valueOf(ctx.bindings()[1]));
                return new MockResult[] {new MockResult(1, rows(new String[] {"x"}))};
            } else if (sql.contains("from script_run r join script s")) {
                scanFloors.add(ctx.bindings()[0]);
                result = rows(RUN_COLUMNS, runs.stream()
                        .filter(r -> !recorded.containsKey(r[0])).toArray(String[][]::new));
            } else if (sql.contains("insert into script_run_notification")) {
                String jobId = String.valueOf(ctx.bindings()[0]);
                if (failRecordFor.contains(jobId)) {
                    throw new java.sql.SQLException("simulated write failure");
                }
                recorded.put(jobId, ctx.bindings());
                return new MockResult[] {new MockResult(1, rows(new String[] {"x"}))};
            } else {
                result = rows(new String[] {"empty"});
            }
            return new MockResult[] {new MockResult(result.size(), result)};
        }), SQLDialect.POSTGRES);

        void run(String jobId, String outcome, String notify, String finishedAt, String tail) {
            runs.add(new String[] {jobId, "nightly-export", "3", "SUCCESS".equals(outcome) ? "COMPLETED" : "FAILED",
                    outcome, "SUCCESS".equals(outcome) ? "0" : "2", "1534", finishedAt, tail, notify});
        }

        String syslogOf(String jobId) {
            return String.valueOf(recorded.get(jobId)[1]);
        }

        Object syslogCodeOf(String jobId) {
            return recorded.get(jobId)[2];
        }

        String smtpOf(String jobId) {
            return String.valueOf(recorded.get(jobId)[3]);
        }

        Object smtpCodeOf(String jobId) {
            return recorded.get(jobId)[4];
        }
    }

    private record Mail(List<String> recipients, String subject, String body) {}

    private static NotificationSettings settings(boolean syslog, boolean smtp, String recipients) {
        return new NotificationSettings(syslog, syslog ? "192.0.2.50" : null, 514, "udp", 16, smtp,
                smtp ? "198.51.100.25" : null, 25, false, smtp ? "overwatch@example.invalid" : null, recipients, false,
                false, Instant.EPOCH, List.of(), NotificationSettings.IDENTITY_RAW);
    }

    private static NotificationSettings both() {
        return settings(true, true, "ops@example.invalid");
    }

    private static ScriptRunNotifier notifier(Fixture fixture, List<String> syslogLines, List<Mail> mails,
            boolean failSyslog, boolean failMail) {
        return new ScriptRunNotifier(new JooqTransactionBoundary(fixture.dsl),
                (s, severity, tag, message) -> {
                    if (failSyslog) throw new IOException("collector down");
                    syslogLines.add(severity + "|" + tag + "|" + message);
                },
                (s, recipients, subject, body) -> {
                    if (failMail) throw new IOException("relay refused");
                    mails.add(new Mail(recipients, subject, body));
                });
    }

    @Test
    void theSyslogLineCarriesTheRunFieldsAndNeverTheOutput() {
        var fixture = new Fixture();
        fixture.run("job-script-1", "FAILURE", "syslog", FIRST_FINISH, "OUTPUT-MARKER line from FW-TANGO-04");
        var lines = new ArrayList<String>();
        var mails = new ArrayList<Mail>();

        int recorded = notifier(fixture, lines, mails, false, false).forward(both(), NotificationIdentity.raw());

        assertEquals(1, recorded);
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.startsWith("ERROR|SCRIPT_RUN|script run finished "), line);
        assertTrue(line.contains("script=\"nightly-export\""), line);
        assertTrue(line.contains("version=3"), line);
        assertTrue(line.contains("outcome=FAILURE"), line);
        assertTrue(line.contains("exit_code=2"), line);
        assertTrue(line.contains("duration_ms=1534"), line);
        assertTrue(line.contains("run_id=job-script-1"), line);
        assertFalse(line.contains("OUTPUT-MARKER"), "syslog never carries output: " + line);
        assertTrue(mails.isEmpty(), "notify=syslog sends no mail");
        assertEquals(ScriptRunNotification.SENT, fixture.syslogOf("job-script-1"));
        assertEquals(ScriptRunNotification.NOT_REQUESTED, fixture.smtpOf("job-script-1"));
        assertNull(fixture.syslogCodeOf("job-script-1"));
        assertNull(fixture.smtpCodeOf("job-script-1"));
    }

    @Test
    void aSuccessfulRunIsAnInformationalLine() {
        var fixture = new Fixture();
        fixture.run("job-script-ok", "SUCCESS", "syslog", FIRST_FINISH, null);
        var lines = new ArrayList<String>();
        notifier(fixture, lines, new ArrayList<>(), false, false).forward(both(), NotificationIdentity.raw());
        assertTrue(lines.get(0).startsWith("INFORMATIONAL|SCRIPT_RUN|"), lines.get(0));
        assertTrue(lines.get(0).contains("outcome=SUCCESS exit_code=0"), lines.get(0));
    }

    @Test
    void theMailCarriesTheFieldsAndAtMost4096BytesOfTheMaskedTail() {
        var fixture = new Fixture();
        // A full stored tail of multi-byte text: 2048 two-byte characters = 4096 bytes, plus one more character.
        String tail = "é".repeat(2049);
        fixture.run("job-script-2", "FAILURE", "smtp", FIRST_FINISH, tail);
        var mails = new ArrayList<Mail>();

        notifier(fixture, new ArrayList<>(), mails, false, false).forward(both(), NotificationIdentity.raw());

        assertEquals(1, mails.size());
        Mail mail = mails.get(0);
        assertEquals(List.of("ops@example.invalid"), mail.recipients());
        assertTrue(mail.subject().contains("Script run FAILURE: nightly-export v3"), mail.subject());
        assertTrue(mail.body().contains("Exit code: 2"), mail.body());
        assertTrue(mail.body().contains("Duration:  1534 ms"), mail.body());
        assertTrue(mail.body().contains("Run id:    job-script-2"), mail.body());
        String marker = "the full output is on the Jobs screen):\n";
        int start = mail.body().indexOf(marker) + marker.length();
        String output = mail.body().substring(start, mail.body().indexOf("\n", start));
        assertEquals(4096, output.getBytes(StandardCharsets.UTF_8).length, "clamped to 4096 bytes on a character boundary");
        assertEquals(ScriptRunNotification.SENT, fixture.smtpOf("job-script-2"));
        assertEquals(ScriptRunNotification.NOT_REQUESTED, fixture.syslogOf("job-script-2"));
    }

    @Test
    void maskedModeWithoutThePseudonymizerWithholdsTheTailAndTheNameRatherThanSendingThem() {
        var fixture = new Fixture();
        fixture.run("job-script-3", "FAILURE", "both", FIRST_FINISH, "reached FW-TANGO-04 at 192.0.2.10");
        var lines = new ArrayList<String>();
        var mails = new ArrayList<Mail>();

        notifier(fixture, lines, mails, false, false).forward(both(), NotificationIdentity.masked(null, null));

        assertFalse(mails.get(0).body().contains("192.0.2.10"), mails.get(0).body());
        assertFalse(mails.get(0).body().contains("FW-TANGO-04"), mails.get(0).body());
        assertTrue(mails.get(0).body().contains(NotificationIdentity.TEXT_UNAVAILABLE), mails.get(0).body());
        assertTrue(lines.get(0).contains("script=\"" + NotificationIdentity.TEXT_UNAVAILABLE + "\""), lines.get(0));
    }

    @Test
    void mailOnFailureOnlySendsNoMailForASuccessButStillTheSyslogLine() {
        var fixture = new Fixture();
        fixture.run("job-script-4", "SUCCESS", "both_mail_on_failure", FIRST_FINISH, "done");
        fixture.run("job-script-5", "FAILURE", "both_mail_on_failure", SECOND_FINISH, "failed");
        var lines = new ArrayList<String>();
        var mails = new ArrayList<Mail>();

        notifier(fixture, lines, mails, false, false).forward(both(), NotificationIdentity.raw());

        assertEquals(2, lines.size(), "the syslog line is sent on every run end");
        assertEquals(1, mails.size());
        assertTrue(mails.get(0).body().contains("job-script-5"));
        assertEquals(ScriptRunNotification.SKIPPED, fixture.smtpOf("job-script-4"));
        assertEquals(ScriptRunNotification.CODE_FAILURE_ONLY, fixture.smtpCodeOf("job-script-4"));
        assertEquals(ScriptRunNotification.SENT, fixture.smtpOf("job-script-5"));
    }

    @Test
    void aSendFailureIsRecordedFailedAndTheRunRowIsNeverTouched() {
        var fixture = new Fixture();
        fixture.run("job-script-6", "FAILURE", "both", FIRST_FINISH, "x");
        var lines = new ArrayList<String>();
        var mails = new ArrayList<Mail>();

        int recorded = notifier(fixture, lines, mails, true, true).forward(both(), NotificationIdentity.raw());

        assertEquals(1, recorded);
        assertEquals(ScriptRunNotification.FAILED, fixture.syslogOf("job-script-6"));
        assertEquals(ScriptRunNotification.CODE_SEND_FAILED, fixture.syslogCodeOf("job-script-6"));
        assertEquals(ScriptRunNotification.FAILED, fixture.smtpOf("job-script-6"));
        assertEquals(ScriptRunNotification.CODE_SEND_FAILED, fixture.smtpCodeOf("job-script-6"));
        assertTrue(fixture.statements.stream().noneMatch(sql -> sql.contains("update script_run")
                        || sql.contains("update \"script_run\"")),
                "the notifier never updates the run: " + fixture.statements);
        assertEquals(Timestamp.valueOf(FIRST_FINISH).toInstant(),
                Instant.parse(fixture.state.get(ScriptRunNotifier.WATERMARK_KEY)),
                "a recorded failure is a recorded attempt: the watermark moves past it");
    }

    @Test
    void theWatermarkNeverPassesARunWhoseOutcomeWasNotRecorded() {
        var fixture = new Fixture();
        fixture.state.put(ScriptRunNotifier.WATERMARK_KEY, "2026-11-22T00:00:00Z");
        fixture.run("job-script-7", "FAILURE", "syslog", FIRST_FINISH, null);
        fixture.run("job-script-8", "FAILURE", "syslog", SECOND_FINISH, null);
        fixture.failRecordFor.add("job-script-8");
        var lines = new ArrayList<String>();

        int recorded = notifier(fixture, lines, new ArrayList<>(), false, false).forward(both(), NotificationIdentity.raw());

        assertEquals(1, recorded);
        assertTrue(fixture.recorded.containsKey("job-script-7"));
        assertFalse(fixture.recorded.containsKey("job-script-8"));
        assertEquals(Timestamp.valueOf(FIRST_FINISH).toInstant(),
                Instant.parse(fixture.state.get(ScriptRunNotifier.WATERMARK_KEY)));

        // The first run's record fails: nothing is recorded and the watermark stays.
        var again = new Fixture();
        again.state.put(ScriptRunNotifier.WATERMARK_KEY, "2026-11-22T00:00:00Z");
        again.run("job-script-9", "FAILURE", "syslog", FIRST_FINISH, null);
        again.failRecordFor.add("job-script-9");
        assertEquals(0, notifier(again, new ArrayList<>(), new ArrayList<>(), false, false)
                .forward(both(), NotificationIdentity.raw()));
        assertEquals("2026-11-22T00:00:00Z", again.state.get(ScriptRunNotifier.WATERMARK_KEY));
    }

    @Test
    void theScanReachesBackTheGraceBeforeTheWatermarkButNeverBeforeTheMigrationFloor() {
        var fixture = new Fixture();
        fixture.state.put(ScriptRunNotifier.SINCE_KEY, "2026-11-23T08:00:00Z");
        fixture.state.put(ScriptRunNotifier.WATERMARK_KEY, "2026-11-23T09:00:00Z");
        notifier(fixture, new ArrayList<>(), new ArrayList<>(), false, false).forward(both(), NotificationIdentity.raw());
        assertEquals(Timestamp.from(Instant.parse("2026-11-23T08:50:00Z")), fixture.scanFloors.get(0));

        var fresh = new Fixture();
        fresh.state.put(ScriptRunNotifier.SINCE_KEY, "2026-11-23T08:55:00Z");
        fresh.state.put(ScriptRunNotifier.WATERMARK_KEY, "2026-11-23T09:00:00Z");
        notifier(fresh, new ArrayList<>(), new ArrayList<>(), false, false).forward(both(), NotificationIdentity.raw());
        assertEquals(Timestamp.from(Instant.parse("2026-11-23T08:55:00Z")), fresh.scanFloors.get(0));
    }

    @Test
    void aChannelThatIsNotConfiguredIsRecordedSkippedWithItsCode() {
        var fixture = new Fixture();
        fixture.run("job-script-10", "FAILURE", "both", FIRST_FINISH, "x");
        var lines = new ArrayList<String>();
        var mails = new ArrayList<Mail>();

        notifier(fixture, lines, mails, false, false).forward(settings(false, false, null), NotificationIdentity.raw());

        assertTrue(lines.isEmpty());
        assertTrue(mails.isEmpty());
        assertEquals(ScriptRunNotification.SKIPPED, fixture.syslogOf("job-script-10"));
        assertEquals(ScriptRunNotification.CODE_SYSLOG_NOT_CONFIGURED, fixture.syslogCodeOf("job-script-10"));
        assertEquals(ScriptRunNotification.SKIPPED, fixture.smtpOf("job-script-10"));
        assertEquals(ScriptRunNotification.CODE_SMTP_NOT_CONFIGURED, fixture.smtpCodeOf("job-script-10"));

        var noRecipients = new Fixture();
        noRecipients.run("job-script-11", "FAILURE", "smtp", FIRST_FINISH, "x");
        notifier(noRecipients, lines, mails, false, false).forward(settings(true, true, null), NotificationIdentity.raw());
        assertTrue(mails.isEmpty());
        assertEquals(ScriptRunNotification.SKIPPED, noRecipients.smtpOf("job-script-11"));
        assertEquals(ScriptRunNotification.CODE_NO_RECIPIENTS, noRecipients.smtpCodeOf("job-script-11"));
    }

    @Test
    void notifyNoneRecordsNotRequestedAndSendsNothing() {
        var fixture = new Fixture();
        fixture.run("job-script-12", "FAILURE", "none", FIRST_FINISH, "x");
        var lines = new ArrayList<String>();
        var mails = new ArrayList<Mail>();
        notifier(fixture, lines, mails, false, false).forward(both(), NotificationIdentity.raw());
        assertTrue(lines.isEmpty());
        assertTrue(mails.isEmpty());
        assertEquals(ScriptRunNotification.NOT_REQUESTED, fixture.syslogOf("job-script-12"));
        assertEquals(ScriptRunNotification.NOT_REQUESTED, fixture.smtpOf("job-script-12"));
    }

    @Test
    void aRunAlreadyRecordedIsNotScannedOrSentAgain() {
        var fixture = new Fixture();
        fixture.run("job-script-13", "FAILURE", "syslog", FIRST_FINISH, null);
        var lines = new ArrayList<String>();
        var notifier = notifier(fixture, lines, new ArrayList<>(), false, false);
        notifier.forward(both(), NotificationIdentity.raw());
        notifier.forward(both(), NotificationIdentity.raw());
        assertEquals(1, lines.size());
    }

    @Test
    void theForwarderRunsTheScriptRunPassWithItsOwnSinks() {
        var fixture = new Fixture();
        fixture.run("job-script-14", "TIMED_OUT", "both", FIRST_FINISH, "x");
        var lines = new ArrayList<String>();
        var mails = new ArrayList<Mail>();
        var forwarder = new NotificationForwarder(mock(NotificationSettingsStore.class),
                new JooqTransactionBoundary(fixture.dsl), null, null)
                .withSyslog((s, severity, tag, message) -> lines.add(tag + "|" + message))
                .withScriptRunMailer((s, recipients, subject, body) -> mails.add(new Mail(recipients, subject, body)));

        assertEquals(1, forwarder.forwardScriptRuns(both(), NotificationIdentity.raw()));
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("outcome=TIMED_OUT"), lines.get(0));
        assertEquals(1, mails.size());
    }

    @Test
    void clampKeepsWholeCharacters() {
        assertEquals("ab", ScriptRunNotifier.clampUtf8("ab", 4));
        assertEquals("a", ScriptRunNotifier.clampUtf8("aé", 2));
        assertEquals("aé", ScriptRunNotifier.clampUtf8("aéb", 3));
    }
}
