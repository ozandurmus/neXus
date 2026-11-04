package com.securityexpert.nexus.ui2.worker.inventory.cp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedRoute;

/**
 * {@code ip -4 route show table all} (14D CF-3, PR-1). {@code local}/
 * {@code broadcast} rows and {@code 127.0.0.0/8} rows are never routes;
 * {@code default} means {@code 0.0.0.0/0}; a {@code blackhole} row's
 * destination is its second token, never its first ({@code blackhole}
 * itself). PR-1's measured correction: Gaia's {@code proto} token is
 * numeric, not textual -- {@code 7} is the routing daemon's installed
 * route (default and static) and maps to {@link InventoryRoute#PROTOCOL_STATIC},
 * {@code kernel} maps to {@link InventoryRoute#PROTOCOL_CONNECTED}; any
 * other numeric token is {@link InventoryRoute#PROTOCOL_UNKNOWN} rather than
 * guessed. A connected row may read {@code dev X proto 7 scope link}
 * without {@code src} and is still a route. PR-5: an unsolicited syslog/
 * kernel line interleaved into the output (its first token is neither a
 * destination-shaped token nor {@code default}/{@code blackhole}/{@code
 * local}/{@code broadcast}) is skipped, never treated as a route and never
 * fatal.
 *
 * <p>G6 (PO approval 2026-11-04): the same parser reads a virtual system's {@code vsenv <VSID> && ip -4 route show
 * table all}. A row of the main table carries no {@code table} token (route table empty); a row of any other table keeps
 * its name or number from {@code table <name>}; {@code table local} rows are never routes. The topology builder reads the
 * non-main names as {@code PBR_PRESENT}.</p>
 *
 * <p>V184 (path analysis A1, gap G4). A {@code blackhole} row is next-hop kind {@link InventoryRoute#NEXT_HOP_DISCARD}
 * (it drops matching traffic), never a route that merely lacks a next hop. A {@code via} next hop is {@link
 * InventoryRoute#NEXT_HOP_IP}; a {@code proto kernel} row without {@code via} is {@link
 * InventoryRoute#NEXT_HOP_CONNECTED}; any other row's kind is UNKNOWN. {@code metric <n>} is kept as read. Multipath:
 * A0 §7 records no official Gaia example, so only the unambiguous iproute2 line structure is read -- a head line with
 * neither {@code via} nor {@code dev}, immediately followed by {@code nexthop via <ip> dev <if>} continuation lines.
 * Two or more such lines yield one route per next hop, each {@code is_ecmp = true}; a single continuation line yields
 * its route with ECMP UNKNOWN. A head line that already names a path ({@code via}/{@code dev}) and still has
 * continuation lines is ambiguous: the head route is kept with ECMP UNKNOWN and the continuation lines are not read.
 * A single-line row that names its path ({@code via} or {@code dev}) is not multipath ({@code false}); a row naming
 * no path, a {@code blackhole} row and an orphan continuation line (after a skipped or {@code local}/{@code
 * broadcast} row) carry no ECMP fact. The ECMP shape still needs a masked real fixture (A0 §7) before real-environment
 * validation. Interleaved syslog/kernel lines (PR-5) are skipped and do not end a multipath group.</p>
 */
public final class CheckPointIpRouteParser {

    private static final Pattern DESTINATION_TOKEN =
            Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}(?:/\\d{1,2})?$");
    private static final Pattern UNSIGNED = Pattern.compile("^\\d{1,10}$");

    private CheckPointIpRouteParser() {
    }

    /** One head line awaiting its possible {@code nexthop} continuation lines. */
    private static final class Head {
        final String destination;
        final Optional<String> nextHop;
        final Optional<String> interfaceName;
        final String protocol;
        final Optional<String> routeTable;
        final Optional<Long> metric;
        final boolean blackhole;
        /** A row of the kernel table is a forwarding entry; {@code linkdown}/{@code dead} makes that UNKNOWN. */
        final Optional<Boolean> active;
        final List<String[]> nextHops = new ArrayList<>();

        Head(String destination, Optional<String> nextHop, Optional<String> interfaceName, String protocol,
                Optional<String> routeTable, Optional<Long> metric, boolean blackhole, Optional<Boolean> active) {
            this.destination = destination;
            this.nextHop = nextHop;
            this.interfaceName = interfaceName;
            this.protocol = protocol;
            this.routeTable = routeTable;
            this.metric = metric;
            this.blackhole = blackhole;
            this.active = active;
        }

        boolean namesPath() {
            return nextHop.isPresent() || interfaceName.isPresent();
        }
    }

    public static List<ParsedRoute> parse(String output) {
        List<ParsedRoute> routes = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return routes;
        }
        Head pending = null;
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> tokens = List.of(line.split("\\s+"));
            if (tokens.isEmpty()) {
                continue;
            }
            String first = tokens.get(0);
            if ("nexthop".equals(first)) {
                if (pending != null) {
                    pending.nextHops.add(nextHopOf(tokens));
                }
                // An orphan continuation line (no head, or a skipped head) is never a route of its own.
                continue;
            }
            if ("local".equals(first) || "broadcast".equals(first)) {
                flush(pending, routes);
                pending = null;
                continue;
            }

            String destination;
            int nextIndex;
            boolean blackhole = false;
            if ("default".equals(first)) {
                destination = "0.0.0.0/0";
                nextIndex = 1;
            } else if ("blackhole".equals(first)) {
                if (tokens.size() < 2) {
                    continue;
                }
                destination = tokens.get(1);
                nextIndex = 2;
                blackhole = true;
            } else if (DESTINATION_TOKEN.matcher(first).matches()) {
                destination = first;
                nextIndex = 1;
            } else {
                // PR-5: not a route line at all (e.g. an interleaved syslog/kernel line) -- skip, never fatal.
                continue;
            }
            flush(pending, routes);
            pending = null;
            if (destination.startsWith("127.")) {
                continue;
            }

            Optional<String> nextHop = Optional.empty();
            Optional<String> interfaceName = Optional.empty();
            Optional<String> routeTable = Optional.empty();
            Optional<Long> metric = Optional.empty();
            String protocolToken = null;
            for (int i = nextIndex; i < tokens.size(); i++) {
                switch (tokens.get(i)) {
                    case "via" -> {
                        if (i + 1 < tokens.size()) {
                            nextHop = Optional.of(tokens.get(++i));
                        }
                    }
                    case "dev" -> {
                        if (i + 1 < tokens.size()) {
                            interfaceName = Optional.of(tokens.get(++i));
                        }
                    }
                    case "proto" -> {
                        if (i + 1 < tokens.size()) {
                            protocolToken = tokens.get(++i);
                        }
                    }
                    case "table" -> {
                        if (i + 1 < tokens.size()) {
                            routeTable = Optional.of(tokens.get(++i));
                        }
                    }
                    case "metric" -> {
                        if (i + 1 < tokens.size()) {
                            metric = unsigned(tokens.get(++i));
                        }
                    }
                    default -> {
                        // scope/src and other trailing tokens carry no D-4 column.
                    }
                }
            }
            // G6 (per-VS `table all`): the local table holds the host's own addresses, never a forwarding route. Its rows
            // normally start with `local`/`broadcast` (dropped above); a row naming `table local` any other way is dropped too.
            if (routeTable.filter("local"::equals).isPresent()) {
                continue;
            }
            // `ip route show` lists the kernel routing table, whose rows are the forwarding entries (PO-delegated decision
            // 2026-11-04, PATH_ANALYSIS_DESIGN.md A4 note); a row flagged linkdown or dead is not proven to forward.
            Optional<Boolean> active = tokens.contains("linkdown") || tokens.contains("dead") ? Optional.empty() : Optional.of(true);
            pending = new Head(destination, nextHop, interfaceName, protocolOf(protocolToken), routeTable, metric,
                    blackhole, active);
        }
        flush(pending, routes);
        return routes;
    }

    /** {@code nexthop via <ip> dev <if> weight <w>} to {via, dev}; either may be null. */
    private static String[] nextHopOf(List<String> tokens) {
        String via = null;
        String dev = null;
        for (int i = 1; i < tokens.size() - 1; i++) {
            if ("via".equals(tokens.get(i))) {
                via = tokens.get(++i);
            } else if ("dev".equals(tokens.get(i))) {
                dev = tokens.get(++i);
            }
        }
        return new String[] {via, dev};
    }

    private static void flush(Head head, List<ParsedRoute> routes) {
        if (head == null) {
            return;
        }
        if (head.blackhole) {
            routes.add(new ParsedRoute(head.destination, Optional.empty(), Optional.empty(), head.protocol,
                    head.routeTable, head.active, Optional.empty(), head.metric,
                    Optional.of(InventoryRoute.NEXT_HOP_DISCARD)));
            return;
        }
        List<String[]> multipath = head.nextHops.stream().filter(hop -> hop[0] != null || hop[1] != null).toList();
        if (head.nextHops.isEmpty() || head.namesPath() || multipath.size() != head.nextHops.size()) {
            Optional<Boolean> ecmp = head.nextHops.isEmpty() && head.namesPath() ? Optional.of(false) : Optional.empty();
            routes.add(new ParsedRoute(head.destination, head.nextHop, head.interfaceName, head.protocol,
                    head.routeTable, head.active, ecmp, head.metric,
                    kindOf(head.nextHop, head.protocol)));
            return;
        }
        Optional<Boolean> ecmp = multipath.size() >= 2 ? Optional.of(true) : Optional.empty();
        for (String[] hop : multipath) {
            Optional<String> via = Optional.ofNullable(hop[0]);
            routes.add(new ParsedRoute(head.destination, via, Optional.ofNullable(hop[1]), head.protocol,
                    head.routeTable, head.active, ecmp, head.metric, kindOf(via, head.protocol)));
        }
    }

    private static Optional<String> kindOf(Optional<String> nextHop, String protocol) {
        if (nextHop.isPresent()) {
            return Optional.of(InventoryRoute.NEXT_HOP_IP);
        }
        return InventoryRoute.PROTOCOL_CONNECTED.equals(protocol)
                ? Optional.of(InventoryRoute.NEXT_HOP_CONNECTED) : Optional.empty();
    }

    private static Optional<Long> unsigned(String token) {
        return UNSIGNED.matcher(token).matches()
                ? Optional.of(Long.parseLong(token)).filter(m -> m <= 4_294_967_295L) : Optional.empty();
    }

    private static final java.util.regex.Pattern NUMERIC_TOKEN = java.util.regex.Pattern.compile("\\d+");

    private static String protocolOf(String token) {
        if (token == null) {
            return InventoryRoute.PROTOCOL_UNKNOWN;
        }
        if (NUMERIC_TOKEN.matcher(token).matches()) {
            // PR-1: "7" is Gaia routed's installed route (default/static); any other numeric is unclassified.
            return "7".equals(token) ? InventoryRoute.PROTOCOL_STATIC : InventoryRoute.PROTOCOL_UNKNOWN;
        }
        return switch (token) {
            case "static" -> InventoryRoute.PROTOCOL_STATIC;
            case "kernel" -> InventoryRoute.PROTOCOL_CONNECTED;
            case "connected" -> InventoryRoute.PROTOCOL_CONNECTED;
            case "ospf" -> InventoryRoute.PROTOCOL_OSPF;
            case "bgp" -> InventoryRoute.PROTOCOL_BGP;
            case "rip" -> InventoryRoute.PROTOCOL_RIP;
            default -> InventoryRoute.PROTOCOL_UNKNOWN;
        };
    }
}
