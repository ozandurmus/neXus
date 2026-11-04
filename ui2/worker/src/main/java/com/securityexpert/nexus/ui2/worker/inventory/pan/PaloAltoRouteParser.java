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
 *
 * <p>V184 (path analysis A1, A0 {@code PATH_ANALYSIS_VENDOR_SEMANTICS.md} §5b, FROZEN): the RIB view lists inactive
 * routes, so {@code active} is the {@code A} token (a route without it is inactive) whenever the entry carries a
 * non-blank {@code flags} leaf; {@code ecmp} is the {@code E} token only when this same response's own {@code
 * result/flags} legend declares {@code E:ecmp} -- the legacy legend is not documented to carry it, so without that
 * declaration ECMP stays UNKNOWN; {@code metric} is the {@code metric} leaf (PP-2) when it is a plain unsigned number.
 * Next-hop kind: {@code C} is {@code CONNECTED}; a literal IPv4 next hop other than {@code 0.0.0.0} is {@code IP};
 * anything else (a next-vr or discard next hop, whose shape no measurement records) is UNKNOWN. {@code A} is necessary,
 * not sufficient, for forwarding: the FIB (A6) is the forwarding truth.</p>
 */
public final class PaloAltoRouteParser {

    private static final Pattern ENTRY = Pattern.compile("(?is)<entry>(.*?)</entry>");
    private static final Pattern DESTINATION = tag("destination");
    private static final Pattern NEXTHOP = tag("nexthop");
    private static final Pattern INTERFACE = tag("interface");
    private static final Pattern VIRTUAL_ROUTER = tag("virtual-router");
    private static final Pattern FLAGS = tag("flags");
    private static final Pattern METRIC = tag("metric");
    private static final Pattern LEGEND_DECLARES_ECMP = Pattern.compile("(?:^|[\\s,])E\\s*:\\s*(?i:ecmp)\\b");
    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");
    private static final Pattern UNSIGNED = Pattern.compile("^\\d{1,10}$");

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
        boolean legendDeclaresEcmp = firstMatch(FLAGS, ENTRY.matcher(responseBody).replaceAll(""))
                .map(legend -> LEGEND_DECLARES_ECMP.matcher(legend).find())
                .orElse(false);
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
            Optional<String> flags = firstMatch(FLAGS, body).filter(f -> !f.isBlank());
            String protocol = protocolOf(flags.orElse(""));
            String virtualRouter = routeTable.orElse("");
            List<String> tokens = flags.map(f -> List.of(f.trim().split("\\s+"))).orElse(List.of());
            Optional<Boolean> active = flags.map(f -> tokens.contains("A"));
            Optional<Boolean> ecmp = flags.isPresent() && legendDeclaresEcmp
                    ? Optional.of(tokens.contains("E")) : Optional.empty();
            Optional<Long> metric = firstMatch(METRIC, body).filter(m -> UNSIGNED.matcher(m).matches())
                    .map(Long::parseLong).filter(m -> m <= 4_294_967_295L);
            Optional<String> nextHopKind = tokens.contains("C") ? Optional.of(InventoryRoute.NEXT_HOP_CONNECTED)
                    : nextHop.filter(h -> IPV4_LITERAL.matcher(h).matches()).map(h -> InventoryRoute.NEXT_HOP_IP);

            byVirtualRouter.computeIfAbsent(virtualRouter, key -> new ArrayList<>())
                    .add(new ParsedRoute(destination, nextHop, interfaceName, protocol, routeTable, active, ecmp,
                            metric, nextHopKind));
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
