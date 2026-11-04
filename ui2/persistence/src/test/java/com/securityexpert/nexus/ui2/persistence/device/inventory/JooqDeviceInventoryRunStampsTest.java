package com.securityexpert.nexus.ui2.persistence.device.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** The run-stamp read (topology change detection) touches the run table only and never loads a child row. */
class JooqDeviceInventoryRunStampsTest {

    @Test
    void stampsComeFromTheRunTableOnlyAndAnEmptyListReadsNothing() {
        List<String> statements = new ArrayList<>();
        var create = DSL.using(SQLDialect.POSTGRES);
        var device = DSL.field("device_id", String.class);
        var run = DSL.field("run_id", String.class);
        var at = DSL.field("collected_at", Timestamp.class);
        Instant collected = Instant.parse("2026-11-04T06:00:00Z");
        DSLContext dsl = DSL.using(new MockConnection(query -> {
            statements.add(query.sql().replaceAll("\\s+", " "));
            var result = create.newResult(device, run, at);
            result.add(create.newRecord(device, run, at).values("dev-a", "run-1", Timestamp.from(collected)));
            return new MockResult[] {new MockResult(result.size(), result)};
        }), SQLDialect.POSTGRES);
        var repository = new JooqDeviceInventoryRepository(new TransactionBoundary() {
            @Override public <T> T inTransaction(Function<DSLContext, T> work) { return work.apply(dsl); }
        });

        assertEquals(List.of(), repository.findLatestRunStamps(List.of()));
        assertTrue(statements.isEmpty());
        assertEquals(List.of(new DeviceInventoryRepository.RunStamp("dev-a", "run-1", collected)),
                repository.findLatestRunStamps(List.of("dev-a", "dev-b")));
        assertEquals(1, statements.size());
        String sql = statements.get(0);
        assertTrue(sql.contains("from device_inventory_run where device_id in (?, ?)"), sql);
        assertTrue(!sql.contains("device_interface") && !sql.contains("device_route"));
    }
}
