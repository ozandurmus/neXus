package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/** Rebuild bounds and exclusivity, and the SQL the V186 repository sends (no live database, jOOQ mock). */
class TopologyServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T07:00:00Z"), ZoneOffset.UTC);

    private static DeviceSummaryRecord gateway(String id, String vendor) {
        return new DeviceSummaryRecord(id, "gateway", vendor, DeviceEnrollmentState.ENROLLED, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    void anOversizedEstateIsRefusedBeforeAnythingIsBuiltOrStored() {
        var devices = mock(DeviceRepository.class);
        List<DeviceSummaryRecord> many = new ArrayList<>();
        for (int i = 0; i <= TopologyService.MAX_DEVICES; i++) {
            many.add(gateway("dev-" + i, TopologyInput.VENDOR_CHECK_POINT));
        }
        when(devices.listAll()).thenReturn(many);
        var inventory = mock(DeviceInventoryRepository.class);
        var repository = new InMemoryTopologyRepository();
        var service = new TopologyService(devices, inventory, repository, CLOCK);
        var refused = assertThrows(TopologyService.Refusal.class, () -> service.rebuild("synthetic-actor"));
        assertEquals("TOPOLOGY_INPUT_TOO_LARGE", refused.code());
        verifyNoInteractions(inventory);
        assertTrue(repository.snapshots.isEmpty());
        assertTrue(service.current().isEmpty());
    }

    @Test
    void managementServersDraftsAndOtherVendorsAreNotPartOfTheTopology() {
        var devices = mock(DeviceRepository.class);
        var draft = new DeviceSummaryRecord("dev-d", "gateway", TopologyInput.VENDOR_PALO_ALTO, DeviceEnrollmentState.DRAFT,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var manager = new DeviceSummaryRecord("dev-m", "management_server", TopologyInput.VENDOR_CHECK_POINT,
                DeviceEnrollmentState.ENROLLED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        when(devices.listAll()).thenReturn(List.of(draft, manager, gateway("dev-f", "fortinet"),
                gateway("dev-g", TopologyInput.VENDOR_CHECK_POINT)));
        var inventory = mock(DeviceInventoryRepository.class);
        when(inventory.findLatestRuns(anyList())).thenReturn(List.of());
        var service = new TopologyService(devices, inventory, new InMemoryTopologyRepository(), CLOCK);
        var rebuilt = service.rebuild("synthetic-actor");
        assertEquals(1, rebuilt.graph().counts().devices());
        assertEquals(1, rebuilt.graph().counts().devicesWithoutInventory());
        verify(inventory).findLatestRuns(List.of("dev-g"));
    }

    @Test
    void oneRebuildAtATime() throws Exception {
        var devices = mock(DeviceRepository.class);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(devices.listAll()).thenAnswer(call -> {
            inside.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return List.of();
        });
        var inventory = mock(DeviceInventoryRepository.class);
        when(inventory.findLatestRuns(anyList())).thenReturn(List.of());
        var service = new TopologyService(devices, inventory, new InMemoryTopologyRepository(), CLOCK);
        AtomicReference<Throwable> first = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                service.rebuild("synthetic-actor");
            } catch (Throwable e) {
                first.set(e);
            }
        });
        t.start();
        assertTrue(inside.await(10, TimeUnit.SECONDS));
        var refused = assertThrows(TopologyService.Refusal.class, () -> service.rebuild("synthetic-actor"));
        assertEquals("TOPOLOGY_REBUILD_IN_PROGRESS", refused.code());
        assertEquals(409, refused.status());
        release.countDown();
        t.join(10_000);
        assertNull(first.get());
        assertTrue(service.current().isPresent());
    }

    @Test
    void theRepositoryAuditsOverrideWritesAndKeepsSnapshotWritesBoundedAndUnaudited() {
        List<String> sql = new ArrayList<>();
        var repository = new JooqTopologyRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(ctx -> {
            sql.add(ctx.sql());
            return new MockResult[] {new MockResult(1, null)};
        }), SQLDialect.POSTGRES)));

        var override = TopologyFixtures.overrides(TopologyFixtures.resource("overrides.json")).get(0);
        repository.insertOverride(override, "synthetic-actor", ActionRegistry.TOPOLOGY_OVERRIDE_WRITE);
        assertTrue(sql.get(0).startsWith("SET LOCAL app.actor_fingerprint"), sql.get(0));
        assertTrue(sql.get(1).contains("app.action_id = 'topology_override_write'"), sql.get(1));
        assertTrue(sql.get(2).startsWith("insert into topology_link_override"), sql.get(2));

        sql.clear();
        assertTrue(repository.revokeOverride(override.overrideId(), "synthetic-actor",
                ActionRegistry.TOPOLOGY_OVERRIDE_WRITE, CLOCK.instant()));
        assertTrue(sql.get(2).startsWith("update topology_link_override set revoked_at"), sql.get(2));
        assertTrue(sql.stream().noneMatch(s -> s.startsWith("delete from topology_link_override")), "never deleted");

        sql.clear();
        var graph = TopologyBuilder.build(TopologyFixtures.input(
                TopologyFixtures.devices(TopologyFixtures.resource("estate.json")), List.of()));
        var header = new TopologyRepository.SnapshotHeader(graph.snapshotId(), graph.builtAt(), "synthetic-actor",
                graph.fingerprints(), graph.counts(), 0, 1);
        assertEquals(2, repository.recordSnapshot(header, graph.conflicts(), 2, 20));
        assertTrue(sql.get(0).startsWith("insert into topology_snapshot"), sql.get(0));
        assertEquals(2, sql.stream().filter(s -> s.startsWith("insert into topology_conflict")).count(),
                "conflicts stored up to the limit; the header keeps the full count");
        assertTrue(sql.get(sql.size() - 1).startsWith("delete from topology_snapshot"), "older snapshots pruned");
        assertTrue(sql.stream().noneMatch(s -> s.startsWith("SET LOCAL")), "derived rows: no audit context");
        assertTrue(sql.stream().noneMatch(s -> s.contains("topology_node") || s.contains("topology_segment")
                || s.contains("topology_attachment")), "D-1: the graph itself is never written");
    }
}
