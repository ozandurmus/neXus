package com.securityexpert.nexus.ui2.integration.supportbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqRetentionQueries;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemSpec;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;

/**
 * V230 against a real PostgreSQL 16: constraints, the one-active-request-per-device index, the audit triggers and the
 * download audit function, the retention queries (warning first, deleted requests, quota exclusion) and the gate rows.
 */
class SupportBundleSchemaAndStoreIntegrationTest {

    private static final String ADMIN = "ACTOR-ALPHA";

    private static long count(Connection db, String sql) throws SQLException {
        try (var st = db.createStatement(); var rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
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

    private static void artefact(Connection db, String id, String device, String expires) throws SQLException {
        exec(db, "INSERT INTO backup_artefact(artefact_id, device_id, artefact_class, vendor, hostname_fingerprint, plaintext_sha256, "
                + "plaintext_bytes, ciphertext_sha256, ciphertext_bytes, key_id, wrapped_data_key, validation, retention_tier, "
                + "recovery_volume_path, expires_at) VALUES ('" + id + "', '" + device + "', 'support_bundle', 'check_point', '"
                + "a".repeat(64) + "', 'plain', 100, 'cipher', 100, 'v1', decode('00', 'hex'), "
                + "'{\"level_reached\":\"V1\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, 'standard', '"
                + id + ".enc', " + expires + ")");
    }

    @Test
    void gateRowsAndSchemaAreIdempotentAndConstrained() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("support_bundle_schema")) {
            fixture.runFlyway();
            try (Connection db = fixture.migrateConnection()) {
                assertEquals(10, count(db, "select count(*) from gate_registry where gate_id like 'cp_sb_%'"));
                assertEquals(9, count(db, "select count(*) from gate_registry where gate_id like 'cp_sb_%' and sign_off_state = 'SIGNED_OFF' "
                        + "and action_class = 'read' and platform_role_scope = 'cp_gaia_gateway' and canonical_command_key like 'unset BASH_ENV ENV;%' "
                        + "or gate_id = 'cp_sb_sftp_get_hcp'"));
                assertEquals(1, count(db, "select count(*) from gate_registry where gate_id = 'cp_sb_cpdata_collector_run' "
                        + "and action_class = 'recovery-write' and sign_off_state = 'SIGNED_OFF'"));
                String sql;
                try (var in = getClass().getResourceAsStream("/db/migration/V230__support_bundle_collection.sql")) {
                    sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                db.setAutoCommit(false);
                try (var st = db.createStatement()) { st.execute(sql); st.execute(sql); }
                db.rollback();
                db.setAutoCommit(true);
                assertEquals(1, count(db, "select count(*) from pg_constraint where conname = 'backup_artefact_support_bundle_shape_check'"));
            }
        }
    }

    @Test
    void requestLifecycleAuditQuotaAndRetention() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("support_bundle_store")) {
            fixture.runFlyway();
            String device;
            try (Connection db = fixture.migrateConnection()) {
                String cred = Ui2Rows.insertCredentialReference(db);
                device = Ui2Rows.insertDevice(db, cred, "ENROLLED");
            }
            var boundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var store = new JooqSupportBundleStore(boundary);
            var queries = new JooqRetentionQueries(boundary);
            UUID first = UUID.randomUUID();
            store.insert(request(device, first), List.of(new ItemSpec("cp_gaia_extras", null), new ItemSpec("cp_hcp_latest", null)));
            assertEquals(2, store.items(first).size());
            assertEquals(1, store.liveRequestCount(device));
            // one active request per device
            assertThrows(RuntimeException.class, () -> store.insert(request(device, UUID.randomUUID()), List.of(new ItemSpec("cp_gaia_extras", null))));
            try (Connection db = fixture.migrateConnection()) {
                assertEquals(1, Ui2Rows.countAuditRows(db, "support_bundle_request", first.toString()));
                
            }
            // cancel a queued request frees the device
            assertTrue(store.cancelQueued(first, ADMIN));
            assertFalse(store.cancelQueued(first, ADMIN));
            UUID second = UUID.randomUUID();
            store.insert(request(device, second), List.of(new ItemSpec("cp_gaia_extras", null)));
            String jobId;
            try (Connection db = fixture.migrateConnection()) { jobId = Ui2Rows.insertJob(db, device); }
            store.attachJob(second, jobId, ADMIN);
            assertEquals(Optional.of(second), store.findByJob(jobId).map(RequestRow::id));
            assertTrue(store.markRunning(second));
            assertFalse(store.markRunning(second));
            UUID item = store.items(second).get(0).id();
            try (Connection db = fixture.migrateConnection()) { artefact(db, "art-1", device, "now() + interval '10 days'"); }
            store.finishItem(item, "COMPLETED", "COMPLETED", null, List.of("cp_sb_df_var_log"));
            try (Connection db = fixture.migrateConnection()) {
                exec(db, "UPDATE support_bundle_item SET artefact_id = 'art-1', size_bytes = 100, sha256 = '" + "b".repeat(64)
                        + "' WHERE id = '" + item + "'");
            }
            store.finishRequest(second, "COMPLETED", "COMPLETED", "{\"cpu_max_core_pct\":8}", Instant.now().plusSeconds(10 * 86400));
            assertEquals(1, store.storedItems(second).size());
            assertEquals(100, store.liveBundleBytes());
            // the bundle bytes stay outside the backup storage budget
            assertEquals(0, queries.liveCiphertextBytes());
            // a download writes the request audit row and one retrieval row with the reason, before any byte
            store.recordDownload(second, ADMIN, "Attach to the vendor case", "raw");
            try (Connection db = fixture.migrateConnection()) {
                assertEquals(1, count(db, "select count(*) from backup_artefact_retrieval where artefact_id = 'art-1' and reason = 'Attach to the vendor case'"));
                assertTrue(count(db, "select count(*) from audit_log where table_name = 'support_bundle_request' and row_pk = '" + second
                        + "' and action_id = 'support_bundle_retrieve'") >= 1);
                // free text never reaches the audit ledger
                assertEquals(0, count(db, "select count(*) from audit_log where table_name = 'support_bundle_request' and "
                        + "(before_state::text like '%TAC case preparation%' or after_state::text like '%TAC case preparation%')"));
            }
            assertEquals("COMPLETED", store.find(second).orElseThrow().state());
            assertEquals(1, store.find(second).orElseThrow().downloadCount());
            // retention: nothing is due, then due but unwarned (kept), then warned a day ago (pruned)
            assertTrue(queries.expiredSupportBundles().isEmpty());
            try (Connection db = fixture.migrateConnection()) {
                exec(db, "UPDATE backup_artefact SET expires_at = now() - interval '1 hour' WHERE artefact_id = 'art-1'");
                exec(db, "UPDATE support_bundle_request SET expires_at = now() - interval '1 hour' WHERE id = '" + second + "'");
            }
            assertTrue(queries.expiredSupportBundles().isEmpty(), "expired but never warned: kept");
            assertEquals(1, queries.warnExpiringSupportBundles());
            assertTrue(queries.expiredSupportBundles().isEmpty(), "warned just now: kept for 24 hours");
            try (Connection db = fixture.migrateConnection()) {
                exec(db, "UPDATE support_bundle_request SET expiry_warned_at = now() - interval '25 hours' WHERE id = '" + second + "'");
            }
            assertEquals(1, queries.expiredSupportBundles().size());
            queries.recordRemoved(UUID.randomUUID().toString(), "art-1", "standard", "system:worker", "backup_artefact_pruned");
            assertEquals(1, queries.closeExpiredSupportBundleRequests());
            assertEquals("EXPIRED", store.find(second).orElseThrow().state());
            assertEquals(0, store.liveBundleBytes());
            // a deleted request is marked at once and its files are pruned by the worker with no age rule
            UUID third = UUID.randomUUID();
            store.insert(request(device, third), List.of(new ItemSpec("cp_gaia_extras", null)));
            UUID thirdItem = store.items(third).get(0).id();
            try (Connection db = fixture.migrateConnection()) {
                artefact(db, "art-2", device, "now() + interval '10 days'");
                exec(db, "UPDATE support_bundle_item SET state = 'COMPLETED', artefact_id = 'art-2' WHERE id = '" + thirdItem + "'");
                exec(db, "UPDATE support_bundle_request SET state = 'COMPLETED', finished_at = now() WHERE id = '" + third + "'");
            }
            assertTrue(queries.expiredSupportBundles().isEmpty());
            assertTrue(store.markDeleted(third, ADMIN, "Downloaded and attached"));
            assertFalse(store.markDeleted(third, ADMIN, "Downloaded and attached"));
            assertEquals(1, queries.expiredSupportBundles().size());
            assertTrue(store.storedItems(third).size() == 1, "files stay until the worker removes them; downloads are refused by state");
            assertEquals("DELETED", store.find(third).orElseThrow().state());
        }
    }
}
