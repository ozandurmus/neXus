import type { PathBranch, PathHop, PathResult, PathValidation, TopologySnapshotView } from "../auth/adminApi";

/**
 * Synthetic path results for the design preview and the tests. Shaped exactly like the A5a golden
 * (`ui2/service/src/test/resources/pathanalysis/query/api-chain.golden.txt`): the first result is that file's first
 * case verbatim. Pseudonyms and documentation addresses only (AGENTS.md UI design law): FW-TANGO-04, FW-BRAVO-02,
 * 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24.
 */

const LIMITATIONS = "Management intent only (stored inventory and policy), not observed traffic. IPv4 only; source port not modelled; Check Point implied rules and anti-spoofing not evaluated; return traffic is traced by routing only.";

const CP_RULE_R1 = { rule_id: "r1", rule_number: 1, rule_name: "RULE-r1", section_id: "6a3ac089-2b81-338d-bc84-329529dfbc0a", section_name: "OBJ-SECTION-01", action: "Accept" };
const CP_NAT_N1 = { rule_id: "n1", rule_number: 1, rule_name: "NAT-n1", section_id: "101d5c80-2c22-3ca3-8b09-58dac15d3f03", section_name: "NAT", action: "static" };
const PAN_SNAT = { rule_id: "1e8f6973-746b-3340-960c-c61cab1a0e82", rule_number: 1, rule_name: "NAT-SNAT-01", section_id: "705c9ed4-91aa-38ac-aedf-55d0c738cd56", section_name: "Local NAT rules", action: "source-nat" };
const PAN_WEB = { rule_id: "487b78e3-4829-3c11-9835-50c70d95b387", rule_number: 1, rule_name: "RULE-WEB-01", section_id: "68792cc2-e590-38c6-b84b-03177be049ad", section_name: "Local rules", action: "allow" };
const PAN_DENY = { rule_id: "021405a7-1ce8-3e8b-ae4f-8bb49ce14dcc", rule_number: 2, rule_name: "RULE-DENY-01", section_id: "68792cc2-e590-38c6-b84b-03177be049ad", section_name: "Local rules", action: "deny" };

export function tangoHop(port = 443): PathHop {
  return {
    hop_index: 1, node_id: "FW-TANGO-04/physical/main", node_kind: "ROUTING_CONTEXT", vendor: "check_point", context: "physical", routing_context: "main", device_id: "dev-t04",
    ingress: { attachment_id: "ATTACHMENT-FW-TANGO-04/physical/main-eth1", segment_id: "SEGMENT-198.51.100.0/26", interface_name: "eth1", zone: null },
    egress: { attachment_id: "ATTACHMENT-FW-TANGO-04/physical/main-eth2", segment_id: "SEGMENT-192.0.2.0/30", interface_name: "eth2", zone: null },
    packet_in: { src: "198.51.100.20", dst: "203.0.113.200", protocol: "tcp", port },
    packet_out: { src: "198.51.100.20", dst: "203.0.113.10", protocol: "tcp", port },
    route: { route_id: "route-dev-t04-9", destination: "203.0.113.0/26", next_hop: "192.0.2.2", interface_name: "eth2", protocol: "static", next_hop_kind: "IP", ecmp_branch: false },
    nat: { nat_status: "TRANSLATED", nat_reason: "NAT_FIRST_MATCH", applied: true, nat_rules: [CP_NAT_N1], nat_undecidable_rules: [] },
    security: { security_verdict: "ALLOWED", security_reason: "FIRST_MATCHING_RULE", deciding_rule: CP_RULE_R1, undecidable_rules: [] },
    post_nat_security: { security_verdict: "ALLOWED", security_reason: "FIRST_MATCHING_RULE", deciding_rule: CP_RULE_R1, undecidable_rules: [] },
    policy_stale: false, policy_collected_at: "2026-11-04T05:00:00Z",
    own_verdict: "ALLOWED", verdict: "ALLOWED", verdict_reason: "FIRST_MATCHING_RULE", reasons: ["FIRST_MATCHING_RULE"], caveats: [],
    notes: ["CP_IMPLIED_RULES_NOT_MODELED", "CP_ROUTE_AFTER_NAT_UNKNOWN"], flags: [],
  };
}

export function bravoHop(port = 443, verdict: "ALLOWED" | "DENIED" = "ALLOWED"): PathHop {
  const denied = verdict === "DENIED";
  return {
    hop_index: 2, node_id: "FW-BRAVO-02/vsys1/vr-main", node_kind: "ROUTING_CONTEXT", vendor: "palo_alto", context: "vsys1", routing_context: "vr-main", device_id: "dev-b02",
    ingress: { attachment_id: "ATTACHMENT-FW-BRAVO-02/vsys1/vr-main-ethernet1/1", segment_id: "SEGMENT-192.0.2.0/30", interface_name: "ethernet1/1", zone: "ZONE-TRANSIT" },
    egress: { attachment_id: "ATTACHMENT-FW-BRAVO-02/vsys1/vr-main-ethernet1/2", segment_id: "SEGMENT-203.0.113.0/26", interface_name: "ethernet1/2", zone: "ZONE-DMZ" },
    packet_in: { src: "198.51.100.20", dst: "203.0.113.10", protocol: "tcp", port },
    packet_out: { src: "203.0.113.1", dst: "203.0.113.10", protocol: "tcp", port },
    route: { route_id: "route-dev-b02-5", destination: "203.0.113.0/26", next_hop: null, interface_name: "ethernet1/2", protocol: "connected", next_hop_kind: "CONNECTED", ecmp_branch: false },
    nat: { nat_status: "TRANSLATED", nat_reason: "NAT_FIRST_MATCH", applied: true, nat_rules: [PAN_SNAT], nat_undecidable_rules: [] },
    security: { security_verdict: verdict, security_reason: "FIRST_MATCHING_RULE", deciding_rule: denied ? PAN_DENY : PAN_WEB, undecidable_rules: [] },
    post_nat_security: null, policy_stale: false, policy_collected_at: "2026-11-04T05:00:00Z",
    own_verdict: verdict, verdict, verdict_reason: "FIRST_MATCHING_RULE", reasons: ["FIRST_MATCHING_RULE"], caveats: [], notes: [], flags: [],
  };
}

const SYMMETRIC_RETURN: PathBranch["return_path"] = {
  return_status: "SYMMETRIC", return_reason: "DELIVERED",
  branches: [{
    termination: "DELIVERED", return_status: "SYMMETRIC",
    hops: [
      { hop_index: 1, node_id: "FW-BRAVO-02/vsys1/vr-main", src: "203.0.113.10", dst: "198.51.100.20", reverse_translated: true,
        route: { route_id: "route-dev-b02-6", destination: "198.51.100.0/24", next_hop: "192.0.2.1", interface_name: "ethernet1/1", protocol: "static", next_hop_kind: "IP", ecmp_branch: false },
        egress: { attachment_id: "ATTACHMENT-FW-BRAVO-02/vsys1/vr-main-ethernet1/1", segment_id: "SEGMENT-192.0.2.0/30", interface_name: "ethernet1/1", zone: "ZONE-TRANSIT" }, reasons: [] },
      { hop_index: 2, node_id: "FW-TANGO-04/physical/main", src: "203.0.113.200", dst: "198.51.100.20", reverse_translated: true,
        route: { route_id: "route-dev-t04-6", destination: "198.51.100.0/26", next_hop: null, interface_name: "eth1", protocol: "kernel", next_hop_kind: "CONNECTED", ecmp_branch: false },
        egress: { attachment_id: "ATTACHMENT-FW-TANGO-04/physical/main-eth1", segment_id: "SEGMENT-198.51.100.0/26", interface_name: "eth1", zone: null }, reasons: [] },
    ],
  }],
};

const INGRESS = { ingress_mode: "INFERRED", ingress_status: "RESOLVED", candidates: [{ node_id: "FW-TANGO-04/physical/main", attachment_id: "ATTACHMENT-FW-TANGO-04/physical/main-eth1", segment_id: "SEGMENT-198.51.100.0/26" }] };

/** The golden file's first case: 198.51.100.20 → 203.0.113.200 tcp/443, ALLOWED across two hops, symmetric return. */
export const PATH_ALLOWED: PathResult = {
  status: "COMPUTED", snapshot_id: "SNAPSHOT", computed_at: "2026-11-04T08:00:00Z",
  query: { src: "198.51.100.20", dst: "203.0.113.200", protocol: "tcp", port: 443, ingress_node_id: null, ingress_interface_name: null, edge_mode: false },
  verdict: "ALLOWED", verdict_reason: "ALL_HOPS_ALLOWED", plain: true, caveats: [], warnings: [], ecmp_truncated: false, asymmetric: false, blocking_hop: null,
  ingress: INGRESS,
  executive_summary: { headline: "Traffic is ALLOWED across 2 hop(s)", blocking_hops: [], unknown_hops: [], asymmetry_detected: false, ecmp_branch_count: 1, ecmp_truncated: false, freshness_status: "CURRENT" },
  branches: [{ branch_id: "b1", verdict: "ALLOWED", verdict_reason: "ALL_HOPS_ALLOWED", termination: "DELIVERED", blocking_hop_index: null, caveats: [], warnings: [], hops: [tangoHop(), bravoHop()], return_path: SYMMETRIC_RETURN }],
  limitations: LIMITATIONS,
};

/** The golden file's second case: tcp/22 DENIED at hop 2 by rule 2. */
export const PATH_DENIED: PathResult = {
  ...PATH_ALLOWED,
  query: { ...PATH_ALLOWED.query, port: 22 },
  verdict: "DENIED", verdict_reason: "DENIED_AT_HOP", plain: false, blocking_hop: { branch_id: "b1", hop_index: 2 },
  executive_summary: { ...PATH_ALLOWED.executive_summary, headline: "Traffic is DENIED at hop 2 by rule 2", blocking_hops: [{ branch_id: "b1", hop_index: 2 }] },
  branches: [{ branch_id: "b1", verdict: "DENIED", verdict_reason: "DENIED_AT_HOP", termination: "BLOCKED", blocking_hop_index: 2, caveats: [], warnings: [], hops: [tangoHop(22), bravoHop(22, "DENIED")],
    return_path: { return_status: "NOT_COMPUTED", return_reason: "BLOCKED", branches: [] } }],
};

/** The golden file's third case: edge mode, the default route leaves the known topology after hop 1. */
export const PATH_ALLOWED_TO_EDGE: PathResult = {
  ...PATH_ALLOWED,
  query: { ...PATH_ALLOWED.query, dst: "203.0.113.150", edge_mode: true },
  verdict: "ALLOWED_TO_EDGE", verdict_reason: "EXITS_KNOWN_TOPOLOGY", plain: false,
  executive_summary: { ...PATH_ALLOWED.executive_summary, headline: "Traffic is ALLOWED up to the edge of known topology across 1 hop(s)" },
  branches: [{ branch_id: "b1", verdict: "ALLOWED_TO_EDGE", verdict_reason: "EXITS_KNOWN_TOPOLOGY", termination: "EXITS_KNOWN_TOPOLOGY", blocking_hop_index: null, caveats: [], warnings: [],
    hops: [{ ...tangoHop(), egress: { attachment_id: "ATTACHMENT-FW-TANGO-04/physical/main-eth3", segment_id: "SEGMENT-198.51.100.128/30", interface_name: "eth3", zone: null },
      packet_in: { src: "198.51.100.20", dst: "203.0.113.150", protocol: "tcp", port: 443 }, packet_out: { src: "198.51.100.20", dst: "203.0.113.150", protocol: "tcp", port: 443 },
      route: { route_id: "route-dev-t04-12", destination: "0.0.0.0/0", next_hop: "198.51.100.130", interface_name: "eth3", protocol: "static", next_hop_kind: "IP", ecmp_branch: false },
      nat: { nat_status: "NO_TRANSLATION", nat_reason: "NAT_NO_RULE_MATCHED", applied: false, nat_rules: [], nat_undecidable_rules: [] }, post_nat_security: null, notes: ["CP_IMPLIED_RULES_NOT_MODELED"] }],
    return_path: { return_status: "NOT_COMPUTED", return_reason: "RETURN_NOT_COMPUTED_AT_EDGE", branches: [] } }],
};

/** The golden file's fourth case: a discard route at hop 1. */
export const PATH_DISCARD: PathResult = {
  ...PATH_ALLOWED,
  query: { ...PATH_ALLOWED.query, dst: "203.0.113.70" },
  verdict: "DENIED", verdict_reason: "DENIED_AT_HOP", plain: false, blocking_hop: { branch_id: "b1", hop_index: 1 },
  executive_summary: { ...PATH_ALLOWED.executive_summary, headline: "Traffic is DENIED at hop 1 by a discard route", blocking_hops: [{ branch_id: "b1", hop_index: 1 }] },
  branches: [{ branch_id: "b1", verdict: "DENIED", verdict_reason: "DENIED_AT_HOP", termination: "BLOCKED", blocking_hop_index: 1, caveats: [], warnings: [],
    hops: [{ ...tangoHop(), egress: { attachment_id: null, segment_id: null, interface_name: null, zone: null },
      packet_in: { src: "198.51.100.20", dst: "203.0.113.70", protocol: "tcp", port: 443 }, packet_out: { src: "198.51.100.20", dst: "203.0.113.70", protocol: "tcp", port: 443 },
      route: { route_id: "route-dev-t04-11", destination: "203.0.113.64/26", next_hop: null, interface_name: null, protocol: "static", next_hop_kind: "DISCARD", ecmp_branch: false },
      nat: { nat_status: "NO_TRANSLATION", nat_reason: "NAT_NO_RULE_MATCHED", applied: false, nat_rules: [], nat_undecidable_rules: [] }, post_nat_security: null,
      own_verdict: "DENIED", verdict: "DENIED", verdict_reason: "ROUTE_DISCARD", reasons: ["ROUTE_DISCARD"], notes: ["CP_IMPLIED_RULES_NOT_MODELED"] }],
    return_path: { return_status: "NOT_COMPUTED", return_reason: "BLOCKED", branches: [] } }],
};

/**
 * Two equal-cost branches from FW-TANGO-04 (not in the golden file; the lattice cases of §5): branch b1 is UNKNOWN at
 * hop 2 (Check Point access/NAT order, A0 §1) so its hop 3 is CONDITIONAL; branch b2 is allowed with the App-ID and
 * local-rules caveats and an asymmetric return. Overall PARTIAL, asymmetric, truncated branch list.
 */
export const PATH_PARTIAL: PathResult = (() => {
  const juliet: PathHop = {
    ...tangoHop(), hop_index: 2, node_id: "FW-JULIET-06/physical/main", device_id: "dev-j06",
    ingress: { attachment_id: "ATTACHMENT-FW-JULIET-06/physical/main-eth1", segment_id: "SEGMENT-192.0.2.0/30", interface_name: "eth1", zone: null },
    egress: { attachment_id: "ATTACHMENT-FW-JULIET-06/physical/main-eth2", segment_id: "SEGMENT-192.0.2.8/30", interface_name: "eth2", zone: null },
    packet_in: { src: "198.51.100.20", dst: "203.0.113.10", protocol: "tcp", port: 443 }, packet_out: { src: "198.51.100.20", dst: "203.0.113.10", protocol: "tcp", port: 443 },
    route: { route_id: "route-dev-j06-3", destination: "203.0.113.0/26", next_hop: "192.0.2.10", interface_name: "eth2", protocol: "static", next_hop_kind: "IP", ecmp_branch: false },
    nat: { nat_status: "TRANSLATED", nat_reason: "NAT_FIRST_MATCH", applied: true, nat_rules: [CP_NAT_N1], nat_undecidable_rules: [] },
    security: { security_verdict: "ALLOWED", security_reason: "FIRST_MATCHING_RULE", deciding_rule: CP_RULE_R1, undecidable_rules: [] },
    post_nat_security: { security_verdict: "DENIED", security_reason: "FIRST_MATCHING_RULE", deciding_rule: { ...CP_RULE_R1, rule_id: "r4", rule_number: 4, rule_name: "RULE-r4", action: "Drop" }, undecidable_rules: [] },
    own_verdict: "UNKNOWN", verdict: "UNKNOWN", verdict_reason: "CP_ACCESS_NAT_ORDER_UNKNOWN", reasons: ["CP_ACCESS_NAT_ORDER_UNKNOWN"], caveats: [], notes: ["CP_IMPLIED_RULES_NOT_MODELED"], flags: [],
  };
  const bravoConditional: PathHop = { ...bravoHop(), hop_index: 3, verdict: "CONDITIONAL", own_verdict: "ALLOWED", caveats: ["APP_ID_ASSUMED"], ingress: { ...bravoHop().ingress, segment_id: "SEGMENT-192.0.2.8/30" } };
  const bravoStale: PathHop = { ...bravoHop(), hop_index: 2, node_id: "FW-BRAVO-02/vsys2/vr-main", context: "vsys2", policy_stale: true, own_verdict: "UNKNOWN", verdict: "UNKNOWN",
    verdict_reason: "STALE_OR_OUT_OF_SYNC_POLICY", reasons: ["STALE_OR_OUT_OF_SYNC_POLICY"], caveats: ["PAN_LOCAL_RULES_NOT_COLLECTED"], flags: ["PBR_PRESENT"],
    ingress: { ...bravoHop().ingress, segment_id: "SEGMENT-192.0.2.4/30", interface_name: "ethernet1/3" } };
  const tangoEcmp = (branch: "b1" | "b2"): PathHop => ({
    ...tangoHop(), route: { ...tangoHop().route!, route_id: branch === "b1" ? "route-dev-t04-9" : "route-dev-t04-10", next_hop: branch === "b1" ? "192.0.2.2" : "192.0.2.6", interface_name: branch === "b1" ? "eth2" : "eth4", ecmp_branch: true },
    egress: branch === "b1" ? tangoHop().egress : { attachment_id: "ATTACHMENT-FW-TANGO-04/physical/main-eth4", segment_id: "SEGMENT-192.0.2.4/30", interface_name: "eth4", zone: null },
    flags: ["CLUSTER_UNCORROBORATED"], caveats: ["CLUSTER_UNCORROBORATED"],
  });
  return {
    ...PATH_ALLOWED,
    verdict: "PARTIAL", verdict_reason: "MIXED_BRANCH_VERDICTS", plain: false, caveats: ["CLUSTER_UNCORROBORATED"], warnings: ["ECMP_TRUNCATED", "ASYMMETRIC_RETURN", "OUT_OF_STATE_DROP_LIKELY"],
    ecmp_truncated: true, asymmetric: true, blocking_hop: null,
    executive_summary: { headline: "Traffic is PARTIAL: 2 branch(es) disagree", blocking_hops: [], unknown_hops: [{ branch_id: "b1", hop_index: 2 }, { branch_id: "b2", hop_index: 2 }], asymmetry_detected: true, ecmp_branch_count: 2, ecmp_truncated: true, freshness_status: "STALE" },
    branches: [
      { branch_id: "b1", verdict: "UNKNOWN", verdict_reason: "UNKNOWN_HOP", termination: "DELIVERED", blocking_hop_index: null, caveats: [], warnings: [], hops: [tangoEcmp("b1"), juliet, bravoConditional],
        return_path: { return_status: "ASYMMETRIC", return_reason: "ASYMMETRIC_RETURN", branches: [{ termination: "DELIVERED", return_status: "ASYMMETRIC", hops: [
          { hop_index: 1, node_id: "FW-BRAVO-02/vsys1/vr-main", src: "203.0.113.10", dst: "198.51.100.20", reverse_translated: true,
            route: { route_id: "route-dev-b02-7", destination: "198.51.100.0/24", next_hop: "192.0.2.5", interface_name: "ethernet1/3", protocol: "static", next_hop_kind: "IP", ecmp_branch: false },
            egress: { attachment_id: "ATTACHMENT-FW-BRAVO-02/vsys1/vr-main-ethernet1/3", segment_id: "SEGMENT-192.0.2.4/30", interface_name: "ethernet1/3", zone: "ZONE-TRANSIT" }, reasons: ["ASYMMETRIC_RETURN"] },
        ] }] } },
      { branch_id: "b2", verdict: "UNKNOWN", verdict_reason: "UNKNOWN_HOP", termination: "DELIVERED", blocking_hop_index: null, caveats: ["RETURN_PATH_UNPROVEN"], warnings: [], hops: [tangoEcmp("b2"), bravoStale],
        return_path: { return_status: "INCOMPLETE", return_reason: "RETURN_PATH_UNPROVEN", branches: [] } },
    ],
  };
})();

/**
 * Stage A6: a completed verification of PATH_ALLOWED's branch b1 (hop states and reason codes only, as the API returns
 * them): the Check Point route agrees, the Palo Alto route and rule agree while its NAT match disagrees.
 */
export const PATH_VALIDATION_COMPLETE: PathValidation = {
  validation_id: "pv-00000000-0000-4000-8000-000000000001", submitted_at: "2026-11-04T08:05:00Z", topology_snapshot_id: "SNAPSHOT",
  path_verdict: "ALLOWED", result: "MISMATCH", hops_not_validated: 0,
  hops: [
    { ordinal: 1, branch_id: "b1", hop_index: 1, node_id: "FW-TANGO-04/physical/main", vendor: "check_point", device_id: "dev-t04", job_id: "job-0001", job_state: "COMPLETED",
      result: "MATCH", checks: [{ check: "CP_ROUTE", result: "MATCH", reason: "SAME_INTERFACE_AND_NEXT_HOP" }], reasons: [], evaluated_at: "2026-11-04T08:05:20Z" },
    { ordinal: 2, branch_id: "b1", hop_index: 2, node_id: "FW-BRAVO-02/vsys1/vr-main", vendor: "palo_alto", device_id: "dev-b02", job_id: "job-0002", job_state: "COMPLETED",
      result: "MISMATCH", checks: [
        { check: "PAN_FIB", result: "MATCH", reason: "CONNECTED_SAME_INTERFACE" },
        { check: "PAN_SECURITY", result: "MATCH", reason: "SAME_FIRST_MATCH_RULE" },
        { check: "PAN_NAT", result: "MISMATCH", reason: "NAT_RULE_DIFFERS" },
      ], reasons: ["NAT_RULE_DIFFERS"], evaluated_at: "2026-11-04T08:05:41Z" },
  ],
  limitations: "A MATCH means the device's own read-only matcher agreed with the computed hop for this sample; it proves neither other flows nor observed traffic. NOT_EVALUABLE is never a mismatch.",
};

/** A verification still waiting: hop 1 answered, hop 2's job is executing. */
export const PATH_VALIDATION_PENDING: PathValidation = {
  ...PATH_VALIDATION_COMPLETE, result: "PENDING",
  hops: [PATH_VALIDATION_COMPLETE.hops[0], { ...PATH_VALIDATION_COMPLETE.hops[1], job_state: "EXECUTING", result: "PENDING", checks: [], reasons: [], evaluated_at: null }],
};

/** Synthetic topology snapshot for the Path diagram preview (pseudonyms and documentation addresses only). */
export const PATH_TOPOLOGY_PREVIEW: TopologySnapshotView = {
  status: "BUILT",
  snapshot_id: "SNAPSHOT",
  built_at: "2026-11-04T07:55:00Z",
  nodes: [
    { node_id: "FW-TANGO-04/physical/main", node_kind: "ROUTING_CONTEXT", vendor: "check_point", context: "physical", routing_context: "main" },
    { node_id: "FW-BRAVO-02/vsys1/vr-main", node_kind: "ROUTING_CONTEXT", vendor: "palo_alto", context: "vsys1", routing_context: "vr-main" },
    { node_id: "FW-JULIET-06/physical/main", node_kind: "ROUTING_CONTEXT", vendor: "check_point", context: "physical", routing_context: "main" },
  ],
  segments: [
    { segment_id: "SEGMENT-198.51.100.0/26", segment_kind: "LAN", network: "198.51.100.0/26" },
    { segment_id: "SEGMENT-192.0.2.0/30", segment_kind: "POINT_TO_POINT", network: "192.0.2.0/30" },
    { segment_id: "SEGMENT-203.0.113.0/26", segment_kind: "LAN", network: "203.0.113.0/26" },
    { segment_id: "SEGMENT-192.0.2.64/26", segment_kind: "LAN", network: "192.0.2.64/26" },
    { segment_id: "SEGMENT-198.51.100.64/26", segment_kind: "LAN", network: "198.51.100.64/26" },
    { segment_id: "SEGMENT-203.0.113.64/26", segment_kind: "LAN", network: "203.0.113.64/26" },
  ],
  attachments: [
    { attachment_id: "ATTACHMENT-FW-TANGO-04/physical/main-eth1", node_id: "FW-TANGO-04/physical/main", segment_id: "SEGMENT-198.51.100.0/26", interface_name: "eth1", address: "198.51.100.1/26", zone: null },
    { attachment_id: "ATTACHMENT-FW-TANGO-04/physical/main-eth2", node_id: "FW-TANGO-04/physical/main", segment_id: "SEGMENT-192.0.2.0/30", interface_name: "eth2", address: "192.0.2.1/30", zone: null },
    { attachment_id: "ATTACHMENT-FW-TANGO-04/physical/main-eth3", node_id: "FW-TANGO-04/physical/main", segment_id: "SEGMENT-192.0.2.64/26", interface_name: "eth3", address: "192.0.2.65/26", zone: null },
    { attachment_id: "ATTACHMENT-FW-TANGO-04/physical/main-eth5", node_id: "FW-TANGO-04/physical/main", segment_id: "SEGMENT-198.51.100.64/26", interface_name: "eth5", address: "198.51.100.65/26", zone: null },
    { attachment_id: "ATTACHMENT-FW-BRAVO-02/vsys1/vr-main-ethernet1/1", node_id: "FW-BRAVO-02/vsys1/vr-main", segment_id: "SEGMENT-192.0.2.0/30", interface_name: "ethernet1/1", address: "192.0.2.2/30", zone: "ZONE-TRANSIT" },
    { attachment_id: "ATTACHMENT-FW-BRAVO-02/vsys1/vr-main-ethernet1/2", node_id: "FW-BRAVO-02/vsys1/vr-main", segment_id: "SEGMENT-203.0.113.0/26", interface_name: "ethernet1/2", address: "203.0.113.1/26", zone: "ZONE-DMZ" },
    { attachment_id: "ATTACHMENT-FW-BRAVO-02/vsys1/vr-main-ethernet1/4", node_id: "FW-BRAVO-02/vsys1/vr-main", segment_id: "SEGMENT-203.0.113.64/26", interface_name: "ethernet1/4", address: "203.0.113.65/26", zone: "ZONE-APP" },
  ],
};
