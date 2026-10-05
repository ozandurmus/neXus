package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.policy.*;
import com.securityexpert.nexus.ui2.jobs.policy.CpPolicyGates;
import org.jooq.impl.DSL;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;

class CpObjectInventorySchemaTest {
    @Test void migrationSignsExactlyApprovedRowsAndApplicationRolePersistsParsedInventoryAndHits() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("cp_object_inventory")) {
            fixture.runFlyway();
            try (var db = fixture.appConnection(); var query = db.prepareStatement(
                    "select sign_off_state, canonical_command_key from gate_registry where canonical_command_key = ?")) {
                for (int i = 3; i < CpPolicyGates.COMMANDS.size(); i++) {
                    query.setString(1, CpPolicyGates.COMMANDS.get(i));
                    try (var rows = query.executeQuery()) {
                        assertTrue(rows.next()); assertEquals("SIGNED_OFF", rows.getString(1));
                        assertEquals(CpPolicyGates.COMMANDS.get(i), rows.getString(2)); assertFalse(rows.next());
                    }
                }
            }
            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var inventories = new PolicyCollectionRepository(tx);
            inventories.saveInventory("source-1", "domain-1", "hosts", "2026-10-05T00:00:00Z",
                "{\"status\":\"RESOLVED\",\"objects\":[{\"uid\":\"001\",\"name\":\"OBJ-HOST-01\"}]}", "synthetic-actor");
            inventories.saveInventory("source-1", "domain-1", "hosts", "2026-10-04T00:00:00Z",
                "{\"status\":\"COLLECTION_FAILED\",\"objects\":[]}", "synthetic-actor");
            var snapshots = new PolicySnapshotRepository(tx);
            assertTrue(snapshots.inventories("source-1", "domain-1").get(0).contains("001"));
            inventories.saveInventory("source-1", "domain-1", "hosts", "2026-10-05T01:00:00Z",
                "{\"status\":\"COLLECTION_FAILED\",\"objects\":[]}", "synthetic-actor");
            assertTrue(snapshots.inventories("source-1", "domain-1").get(0).contains("COLLECTION_FAILED"));
            snapshots.save(new PolicySnapshotRepository.Stored("policy-1", "2026-10-05T00:00:00Z", "{}", """
                {"sections":[{"id":"section-1","rules":[{"id":"rule-1","uuid":"rule-1","source":{"refs":[]},
                 "destination":{"refs":[]},"service":{"refs":[]},"application":{"refs":[]},"extras":{},
                 "hitCounts":{"hits":12,"firstHit":"2026-10-01T00:00:00Z","lastHit":"2026-10-05T00:00:00Z","level":"high"}}]}],
                 "objects":{},"failures":[]}
                """), "synthetic-actor", "policy_collect_publish");
            try (var db = fixture.appConnection(); var statement = db.createStatement()) {
                try (var rows = statement.executeQuery("select snapshot->'sections'->0->'rules'->0->'hitCounts'->>'hits' from policy_snapshot where policy_id='policy-1'")) {
                    assertTrue(rows.next()); assertEquals("12", rows.getString(1));
                }
                assertEquals(1, statement.executeUpdate("delete from cp_policy_object_inventory where source_id='source-1'"));
            }
        }
    }
}
