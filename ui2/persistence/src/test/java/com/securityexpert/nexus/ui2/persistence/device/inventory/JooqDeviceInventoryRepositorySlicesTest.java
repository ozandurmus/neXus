package com.securityexpert.nexus.ui2.persistence.device.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** The failover unit lookup reads a run's existence and HA facts without reassembling its interfaces and routes. */
class JooqDeviceInventoryRepositorySlicesTest {
    private final List<String> statements = new ArrayList<>();

    private JooqDeviceInventoryRepository repository(boolean present) {
        var create = DSL.using(SQLDialect.POSTGRES);
        DSLContext dsl = DSL.using(new MockConnection(query -> {
            String sql = query.sql().replaceAll("\\s+", " ");
            statements.add(sql);
            if (sql.contains("as present")) {
                var result = create.newResult(DSL.field("present", Boolean.class));
                result.add(create.newRecord(DSL.field("present", Boolean.class)).values(present));
                return new MockResult[] {new MockResult(1, result)};
            }
            var haId = DSL.field("ha_id", String.class);
            var context = DSL.field("context", String.class);
            var role = DSL.field("role", String.class);
            var mode = DSL.field("cluster_mode", String.class);
            var source = DSL.field("source", String.class);
            var result = create.newResult(haId, context, role, mode, source);
            if (present) {
                result.add(create.newRecord(haId, context, role, mode, source).values("ha-1", "physical", "ACTIVE",
                    "Active-Passive", InventoryHaFact.SOURCE_PAN_HIGH_AVAILABILITY_STATE));
                result.add(create.newRecord(haId, context, role, mode, source).values("ha-2", "13", "STANDBY",
                    null, InventoryHaFact.SOURCE_CP_CPHAPROB_STAT));
            }
            return new MockResult[] {new MockResult(result.size(), result)};
        }), SQLDialect.POSTGRES);
        return new JooqDeviceInventoryRepository(new TransactionBoundary() {
            @Override public <T> T inTransaction(Function<DSLContext, T> work) { return work.apply(dsl); }
        });
    }

    @Test
    void hasRunTouchesOnlyTheRunTable() {
        assertTrue(repository(true).hasRun("device-a"));
        assertFalse(repository(false).hasRun("device-a"));
        assertTrue(statements.stream().allMatch(sql -> sql.contains("from device_inventory_run where device_id = ?")
            && !sql.contains("device_interface") && !sql.contains("device_route")));
    }

    @Test
    void haFactsComeFromTheNewestRunOnlyAndKeepEveryField() {
        var facts = repository(true).findLatestHaFacts("device-a");
        assertEquals(List.of(
            new InventoryHaFact("ha-1", "physical", "ACTIVE", Optional.of("Active-Passive"),
                InventoryHaFact.SOURCE_PAN_HIGH_AVAILABILITY_STATE),
            new InventoryHaFact("ha-2", "13", "STANDBY", Optional.empty(), InventoryHaFact.SOURCE_CP_CPHAPROB_STAT)), facts);
        String sql = statements.get(0);
        assertTrue(sql.contains("order by collected_at desc limit 1"));
        assertFalse(sql.contains("device_interface") || sql.contains("device_route"));
        assertEquals(List.of(), repository(false).findLatestHaFacts("device-a"));
    }
}
