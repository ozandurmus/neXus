package com.securityexpert.nexus.ui2.worker.inventory.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.worker.inventory.Fixtures;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedRoute;

/** 14E PP-2: whitespace-tokenised flags, grouped by {@code virtual-router}; the {@code result/flags} legend is not a route. */
class PaloAltoRouteParserTest {

    @Test
    void tokenisesFlagsOnWhitespaceAndGroupsByVirtualRouter() {
        Map<String, List<ParsedRoute>> byVirtualRouter =
                PaloAltoRouteParser.parse(Fixtures.read("pan/show_routing_route.xml"));

        assertEquals(3, byVirtualRouter.size(), "default, VR-DMZ, VR-ORPHAN -- the legend line is not a fourth route");

        ParsedRoute connected = byVirtualRouter.get("default").get(0);
        assertEquals("192.0.2.0/24", connected.destination());
        assertTrue(connected.nextHop().isEmpty(), "nexthop 0.0.0.0 means none");
        assertEquals(InventoryRoute.PROTOCOL_CONNECTED, connected.protocol());
        assertEquals("default", connected.routeTable().orElseThrow());

        ParsedRoute staticRoute = byVirtualRouter.get("VR-DMZ").get(0);
        assertEquals(InventoryRoute.PROTOCOL_STATIC, staticRoute.protocol());
        assertEquals("ethernet1/3", staticRoute.interfaceName().orElseThrow());

        ParsedRoute hostRoute = byVirtualRouter.get("VR-ORPHAN").get(0);
        assertEquals(InventoryRoute.PROTOCOL_HOST, hostRoute.protocol());
        assertEquals("192.0.2.254", hostRoute.nextHop().orElseThrow());
        assertTrue(hostRoute.interfaceName().isEmpty(), "an empty <interface> element is kept empty, per PP-2");
    }

    @Test
    void v184RibFlagsKeepInactiveRoutesEcmpAndMetricWhenTheLegendDeclaresEcmp() {
        List<ParsedRoute> routes = PaloAltoRouteParser.parse(Fixtures.read("pan/show_routing_route_rib_flags.xml"))
                .get("default");

        assertEquals(5, routes.size(), "the inactive floating default route is kept, never dropped");
        ParsedRoute firstEcmp = routes.get(0);
        assertEquals(Optional.of(true), firstEcmp.active());
        assertEquals(Optional.of(true), firstEcmp.ecmp(), "E with a legend declaring E:ecmp");
        assertEquals(Optional.of(10L), firstEcmp.metric());
        assertEquals(Optional.of(InventoryRoute.NEXT_HOP_IP), firstEcmp.nextHopKind());
        assertEquals(Optional.of(true), routes.get(1).ecmp());

        ParsedRoute floating = routes.get(2);
        assertEquals(Optional.of(false), floating.active(), "no A flag: an inactive RIB route (A0 §5b)");
        assertEquals(Optional.of(false), floating.ecmp(), "the legend declares E and this entry has none");
        assertEquals(Optional.of(200L), floating.metric());

        ParsedRoute connected = routes.get(3);
        assertEquals(Optional.of(InventoryRoute.NEXT_HOP_CONNECTED), connected.nextHopKind());
        assertEquals(Optional.of(0L), connected.metric());

        ParsedRoute nonLiteralNextHop = routes.get(4);
        assertEquals(Optional.empty(), nonLiteralNextHop.nextHopKind(), "a non-address next hop is UNKNOWN, not guessed NEXT_VR");
        assertEquals(Optional.empty(), nonLiteralNextHop.metric(), "a non-numeric metric is UNKNOWN");
    }

    @Test
    void v184EcmpStaysUnknownWhenTheLegendDoesNotDeclareItAndAMissingFlagsLeafIsUnknown() {
        List<ParsedRoute> routes = PaloAltoRouteParser.parse(Fixtures.read("pan/show_routing_route_legacy_legend.xml"))
                .get("default");

        ParsedRoute loose = routes.get(0);
        assertEquals(Optional.of(false), loose.active(), "?B without A is not active (A0 §5b, PA-KB1)");
        assertEquals(Optional.empty(), loose.ecmp(), "legend without E:ecmp: ECMP is UNKNOWN, never false");
        assertEquals(Optional.of(20L), loose.metric());

        ParsedRoute noFlags = routes.get(1);
        assertEquals(Optional.empty(), noFlags.active(), "no flags leaf: active is UNKNOWN");
        assertEquals(Optional.empty(), noFlags.ecmp());
        assertEquals(Optional.empty(), noFlags.metric());
    }

    @Test
    void v184TheExistingFixtureLegendDeclaresEcmpSoAbsentEIsNotEcmp() {
        ParsedRoute connected = PaloAltoRouteParser.parse(Fixtures.read("pan/show_routing_route.xml")).get("default").get(0);
        assertEquals(Optional.of(true), connected.active());
        assertEquals(Optional.of(false), connected.ecmp());
        assertEquals(Optional.empty(), connected.metric(), "no metric leaf: UNKNOWN");
    }

    @Test
    void emptyResponseYieldsNoRoutes() {
        assertEquals(Map.of(), PaloAltoRouteParser.parse(""));
    }
}
