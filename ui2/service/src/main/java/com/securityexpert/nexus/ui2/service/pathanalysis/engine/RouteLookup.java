package com.securityexpert.nexus.ui2.service.pathanalysis.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.Ipv4Prefix;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;

/**
 * Longest-prefix match over one unit's routes (the active member's main table / virtual router as A3 selected them).
 * Rules, all fail-closed:
 * <ul>
 * <li>{@code is_active = false} is not a candidate; {@code is_active} NULL is a candidate whose selection taints the
 *     hop ({@link PathReasons#ROUTE_ACTIVE_UNKNOWN}).</li>
 * <li>An IPv6 row is ignored; any other row whose destination does not parse taints every lookup of the unit
 *     ({@link PathReasons#ROUTE_TABLE_UNPARSEABLE}): it might be the more specific route.</li>
 * <li>Several equally specific candidates: all {@code is_ecmp = true} = ECMP; otherwise the lowest metric when every
 *     metric is known; anything else keeps them all and taints ({@link PathReasons#ROUTE_SELECTION_AMBIGUOUS}).</li>
 * </ul>
 * Candidates come back sorted by next hop (numeric), interface and route id, so branch order is deterministic.
 */
final class RouteLookup {
    private RouteLookup() {
    }

    record Result(List<InventoryRoute> routes, Optional<Ipv4Prefix> prefix, List<String> taints) {
        Result {
            routes = List.copyOf(routes);
            taints = List.copyOf(taints);
        }

        boolean ecmp() {
            return routes.size() > 1;
        }
    }

    static Result lookup(TopologyGraph.Node node, long address) {
        boolean unparseable = false;
        int best = -1;
        List<InventoryRoute> candidates = new ArrayList<>();
        for (InventoryRoute route : node.routes()) {
            if (route.active().filter(a -> !a).isPresent()) {
                continue;
            }
            String destination = route.destination().strip();
            if (destination.contains(":")) {
                continue;
            }
            Optional<Ipv4Prefix> prefix = Ipv4Prefix.parse(destination);
            if (prefix.isEmpty()) {
                unparseable = true;
                continue;
            }
            if (!prefix.get().contains(address)) {
                continue;
            }
            int length = prefix.get().length();
            if (length > best) {
                best = length;
                candidates.clear();
            }
            if (length == best) {
                candidates.add(route);
            }
        }
        List<String> taints = new ArrayList<>();
        if (unparseable) {
            taints.add(PathReasons.ROUTE_TABLE_UNPARSEABLE);
        }
        if (candidates.isEmpty()) {
            return new Result(List.of(), Optional.empty(), taints);
        }
        candidates = dedupe(candidates);
        if (candidates.size() > 1 && !candidates.stream().allMatch(r -> r.ecmp().orElse(false))) {
            if (candidates.stream().allMatch(r -> r.metric().isPresent())) {
                long min = candidates.stream().mapToLong(r -> r.metric().get()).min().orElseThrow();
                candidates = candidates.stream().filter(r -> r.metric().get() == min).toList();
            }
            if (candidates.size() > 1 && !candidates.stream().allMatch(r -> r.ecmp().orElse(false))) {
                taints.add(PathReasons.ROUTE_SELECTION_AMBIGUOUS);
            }
        }
        if (candidates.stream().anyMatch(r -> r.active().isEmpty())) {
            taints.add(PathReasons.ROUTE_ACTIVE_UNKNOWN);
        }
        List<InventoryRoute> sorted = new ArrayList<>(candidates);
        sorted.sort(ORDER);
        int length = best;
        return new Result(sorted, Optional.of(new Ipv4Prefix(address, length)), taints);
    }

    /** One route per (next hop, interface, kind): the same forwarding written twice is one choice. */
    private static List<InventoryRoute> dedupe(List<InventoryRoute> routes) {
        Map<String, InventoryRoute> unique = new LinkedHashMap<>();
        for (InventoryRoute r : routes.stream().sorted(Comparator.comparing(InventoryRoute::routeId)).toList()) {
            unique.putIfAbsent(r.nextHop().map(String::strip).orElse("") + "|" + r.interfaceName().map(String::strip).orElse("")
                    + "|" + r.nextHopKind().orElse(""), r);
        }
        return List.copyOf(unique.values());
    }

    private static final Comparator<InventoryRoute> ORDER = Comparator
            .comparingLong((InventoryRoute r) -> r.nextHop().flatMap(Ipv4Prefix::parseHost).map(Ipv4Prefix.HostAddress::host)
                    .orElse(Long.MAX_VALUE))
            .thenComparing(r -> r.nextHop().orElse(""))
            .thenComparing(r -> r.interfaceName().orElse(""))
            .thenComparing(InventoryRoute::routeId);
}
