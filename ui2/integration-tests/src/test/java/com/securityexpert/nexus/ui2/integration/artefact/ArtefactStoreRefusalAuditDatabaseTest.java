package com.securityexpert.nexus.ui2.integration.artefact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStoreRefusalRecorder;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqArtefactStoreRefusalRecorder;

/**
 * V227 over a real database: a refused artefact write leaves one {@code artefact_store_refusal} row and one
 * {@code audit_log} row naming the actor and the action, and the table is append-only for the application role.
 * Synthetic values only. Needs PostgreSQL (excluded from the default build).
 */
class ArtefactStoreRefusalAuditDatabaseTest {

    @Test
    void aRefusalLeavesARowAndAnAuditRowAndTheTableIsAppendOnly() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("artefact_store_refusal_audit")) {
            fixture.runFlyway();
            var boundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var recorder = new JooqArtefactStoreRefusalRecorder(boundary, "worker", "system:worker");
            recorder.record(new ArtefactStoreRefusalRecorder.Refusal("check_point", "store", 900L, 1000L, 200L,
                    "synthetic-device-1", "synthetic-job-1"));
            recorder.record(new ArtefactStoreRefusalRecorder.Refusal(null, "global", 1000L, 1000L, 0L, null, null));

            try (Connection db = fixture.migrateConnection(); var st = db.createStatement()) {
                var rows = st.executeQuery("select count(*) from artefact_store_refusal");
                rows.next();
                assertEquals(2, rows.getInt(1));
                var audit = st.executeQuery("select actor_fingerprint, action_id, operation, after_state ->> 'store_name' as store, "
                        + "after_state ->> 'writer' as writer from audit_log where table_name = 'artefact_store_refusal' "
                        + "order by audit_id");
                assertTrue(audit.next());
                assertEquals("system:worker", audit.getString("actor_fingerprint"));
                assertEquals("artefact_store_quota_refused", audit.getString("action_id"));
                assertEquals("INSERT", audit.getString("operation"));
                assertEquals("check_point", audit.getString("store"));
                assertEquals("worker", audit.getString("writer"));
                assertTrue(audit.next());
                assertFalse(audit.next());
            }
            try (Connection app = fixture.appDataSource().getConnection(); var st = app.createStatement()) {
                org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                        () -> st.execute("delete from artefact_store_refusal"), "append-only for ui2_app");
            }
        }
    }
}
