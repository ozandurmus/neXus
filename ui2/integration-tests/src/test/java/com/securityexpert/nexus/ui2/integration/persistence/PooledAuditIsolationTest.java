package com.securityexpert.nexus.ui2.integration.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundaryFactory;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

class PooledAuditIsolationTest {
    @Test void transactionLocalAuditSettingsDoNotSurviveCommitOrRollback() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("pool_audit");
                var pool = new HikariDataSource()) {
            pool.setDataSource(fixture.appDataSource());
            pool.setMaximumPoolSize(1);
            pool.setMinimumIdle(0);
            pool.setConnectionTimeout(10_000);
            var boundary = TransactionBoundaryFactory.fromDataSource(pool);
            Integer backend = boundary.inTransaction(dsl -> {
                dsl.execute("select set_config('app.actor_fingerprint', 'synthetic_actor_one', true)");
                dsl.execute("select set_config('app.action_id', 'synthetic_action_one', true)");
                assertEquals("synthetic_actor_one", dsl.fetchValue("select current_setting('app.actor_fingerprint', true)"));
                return dsl.fetchOne("select pg_backend_pid()").get(0, Integer.class);
            });
            boundary.inTransaction(dsl -> {
                assertEquals(backend, dsl.fetchOne("select pg_backend_pid()").get(0, Integer.class));
                assertNull(dsl.fetchValue("select nullif(current_setting('app.actor_fingerprint', true), '')"));
                assertNull(dsl.fetchValue("select nullif(current_setting('app.action_id', true), '')"));
                dsl.execute("select set_config('app.actor_fingerprint', 'synthetic_actor_two', true)");
                assertEquals("synthetic_actor_two", dsl.fetchValue("select current_setting('app.actor_fingerprint', true)"));
                return null;
            });
            assertThrows(IllegalStateException.class, () -> boundary.inTransaction(dsl -> {
                dsl.execute("select set_config('app.actor_fingerprint', 'synthetic_actor_rollback', true)");
                dsl.execute("select set_config('app.action_id', 'synthetic_action_rollback', true)");
                throw new IllegalStateException("synthetic rollback");
            }));
            boundary.inTransaction(dsl -> {
                assertEquals(backend, dsl.fetchOne("select pg_backend_pid()").get(0, Integer.class));
                assertNull(dsl.fetchValue("select nullif(current_setting('app.actor_fingerprint', true), '')"));
                assertNull(dsl.fetchValue("select nullif(current_setting('app.action_id', true), '')"));
                return null;
            });
        }
    }
}
