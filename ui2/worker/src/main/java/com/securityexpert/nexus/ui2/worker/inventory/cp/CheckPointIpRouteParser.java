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
 */
public final class CheckPointIpRouteParser {

    private static final Pattern DESTINATION_TOKEN =
            Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}(?:/\\d{1,2})?$");

    private CheckPointIpRouteParser() {
    }

    public static List<ParsedRoute> parse(String output) {
        List<ParsedRoute> routes = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return routes;
        }
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
            if ("local".equals(first) || "broadcast".equals(first)) {
                continue;
            }

            String destination;
            int nextIndex;
            if ("default".equals(first)) {
                destination = "0.0.0.0/0";
                nextIndex = 1;
            } else if ("blackhole".equals(first)) {
                if (tokens.size() < 2) {
                    continue;
                }
                destination = tokens.get(1);
                nextIndex = 2;
            } else if (DESTINATION_TOKEN.matcher(first).matches()) {
                destination = first;
                nextIndex = 1;
            } else {
                // PR-5: not a route line at all (e.g. an interleaved syslog/kernel line) -- skip, never fatal.
                continue;
            }
            if (destination.startsWith("127.")) {
                continue;
            }

            Optional<String> nextHop = Optional.empty();
            Optional<String> interfaceName = Optional.empty();
            Optional<String> routeTable = Optional.empty();
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
                    default -> {
                        // metric/scope/src and other trailing tokens carry no D-4 column.
                    }
                }
            }
            routes.add(new ParsedRoute(destination, nextHop, interfaceName, protocolOf(protocolToken), routeTable));
        }
        return routes;
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
