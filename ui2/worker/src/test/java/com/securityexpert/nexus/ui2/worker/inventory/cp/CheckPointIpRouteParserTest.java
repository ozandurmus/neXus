package com.securityexpert.nexus.ui2.worker.inventory.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.worker.inventory.Fixtures;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedRoute;

/** AC-2: {@code ip -4 route show table all}, and 14C §4's named corrections. */
class CheckPointIpRouteParserTest {

    @Test
    void appliesTheDefaultBlackholeAndExclusionRules() {
        List<ParsedRoute> routes = CheckPointIpRouteParser.parse(Fixtures.read("cp/ip_route_show_table_all.txt"));

        assertEquals(List.of(
                new ParsedRoute("0.0.0.0/0", Optional.of("192.0.2.1"), Optional.of("eth0"),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty()),
                new ParsedRoute("192.0.2.0/24", Optional.empty(), Optional.of("eth0"), InventoryRoute.PROTOCOL_KERNEL,
                        Optional.empty()),
                new ParsedRoute("198.51.100.0/24", Optional.of("192.0.2.254"), Optional.of("eth0"),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty()),
                new ParsedRoute("203.0.113.0/24", Optional.empty(), Optional.empty(), InventoryRoute.PROTOCOL_STATIC,
                        Optional.empty())),
                routes, "local/broadcast and 127.x rows are excluded; 'default' becomes 0.0.0.0/0; a blackhole "
                        + "row's destination is its second token");
    }
}
