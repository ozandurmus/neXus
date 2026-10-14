package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.*;
import com.securityexpert.nexus.ui2.persistence.policy.OfflineAnalysisRepository;
import org.junit.jupiter.api.Test;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import java.nio.charset.StandardCharsets;

class OfflineAnalysisSchemaTest {
    @Test void migrationIsIdempotentRollbackSafeGrantsAppWritesAndAuditsDeletionWithoutAggregates() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("offline_analysis")) {
            fixture.runFlyway();
            try (var resource = getClass().getResourceAsStream("/db/migration/V146__policy_offline_analysis.sql")) {
                assertNotNull(resource); String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                    db.setAutoCommit(false); statement.execute(sql); statement.execute(sql); db.rollback();
                }
            }
            var repository = new OfflineAnalysisRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            String aggregates = "{\"rowCount\":1,\"rules\":[{\"observedSource\":\"192.0.2.8\"}],\"parseStats\":[],\"unknownStats\":[]}";
            repository.save("analysis-1", "policy-1", "synthetic-actor", "synthetic-private.csv", aggregates);
            assertTrue(repository.find("analysis-1").orElseThrow().contains("192.0.2.8")); assertEquals(1, repository.list("policy-1", 0).size());
            try (var db = fixture.appConnection(); var statement = db.createStatement()) {
                assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("delete from policy_offline_analysis where analysis_id='analysis-1'"));
            }
            assertTrue(repository.delete("analysis-1", "synthetic-actor")); assertTrue(repository.find("analysis-1").isEmpty());
            try (var db = fixture.migrateConnection(); var statement = db.createStatement(); var result = statement.executeQuery(
                    "select operation, before_state::text, after_state::text from audit_log where table_name='policy_offline_analysis' order by operation")) {
                int count = 0;
                while (result.next()) {
                    count++; String states = result.getString(2) + " " + result.getString(3);
                    assertFalse(states.contains("192.0.2.8")); assertFalse(states.contains("synthetic-private.csv")); assertTrue(states.contains("analysis-1"));
                }
                assertEquals(2, count);
            }
            assertThrows(RuntimeException.class, () -> repository.save("bad-analysis", "policy-1", "synthetic-actor", "test.csv",
                "{\"rowCount\":1,\"rules\":[],\"parseStats\":[],\"unknownStats\":[],\"rows\":[\"synthetic-raw-marker\"]}"));
        }
    }
}
