package com.securityexpert.nexus.ui2.service.pathanalysis.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.HopPolicySource;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathGoldenAccess;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathReasons;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyInput;

/**
 * Stage A5a policy binding over stored snapshots (synthetic catalog): context mapping, one snapshot per unit, the
 * staleness rule (24 h, OUT_OF_SYNC, unreadable time), bindings and serials handed to the A2 evaluator, and the prepared
 * view reused while the parse cache hands out the same snapshot.
 */
class PathPolicyBinderTest {

    private static final Instant NOW = Instant.parse("2026-11-04T08:00:00Z");

    private static TopologyGraph.Node node(String vendor, String context, String deviceId) {
        return new TopologyGraph.Node("node-" + deviceId + "-" + context, TopologyGraph.NodeKind.ROUTING_CONTEXT, vendor,
                Optional.empty(), context, Optional.of("main"),
                List.of(new TopologyGraph.Member(deviceId, Optional.of("FW-TANGO-04"), Optional.empty(), Optional.empty(),
                        Optional.empty())),
                Optional.ofNullable(deviceId), Set.of(), List.of());
    }

    private static PolicySnapshot with(PolicySnapshot s, String id, String collectedAt, List<PolicySnapshot.Target> targets) {
        var m = s.metadata();
        return new PolicySnapshot(new PolicySnapshot.Metadata(id, m.sourceId(), m.sourceName(), m.vendor(), m.containerId(),
                m.containerName(), m.name(), collectedAt, m.artefactRef(), targets), s.sections(), s.objects(), s.failures());
    }

    private static HopPolicySource.HopPolicy bind(InMemoryPathPolicyCatalog catalog, TopologyGraph.Node node, Instant now) {
        return new PathPolicyBinder(catalog).forQuery(now).policyFor(node);
    }

    @Test
    void contextsMapPhysicalToNoVirtualSystemAndPanVsysExactlyAndRefuseACheckPointVsid() {
        assertEquals(Optional.of(""), PathPolicyBinder.policyContext(TopologyInput.VENDOR_CHECK_POINT, "physical"));
        assertEquals(Optional.of(""), PathPolicyBinder.policyContext(TopologyInput.VENDOR_PALO_ALTO, "physical"));
        assertEquals(Optional.of("vsys2"), PathPolicyBinder.policyContext(TopologyInput.VENDOR_PALO_ALTO, "vsys2"));
        assertEquals(Optional.empty(), PathPolicyBinder.policyContext(TopologyInput.VENDOR_CHECK_POINT, "2"));
        assertEquals(Optional.empty(), PathPolicyBinder.policyContext(TopologyInput.VENDOR_CHECK_POINT, ""));

        var catalog = new InMemoryPathPolicyCatalog().add(PathGoldenAccess.cpChain("dev-t04"));
        var vs = bind(catalog, node(TopologyInput.VENDOR_CHECK_POINT, "2", "dev-t04"), NOW);
        assertEquals(new HopPolicySource.Unbound(PathReasons.POLICY_CONTEXT_UNPROVEN), vs);
        // A Panorama-style target listed without a vsys is not equated with vsys1 (unmeasured relation).
        var pan = PathGoldenAccess.panHttpsOnlyWithSourceNat("dev-b02");
        var noVsys = new InMemoryPathPolicyCatalog().add(with(pan, pan.metadata().id(), pan.metadata().collectedAt(),
                List.of(new PolicySnapshot.Target("dev-b02", "FW-BRAVO-02", "", "IN_SYNC"))));
        assertEquals(new HopPolicySource.Unbound(PathReasons.POLICY_BINDING_UNPROVEN),
                bind(noVsys, node(TopologyInput.VENDOR_PALO_ALTO, "vsys1", "dev-b02"), NOW));
    }

    @Test
    void aFreshSnapshotNamingTheDeviceIsBoundAndOthersAreNot() {
        var catalog = new InMemoryPathPolicyCatalog().add(PathGoldenAccess.cpChain("dev-t04"))
                .add(PathGoldenAccess.panHttpsOnlyWithSourceNat("dev-b02"));
        var cp = assertInstanceOf(HopPolicySource.Bound.class,
                bind(catalog, node(TopologyInput.VENDOR_CHECK_POINT, "physical", "dev-t04"), NOW));
        assertFalse(cp.stale());
        assertEquals(Optional.of(Instant.parse("2026-11-04T05:00:00Z")), cp.collectedAt());
        var pan = assertInstanceOf(HopPolicySource.Bound.class,
                bind(catalog, node(TopologyInput.VENDOR_PALO_ALTO, "vsys1", "dev-b02"), NOW));
        assertFalse(pan.stale());
        // No snapshot names this device; a vendor mismatch never binds; no active member or a cloud binds nothing.
        assertEquals(new HopPolicySource.Unbound(PathReasons.POLICY_BINDING_UNPROVEN),
                bind(catalog, node(TopologyInput.VENDOR_CHECK_POINT, "physical", "dev-x99"), NOW));
        assertEquals(new HopPolicySource.Unbound(PathReasons.POLICY_BINDING_UNPROVEN),
                bind(catalog, node(TopologyInput.VENDOR_PALO_ALTO, "physical", "dev-t04"), NOW));
        assertEquals(new HopPolicySource.Unbound(PathReasons.POLICY_BINDING_UNPROVEN),
                bind(catalog, node(TopologyInput.VENDOR_CHECK_POINT, "physical", null), NOW));
        assertEquals(new HopPolicySource.Unbound(PathReasons.UNSUPPORTED_NODE_VENDOR),
                bind(catalog, node("fortinet", "physical", "dev-t04"), NOW));
    }

    @Test
    void twoSnapshotsNamingTheSameDeviceAndContextBindNeither() {
        var a = PathGoldenAccess.cpChain("dev-t04");
        var catalog = new InMemoryPathPolicyCatalog().add(a)
                .add(with(a, "policy-synthetic-cp-02", a.metadata().collectedAt(), a.metadata().targets()));
        assertEquals(new HopPolicySource.Unbound(PathReasons.POLICY_BINDING_AMBIGUOUS),
                bind(catalog, node(TopologyInput.VENDOR_CHECK_POINT, "physical", "dev-t04"), NOW));
    }

    @Test
    void stalenessIs24HoursOutOfSyncOrAnUnreadableCollectionTime() {
        var cp = PathGoldenAccess.cpChain("dev-t04");
        var t04 = node(TopologyInput.VENDOR_CHECK_POINT, "physical", "dev-t04");
        var catalog = new InMemoryPathPolicyCatalog().add(cp);
        assertFalse(((HopPolicySource.Bound) bind(catalog, t04, Instant.parse("2026-11-05T05:00:00Z"))).stale(),
                "exactly 24 h is still current");
        assertTrue(((HopPolicySource.Bound) bind(catalog, t04, Instant.parse("2026-11-05T05:00:01Z"))).stale());

        for (String unreadable : new String[] {"", "yesterday", "2026-11-04"}) {
            var odd = new InMemoryPathPolicyCatalog().add(with(cp, cp.metadata().id(), unreadable, cp.metadata().targets()));
            var bound = (HopPolicySource.Bound) bind(odd, t04, NOW);
            assertTrue(bound.stale(), unreadable);
            assertEquals(Optional.empty(), bound.collectedAt());
        }
        var pan = PathGoldenAccess.panHttpsOnlyWithSourceNat("dev-b02");
        var outOfSync = new InMemoryPathPolicyCatalog().add(with(pan, pan.metadata().id(), pan.metadata().collectedAt(),
                List.of(new PolicySnapshot.Target("dev-b02", "FW-BRAVO-02", "vsys1", "OUT_OF_SYNC"))));
        assertTrue(((HopPolicySource.Bound) bind(outOfSync, node(TopologyInput.VENDOR_PALO_ALTO, "vsys1", "dev-b02"), NOW))
                .stale());
    }

    @Test
    void checkPointReadsItsBindingsAndPaloAltoItsSerialAndThePreparedViewIsReused() {
        var cp = PathGoldenAccess.cpChain("dev-t04");
        var catalog = new InMemoryPathPolicyCatalog().add(cp).add(PathGoldenAccess.panHttpsOnlyWithSourceNat("dev-b02"));
        catalog.bindings.put(cp.metadata().id(), List.of(new PolicyCollectionRepository.TargetBinding("dev-t04", "",
                cp.metadata().id(), PolicySnapshot.ref(cp.metadata().id(), "object", "gw-t04"))));
        catalog.serials.put("dev-b02", "SERIAL-SYNTHETIC-01");
        var binder = new PathPolicyBinder(catalog);
        var t04 = node(TopologyInput.VENDOR_CHECK_POINT, "physical", "dev-t04");
        var b02 = node(TopologyInput.VENDOR_PALO_ALTO, "vsys1", "dev-b02");
        var source = binder.forQuery(NOW);
        var first = (HopPolicySource.Bound) source.policyFor(t04);
        source.policyFor(b02);
        assertEquals(List.of("catalog", "find:" + cp.metadata().id(), "bindings:" + cp.metadata().id(),
                "find:" + PathGoldenAccess.panHttpsOnlyWithSourceNat("dev-b02").metadata().id(), "serial:dev-b02"),
                catalog.reads, "the catalog is read once per query");
        var again = (HopPolicySource.Bound) binder.forQuery(NOW).policyFor(t04);
        assertSame(first.prepared(), again.prepared(), "same snapshot instance -> the prepared view is reused");
        assertEquals(2, binder.preparedHeld());
        // A new snapshot instance (the parse cache re-read a changed row) is prepared again.
        catalog.add(with(cp, cp.metadata().id(), cp.metadata().collectedAt(), cp.metadata().targets()));
        var changed = (HopPolicySource.Bound) binder.forQuery(NOW).policyFor(t04);
        assertFalse(first.prepared() == changed.prepared());
    }
}
