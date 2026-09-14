package com.securityexpert.nexus.ui2.worker.inventory.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.worker.inventory.Fixtures;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedRoute;

/** AC-2: {@code show routing route} -- protocol from flags, virtual-router as table, container-anchored. */
class PaloAltoRouteParserTest {

    @Test
    void keepsFlagLettersAsProtocolAndGroupsByVsys() {
        Map<String, String> interfaceToVsys = Map.of("ethernet1/1", "vsys1", "ethernet1/2.100", "vsys2");

        Map<String, List<ParsedRoute>> byVsys =
                PaloAltoRouteParser.parse(Fixtures.read("pan/show_routing_route.xml"), interfaceToVsys, "vsys1");

        assertEquals(2, byVsys.size());
        List<ParsedRoute> vsys1Routes = byVsys.get("vsys1");
        assertEquals(2, vsys1Routes.size());

        ParsedRoute connected = vsys1Routes.stream().filter(r -> r.destination().equals("192.0.2.0/24")).findFirst()
                .orElseThrow();
        assertTrue(connected.nextHop().isEmpty(), "nexthop 0.0.0.0 means none");
        assertEquals(InventoryRoute.PROTOCOL_CONNECTED, connected.protocol());
        assertEquals("default", connected.routeTable().orElseThrow());

        ParsedRoute defaultRoute = vsys1Routes.stream().filter(r -> r.destination().equals("0.0.0.0/0")).findFirst()
                .orElseThrow();
        assertEquals(InventoryRoute.PROTOCOL_STATIC, defaultRoute.protocol());
        assertEquals("192.0.2.254", defaultRoute.nextHop().orElseThrow());
        assertEquals("ethernet1/1", defaultRoute.interfaceName().orElseThrow(),
                "the @vsys1 suffix is stripped from the stored interface name");

        List<ParsedRoute> vsys2Routes = byVsys.get("vsys2");
        assertEquals(2, vsys2Routes.size());
        ParsedRoute hostRoute = vsys2Routes.stream().filter(r -> r.destination().equals("203.0.113.1/32")).findFirst()
                .orElseThrow();
        assertEquals(InventoryRoute.PROTOCOL_HOST, hostRoute.protocol());
    }
}
