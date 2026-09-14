package com.securityexpert.nexus.ui2.worker.inventory.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.worker.inventory.Fixtures;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedRoute;

/**
 * AC-2: {@code ip -4 route show table all}, 14D PR-1's measured numeric
 * {@code proto} mapping, and the {@code local}/{@code broadcast}/loopback
 * exclusion rules -- fixtures are the measured shapes (22 lines / 6 routes
 * on the cluster member, 13 lines / 3 routes on the VSX host VS0), each
 * carrying an interleaved syslog/kernel line (PR-5) that must not affect
 * the route count.
 */
class CheckPointIpRouteParserTest {

    @Test
    void clusterMemberFixtureYields6RoutesWithNumericProtoMappedAndLocalRowsDropped() {
        List<ParsedRoute> routes = CheckPointIpRouteParser.parse(Fixtures.read("cp/ip_route_show_table_all.txt"));

        assertEquals(List.of(
                new ParsedRoute("0.0.0.0/0", Optional.of("192.0.2.1"), Optional.of("eth0"),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty()),
                new ParsedRoute("192.0.2.0/24", Optional.empty(), Optional.of("eth0"),
                        InventoryRoute.PROTOCOL_CONNECTED, Optional.empty()),
                new ParsedRoute("198.51.100.0/24", Optional.empty(), Optional.of("eth1"),
                        InventoryRoute.PROTOCOL_CONNECTED, Optional.empty()),
                new ParsedRoute("198.51.100.128/25", Optional.of("198.51.100.1"), Optional.of("eth1"),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty()),
                new ParsedRoute("203.0.113.0/25", Optional.of("203.0.113.1"), Optional.of("eth2"),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty()),
                new ParsedRoute("203.0.113.128/25", Optional.of("203.0.113.129"), Optional.of("eth2"),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty())),
                routes, "proto 7 -> static, proto kernel -> connected, table local rows dropped, interleaved "
                        + "syslog/kernel lines skipped without affecting the route count");
    }

    @Test
    void vsxHostVs0FixtureYields3RoutesIncludingAScopeLinkRouteWithoutSrc() {
        List<ParsedRoute> routes = CheckPointIpRouteParser.parse(Fixtures.read("cp/ip_route_show_table_all_vs0.txt"));

        assertEquals(List.of(
                new ParsedRoute("0.0.0.0/0", Optional.of("192.0.2.1"), Optional.of("Mgmt"),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty()),
                new ParsedRoute("192.0.2.0/24", Optional.empty(), Optional.of("Mgmt"),
                        InventoryRoute.PROTOCOL_CONNECTED, Optional.empty()),
                new ParsedRoute("203.0.113.0/24", Optional.empty(), Optional.of("Sync"),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty())),
                routes, "'dev X proto 7 scope link' without src is still a route");
    }

    @Test
    void anUnclassifiedNumericProtoTokenIsUnknownNeverGuessedStatic() {
        List<ParsedRoute> routes = CheckPointIpRouteParser.parse("192.0.2.0/24 dev eth0 proto 9 scope link\n");

        assertEquals(InventoryRoute.PROTOCOL_UNKNOWN, routes.get(0).protocol());
    }
}
