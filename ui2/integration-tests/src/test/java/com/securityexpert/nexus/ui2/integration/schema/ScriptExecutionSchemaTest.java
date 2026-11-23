package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.script.JooqScriptRepository;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRecord;
import com.securityexpert.nexus.ui2.persistence.script.ScriptSecretFinding;
import com.securityexpert.nexus.ui2.persistence.script.ScriptVersionRecord;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * PostgreSQL integration test for V251 ({@code script} and {@code script_version}): append-only monotonic versions,
 * audit capture, redaction of {@code script.egress} and {@code script_version.wrapped_data_key}, and stored endpoint
 * correlation for {@code egress_device_targets}.
 */
class ScriptExecutionSchemaTest {

    private static Ui2PostgresFixture fixture;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("script_execution_slice1");
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void repositoryPersistsScriptsAndAppendOnlyVersionsAndTriggerRefusesUpdateOrDelete() throws SQLException {
        JooqScriptRepository repo = new JooqScriptRepository(
                new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
        Instant now = Instant.parse("2026-11-18T10:00:00Z");
        String sha1 = "a".repeat(64);
        String sha2 = "b".repeat(64);

        ScriptRecord s1 = new ScriptRecord(
                "script-int-1", "nightly-audit", "py", 1, false, Optional.of("0 2 * * *"),
                300, List.of("192.0.2.10:443"), List.of("dev-1"), List.of("cred-ref-1"),
                "syslog", "role:operator", Ui2Rows.ACTOR, now, now);
        ScriptVersionRecord v1 = new ScriptVersionRecord(
                "script-int-1", 1, sha1, 42L, "script_body/script-int-1/" + sha1 + ".enc",
                "key-1", new byte[32], List.of(new ScriptSecretFinding(3, "CREDENTIAL_LITERAL")),
                List.of("cred-ref-1"), List.of("dev-1"), Ui2Rows.ACTOR, now);

        ScriptRecord created = repo.create(s1, v1, Ui2Rows.ACTOR, ActionRegistry.SCRIPT_WRITE);
        assertEquals(1, created.currentVersion());
        assertEquals(List.of("cred-ref-1"), created.credentialRefs());

        ScriptVersionRecord v2 = new ScriptVersionRecord(
                "script-int-1", 2, sha2, 84L, "script_body/script-int-1/" + sha2 + ".enc",
                "key-1", new byte[32], List.of(), List.of("cred-ref-1"), List.of("dev-1"),
                Ui2Rows.ACTOR, now.plusSeconds(60));
        ScriptRecord afterV2 = repo.appendVersion("script-int-1", v2, Ui2Rows.ACTOR, ActionRegistry.SCRIPT_WRITE);
        assertEquals(2, afterV2.currentVersion());
        assertEquals(2, repo.findVersions("script-int-1").size());

        try (Connection migrate = fixture.migrateConnection(); Statement st = migrate.createStatement()) {
            Ui2Rows.setAuditContext(migrate, Ui2Rows.ACTOR, ActionRegistry.SCRIPT_WRITE);
            assertThrows(SQLException.class, () -> st.executeUpdate(
                    "UPDATE script_version SET size_bytes = 99 WHERE script_id = 'script-int-1' AND version = 1"));
            assertThrows(SQLException.class, () -> st.executeUpdate(
                    "DELETE FROM script_version WHERE script_id = 'script-int-1' AND version = 1"));
            assertTrue(Ui2Rows.countAuditRows(migrate, "script", "script-int-1") >= 2);
            assertTrue(Ui2Rows.countAuditRows(migrate, "script_version", "script-int-1") >= 2);
        }

        // V258: scheduled slot fence, script_run admission, completion, and audit capture
        repo.setEnabled("script-int-1", true, Ui2Rows.ACTOR, ActionRegistry.SCRIPT_WRITE);
        Instant slot = Instant.parse("2026-11-18T02:00:00Z");
        var run1 = repo.admitRun(
                "job-script-int-1", "script-int-1", 2, "SCHEDULED", Optional.of(slot),
                "role:operator", List.of("192.0.2.10:443"), List.of("dev-1"), List.of("cred-ref-1"),
                now.plusSeconds(120), "system:script_scheduler", "script_run");
        assertTrue(run1.isPresent());
        assertEquals(Optional.of(slot), repo.findLastScheduledSlot("script-int-1"));

        // Duplicate claim for the same slot is fenced
        var dupRun = repo.admitRun(
                "job-script-int-dup", "script-int-1", 2, "SCHEDULED", Optional.of(slot),
                "role:operator", List.of("192.0.2.10:443"), List.of("dev-1"), List.of("cred-ref-1"),
                now.plusSeconds(125), "system:script_scheduler", "script_run");
        assertTrue(dupRun.isEmpty());

        assertTrue(repo.beginDispatch(
                "job-script-int-1", 1L, "sess-1", List.of("cred-ref-1"), now.plusSeconds(121), "system:script_runner"));
        // The same epoch is never dispatched twice (the body is released once per lease epoch).
        assertFalse(repo.beginDispatch(
                "job-script-int-1", 1L, "sess-2", List.of("cred-ref-1"), now.plusSeconds(122), "system:script_runner"));
        assertTrue(repo.completeRun(
                "job-script-int-1", "COMPLETED", "SUCCESS", now.plusSeconds(124), 3000L, 0,
                12, 0, false, false,
                Optional.of("script_output/script-int-1/" + sha2 + ".enc"),
                Optional.of("key-1"), new byte[32], Optional.of(sha2), Optional.of(12L),
                Optional.of("ok"), List.of("dev-1"), List.of("cred-ref-1"),
                Optional.empty(), "system:script_runner"));
        assertEquals(1, repo.findRunsByScriptId("script-int-1", 10).size());

        // 30-day retention pruner queries and clears expired output columns while keeping the run ledger row
        assertEquals(0, repo.findRunsWithOutputOlderThan(now.plusSeconds(100), 10).size());
        var expired = repo.findRunsWithOutputOlderThan(now.plusSeconds(300), 10);
        assertEquals(1, expired.size());
        assertEquals("job-script-int-1", expired.get(0).jobId());
        assertTrue(repo.clearExpiredRunOutput("job-script-int-1", "system:script_retention"));
        assertEquals(0, repo.findRunsWithOutputOlderThan(now.plusSeconds(300), 10).size());
        var cleared = repo.findRunByJobId("job-script-int-1").orElseThrow();
        assertTrue(cleared.outputArtefactRef().isEmpty());
        assertTrue(cleared.outputTail().isEmpty());

        try (Connection migrate = fixture.migrateConnection()) {
            assertTrue(Ui2Rows.countAuditRows(migrate, "script_run", "job-script-int-1") >= 2);
        }
    }

    /**
     * V264: the widened {@code script.notify} CHECK carries the per-script "mail on failure only" option, the repository
     * maps it onto the API pair, and {@code script_run_notification} records the run-end outcome append-only with its
     * closed states and codes and the module-role grants.
     */
    @Test
    void slice3NotifyOptionAndAppendOnlyNotificationOutcome() throws SQLException {
        JooqScriptRepository repo = new JooqScriptRepository(
                new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
        Instant now = Instant.parse("2026-11-23T10:00:00Z");
        String sha = "c".repeat(64);
        ScriptRecord script = new ScriptRecord(
                "script-int-3", "nightly-mail", "sh", 1, true, Optional.empty(), 300, List.of(), List.of(),
                List.of(), "both", "role:operator", Ui2Rows.ACTOR, now, now, true);
        ScriptVersionRecord version = new ScriptVersionRecord(
                "script-int-3", 1, sha, 10L, "script_body/script-int-3/" + sha + ".enc", "key-1", new byte[32],
                List.of(), List.of(), List.of(), Ui2Rows.ACTOR, now);
        ScriptRecord created = repo.create(script, version, Ui2Rows.ACTOR, ActionRegistry.SCRIPT_WRITE);
        assertEquals("both", created.notifyMode());
        assertTrue(created.notifyMailOnFailureOnly());
        ScriptRecord plain = repo.updateSettings("script-int-3", true, Optional.empty(), 300, List.of(), List.of(),
                List.of(), "smtp", false, "role:operator", Ui2Rows.ACTOR, ActionRegistry.SCRIPT_WRITE).orElseThrow();
        assertEquals("smtp", plain.notifyMode());
        assertFalse(plain.notifyMailOnFailureOnly());
        ScriptRecord narrowed = repo.updateSettings("script-int-3", true, Optional.empty(), 300, List.of(), List.of(),
                List.of(), "smtp", true, "role:operator", Ui2Rows.ACTOR, ActionRegistry.SCRIPT_WRITE).orElseThrow();
        assertTrue(narrowed.notifyMailOnFailureOnly());

        try (Connection migrate = fixture.migrateConnection(); Statement st = migrate.createStatement()) {
            Ui2Rows.setAuditContext(migrate, Ui2Rows.ACTOR, ActionRegistry.SCRIPT_WRITE);
            var stored = st.executeQuery("SELECT notify FROM script WHERE script_id = 'script-int-3'");
            assertTrue(stored.next());
            assertEquals("smtp_mail_on_failure", stored.getString(1));
            assertThrows(SQLException.class, () -> st.executeUpdate(
                    "UPDATE script SET notify = 'syslog_mail_on_failure' WHERE script_id = 'script-int-3'"));
            var seeded = st.executeQuery("SELECT count(*) FROM notification_state "
                    + "WHERE key IN ('script_run_notify_since', 'script_run_notify_watermark')");
            assertTrue(seeded.next());
            assertEquals(2, seeded.getInt(1));
        }

        assertTrue(repo.admitRun("job-script-int-3", "script-int-3", 1, "MANUAL", Optional.empty(), "role:operator",
                List.of(), List.of(), List.of(), now.plusSeconds(10), Ui2Rows.ACTOR, "script_run").isPresent());
        assertTrue(repo.beginDispatch("job-script-int-3", 1L, "sess-3", List.of(), now.plusSeconds(11),
                "system:script_runner"));
        assertTrue(repo.completeRun("job-script-int-3", "FAILED", "FAILURE", now.plusSeconds(20), 9000L, 2, 4, 0,
                false, false, Optional.empty(), Optional.empty(), null, Optional.empty(), Optional.empty(),
                Optional.of("boom"), List.of(), List.of(), Optional.empty(), "system:script_runner"));
        assertTrue(repo.findRunByJobId("job-script-int-3").orElseThrow().notification().isEmpty());

        try (Connection app = fixture.appConnection(); Statement st = app.createStatement()) {
            // Closed states and codes; a code explains exactly a FAILED or SKIPPED channel.
            assertThrows(SQLException.class, () -> st.executeUpdate("INSERT INTO script_run_notification"
                    + "(job_id, syslog, syslog_code, smtp, smtp_code) VALUES ('job-script-int-3', 'SENT', 'send_failed', "
                    + "'NOT_REQUESTED', NULL)"));
            assertThrows(SQLException.class, () -> st.executeUpdate("INSERT INTO script_run_notification"
                    + "(job_id, syslog, syslog_code, smtp, smtp_code) VALUES ('job-script-int-3', 'FAILED', NULL, "
                    + "'NOT_REQUESTED', NULL)"));
            assertThrows(SQLException.class, () -> st.executeUpdate("INSERT INTO script_run_notification"
                    + "(job_id, syslog, syslog_code, smtp, smtp_code) VALUES ('job-script-int-3', 'SENT', NULL, "
                    + "'SKIPPED', 'relay down at 198.51.100.25')"));
            assertThrows(SQLException.class, () -> st.executeUpdate("INSERT INTO script_run_notification"
                    + "(job_id, syslog, syslog_code, smtp, smtp_code) VALUES ('job-script-int-3', 'DELIVERED', NULL, "
                    + "'SENT', NULL)"));
            assertThrows(SQLException.class, () -> st.executeUpdate("INSERT INTO script_run_notification"
                    + "(job_id, syslog, syslog_code, smtp, smtp_code) VALUES ('job-no-such-run', 'SENT', NULL, "
                    + "'SENT', NULL)"));
            assertEquals(1, st.executeUpdate("INSERT INTO script_run_notification"
                    + "(job_id, syslog, syslog_code, smtp, smtp_code) VALUES ('job-script-int-3', 'SENT', NULL, "
                    + "'FAILED', 'send_failed')"));
            // Append-only: a recorded outcome is never rewritten or removed; a second record is refused.
            assertThrows(SQLException.class, () -> st.executeUpdate(
                    "UPDATE script_run_notification SET smtp = 'SENT', smtp_code = NULL WHERE job_id = 'job-script-int-3'"));
            assertThrows(SQLException.class, () -> st.executeUpdate(
                    "DELETE FROM script_run_notification WHERE job_id = 'job-script-int-3'"));
            assertEquals(0, st.executeUpdate("INSERT INTO script_run_notification"
                    + "(job_id, syslog, syslog_code, smtp, smtp_code) VALUES ('job-script-int-3', 'SENT', NULL, "
                    + "'SENT', NULL) ON CONFLICT (job_id) DO NOTHING"));
        }

        var run = repo.findRunByJobId("job-script-int-3").orElseThrow();
        assertEquals("FAILED", run.state(), "recording the notification leaves the run untouched");
        var notification = run.notification().orElseThrow();
        assertEquals("SENT", notification.syslog());
        assertEquals("FAILED", notification.smtp());
        assertEquals(Optional.of("send_failed"), notification.smtpCode());
        assertEquals(1, repo.findRunsByScriptId("script-int-3", 10).size());
        assertTrue(repo.findRunsByScriptId("script-int-3", 10).get(0).notification().isPresent());

        try (Connection admin = fixture.adminDatabaseConnection(); Statement st = admin.createStatement()) {
            assertTrue(privilege(st, "ui2_app", "INSERT"));
            assertFalse(privilege(st, "ui2_app", "UPDATE"));
            assertFalse(privilege(st, "ui2_app", "DELETE"));
            assertTrue(privilege(st, "ui2_service", "SELECT"));
            assertTrue(privilege(st, "ui2_service", "INSERT"));
            assertFalse(privilege(st, "ui2_service", "UPDATE"));
            assertFalse(privilege(st, "ui2_service", "DELETE"));
            assertTrue(privilege(st, "ui2_scheduler", "SELECT"));
            assertFalse(privilege(st, "ui2_scheduler", "INSERT"));
            assertFalse(privilege(st, "ui2_worker_general", "SELECT"));
        }
    }

    private static boolean privilege(Statement st, String role, String privilege) throws SQLException {
        var rs = st.executeQuery("SELECT has_table_privilege('" + role + "', 'public.script_run_notification', '"
                + privilege + "')");
        assertTrue(rs.next());
        return rs.getBoolean(1);
    }
}
