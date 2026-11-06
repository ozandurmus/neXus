package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.ConflictKind;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.NodeFlag;

/** Stage 2 of the VSX VIP prefix decision (2026-11-06, amended 2026-11-07): strict whole-set binding, synthetic values. */
class TopologyManagementVsPrefixTest {

    private static final Instant AT = Instant.parse("2026-11-07T06:00:00Z");

    private static InventoryAddress vip(String address) {
        return new InventoryAddress("a-" + address, address, InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_CLUSTER_VIRTUAL);
    }

    private static InventoryInterface iface(String name, InventoryAddress... addresses) {
        return new InventoryInterface("if-" + name, name, Optional.empty(), "physical", "up", List.of(addresses));
    }

    private static InventoryRoute connected(String destination, String iface) {
        return new InventoryRoute("r-" + destination, destination, Optional.empty(), Optional.of(iface), "connected",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("CONNECTED"));
    }

    private static TopologyInput.DeviceFacts unit(List<InventoryInterface> interfaces, List<InventoryRoute> routes) {
        var run = new InventoryRun("run-x", "dev-x", "job-x", AT, 1, List.of(new InventoryContext("2", interfaces, routes)),
                List.of(new InventoryHaFact("ha", "2", "ACTIVE", Optional.empty(), "cp_cphaprob_stat")));
        return new TopologyInput.DeviceFacts("dev-x", TopologyInput.VENDOR_CHECK_POINT, Optional.of("FW-VSX-01"),
                Optional.of("cls-ref"), Optional.of(run));
    }

    private static TopologyInput.ManagementVs mgmt(String uid, String... addresses) {
        return new TopologyInput.ManagementVs(uid, Set.of(addresses));
    }

    private static TopologyGraph build(TopologyInput.DeviceFacts device, TopologyInput.ManagementVs... objects) {
        return TopologyBuilder.build(new TopologyInput("tps-mgmt-vs", AT, List.of(device), List.of(),
                "synthetic-topology-golden-salt-0001".getBytes(StandardCharsets.UTF_8), List.of(objects)));
    }

    private static TopologyInput.DeviceFacts twoVips() {
        return unit(List.of(iface("eth1", vip("192.0.2.1")), iface("eth2", vip("198.51.100.1"))), List.of());
    }

    private static boolean unknown(TopologyGraph g) {
        return g.conflicts().stream().anyMatch(c -> c.kind() == ConflictKind.ADDRESS_PREFIX_UNKNOWN);
    }

    @Test
    void anExactUniqueSetBindsWithTheManagementGradeAndIsNeverCorroborated() {
        TopologyGraph g = build(twoVips(), mgmt("uid-one", "192.0.2.1/26", "198.51.100.1/24"));
        assertFalse(unknown(g));
        assertEquals(2, g.attachments().size());
        for (var a : g.attachments()) {
            assertEquals(Optional.of(InventoryInterface.GRADE_MANAGEMENT_DERIVED), a.evidenceGrade());
            assertFalse(a.corroborated());
        }
        assertEquals(Set.of(Optional.of("192.0.2.1/26"), Optional.of("198.51.100.1/24")),
                Set.copyOf(g.attachments().stream().map(TopologyGraph.Attachment::address).toList()));
        assertTrue(g.nodes().get(0).flags().contains(NodeFlag.MANAGEMENT_DERIVED_PREFIX));
    }

    @Test
    void twoObjectsWithTheSameSetStayUnknown() {
        TopologyGraph g = build(twoVips(), mgmt("uid-one", "192.0.2.1/26", "198.51.100.1/24"),
                mgmt("uid-two", "192.0.2.1/26", "198.51.100.1/24"));
        assertTrue(unknown(g));
        assertEquals(0, g.attachments().size());
    }

    @Test
    void aSubsetOrASupersetNeverBinds() {
        assertTrue(unknown(build(twoVips(), mgmt("uid-sub", "192.0.2.1/26"))));
        assertTrue(unknown(build(twoVips(), mgmt("uid-sup", "192.0.2.1/26", "198.51.100.1/24", "203.0.113.1/24"))));
    }

    @Test
    void noObjectsOrNoRecordedVipsChangeNothing() {
        assertTrue(unknown(build(twoVips())));
        var noVip = unit(List.of(iface("eth1", new InventoryAddress("a", "192.0.2.9/26", InventoryAddress.FAMILY_IPV4,
                InventoryAddress.ROLE_MEMBER))), List.of());
        assertFalse(unknown(build(noVip, mgmt("uid-one", "192.0.2.9/26"))));
        assertEquals(0, build(noVip, mgmt("uid-one", "192.0.2.9/26")).attachments().size(), "member addresses never attach a cluster");
    }

    @Test
    void aRuntimeMaskOnTheAddressWins() {
        var device = unit(List.of(iface("eth1", vip("192.0.2.1/27"), vip("198.51.100.1"))), List.of());
        TopologyGraph g = build(device, mgmt("uid-one", "192.0.2.1/26", "198.51.100.1/24"));
        var byAddress = g.attachments().stream().collect(java.util.stream.Collectors.toMap(
                a -> a.address().orElseThrow(), a -> a.evidenceGrade()));
        assertEquals(Optional.empty(), byAddress.get("192.0.2.1/27"), "runtime prefix keeps the runtime grade");
        assertEquals(Optional.of(InventoryInterface.GRADE_MANAGEMENT_DERIVED), byAddress.get("198.51.100.1/24"));
    }

    @Test
    void aSingleConnectedRouteContainingTheAddressWins() {
        var device = unit(List.of(iface("eth1", vip("192.0.2.1"))), List.of(connected("192.0.2.0/28", "eth1")));
        TopologyGraph g = build(device, mgmt("uid-one", "192.0.2.1/26"));
        assertEquals(Optional.of("192.0.2.1/28"), g.attachments().get(0).address());
        assertEquals(Optional.empty(), g.attachments().get(0).evidenceGrade());
        assertFalse(g.nodes().get(0).flags().contains(NodeFlag.MANAGEMENT_DERIVED_PREFIX));
    }

    @Test
    void anObjectWithTwoMasksForOneAddressNeverBinds() {
        var device = unit(List.of(iface("eth1", vip("192.0.2.1"))), List.of());
        assertTrue(unknown(build(device, mgmt("uid-one", "192.0.2.1/26", "192.0.2.1/27"))));
    }

    @Test
    void theLoaderParsesOnlyResolvedCompleteVirtualSystemObjects() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String doc = "{\"status\":\"RESOLVED\",\"objects\":["
                + "{\"uid\":\"u1\",\"type\":\"CpmiVsClusterNetobj\",\"vs\":{\"complete\":true,\"interfaces\":[{\"address\":\"192.0.2.1\",\"maskLength\":26}]}},"
                + "{\"uid\":\"u2\",\"type\":\"CpmiVsNetobj\",\"vs\":{\"complete\":false,\"interfaces\":[{\"address\":\"192.0.2.2\",\"maskLength\":26}]}},"
                + "{\"uid\":\"u3\",\"type\":\"simple-gateway\",\"vs\":{\"complete\":true,\"interfaces\":[{\"address\":\"192.0.2.3\",\"maskLength\":26}]}},"
                + "{\"uid\":\"u4\",\"type\":\"CpmiVsNetobj\"}]}";
        var parsed = PolicyManagementVsSource.parse(mapper.readTree(doc));
        assertEquals(List.of(new TopologyInput.ManagementVs("u1", Set.of("192.0.2.1/26"))), parsed);
        assertTrue(PolicyManagementVsSource.parse(mapper.readTree(doc.replace("RESOLVED", "TYPE_INCOMPLETE"))).isEmpty());
    }

    private static final String DOC = "{\"status\":\"RESOLVED\",\"objects\":["
            + "{\"uid\":\"u1\",\"type\":\"CpmiVsNetobj\",\"vs\":{\"complete\":true,\"interfaces\":[{\"address\":\"192.0.2.1\",\"maskLength\":26}]}}]}";

    private static PolicyManagementVsSource source(java.util.List<com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository.InventoryRef> refs,
            Set<String> proven, java.util.concurrent.atomic.AtomicReference<Instant> asked) {
        var repository = org.mockito.Mockito.mock(com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository.class);
        org.mockito.Mockito.when(repository.resolvedInventories(org.mockito.ArgumentMatchers.eq("gateways-and-servers"),
                org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
                    Instant notBefore = call.getArgument(1);
                    asked.set(notBefore);
                    return refs.stream().filter(r -> !r.collectedAt().isBefore(notBefore)).toList();
                });
        org.mockito.Mockito.doAnswer(call -> {
            java.util.function.Consumer<com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository.InventoryDocument> c = call.getArgument(3);
            c.accept(new com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository.InventoryDocument(
                    "gateways-and-servers", AT, new java.io.StringReader(DOC)));
            return null;
        }).when(repository).readInventory(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        return new PolicyManagementVsSource(repository, new com.fasterxml.jackson.databind.ObjectMapper(), () -> proven);
    }

    @Test
    void aFreshInventoryAndADiscoveryProvenUidLoadButAnUnprovenUidOrAStaleInventoryDoNot() {
        var fresh = new com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository.InventoryRef("s", "d", AT.minusSeconds(3600));
        var stale = new com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository.InventoryRef("s", "d", AT.minusSeconds(49 * 3600));
        var asked = new java.util.concurrent.atomic.AtomicReference<Instant>();
        assertEquals(List.of(new TopologyInput.ManagementVs("u1", Set.of("192.0.2.1/26"))),
                source(List.of(fresh), Set.of("u1"), asked).load(AT));
        assertEquals(AT.minus(PolicyManagementVsSource.MAX_AGE), asked.get());
        assertTrue(source(List.of(fresh), Set.of("other"), asked).load(AT).isEmpty(), "UID not in discovery");
        assertTrue(source(List.of(stale), Set.of("u1"), asked).load(AT).isEmpty(), "older than 48 h");
    }
}
