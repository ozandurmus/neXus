package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.Attachment;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.Conflict;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.ConflictKind;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.Member;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.NoMatchReason;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.Node;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.NodeFlag;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.NodeKind;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.OverrideResult;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.OverrideStatus;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.Resolution;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.Segment;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.SegmentKind;

/**
 * Stage A3 topology construction (PATH_ANALYSIS_DESIGN.md §4, FROZEN; F§3 steps with the A§3 additions). A pure,
 * deterministic function of {@link TopologyInput}: the same input (salt included) gives an equal graph. It reads parsed
 * inventory rows only and contacts no device.
 *
 * <ol>
 *   <li><b>Units.</b> Devices sharing an enrolled {@code cluster_member_ref} form one cluster unit; its HA facts pick
 *       the active member whose tables route for the unit. VIP equality across members corroborates the unit, it never
 *       identifies one. A unit with one collected member is {@code CLUSTER_UNCORROBORATED}; no single active member
 *       is {@code ACTIVE_MEMBER_UNPROVEN} (routes then stay empty: nothing is routed on an unproven table).</li>
 *   <li><b>Nodes</b> are {@code (unit, context, routing table)}: Check Point evaluates the {@code main} table (other
 *       Gaia tables -> {@code PBR_PRESENT}); a Palo Alto node per virtual router.</li>
 *   <li><b>Attachments.</b> A Check Point cluster unit attaches through its {@code cluster_virtual} addresses (member
 *       addresses are evidence only); a Palo Alto HA pair through its {@code member} addresses (PAN has no VIP role);
 *       a standalone unit through its own addresses. A VIP without a prefix takes the prefix of the single connected
 *       route in the same context containing it, else {@code ADDRESS_PREFIX_UNKNOWN}.</li>
 *   <li><b>Segments.</b> Equal canonical prefixes join. A prefix that is not globally reachable (IANA special-purpose
 *       registry: private, shared, documentation, ...) joins only units a route corroborates (a next hop on one equals
 *       the other's address on that prefix) or an override joins; anything else stays apart as
 *       {@code DUPLICATE_PRIVATE_PREFIX} -- never auto-joined. {@code /30}-{@code /31} with two units is a direct
 *       edge. Overlapping unequal prefixes on different units are {@code OVERLAPPING_PREFIX}, no link. VSX
 *       {@code wrp*} links stay their own cloud segment.</li>
 *   <li><b>Overrides</b> apply last; one that no longer matches is reported, never dropped.</li>
 *   <li><b>Next hops.</b> A route whose next hop no enrolled unit holds exits into a cloud on that segment.</li>
 * </ol>
 */
public final class TopologyBuilder {

    static final String MAIN_TABLE = "main";
    /** iproute2's kernel-reserved local table holds local/broadcast rows only; it is not policy routing. */
    private static final Set<String> NOT_PBR_TABLES = Set.of(MAIN_TABLE, "local");
    private static final String WARP_PREFIX = "wrp";

    private TopologyBuilder() {
    }

    public static TopologyGraph build(TopologyInput input) {
        return new Run(input).build();
    }

    /** True for the HA roles that make a member the routing member ({@code ACTIVE}, any case). */
    static boolean isActiveRole(String role) {
        return role != null && "ACTIVE".equals(role.strip().toUpperCase(Locale.ROOT));
    }

    private static final class Run {
        private final TopologyInput input;
        private final Mac mac;
        private final Map<String, Node> nodesByKey = new TreeMap<>();
        private final Map<String, Set<String>> interfacesByNode = new LinkedHashMap<>();
        private final List<Candidate> candidates = new ArrayList<>();
        private final List<Conflict> conflicts = new ArrayList<>();
        private final Set<String> conflictIds = new LinkedHashSet<>();
        private final Map<String, Node> nodesById = new LinkedHashMap<>();
        /** Node id -> canonical key: orders pairs independently of the salted ids, so only the ids vary with the salt. */
        private final Map<String, String> nodeKeyOf = new LinkedHashMap<>();
        private final Comparator<String> byNodeKey = Comparator.comparing((String id) -> nodeKeyOf.getOrDefault(id, id));

        /** One attachable address of a node, before segments exist. */
        private record Candidate(String nodeId, String interfaceName, Ipv4Prefix.HostAddress host, Ipv4Prefix prefix,
                String role, Optional<String> zone, boolean corroborated, boolean warp, Optional<String> grade) {
            String key() {
                return nodeId + "|" + interfaceName + "|" + host.hostText() + "/" + prefix.length();
            }
        }

        Run(TopologyInput input) {
            this.input = input;
            try {
                mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(input.idSalt(), "HmacSHA256"));
            } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                throw new IllegalStateException("HmacSHA256 unavailable", e);
            }
        }

        private String id(String prefix, String canonicalKey) {
            byte[] digest = mac.doFinal(canonicalKey.getBytes(StandardCharsets.UTF_8));
            return prefix + HexFormat.of().formatHex(digest, 0, 10);
        }

        TopologyGraph build() {
            List<TopologyInput.DeviceFacts> devices = input.devices().stream()
                    .filter(d -> TopologyInput.VENDOR_CHECK_POINT.equals(d.vendor())
                            || TopologyInput.VENDOR_PALO_ALTO.equals(d.vendor()))
                    .sorted(Comparator.comparing(TopologyInput.DeviceFacts::deviceId)).toList();
            int withoutInventory = (int) devices.stream().filter(d -> d.latestRun().isEmpty()).count();

            Map<String, List<TopologyInput.DeviceFacts>> units = new TreeMap<>();
            for (TopologyInput.DeviceFacts device : devices) {
                String unitKey = device.clusterMemberRef().map(r -> "c:" + r).orElse("d:" + device.deviceId());
                units.computeIfAbsent(unitKey, k -> new ArrayList<>()).add(device);
            }
            units.forEach(this::buildUnit);

            List<Segment> segments = new ArrayList<>();
            List<Attachment> attachments = new ArrayList<>();
            List<OverrideResult> overrideResults = new ArrayList<>();
            buildSegments(segments, attachments, overrideResults);
            detectOverlaps(segments, attachments);

            List<TopologyGraph.NextHopEdge> edges = new ArrayList<>();
            resolveNextHops(segments, attachments, edges);

            List<Node> nodes = new ArrayList<>(nodesById.values());
            nodes.sort(Comparator.comparing(Node::nodeId));
            segments.sort(Comparator.comparing(Segment::segmentId));
            attachments.sort(Comparator.comparing(Attachment::attachmentId));
            conflicts.sort(Comparator.comparing(Conflict::conflictId));
            overrideResults.sort(Comparator.comparing(OverrideResult::overrideId));
            edges.sort(Comparator.comparing(TopologyGraph.NextHopEdge::fromNodeId)
                    .thenComparing(TopologyGraph.NextHopEdge::segmentId).thenComparing(TopologyGraph.NextHopEdge::toNodeId));

            int applied = (int) overrideResults.stream().filter(r -> r.status() != OverrideStatus.NO_MATCH).count();
            var counts = new TopologyGraph.Counts(devices.size(), withoutInventory, nodes.size(),
                    (int) nodes.stream().filter(n -> n.kind() == NodeKind.CLUSTER_UNIT).count(),
                    (int) nodes.stream().filter(n -> n.kind() == NodeKind.CLOUD).count(), segments.size(),
                    attachments.size(), conflicts.size(), applied, overrideResults.size() - applied);
            return new TopologyGraph(input.snapshotId(), input.builtAt(), nodes, segments, attachments, conflicts,
                    overrideResults, edges, counts, fingerprints(devices));
        }

        // ---- step 1/2: units, nodes, candidate attachments -------------------------------------------------------

        private void buildUnit(String unitKey, List<TopologyInput.DeviceFacts> group) {
            List<TopologyInput.DeviceFacts> collected = group.stream().filter(d -> d.latestRun().isPresent()).toList();
            if (collected.isEmpty()) {
                return;
            }
            boolean cluster = unitKey.startsWith("c:");
            String vendor = collected.get(0).vendor();
            boolean checkPoint = TopologyInput.VENDOR_CHECK_POINT.equals(vendor);
            Optional<String> clusterRef = cluster ? group.get(0).clusterMemberRef() : Optional.empty();

            Set<String> contexts = new TreeSet<>();
            collected.forEach(d -> d.latestRun().get().contexts().forEach(c -> contexts.add(c.context())));

            if (cluster) {
                clusterDisagreement(collected, contexts, checkPoint);
            }
            for (String context : contexts) {
                Optional<TopologyInput.DeviceFacts> routingMember = routingMember(cluster, collected, context);
                Set<String> tables = new TreeSet<>();
                if (checkPoint) {
                    tables.add(MAIN_TABLE);
                } else {
                    for (TopologyInput.DeviceFacts d : collected) {
                        contextOf(d, context).ifPresent(c -> {
                            c.routes().forEach(r -> r.routeTable().filter(t -> !t.isBlank()).ifPresent(tables::add));
                            c.interfaces().forEach(i -> i.routingContext().ifPresent(tables::add));
                        });
                    }
                }
                List<Optional<String>> nodeTables = tables.isEmpty() ? List.of(Optional.empty())
                        : tables.stream().map(Optional::of).toList();
                for (Optional<String> table : nodeTables) {
                    buildNode(unitKey, cluster, clusterRef, vendor, context, table, group, collected, routingMember,
                            tables.size());
                }
            }
        }

        private void buildNode(String unitKey, boolean cluster, Optional<String> clusterRef, String vendor, String context,
                Optional<String> table, List<TopologyInput.DeviceFacts> group, List<TopologyInput.DeviceFacts> collected,
                Optional<TopologyInput.DeviceFacts> routingMember, int tableCount) {
            boolean checkPoint = TopologyInput.VENDOR_CHECK_POINT.equals(vendor);
            String nodeKey = unitKey + "|" + context + "|" + table.orElse("");
            String nodeId = id("tn-", "node|" + nodeKey);
            EnumSet<NodeFlag> flags = EnumSet.noneOf(NodeFlag.class);
            if (cluster && (collected.size() < 2 || group.size() < 2)) {
                flags.add(NodeFlag.CLUSTER_UNCORROBORATED);
            }
            if (routingMember.isEmpty()) {
                flags.add(NodeFlag.ACTIVE_MEMBER_UNPROVEN);
            }
            if (cluster && disagreeingClusters.contains(unitKey)) {
                flags.add(NodeFlag.CLUSTER_MEMBERS_DISAGREE);
            }

            List<InventoryRoute> routes = routingMember.flatMap(m -> contextOf(m, context))
                    .map(c -> routesOf(c, table, checkPoint)).orElse(List.of());
            if (checkPoint) {
                boolean pbr = (routingMember.isPresent() ? List.of(routingMember.get()) : collected).stream()
                        .flatMap(d -> contextOf(d, context).stream()).flatMap(c -> c.routes().stream())
                        .anyMatch(r -> r.routeTable().filter(t -> !t.isBlank() && !NOT_PBR_TABLES.contains(t)).isPresent());
                if (pbr) {
                    flags.add(NodeFlag.PBR_PRESENT);
                }
            }
            if (cluster && routingMember.isPresent()) {
                Set<String> activeRoutes = canonicalRoutes(routes);
                for (TopologyInput.DeviceFacts peer : collected) {
                    if (peer.deviceId().equals(routingMember.get().deviceId())) {
                        continue;
                    }
                    Set<String> peerRoutes = contextOf(peer, context).map(c -> canonicalRoutes(routesOf(c, table, checkPoint)))
                            .orElse(Set.of());
                    if (!peerRoutes.equals(activeRoutes)) {
                        flags.add(NodeFlag.HA_ROUTE_DRIFT);
                        conflict(ConflictKind.HA_ROUTE_DRIFT, Optional.of(routingMember.get().deviceId()),
                                Optional.of(peer.deviceId()), Optional.of(context), Optional.empty(), Optional.empty(),
                                List.of(nodeId));
                    }
                }
            }

            List<Member> members = new ArrayList<>();
            for (TopologyInput.DeviceFacts d : group) {
                members.add(new Member(d.deviceId(), d.hostname(), d.latestRun().flatMap(r -> haRole(r, context)),
                        d.latestRun().map(InventoryRun::runId), d.latestRun().map(InventoryRun::collectedAt)));
            }

            Set<String> interfaceNames = new TreeSet<>();
            Map<String, Candidate> nodeCandidates = new TreeMap<>();
            for (TopologyInput.DeviceFacts d : collected) {
                Optional<InventoryContext> ctx = contextOf(d, context);
                if (ctx.isEmpty()) {
                    continue;
                }
                for (InventoryInterface iface : ctx.get().interfaces()) {
                    if (!checkPoint && !belongsToTable(iface, table, tableCount)) {
                        if (iface.routingContext().isEmpty() && tableCount > 1) {
                            // Raised once per device and context (conflict ids are deduplicated).
                            conflict(ConflictKind.INTERFACE_WITHOUT_CONTEXT, Optional.of(d.deviceId()), Optional.empty(),
                                    Optional.of(context), Optional.empty(), Optional.empty(), List.of());
                        }
                        continue;
                    }
                    interfaceNames.add(iface.name());
                    if (iface.evidenceGrade().filter(InventoryInterface.GRADE_CONFIG_DERIVED::equals).isPresent()) {
                        flags.add(NodeFlag.CONFIG_DERIVED_INTERFACES);
                    }
                    for (InventoryAddress address : iface.addresses()) {
                        if (!InventoryAddress.FAMILY_IPV4.equals(address.family()) || !attaches(cluster, checkPoint, address)) {
                            continue;
                        }
                        Optional<Ipv4Prefix.HostAddress> host = Ipv4Prefix.parseHost(address.address());
                        if (host.isEmpty()) {
                            continue;
                        }
                        Optional<Ipv4Prefix> prefix = host.get().prefix()
                                .or(() -> connectedPrefix(ctx.get(), iface.name(), host.get().host()));
                        if (prefix.isEmpty()) {
                            conflict(ConflictKind.ADDRESS_PREFIX_UNKNOWN, Optional.of(d.deviceId()), Optional.empty(),
                                    Optional.of(context), Optional.empty(), Optional.empty(), List.of(nodeId));
                            continue;
                        }
                        Ipv4Prefix.HostAddress withLength = new Ipv4Prefix.HostAddress(host.get().host(),
                                Optional.of(prefix.get().length()));
                        boolean corroborated = ctx.get().routes().stream().anyMatch(r -> isConnected(r)
                                && r.interfaceName().filter(iface.name()::equals).isPresent()
                                && Ipv4Prefix.parse(r.destination()).filter(prefix.get()::equals).isPresent());
                        boolean warp = checkPoint && iface.name().toLowerCase(Locale.ROOT).startsWith(WARP_PREFIX);
                        Candidate c = new Candidate(nodeId, iface.name(), withLength, prefix.get(), address.role(),
                                iface.zone(), corroborated, warp, iface.evidenceGrade());
                        nodeCandidates.merge(c.key(), c, (a, b) -> a.corroborated() ? a : b);
                    }
                }
            }
            candidates.addAll(nodeCandidates.values());
            interfacesByNode.put(nodeId, interfaceNames);
            Node node = new Node(nodeId, cluster ? NodeKind.CLUSTER_UNIT : NodeKind.ROUTING_CONTEXT, vendor, clusterRef,
                    context, table, members, routingMember.map(TopologyInput.DeviceFacts::deviceId), flags, routes);
            nodesByKey.put(nodeKey, node);
            nodesById.put(nodeId, node);
            nodeKeyOf.put(nodeId, nodeKey);
        }

        private final Set<String> disagreeingClusters = new TreeSet<>();

        /** Members of one enrolled cluster that report no cluster, or Check Point members whose VIP sets differ. */
        private void clusterDisagreement(List<TopologyInput.DeviceFacts> collected, Set<String> contexts, boolean checkPoint) {
            String unitKey = "c:" + collected.get(0).clusterMemberRef().orElseThrow();
            for (TopologyInput.DeviceFacts d : collected) {
                boolean standalone = d.latestRun().get().haFacts().stream()
                        .anyMatch(f -> InventoryContext.PHYSICAL.equals(f.context())
                                && "STANDALONE".equalsIgnoreCase(f.role().strip()));
                if (standalone) {
                    disagreeingClusters.add(unitKey);
                    conflict(ConflictKind.CLUSTER_MEMBERS_DISAGREE, Optional.of(d.deviceId()), Optional.empty(),
                            Optional.of(InventoryContext.PHYSICAL), Optional.empty(), Optional.empty(), List.of());
                }
            }
            if (!checkPoint || collected.size() < 2) {
                return;
            }
            for (String context : contexts) {
                TopologyInput.DeviceFacts first = collected.get(0);
                Set<String> baseline = vips(first, context);
                for (TopologyInput.DeviceFacts other : collected.subList(1, collected.size())) {
                    if (!vips(other, context).equals(baseline)) {
                        disagreeingClusters.add(unitKey);
                        conflict(ConflictKind.CLUSTER_MEMBERS_DISAGREE, Optional.of(first.deviceId()),
                                Optional.of(other.deviceId()), Optional.of(context), Optional.empty(), Optional.empty(),
                                List.of());
                    }
                }
            }
        }

        private static Set<String> vips(TopologyInput.DeviceFacts d, String context) {
            Set<String> out = new TreeSet<>();
            contextOf(d, context).ifPresent(c -> c.interfaces().forEach(i -> i.addresses().stream()
                    .filter(a -> InventoryAddress.ROLE_CLUSTER_VIRTUAL.equals(a.role()))
                    .forEach(a -> out.add(i.name() + "|" + Ipv4Prefix.parseHost(a.address())
                            .map(Ipv4Prefix.HostAddress::hostText).orElse(a.address())))));
            return out;
        }

        /** A standalone unit routes on its own tables; a cluster unit on its single provable ACTIVE member. */
        private static Optional<TopologyInput.DeviceFacts> routingMember(boolean cluster,
                List<TopologyInput.DeviceFacts> collected, String context) {
            if (!cluster) {
                return Optional.of(collected.get(0));
            }
            List<TopologyInput.DeviceFacts> active = collected.stream()
                    .filter(d -> haRole(d.latestRun().get(), context).filter(TopologyBuilder::isActiveRole).isPresent())
                    .toList();
            return active.size() == 1 ? Optional.of(active.get(0)) : Optional.empty();
        }

        /** The context's own HA fact (VSX per-VS), else the physical one; empty = UNKNOWN. */
        private static Optional<String> haRole(InventoryRun run, String context) {
            return run.haFacts().stream().filter(f -> f.context().equals(context)).map(InventoryHaFact::role).findFirst()
                    .or(() -> run.haFacts().stream().filter(f -> InventoryContext.PHYSICAL.equals(f.context()))
                            .map(InventoryHaFact::role).findFirst());
        }

        private static boolean attaches(boolean cluster, boolean checkPoint, InventoryAddress address) {
            if (cluster && checkPoint) {
                return InventoryAddress.ROLE_CLUSTER_VIRTUAL.equals(address.role());
            }
            return InventoryAddress.ROLE_MEMBER.equals(address.role())
                    || InventoryAddress.ROLE_CLUSTER_VIRTUAL.equals(address.role()) && checkPoint;
        }

        private static boolean belongsToTable(InventoryInterface iface, Optional<String> table, int tableCount) {
            if (iface.routingContext().isPresent()) {
                return iface.routingContext().equals(table);
            }
            // No virtual router recorded: attachable only when the context has a single routing instance.
            return tableCount <= 1;
        }

        private static List<InventoryRoute> routesOf(InventoryContext context, Optional<String> table, boolean checkPoint) {
            return context.routes().stream().filter(r -> checkPoint
                    ? r.routeTable().filter(t -> !t.isBlank()).map(MAIN_TABLE::equals).orElse(true)
                    : table.isEmpty() || r.routeTable().equals(table)).toList();
        }

        private static Set<String> canonicalRoutes(List<InventoryRoute> routes) {
            Set<String> out = new TreeSet<>();
            for (InventoryRoute r : routes) {
                if (r.active().filter(a -> !a).isPresent()) {
                    continue;
                }
                String destination = Ipv4Prefix.parse(r.destination()).map(Ipv4Prefix::toString).orElse(r.destination());
                out.add(destination + "|" + r.nextHop().map(String::strip).orElse("") + "|"
                        + r.interfaceName().map(String::strip).orElse(""));
            }
            return out;
        }

        private static boolean isConnected(InventoryRoute r) {
            return InventoryRoute.PROTOCOL_CONNECTED.equals(r.protocol())
                    || r.nextHopKind().filter(InventoryRoute.NEXT_HOP_CONNECTED::equals).isPresent();
        }

        /** The single connected route on this interface in this context whose network contains the address (G5). */
        private static Optional<Ipv4Prefix> connectedPrefix(InventoryContext context, String interfaceName, long host) {
            Set<Ipv4Prefix> found = new TreeSet<>();
            for (InventoryRoute r : context.routes()) {
                if (isConnected(r) && r.interfaceName().filter(interfaceName::equals).isPresent()) {
                    Ipv4Prefix.parse(r.destination()).filter(p -> p.contains(host) && p.length() < 32).ifPresent(found::add);
                }
            }
            return found.size() == 1 ? Optional.of(found.iterator().next()) : Optional.empty();
        }

        private static Optional<InventoryContext> contextOf(TopologyInput.DeviceFacts d, String context) {
            return d.latestRun().flatMap(r -> r.contexts().stream().filter(c -> c.context().equals(context)).findFirst());
        }

        // ---- step 3/5: segments and overrides ------------------------------------------------------------------

        /** Union-find over node ids, scoped to one prefix. */
        private static final class Components {
            private final Map<String, String> parent = new TreeMap<>();

            void add(String n) {
                parent.putIfAbsent(n, n);
            }

            String find(String n) {
                String p = parent.get(n);
                if (p.equals(n)) {
                    return n;
                }
                String root = find(p);
                parent.put(n, root);
                return root;
            }

            void union(String a, String b) {
                String ra = find(a);
                String rb = find(b);
                if (!ra.equals(rb)) {
                    if (ra.compareTo(rb) < 0) {
                        parent.put(rb, ra);
                    } else {
                        parent.put(ra, rb);
                    }
                }
            }
        }

        private record Endpoint(String nodeId, String interfaceName) {
        }

        private record PlannedOverride(TopologyLinkOverride override, Endpoint a, Endpoint b) {
        }

        private void buildSegments(List<Segment> segments, List<Attachment> attachments, List<OverrideResult> results) {
            // Resolve every active override's ends first; an unmatched override is reported and changes nothing.
            List<PlannedOverride> planned = new ArrayList<>();
            List<TopologyLinkOverride> active = input.overrides().stream().filter(TopologyLinkOverride::active)
                    .sorted(Comparator.comparing(TopologyLinkOverride::overrideId)).toList();
            for (TopologyLinkOverride o : active) {
                if (o.orphaned()) {
                    noMatch(results, o, NoMatchReason.DEVICE_REMOVED);
                    continue;
                }
                List<String> aNodes = endpointNodes(o.aDeviceId().orElseThrow(), o.aContext(), o.aInterfaceName());
                List<String> bNodes = endpointNodes(o.bDeviceId().orElseThrow(), o.bContext(), o.bInterfaceName());
                if (aNodes.isEmpty() || bNodes.isEmpty()) {
                    noMatch(results, o, NoMatchReason.ENDPOINT_NOT_FOUND);
                } else if (aNodes.size() > 1 || bNodes.size() > 1) {
                    noMatch(results, o, NoMatchReason.ENDPOINT_AMBIGUOUS);
                } else if (aNodes.get(0).equals(bNodes.get(0))) {
                    noMatch(results, o, NoMatchReason.SAME_NODE);
                } else {
                    planned.add(new PlannedOverride(o, new Endpoint(aNodes.get(0), o.aInterfaceName()),
                            new Endpoint(bNodes.get(0), o.bInterfaceName())));
                }
            }

            Map<Ipv4Prefix, List<Candidate>> byPrefix = new TreeMap<>();
            List<Candidate> warps = new ArrayList<>();
            for (Candidate c : candidates) {
                (c.warp() ? warps : byPrefix.computeIfAbsent(c.prefix(), k -> new ArrayList<>())).add(c);
            }
            Map<String, OverrideStatus> statuses = new TreeMap<>();
            Map<String, String> segmentOfCandidate = new LinkedHashMap<>();

            for (Map.Entry<Ipv4Prefix, List<Candidate>> group : byPrefix.entrySet()) {
                Ipv4Prefix prefix = group.getKey();
                List<Candidate> members = group.getValue();
                Set<String> nodes = new TreeSet<>(byNodeKey);
                members.forEach(c -> nodes.add(c.nodeId()));
                Components comps = new Components();
                nodes.forEach(comps::add);
                List<String> nodeList = List.copyOf(nodes);
                if (prefix.globallyReachable()) {
                    nodeList.forEach(n -> comps.union(nodeList.get(0), n));
                } else {
                    for (int i = 0; i < nodeList.size(); i++) {
                        for (int j = i + 1; j < nodeList.size(); j++) {
                            if (corroborated(nodeList.get(i), nodeList.get(j), members)) {
                                comps.union(nodeList.get(i), nodeList.get(j));
                            }
                        }
                    }
                }
                int componentsBefore = (int) nodes.stream().map(comps::find).distinct().count();
                Set<List<String>> declaredApart = new TreeSet<>(Comparator.comparing(Object::toString));
                Set<String> touching = new TreeSet<>();
                for (PlannedOverride p : planned) {
                    boolean aHere = members.stream().anyMatch(c -> c.nodeId().equals(p.a().nodeId())
                            && c.interfaceName().equals(p.a().interfaceName()));
                    boolean bHere = members.stream().anyMatch(c -> c.nodeId().equals(p.b().nodeId())
                            && c.interfaceName().equals(p.b().interfaceName()));
                    if (!aHere || !bHere) {
                        continue;
                    }
                    String id = p.override().overrideId();
                    touching.add(id);
                    if (TopologyLinkOverride.ACTION_FORCE_LINK.equals(p.override().action())) {
                        boolean already = comps.find(p.a().nodeId()).equals(comps.find(p.b().nodeId()));
                        comps.union(p.a().nodeId(), p.b().nodeId());
                        statuses.put(id, already ? OverrideStatus.NO_EFFECT : OverrideStatus.APPLIED);
                    } else {
                        declaredApart.add(List.of(p.a().nodeId(), p.b().nodeId()));
                        statuses.put(id, OverrideStatus.NO_EFFECT);
                    }
                }
                // SUPPRESS_LINK: the b end leaves a component it shares with the a end.
                Set<String> isolated = new TreeSet<>();
                for (PlannedOverride p : planned) {
                    if (TopologyLinkOverride.ACTION_SUPPRESS_LINK.equals(p.override().action())
                            && declaredApart.contains(List.of(p.a().nodeId(), p.b().nodeId()))
                            && comps.find(p.a().nodeId()).equals(comps.find(p.b().nodeId()))) {
                        isolated.add(p.b().nodeId());
                        statuses.put(p.override().overrideId(), OverrideStatus.APPLIED);
                    }
                }
                Map<String, List<String>> componentNodes = new TreeMap<>();
                for (String n : nodes) {
                    String root = isolated.contains(n) ? "iso|" + n : comps.find(n);
                    componentNodes.computeIfAbsent(root, k -> new ArrayList<>()).add(n);
                }
                if (!prefix.globallyReachable() && componentsBefore > 1) {
                    boolean resolved = componentNodes.size() == 1 || allPairsDeclaredApart(componentNodes, declaredApart);
                    conflict(ConflictKind.DUPLICATE_PRIVATE_PREFIX, firstMember(nodeList.get(0)),
                            firstMember(nodeList.get(1)), Optional.empty(), Optional.of(prefix),
                            resolved ? touching.stream().findFirst() : Optional.empty(),
                            nodeList, resolved ? Resolution.OVERRIDDEN : Resolution.OPEN);
                }
                for (List<String> componentMembers : componentNodes.values()) {
                    SegmentKind kind = componentMembers.size() == 1 ? SegmentKind.STUB
                            : componentMembers.size() == 2 && prefix.pointToPoint() ? SegmentKind.POINT_TO_POINT
                            : SegmentKind.SHARED;
                    String segmentId = id("ts-", "segment|" + prefix + "|" + String.join(",", componentMembers));
                    segments.add(new Segment(segmentId, Optional.of(prefix), kind, Optional.empty()));
                    Map<String, String> hostOwner = new TreeMap<>();
                    for (Candidate c : members) {
                        if (!componentMembers.contains(c.nodeId())) {
                            continue;
                        }
                        segmentOfCandidate.put(c.key(), segmentId);
                        attachments.add(toAttachment(c, segmentId));
                        String owner = hostOwner.putIfAbsent(c.host().hostText(), c.nodeId());
                        if (owner != null && !owner.equals(c.nodeId())) {
                            conflict(ConflictKind.DUPLICATE_ADDRESS, firstMember(owner), firstMember(c.nodeId()),
                                    Optional.empty(), Optional.of(prefix), Optional.empty(), List.of(owner, c.nodeId()));
                        }
                    }
                }
            }

            for (Candidate c : warps) {
                String segmentId = id("ts-", "warp|" + c.key());
                segments.add(new Segment(segmentId, Optional.of(c.prefix()), SegmentKind.VSX_WARP, Optional.empty()));
                attachments.add(toAttachment(c, segmentId));
                segmentOfCandidate.put(c.key(), segmentId);
            }

            // FORCE_LINK whose ends are not on one prefix: a declared (manual) segment between the two ends.
            for (PlannedOverride p : planned) {
                String id = p.override().overrideId();
                if (statuses.containsKey(id)) {
                    continue;
                }
                if (TopologyLinkOverride.ACTION_SUPPRESS_LINK.equals(p.override().action())) {
                    statuses.put(id, OverrideStatus.NO_EFFECT);
                    continue;
                }
                Optional<Ipv4Prefix> assumed = p.override().assumedSubnet().flatMap(Ipv4Prefix::parse);
                String segmentId = id("ts-", "manual|" + id);
                segments.add(new Segment(segmentId, assumed, SegmentKind.MANUAL, Optional.of(id)));
                for (Endpoint e : List.of(p.a(), p.b())) {
                    attachments.add(new Attachment(id("ta-", "manual|" + id + "|" + e.nodeId()), e.nodeId(), segmentId,
                            Optional.of(e.interfaceName()), Optional.empty(), Optional.empty(), Optional.empty(), false,
                            Attachment.Source.OVERRIDE, Optional.empty()));
                }
                statuses.put(id, OverrideStatus.APPLIED);
            }
            statuses.forEach((id, status) -> results.add(new OverrideResult(id, status, Optional.empty())));
        }

        private static boolean allPairsDeclaredApart(Map<String, List<String>> componentNodes, Set<List<String>> apart) {
            List<List<String>> comps = List.copyOf(componentNodes.values());
            for (int i = 0; i < comps.size(); i++) {
                for (int j = i + 1; j < comps.size(); j++) {
                    boolean declared = false;
                    for (String a : comps.get(i)) {
                        for (String b : comps.get(j)) {
                            declared |= apart.contains(List.of(a, b)) || apart.contains(List.of(b, a));
                        }
                    }
                    if (!declared) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** A route on one node whose next hop is exactly the other node's address on this prefix (either way). */
        private boolean corroborated(String a, String b, List<Candidate> members) {
            return pointsAt(a, b, members) || pointsAt(b, a, members);
        }

        private final Map<String, Set<String>> nextHopHosts = new LinkedHashMap<>();

        private boolean pointsAt(String from, String to, List<Candidate> members) {
            Set<String> hops = nextHopHosts.computeIfAbsent(from, n -> {
                Set<String> out = new TreeSet<>();
                nodesById.get(n).routes().stream().filter(r -> r.active().orElse(true))
                        .map(InventoryRoute::nextHop).flatMap(Optional::stream)
                        .map(Ipv4Prefix::parseHost).flatMap(Optional::stream)
                        .forEach(h -> out.add(h.hostText()));
                return out;
            });
            return members.stream().anyMatch(c -> c.nodeId().equals(to) && hops.contains(c.host().hostText()));
        }

        private Attachment toAttachment(Candidate c, String segmentId) {
            return new Attachment(id("ta-", "attachment|" + c.key()), c.nodeId(), segmentId, Optional.of(c.interfaceName()),
                    Optional.of(c.host().hostText() + "/" + c.prefix().length()), Optional.of(c.role()), c.zone(),
                    c.corroborated(), Attachment.Source.INVENTORY, c.grade());
        }

        private List<String> endpointNodes(String deviceId, String context, String interfaceName) {
            List<String> out = new ArrayList<>();
            for (Node n : nodesById.values()) {
                if (n.context().equals(context) && n.members().stream().anyMatch(m -> m.deviceId().equals(deviceId))
                        && interfacesByNode.getOrDefault(n.nodeId(), Set.of()).contains(interfaceName)) {
                    out.add(n.nodeId());
                }
            }
            return out;
        }

        private void noMatch(List<OverrideResult> results, TopologyLinkOverride o, NoMatchReason reason) {
            results.add(new OverrideResult(o.overrideId(), OverrideStatus.NO_MATCH, Optional.of(reason)));
            conflict(ConflictKind.OVERRIDE_NO_MATCH, o.aDeviceId(), o.bDeviceId(), Optional.empty(), Optional.empty(),
                    Optional.of(o.overrideId()), List.of());
        }

        private Optional<String> firstMember(String nodeId) {
            Node n = nodesById.get(nodeId);
            return n == null ? Optional.empty()
                    : n.activeMemberDeviceId().or(() -> n.members().stream().map(Member::deviceId).findFirst());
        }

        /** Unequal overlapping prefixes on different units (e.g. a /24 and a /25): a conflict, never a link. */
        private void detectOverlaps(List<Segment> segments, List<Attachment> attachments) {
            Map<Ipv4Prefix, Set<String>> nodesByPrefix = new TreeMap<>();
            Map<String, Segment> byId = new LinkedHashMap<>();
            segments.forEach(s -> byId.put(s.segmentId(), s));
            for (Attachment a : attachments) {
                Segment s = byId.get(a.segmentId());
                if (s.kind() == SegmentKind.VSX_WARP || s.kind() == SegmentKind.MANUAL || s.network().isEmpty()
                        || s.network().get().length() == 32) {
                    continue;
                }
                nodesByPrefix.computeIfAbsent(s.network().get(), k -> new TreeSet<>(byNodeKey)).add(a.nodeId());
            }
            List<Ipv4Prefix> open = new ArrayList<>();
            for (Ipv4Prefix p : nodesByPrefix.keySet()) {
                open.removeIf(q -> !q.contains(p));
                for (Ipv4Prefix outer : open) {
                    Set<String> outerNodes = nodesByPrefix.get(outer);
                    Set<String> innerNodes = nodesByPrefix.get(p);
                    // One conflict per pair of prefixes, naming the first pair of different units (bounded output).
                    pairs:
                    for (String o : outerNodes) {
                        for (String i : innerNodes) {
                            if (!o.equals(i)) {
                                conflict(ConflictKind.OVERLAPPING_PREFIX, firstMember(o), firstMember(i), Optional.empty(),
                                        Optional.of(outer), Optional.empty(), List.of(o, i));
                                break pairs;
                            }
                        }
                    }
                }
                open.add(p);
            }
        }

        // ---- step 6: next hops and clouds ------------------------------------------------------------------------

        private void resolveNextHops(List<Segment> segments, List<Attachment> attachments,
                List<TopologyGraph.NextHopEdge> edges) {
            Map<String, Segment> segmentById = new LinkedHashMap<>();
            segments.forEach(s -> segmentById.put(s.segmentId(), s));
            Map<String, String> cloudOfSegment = new TreeMap<>();
            for (Segment s : segments) {
                if (s.kind() == SegmentKind.VSX_WARP) {
                    cloudOfSegment.put(s.segmentId(), cloud(s, attachments, EnumSet.of(NodeFlag.VSX_WARP_CLOUD)));
                }
            }
            // Holders never change below: a cloud carries no address, so one index serves the whole pass.
            NextHopResolver resolver = new NextHopResolver(List.copyOf(nodesById.values()), segments, attachments);
            List<Node> routing = nodesById.values().stream().filter(n -> n.kind() != NodeKind.CLOUD)
                    .sorted(Comparator.comparing(Node::nodeId)).toList();
            Map<String, Integer> edgeCounts = new TreeMap<>();
            for (Node node : routing) {
                boolean unreachable = false;
                Set<String> ambiguous = new TreeSet<>();
                for (InventoryRoute route : node.routes()) {
                    if (route.active().filter(a -> !a).isPresent() || route.nextHop().isEmpty()
                            || route.nextHopKind().filter(k -> !InventoryRoute.NEXT_HOP_IP.equals(k)).isPresent()) {
                        continue;
                    }
                    TopologyGraph.NextHop hop = resolver.resolve(node.nodeId(), route.interfaceName(), route.nextHop().get());
                    switch (hop) {
                        case TopologyGraph.NextHop.ToNode to ->
                            edgeCounts.merge(node.nodeId() + "|" + to.segmentId() + "|" + to.nodeId(), 1, Integer::sum);
                        case TopologyGraph.NextHop.ToCloud to ->
                            edgeCounts.merge(node.nodeId() + "|" + to.segmentId() + "|" + to.cloudNodeId(), 1, Integer::sum);
                        case TopologyGraph.NextHop.Unheld unheld -> {
                            String cloudId = cloudOfSegment.computeIfAbsent(unheld.segmentId(),
                                    k -> cloud(segmentById.get(k), attachments, EnumSet.noneOf(NodeFlag.class)));
                            edgeCounts.merge(node.nodeId() + "|" + unheld.segmentId() + "|" + cloudId, 1, Integer::sum);
                        }
                        case TopologyGraph.NextHop.Ambiguous amb -> ambiguous.add(amb.segmentId());
                        case TopologyGraph.NextHop.Unreachable u -> unreachable = true;
                    }
                }
                if (unreachable) {
                    conflict(ConflictKind.NEXT_HOP_UNREACHABLE, firstMember(node.nodeId()), Optional.empty(),
                            Optional.of(node.context()), Optional.empty(), Optional.empty(), List.of(node.nodeId()));
                }
                for (String segmentId : ambiguous) {
                    conflict(ConflictKind.NEXT_HOP_AMBIGUOUS, firstMember(node.nodeId()), Optional.empty(),
                            Optional.of(node.context()), segmentById.get(segmentId).network(), Optional.empty(),
                            List.of(node.nodeId()));
                }
            }
            edgeCounts.forEach((key, count) -> {
                String[] parts = key.split("\\|");
                edges.add(new TopologyGraph.NextHopEdge(parts[0], parts[1], parts[2], count));
            });
        }

        /** One cloud node per segment: an unknown device (or a VSX virtual switch) the estate does not enrol. */
        private String cloud(Segment segment, List<Attachment> attachments, EnumSet<NodeFlag> flags) {
            String nodeId = id("tn-", "cloud|" + segment.segmentId());
            nodesById.put(nodeId, new Node(nodeId, NodeKind.CLOUD, "unknown", Optional.empty(), "", Optional.empty(),
                    List.of(), Optional.empty(), flags, List.of()));
            attachments.add(new Attachment(id("ta-", "cloud|" + segment.segmentId()), nodeId, segment.segmentId(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false,
                    Attachment.Source.INVENTORY, Optional.empty()));
            return nodeId;
        }

        // ---- conflicts and fingerprints --------------------------------------------------------------------------

        private void conflict(ConflictKind kind, Optional<String> device, Optional<String> peer, Optional<String> context,
                Optional<Ipv4Prefix> network, Optional<String> overrideId, List<String> nodeIds) {
            conflict(kind, device, peer, context, network, overrideId, nodeIds, Resolution.OPEN);
        }

        private void conflict(ConflictKind kind, Optional<String> device, Optional<String> peer, Optional<String> context,
                Optional<Ipv4Prefix> network, Optional<String> overrideId, List<String> nodeIds, Resolution resolution) {
            String key = "conflict|" + kind + "|" + device.orElse("") + "|" + peer.orElse("") + "|" + context.orElse("")
                    + "|" + network.map(Ipv4Prefix::toString).orElse("") + "|" + overrideId.orElse("") + "|"
                    + String.join(",", nodeIds);
            String conflictId = id("tc-", key);
            if (conflictIds.add(conflictId)) {
                conflicts.add(new Conflict(conflictId, kind, device, peer, context, network, overrideId, nodeIds,
                        resolution));
            }
        }

        private TopologyGraph.Fingerprints fingerprints(List<TopologyInput.DeviceFacts> devices) {
            List<String> inventory = new ArrayList<>();
            for (TopologyInput.DeviceFacts d : devices) {
                inventory.add(inventoryLine(d.deviceId(), d.latestRun().map(InventoryRun::runId),
                        d.latestRun().map(InventoryRun::collectedAt), d.clusterMemberRef()));
            }
            List<String> overrides = new ArrayList<>();
            for (TopologyLinkOverride o : input.overrides()) {
                overrides.add(o.overrideId() + "|" + o.revokedAt().map(Object::toString).orElse("") + "|"
                        + o.orphanedAt().map(Object::toString).orElse(""));
            }
            overrides.sort(null);
            return new TopologyGraph.Fingerprints(inventoryFingerprint(inventory), sha256(String.join("\n", overrides)));
        }
    }

    /** One device's contribution to the inventory fingerprint (shared with the cheap change check in the service). */
    static String inventoryLine(String deviceId, Optional<String> runId, Optional<Instant> collectedAt,
            Optional<String> clusterMemberRef) {
        return deviceId + "|" + runId.orElse("") + "|" + collectedAt.map(Instant::toString).orElse("") + "|"
                + clusterMemberRef.map(TopologyBuilder::sha256).orElse("");
    }

    /** The inventory fingerprint of a set of {@link #inventoryLine} values (order independent). */
    static String inventoryFingerprint(List<String> lines) {
        List<String> sorted = new ArrayList<>(lines);
        sorted.sort(null);
        return sha256(String.join("\n", sorted));
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
