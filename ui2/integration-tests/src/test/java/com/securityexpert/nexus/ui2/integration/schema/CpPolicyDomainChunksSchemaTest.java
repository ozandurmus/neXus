package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.*;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobRecordDao;
import com.securityexpert.nexus.ui2.persistence.policy.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import java.util.Collections;

class CpPolicyDomainChunksSchemaTest {
    @Test void appRoleWritesBoundedChunksAndReadsLegacyAndNewDomainAndInventoryFormats() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("cp_domain_chunks")) {
            fixture.runFlyway();
            var tx = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var collections = new PolicyCollectionRepository(tx);
            var snapshots = new PolicySnapshotRepository(tx);
            new JooqJobRecordDao(tx).insertRequestedIfAbsentForRun("job-1", "key-1", "cp_policy_collect", "run-1", "read",
                "cp_policy_collect", "synthetic-actor", "policy_collect").orElseThrow();
            new AuditedTransactionBoundary(tx).inTransaction("synthetic-actor", "policy_collect", db -> {
                db.execute("update jobs set state = 'EXECUTING', lease_epoch = 1, lease_expires_at = now() + interval '1 hour' where job_id = 'job-1'");
                db.execute("insert into cp_policy_domain_run(job_id, source_id, domain_ref, status, complete, signal, snapshots) "
                    + "values ('job-1','source-1','domain-1','COLLECTED',true,'{}','[{\"legacy\":true}]')");
                db.execute("insert into cp_policy_object_inventory(source_id, domain_ref, object_type, collected_at, snapshot) "
                    + "values ('source-1','domain-1','hosts','2026-10-04T00:00:00Z','{\"status\":\"RESOLVED\",\"objects\":[]}')");
                return null;
            });
            assertTrue(collections.previousDomain("source-1", "domain-1").orElseThrow().snapshotsJson().contains("legacy"));
            assertTrue(snapshots.inventories("source-1", "domain-1").get(0).contains("RESOLVED"));
            String item = "{\"uid\":\"opaque-001\",\"name\":\"" + "x".repeat(8192) + "\"}";
            String array = "[" + String.join(",", Collections.nCopies(7680, item)) + "]";
            assertTrue(array.length() >= 60 * 1024 * 1024);
            String inventory = "{\"status\":\"RESOLVED\",\"objects\":" + array + "}";
            collections.saveInventory("source-1", "domain-1", "hosts", "2026-10-05T00:00:00Z", inventory, "synthetic-actor");
            assertEquals(inventory, snapshots.inventories("source-1", "domain-1").get(0));
            var request = new PolicyCollectionRepository.Request("source-1", "", false, PolicyCollectionRepository.Mode.CHANGED_ONLY, "job-1", 1);
            assertTrue(collections.saveDomain(request, "domain-1", "COLLECTED", true, "{}", array, 0, null, "synthetic-actor"));
            assertEquals(array, collections.previousDomain("source-1", "domain-1").orElseThrow().snapshotsJson());
            assertTrue(collections.saveDomain(request, "domain-1", "COLLECTED", false, "{\"uid\":\"must-not-be-published\"}", "[]", 0, null, "synthetic-actor"));
            assertNull(collections.previousDomain("source-1", "domain-1").orElseThrow().signalJson());
            assertFalse(collections.saveDomain(request.withLease(2), "domain-1", "REUSED", true, "{}", "[]", 0, null, "synthetic-actor"));
            // An older inventory cannot replace the published generation.
            collections.saveInventory("source-1", "domain-1", "hosts", "2026-10-03T00:00:00Z", "{\"objects\":[]}", "synthetic-actor");
            assertEquals(inventory, snapshots.inventories("source-1", "domain-1").get(0));
            try (var connection = fixture.appConnection(); var query = connection.createStatement()) {
                try (var rows = query.executeQuery("select count(*), max(octet_length(payload)), max(plain_bytes) from cp_policy_json_chunk")) {
                    assertTrue(rows.next()); assertTrue(rows.getInt(1) > 100);
                    assertTrue(rows.getInt(2) <= 1048576); assertTrue(rows.getInt(3) <= 1048576);
                }
                // The app role has UPDATE and DELETE as well as SELECT/INSERT.
                assertTrue(query.executeUpdate("update cp_policy_json_chunk set plain_bytes = plain_bytes") > 0);
                assertThrows(java.sql.SQLException.class, () -> query.executeUpdate("update cp_policy_json_chunk set plain_bytes = 1048577"));
                assertTrue(query.executeUpdate("delete from cp_policy_json_chunk") > 0);
            }
        }
    }
}
