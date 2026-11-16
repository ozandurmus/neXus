package com.securityexpert.nexus.ui2.integration.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.identity.JooqSamlSettingsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SamlSettingsRecord;

/** V241 (migration plan Step 4): SAML is off with no row, the write is audited without any value, the constraints hold, SAML_GROUP is a binding kind. */
class SamlSettingsSchemaTest {

    private static SamlSettingsRecord row(boolean enabled, String admin, String users, byte[] keyCipher, String keyId, String spCert) {
        return new SamlSettingsRecord(enabled, "https://nexus.example.test", "https://nexus.example.test/saml2/service-provider-metadata/adfs",
                "https://adfs.test.example/adfs/services/trust", "https://adfs.test.example/adfs/ls/", "REDIRECT",
                "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n", "http://schemas.xmlsoap.org/claims/Group", admin, users, 60,
                false, spCert, keyCipher, keyId, null, "actor-a");
    }

    @Test
    void thereIsNoRowByDefaultAndAWriteIsAuditedWithoutAnyValue() throws SQLException {
        try (Ui2PostgresFixture fixture = Ui2PostgresFixture.createAndMigrate("saml_settings")) {
            var repository = new JooqSamlSettingsRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            assertTrue(repository.find().isEmpty(), "SAML is off: no row");

            repository.save(row(true, "nexus-admins", "nexus-users", new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                    21, 22, 23, 24, 25, 26, 27, 28, 29, 30}, "key-1", "-----BEGIN CERTIFICATE-----\nBBBB\n-----END CERTIFICATE-----\n"));
            var saved = repository.find().orElseThrow();
            assertTrue(saved.enabled());
            assertEquals("nexus-admins", saved.adminGroupValue());
            assertEquals(30, saved.spPrivateKeyEncrypted().length);
            assertFalse(saved.toString().contains("nexus-admins"), "the record never prints its values");
            repository.save(row(false, "nexus-admins", "nexus-users", null, null, null));
            assertFalse(repository.find().orElseThrow().enabled());

            try (Connection admin = fixture.migrateConnection(); Statement st = admin.createStatement()) {
                assertEquals(2, count(st, "table_name = 'saml_settings' and action_id = 'saml_config_write' and actor_fingerprint = 'actor-a'"));
                // Narrow capture: table, key, operation, actor and action only (no column list that could hold a value).
                try (var rs = st.executeQuery("select count(*) from audit_log where table_name = 'saml_settings' "
                        + "and (before_state is not null or after_state is not null)")) {
                    rs.next();
                    assertEquals(0, rs.getInt(1), "the audit rows hold no value of the settings row");
                }
            }
        }
    }

    @Test
    void theTableRefusesInconsistentRowsAndTheBindingKindIsAccepted() throws SQLException {
        try (Ui2PostgresFixture fixture = Ui2PostgresFixture.createAndMigrate("saml_constraints")) {
            var repository = new JooqSamlSettingsRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            assertThrows(RuntimeException.class, () -> repository.save(row(true, "same", "same", null, null, null)), "equal group values");
            assertThrows(RuntimeException.class, () -> repository.save(row(true, "a", "b", new byte[40], "k", null)), "a key without its certificate");
            assertThrows(RuntimeException.class, () -> repository.save(row(true, "", "b", null, null, null)), "empty group value");
            assertTrue(repository.find().isEmpty());

            try (Connection admin = fixture.migrateConnection(); Statement st = admin.createStatement()) {
                admin.setAutoCommit(false);
                st.execute("select set_config('app.actor_fingerprint', 'actor-t', true), set_config('app.action_id', 'saml_binding_test', true)");
                String insert = "insert into role_bindings(binding_id, role_token, group_reference_encrypted, group_reference_key_id, "
                        + "created_by_actor_fingerprint, created_at, binding_kind, directory_profile_id) values ('%s', 'role:viewer', "
                        + "decode('000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f', 'hex'), 'k', 'actor-t', now(), '%s', %s)";
                st.execute(String.format(insert, "b-saml", "SAML_GROUP", "'saml:adfs'"));
                assertThrows(SQLException.class, () -> st.execute(String.format(insert, "b-bad", "SAML_PRINCIPAL", "'saml:adfs'")));
                admin.rollback();
                admin.setAutoCommit(false);
                st.execute("select set_config('app.actor_fingerprint', 'actor-t', true), set_config('app.action_id', 'saml_binding_test', true)");
                assertThrows(SQLException.class, () -> st.execute(String.format(insert, "b-noprofile", "SAML_GROUP", "null")), "a SAML group needs a profile id");
                admin.rollback();
            }
        }
    }

    private static int count(Statement st, String where) throws SQLException {
        try (var rs = st.executeQuery("select count(*) from audit_log where " + where)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
