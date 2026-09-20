package com.securityexpert.nexus.ui2.persistence.device.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * AC-1: {@link JooqDeviceInventoryRepository} round-trips a run in one
 * transaction, proved without a live PostgreSQL instance (the same jOOQ
 * {@code MockDataProvider} pattern {@code AuditedTransactionBoundaryTest}
 * already establishes).
 */
class JooqDeviceInventoryRepositoryTest {

    private static InventoryRun sampleRun() {
        InventoryAddress memberAddress = new InventoryAddress("addr-1", "192.0.2.10/24", InventoryAddress.FAMILY_IPV4,
                InventoryAddress.ROLE_MEMBER);
        InventoryAddress vip = new InventoryAddress("addr-2", "192.0.2.1/24", InventoryAddress.FAMILY_IPV4,
                InventoryAddress.ROLE_CLUSTER_VIRTUAL);
        InventoryInterface eth0 = new InventoryInterface("iface-1", "eth0", Optional.empty(),
                InventoryInterface.KIND_PHYSICAL, InventoryInterface.STATE_UP, List.of(memberAddress, vip),
                Optional.of(100));
        InventoryRoute route = new InventoryRoute("route-1", "0.0.0.0/0", Optional.of("192.0.2.254"),
                Optional.of("eth0"), InventoryRoute.PROTOCOL_DEFAULT, Optional.empty());
        InventoryContext physical = new InventoryContext(InventoryContext.PHYSICAL, List.of(eth0), List.of(route));
        InventoryHaFact haFact = new InventoryHaFact("ha-1", InventoryContext.PHYSICAL, "ACTIVE",
                Optional.of("High Availability"), InventoryHaFact.SOURCE_CP_CPHAPROB_STAT);
        return new InventoryRun("run-1", "device-1", "job-1", Instant.parse("2026-09-14T00:00:00Z"), 1,
                List.of(physical), List.of(haFact));
    }

    @Test
    void recordRunInsertsTheRunAndEveryChildRowInOneAuditedTransaction() {
        List<String> executedSql = new ArrayList<>();
        MockDataProvider provider = ctx -> {
            executedSql.add(ctx.sql());
            return new MockResult[] { new MockResult(1, null) };
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        TransactionBoundary transactionBoundary = new JooqTransactionBoundary(dsl);
        JooqDeviceInventoryRepository repository = new JooqDeviceInventoryRepository(transactionBoundary);

        repository.recordRun(sampleRun(), "system:worker", "inventory_collect_completed");

        assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("insert into device_inventory_run")));
        assertEquals(1, executedSql.stream().filter(sql -> sql.contains("insert into device_interface(")).count());
        assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("insert into device_interface(") && sql.contains("vlan_id")),
                "AC-4: the VLAN id column is written");
        assertEquals(2, executedSql.stream().filter(sql -> sql.contains("insert into device_interface_address")).count());
        assertEquals(1, executedSql.stream().filter(sql -> sql.contains("insert into device_route")).count());
        assertEquals(1, executedSql.stream().filter(sql -> sql.contains("insert into device_inventory_ha")).count(),
                "AC-4: the HA fact is written");
        assertTrue(executedSql.get(0).contains("SET LOCAL app.actor_fingerprint"),
                "the audit context is set before any child row is written");
    }

    @Test
    void findLatestRunReassemblesContextsInterfacesAddressesAndRoutes() {
        DSLContext create = DSL.using(SQLDialect.POSTGRES);

        Result<Record> runResult = create.fetchFromStringData(
                new String[] { "run_id", "device_id", "job_id", "collected_at", "context_count", "virtual_systems" },
                new String[] { "run-1", "device-1", "job-1", "2026-09-14 00:00:00", "1", "vs-test (VSID 1)" });

        Result<Record> addressResult = create.fetchFromStringData(
                new String[] { "address_id", "interface_id", "address", "family", "role" },
                new String[] { "addr-1", "iface-1", "192.0.2.10/24", "ipv4", "member" },
                new String[] { "addr-2", "iface-1", "192.0.2.1/24", "ipv4", "cluster_virtual" });

        Result<Record> interfaceResult = create.fetchFromStringData(
                new String[] { "interface_id", "context", "name", "parent", "kind", "state", "vlan_id" },
                new String[] { "iface-1", "physical", "eth0", null, "physical", "up", "100" });

        Result<Record> routeResult = create.fetchFromStringData(
                new String[] { "route_id", "context", "destination", "next_hop", "interface", "protocol",
                        "route_table" },
                new String[] { "route-1", "physical", "0.0.0.0/0", "192.0.2.254", "eth0", "default", null });

        Result<Record> haResult = create.fetchFromStringData(
                new String[] { "ha_id", "context", "role", "cluster_mode", "source" },
                new String[] { "ha-1", "physical", "ACTIVE", "High Availability", "cp_cphaprob_stat" });

        List<Result<Record>> queue =
                new ArrayList<>(List.of(runResult, addressResult, interfaceResult, routeResult, haResult));
        MockDataProvider provider = ctx -> {
            Result<Record> next = queue.remove(0);
            return new MockResult[] { new MockResult(next.size(), next) };
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        TransactionBoundary transactionBoundary = new JooqTransactionBoundary(dsl);
        JooqDeviceInventoryRepository repository = new JooqDeviceInventoryRepository(transactionBoundary);

        Optional<InventoryRun> found = repository.findLatestRun("device-1");

        assertTrue(found.isPresent());
        InventoryRun run = found.get();
        assertEquals("run-1", run.runId());
        assertEquals(Optional.of("vs-test (VSID 1)"), run.virtualSystems());
        assertEquals(1, run.contexts().size());
        InventoryContext context = run.contexts().get(0);
        assertEquals("physical", context.context());
        assertEquals(1, context.interfaces().size());
        assertEquals(2, context.interfaces().get(0).addresses().size());
        assertEquals(Optional.of(100), context.interfaces().get(0).vlanId(), "AC-4: the VLAN id round-trips");
        assertEquals(1, context.routes().size());
        assertEquals("default", context.routes().get(0).protocol());
        assertEquals(1, run.haFacts().size(), "AC-4: the HA fact round-trips");
        InventoryHaFact haFact = run.haFacts().get(0);
        assertEquals("physical", haFact.context());
        assertEquals("ACTIVE", haFact.role());
        assertEquals(Optional.of("High Availability"), haFact.clusterMode());
        assertEquals(InventoryHaFact.SOURCE_CP_CPHAPROB_STAT, haFact.source());
    }

    @Test
    void findLatestRunReturnsEmptyWhenNoRunWasEverRecorded() {
        DSLContext create = DSL.using(SQLDialect.POSTGRES);
        Result<Record> emptyRunResult = create.fetchFromStringData(
                new String[] { "run_id", "device_id", "job_id", "collected_at", "context_count" });
        MockDataProvider provider = ctx -> new MockResult[] { new MockResult(0, emptyRunResult) };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        JooqDeviceInventoryRepository repository =
                new JooqDeviceInventoryRepository(new JooqTransactionBoundary(dsl));

        assertTrue(repository.findLatestRun("device-none").isEmpty());
    }
}
