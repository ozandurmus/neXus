package com.securityexpert.nexus.ui2.service.pathanalysis.query;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathEngine;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathQuery;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyService;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * Stage A5a: one path question against the held topology (PATH_ANALYSIS_DESIGN.md §5, §7; PO record 2026-11-03 D-2,
 * D-3, D-8). Read only: it never builds the topology, never collects, never persists a result.
 *
 * <p><b>Topology not built.</b> The query answers {@link NotBuilt} (the API's 409 {@code TOPOLOGY_NOT_BUILT}) instead of
 * building on demand: a build is a {@code topology_rebuild} (security_admin only, D-8) and persists a snapshot header, so
 * a read that triggered one would be both a privilege widening and a side effect (D-3 condition 4). Fail closed and
 * non-blocking.</p>
 *
 * <p><b>aiview (D-3).</b> A replay viewer submits masked values only. Source, destination and a hinted ingress interface
 * are resolved through the snapshot's in-memory {@link PathPseudonymMap}; a raw, unknown or ambiguous value is
 * {@link Refused} with one uniform outcome (the specific code goes to the audit row only). Both addresses are always
 * resolved, so a refusal does not reveal which field failed by doing less work.</p>
 */
public final class PathQueryService {

    /** What one query came to; {@code auditQuery} is the masked query for the {@code authz_decisions} row (D-2). */
    public sealed interface Outcome permits Computed, NotBuilt, Refused {
        Map<String, Object> auditQuery();
    }

    public record Computed(PathResult result, Map<String, Object> auditQuery) implements Outcome {
    }

    public record NotBuilt(Map<String, Object> auditQuery) implements Outcome {
    }

    /** {@code code} is internal (RAW_VALUE_REFUSED / UNKNOWN_PSEUDONYM / AMBIGUOUS_PSEUDONYM), never returned. */
    public record Refused(String code, Map<String, Object> auditQuery) implements Outcome {
    }

    /** The validated request as submitted (masked values for aiview, raw values otherwise). */
    public record Request(String source, String destination, String protocol, int port, Optional<String> ingressNodeId,
            Optional<String> ingressInterface, boolean edgeMode) {
        public Request {
            ingressNodeId = ingressNodeId == null ? Optional.empty() : ingressNodeId;
            ingressInterface = ingressInterface == null ? Optional.empty() : ingressInterface;
        }

        /** The engine query of these values; {@link IllegalArgumentException} for a malformed one (the API's 400). */
        PathQuery toQuery(String src, String dst, Optional<String> iface) {
            PathQuery q = new PathQuery(src, dst, protocol, port, Optional.empty(), edgeMode, PathQuery.Limits.DEFAULT);
            return ingressNodeId.map(n -> q.withIngress(n, iface.orElse(null))).orElse(q);
        }
    }

    static final String REFUSED_VALUE = "[REFUSED]";

    private final TopologyService topology;
    private final PathPolicyBinder policies;
    private final PathPseudonymResolver pseudonyms;
    private final SubnetPreservingIpMasker ips;
    private final TopologyNamePseudonymizer names;
    private final Clock clock;

    public PathQueryService(TopologyService topology, PathPolicyBinder policies, PathPseudonymResolver pseudonyms,
            SubnetPreservingIpMasker ips, TopologyNamePseudonymizer names, Clock clock) {
        this.topology = Objects.requireNonNull(topology, "topology");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.pseudonyms = Objects.requireNonNull(pseudonyms, "pseudonyms");
        this.ips = Objects.requireNonNull(ips, "ips");
        this.names = Objects.requireNonNull(names, "names");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Syntax check only (the same for every role): throws {@link IllegalArgumentException} when malformed. */
    public static void validate(Request request) {
        request.toQuery(request.source(), request.destination(), request.ingressInterface());
    }

    public Outcome query(Request request, boolean replayViewer) {
        validate(request);
        Optional<TopologyGraph> held = topology.current();
        if (held.isEmpty()) {
            if (!replayViewer) {
                return new NotBuilt(masked(request));
            }
            // Nothing can be resolved without a snapshot: keep only values already in the masked form.
            Map<String, String> unresolved = new LinkedHashMap<>();
            if (!maskedForm(request.source())) unresolved.put("src", PathPseudonymMap.RAW_VALUE_REFUSED);
            if (!maskedForm(request.destination())) unresolved.put("dst", PathPseudonymMap.RAW_VALUE_REFUSED);
            request.ingressInterface().ifPresent(i -> unresolved.put("ingress_interface_name", PathPseudonymMap.UNKNOWN_PSEUDONYM));
            Map<String, Object> audit = submitted(request, unresolved);
            audit.remove("refused");
            return new NotBuilt(audit);
        }
        TopologyGraph graph = held.get();
        if (!replayViewer) {
            PathQuery query = request.toQuery(request.source(), request.destination(), request.ingressInterface());
            PathResult result = PathEngine.analyze(graph, policies.forQuery(clock.instant()), query, clock.instant());
            return new Computed(result, masked(request));
        }
        PathPseudonymMap map = pseudonyms.mapFor(graph);
        PathPseudonymMap.Resolution src = map.resolveAddress(request.source());
        PathPseudonymMap.Resolution dst = map.resolveAddress(request.destination());
        Optional<PathPseudonymMap.Resolution> iface = request.ingressInterface()
                .map(i -> map.resolveInterface(request.ingressNodeId().orElse(""), i));
        Map<String, String> refusedFields = new LinkedHashMap<>();
        String code = null;
        for (var entry : List.of(Map.entry("src", src), Map.entry("dst", dst))) {
            if (entry.getValue() instanceof PathPseudonymMap.Refused r) {
                refusedFields.put(entry.getKey(), r.code());
                code = worst(code, r.code());
            }
        }
        if (iface.isPresent() && iface.get() instanceof PathPseudonymMap.Refused r) {
            refusedFields.put("ingress_interface_name", r.code());
            code = worst(code, r.code());
        }
        Map<String, Object> audit = submitted(request, refusedFields);
        if (code != null) {
            return new Refused(code, audit);
        }
        PathQuery query = request.toQuery(((PathPseudonymMap.Resolved) src).raw(), ((PathPseudonymMap.Resolved) dst).raw(),
                iface.map(r -> ((PathPseudonymMap.Resolved) r).raw()));
        PathResult result = PathEngine.analyze(graph, policies.forQuery(clock.instant()), query, clock.instant());
        return new Computed(result, audit);
    }

    /** One code for the audit row: a raw value outranks ambiguity, which outranks an unknown value. */
    private static String worst(String current, String next) {
        List<String> order = List.of(PathPseudonymMap.RAW_VALUE_REFUSED, PathPseudonymMap.AMBIGUOUS_PSEUDONYM,
                PathPseudonymMap.UNKNOWN_PSEUDONYM);
        if (current == null) {
            return next;
        }
        return order.indexOf(next) < order.indexOf(current) ? next : current;
    }

    /** aiview's own (already masked) values; a raw value it sent is withheld, never stored. No masker is called. */
    private static Map<String, Object> submitted(Request r, Map<String, String> refused) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("src", withheld(r.source(), refused.get("src")));
        q.put("dst", withheld(r.destination(), refused.get("dst")));
        q.put("protocol", r.protocol());
        q.put("port", r.port());
        q.put("ingress_node_id", r.ingressNodeId().orElse(null));
        // An interface label is kept only when it resolved: an unresolved one may be a raw name of another unit.
        q.put("ingress_interface_name", r.ingressInterface()
                .map(i -> refused.containsKey("ingress_interface_name") ? REFUSED_VALUE : i).orElse(null));
        q.put("edge_mode", r.edgeMode());
        q.put("masked_input", true);
        if (!refused.isEmpty()) {
            q.put("refused", refused);
        }
        return q;
    }

    private static boolean maskedForm(String address) {
        return com.securityexpert.nexus.ui2.service.pathanalysis.topology.Ipv4Prefix.parseHost(address)
                .filter(h -> h.length().isEmpty()).filter(h -> PathPseudonymMap.maskedSpace(h.host())).isPresent();
    }

    private static String withheld(String value, String refusal) {
        return PathPseudonymMap.RAW_VALUE_REFUSED.equals(refusal) ? REFUSED_VALUE : value;
    }

    /** A raw-role query masked for the audit row (the PolicyLookupController pattern). */
    private Map<String, Object> masked(Request r) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("src", ips.maskText(r.source()));
        q.put("dst", ips.maskText(r.destination()));
        q.put("protocol", r.protocol());
        q.put("port", r.port());
        q.put("ingress_node_id", r.ingressNodeId().orElse(null));
        q.put("ingress_interface_name", r.ingressInterface().map(i -> names.maskPolicyName("interface", i)).orElse(null));
        q.put("edge_mode", r.edgeMode());
        q.put("masked_input", false);
        return q;
    }
}
