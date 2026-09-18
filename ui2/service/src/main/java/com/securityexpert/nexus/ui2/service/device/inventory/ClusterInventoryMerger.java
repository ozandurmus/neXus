package com.securityexpert.nexus.ui2.service.device.inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;

/**
 * 14C D-5 / 13F CL-2: the pure function that unifies a cluster's member
 * runs into one {@code GET /clusters/{cluster_member_ref}/inventory} view.
 * Never stores anything -- WORKER.md invariant "the view is computed,
 * never stored." Every method here is deterministic in its inputs alone
 * (member order, run contents): given the same arguments twice, it returns
 * an equal result, so ordering below is by explicit sort key, never by
 * map/set iteration order.
 *
 * <p>Join keys (WORKER.md "Service read model"): interfaces by
 * {@code (context, name)}, routes by
 * {@code (context, destination, next_hop, interface)}. A row present on
 * every cluster member gets {@link Presence.All}; otherwise {@link
 * Presence.Members} names exactly the member device ids that have it. A
 * scalar field ({@code kind}/{@code state} for an interface, {@code
 * protocol} for a route) that disagrees across members is reported once
 * per diverging member in {@code differences}, against a baseline value
 * taken from the first member (in the caller's own member order) that
 * carries the row -- never averaged, invented, or silently dropped.</p>
 *
 * <p>VIPs (14C D-5, invariant "VIPs shown as data on the cluster row,
 * never as a member address"): only {@code cluster_virtual}-role addresses
 * are folded onto the merged interface's own {@code addresses} list,
 * de-duplicated by {@code (address, family)} -- a VIP reported identically
 * by every member therefore appears exactly once. A member's own {@code
 * member}-role address is never copied onto the cluster row; it stays
 * visible only through that device's own {@code GET /devices/{id}/
 * inventory}.</p>
 */
public final class ClusterInventoryMerger {

    private ClusterInventoryMerger() {
    }

    public sealed interface Presence {
        record All() implements Presence {
        }

        record Members(List<String> deviceIds) implements Presence {
        }
    }

    public record Difference(String deviceId, String field, String value) {
    }

    public record MergedInterface(String name, String kind, List<InventoryAddress> addresses, Presence presence,
            List<Difference> differences, Map<String, List<InventoryAddress>> memberAddresses,
            Map<String, String> memberStates) {
        public MergedInterface(String name, String kind, List<InventoryAddress> addresses, Presence presence,
                List<Difference> differences) {
            this(name, kind, addresses, presence, differences, Map.of(), Map.of());
        }
    }

    public record MergedRoute(String destination, Optional<String> nextHop, Optional<String> interfaceName,
            String protocol, Presence presence, List<Difference> differences) {
    }

    public record MergedContext(String context, List<MergedInterface> interfaces, List<MergedRoute> routes) {
    }

    /**
     * @param memberDeviceIdsInOrder every member of the cluster, in the
     *                               caller's own deterministic order (the
     *                               order used both for presence's "every
     *                               member" denominator and as the
     *                               difference baseline's tie-break)
     * @param latestRunByDeviceId    the latest {@link InventoryRun} for
     *                               whichever of those members have one --
     *                               a member absent from this map has
     *                               never been collected and so can never
     *                               contribute a row nor appear in any
     *                               row's presence
     */
    public static List<MergedContext> merge(List<String> memberDeviceIdsInOrder,
            Map<String, InventoryRun> latestRunByDeviceId) {
        Objects.requireNonNull(memberDeviceIdsInOrder, "memberDeviceIdsInOrder");
        Objects.requireNonNull(latestRunByDeviceId, "latestRunByDeviceId");

        // deviceId -> context -> InventoryContext, restricted to members that were actually collected.
        Map<String, Map<String, InventoryContext>> contextsByDevice = new LinkedHashMap<>();
        TreeSet<String> contextNames = new TreeSet<>();
        for (String deviceId : memberDeviceIdsInOrder) {
            InventoryRun run = latestRunByDeviceId.get(deviceId);
            if (run == null) {
                continue;
            }
            Map<String, InventoryContext> byContext = new LinkedHashMap<>();
            for (InventoryContext context : run.contexts()) {
                byContext.put(context.context(), context);
                contextNames.add(context.context());
            }
            contextsByDevice.put(deviceId, byContext);
        }

        List<MergedContext> merged = new ArrayList<>();
        for (String contextName : contextNames) {
            merged.add(mergeContext(contextName, memberDeviceIdsInOrder, contextsByDevice));
        }
        return merged;
    }

    private static MergedContext mergeContext(String contextName, List<String> memberOrder,
            Map<String, Map<String, InventoryContext>> contextsByDevice) {
        List<MergedInterface> interfaces = mergeInterfaces(contextName, memberOrder, contextsByDevice);
        List<MergedRoute> routes = mergeRoutes(contextName, memberOrder, contextsByDevice);
        return new MergedContext(contextName, interfaces, routes);
    }

    private static List<MergedInterface> mergeInterfaces(String contextName, List<String> memberOrder,
            Map<String, Map<String, InventoryContext>> contextsByDevice) {
        // interface name -> deviceId -> that member's own row.
        Map<String, Map<String, InventoryInterface>> byName = new TreeMap<>();
        for (String deviceId : memberOrder) {
            InventoryContext context = contextForMember(contextsByDevice, deviceId, contextName);
            if (context == null) {
                continue;
            }
            for (InventoryInterface iface : context.interfaces()) {
                byName.computeIfAbsent(iface.name(), key -> new LinkedHashMap<>()).put(deviceId, iface);
            }
        }

        List<MergedInterface> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, InventoryInterface>> entry : byName.entrySet()) {
            Map<String, InventoryInterface> byDevice = entry.getValue();
            Presence presence = presenceOf(memberOrder, byDevice.keySet());

            List<Difference> differences = new ArrayList<>();
            String kind = canonicalAndDifferences(memberOrder, byDevice, InventoryInterface::kind, "kind", differences);
            canonicalAndDifferences(memberOrder, byDevice, InventoryInterface::state, "state", differences);

            List<InventoryAddress> vips = mergeVirtualAddresses(memberOrder, byDevice);
            Map<String, List<InventoryAddress>> memberAddresses = new LinkedHashMap<>();
            Map<String, String> memberStates = new LinkedHashMap<>();
            for (String deviceId : memberOrder) {
                InventoryInterface iface = byDevice.get(deviceId);
                if (iface != null) {
                    memberStates.put(deviceId, iface.state());
                    List<InventoryAddress> nonVip = iface.addresses().stream()
                            .filter(a -> !"cluster_virtual".equals(a.role()))
                            .toList();
                    memberAddresses.put(deviceId, nonVip);
                }
            }
            result.add(new MergedInterface(entry.getKey(), kind, vips, presence, differences, memberAddresses, memberStates));
        }
        return result;
    }

    private static List<MergedRoute> mergeRoutes(String contextName, List<String> memberOrder,
            Map<String, Map<String, InventoryContext>> contextsByDevice) {
        record RouteKey(String destination, String nextHop, String interfaceName) {
        }

        Map<RouteKey, Map<String, InventoryRoute>> byKey = new TreeMap<>(
                Comparator.comparing(RouteKey::destination)
                        .thenComparing(RouteKey::nextHop)
                        .thenComparing(RouteKey::interfaceName));
        for (String deviceId : memberOrder) {
            InventoryContext context = contextForMember(contextsByDevice, deviceId, contextName);
            if (context == null) {
                continue;
            }
            for (InventoryRoute route : context.routes()) {
                RouteKey key = new RouteKey(route.destination(), route.nextHop().orElse(""),
                        route.interfaceName().orElse(""));
                byKey.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(deviceId, route);
            }
        }

        List<MergedRoute> result = new ArrayList<>();
        for (Map.Entry<RouteKey, Map<String, InventoryRoute>> entry : byKey.entrySet()) {
            Map<String, InventoryRoute> byDevice = entry.getValue();
            Presence presence = presenceOf(memberOrder, byDevice.keySet());

            List<Difference> differences = new ArrayList<>();
            String protocol =
                    canonicalAndDifferences(memberOrder, byDevice, InventoryRoute::protocol, "protocol", differences);

            RouteKey key = entry.getKey();
            result.add(new MergedRoute(key.destination(), optionalOf(key.nextHop()), optionalOf(key.interfaceName()),
                    protocol, presence, differences));
        }
        return result;
    }

    private static InventoryContext contextForMember(Map<String, Map<String, InventoryContext>> contextsByDevice,
            String deviceId, String contextName) {
        Map<String, InventoryContext> byContext = contextsByDevice.get(deviceId);
        return byContext == null ? null : byContext.get(contextName);
    }

    private static Presence presenceOf(List<String> memberOrder, java.util.Set<String> haveIt) {
        if (haveIt.size() == memberOrder.size() && haveIt.containsAll(memberOrder)) {
            return new Presence.All();
        }
        List<String> ordered = memberOrder.stream().filter(haveIt::contains).toList();
        return new Presence.Members(ordered);
    }

    /**
     * @return the baseline value (the first member's, in {@code
     *         memberOrder}, that carries this row); every other member
     *         whose own value disagrees is appended to {@code differences}
     *         as {@code (deviceId, field, thatMember'sValue)}.
     */
    private static <T> String canonicalAndDifferences(List<String> memberOrder, Map<String, T> byDevice,
            java.util.function.Function<T, String> fieldReader, String fieldName, List<Difference> differences) {
        String baseline = null;
        for (String deviceId : memberOrder) {
            T row = byDevice.get(deviceId);
            if (row == null) {
                continue;
            }
            if (baseline == null) {
                baseline = fieldReader.apply(row);
                continue;
            }
            String value = fieldReader.apply(row);
            if (!Objects.equals(value, baseline)) {
                differences.add(new Difference(deviceId, fieldName, value));
            }
        }
        return baseline;
    }

    private static List<InventoryAddress> mergeVirtualAddresses(List<String> memberOrder,
            Map<String, InventoryInterface> byDevice) {
        // (address, family) -> the address, de-duplicated across members reporting the identical VIP.
        Map<String, InventoryAddress> byAddressFamily = new TreeMap<>();
        for (String deviceId : memberOrder) {
            InventoryInterface iface = byDevice.get(deviceId);
            if (iface == null) {
                continue;
            }
            for (InventoryAddress address : iface.addresses()) {
                if (!InventoryAddress.ROLE_CLUSTER_VIRTUAL.equals(address.role())) {
                    continue;
                }
                byAddressFamily.putIfAbsent(address.address() + "|" + address.family(), address);
            }
        }
        return List.copyOf(byAddressFamily.values());
    }

    private static Optional<String> optionalOf(String value) {
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
