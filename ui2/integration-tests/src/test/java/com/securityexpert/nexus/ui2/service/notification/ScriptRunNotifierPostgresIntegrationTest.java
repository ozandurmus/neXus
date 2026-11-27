package com.securityexpert.nexus.ui2.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.script.JooqScriptRepository;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRecord;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRunNotification;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRunRecord;
import com.securityexpert.nexus.ui2.persistence.script.ScriptVersionRecord;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * Real PostgreSQL 16 integration tests for {@link ScriptRunNotifier} and {@link NotificationForwarder} against V266
 * ({@code script.notify}, {@code script_run_notification}, {@code notification_state}, advisory lock {@code 10010051},
 * and live-table {@link NotificationIdentity#warm(DSLContext)} masking).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScriptRunNotifierPostgresIntegrationTest {

    private static final byte[] TEST_HMAC_KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String SHA_64 = "a".repeat(64);
    private static final String OUTPUT_MARKER = "the full output is on the Jobs screen):\n";

    private static Ui2PostgresFixture fixture;
    private static DSLContext dsl;
    private static JooqTransactionBoundary tx;
    private static JooqScriptRepository repo;

    private record CapturedSyslog(SyslogSender.Severity severity, String tag, String message) {
    }

    private record CapturedMail(List<String> recipients, String subject, String body) {
    }

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("script_run_notifier_pg");
        dsl = DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES);
        tx = new JooqTransactionBoundary(dsl);
        repo = new JooqScriptRepository(tx);
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    @Order(1)
    void sqlScanChannelMatrixPersistenceAndRepositoryReadback() throws SQLException {
        Instant since = Instant.parse("2026-11-25T10:00:00Z");
        setNotificationWindow(since, since);

        createScript("scr-m1-both", "matrix-both", "both", false, since);
        createScript("scr-m1-both-mof", "matrix-both-mof", "both", true, since);
        createScript("scr-m1-smtp-mof", "matrix-smtp-mof", "smtp", true, since);
        createScript("scr-m1-syslog", "matrix-syslog", "syslog", false, since);
        createScript("scr-m1-none", "matrix-none", "none", false, since);

        // Verify V266 stored notify values on script rows.
        assertEquals("both_mail_on_failure", storedNotifyColumn("scr-m1-both-mof"));
        assertEquals("smtp_mail_on_failure", storedNotifyColumn("scr-m1-smtp-mof"));

        // 4096 UTF-8 bytes exact: 14-byte ASCII prefix + 2041 two-byte 'é' characters.
        String tail4096 = "OUTPUT-MARKER-" + "é".repeat(2041);
        assertEquals(4096, tail4096.getBytes(StandardCharsets.UTF_8).length);

        completeRun("job-m1-both-fail", "scr-m1-both", "FAILED", "FAILURE", 2, 1534L,
                since.plusSeconds(10), tail4096);
        completeRun("job-m1-both-mof-ok", "scr-m1-both-mof", "COMPLETED", "SUCCESS", 0, 210L,
                since.plusSeconds(20), "success-output");
        completeRun("job-m1-both-mof-to", "scr-m1-both-mof", "TIMED_OUT", "TIMED_OUT", null, 300000L,
                since.plusSeconds(30), "timeout-output");
        completeRun("job-m1-smtp-mof-ok", "scr-m1-smtp-mof", "COMPLETED", "SUCCESS", 0, 100L,
                since.plusSeconds(40), "smtp-ok-output");
        completeRun("job-m1-smtp-mof-cancel", "scr-m1-smtp-mof", "CANCELLED", "CANCELLED", null, 500L,
                since.plusSeconds(50), "cancelled-output");
        completeRun("job-m1-syslog-ok", "scr-m1-syslog", "COMPLETED", "SUCCESS", 0, 80L,
                since.plusSeconds(60), "SYSLOG-TAIL-MUST-NEVER-LEAK");
        completeRun("job-m1-none-fail", "scr-m1-none", "FAILED", "FAILURE", 1, 90L,
                since.plusSeconds(70), "none-output");

        var syslogs = new ArrayList<CapturedSyslog>();
        var mails = new ArrayList<CapturedMail>();
        int recorded = notifier(syslogs, mails, false, false)
                .forward(settings(true, true, "ops@example.invalid", NotificationSettings.IDENTITY_RAW),
                        NotificationIdentity.raw());

        assertEquals(7, recorded);
        assertEquals(4, syslogs.size(), "syslog runs: both-fail, both-mof-ok, both-mof-to, syslog-ok");
        assertEquals(3, mails.size(), "mail runs: both-fail, both-mof-to, smtp-mof-cancel");

        // Check syslog lines: RFC 5424 tag/severity, run fields present, output_tail never included.
        CapturedSyslog bothLine = syslogs.get(0);
        assertEquals(SyslogSender.Severity.ERROR, bothLine.severity());
        assertEquals(ScriptRunNotifier.SYSLOG_TAG, bothLine.tag());
        assertTrue(bothLine.message().contains("script=\"matrix-both\""), bothLine.message());
        assertTrue(bothLine.message().contains("version=1"), bothLine.message());
        assertTrue(bothLine.message().contains("outcome=FAILURE"), bothLine.message());
        assertTrue(bothLine.message().contains("exit_code=2"), bothLine.message());
        assertTrue(bothLine.message().contains("duration_ms=1534"), bothLine.message());
        assertTrue(bothLine.message().contains("run_id=job-m1-both-fail"), bothLine.message());
        assertFalse(bothLine.message().contains("OUTPUT-MARKER"), bothLine.message());
        assertFalse(bothLine.message().contains("é"), bothLine.message());

        assertEquals(SyslogSender.Severity.INFORMATIONAL, syslogs.get(1).severity());
        assertTrue(syslogs.get(1).message().contains("run_id=job-m1-both-mof-ok"));
        assertEquals(SyslogSender.Severity.ERROR, syslogs.get(2).severity());
        assertTrue(syslogs.get(2).message().contains("outcome=TIMED_OUT exit_code=-"));
        assertEquals(SyslogSender.Severity.INFORMATIONAL, syslogs.get(3).severity());
        assertFalse(syslogs.get(3).message().contains("SYSLOG-TAIL-MUST-NEVER-LEAK"));

        // Check SMTP mails: both-fail carries the 4096-byte UTF-8 tail without splitting multi-byte chars.
        CapturedMail bothMail = mails.get(0);
        assertEquals(List.of("ops@example.invalid"), bothMail.recipients());
        assertTrue(bothMail.subject().contains("Script run FAILURE: matrix-both v1"), bothMail.subject());
        assertTrue(bothMail.body().contains("Run id:    job-m1-both-fail"), bothMail.body());
        String extractedOutput = extractMailOutput(bothMail.body());
        assertEquals(tail4096, extractedOutput);
        assertTrue(extractedOutput.getBytes(StandardCharsets.UTF_8).length <= 4096);
        assertFalse(extractedOutput.contains("\uFFFD"));

        assertTrue(mails.get(1).subject().contains("Script run TIMED_OUT: matrix-both-mof v1"));
        assertTrue(mails.get(1).body().contains("Run id:    job-m1-both-mof-to"));
        assertTrue(mails.get(2).subject().contains("Script run CANCELLED: matrix-smtp-mof v1"));
        assertTrue(mails.get(2).body().contains("Run id:    job-m1-smtp-mof-cancel"));

        // Verify script_run_notification persistence & JooqScriptRepository readback (run state/outcome untouched).
        assertNotificationRow("job-m1-both-fail", "FAILED", "FAILURE",
                ScriptRunNotification.SENT, null, ScriptRunNotification.SENT, null);
        assertNotificationRow("job-m1-both-mof-ok", "COMPLETED", "SUCCESS",
                ScriptRunNotification.SENT, null,
                ScriptRunNotification.SKIPPED, ScriptRunNotification.CODE_FAILURE_ONLY);
        assertNotificationRow("job-m1-both-mof-to", "TIMED_OUT", "TIMED_OUT",
                ScriptRunNotification.SENT, null, ScriptRunNotification.SENT, null);
        assertNotificationRow("job-m1-smtp-mof-ok", "COMPLETED", "SUCCESS",
                ScriptRunNotification.NOT_REQUESTED, null,
                ScriptRunNotification.SKIPPED, ScriptRunNotification.CODE_FAILURE_ONLY);
        assertNotificationRow("job-m1-smtp-mof-cancel", "CANCELLED", "CANCELLED",
                ScriptRunNotification.NOT_REQUESTED, null, ScriptRunNotification.SENT, null);
        assertNotificationRow("job-m1-syslog-ok", "COMPLETED", "SUCCESS",
                ScriptRunNotification.SENT, null, ScriptRunNotification.NOT_REQUESTED, null);
        assertNotificationRow("job-m1-none-fail", "FAILED", "FAILURE",
                ScriptRunNotification.NOT_REQUESTED, null, ScriptRunNotification.NOT_REQUESTED, null);

        List<ScriptRunRecord> bothMofRuns = repo.findRunsByScriptId("scr-m1-both-mof", 10);
        assertEquals(2, bothMofRuns.size());
        assertTrue(bothMofRuns.stream().allMatch(r -> r.notification().isPresent()));
        ScriptRunRecord okRunFromList = bothMofRuns.stream()
                .filter(r -> "job-m1-both-mof-ok".equals(r.jobId()))
                .findFirst()
                .orElseThrow();
        assertEquals(ScriptRunNotification.SKIPPED, okRunFromList.notification().orElseThrow().smtp());
        assertEquals(Optional.of(ScriptRunNotification.CODE_FAILURE_ONLY),
                okRunFromList.notification().orElseThrow().smtpCode());
    }

    @Test
    @Order(2)
    void unconfiguredChannelsAndSendFailuresComplyWithPostgresCheckConstraint() {
        Instant since = Instant.parse("2026-11-25T12:00:00Z");
        setNotificationWindow(since, since);

        createScript("scr-u-both", "unconf-both", "both", false, since);
        createScript("scr-u-smtp", "unconf-smtp", "smtp", false, since);

        var syslogs = new ArrayList<CapturedSyslog>();
        var mails = new ArrayList<CapturedMail>();

        // 1. Both channels disabled in NotificationSettings -> SKIPPED + not_configured codes.
        completeRun("job-u-not-configured", "scr-u-both", "FAILED", "FAILURE", 1, 120L,
                since.plusSeconds(10), "out");
        assertEquals(1, notifier(syslogs, mails, false, false)
                .forward(settings(false, false, null, NotificationSettings.IDENTITY_RAW), NotificationIdentity.raw()));
        assertTrue(syslogs.isEmpty());
        assertTrue(mails.isEmpty());
        assertNotificationRow("job-u-not-configured", "FAILED", "FAILURE",
                ScriptRunNotification.SKIPPED, ScriptRunNotification.CODE_SYSLOG_NOT_CONFIGURED,
                ScriptRunNotification.SKIPPED, ScriptRunNotification.CODE_SMTP_NOT_CONFIGURED);

        // 2. SMTP enabled with host/from but blank recipients -> SKIPPED / no_recipients.
        completeRun("job-u-no-recipients", "scr-u-smtp", "FAILED", "FAILURE", 1, 130L,
                since.plusSeconds(20), "out");
        assertEquals(1, notifier(syslogs, mails, false, false)
                .forward(settings(true, true, "   ", NotificationSettings.IDENTITY_RAW), NotificationIdentity.raw()));
        assertTrue(mails.isEmpty());
        assertNotificationRow("job-u-no-recipients", "FAILED", "FAILURE",
                ScriptRunNotification.NOT_REQUESTED, null,
                ScriptRunNotification.SKIPPED, ScriptRunNotification.CODE_NO_RECIPIENTS);

        // 3. Both syslog sink and mailer throw IOException -> FAILED / send_failed, never aborts or mutates run.
        completeRun("job-u-both-ioerror", "scr-u-both", "FAILED", "FAILURE", 2, 140L,
                since.plusSeconds(30), "out");
        assertEquals(1, notifier(syslogs, mails, true, true)
                .forward(settings(true, true, "ops@example.invalid", NotificationSettings.IDENTITY_RAW),
                        NotificationIdentity.raw()));
        assertNotificationRow("job-u-both-ioerror", "FAILED", "FAILURE",
                ScriptRunNotification.FAILED, ScriptRunNotification.CODE_SEND_FAILED,
                ScriptRunNotification.FAILED, ScriptRunNotification.CODE_SEND_FAILED);

        // 4. Syslog succeeds while mailer throws IOException -> SENT/null + FAILED/send_failed.
        completeRun("job-u-mail-ioerror", "scr-u-both", "COMPLETED", "SUCCESS", 0, 150L,
                since.plusSeconds(40), "out");
        assertEquals(1, notifier(syslogs, mails, false, true)
                .forward(settings(true, true, "ops@example.invalid", NotificationSettings.IDENTITY_RAW),
                        NotificationIdentity.raw()));
        assertEquals(1, syslogs.size());
        assertNotificationRow("job-u-mail-ioerror", "COMPLETED", "SUCCESS",
                ScriptRunNotification.SENT, null,
                ScriptRunNotification.FAILED, ScriptRunNotification.CODE_SEND_FAILED);
    }

    @Test
    @Order(3)
    void floorWatermarkLateCommitGraceAndIdempotency() {
        Instant since = Instant.parse("2026-11-25T14:00:00Z");
        Instant watermarkT = Instant.parse("2026-11-25T15:00:00Z");
        setNotificationWindow(since, since);

        createScript("scr-wm", "watermark-script", "both", false, since);

        // Strictly before script_run_notify_since -> never scanned or notified.
        completeRun("job-wm-before-since", "scr-wm", "FAILED", "FAILURE", 1, 50L,
                since.minusSeconds(1), "before-since");
        // After since at watermarkT -> notified on pass 1, advancing watermark to watermarkT.
        completeRun("job-wm-at-t", "scr-wm", "FAILED", "FAILURE", 1, 60L,
                watermarkT, "at-t");

        var syslogs = new ArrayList<CapturedSyslog>();
        var mails = new ArrayList<CapturedMail>();
        var configured = settings(true, true, "ops@example.invalid", NotificationSettings.IDENTITY_RAW);
        ScriptRunNotifier n = notifier(syslogs, mails, false, false);

        assertEquals(1, n.forward(configured, NotificationIdentity.raw()));
        assertEquals(1, syslogs.size());
        assertEquals(1, mails.size());
        assertTrue(syslogs.get(0).message().contains("run_id=job-wm-at-t"));
        assertTrue(repo.findRunByJobId("job-wm-before-since").orElseThrow().notification().isEmpty());
        assertTrue(repo.findRunByJobId("job-wm-at-t").orElseThrow().notification().isPresent());
        assertEquals(watermarkT, Instant.parse(readNotificationState(ScriptRunNotifier.WATERMARK_KEY)));

        // Second pass is idempotent: returns 0 and sends no duplicates.
        assertEquals(0, n.forward(configured, NotificationIdentity.raw()));
        assertEquals(1, syslogs.size());
        assertEquals(1, mails.size());

        // Late-committed runs after watermark advanced to watermarkT:
        // - watermarkT - 11m is outside LATE_COMMIT_GRACE (10m) -> NOT scanned.
        // - watermarkT - 5m is inside LATE_COMMIT_GRACE (10m) and >= since -> discovered and notified once.
        completeRun("job-wm-outside-grace", "scr-wm", "FAILED", "FAILURE", 1, 70L,
                watermarkT.minus(Duration.ofMinutes(11)), "outside-grace");
        completeRun("job-wm-inside-grace", "scr-wm", "FAILED", "FAILURE", 1, 80L,
                watermarkT.minus(Duration.ofMinutes(5)), "inside-grace");

        assertEquals(1, n.forward(configured, NotificationIdentity.raw()));
        assertEquals(2, syslogs.size());
        assertEquals(2, mails.size());
        assertTrue(syslogs.get(1).message().contains("run_id=job-wm-inside-grace"));
        assertTrue(repo.findRunByJobId("job-wm-inside-grace").orElseThrow().notification().isPresent());
        assertTrue(repo.findRunByJobId("job-wm-outside-grace").orElseThrow().notification().isEmpty());
        assertEquals(watermarkT, Instant.parse(readNotificationState(ScriptRunNotifier.WATERMARK_KEY)),
                "watermark must never move backwards");

        // Fourth pass sends nothing.
        assertEquals(0, n.forward(configured, NotificationIdentity.raw()));
        assertEquals(2, syslogs.size());
        assertEquals(2, mails.size());
    }

    @Test
    @Order(4)
    void notificationForwarderTickWithAdvisoryLockAndLiveSqlWarmupMasking() throws SQLException {
        Instant since = Instant.parse("2026-11-25T16:00:00Z");
        setNotificationWindow(since, since);

        String rawHost = "FW-TANGO-04";
        String rawIp = "192.0.2.42";
        String deviceId = seedSyntheticDevice(rawHost, rawIp);

        var store = new NotificationSettingsStore(tx);
        store.save(settings(true, true, "ops@example.invalid", NotificationSettings.IDENTITY_MASKED), Ui2Rows.ACTOR);

        // Fresh pseudonymizer + IP masker: NotificationIdentity#warm(dsl) must learn rawHost from PostgreSQL devices.
        var names = new TopologyNamePseudonymizer(TEST_HMAC_KEY);
        var ips = new SubnetPreservingIpMasker(TEST_HMAC_KEY);

        String expectedHost = new TopologyNamePseudonymizer(TEST_HMAC_KEY).maskDeviceName(rawHost, null, deviceId);
        String expectedIp = new SubnetPreservingIpMasker(TEST_HMAC_KEY).mask(rawIp);
        assertNotEquals(rawHost, expectedHost);
        assertNotEquals(rawIp, expectedIp);

        createScript("scr-tick-masked", "check " + rawHost + " at " + rawIp, "both", false, since);

        // 54-byte prefix + 2021 two-byte 'é' = 4096 bytes in DB; masking expands the two IPs so clampUtf8 enforces
        // <= 4096 bytes on a multi-byte character boundary without splitting 'é'.
        String tailPrefix = "connected to " + rawHost + " at " + rawIp + " and " + rawIp + " ";
        String tail = tailPrefix + "é".repeat(2021);
        assertEquals(4096, tail.getBytes(StandardCharsets.UTF_8).length);
        completeRun("job-tick-masked", "scr-tick-masked", "FAILED", "FAILURE", 2, 420L,
                since.plusSeconds(10), tail);

        var syslogs = new ArrayList<CapturedSyslog>();
        var mails = new ArrayList<CapturedMail>();
        var forwarder = new NotificationForwarder(store, tx, names, ips)
                .withSyslog((s, severity, tag, message) -> syslogs.add(new CapturedSyslog(severity, tag, message)))
                .withScriptRunMailer((s, recipients, subject, body) -> mails.add(new CapturedMail(recipients, subject, body)));

        // While another connection holds advisory lock 10010051, tick() returns cleanly without sending or failing.
        try (Connection locker = fixture.appConnection(); Statement st = locker.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT pg_advisory_lock(10010051)")) {
                assertTrue(rs.next());
            }
            try {
                forwarder.tick();
                assertTrue(syslogs.isEmpty(), "contended lock must skip sending syslog");
                assertTrue(mails.isEmpty(), "contended lock must skip sending SMTP");
                assertTrue(repo.findRunByJobId("job-tick-masked").orElseThrow().notification().isEmpty());
            } finally {
                st.executeQuery("SELECT pg_advisory_unlock(10010051)").close();
            }
        }

        // Now lock is free: tick() warms from live SQL tables, masks script name & tail, sends, and persists outcome.
        forwarder.tick();

        assertEquals(1, syslogs.size());
        assertEquals(1, mails.size());

        CapturedSyslog syslog = syslogs.get(0);
        assertEquals(SyslogSender.Severity.ERROR, syslog.severity());
        assertEquals(ScriptRunNotifier.SYSLOG_TAG, syslog.tag());
        assertFalse(syslog.message().contains(rawHost), "syslog leaked raw host: " + syslog.message());
        assertFalse(syslog.message().contains(rawIp), "syslog leaked raw IP: " + syslog.message());
        assertFalse(syslog.message().contains("connected to"), "syslog leaked output: " + syslog.message());
        assertTrue(syslog.message().contains(expectedHost), syslog.message());
        assertTrue(syslog.message().contains(expectedIp), syslog.message());
        assertTrue(syslog.message().contains("run_id=job-tick-masked"), syslog.message());

        CapturedMail mail = mails.get(0);
        assertFalse(mail.subject().contains(rawHost), "subject leaked raw host: " + mail.subject());
        assertFalse(mail.subject().contains(rawIp), "subject leaked raw IP: " + mail.subject());
        assertFalse(mail.body().contains(rawHost), "body leaked raw host: " + mail.body());
        assertFalse(mail.body().contains(rawIp), "body leaked raw IP: " + mail.body());
        assertTrue(mail.subject().contains(expectedHost), mail.subject());
        assertTrue(mail.body().contains(expectedHost), mail.body());
        assertTrue(mail.body().contains(expectedIp), mail.body());

        String output = extractMailOutput(mail.body());
        assertTrue(output.contains(expectedHost), output);
        assertTrue(output.contains(expectedIp), output);
        assertTrue(output.getBytes(StandardCharsets.UTF_8).length <= 4096,
                "clamped mail output must be <= 4096 UTF-8 bytes");
        assertFalse(output.contains("\uFFFD"), "clamping must not split multi-byte UTF-8 characters");
        assertTrue(output.endsWith("é"), "clamped multi-byte output must end on a complete character");

        assertNotificationRow("job-tick-masked", "FAILED", "FAILURE",
                ScriptRunNotification.SENT, null, ScriptRunNotification.SENT, null);
    }

    private static void setNotificationWindow(Instant since, Instant watermark) {
        tx.inTransaction(d -> {
            d.execute("insert into notification_state(key, value) values ({0}, {1}) "
                    + "on conflict (key) do update set value = excluded.value, updated_at = now()",
                    ScriptRunNotifier.SINCE_KEY, since.toString());
            d.execute("insert into notification_state(key, value) values ({0}, {1}) "
                    + "on conflict (key) do update set value = excluded.value, updated_at = now()",
                    ScriptRunNotifier.WATERMARK_KEY, watermark.toString());
            return null;
        });
    }

    private static String readNotificationState(String key) {
        return tx.inTransaction(d -> d.fetchOne("select value from notification_state where key = {0}", key)
                .get("value", String.class));
    }

    private static String storedNotifyColumn(String scriptId) {
        return tx.inTransaction(d -> d.fetchOne("select notify from script where script_id = {0}", scriptId)
                .get("notify", String.class));
    }

    private static void createScript(String scriptId, String name, String notifyMode, boolean mailOnFailureOnly,
            Instant now) {
        ScriptRecord script = new ScriptRecord(
                scriptId, name, "sh", 1, true, Optional.empty(), 300, List.of(), List.of(), List.of(),
                notifyMode, "role:operator", Ui2Rows.ACTOR, now, now, mailOnFailureOnly);
        ScriptVersionRecord version = new ScriptVersionRecord(
                scriptId, 1, SHA_64, 32L, "script_body/" + scriptId + "/" + SHA_64 + ".enc",
                "key-1", new byte[32], List.of(), List.of(), List.of(), Ui2Rows.ACTOR, now);
        repo.create(script, version, Ui2Rows.ACTOR, ActionRegistry.SCRIPT_WRITE);
    }

    private static void completeRun(String jobId, String scriptId, String state, String outcome, Integer exitCode,
            long durationMs, Instant finishedAt, String outputTail) {
        assertTrue(repo.admitRun(jobId, scriptId, 1, "MANUAL", Optional.empty(), "role:operator",
                List.of(), List.of(), List.of(), finishedAt.minusSeconds(10), Ui2Rows.ACTOR, "script_run")
                .isPresent());
        assertTrue(repo.beginDispatch(jobId, 1L, "sess-" + jobId, List.of(), finishedAt.minusSeconds(5),
                "system:script_runner"));
        int stdoutBytes = outputTail == null ? 0 : outputTail.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(repo.completeRun(jobId, state, outcome, finishedAt, durationMs, exitCode, stdoutBytes, 0,
                false, false, Optional.empty(), Optional.empty(), null, Optional.empty(), Optional.empty(),
                Optional.ofNullable(outputTail), List.of(), List.of(), Optional.empty(), "system:script_runner"));
    }

    private static NotificationSettings settings(boolean syslogEnabled, boolean smtpEnabled, String recipients,
            String identityMode) {
        return new NotificationSettings(
                syslogEnabled, syslogEnabled ? "192.0.2.50" : null, 514, "udp", 16,
                smtpEnabled, smtpEnabled ? "198.51.100.25" : null, 25, false,
                smtpEnabled ? "overwatch@example.invalid" : null, recipients,
                false, false, Instant.EPOCH, List.of(), identityMode);
    }

    private static ScriptRunNotifier notifier(List<CapturedSyslog> syslogs, List<CapturedMail> mails,
            boolean failSyslog, boolean failMail) {
        return new ScriptRunNotifier(tx,
                (s, severity, tag, message) -> {
                    if (failSyslog) {
                        throw new IOException("syslog collector unreachable");
                    }
                    syslogs.add(new CapturedSyslog(severity, tag, message));
                },
                (s, recipients, subject, body) -> {
                    if (failMail) {
                        throw new IOException("smtp relay refused");
                    }
                    mails.add(new CapturedMail(recipients, subject, body));
                });
    }

    private static void assertNotificationRow(String jobId, String expectedState, String expectedOutcome,
            String expectedSyslog, String expectedSyslogCode, String expectedSmtp, String expectedSmtpCode) {
        ScriptRunRecord run = repo.findRunByJobId(jobId).orElseThrow();
        assertEquals(expectedState, run.state(), "script_run.state must remain untouched");
        assertEquals(Optional.of(expectedOutcome), run.outcome(), "script_run.outcome must remain untouched");
        ScriptRunNotification notification = run.notification().orElseThrow();
        assertEquals(expectedSyslog, notification.syslog());
        assertEquals(Optional.ofNullable(expectedSyslogCode), notification.syslogCode());
        assertEquals(expectedSmtp, notification.smtp());
        assertEquals(Optional.ofNullable(expectedSmtpCode), notification.smtpCode());
    }

    private static String extractMailOutput(String body) {
        int markerIdx = body.indexOf(OUTPUT_MARKER);
        assertTrue(markerIdx >= 0, "mail body missing output marker: " + body);
        int start = markerIdx + OUTPUT_MARKER.length();
        int end = body.indexOf('\n', start);
        assertTrue(end > start, "mail body missing newline after output: " + body);
        return body.substring(start, end);
    }

    private static String seedSyntheticDevice(String observedHostname, String managementIp) throws SQLException {
        try (Connection db = fixture.migrateConnection()) {
            String credId = Ui2Rows.insertCredentialReference(db);
            String deviceId = Ui2Rows.opaqueId("dev");
            String endpointId = Ui2Rows.opaqueId("ep");
            db.setAutoCommit(false);
            try {
                Ui2Rows.setAuditContext(db, Ui2Rows.ACTOR, Ui2Rows.ACTION);
                try (PreparedStatement ps = db.prepareStatement(
                        "INSERT INTO devices(device_id, vendor_hint, registration_source, is_test_target, "
                                + "enrollment_state, disabled, credential_reference_id, observed_hostname, "
                                + Ui2Rows.DEVICE_REQUIRED_COLUMNS + ") VALUES (?, 'check_point', 'manual', true, "
                                + "'ENROLLED', false, ?, ?, " + Ui2Rows.DEVICE_REQUIRED_VALUES + ")")) {
                    ps.setString(1, deviceId);
                    ps.setString(2, credId);
                    ps.setString(3, observedHostname);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = db.prepareStatement(
                        "INSERT INTO endpoints(endpoint_id, device_id, transport_kind, address_ref) "
                                + "VALUES (?, ?, 'ssh_exec', ?)")) {
                    ps.setString(1, endpointId);
                    ps.setString(2, deviceId);
                    ps.setString(3, managementIp);
                    ps.executeUpdate();
                }
                db.commit();
            } catch (SQLException e) {
                db.rollback();
                throw e;
            } finally {
                db.setAutoCommit(true);
            }
            return deviceId;
        }
    }
}
