package com.securityexpert.nexus.ui2.service.pathanalysis.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult;
import com.securityexpert.nexus.ui2.service.policy.HopPolicyEvaluator;

/**
 * The {@code path_analysis_read} body: one {@link PathResult}, raw for every role that reads inventory raw (D-8). For
 * the replay viewer (aiview) the same body goes through {@code PrivacyMaskingResponseBodyAdvice}, so its keys are chosen
 * to land on the masking rules the topology read already uses: {@code src}/{@code dst}/{@code destination}/
 * {@code next_hop} -> the subnet-preserving IP masker, {@code interface_name}/{@code ingress_interface_name} and
 * {@code zone} -> their pseudonyms, {@code routing_context} -> the shared "vrf" pseudonym, {@code rule_name} and
 * {@code section_name} -> the rule pseudonym; opaque ids ({@code node_id}, {@code segment_id}, {@code attachment_id},
 * {@code snapshot_id}, {@code device_id}, {@code rule_id}, {@code section_id}) pass unchanged so aiview can relate the
 * result to the masked topology. A rule map carries only the fields named here; anything else the rule engine adds is
 * left out (fail closed). Nothing in this body is stored (D-2).
 *
 * <p>Rule identity follows today's policy RBAC (coordinator decision 2026-11-04 on the A5a open point): a caller without
 * {@code policy_read} receives every verdict and reason unchanged, but each rule's id, number, name and section are
 * withheld and marked {@link #RULE_WITHHELD}, and the executive headline drops its rule number. Callers with
 * {@code policy_read} (aiview masked) keep them.</p>
 */
public final class PathResultView {

    private PathResultView() {
    }

    /** Marker of a rule whose identity the caller may not see (no {@code policy_read}). */
    public static final String RULE_WITHHELD = "RULE_WITHHELD_NO_POLICY_READ";

    /** The body with rule identity (a caller holding {@code policy_read}). */
    public static Map<String, Object> body(PathResult r) {
        return body(r, true);
    }

    public static Map<String, Object> body(PathResult r, boolean ruleIdentity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "COMPUTED");
        body.put("snapshot_id", r.topologySnapshotId());
        body.put("computed_at", r.computedAt().toString());
        body.put("query", query(r));
        body.put("verdict", r.verdict());
        body.put("verdict_reason", r.reason());
        body.put("plain", r.plain());
        body.put("caveats", r.caveats());
        body.put("warnings", r.warnings());
        body.put("ecmp_truncated", r.ecmpTruncated());
        body.put("asymmetric", r.asymmetric());
        body.put("blocking_hop", r.blockingHop().map(PathResultView::ref).orElse(null));
        Map<String, Object> ingress = new LinkedHashMap<>();
        ingress.put("ingress_mode", r.ingress().mode());
        ingress.put("ingress_status", r.ingress().status());
        ingress.put("candidates", r.ingress().candidates().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("node_id", c.nodeId());
            m.put("attachment_id", c.attachmentId().orElse(null));
            m.put("segment_id", c.segmentId().orElse(null));
            return m;
        }).toList());
        body.put("ingress", ingress);
        var s = r.executiveSummary();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("headline", ruleIdentity ? s.headline() : withoutRuleNumber(s.headline()));
        summary.put("blocking_hops", s.blockingHops().stream().map(PathResultView::ref).toList());
        summary.put("unknown_hops", s.unknownHops().stream().map(PathResultView::ref).toList());
        summary.put("asymmetry_detected", s.asymmetryDetected());
        summary.put("ecmp_branch_count", s.ecmpBranchCount());
        summary.put("ecmp_truncated", s.ecmpTruncated());
        summary.put("freshness_status", s.freshnessStatus());
        body.put("executive_summary", summary);
        body.put("branches", r.branches().stream().map(b -> branch(b, ruleIdentity)).toList());
        body.put("limitations", r.limitations());
        return body;
    }

    private static Map<String, Object> query(PathResult r) {
        var q = r.query();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("src", q.source());
        m.put("dst", q.destination());
        m.put("protocol", q.protocol());
        m.put("port", q.destinationPort());
        m.put("ingress_node_id", q.ingress().map(h -> h.nodeId()).orElse(null));
        m.put("ingress_interface_name", q.ingress().flatMap(h -> h.interfaceName()).orElse(null));
        m.put("edge_mode", q.edgeMode());
        return m;
    }

    private static Map<String, Object> ref(PathResult.HopRef ref) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("branch_id", ref.branchId());
        m.put("hop_index", ref.hopIndex());
        return m;
    }

    /** The engine's headline names a blocking rule only as {@code " by rule <number>"}; that suffix is withheld. */
    static String withoutRuleNumber(String headline) {
        return headline == null ? null : headline.replaceAll(" by rule \\S+$", " by a rule (withheld)");
    }

    private static Map<String, Object> branch(PathResult.Branch b, boolean ruleIdentity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("branch_id", b.branchId());
        m.put("verdict", b.verdict());
        m.put("verdict_reason", b.reason());
        m.put("termination", b.termination());
        m.put("blocking_hop_index", b.blockingHopIndex().orElse(null));
        m.put("caveats", b.caveats());
        m.put("warnings", b.warnings());
        m.put("hops", b.hops().stream().map(h -> hop(h, ruleIdentity)).toList());
        var ret = b.returnPath();
        Map<String, Object> rp = new LinkedHashMap<>();
        rp.put("return_status", ret.status());
        rp.put("return_reason", ret.reason());
        rp.put("branches", ret.branches().stream().map(rb -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("termination", rb.termination());
            x.put("return_status", rb.status());
            x.put("hops", rb.hops().stream().map(rh -> {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("hop_index", rh.index());
                h.put("node_id", rh.nodeId());
                h.put("src", rh.source());
                h.put("dst", rh.destination());
                h.put("reverse_translated", rh.reverseTranslated());
                h.put("route", rh.route().map(PathResultView::route).orElse(null));
                h.put("egress", endpoint(rh.egress()));
                h.put("reasons", rh.reasons());
                return h;
            }).toList());
            return x;
        }).toList());
        m.put("return_path", rp);
        return m;
    }

    private static Map<String, Object> hop(PathResult.Hop h, boolean ruleIdentity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hop_index", h.index());
        m.put("node_id", h.nodeId());
        m.put("node_kind", h.nodeKind());
        m.put("vendor", h.vendor());
        m.put("context", h.context());
        m.put("routing_context", h.routingTable().orElse(null));
        m.put("device_id", h.deviceId().orElse(null));
        m.put("ingress", endpoint(h.ingress()));
        m.put("egress", endpoint(h.egress()));
        m.put("packet_in", tuple(h.packetIn()));
        m.put("packet_out", tuple(h.packetOut()));
        m.put("route", h.route().map(PathResultView::route).orElse(null));
        m.put("nat", h.nat().map(n -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("nat_status", n.status());
            x.put("nat_reason", n.reason());
            x.put("applied", n.applied());
            x.put("nat_rules", rules(n.rules(), ruleIdentity));
            x.put("nat_undecidable_rules", rules(n.undecidableRules(), ruleIdentity));
            return x;
        }).orElse(null));
        m.put("security", h.security().map(s -> security(s, ruleIdentity)).orElse(null));
        m.put("post_nat_security", h.postNatSecurity().map(s -> security(s, ruleIdentity)).orElse(null));
        m.put("policy_stale", h.policyStale());
        m.put("policy_collected_at", h.policyCollectedAt().map(Object::toString).orElse(null));
        m.put("own_verdict", h.ownVerdict());
        m.put("verdict", h.verdict());
        m.put("verdict_reason", h.reason());
        m.put("reasons", h.reasons());
        m.put("caveats", h.caveats());
        m.put("notes", h.notes());
        m.put("flags", h.flags().stream().sorted().toList());
        return m;
    }

    private static Map<String, Object> endpoint(PathResult.Endpoint e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attachment_id", e.attachmentId().orElse(null));
        m.put("segment_id", e.segmentId().orElse(null));
        m.put("interface_name", e.interfaceName().orElse(null));
        m.put("zone", e.zone().orElse(null));
        return m;
    }

    private static Map<String, Object> tuple(HopPolicyEvaluator.Tuple t) {
        if (t == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("src", t.source());
        m.put("dst", t.destination());
        m.put("protocol", t.protocol());
        m.put("port", t.destinationPort());
        return m;
    }

    private static Map<String, Object> route(PathResult.RouteUsed r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("route_id", r.routeId());
        m.put("destination", r.destination());
        m.put("next_hop", r.nextHop().orElse(null));
        m.put("interface_name", r.interfaceName().orElse(null));
        m.put("protocol", r.protocol());
        m.put("next_hop_kind", r.nextHopKind().orElse(null));
        m.put("ecmp_branch", r.ecmpBranch());
        return m;
    }

    private static Map<String, Object> security(PathResult.Security s, boolean ruleIdentity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("security_verdict", s.verdict());
        m.put("security_reason", s.reason());
        m.put("deciding_rule", s.decidingRule().map(r -> rule(r, ruleIdentity)).orElse(null));
        m.put("undecidable_rules", rules(s.undecidableRules(), ruleIdentity));
        return m;
    }

    private static List<Map<String, Object>> rules(List<Map<String, Object>> rules, boolean ruleIdentity) {
        List<Map<String, Object>> out = new ArrayList<>(rules.size());
        rules.forEach(r -> out.add(rule(r, ruleIdentity)));
        return out;
    }

    /** Rule identity as the rule engine reports it, renamed onto the advice's rule keys; other fields are dropped. */
    static Map<String, Object> rule(Map<String, Object> r, boolean ruleIdentity) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (ruleIdentity) {
            m.put("rule_id", text(r.get("ruleId")));
            m.put("rule_number", r.get("number") instanceof Number n ? n : null);
            m.put("rule_name", text(r.get("name")));
            m.put("section_id", text(r.get("sectionId")));
            m.put("section_name", text(r.get("sectionName")));
        } else {
            m.put("rule_identity", RULE_WITHHELD);
        }
        m.put("action", text(r.get("action")));
        Optional.ofNullable(text(r.get("lookupReason"))).ifPresent(v -> m.put("lookup_reason", v));
        Optional.ofNullable(text(r.get("pathReason"))).ifPresent(v -> m.put("path_reason", v));
        return m;
    }

    private static String text(Object value) {
        return value instanceof String s ? s : null;
    }
}
