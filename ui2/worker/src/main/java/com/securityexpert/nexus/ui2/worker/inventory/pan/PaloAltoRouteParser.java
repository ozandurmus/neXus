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
 * {@code show routing route} (14C §3), container-anchored on the
 * response's own {@code result} entries (14C §4 baseline: leaves {@code
 * destination}, {@code nexthop}, {@code interface}, {@code
 * virtual-router}, {@code flags}). {@code UNVERIFIED} -- "payload written,
 * shape unproven" (14C §3). Keeps the {@code flags} letters as the
 * protocol token rather than collapsing every dynamic protocol into
 * "static" (14C §4's named correction); {@code nexthop} of {@code
 * 0.0.0.0} means none; the {@code virtual-router} leaf is kept as the
 * route table.
 *
 * <p>Context (vsys) assignment: an {@code interface} value carrying an
 * {@code @vsysN} suffix (as {@code tests/fixtures/panorama/routes.xml}
 * shows for one row) names its own vsys directly; otherwise the
 * (already-parsed) interface-to-vsys map from {@link
 * PaloAltoInterfaceParser#parse} is consulted; a route naming an unknown
 * interface falls back to {@code defaultVsys} -- this shape is explicitly
 * unmeasured (14C §5 M-3/M-4), so this is a documented best-effort
 * reading, never a fabricated certainty.</p>
 */
public final class PaloAltoRouteParser {

    private static final Pattern ENTRY = Pattern.compile("(?is)<entry>(.*?)</entry>");
    private static final Pattern DESTINATION = tag("destination");
    private static final Pattern NEXTHOP = tag("nexthop");
    private static final Pattern INTERFACE = tag("interface");
    private static final Pattern VIRTUAL_ROUTER = tag("virtual-router");
    private static final Pattern FLAGS = tag("flags");
    private static final Pattern VSYS_SUFFIX = Pattern.compile("^(.*)@(vsys\\d+)$");

    private PaloAltoRouteParser() {
    }

    private static Pattern tag(String name) {
        return Pattern.compile("(?is)<" + name + ">\\s*([^<]*?)\\s*</" + name + ">");
    }

    public static Map<String, List<ParsedRoute>> parse(String responseBody, Map<String, String> interfaceNameToVsys,
            String defaultVsys) {
        Map<String, List<ParsedRoute>> byVsys = new LinkedHashMap<>();
        if (responseBody == null || responseBody.isBlank()) {
            return byVsys;
        }
        Matcher entries = ENTRY.matcher(responseBody);
        while (entries.find()) {
            String body = entries.group(1);
            String destination = firstMatch(DESTINATION, body).orElse(null);
            if (destination == null) {
                continue;
            }
            String rawInterface = firstMatch(INTERFACE, body).orElse(null);
            String vsys = defaultVsys;
            Optional<String> interfaceName = Optional.empty();
            if (rawInterface != null && !rawInterface.isBlank()) {
                Matcher suffix = VSYS_SUFFIX.matcher(rawInterface);
                if (suffix.matches()) {
                    interfaceName = Optional.of(suffix.group(1));
                    vsys = suffix.group(2);
                } else {
                    interfaceName = Optional.of(rawInterface);
                    vsys = interfaceNameToVsys.getOrDefault(rawInterface, defaultVsys);
                }
            }

            Optional<String> nextHop = firstMatch(NEXTHOP, body).filter(v -> !"0.0.0.0".equals(v));
            Optional<String> routeTable = firstMatch(VIRTUAL_ROUTER, body);
            String protocol = protocolOf(firstMatch(FLAGS, body).orElse(""));

            byVsys.computeIfAbsent(vsys, key -> new ArrayList<>())
                    .add(new ParsedRoute(destination, nextHop, interfaceName, protocol, routeTable));
        }
        return byVsys;
    }

    private static String protocolOf(String flags) {
        // Priority order when more than one substantive letter is present is unmeasured; a real
        // response mixes at most one of these with the "A"(active)/"E"(ecmp) modifier letters this
        // method ignores (14C §4 baseline: "keeps the flags letters as the protocol token").
        if (flags.contains("S")) {
            return InventoryRoute.PROTOCOL_STATIC;
        }
        if (flags.contains("C")) {
            return InventoryRoute.PROTOCOL_CONNECTED;
        }
        if (flags.contains("O")) {
            return InventoryRoute.PROTOCOL_OSPF;
        }
        if (flags.contains("B")) {
            return InventoryRoute.PROTOCOL_BGP;
        }
        if (flags.contains("R")) {
            return InventoryRoute.PROTOCOL_RIP;
        }
        if (flags.contains("H")) {
            return InventoryRoute.PROTOCOL_HOST;
        }
        return InventoryRoute.PROTOCOL_UNKNOWN;
    }

    private static Optional<String> firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1).trim()) : Optional.empty();
    }
}
