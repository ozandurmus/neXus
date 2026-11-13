package com.securityexpert.nexus.ui2.service.pathanalysis.query;

import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

import com.securityexpert.nexus.ui2.service.pathanalysis.engine.HopPolicySource;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathEngine;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathQuery;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathReasons;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyInput;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyService;
import com.securityexpert.nexus.ui2.service.policy.CutoverAssessment;

/**
 * The firewalls a segment's flow crosses, from path computation (PO 2026-11-14: "ACI may send traffic anywhere; the next
 * hop comes from IP, routes and contracts"): each flow tuple is traced through the held topology with the stored
 * policies, exactly as Trace does, and the path verdict becomes the flow's decision. The fabric's redirect (contracts,
 * service graph, PBR) is not modelled until the topology-sources overlay exists, so every result carries
 * {@code FABRIC_REDIRECT_NOT_MODELLED}: today the routed path is the path before the redirect.
 */
public final class CutoverPathTargets {
    /** Every path reason and verdict string the engine can return; the aiview projection passes only these. */
    public static final Set<String> REASONS;
    static {
        Set<String> reasons = new HashSet<>();
        for (var field : PathReasons.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try { reasons.add((String) field.get(null)); } catch (IllegalAccessException unreachable) { throw new IllegalStateException(unreachable); }
            }
        }
        REASONS = Set.copyOf(reasons);
    }
    public static final Set<String> HOP_VERDICTS = Set.of("ALLOWED", "DENIED", "UNKNOWN", "CONDITIONAL");
    static final int MAX_FIREWALLS = 16;

    private final TopologyService topology;
    private final PathPolicyBinder policies;
    private final Clock clock;

    public CutoverPathTargets(TopologyService topology, PathPolicyBinder policies, Clock clock) {
        this.topology = Objects.requireNonNull(topology, "topology");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The routed-path policy over the topology held now, or empty when none is held ({@code TOPOLOGY_NOT_BUILT}). */
    public Optional<Routed> current() {
        Instant now = clock.instant();
        return topology.current().map(graph -> new Routed(graph, policies.forQuery(now), now));
    }

    /** The id of the topology snapshot held now, for staleness of a stored assessment. */
    public Optional<String> currentSnapshotId() { return topology.current().map(TopologyGraph::snapshotId); }

    /** One assessment's view of the held topology: one policy binding for every flow of the run. */
    public record Routed(TopologyGraph graph, HopPolicySource hops, Instant now) implements CutoverAssessment.FlowPolicy {
        public String snapshotId() { return graph.snapshotId(); }
        @Override public CutoverAssessment.Decision decide(String source, String destination, String protocol, int port, String fromZone, String toZone) {
            PathQuery query;
            try { query = PathQuery.of(source, destination, protocol, port); }
            catch (IllegalArgumentException invalid) { return new CutoverAssessment.Decision("NOT_DECIDED", "unsupported evaluation scope", null, null, List.of()); }
            return decision(PathEngine.analyze(graph, hops, query, now));
        }
        @Override public String source() { return CutoverAssessment.SOURCE_PATH; }
        @Override public List<String> coverage() { return List.of("TARGET_FROM_ROUTED_PATH", "FABRIC_REDIRECT_NOT_MODELLED"); }
    }

    /**
     * The flow decision of a path result: ALLOWED is WOULD_ALLOW, DENIED is WOULD_DROP with the blocking hop's rule, any
     * other verdict (UNKNOWN, PARTIAL, an exit from the known topology, an asymmetric return) is NOT_DECIDED with the
     * path's own reason. The firewalls on the path are listed with their own verdicts and deciding rules.
     */
    public static CutoverAssessment.Decision decision(PathResult result) {
        Map<String, Object> blockingRule = result.blockingHop().flatMap(ref -> result.branches().stream()
                .filter(b -> b.branchId().equals(ref.branchId())).flatMap(b -> b.hops().stream())
                .filter(h -> h.index() == ref.hopIndex()).findFirst())
            .flatMap(PathResult.Hop::security).flatMap(PathResult.Security::decidingRule).map(CutoverPathTargets::ruleView).orElse(null);
        String decision = switch (result.verdict()) {
            case PathReasons.ALLOWED -> "WOULD_ALLOW";
            case PathReasons.DENIED -> "WOULD_DROP";
            default -> "NOT_DECIDED";
        };
        String reason = result.reason() == null || result.reason().isBlank() ? result.verdict() : result.reason();
        return new CutoverAssessment.Decision(decision, reason, "WOULD_DROP".equals(decision) ? blockingRule : null, null, List.of(), firewalls(result));
    }

    private static List<Map<String, Object>> firewalls(PathResult result) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (PathResult.Branch branch : result.branches()) for (PathResult.Hop hop : branch.hops()) {
            if (out.size() >= MAX_FIREWALLS || out.containsKey(hop.nodeId())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            hop.deviceId().ifPresent(id -> row.put("deviceId", id));
            row.put("vendor", TopologyInput.VENDOR_CHECK_POINT.equals(hop.vendor()) ? "CP" : TopologyInput.VENDOR_PALO_ALTO.equals(hop.vendor()) ? "PAN" : "");
            row.put("hopVerdict", HOP_VERDICTS.contains(hop.ownVerdict()) ? hop.ownVerdict() : "UNKNOWN");
            hop.security().flatMap(PathResult.Security::decidingRule).map(CutoverPathTargets::ruleView).ifPresent(rule -> row.put("decidingRule", rule));
            out.put(hop.nodeId(), row);
        }
        return List.copyOf(out.values());
    }

    private static Map<String, Object> ruleView(Map<String, Object> rule) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("ruleId", "number", "name", "sectionId", "sectionName", "action")) if (rule.get(key) != null) out.put(key, rule.get(key));
        return out;
    }
}
