package com.securityexpert.nexus.ui2.service.device.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;

/** WORKER.md "Service read model": "Unit tests for the merger: identical members, one member missing a route, differing interface state, VIP appears once, two contexts." */
class ClusterInventoryMergerTest {

    private static InventoryAddress memberAddress(String address) {
        return new InventoryAddress("addr-" + address, address, InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER);
    }

    private static InventoryAddress vip(String address) {
        return new InventoryAddress("addr-" + address, address, InventoryAddress.FAMILY_IPV4,
                InventoryAddress.ROLE_CLUSTER_VIRTUAL);
    }

    private static InventoryRun run(String deviceId, InventoryContext... contexts) {
        return new InventoryRun("run-" + deviceId, deviceId, "job-" + deviceId, Instant.parse("2026-09-14T00:00:00Z"),
                contexts.length, List.of(contexts));
    }

    @Test
    void identicalMembersMergeToOneRowWithAllPresenceAndNoDifferences() {
        InventoryInterface eth0A = new InventoryInterface("if-a-eth0", "eth0", Optional.empty(),
                InventoryInterface.KIND_PHYSICAL, InventoryInterface.STATE_UP, List.of(memberAddress("192.0.2.1/24")));
        InventoryInterface eth0B = new InventoryInterface("if-b-eth0", "eth0", Optional.empty(),
                InventoryInterface.KIND_PHYSICAL, InventoryInterface.STATE_UP, List.of(memberAddress("192.0.2.2/24")));
        InventoryContext contextA = new InventoryContext(InventoryContext.PHYSICAL, List.of(eth0A), List.of());
        InventoryContext contextB = new InventoryContext(InventoryContext.PHYSICAL, List.of(eth0B), List.of());

        Map<String, InventoryRun> runs = new LinkedHashMap<>();
        runs.put("dev-a", run("dev-a", contextA));
        runs.put("dev-b", run("dev-b", contextB));

        List<ClusterInventoryMerger.MergedContext> merged = ClusterInventoryMerger.merge(List.of("dev-a", "dev-b"), runs);

        assertEquals(1, merged.size());
        ClusterInventoryMerger.MergedInterface iface = merged.get(0).interfaces().get(0);
        assertEquals("eth0", iface.name());
        assertEquals(InventoryInterface.KIND_PHYSICAL, iface.kind());
        assertTrue(iface.presence() instanceof ClusterInventoryMerger.Presence.All);
        assertTrue(iface.differences().isEmpty(), "kind and state agree, no VIP to compare -- no differences expected");
        // Member-role addresses are never copied onto the cluster row.
        assertTrue(iface.addresses().isEmpty());
    }

    @Test
    void aRouteMissingOnOneMemberListsOnlyTheMembersThatHaveIt() {
        InventoryRoute onBoth = new InventoryRoute("route-both", "198.51.100.0/24", Optional.of("198.51.100.1"),
                Optional.of("eth0"), InventoryRoute.PROTOCOL_STATIC, Optional.empty());
        InventoryRoute onlyOnA = new InventoryRoute("route-only-a", "203.0.113.0/24", Optional.empty(),
                Optional.of("eth1"), InventoryRoute.PROTOCOL_CONNECTED, Optional.empty());

        InventoryContext contextA = new InventoryContext(InventoryContext.PHYSICAL, List.of(), List.of(onBoth, onlyOnA));
        InventoryContext contextB = new InventoryContext(InventoryContext.PHYSICAL, List.of(), List.of(onBoth));

        Map<String, InventoryRun> runs = new LinkedHashMap<>();
        runs.put("dev-a", run("dev-a", contextA));
        runs.put("dev-b", run("dev-b", contextB));

        List<ClusterInventoryMerger.MergedContext> merged = ClusterInventoryMerger.merge(List.of("dev-a", "dev-b"), runs);

        List<ClusterInventoryMerger.MergedRoute> routes = merged.get(0).routes();
        assertEquals(2, routes.size());
        ClusterInventoryMerger.MergedRoute both = routes.stream().filter(r -> r.destination().equals("198.51.100.0/24"))
                .findFirst().orElseThrow();
        assertTrue(both.presence() instanceof ClusterInventoryMerger.Presence.All);
        ClusterInventoryMerger.MergedRoute onlyA = routes.stream().filter(r -> r.destination().equals("203.0.113.0/24"))
                .findFirst().orElseThrow();
        assertEquals(new ClusterInventoryMerger.Presence.Members(List.of("dev-a")), onlyA.presence());
    }

    @Test
    void aDifferingInterfaceStateIsReportedAsADifferenceAgainstTheFirstMembersBaseline() {
        InventoryInterface up = new InventoryInterface("if-a-eth0", "eth0", Optional.empty(),
                InventoryInterface.KIND_PHYSICAL, InventoryInterface.STATE_UP, List.of());
        InventoryInterface down = new InventoryInterface("if-b-eth0", "eth0", Optional.empty(),
                InventoryInterface.KIND_PHYSICAL, InventoryInterface.STATE_DOWN, List.of());
        InventoryContext contextA = new InventoryContext(InventoryContext.PHYSICAL, List.of(up), List.of());
        InventoryContext contextB = new InventoryContext(InventoryContext.PHYSICAL, List.of(down), List.of());

        Map<String, InventoryRun> runs = new LinkedHashMap<>();
        runs.put("dev-a", run("dev-a", contextA));
        runs.put("dev-b", run("dev-b", contextB));

        List<ClusterInventoryMerger.MergedContext> merged = ClusterInventoryMerger.merge(List.of("dev-a", "dev-b"), runs);

        ClusterInventoryMerger.MergedInterface iface = merged.get(0).interfaces().get(0);
        assertEquals(1, iface.differences().size());
        ClusterInventoryMerger.Difference difference = iface.differences().get(0);
        assertEquals("dev-b", difference.deviceId());
        assertEquals("state", difference.field());
        assertEquals(InventoryInterface.STATE_DOWN, difference.value());
    }

    @Test
    void anIdenticalVipReportedByEveryMemberAppearsExactlyOnceOnTheClusterRow() {
        InventoryInterface eth0A = new InventoryInterface("if-a-eth0", "eth0", Optional.empty(),
                InventoryInterface.KIND_PHYSICAL, InventoryInterface.STATE_UP,
                List.of(memberAddress("192.0.2.1/24"), vip("192.0.2.100/24")));
        InventoryInterface eth0B = new InventoryInterface("if-b-eth0", "eth0", Optional.empty(),
                InventoryInterface.KIND_PHYSICAL, InventoryInterface.STATE_UP,
                List.of(memberAddress("192.0.2.2/24"), vip("192.0.2.100/24")));
        InventoryContext contextA = new InventoryContext(InventoryContext.PHYSICAL, List.of(eth0A), List.of());
        InventoryContext contextB = new InventoryContext(InventoryContext.PHYSICAL, List.of(eth0B), List.of());

        Map<String, InventoryRun> runs = new LinkedHashMap<>();
        runs.put("dev-a", run("dev-a", contextA));
        runs.put("dev-b", run("dev-b", contextB));

        List<ClusterInventoryMerger.MergedContext> merged = ClusterInventoryMerger.merge(List.of("dev-a", "dev-b"), runs);

        List<InventoryAddress> addresses = merged.get(0).interfaces().get(0).addresses();
        assertEquals(1, addresses.size(), "the identical VIP must appear exactly once, not once per member");
        assertEquals("192.0.2.100/24", addresses.get(0).address());
        assertEquals(InventoryAddress.ROLE_CLUSTER_VIRTUAL, addresses.get(0).role());
    }

    @Test
    void twoContextsMergeIndependentlyAndAreOrderedDeterministically() {
        InventoryInterface vsys1Iface = new InventoryInterface("if-vsys1-eth0", "eth0", Optional.empty(),
                InventoryInterface.KIND_PHYSICAL, InventoryInterface.STATE_UP, List.of());
        InventoryInterface vsys2Iface = new InventoryInterface("if-vsys2-eth1", "eth1", Optional.empty(),
                InventoryInterface.KIND_PHYSICAL, InventoryInterface.STATE_UP, List.of());
        InventoryContext vsys1A = new InventoryContext("vsys1", List.of(vsys1Iface), List.of());
        InventoryContext vsys2A = new InventoryContext("vsys2", List.of(vsys2Iface), List.of());

        Map<String, InventoryRun> runs = new LinkedHashMap<>();
        runs.put("dev-a", run("dev-a", vsys2A, vsys1A));

        List<ClusterInventoryMerger.MergedContext> merged = ClusterInventoryMerger.merge(List.of("dev-a"), runs);

        assertEquals(2, merged.size());
        assertEquals("vsys1", merged.get(0).context());
        assertEquals("vsys2", merged.get(1).context());
        assertEquals("eth0", merged.get(0).interfaces().get(0).name());
        assertEquals("eth1", merged.get(1).interfaces().get(0).name());
    }
}
