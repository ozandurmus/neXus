package com.securityexpert.nexus.ui2.service.pathanalysis.engine;

import static com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathReasons.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult.Branch;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult.Endpoint;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult.ExecutiveSummary;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult.Hop;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult.HopRef;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult.Ingress;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult.IngressCandidate;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult.NatApplied;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult.ReturnBranch;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult.ReturnHop;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult.ReturnPath;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult.RouteUsed;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult.Security;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.Ipv4Prefix;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.Attachment;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.Node;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.NodeFlag;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.NodeKind;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.Segment;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.SegmentKind;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyInput;
import com.securityexpert.nexus.ui2.service.policy.HopNatEvaluator;
import com.securityexpert.nexus.ui2.service.policy.HopPolicyEvaluator;
import com.securityexpert.nexus.ui2.service.policy.HopPolicyEvaluator.Tuple;

/**
 * Stage A4 path engine (PATH_ANALYSIS_DESIGN.md §5 and §7 A4, FROZEN; F§4.1/4.2/4.7 adopted; PO record 2026-11-03
 * D-2, D-4). A pure, bounded walk over one in-memory {@link TopologyGraph} (A3) that calls the A2 hop rule engine
 * ({@link HopPolicyEvaluator}) at every unit in the vendor order of §5. It reads nothing, persists nothing and contacts
 * no device; the same graph, policies, query and {@code computedAt} give an equal result.
 *
 * <p><b>Per hop.</b> Route lookup is longest prefix over the unit's routes ({@link RouteLookup}: active routes only,
 * {@code is_active} NULL taints the hop, ECMP branches). Check Point: NAT first match and access (dual pre/post-NAT,
 * A2), then the route lookup on the translated destination; when it differs from the lookup on the original destination
 * the hop is UNKNOWN ({@code CP_ROUTE_AFTER_NAT_UNKNOWN}: routing relative to NAT is not collected). Palo Alto: ingress
 * zone of the ingress interface; route lookup on the original destination gives the provisional egress zone; NAT first
 * match; after a destination translation a second route lookup (the A2 callback) gives the post-NAT zone and forwards;
 * security on the pre-NAT addresses with the post-NAT zone. The translated tuple is handed to the next unit.</p>
 *
 * <p><b>Verdict lattice (§5).</b> An UNKNOWN hop taints its branch; tracing continues and downstream hops are
 * {@code CONDITIONAL}. A branch is DENIED only when the blocking hop and every hop before it are decided; DELIVERED with
 * every hop ALLOWED is ALLOWED (caveats carried: a caveated allow is never plain); an exit into a cloud is UNKNOWN
 * ({@code EXITS_KNOWN_TOPOLOGY}) or {@code ALLOWED_TO_EDGE} in edge mode (D-4). Branches (ingress candidates, ECMP: 8 per
 * hop, 16 in total, {@code ecmpTruncated}) combine to the overall verdict; allowed and denied branches make PARTIAL; an
 * unexplored (truncated) branch counts as UNKNOWN. Candidates on equally specific unjoined source segments (design §4
 * amendment 2026-11-05) are all traced: their verdict stands only when every candidate gives the same decided one
 * ({@code INGRESS_CANDIDATES_AGREE}), else UNKNOWN ({@code AMBIGUOUS_INGRESS}).</p>
 *
 * <p><b>Return path.</b> A delivered branch is walked back by routing only, undoing the forward NAT at the units that
 * applied it. If the return crosses a stateful unit (every Phase A unit is one) the forward path did not, or skips one
 * it did, the branch is {@code ASYMMETRIC}: never ALLOWED -- UNKNOWN with {@code OUT_OF_STATE_DROP_LIKELY}. A return walk
 * that ends without proving either way leaves the allow caveated ({@code RETURN_PATH_UNPROVEN}).</p>
 *
 * <p><b>Bounds.</b> Hop limit, loop detection on (unit, packet), a work budget counting unit visits forward and back.
 * Bad data never throws: an unexpected failure is {@code UNKNOWN (ENGINE_DATA_ERROR)} with no detail, so no value can
 * leak through an exception message.</p>
 */
public final class PathEngine {
    private PathEngine() {
    }

    public static PathResult analyze(TopologyGraph graph, HopPolicySource policies, PathQuery query, Instant computedAt) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(computedAt, "computedAt");
        String snapshotId = graph == null ? "" : graph.snapshotId();
        try {
            return new Run(Objects.requireNonNull(graph, "graph"), policies == null ? HopPolicySource.NONE : policies,
                    query, computedAt).run();
        } catch (RuntimeException failure) {
            Ingress ingress = new Ingress(query.ingress().isPresent() ? GIVEN : INFERRED, ENGINE_DATA_ERROR, List.of());
            return new PathResult(query, snapshotId, computedAt, UNKNOWN, ENGINE_DATA_ERROR, false, List.of(), List.of(),
                    ingress, List.of(), false, false, Optional.empty(),
                    new ExecutiveSummary(headline(UNKNOWN, ENGINE_DATA_ERROR, List.of(), Optional.empty(), false), List.of(),
                            List.of(), false, 0, false, "CURRENT"),
                    PathResult.LIMITATIONS);
        }
    }

    // ---- steps: what one route does with a packet at one unit --------------------------------------------------------

    private sealed interface Step {
    }

    private record Deliver(Attachment on) implements Step {
    }

    private record Exit(Optional<Attachment> via) implements Step {
    }

    private record Forward(Node next, Optional<Attachment> egress, Optional<Attachment> ingress) implements Step {
    }

    private record Drop() implements Step {
    }

    private record Stop(String reason, Optional<Attachment> egress) implements Step {
    }

    /** One hop record with the step its route takes. */
    private record Choice(Hop hop, Step step) {
    }

    private record Start(Node node, Optional<Attachment> ingress) {
    }

    /** A finished forward branch before its verdict. */
    private record Finished(List<Hop> hops, String termination, Optional<Attachment> deliveredOn) {
    }

    private record Prefix(List<Hop> hops, Set<String> visited, boolean tainted) {
        Prefix with(Hop hop, String visitKey) {
            List<Hop> h = new ArrayList<>(hops);
            h.add(hop);
            Set<String> v = new HashSet<>(visited);
            v.add(visitKey);
            return new Prefix(h, v, tainted || UNKNOWN.equals(hop.ownVerdict()));
        }
    }

    private static final class Run {
        private final TopologyGraph graph;
        private final HopPolicySource policies;
        private final PathQuery query;
        private final PathQuery.Limits limits;
        private final Instant computedAt;
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final Map<String, Segment> segments = new HashMap<>();
        private final Map<String, List<Attachment>> byNode = new HashMap<>();
        private final Map<String, List<Attachment>> bySegment = new HashMap<>();
        private final Map<String, HopPolicySource.HopPolicy> policyCache = new HashMap<>();
        private int work;
        private int branchesStarted;
        private boolean truncated;
        /** Equally specific unjoined source segments: every candidate is traced, a verdict only when they all agree. */
        private boolean candidatesMustAgree;

        Run(TopologyGraph graph, HopPolicySource policies, PathQuery query, Instant computedAt) {
            this.graph = graph;
            this.policies = policies;
            this.query = query;
            this.limits = query.limits();
            this.computedAt = computedAt;
            graph.nodes().forEach(n -> nodes.put(n.nodeId(), n));
            graph.segments().forEach(s -> segments.put(s.segmentId(), s));
            List<Attachment> sorted = graph.attachments().stream().sorted(Comparator.comparing(Attachment::attachmentId)).toList();
            for (Attachment a : sorted) {
                byNode.computeIfAbsent(a.nodeId(), k -> new ArrayList<>()).add(a);
                bySegment.computeIfAbsent(a.segmentId(), k -> new ArrayList<>()).add(a);
            }
        }

        PathResult run() {
            List<Start> starts = new ArrayList<>();
            Ingress ingress = ingress(starts);
            Tuple tuple = new Tuple(query.source(), query.destination(), query.protocol(), query.destinationPort());
            List<Finished> finished = new ArrayList<>();
            List<Integer> origin = new ArrayList<>();
            for (int s = 0; s < starts.size(); s++) {
                if (branchesStarted >= limits.maxBranches()) {
                    truncated = true;
                    break;
                }
                branchesStarted++;
                Start start = starts.get(s);
                List<Finished> fromStart = explore(new Prefix(List.of(), Set.of(), false), start.node(), start.ingress(), tuple);
                finished.addAll(fromStart);
                for (int i = 0; i < fromStart.size(); i++) {
                    origin.add(s);
                }
            }
            List<Branch> branches = new ArrayList<>();
            Map<String, Integer> startOf = new HashMap<>();
            for (int i = 0; i < finished.size(); i++) {
                Branch b = branch("b" + (branches.size() + 1), finished.get(i));
                branches.add(b);
                startOf.put(b.branchId(), origin.get(i));
            }
            return result(ingress, branches, startOf, starts.size());
        }

        // ---- ingress (F§4.2) ---------------------------------------------------------------------------------------

        private Ingress ingress(List<Start> starts) {
            long source = host(query.source());
            if (query.ingress().isPresent()) {
                var hint = query.ingress().get();
                Node node = nodes.get(hint.nodeId());
                if (node == null || node.kind() == NodeKind.CLOUD) {
                    return new Ingress(GIVEN, INGRESS_HINT_NOT_FOUND, List.of());
                }
                Optional<Attachment> on;
                if (hint.interfaceName().isPresent()) {
                    List<Attachment> named = attachmentsOf(node.nodeId()).stream()
                            .filter(a -> a.interfaceName().equals(hint.interfaceName())).toList();
                    if (named.isEmpty()) {
                        return new Ingress(GIVEN, INGRESS_INTERFACE_NOT_FOUND, List.of());
                    }
                    on = named.stream().filter(a -> network(a).filter(p -> p.contains(source)).isPresent()).findFirst()
                            .or(() -> Optional.of(named.get(0)));
                } else {
                    on = longestContaining(node, source, Optional.empty());
                }
                starts.add(new Start(node, on));
                return new Ingress(GIVEN, "RESOLVED", List.of(candidate(node, on)));
            }
            List<Segment> containing = graph.segments().stream()
                    .filter(s -> s.network().filter(p -> p.contains(source)).isPresent())
                    .filter(s -> attachmentsOn(s.segmentId()).stream().anyMatch(a -> !cloud(a.nodeId()))).toList();
            if (!containing.isEmpty()) {
                int best = containing.stream().mapToInt(s -> s.network().get().length()).max().orElseThrow();
                List<Segment> longest = containing.stream().filter(s -> s.network().get().length() == best)
                        .sorted(Comparator.comparing(Segment::segmentId)).toList();
                List<Start> found = new ArrayList<>();
                for (Segment s : longest) {
                    Set<String> seen = new HashSet<>();
                    for (Attachment a : attachmentsOn(s.segmentId())) {
                        if (!cloud(a.nodeId()) && seen.add(a.nodeId())) {
                            found.add(new Start(nodes.get(a.nodeId()), Optional.of(a)));
                        }
                    }
                }
                found.sort(Comparator.comparing((Start st) -> st.node().nodeId()));
                List<IngressCandidate> candidates = found.stream().map(st -> candidate(st.node(), st.ingress())).toList();
                if (longest.size() > 1) {
                    // Equally specific segments that were never joined (duplicate private prefix, design §4): which one
                    // the source sits on is not known. Nothing is joined and nothing is guessed -- every candidate is
                    // traced, and result() keeps the verdict only when every candidate gives the same decided one.
                    starts.addAll(found);
                    candidatesMustAgree = true;
                    return new Ingress(INFERRED, AMBIGUOUS_INGRESS, candidates);
                }
                starts.addAll(found);
                return new Ingress(INFERRED, "RESOLVED", candidates);
            }
            // Behind a cloud: the units holding the most specific non-default route toward the source into a cloud.
            int best = 0;
            List<Start> found = new ArrayList<>();
            for (Node node : nodes.values()) {
                if (node.kind() == NodeKind.CLOUD) {
                    continue;
                }
                RouteLookup.Result lookup = RouteLookup.lookup(node, source);
                int length = lookup.prefix().map(Ipv4Prefix::length).orElse(0);
                if (length == 0 || length < best) {
                    continue;
                }
                Optional<Attachment> via = Optional.empty();
                boolean exits = false;
                for (InventoryRoute route : lookup.routes()) {
                    if (step(node, route, source) instanceof Exit exit) {
                        exits = true;
                        via = exit.via();
                        break;
                    }
                }
                if (!exits) {
                    continue;
                }
                if (length > best) {
                    best = length;
                    found.clear();
                }
                found.add(new Start(node, via));
            }
            found.sort(Comparator.comparing((Start st) -> st.node().nodeId()));
            List<IngressCandidate> candidates = found.stream().map(st -> candidate(st.node(), st.ingress())).toList();
            if (found.isEmpty()) {
                return new Ingress(INFERRED, INGRESS_NOT_FOUND, List.of());
            }
            if (found.size() > 1) {
                return new Ingress(INFERRED, AMBIGUOUS_INGRESS, candidates);
            }
            starts.addAll(found);
            return new Ingress(INFERRED, "RESOLVED", candidates);
        }

        private static IngressCandidate candidate(Node node, Optional<Attachment> on) {
            return new IngressCandidate(node.nodeId(), on.map(Attachment::attachmentId), on.map(Attachment::segmentId));
        }

        // ---- forward walk ------------------------------------------------------------------------------------------

        private List<Finished> explore(Prefix prefix, Node node, Optional<Attachment> ingress, Tuple tuple) {
            if (++work > limits.workBudget()) {
                return List.of(new Finished(prefix.hops(), ANALYSIS_BUDGET, Optional.empty()));
            }
            if (prefix.hops().size() >= limits.maxHops()) {
                return List.of(new Finished(prefix.hops(), HOP_LIMIT, Optional.empty()));
            }
            String visit = node.nodeId() + "|" + tuple.source() + "|" + tuple.destination() + "|" + tuple.destinationPort();
            if (prefix.visited().contains(visit)) {
                return List.of(new Finished(prefix.hops(), ROUTING_LOOP, Optional.empty()));
            }
            List<Choice> choices = evaluate(node, ingress, tuple, prefix.hops().size() + 1, prefix.tainted());
            if (choices.size() > limits.ecmpPerHop()) {
                truncated = true;
                choices = choices.subList(0, limits.ecmpPerHop());
            }
            List<Finished> out = new ArrayList<>();
            for (int i = 0; i < choices.size(); i++) {
                if (i > 0) {
                    if (branchesStarted >= limits.maxBranches()) {
                        truncated = true;
                        break;
                    }
                    branchesStarted++;
                }
                Choice choice = choices.get(i);
                Prefix next = prefix.with(choice.hop(), visit);
                if (DENIED.equals(choice.hop().ownVerdict())) {
                    out.add(new Finished(next.hops(), BLOCKED, Optional.empty()));
                    continue;
                }
                switch (choice.step()) {
                    case Deliver d -> out.add(new Finished(next.hops(), DELIVERED, Optional.of(d.on())));
                    case Exit e -> out.add(new Finished(next.hops(), EXITS_KNOWN_TOPOLOGY, Optional.empty()));
                    case Forward f -> out.addAll(explore(next, f.next(), f.ingress(), choice.hop().packetOut()));
                    case Drop d -> out.add(new Finished(next.hops(), BLOCKED, Optional.empty()));
                    case Stop s -> out.add(new Finished(next.hops(), s.reason(), Optional.empty()));
                }
            }
            return out;
        }

        /** The hop evaluation of one unit, one choice per forwarding route (at least one choice). */
        private List<Choice> evaluate(Node node, Optional<Attachment> ingress, Tuple tuple, int index, boolean tainted) {
            List<String> nodeReasons = new ArrayList<>(), caveats = new ArrayList<>(), notes = new ArrayList<>();
            if (node.flags().contains(NodeFlag.ACTIVE_MEMBER_UNPROVEN)) {
                nodeReasons.add(ACTIVE_MEMBER_UNPROVEN);
            }
            if (node.flags().contains(NodeFlag.CLUSTER_MEMBERS_DISAGREE)) {
                nodeReasons.add(RELATIONSHIP_INCONSISTENT);
            }
            if (node.flags().contains(NodeFlag.CLUSTER_UNCORROBORATED)) {
                caveats.add(CLUSTER_UNCORROBORATED);
            }
            if (node.flags().contains(NodeFlag.CONFIG_DERIVED_INTERFACES)) {
                caveats.add(CONFIG_DERIVED_INTERFACES);
            }
            if (node.flags().contains(NodeFlag.PBR_PRESENT)) {
                caveats.add(PBR_PRESENT);
            }
            if (node.flags().contains(NodeFlag.HA_ROUTE_DRIFT)) {
                notes.add(HA_ROUTE_DRIFT); // informational only (design §4 amendment 2026-11-04)
            }
            boolean checkPoint = TopologyInput.VENDOR_CHECK_POINT.equals(node.vendor());
            boolean paloAlto = TopologyInput.VENDOR_PALO_ALTO.equals(node.vendor());
            HopPolicySource.HopPolicy policy = policy(node);
            Optional<HopPolicySource.Bound> bound = policy instanceof HopPolicySource.Bound b ? Optional.of(b) : Optional.empty();
            String unboundReason = policy instanceof HopPolicySource.Unbound u ? u.reason() : null;
            if (!checkPoint && !paloAlto) {
                unboundReason = UNSUPPORTED_NODE_VENDOR;
                bound = Optional.empty();
            }
            Optional<String> ingressZone = ingress.flatMap(Attachment::zone);
            Context ctx = new Context(node, ingress, tuple, index, tainted, nodeReasons, caveats, notes, bound, unboundReason);

            if (bound.isPresent() && paloAlto) {
                return paloAlto(ctx, ingressZone.orElse(null));
            }
            HopPolicyEvaluator.Hop hop = null;
            String failure = unboundReason;
            if (bound.isPresent()) {
                try {
                    hop = bound.get().prepared().checkPoint(tuple);
                } catch (RuntimeException e) {
                    failure = POLICY_EVALUATION_FAILED;
                }
            }
            Tuple out = translated(hop, tuple);
            RouteLookup.Result lookup = RouteLookup.lookup(node, host(out.destination()));
            List<String> taints = new ArrayList<>(lookup.taints());
            if (!out.destination().equals(tuple.destination())) {
                RouteLookup.Result original = RouteLookup.lookup(node, host(tuple.destination()));
                if (!forwarding(original).equals(forwarding(lookup))) {
                    taints.add(CP_ROUTE_AFTER_NAT_UNKNOWN);
                }
            }
            return choices(ctx, hop, failure, out, lookup.routes(), taints, null, lookup.ecmp());
        }

        /** Palo Alto (§5): one evaluation per distinct provisional (egress zone, egress interface). */
        private List<Choice> paloAlto(Context ctx, String ingressZone) {
            Node node = ctx.node();
            Tuple tuple = ctx.tuple();
            RouteLookup.Result first = RouteLookup.lookup(node, host(tuple.destination()));
            Map<List<String>, List<InventoryRoute>> groups = new LinkedHashMap<>();
            for (InventoryRoute route : first.routes()) {
                String zone = egressZone(node, route, host(tuple.destination())).orElse(null);
                groups.computeIfAbsent(java.util.Arrays.asList(zone, route.interfaceName().orElse(null)), k -> new ArrayList<>())
                        .add(route);
            }
            if (groups.isEmpty()) {
                groups.put(java.util.Arrays.asList(null, null), List.of());
            }
            Function<String, String> secondLookup = address -> uniqueZone(node, address);
            List<Choice> out = new ArrayList<>();
            for (var group : groups.entrySet()) {
                String zone = group.getKey().get(0), egressInterface = group.getKey().get(1);
                HopPolicyEvaluator.Hop hop = null;
                String failure = null;
                try {
                    hop = ctx.bound().orElseThrow().prepared().paloAlto(tuple, ingressZone, zone, egressInterface, secondLookup);
                } catch (RuntimeException e) {
                    failure = POLICY_EVALUATION_FAILED;
                }
                Tuple translated = translated(hop, tuple);
                List<InventoryRoute> forward = group.getValue();
                List<String> taints = new ArrayList<>(first.taints());
                boolean ecmp = first.ecmp();
                if (!translated.destination().equals(tuple.destination())) {
                    RouteLookup.Result second = RouteLookup.lookup(node, host(translated.destination()));
                    forward = second.routes();
                    taints.addAll(second.taints());
                    ecmp = second.ecmp();
                }
                out.addAll(choices(ctx, hop, failure, translated, forward, taints, zone, ecmp));
            }
            return out;
        }

        private record Context(Node node, Optional<Attachment> ingress, Tuple tuple, int index, boolean tainted,
                List<String> nodeReasons, List<String> caveats, List<String> notes,
                Optional<HopPolicySource.Bound> bound, String unboundReason) {
        }

        private List<Choice> choices(Context ctx, HopPolicyEvaluator.Hop hop, String failure, Tuple out,
                List<InventoryRoute> routes, List<String> taints, String provisionalZone, boolean ecmp) {
            Node node = ctx.node();
            List<Choice> choices = new ArrayList<>();
            if (routes.isEmpty()) {
                Step stop = new Stop(NO_ROUTE, Optional.empty());
                choices.add(new Choice(hop(ctx, hop, failure, out, Optional.empty(), stop, taints, false), stop));
                return choices;
            }
            for (InventoryRoute route : routes) {
                Step step = step(node, route, host(out.destination()));
                choices.add(new Choice(hop(ctx, hop, failure, out, Optional.of(route), step, taints, ecmp), step));
            }
            return choices;
        }

        private Hop hop(Context ctx, HopPolicyEvaluator.Hop hop, String failure, Tuple out, Optional<InventoryRoute> route,
                Step step, List<String> taints, boolean ecmp) {
            Node node = ctx.node();
            boolean checkPoint = TopologyInput.VENDOR_CHECK_POINT.equals(node.vendor());
            boolean stale = ctx.bound().map(HopPolicySource.Bound::stale).orElse(false);
            List<String> reasons = new ArrayList<>();
            List<String> caveats = new ArrayList<>(ctx.caveats());
            List<String> notes = new ArrayList<>(ctx.notes());
            String policyVerdict = UNKNOWN;
            String policyReason = failure != null ? failure : POLICY_BINDING_UNPROVEN;
            if (hop != null) {
                policyVerdict = hop.verdict();
                policyReason = hop.reason();
                caveats.addAll(hop.caveats());
                notes.addAll(hop.notes());
                if (hop.nat() != null && HopNatEvaluator.UNKNOWN.equals(hop.nat().status())) {
                    notes.add(NAT_UNKNOWN_TUPLE_UNCHANGED);
                }
            }
            if (stale) {
                reasons.add(STALE_OR_OUT_OF_SYNC_POLICY);
            }
            if (step instanceof Exit exit) {
                // Why the branch leaves the known topology (informational: the exit itself stays UNKNOWN / edge).
                if (route.flatMap(r -> Ipv4Prefix.parse(r.destination().strip())).filter(p -> p.length() == 0).isPresent()) {
                    notes.add(EXIT_VIA_DEFAULT_ROUTE);
                }
                if (exit.via().map(a -> segments.get(a.segmentId())).filter(s -> s.kind() == SegmentKind.VSX_WARP).isPresent()) {
                    notes.add(EXIT_VIA_VSX_WARP);
                }
            }
            reasons.addAll(ctx.nodeReasons());
            // UNKNOWN causes in order: stale policy, unit facts, policy, routing.
            String own;
            String reason;
            if (step instanceof Drop && taints.isEmpty() && ctx.nodeReasons().isEmpty()) {
                // A proven discard route drops the packet whatever the policy says.
                own = DENIED;
                reason = DENIED.equals(policyVerdict) && !stale ? policyReason : ROUTE_DISCARD;
                if (!reason.equals(ROUTE_DISCARD)) {
                    notes.add(ROUTE_DISCARD);
                }
                reasons = new ArrayList<>(List.of(reason));
            } else if (DENIED.equals(policyVerdict) && !stale && ctx.nodeReasons().isEmpty()
                    && (checkPoint || taints.isEmpty())) {
                // A Check Point access decision does not depend on routing, so a route taint does not undo a deny; a
                // Palo Alto one does (the egress zone came from the route).
                own = DENIED;
                reason = policyReason;
                notes.addAll(taints);
                reasons = new ArrayList<>(List.of(reason));
            } else {
                if (UNKNOWN.equals(policyVerdict)) {
                    reasons.add(policyReason);
                }
                reasons.addAll(taints);
                if (reasons.isEmpty()) {
                    own = ALLOWED;
                    reason = policyReason;
                    reasons.add(reason);
                } else {
                    own = UNKNOWN;
                    reason = reasons.get(0);
                }
            }
            String verdict = ctx.tainted() && !UNKNOWN.equals(own) ? CONDITIONAL : own;
            Set<String> flags = new TreeSet<>();
            node.flags().forEach(f -> flags.add(f.name()));
            Endpoint ingressEnd = endpoint(ctx.ingress(), Optional.empty(), node);
            Endpoint egressEnd = endpoint(egressOf(step), route.flatMap(InventoryRoute::interfaceName), node);
            Optional<RouteUsed> used = route.map(r -> new RouteUsed(r.routeId(), r.destination(), r.nextHop(), r.interfaceName(),
                    r.protocol(), r.nextHopKind(), ecmp));
            Optional<NatApplied> nat = Optional.ofNullable(hop).map(HopPolicyEvaluator.Hop::nat)
                    .map(n -> new NatApplied(n.status(), n.reason(), HopNatEvaluator.TRANSLATED.equals(n.status()), n.rules(),
                            n.undecidableRules()));
            Optional<Security> pre = Optional.ofNullable(hop).map(HopPolicyEvaluator.Hop::preNatAccess).map(Run::security);
            Optional<Security> post = Optional.ofNullable(hop).map(HopPolicyEvaluator.Hop::postNatAccess).map(Run::security);
            return new Hop(ctx.index(), node.nodeId(), node.kind().name(), node.vendor(), node.context(), node.routingTable(),
                    node.activeMemberDeviceId(), ingressEnd, egressEnd, ctx.tuple(), out, used, nat, pre, post, stale,
                    ctx.bound().flatMap(HopPolicySource.Bound::collectedAt), own, verdict, reason, dedupe(reasons),
                    dedupe(caveats), dedupe(notes), flags);
        }

        private static Security security(HopPolicyEvaluator.Verdict v) {
            return new Security(v.verdict(), v.reason(), Optional.ofNullable(v.decidingRule()), v.undecidableRules());
        }

        private static Tuple translated(HopPolicyEvaluator.Hop hop, Tuple tuple) {
            if (hop == null || hop.nat() == null || hop.nat().translated() == null) {
                return tuple;
            }
            return hop.nat().translated();
        }

        private HopPolicySource.HopPolicy policy(Node node) {
            return policyCache.computeIfAbsent(node.nodeId(), id -> {
                try {
                    HopPolicySource.HopPolicy p = policies.policyFor(node);
                    return p == null ? new HopPolicySource.Unbound(POLICY_BINDING_UNPROVEN) : p;
                } catch (RuntimeException e) {
                    return new HopPolicySource.Unbound(POLICY_SOURCE_FAILED);
                }
            });
        }

        // ---- routing helpers -----------------------------------------------------------------------------------------

        /** What forwarding on {@code route} does with a packet to {@code destination} at {@code node}. Pure. */
        private Step step(Node node, InventoryRoute route, long destination) {
            Optional<String> kind = route.nextHopKind();
            if (kind.filter(InventoryRoute.NEXT_HOP_DISCARD::equals).isPresent()) {
                return new Drop();
            }
            if (kind.filter(InventoryRoute.NEXT_HOP_NEXT_VR::equals).isPresent()) {
                return nextVirtualRouter(node, route);
            }
            Optional<String> nextHop = route.nextHop().map(String::strip).filter(s -> !s.isEmpty());
            boolean connected = kind.filter(InventoryRoute.NEXT_HOP_CONNECTED::equals).isPresent()
                    || kind.isEmpty() && (InventoryRoute.PROTOCOL_CONNECTED.equals(route.protocol()) || nextHop.isEmpty());
            if (connected) {
                Optional<Attachment> on = longestContaining(node, destination, route.interfaceName());
                return on.<Step>map(Deliver::new).orElseGet(() -> new Stop(CONNECTED_SEGMENT_UNKNOWN, Optional.empty()));
            }
            if (kind.isPresent() && !InventoryRoute.NEXT_HOP_IP.equals(kind.get()) || nextHop.isEmpty()) {
                return new Stop(NEXT_HOP_KIND_UNKNOWN, Optional.empty());
            }
            Optional<Ipv4Prefix.HostAddress> address = Ipv4Prefix.parseHost(nextHop.get())
                    .filter(h -> h.length().isEmpty() || h.length().get() == 32).filter(h -> h.host() != 0);
            if (address.isEmpty()) {
                return new Stop(NEXT_HOP_KIND_UNKNOWN, Optional.empty());
            }
            String hopText = address.get().hostText();
            TopologyGraph.NextHop resolved = graph.resolveNextHop(node.nodeId(), route.interfaceName(), hopText);
            return switch (resolved) {
                case TopologyGraph.NextHop.ToNode to -> {
                    Node next = nodes.get(to.nodeId());
                    Optional<Attachment> egress = attachmentOn(node.nodeId(), to.segmentId(), route.interfaceName(), Optional.empty());
                    if (next == null || next.kind() == NodeKind.CLOUD) {
                        yield new Exit(egress);
                    }
                    yield new Forward(next, egress, attachmentOn(next.nodeId(), to.segmentId(), Optional.empty(), Optional.of(hopText)));
                }
                case TopologyGraph.NextHop.ToCloud to ->
                    new Exit(attachmentOn(node.nodeId(), to.segmentId(), route.interfaceName(), Optional.empty()));
                case TopologyGraph.NextHop.Unheld unheld ->
                    new Exit(attachmentOn(node.nodeId(), unheld.segmentId(), route.interfaceName(), Optional.empty()));
                case TopologyGraph.NextHop.Ambiguous amb ->
                    new Stop(NEXT_HOP_AMBIGUOUS, attachmentOn(node.nodeId(), amb.segmentId(), route.interfaceName(), Optional.empty()));
                case TopologyGraph.NextHop.Unreachable u -> new Stop(NEXT_HOP_UNREACHABLE, Optional.empty());
            };
        }

        /** A Palo Alto next-vr route: the same unit and context, the virtual router the route names (exact equality). */
        private Step nextVirtualRouter(Node node, InventoryRoute route) {
            Optional<String> table = route.nextHop().map(String::strip).filter(s -> !s.isEmpty());
            if (table.isEmpty()) {
                return new Stop(NEXT_VR_UNRESOLVED, Optional.empty());
            }
            List<Node> found = nodes.values().stream().filter(n -> n.kind() != NodeKind.CLOUD
                    && stateKey(n).equals(stateKey(node)) && n.routingTable().equals(table)
                    && !n.nodeId().equals(node.nodeId())).toList();
            return found.size() == 1 ? new Forward(found.get(0), Optional.empty(), Optional.empty())
                    : new Stop(NEXT_VR_UNRESOLVED, Optional.empty());
        }

        private static Set<String> forwarding(RouteLookup.Result result) {
            Set<String> keys = new TreeSet<>();
            for (InventoryRoute r : result.routes()) {
                keys.add(r.nextHop().map(String::strip).orElse("") + "|" + r.interfaceName().orElse("") + "|"
                        + r.nextHopKind().orElse(""));
            }
            return keys;
        }

        private Optional<String> egressZone(Node node, InventoryRoute route, long destination) {
            Optional<Attachment> via = egressOf(step(node, route, destination));
            Optional<String> zone = via.flatMap(Attachment::zone);
            return zone.isPresent() ? zone : route.interfaceName().flatMap(i -> zoneOf(node, i));
        }

        /** The A2 second-lookup callback: the single post-NAT egress zone, else {@code null} (UNKNOWN). */
        private String uniqueZone(Node node, String address) {
            Optional<Ipv4Prefix.HostAddress> parsed = Ipv4Prefix.parseHost(address);
            if (parsed.isEmpty()) {
                return null;
            }
            RouteLookup.Result lookup = RouteLookup.lookup(node, parsed.get().host());
            Set<String> zones = new TreeSet<>();
            for (InventoryRoute route : lookup.routes()) {
                Optional<String> zone = egressZone(node, route, parsed.get().host());
                if (zone.isEmpty()) {
                    return null;
                }
                zones.add(zone.get());
            }
            return zones.size() == 1 ? zones.iterator().next() : null;
        }

        private Optional<String> zoneOf(Node node, String interfaceName) {
            Set<String> zones = new TreeSet<>();
            attachmentsOf(node.nodeId()).stream().filter(a -> a.interfaceName().filter(interfaceName::equals).isPresent())
                    .forEach(a -> a.zone().filter(z -> !z.isBlank()).ifPresent(zones::add));
            return zones.size() == 1 ? Optional.of(zones.iterator().next()) : Optional.empty();
        }

        private static Optional<Attachment> egressOf(Step step) {
            return switch (step) {
                case Deliver d -> Optional.of(d.on());
                case Exit e -> e.via();
                case Forward f -> f.egress();
                case Stop s -> s.egress();
                case Drop d -> Optional.empty();
            };
        }

        private Endpoint endpoint(Optional<Attachment> attachment, Optional<String> fallbackInterface, Node node) {
            if (attachment.isPresent()) {
                Attachment a = attachment.get();
                return new Endpoint(Optional.of(a.attachmentId()), Optional.of(a.segmentId()), a.interfaceName(), a.zone());
            }
            if (fallbackInterface.isPresent()) {
                return new Endpoint(Optional.empty(), Optional.empty(), fallbackInterface, zoneOf(node, fallbackInterface.get()));
            }
            return Endpoint.NONE;
        }

        /** The node's attachment on the segment; preferring the named interface, then the exact address. */
        private Optional<Attachment> attachmentOn(String nodeId, String segmentId, Optional<String> interfaceName,
                Optional<String> address) {
            List<Attachment> on = attachmentsOn(segmentId).stream().filter(a -> a.nodeId().equals(nodeId)).toList();
            if (on.isEmpty()) {
                return Optional.empty();
            }
            if (interfaceName.isPresent()) {
                Optional<Attachment> named = on.stream().filter(a -> a.interfaceName().equals(interfaceName)).findFirst();
                if (named.isPresent()) {
                    return named;
                }
            }
            if (address.isPresent()) {
                Optional<Attachment> exact = on.stream().filter(a -> a.address().flatMap(Ipv4Prefix::parseHost)
                        .map(Ipv4Prefix.HostAddress::hostText).equals(address)).findFirst();
                if (exact.isPresent()) {
                    return exact;
                }
            }
            return Optional.of(on.get(0));
        }

        /** The node's attachment whose segment is the longest prefix containing the address (on that interface, if named). */
        private Optional<Attachment> longestContaining(Node node, long address, Optional<String> interfaceName) {
            return attachmentsOf(node.nodeId()).stream()
                    .filter(a -> interfaceName.isEmpty() || a.interfaceName().equals(interfaceName))
                    .filter(a -> network(a).filter(p -> p.contains(address)).isPresent())
                    .max(Comparator.comparingInt((Attachment a) -> network(a).get().length())
                            .thenComparing(Attachment::attachmentId, Comparator.reverseOrder()));
        }

        private Optional<Ipv4Prefix> network(Attachment a) {
            Segment s = segments.get(a.segmentId());
            return s == null ? Optional.empty() : s.network();
        }

        private List<Attachment> attachmentsOf(String nodeId) {
            return byNode.getOrDefault(nodeId, List.of());
        }

        private List<Attachment> attachmentsOn(String segmentId) {
            return bySegment.getOrDefault(segmentId, List.of());
        }

        /** True when a segment an enrolled (non-cloud) unit is attached to contains the address. */
        private boolean onKnownSegment(long address) {
            return graph.segments().stream().filter(s -> s.network().filter(p -> p.contains(address)).isPresent())
                    .anyMatch(s -> attachmentsOn(s.segmentId()).stream().anyMatch(a -> !cloud(a.nodeId())));
        }

        /** True when an enrolled (non-cloud) unit holds exactly this address on one of its attachments. */
        private boolean unitAddress(String address) {
            Optional<String> wanted = Ipv4Prefix.parseHost(address).map(Ipv4Prefix.HostAddress::hostText);
            return wanted.isPresent() && graph.attachments().stream().filter(a -> !cloud(a.nodeId()))
                    .anyMatch(a -> a.address().flatMap(Ipv4Prefix::parseHost).map(Ipv4Prefix.HostAddress::hostText).equals(wanted));
        }

        private boolean cloud(String nodeId) {
            Node n = nodes.get(nodeId);
            return n == null || n.kind() == NodeKind.CLOUD;
        }

        /** The stateful unit a node belongs to: cluster or device, and context (opaque, internal only). */
        private static String stateKey(Node node) {
            String unit = node.clusterMemberRef().map(r -> "c:" + r).orElseGet(() -> "d:" + node.members().stream()
                    .map(TopologyGraph.Member::deviceId).sorted().findFirst().orElse(node.nodeId()));
            return unit + "|" + node.context();
        }

        // ---- branch verdict and return path ------------------------------------------------------------------------

        private Branch branch(String id, Finished f) {
            List<Hop> hops = f.hops();
            boolean anyUnknown = hops.stream().anyMatch(h -> UNKNOWN.equals(h.ownVerdict()));
            List<String> caveats = new ArrayList<>(), warnings = new ArrayList<>();
            hops.forEach(h -> caveats.addAll(h.caveats()));
            String verdict;
            String reason;
            Optional<Integer> blocking = Optional.empty();
            ReturnPath returnPath;
            switch (f.termination()) {
                case BLOCKED -> {
                    blocking = Optional.of(hops.get(hops.size() - 1).index());
                    boolean unknownBefore = hops.subList(0, hops.size() - 1).stream().anyMatch(h -> UNKNOWN.equals(h.ownVerdict()));
                    verdict = unknownBefore ? UNKNOWN : DENIED;
                    reason = unknownBefore ? DENIED_AFTER_UNKNOWN_HOP : DENIED_AT_HOP;
                    returnPath = ReturnPath.notComputed(BLOCKED);
                }
                case DELIVERED -> {
                    verdict = anyUnknown ? UNKNOWN : ALLOWED;
                    reason = anyUnknown ? UNKNOWN_HOP : ALL_HOPS_ALLOWED;
                    returnPath = returnPath(f);
                    if (ASYMMETRIC.equals(returnPath.status())) {
                        warnings.add(OUT_OF_STATE_DROP_LIKELY);
                        if (ALLOWED.equals(verdict)) {
                            verdict = UNKNOWN;
                            reason = ASYMMETRIC_RETURN;
                        }
                    } else if (!SYMMETRIC.equals(returnPath.status())) {
                        caveats.add(RETURN_PATH_UNPROVEN);
                    }
                }
                case EXITS_KNOWN_TOPOLOGY -> {
                    if (query.edgeMode()) {
                        verdict = anyUnknown ? UNKNOWN : ALLOWED_TO_EDGE;
                        reason = anyUnknown ? UNKNOWN_HOP : EXITS_KNOWN_TOPOLOGY;
                    } else {
                        verdict = UNKNOWN;
                        reason = EXITS_KNOWN_TOPOLOGY;
                    }
                    returnPath = ReturnPath.notComputed(RETURN_NOT_COMPUTED_AT_EDGE);
                    if (onKnownSegment(host(hops.get(hops.size() - 1).packetOut().destination()))) {
                        warnings.add(DESTINATION_IN_KNOWN_TOPOLOGY);
                    }
                }
                default -> {
                    verdict = UNKNOWN;
                    reason = f.termination();
                    if (NO_ROUTE.equals(reason) && !hops.isEmpty()) {
                        // Say why the table had nothing: withheld on an unproven active member, or nothing collected.
                        Node last = nodes.get(hops.get(hops.size() - 1).nodeId());
                        if (last.flags().contains(NodeFlag.ACTIVE_MEMBER_UNPROVEN)) {
                            reason = ACTIVE_MEMBER_UNPROVEN;
                        } else if (last.routes().isEmpty()) {
                            reason = ROUTE_TABLE_EMPTY;
                        }
                    }
                    returnPath = ReturnPath.notComputed(f.termination());
                }
            }
            return new Branch(id, verdict, reason, f.termination(), hops, returnPath, dedupe(caveats), dedupe(warnings), blocking);
        }

        private ReturnPath returnPath(Finished f) {
            Attachment delivered = f.deliveredOn().orElseThrow();
            Tuple last = f.hops().get(f.hops().size() - 1).packetOut();
            Set<String> forwardUnits = new TreeSet<>();
            Map<String, Hop> forwardAt = new HashMap<>();
            for (Hop h : f.hops()) {
                forwardUnits.add(stateKey(nodes.get(h.nodeId())));
                forwardAt.putIfAbsent(h.nodeId(), h);
            }
            List<ReturnBranch> branches = new ArrayList<>();
            int[] started = {0};
            Set<String> seen = new HashSet<>();
            for (Attachment a : attachmentsOn(delivered.segmentId())) {
                if (cloud(a.nodeId()) || !seen.add(a.nodeId())) {
                    continue;
                }
                if (started[0]++ >= limits.maxBranches()) {
                    break;
                }
                walkBack(nodes.get(a.nodeId()), last.destination(), last.source(), List.of(), Set.of(), forwardAt,
                        forwardUnits, branches, started);
            }
            if (branches.isEmpty()) {
                return new ReturnPath(INCOMPLETE, INGRESS_NOT_FOUND, List.of());
            }
            String status = branches.stream().anyMatch(b -> ASYMMETRIC.equals(b.status())) ? ASYMMETRIC
                    : branches.stream().allMatch(b -> SYMMETRIC.equals(b.status())) ? SYMMETRIC : INCOMPLETE;
            String reason = branches.stream().filter(b -> b.status().equals(status)).map(ReturnBranch::termination)
                    .findFirst().orElse(status);
            return new ReturnPath(status, reason, branches);
        }

        private void walkBack(Node node, String source, String destination, List<ReturnHop> hops, Set<String> visited,
                Map<String, Hop> forwardAt, Set<String> forwardUnits, List<ReturnBranch> out, int[] started) {
            if (++work > limits.workBudget()) {
                out.add(returnBranch(ANALYSIS_BUDGET, hops, forwardUnits));
                return;
            }
            if (hops.size() >= limits.maxHops()) {
                out.add(returnBranch(HOP_LIMIT, hops, forwardUnits));
                return;
            }
            String src = source, dst = destination;
            Hop forward = forwardAt.get(node.nodeId());
            if (forward != null) {
                Tuple in = forward.packetIn(), fwdOut = forward.packetOut();
                if (!in.source().equals(fwdOut.source()) && dst.equals(fwdOut.source())) {
                    dst = in.source();
                }
                if (!in.destination().equals(fwdOut.destination()) && src.equals(fwdOut.destination())) {
                    src = in.destination();
                }
            }
            boolean reversed = !src.equals(source) || !dst.equals(destination);
            String visit = node.nodeId() + "|" + src + "|" + dst;
            if (visited.contains(visit)) {
                out.add(returnBranch(ROUTING_LOOP, hops, forwardUnits));
                return;
            }
            Set<String> nextVisited = new HashSet<>(visited);
            nextVisited.add(visit);
            long target = host(dst);
            RouteLookup.Result lookup = RouteLookup.lookup(node, target);
            List<String> reasons = new ArrayList<>();
            if (node.flags().contains(NodeFlag.ACTIVE_MEMBER_UNPROVEN)) {
                reasons.add(ACTIVE_MEMBER_UNPROVEN);
            }
            reasons.addAll(lookup.taints());
            if (lookup.routes().isEmpty()) {
                List<ReturnHop> h = new ArrayList<>(hops);
                h.add(new ReturnHop(hops.size() + 1, node.nodeId(), src, dst, reversed, Optional.empty(), Endpoint.NONE, reasons));
                out.add(returnBranch(NO_ROUTE, h, forwardUnits));
                return;
            }
            List<InventoryRoute> routes = lookup.routes();
            if (routes.size() > limits.ecmpPerHop()) {
                truncated = true;
                routes = routes.subList(0, limits.ecmpPerHop());
            }
            for (int i = 0; i < routes.size(); i++) {
                if (i > 0 && started[0]++ >= limits.maxBranches()) {
                    truncated = true;
                    break;
                }
                InventoryRoute route = routes.get(i);
                Step step = step(node, route, target);
                List<ReturnHop> h = new ArrayList<>(hops);
                h.add(new ReturnHop(hops.size() + 1, node.nodeId(), src, dst, reversed,
                        Optional.of(new RouteUsed(route.routeId(), route.destination(), route.nextHop(), route.interfaceName(),
                                route.protocol(), route.nextHopKind(), lookup.ecmp())),
                        endpoint(egressOf(step), route.interfaceName(), node), reasons));
                switch (step) {
                    case Deliver d -> out.add(returnBranch(DELIVERED, h, forwardUnits));
                    case Exit e -> out.add(returnBranch(EXITS_KNOWN_TOPOLOGY, h, forwardUnits));
                    case Forward fw -> walkBack(fw.next(), src, dst, h, nextVisited, forwardAt, forwardUnits, out, started);
                    case Drop d -> out.add(returnBranch(ROUTE_DISCARD, h, forwardUnits));
                    case Stop s -> out.add(returnBranch(s.reason(), h, forwardUnits));
                }
            }
        }

        private ReturnBranch returnBranch(String termination, List<ReturnHop> hops, Set<String> forwardUnits) {
            Set<String> units = new TreeSet<>();
            hops.forEach(h -> units.add(stateKey(nodes.get(h.nodeId()))));
            boolean extra = units.stream().anyMatch(u -> !forwardUnits.contains(u));
            boolean tainted = hops.stream().anyMatch(h -> !h.reasons().isEmpty());
            String status;
            if (extra) {
                status = ASYMMETRIC;
            } else if (DELIVERED.equals(termination) || EXITS_KNOWN_TOPOLOGY.equals(termination)) {
                status = !units.equals(forwardUnits) ? ASYMMETRIC : tainted ? INCOMPLETE : SYMMETRIC;
            } else {
                status = INCOMPLETE;
            }
            return new ReturnBranch(termination, status, hops);
        }

        // ---- overall -------------------------------------------------------------------------------------------------

        /** The overall verdict and reason of a set of branches (§5 lattice); {@code [verdict, reason]}. */
        private static String[] combine(List<Branch> branches, boolean truncated) {
            Set<String> verdicts = new LinkedHashSet<>();
            branches.forEach(b -> verdicts.add(b.verdict()));
            if (truncated) {
                verdicts.add(UNKNOWN);
            }
            boolean allowed = verdicts.contains(ALLOWED) || verdicts.contains(ALLOWED_TO_EDGE);
            if (verdicts.size() == 1) {
                return new String[] {verdicts.iterator().next(), branches.get(0).reason()};
            }
            if (allowed && verdicts.contains(DENIED)) {
                return new String[] {PARTIAL, MIXED_BRANCH_VERDICTS};
            }
            if (verdicts.contains(UNKNOWN)) {
                return new String[] {UNKNOWN, branches.stream().filter(b -> UNKNOWN.equals(b.verdict())).map(Branch::reason)
                        .findFirst().orElse(ECMP_TRUNCATED)};
            }
            return new String[] {ALLOWED_TO_EDGE, MIXED_BRANCH_VERDICTS};
        }

        private PathResult result(Ingress ingress, List<Branch> branches, Map<String, Integer> startOf, int startCount) {
            String verdict;
            String reason;
            boolean candidatesAgree = false;
            if (branches.isEmpty()) {
                verdict = UNKNOWN;
                reason = "RESOLVED".equals(ingress.status()) ? (truncated ? ECMP_TRUNCATED : ANALYSIS_BUDGET) : ingress.status();
            } else if (candidatesMustAgree) {
                // Each candidate first hop answers on its own; the verdict stands only when every candidate was traced in
                // full and all of them give the same decided verdict. Anything else stays UNKNOWN (AMBIGUOUS_INGRESS).
                Set<String> perCandidate = new LinkedHashSet<>();
                for (int s = 0; s < startCount; s++) {
                    final int start = s;
                    List<Branch> own = branches.stream().filter(b -> startOf.get(b.branchId()) == start).toList();
                    perCandidate.add(own.isEmpty() ? UNKNOWN : combine(own, false)[0]);
                }
                if (!truncated && perCandidate.size() == 1 && !perCandidate.contains(UNKNOWN)) {
                    String[] all = combine(branches, false);
                    verdict = all[0];
                    reason = all[1];
                    candidatesAgree = true;
                } else {
                    verdict = UNKNOWN;
                    reason = AMBIGUOUS_INGRESS;
                }
            } else {
                String[] all = combine(branches, truncated);
                verdict = all[0];
                reason = all[1];
            }
            List<String> caveats = new ArrayList<>(), warnings = new ArrayList<>();
            branches.forEach(b -> {
                caveats.addAll(b.caveats());
                warnings.addAll(b.warnings());
            });
            if (candidatesAgree) {
                caveats.add(INGRESS_CANDIDATES_AGREE);
            }
            if (unitAddress(query.source())) {
                caveats.add(SOURCE_IS_UNIT_ADDRESS);
            }
            if (unitAddress(query.destination())) {
                caveats.add(DESTINATION_IS_UNIT_ADDRESS);
            }
            if (truncated) {
                warnings.add(ECMP_TRUNCATED);
            }
            boolean plain = ALLOWED.equals(verdict) && caveats.isEmpty();
            boolean asymmetric = branches.stream().anyMatch(b -> ASYMMETRIC.equals(b.returnPath().status()));
            List<HopRef> blocking = new ArrayList<>(), unknown = new ArrayList<>();
            for (Branch b : branches) {
                b.blockingHopIndex().ifPresent(i -> blocking.add(new HopRef(b.branchId(), i)));
                b.hops().stream().filter(h -> UNKNOWN.equals(h.ownVerdict()))
                        .forEach(h -> unknown.add(new HopRef(b.branchId(), h.index())));
            }
            Optional<HopRef> firstBlock = blocking.stream().findFirst();
            boolean stale = branches.stream().flatMap(b -> b.hops().stream()).anyMatch(Hop::policyStale);
            Optional<Hop> blockingHop = firstBlock.flatMap(ref -> branches.stream()
                    .filter(b -> b.branchId().equals(ref.branchId())).findFirst()
                    .map(b -> b.hops().get(ref.hopIndex() - 1)));
            String headline = headline(verdict, reason, branches, blockingHop, plain);
            var summary = new ExecutiveSummary(headline, blocking, unknown, asymmetric, branches.size(), truncated,
                    stale ? "STALE" : "CURRENT");
            return new PathResult(query, graph.snapshotId(), computedAt, verdict, reason, plain, dedupe(caveats),
                    dedupe(warnings), ingress, branches, truncated, asymmetric, firstBlock, summary, PathResult.LIMITATIONS);
        }
    }

    // ---- shared helpers -------------------------------------------------------------------------------------------------

    /** Template text only: verdict, counts, hop index, reason code, rule number. Never a name or an address. */
    static String headline(String verdict, String reason, List<Branch> branches, Optional<Hop> blockingHop, boolean plain) {
        int count = branches.size();
        String hops = count == 1 ? " across " + branches.get(0).hops().size() + " hop(s)" : " on all " + count + " branches";
        return switch (verdict) {
            case ALLOWED -> "Traffic is ALLOWED" + hops + (plain ? "" : " with caveats");
            case ALLOWED_TO_EDGE -> "Traffic is ALLOWED up to the edge of known topology" + hops;
            case DENIED -> "Traffic is DENIED at hop " + blockingHop.map(Hop::index).map(String::valueOf).orElse("?")
                    + blockingHop.map(PathEngine::blockedBy).orElse("");
            case PARTIAL -> "Traffic is PARTIAL: " + branches.stream().filter(b -> ALLOWED.equals(b.verdict())
                    || ALLOWED_TO_EDGE.equals(b.verdict())).count() + " of " + count + " branches allowed";
            default -> "Verdict is UNKNOWN (" + reason + ")";
        };
    }

    private static String blockedBy(Hop hop) {
        if (ROUTE_DISCARD.equals(hop.reason())) {
            return " by a discard route";
        }
        Optional<Object> number = hop.postNatSecurity().or(hop::security).flatMap(PathResult.Security::decidingRule)
                .map(r -> r.get("number")).filter(Objects::nonNull);
        return number.map(n -> " by rule " + n).orElse("");
    }

    private static long host(String address) {
        return Ipv4Prefix.parseHost(address).orElseThrow().host();
    }

    private static List<String> dedupe(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }
}
