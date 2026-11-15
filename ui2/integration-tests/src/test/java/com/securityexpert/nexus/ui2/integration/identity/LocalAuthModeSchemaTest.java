package com.securityexpert.nexus.ui2.integration.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.securityexpert.nexus.ui2.persistence.identity.JooqLocalAuthModeRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqLocalBreakGlassEventRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalAuthModeRepository;

/** V231 (migration plan Step 3): the default mode, the audited mode write, the audited break-glass events, and an insert-only event table. */
class LocalAuthModeSchemaTest {

    @Test
    void theDefaultIsAllLocalTheWriteAndTheEventsAreAuditedAndTheEventTableIsInsertOnly() throws SQLException {
        try (Ui2PostgresFixture fixture = Ui2PostgresFixture.createAndMigrate("local_auth_mode")) {
            var boundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var modes = new JooqLocalAuthModeRepository(boundary);
            var events = new JooqLocalBreakGlassEventRepository(boundary);

            assertEquals(LocalAuthModeRepository.ALL_LOCAL, modes.read().mode());
            assertEquals(LocalAuthModeRepository.BREAK_GLASS_ONLY,
                    modes.write("actor-a", LocalAuthModeRepository.BREAK_GLASS_ONLY).mode());
            assertEquals(LocalAuthModeRepository.BREAK_GLASS_ONLY, modes.read().mode());
            assertThrows(IllegalArgumentException.class, () -> modes.write("actor-a", "EVERYBODY"));

            events.recordLoginSucceeded("actor-root", "ROOT", true);
            events.recordRefused("actor-other");

            try (Connection admin = fixture.migrateConnection(); Statement st = admin.createStatement()) {
                assertEquals(1, count(st, "action_id = 'local_auth_mode_write' and table_name = 'local_auth_mode_settings' and actor_fingerprint = 'actor-a'"));
                assertEquals(1, count(st, "action_id = 'local_break_glass_login_succeeded' and table_name = 'local_break_glass_event' and actor_fingerprint = 'actor-root'"));
                assertEquals(1, count(st, "action_id = 'local_non_break_glass_refused' and table_name = 'local_break_glass_event' and actor_fingerprint = 'actor-other'"));
                assertEquals(1, count(st, "action_id = 'local_auth_mode_seed_by_migration'"));
            }
            try (Connection app = fixture.appConnection(); Statement st = app.createStatement()) {
                SQLException update = assertThrows(SQLException.class, () -> st.execute("update local_break_glass_event set alert = false"));
                assertTrue(update.getMessage().contains("permission denied"), update.getMessage());
                SQLException delete = assertThrows(SQLException.class, () -> st.execute("delete from local_break_glass_event"));
                assertTrue(delete.getMessage().contains("permission denied"), delete.getMessage());
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
