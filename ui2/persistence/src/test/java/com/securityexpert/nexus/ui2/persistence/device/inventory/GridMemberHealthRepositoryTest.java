package com.securityexpert.nexus.ui2.persistence.device.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

/**
 * V271 (PO 2026-11-24): a Management Center's managed-device health round-trips through {@code grid_member} -- written with
 * the member in the same audited transaction, read back as the same closed state, counts, check time and texts; a member
 * recorded without a health read (NULL) reads back without one, never as OK.
 */
class GridMemberHealthRepositoryTest {

    private static GridMember member(String id, String name, GridMember.Health health) {
        GridMember base = new GridMember(id, name, Optional.of("192.0.2.15"), Optional.of("Reporter"), Optional.of("Reporter VA"),
                Optional.of("11.1.1"), false, false, false, Optional.empty(), Optional.of("FULLY_MANAGED"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(new GridMember.ServiceStatus("DEPLOYMENT", "DEPLOYED")));
        return health == null ? base : base.withHealth(health);
    }

    @Test
    void recordRunWritesTheHealthColumnsAndLeavesThemNullWithoutAHealthRead() {
        List<String> sql = new ArrayList<>();
        List<Object[]> bindings = new ArrayList<>();
        DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            if (ctx.sql().contains("insert into grid_member(")) {
                sql.add(ctx.sql());
                bindings.add(ctx.bindings());
            }
            return new MockResult[] { new MockResult(1, null) };
        }), SQLDialect.POSTGRES);
        var repository = new JooqDeviceInventoryRepository(new JooqTransactionBoundary(dsl));
        var withHealth = member("m-1", "MD-BRAVO-02", new GridMember.Health("ERROR", 1, 2, Optional.of("2026-11-24T10:12:23"),
                List.of("Error: Unknown error", "Warning: Disk high")));
        var unknown = member("m-2", "MD-CHARLIE-03", GridMember.Health.unknown("GATE_NOT_APPROVED"));
        var none = member("m-3", "MD-DELTA-04", null);
        repository.recordRun(new InventoryRun("run-1", "device-1", "job-1", Instant.parse("2026-11-24T10:00:00Z"), 0, List.of(), List.of(),
                Optional.empty(), List.of(withHealth, unknown, none), null), "system:worker", "inventory_collect_completed");

        assertEquals(3, sql.size());
        assertTrue(sql.get(0).contains("health_state") && sql.get(0).contains("health_errors") && sql.get(0).contains("health_warnings")
                && sql.get(0).contains("health_checked_at") && sql.get(0).contains("health_messages"), sql.get(0));
        // the last five binds of each insert are the health columns
        List<Object> first = Arrays.asList(bindings.get(0));
        assertEquals(Arrays.asList("ERROR", 1, 2, "2026-11-24T10:12:23", "Error: Unknown error\nWarning: Disk high"), first.subList(first.size() - 5, first.size()));
        List<Object> second = Arrays.asList(bindings.get(1));
        assertEquals(Arrays.asList("UNKNOWN", 0, 0, null, "GATE_NOT_APPROVED"), second.subList(second.size() - 5, second.size()));
        List<Object> third = Arrays.asList(bindings.get(2));
        assertEquals(Arrays.asList(null, null, null, null, null), third.subList(third.size() - 5, third.size()));
    }

    @Test
    void findLatestRunReadsTheHealthBackAndANullHealthStaysAbsent() {
        DSLContext create = DSL.using(SQLDialect.POSTGRES);
        Result<Record> run = create.fetchFromStringData(new String[] { "run_id", "device_id", "job_id", "collected_at", "context_count" },
                new String[] { "run-1", "device-1", "job-1", "2026-11-24 10:00:00", "0" });
        String[] columns = { "member_id", "host_name", "vip_address", "platform", "hardware_type", "hypervisor", "grid_master", "master_candidate",
                "ha_enabled", "ha_status", "node_status", "replication", "disk_percent", "memory_percent", "cpu_percent", "db_percent", "services",
                "health_state", "health_errors", "health_warnings", "health_checked_at", "health_messages" };
        Result<Record> members = create.fetchFromStringData(columns,
                new String[] { "m-1", "MD-BRAVO-02", "192.0.2.15", "Reporter", "Reporter VA", "11.1.1", "false", "false", "false", null, "FULLY_MANAGED",
                        null, null, null, null, null, "DEPLOYMENT=DEPLOYED", "ERROR", "1", "2", "2026-11-24T10:12:23", "Error: Unknown error\nWarning: Disk high" },
                new String[] { "m-2", "MD-CHARLIE-03", null, null, null, null, "false", "false", "false", null, null, null, null, null, null, null, "",
                        "UNKNOWN", "0", "0", null, "NOT_IN_STATUS_ANSWER" },
                new String[] { "m-3", "MD-DELTA-04", null, null, null, null, "false", "false", "false", null, null, null, null, null, null, null, "",
                        null, null, null, null, null },
                new String[] { "m-4", "MD-ECHO-05", null, null, null, null, "false", "false", "false", null, null, null, null, null, null, null, "",
                        "GREEN", "0", "0", null, null });
        DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            String q = ctx.sql();
            Result<Record> result = q.contains("from device_inventory_run") ? run : q.contains("from grid_member") ? members
                    : create.fetchFromStringData(new String[] { "x" });
            return new MockResult[] { new MockResult(result.size(), result) };
        }), SQLDialect.POSTGRES);
        var found = new JooqDeviceInventoryRepository(new JooqTransactionBoundary(dsl)).findLatestRun("device-1").orElseThrow();

        var byName = new java.util.HashMap<String, GridMember>();
        found.gridMembers().forEach(m -> byName.put(m.hostName(), m));
        var bravo = byName.get("MD-BRAVO-02").health().orElseThrow();
        assertEquals("ERROR", bravo.state());
        assertEquals(1, bravo.errors());
        assertEquals(2, bravo.warnings());
        assertEquals(Optional.of("2026-11-24T10:12:23"), bravo.checkedAt());
        assertEquals(List.of("Error: Unknown error", "Warning: Disk high"), bravo.messages());
        var charlie = byName.get("MD-CHARLIE-03").health().orElseThrow();
        assertEquals("UNKNOWN", charlie.state());
        assertEquals(List.of("NOT_IN_STATUS_ANSWER"), charlie.messages());
        assertEquals(Optional.empty(), byName.get("MD-DELTA-04").health(), "NULL health_state: no health was read");
        assertEquals(Optional.empty(), byName.get("MD-ECHO-05").health(), "a value outside the closed states is never shown as a state");
    }

    @Test
    void messagesPackOnePerLineAndNeverBreakTheLine() {
        assertEquals(null, JooqDeviceInventoryRepository.packMessages(List.of()));
        assertEquals("a b\nc", JooqDeviceInventoryRepository.packMessages(List.of("a\r\nb", "c")));
        assertEquals(List.of("a", "b"), JooqDeviceInventoryRepository.unpackMessages("a\n\n b \n"));
        assertEquals(List.of(), JooqDeviceInventoryRepository.unpackMessages(null));
    }

    @Test
    void healthRejectsAStateOutsideTheClosedSet() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new GridMember.Health("GREEN", 0, 0, Optional.empty(), List.of()));
        assertEquals(0, new GridMember.Health("OK", -4, -1, null, null).errors());
    }
}
