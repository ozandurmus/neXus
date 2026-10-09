package com.securityexpert.nexus.ui2.persistence.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

class JooqLifecycleCatalogRepositoryTest {
    @Test void saveReplacesTheSameProductInsideAnAuditedTransaction() {
        var queries = new ArrayList<String>();
        var repository = new JooqLifecycleCatalogRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(ctx -> {
            queries.add(ctx.sql()); return new MockResult[]{new MockResult(1, null)};
        }), SQLDialect.POSTGRES)));
        var row = new LifecycleCatalogEntry("row-1", "CHECKPOINT", "HARDWARE", "Example Appliance", null,
                LocalDate.of(2030, 1, 1), null, "IMPORT", "Synthetic source", "synthetic-actor", Instant.EPOCH);
        repository.save(List.of(row), null, "synthetic-actor");
        assertTrue(queries.getFirst().contains("SET LOCAL app.actor_fingerprint"));
        assertTrue(queries.get(1).contains("SET LOCAL app.action_id"));
        String sql = queries.getLast();
        assertTrue(sql.contains("on conflict (vendor,kind,product) do update"));
        assertTrue(sql.contains("end_of_support=excluded.end_of_support"));
        assertFalse(sql.contains("catalog_id=excluded.catalog_id"));
        assertTrue(sql.contains("imported_by=excluded.imported_by"));
        queries.clear();
        repository.save(List.of(row), "existing-row", "synthetic-actor");
        assertTrue(queries.getLast().contains("where catalog_id="));
        assertFalse(queries.getLast().contains("insert into"));
    }

    @Test void latestLicenseReadUsesLatestRunWithoutLoadingTopology() {
        var queries = new ArrayList<String>();
        var result = DSL.using(SQLDialect.POSTGRES).fetchFromStringData(new String[]{"device_id", "observed_model", "observed_software_version", "licenses"},
                new String[]{"device-1", "Example Appliance", "R81.20", "[]"});
        var repository = new JooqLifecycleCatalogRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(ctx -> {
            queries.add(ctx.sql()); return new MockResult[]{new MockResult(1, result)};
        }), SQLDialect.POSTGRES)));
        assertEquals(java.util.Map.of("device-1", new LifecycleCatalogRepository.StoredInventory("Example Appliance", "R81.20", "[]")), repository.storedInventory());
        assertEquals(1, queries.size());
        assertTrue(queries.getFirst().contains("distinct on (device_id)"));
        assertTrue(queries.getFirst().contains("collected_at desc"));
        assertFalse(queries.getFirst().contains("device_route"));
        assertFalse(queries.getFirst().contains("grid_member"));
        assertFalse(queries.getFirst().contains("discovery_candidate"));
        assertFalse(queries.getFirst().contains("coalesce"));
    }
}
