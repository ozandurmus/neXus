import { describe, expect, it } from "vitest";
import type { CutoverAssessmentView, OfflineAnalysisResult, PathResult, PolicyLookupResult } from "../src/auth/adminApi";
import {
  buildCutoverAnswer,
  buildLookupAnswer,
  buildPathAnswer,
  buildUsageAnswer,
  buildZonesAnswer,
  parseAskMode,
  parseFlowQuestion,
} from "../src/estate/estateAskModel";

describe("parseFlowQuestion", () => {
  it("reads an arrow, a word form and a port-first form", () => {
    const expected = { src: "192.0.2.10", dst: "198.51.100.40", protocol: "tcp", port: 8443 };
    expect(parseFlowQuestion("192.0.2.10 → 198.51.100.40 tcp/8443").flow).toEqual(expected);
    expect(parseFlowQuestion("can 192.0.2.10 reach 198.51.100.40 on tcp 8443").flow).toEqual(expected);
    expect(parseFlowQuestion("192.0.2.10 -> 198.51.100.40 8443/tcp").flow).toEqual(expected);
  });

  it("names what is missing instead of guessing", () => {
    expect(parseFlowQuestion("192.0.2.10").missing).toEqual(["a destination address", "tcp or udp", "a port (1–65535)"]);
    expect(parseFlowQuestion("192.0.2.10 198.51.100.40 tcp/70000").flow).toBeNull();
    expect(parseFlowQuestion("192.0.2.999 198.51.100.40 tcp/443").missing).toEqual(["a destination address"]);
  });

  it("falls back to the path mode for an unknown mode", () => {
    expect(parseAskMode("zones")).toBe("zones");
    expect(parseAskMode("nope")).toBe("path");
    expect(parseAskMode(null)).toBe("path");
  });
});

const hop = (index: number, verdict: string, withRule: boolean) => ({
  hop_index: index, node_id: `FW-TANGO-0${index + 4}`, node_kind: "firewall", vendor: "PAN", context: "", routing_context: null, device_id: null,
  ingress: { attachment_id: null, segment_id: null, interface_name: "ethernet1/3", zone: "core-apps" },
  egress: { attachment_id: null, segment_id: null, interface_name: "ethernet1/4", zone: null },
  packet_in: null, packet_out: null,
  route: { route_id: "r1", destination: "198.51.100.0/26", next_hop: "198.51.100.62", interface_name: "ethernet1/4", protocol: "connected", next_hop_kind: null, ecmp_branch: false },
  nat: { nat_status: "UNKNOWN", nat_reason: "NAT rules not in the stored policy read", applied: false, nat_rules: [], nat_undecidable_rules: [] },
  security: { security_verdict: verdict, security_reason: "", deciding_rule: withRule ? { rule_id: "x", rule_number: 88, rule_name: "apps-tls-alt", section_id: null, section_name: "Core", action: "Accept" } : null, undecidable_rules: [] },
  post_nat_security: null, policy_stale: false, policy_collected_at: "2026-11-19T10:00:00Z",
  own_verdict: verdict, verdict, verdict_reason: "", reasons: [], caveats: [], notes: [], flags: [],
});

function pathResult(verdict: string, unknownHops: number): PathResult {
  return {
    status: "COMPUTED", snapshot_id: "s", computed_at: "2026-11-19T11:00:00Z",
    query: { src: "192.0.2.10", dst: "198.51.100.40", protocol: "tcp", port: 8443, ingress_node_id: null, ingress_interface_name: null, edge_mode: false },
    verdict, verdict_reason: "reason", plain: true, caveats: [], warnings: [], ecmp_truncated: false, asymmetric: false, blocking_hop: null,
    ingress: { ingress_mode: "x", ingress_status: "y", candidates: [] },
    executive_summary: { headline: "Headline", blocking_hops: [], unknown_hops: Array.from({ length: unknownHops }, (_, i) => ({ branch_id: "b", hop_index: i })), asymmetry_detected: false, ecmp_branch_count: 1, ecmp_truncated: false, freshness_status: "x" },
    branches: [{ branch_id: "b", verdict, verdict_reason: "", termination: "DESTINATION", blocking_hop_index: null, caveats: [], warnings: [], hops: [hop(0, verdict, true), hop(1, verdict === "UNKNOWN" ? "UNKNOWN" : "ALLOWED", false)], return_path: { return_status: "ok", return_reason: "", branches: [] } }],
    limitations: "Intent only.",
  } as unknown as PathResult;
}

describe("answers", () => {
  it("puts the verdict first and the hops after, and never dresses UNKNOWN as a verdict", () => {
    const allowed = buildPathAnswer(pathResult("ALLOWED", 1));
    expect(allowed.state).toBe("ok");
    expect(allowed.verdictWord).toBe("Allowed by intent");
    expect(allowed.unprovable).toBe(false);
    expect(allowed.rows).toHaveLength(2);
    expect(allowed.rows[0]?.lines.join(" ")).toContain("Rule #88 apps-tls-alt");
    expect(allowed.detail).toContain("1 hop partly UNKNOWN");

    const unknown = buildPathAnswer(pathResult("UNKNOWN", 2));
    expect(unknown.state).toBe("unk");
    expect(unknown.unprovable).toBe(true);
    expect(unknown.rows[1]?.lines.join(" ")).toContain("Rule UNKNOWN");
    expect(buildPathAnswer(pathResult("DENIED", 0)).state).toBe("act");
  });

  it("lookup: an UNKNOWN verdict is unprovable and the deciding rule is labelled as not a verdict", () => {
    const result = {
      verdict: "UNKNOWN", lookupReason: "A rule could match", decidingRule: { ruleId: "r1", number: 9, name: "cleanup", sectionId: "s", sectionName: "Sec", action: "Drop" },
      undecidableRules: [{ ruleId: "r2", number: 4, name: "maybe", sectionId: "s", sectionName: "Sec", action: "Accept", lookupReason: "ZONE_UNKNOWN" }],
      policyId: "p1", collectedAt: "2026-11-19T10:00:00Z", limitations: "NAT not evaluated", query: { querySource: "a", queryDestination: "b", queryService: "tcp/443" }, parts: [],
    } as PolicyLookupResult;
    const answer = buildLookupAnswer(result);
    expect(answer.unprovable).toBe(true);
    expect(answer.rows[0]?.title).toContain("not a verdict");
    expect(answer.rows[1]?.stateWord).toBe("UNKNOWN");
    expect(buildLookupAnswer({ ...result, verdict: "DENIED", undecidableRules: [] }).state).toBe("act");
  });

  it("rule usage, cutover and zone matrix map to states without guessing", () => {
    const usage = buildUsageAnswer({
      flowCount: 10, invalidRows: 0, periodStart: "a", periodEnd: "b", collectedAt: "2026-11-19T10:00:00Z", limitations: "",
      rules: [{ objectUsage: [{ dimension: "source", objectId: "o", name: "NET-A", parentId: null, depth: 0, state: "NOT_SEEN", flowCount: 0 }, { dimension: "service", objectId: "s", name: "https", parentId: null, depth: 0, state: "USED", flowCount: 10 }] }],
    } as unknown as OfflineAnalysisResult);
    expect(usage.state).toBe("watch");
    expect(usage.headline).toContain("1 of 2 objects");
    expect(buildUsageAnswer({ rules: [], flowCount: 0, invalidRows: 0 } as unknown as OfflineAnalysisResult).unprovable).toBe(true);

    const cut = (readiness: string, status: string) => buildCutoverAnswer({ readiness, status, headline: "h", counts: { WOULD_DROP: 3 }, createdAt: "x" } as unknown as CutoverAssessmentView);
    expect(cut("BLOCKERS_FOUND", "COMPLETE").state).toBe("act");
    expect(cut("NO_BLOCKERS_IN_EVALUATED_FLOWS", "COMPLETE").state).toBe("ok");
    expect(cut("NO_BLOCKERS_IN_EVALUATED_FLOWS", "INCOMPLETE").state).toBe("watch");
    expect(cut("NO_EVIDENCE", "COMPLETE").unprovable).toBe(true);

    const zones = (over: Record<string, unknown>) => buildZonesAnswer({
      evaluated: true, coverage: "COMPLETE", outcome_counts: {}, version_number: 2, collected_at: null, coverage_items: [],
      headline: { distinct_violating_rules: 0, violated_zone_pairs: 0, unknown_rules: 0, not_evaluable_rules: 0 }, ...over,
    } as never);
    expect(zones({}).state).toBe("ok");
    expect(zones({ outcome_counts: { VIOLATION: 2 } }).state).toBe("act");
    expect(zones({ coverage: "INCOMPLETE" }).state).toBe("watch");
    expect(zones({ evaluated: false, not_evaluated_reason: "No matrix published" }).unprovable).toBe(true);
  });
});
