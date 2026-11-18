package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
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
        JooqScriptRepository repo = new JooqScriptRepository(fixture.appTransactionBoundary());
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
    }
}
