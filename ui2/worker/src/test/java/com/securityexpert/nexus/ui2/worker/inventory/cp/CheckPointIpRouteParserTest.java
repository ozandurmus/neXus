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
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty(), Optional.of(true), Optional.of(false), Optional.of(100L), Optional.of(InventoryRoute.NEXT_HOP_IP)),
                new ParsedRoute("192.0.2.0/24", Optional.empty(), Optional.of("eth0"),
                        InventoryRoute.PROTOCOL_CONNECTED, Optional.empty(), Optional.of(true), Optional.of(false), Optional.empty(), Optional.of(InventoryRoute.NEXT_HOP_CONNECTED)),
                new ParsedRoute("198.51.100.0/24", Optional.empty(), Optional.of("eth1"),
                        InventoryRoute.PROTOCOL_CONNECTED, Optional.empty(), Optional.of(true), Optional.of(false), Optional.empty(), Optional.of(InventoryRoute.NEXT_HOP_CONNECTED)),
                new ParsedRoute("198.51.100.128/25", Optional.of("198.51.100.1"), Optional.of("eth1"),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty(), Optional.of(true), Optional.of(false), Optional.empty(), Optional.of(InventoryRoute.NEXT_HOP_IP)),
                new ParsedRoute("203.0.113.0/25", Optional.of("203.0.113.1"), Optional.of("eth2"),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty(), Optional.of(true), Optional.of(false), Optional.empty(), Optional.of(InventoryRoute.NEXT_HOP_IP)),
                new ParsedRoute("203.0.113.128/25", Optional.of("203.0.113.129"), Optional.of("eth2"),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty(), Optional.of(true), Optional.of(false), Optional.empty(), Optional.of(InventoryRoute.NEXT_HOP_IP))),
                routes, "proto 7 -> static, proto kernel -> connected, table local rows dropped, interleaved "
                        + "syslog/kernel lines skipped without affecting the route count");
    }

    @Test
    void vsxHostVs0FixtureYields3RoutesIncludingAScopeLinkRouteWithoutSrc() {
        List<ParsedRoute> routes = CheckPointIpRouteParser.parse(Fixtures.read("cp/ip_route_show_table_all_vs0.txt"));

        assertEquals(List.of(
                new ParsedRoute("0.0.0.0/0", Optional.of("192.0.2.1"), Optional.of("Mgmt"),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty(), Optional.of(true), Optional.of(false), Optional.of(100L), Optional.of(InventoryRoute.NEXT_HOP_IP)),
                new ParsedRoute("192.0.2.0/24", Optional.empty(), Optional.of("Mgmt"),
                        InventoryRoute.PROTOCOL_CONNECTED, Optional.empty(), Optional.of(true), Optional.of(false), Optional.empty(), Optional.of(InventoryRoute.NEXT_HOP_CONNECTED)),
                new ParsedRoute("203.0.113.0/24", Optional.empty(), Optional.of("Sync"),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty(), Optional.of(true), Optional.of(false), Optional.empty(), Optional.empty())),
                routes, "'dev X proto 7 scope link' without src is still a route");
    }

    /**
     * V184 / G4 (synthetic, A0 §7: no official Gaia multipath example exists): a multipath head line followed by two
     * nexthop lines is two ECMP routes (an interleaved syslog line does not end the group); a single continuation line
     * gives its route with ECMP UNKNOWN; a head that already names a path keeps only itself with ECMP UNKNOWN; a
     * blackhole is DISCARD and never takes continuation lines; continuation lines after a skipped broadcast row are
     * orphans and never routes; a head naming no path and no continuation carries no ECMP fact and no kind.
     */
    @Test
    void v184MultipathGroupsBlackholesAndAmbiguousShapes() {
        List<ParsedRoute> routes = CheckPointIpRouteParser.parse(
                Fixtures.read("cp/ip_route_show_table_all_multipath_synthetic.txt"));

        assertEquals(List.of(
                new ParsedRoute("0.0.0.0/0", Optional.of("192.0.2.1"), Optional.of("eth0"),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty(), Optional.of(true), Optional.of(false), Optional.of(100L), Optional.of(InventoryRoute.NEXT_HOP_IP)),
                new ParsedRoute("198.51.100.0/24", Optional.of("192.0.2.2"), Optional.of("eth1"),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty(), Optional.of(true), Optional.of(true), Optional.of(20L), Optional.of(InventoryRoute.NEXT_HOP_IP)),
                new ParsedRoute("198.51.100.0/24", Optional.of("192.0.2.3"), Optional.of("eth2"),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty(), Optional.of(true), Optional.of(true), Optional.of(20L), Optional.of(InventoryRoute.NEXT_HOP_IP)),
                new ParsedRoute("203.0.113.0/25", Optional.of("192.0.2.4"), Optional.of("eth1"),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty(), Optional.of(true), Optional.empty(), Optional.empty(), Optional.of(InventoryRoute.NEXT_HOP_IP)),
                new ParsedRoute("203.0.113.128/25", Optional.of("192.0.2.5"), Optional.of("eth1"),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty(), Optional.of(true), Optional.empty(), Optional.empty(), Optional.of(InventoryRoute.NEXT_HOP_IP)),
                new ParsedRoute("198.51.100.128/25", Optional.empty(), Optional.empty(),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty(), Optional.of(true), Optional.empty(), Optional.empty(), Optional.of(InventoryRoute.NEXT_HOP_DISCARD)),
                new ParsedRoute("192.0.2.0/24", Optional.empty(), Optional.of("eth0"),
                        InventoryRoute.PROTOCOL_CONNECTED, Optional.empty(), Optional.of(true), Optional.of(false), Optional.empty(), Optional.of(InventoryRoute.NEXT_HOP_CONNECTED)),
                new ParsedRoute("198.51.100.192/26", Optional.empty(), Optional.empty(),
                        InventoryRoute.PROTOCOL_STATIC, Optional.empty(), Optional.of(true), Optional.empty(), Optional.empty(), Optional.empty())), routes);
    }

    @Test
    void v184BlackholeIsDiscardNotARouteWithoutANextHop() {
        ParsedRoute blackhole = CheckPointIpRouteParser.parse("blackhole 192.0.2.128/25 proto 7 metric 5\n").get(0);
        assertEquals("192.0.2.128/25", blackhole.destination());
        assertEquals(Optional.of(InventoryRoute.NEXT_HOP_DISCARD), blackhole.nextHopKind());
        assertEquals(Optional.of(5L), blackhole.metric());
        assertEquals(Optional.empty(), blackhole.ecmp());
    }

    @Test
    void anUnclassifiedNumericProtoTokenIsUnknownNeverGuessedStatic() {
        List<ParsedRoute> routes = CheckPointIpRouteParser.parse("192.0.2.0/24 dev eth0 proto 9 scope link\n");

        assertEquals(InventoryRoute.PROTOCOL_UNKNOWN, routes.get(0).protocol());
    }

    @Test
    void kernelTableRowsAreActiveUnlessFlaggedLinkdownOrDead() {
        List<ParsedRoute> routes = CheckPointIpRouteParser.parse(String.join("\n",
                "default via 192.0.2.1 dev eth0 proto 7",
                "198.51.100.0/24 dev eth1 proto kernel scope link src 198.51.100.2 linkdown",
                "203.0.113.0/24 via 192.0.2.9 dev eth0 proto 7 dead",
                "blackhole 192.0.2.128/25 proto 7"));
        assertEquals(java.util.Optional.of(true), routes.get(0).active());
        assertEquals(java.util.Optional.empty(), routes.get(1).active());
        assertEquals(java.util.Optional.empty(), routes.get(2).active());
        assertEquals(java.util.Optional.of(true), routes.get(3).active());
    }
}
