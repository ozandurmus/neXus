package com.securityexpert.nexus.ui2.worker.inventory.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

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
    void emptyResponseYieldsNoRoutes() {
        assertEquals(Map.of(), PaloAltoRouteParser.parse(""));
    }
}
