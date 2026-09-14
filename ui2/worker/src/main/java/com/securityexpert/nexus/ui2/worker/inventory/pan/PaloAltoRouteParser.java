package com.securityexpert.nexus.ui2.worker.inventory.pan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedRoute;

/**
 * {@code show routing route} (14E PF-1), {@code result/entry} (14E PP-2).
 * Rows are directly under {@code result} -- a {@code result/flags} legend
 * line sits outside any {@code entry} and is never matched by the
 * per-entry regex below, so it is never mistaken for a route (PP-2).
 *
 * <p>{@code flags} is tokenised on whitespace, not treated as a single
 * concatenated letter run (PP-2's own correction over the pre-measurement
 * guess): {@code A} active, {@code C} connected, {@code S} static,
 * {@code H} host, {@code R} rip, {@code O}/{@code Oi}/{@code Oo}/
 * {@code O1}/{@code O2} ospf, {@code B} bgp; {@code E}, {@code M},
 * {@code ?}, {@code ~} are non-protocol qualifiers and are never mapped to
 * a protocol. Routes are grouped by their own {@code virtual-router} leaf
 * (PM-3: "routes belong to a virtual router") -- vsys attribution is the
 * executor's job (PM-3's virtual-router-to-vsys mapping via {@link
 * PaloAltoInterfaceParser}), not this parser's.</p>
 */
public final class PaloAltoRouteParser {

    private static final Pattern ENTRY = Pattern.compile("(?is)<entry>(.*?)</entry>");
    private static final Pattern DESTINATION = tag("destination");
    private static final Pattern NEXTHOP = tag("nexthop");
    private static final Pattern INTERFACE = tag("interface");
    private static final Pattern VIRTUAL_ROUTER = tag("virtual-router");
    private static final Pattern FLAGS = tag("flags");

    private PaloAltoRouteParser() {
    }

    private static Pattern tag(String name) {
        return Pattern.compile("(?is)<" + name + ">\\s*([^<]*?)\\s*</" + name + ">");
    }

    /** @return every route, grouped by its own {@code virtual-router} leaf (kept as the route table, PM-3). */
    public static Map<String, List<ParsedRoute>> parse(String responseBody) {
        Map<String, List<ParsedRoute>> byVirtualRouter = new LinkedHashMap<>();
        if (responseBody == null || responseBody.isBlank()) {
            return byVirtualRouter;
        }
        Matcher entries = ENTRY.matcher(responseBody);
        while (entries.find()) {
            String body = entries.group(1);
            String destination = firstMatch(DESTINATION, body).orElse(null);
            if (destination == null) {
                continue;
            }
            Optional<String> interfaceName = firstMatch(INTERFACE, body).filter(v -> !v.isBlank());
            Optional<String> nextHop = firstMatch(NEXTHOP, body).filter(v -> !"0.0.0.0".equals(v));
            Optional<String> routeTable = firstMatch(VIRTUAL_ROUTER, body);
            String protocol = protocolOf(firstMatch(FLAGS, body).orElse(""));
            String virtualRouter = routeTable.orElse("");

            byVirtualRouter.computeIfAbsent(virtualRouter, key -> new ArrayList<>())
                    .add(new ParsedRoute(destination, nextHop, interfaceName, protocol, routeTable));
        }
        return byVirtualRouter;
    }

    private static String protocolOf(String flags) {
        for (String token : flags.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if ("C".equals(token)) {
                return InventoryRoute.PROTOCOL_CONNECTED;
            }
            if ("S".equals(token)) {
                return InventoryRoute.PROTOCOL_STATIC;
            }
            if ("H".equals(token)) {
                return InventoryRoute.PROTOCOL_HOST;
            }
            if ("R".equals(token)) {
                return InventoryRoute.PROTOCOL_RIP;
            }
            if (token.startsWith("O")) {
                return InventoryRoute.PROTOCOL_OSPF;
            }
            if ("B".equals(token)) {
                return InventoryRoute.PROTOCOL_BGP;
            }
            // "A" (active), "E" (ecmp), "M" (multicast), "?" (loose), "~" (internal) are
            // qualifiers, never a protocol (PP-2) -- fall through to the next token.
        }
        return InventoryRoute.PROTOCOL_UNKNOWN;
    }

    private static Optional<String> firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1).trim()) : Optional.empty();
    }
}
