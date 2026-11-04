package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository.TargetBinding;

/**
 * V185 (path analysis A2, PO record 2026-11-03 D-1): policy_target_binding is written as a difference by the app role,
 * audited with context redacted, removed with its device, NULL install_on_ref stays UNKNOWN, and a re-run is a no-op.
 */
class PathAnalysisPolicyTargetBindingSchemaTest {

    @Test
    void bindingsAreAuditedRedactedRemovedWithTheDeviceAndTheMigrationIsIdempotent() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("path_analysis_a2_binding")) {
            fixture.runFlyway();
            String device;
            try (Connection db = fixture.migrateConnection()) {
                device = Ui2Rows.insertDevice(db, Ui2Rows.insertCredentialReference(db), "ENROLLED");
                assertEquals("text:YES", scalar(db, "select data_type || ':' || is_nullable from information_schema.columns "
                        + "where table_name='policy_target_binding' and column_name='install_on_ref'"), "NULL = UNKNOWN");
                assertEquals("2", scalar(db, "select tier from audit_redaction_policy "
                        + "where table_name='policy_target_binding' and column_name='context'"));
                assertEquals("c", scalar(db, "select confdeltype from pg_constraint where conrelid='policy_target_binding'::regclass "
                        + "and contype='f'"), "device deletion cascades");
            }
            var repository = new PolicyCollectionRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            var binding = new TargetBinding(device, "vs-synthetic-01", "policy-synthetic-01", "ref-synthetic-01");
            repository.saveTargetBindings("policy-synthetic-01", List.of(binding, new TargetBinding("absent-device", "", "policy-synthetic-01", "ref-synthetic-01")),
                    "system:worker");
            repository.saveTargetBindings("policy-synthetic-01", List.of(binding), "system:worker");
            assertEquals(List.of(binding), repository.targetBindings("policy-synthetic-01"), "an unenrolled device is skipped, not failed");
            try (Connection db = fixture.migrateConnection()) {
                assertEquals("1", scalar(db, "select count(*) from audit_log where table_name='policy_target_binding'"),
                        "the unchanged second write adds no audit row");
                assertEquals("0", scalar(db, "select count(*) from audit_log where table_name='policy_target_binding' "
                        + "and after_state::text like '%vs-synthetic-01%'"), "context is redacted in the audit trail");
                db.setAutoCommit(false);
                Ui2Rows.setAuditContext(db, Ui2Rows.ACTOR, Ui2Rows.ACTION);
                try (var statement = db.createStatement()) { statement.execute("delete from devices where device_id = '" + device + "'"); }
                db.commit();
                assertEquals("0", scalar(db, "select count(*) from policy_target_binding"));
            }
            try (var resource = getClass().getResourceAsStream("/db/migration/V185__path_analysis_policy_target_binding.sql")) {
                assertNotNull(resource);
                String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                    db.setAutoCommit(false);
                    statement.execute(sql);
                    assertEquals("1", scalar(db, "select count(*) from audit_redaction_policy where table_name='policy_target_binding'"));
                    db.rollback();
                }
            }
        }
    }

    private static String scalar(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
