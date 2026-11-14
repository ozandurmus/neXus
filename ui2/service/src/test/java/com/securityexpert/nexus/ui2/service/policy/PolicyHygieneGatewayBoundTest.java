package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * PO decision D-1 (2026-11-14, A0 amendment section G1): a Check Point gateway/cluster object matches at most its own
 * management addresses, used only to pass an earlier rule over, never as cover proof (Resolver#gatewayBound). Synthetic
 * fixtures: documentation addresses and masked names only.
 */
class PolicyHygieneGatewayBoundTest {
    static PolicyObject gateway(String id, String... values) {
        return new PolicyObject(id, "OBJ-GW-" + id, "gateway", List.of(), List.of(values), "RESOLVED");
    }
    static Map<String, PolicyObject> objects() {
        var objects = PolicyHygienePartialPriorTest.partialObjects();
        objects.put("gw", gateway("gw", "ipv4-address: 192.0.2.1", "gateway-ipv4: 192.0.2.1", "gateway-ipv4: 192.0.2.2", "gateway-addresses: complete"));
        objects.put("gw-incomplete", gateway("gw-incomplete", "ipv4-address: 192.0.2.1", "gateway-ipv4: 192.0.2.1"));
        objects.put("gw-main-only", gateway("gw-main-only", "ipv4-address: 192.0.2.1"));
        objects.put("gw-bad", gateway("gw-bad", "gateway-ipv4: 192.0.2.0/24", "gateway-addresses: complete"));
        objects.put("gw-host", object("gw-host", "host", "ipv4-address: 192.0.2.2"));
        objects.put("gw-group", new PolicyObject("gw-group", "OBJ-GROUP-GW", "group", List.of("gw", "other-host"), List.of(), "RESOLVED"));
        return objects;
    }
    static PolicySnapshot cp(Rule... rules) {
        var base = sections("CP", cpSection("s1", null, rules));
        return new PolicySnapshot(base.metadata(), base.sections(), objects());
    }
    static Rule rule(String id, int number, String source, String destination) {
        return PolicyHygienePartialPriorTest.cp(id, number, "Accept", source, destination, "any");
    }

    @Test void anEarlierGatewayDestinationRuleIsPassedOverForATrafficOutsideItsAddresses() {
        var snapshot = cp(rule("r1", 1, "any", "gw"), rule("r2", 2, "any", "far"));
        snapshot = withFar(snapshot);
        var r2 = assessment(snapshot, "r2");
        assertEquals("NOT_SHADOWED", r2.shadowStatus(), r2.toString());
        // The same snapshot without the completeness marker keeps the former answer.
        var before = assessment(withFar(cp(rule("r1", 1, "any", "gw-incomplete"), rule("r2", 2, "any", "far"))), "r2");
        assertEquals("UNKNOWN", before.shadowStatus());
        assertEquals(PolicyHygiene.EARLIER_UNSUPPORTED, before.shadowReason());
        assertEquals("r1", before.blockingRuleId());
    }

    @Test void aRuleInsideTheGatewayAddressesStaysUnknownAndIsNeverCovered() {
        var r2 = assessment(cp(rule("r1", 1, "any", "gw"), rule("r2", 2, "any", "gw-host")), "r2");
        assertEquals("UNKNOWN", r2.shadowStatus(), r2.toString());
        assertEquals(PolicyHygiene.EARLIER_UNSUPPORTED, r2.shadowReason());
        assertNull(r2.coveringRuleId());
    }

    @Test void theBoundNeverCoversTheGatewayRuleItself() {
        var snapshot = cp(rule("r1", 1, "any", "any"), rule("r2", 2, "any", "gw"));
        for (var a : List.of(assessment(snapshot, "r2"), PolicyHygiene.analyzeLegacy(PolicyLegacyView.of(snapshot), 90, NOW, PolicyHygiene.TOTAL_WORK).rules().get("r2"))) {
            assertEquals("UNKNOWN", a.shadowStatus(), a.toString());
            assertNull(a.coveringRuleId());
        }
    }

    @Test void sourcesAndGroupsAreBoundedTheSameWay() {
        assertEquals("NOT_SHADOWED", assessment(withFar(cp(rule("r1", 1, "gw", "any"), rule("r2", 2, "far", "any"))), "r2").shadowStatus());
        assertEquals("NOT_SHADOWED", assessment(withFar(cp(rule("r1", 1, "any", "gw-group"), rule("r2", 2, "any", "far"))), "r2").shadowStatus());
    }

    @Test void incompleteOrUnparseableAddressesGiveNoBound() {
        for (String gw : List.of("gw-incomplete", "gw-main-only", "gw-bad")) {
            var r2 = assessment(withFar(cp(rule("r1", 1, "any", gw), rule("r2", 2, "any", "far"))), "r2");
            assertEquals("UNKNOWN", r2.shadowStatus(), gw);
            assertEquals(PolicyHygiene.EARLIER_UNSUPPORTED, r2.shadowReason(), gw);
        }
    }

    @Test void theLegacyAnalysisPassesTheGatewayRuleOverToo() {
        var snapshot = withFar(cp(rule("r1", 1, "any", "gw"), rule("r2", 2, "any", "far")));
        var legacy = PolicyHygiene.analyzeLegacy(PolicyLegacyView.of(snapshot), 90, NOW, PolicyHygiene.TOTAL_WORK).rules();
        assertEquals("NOT_SHADOWED", legacy.get("r2").shadowStatus(), legacy.get("r2").toString());
    }

    @Test void theBoundIsTheGatewayAddressesPlusEveryIpv6Address() {
        var resolver = PolicyNormalization.Resolver.shadowing(cp(), new PolicyNormalization.Budget(PolicyHygiene.TOTAL_WORK));
        var bound = resolver.staticBound(new Cell(List.of("gw"), false), false);
        assertFalse(bound.any());
        assertEquals(Set.of("ipv4", "ipv6"), bound.ranges().keySet());
        assertEquals(List.of(new PolicyNormalization.Range(0xC0000201L, 0xC0000202L)), bound.ranges().get("ipv4"));
        assertThrows(PolicyNormalization.Unknown.class, () -> resolver.staticBound(new Cell(List.of("other-host"), false), false));
    }

    static PolicySnapshot withFar(PolicySnapshot snapshot) {
        Map<String, PolicyObject> objects = new HashMap<>(snapshot.objects());
        objects.put("far", object("far", "host", "ipv4-address: 203.0.113.9"));
        return new PolicySnapshot(snapshot.metadata(), snapshot.sections(), objects);
    }
}
