package com.securityexpert.nexus.ui2.integration.supportbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemSpec;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleUploadStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleUploadStore.UploadRow;

/** V236 against a real PostgreSQL 16: the upload row, its audit, the host key record, the stale reap and the cleanup state. */
class SupportBundleUploadStoreIntegrationTest {

    private static final String ADMIN = "ACTOR-ALPHA";
    private static final String REASON = "Attach to the vendor case";

    private static long count(Connection db, String sql) throws SQLException {
        try (var st = db.createStatement(); var rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }

    private static String text(Connection db, String sql) throws SQLException {
        try (var st = db.createStatement(); var rs = st.executeQuery(sql)) { return rs.next() ? rs.getString(1) : null; }
    }

    private static void exec(Connection db, String sql) throws SQLException {
        db.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(db, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            try (var st = db.createStatement()) { st.execute(sql); }
            db.commit();
        } finally { db.setAutoCommit(true); }
    }

    private static RequestRow request(String device, UUID id) {
        Instant now = Instant.now();
        return new RequestRow(id, device, "check_point", "cp_gaia_gateway", "NOW", now, "CASE-1", "TAC case preparation", ADMIN,
                now, null, null, now.plusSeconds(14 * 86400), null, null, 0, null, null, null, "{}", "QUEUED", null);
    }

    private static UploadRow upload(UUID id, UUID request, List<UUID> items) {
        return new UploadRow(id, request, items, "cisco_cxd_https", "cxd.cisco.com", 443, "CASE-SECRET-77", "bundle.tgz", "CASE-9",
                REASON, ADMIN, null, null, null, null, "RUNNING", 0, null, 0, null, null, null, null, null, false);
    }

    @Test
    void uploadLifecycleAuditHostKeysReapAndCleanup() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("support_bundle_upload")) {
            fixture.runFlyway();
            String device;
            try (Connection db = fixture.migrateConnection()) {
                String cred = Ui2Rows.insertCredentialReference(db);
                device = Ui2Rows.insertDevice(db, cred, "ENROLLED");
                // the table has no credential column of any kind
                assertEquals(0, count(db, "select count(*) from information_schema.columns where table_name = 'support_bundle_upload' "
                        + "and (column_name ~* 'pass|token|secret|credential|key_material' and column_name <> 'host_key_fingerprint')"));
            }
            var boundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var store = new JooqSupportBundleStore(boundary);
            var uploads = new JooqSupportBundleUploadStore(boundary);
            UUID request = UUID.randomUUID();
            store.insert(request(device, request), List.of(new ItemSpec("cp_gaia_extras", null)));
            UUID item = store.items(request).get(0).id();
            try (Connection db = fixture.migrateConnection()) {
                exec(db, "INSERT INTO backup_artefact(artefact_id, device_id, artefact_class, vendor, hostname_fingerprint, plaintext_sha256, "
                        + "plaintext_bytes, ciphertext_sha256, ciphertext_bytes, key_id, wrapped_data_key, validation, retention_tier, "
                        + "recovery_volume_path, expires_at) VALUES ('art-u', '" + device + "', 'support_bundle', 'check_point', '"
                        + "a".repeat(64) + "', 'plain', 100, 'cipher', 100, 'v1', decode('00', 'hex'), "
                        + "'{\"level_reached\":\"V1\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, 'standard', "
                        + "'art-u.enc', now() + interval '10 days')");
                exec(db, "UPDATE support_bundle_item SET state = 'COMPLETED', artefact_id = 'art-u', size_bytes = 100, sha256 = '"
                        + "b".repeat(64) + "' WHERE id = '" + item + "'");
                exec(db, "UPDATE support_bundle_request SET state = 'COMPLETED', outcome = 'COMPLETED', finished_at = now() WHERE id = '" + request + "'");
            }

            // start: the audited insert and the retrieval ledger row, before any byte
            UUID first = UUID.randomUUID();
            uploads.start(upload(first, request, List.of(item)));
            assertEquals("RUNNING", uploads.find(first).orElseThrow().state());
            assertThrows(RuntimeException.class, () -> uploads.start(upload(UUID.randomUUID(), request, List.of(item))),
                    "one running upload per bundle");
            try (Connection db = fixture.migrateConnection()) {
                assertEquals(1, Ui2Rows.countAuditRows(db, "support_bundle_upload", first.toString()));
                assertEquals(1, count(db, "select count(*) from backup_artefact_retrieval where artefact_id = 'art-u' "
                        + "and destination_path = 'support_bundle_uploaded:cisco_cxd_https' and reason = '" + REASON + "'"));
            }
            // progress is a heartbeat, not an audit event
            uploads.progress(first, 40, 100L, "c".repeat(64), 1);
            try (Connection db = fixture.migrateConnection()) {
                assertEquals(1, Ui2Rows.countAuditRows(db, "support_bundle_upload", first.toString()));
            }
            assertTrue(uploads.finish(first, ADMIN, "COMPLETED", 100, 100L, "c".repeat(64), "HTTP 201", null, null, "SHA256:abc", false, 1));
            assertFalse(uploads.finish(first, ADMIN, "FAILED", 0, null, null, null, "X", null, null, false, 1), "only a running row can end");
            var done = uploads.find(first).orElseThrow();
            assertEquals("COMPLETED", done.state());
            assertEquals(100, done.bytesSent());
            assertEquals("HTTP 201", done.remoteStatus());
            try (Connection db = fixture.migrateConnection()) {
                assertEquals(2, Ui2Rows.countAuditRows(db, "support_bundle_upload", first.toString()));
                String audit = text(db, "select string_agg(coalesce(before_state::text, '') || coalesce(after_state::text, ''), ' ') "
                        + "from audit_log where table_name = 'support_bundle_upload' and row_pk = '" + first + "'");
                assertFalse(audit.contains("CASE-SECRET-77"), "the remote account is redacted in the audit");
                assertFalse(audit.contains(REASON), "the reason is redacted in the audit");
                assertFalse(audit.contains("cxd.cisco.com"), "the destination host is redacted in the audit");
                assertTrue(audit.contains("cisco_cxd_https"), "the destination kind is audited in full");
            }

            // a running row nobody has touched is interrupted
            UUID second = UUID.randomUUID();
            uploads.start(upload(second, request, List.of(item)));
            try (Connection db = fixture.migrateConnection()) {
                exec(db, "UPDATE support_bundle_upload SET heartbeat_at = now() - interval '10 minutes' WHERE id = '" + second + "'");
            }
            assertEquals(1, uploads.reapStale(120));
            assertEquals("INTERRUPTED", uploads.find(second).orElseThrow().state());
            assertEquals(2, uploads.list(request).size());

            // host keys: first sight is not a change, a different key later is, never a refusal
            assertFalse(uploads.recordHostKey("h.example", 22, "ssh-ed25519", "SHA256:one"));
            assertFalse(uploads.recordHostKey("h.example", 22, "ssh-ed25519", "SHA256:one"));
            assertTrue(uploads.recordHostKey("h.example", 22, "ssh-ed25519", "SHA256:two"));
            assertFalse(uploads.recordHostKey("h.example", 22, "ssh-ed25519", "SHA256:two"));
            assertFalse(uploads.recordHostKey("h.example", 2222, "ssh-ed25519", "SHA256:other-port"));

            // cleanup: only a finished request with a recorded, uncleaned directory
            assertTrue(uploads.cleanupTarget(request).isEmpty(), "no recorded work directory");
            try (Connection db = fixture.migrateConnection()) {
                exec(db, "UPDATE support_bundle_request SET work_dir_path = '/var/log/nexus-sb-0123456789ab', work_dir_cleaned = false "
                        + "WHERE id = '" + request + "'");
            }
            assertEquals("/var/log/nexus-sb-0123456789ab", uploads.cleanupTarget(request).orElseThrow().workDirPath());
            assertTrue(uploads.markCleanupRequested(request, ADMIN, "Remove the leftover work directory"));
            assertFalse(uploads.markCleanupRequested(request, ADMIN, "Remove the leftover work directory"), "a second click finds it requested");
            assertTrue(uploads.cleanupTarget(request).isEmpty());
            uploads.attachCleanupJob(request, "job-x", ADMIN);
            uploads.failCleanupRequest(request, ADMIN, "ADMISSION_REFUSED");
            assertTrue(uploads.cleanupTarget(request).isPresent(), "a failed cleanup can be asked again");
            try (Connection db = fixture.migrateConnection()) {
                String audit = text(db, "select string_agg(after_state::text, ' ') from audit_log where table_name = 'support_bundle_request' "
                        + "and row_pk = '" + request + "'");
                assertFalse(audit.contains("Remove the leftover work directory"), "the cleanup reason is redacted");
                assertTrue(audit.contains("cleanup_state"), "the cleanup state is audited");
                // V236 is idempotent
                String sql;
                try (var in = getClass().getResourceAsStream("/db/migration/V236__support_bundle_upload_and_cleanup.sql")) {
                    sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                db.setAutoCommit(false);
                try (var st = db.createStatement()) { st.execute(sql); st.execute(sql); }
                db.rollback();
                db.setAutoCommit(true);
            }
        }
    }
}
