import { call } from "./adminApi";

// ---- Path analysis (PATH_ANALYSIS_DESIGN.md §5, stage A5a API / A5b screen) ------------------------------------------

/** The query body of `POST /api/v2/path-analysis/query`: IPv4 hosts, tcp or udp, a destination port, an optional ingress hint. */
export interface PathQueryRequest {
  readonly src: string;
  readonly dst: string;
  readonly protocol: "tcp" | "udp";
  readonly port: number;
  readonly ingress_node_id?: string;
  readonly ingress_interface_name?: string;
  readonly edge_mode?: boolean;
}

export interface PathTuple { readonly src: string; readonly dst: string; readonly protocol: string; readonly port: number }
export interface PathEndpoint {
  readonly attachment_id: string | null;
  readonly segment_id: string | null;
  readonly interface_name: string | null;
  readonly zone: string | null;
}
export interface PathRoute {
  readonly route_id: string;
  readonly destination: string;
  readonly next_hop: string | null;
  readonly interface_name: string | null;
  readonly protocol: string;
  readonly next_hop_kind: string | null;
  readonly ecmp_branch: boolean;
}
export interface PathRule {
  readonly rule_id: string | null;
  readonly rule_number: number | null;
  readonly rule_name: string | null;
  readonly section_id: string | null;
  readonly section_name: string | null;
  readonly action: string | null;
  readonly lookup_reason?: string;
  readonly path_reason?: string;
}
export interface PathNat {
  readonly nat_status: string;
  readonly nat_reason: string;
  readonly applied: boolean;
  readonly nat_rules: readonly PathRule[];
  readonly nat_undecidable_rules: readonly PathRule[];
}
export interface PathSecurity {
  readonly security_verdict: string;
  readonly security_reason: string;
  readonly deciding_rule: PathRule | null;
  readonly undecidable_rules: readonly PathRule[];
}
export interface PathHop {
  readonly hop_index: number;
  readonly node_id: string;
  readonly node_kind: string;
  readonly vendor: string;
  readonly context: string;
  readonly routing_context: string | null;
  readonly device_id: string | null;
  readonly ingress: PathEndpoint;
  readonly egress: PathEndpoint;
  readonly packet_in: PathTuple | null;
  readonly packet_out: PathTuple | null;
  readonly route: PathRoute | null;
  readonly nat: PathNat | null;
  readonly security: PathSecurity | null;
  readonly post_nat_security: PathSecurity | null;
  readonly policy_stale: boolean;
  readonly policy_collected_at: string | null;
  readonly own_verdict: string;
  readonly verdict: string;
  readonly verdict_reason: string;
  readonly reasons: readonly string[];
  readonly caveats: readonly string[];
  readonly notes: readonly string[];
  readonly flags: readonly string[];
}
export interface PathReturnHop {
  readonly hop_index: number;
  readonly node_id: string;
  readonly src: string;
  readonly dst: string;
  readonly reverse_translated: boolean;
  readonly route: PathRoute | null;
  readonly egress: PathEndpoint;
  readonly reasons: readonly string[];
}
export interface PathReturnBranch { readonly termination: string; readonly return_status: string; readonly hops: readonly PathReturnHop[] }
export interface PathReturnPath { readonly return_status: string; readonly return_reason: string; readonly branches: readonly PathReturnBranch[] }
export interface PathBranch {
  readonly branch_id: string;
  readonly verdict: string;
  readonly verdict_reason: string;
  readonly termination: string;
  readonly blocking_hop_index: number | null;
  readonly caveats: readonly string[];
  readonly warnings: readonly string[];
  readonly hops: readonly PathHop[];
  readonly return_path: PathReturnPath;
}
export interface PathHopRef { readonly branch_id: string; readonly hop_index: number }
export interface PathIngressCandidate { readonly node_id: string; readonly attachment_id: string | null; readonly segment_id: string | null }
export interface PathResult {
  readonly status: "COMPUTED";
  readonly snapshot_id: string;
  readonly computed_at: string;
  readonly query: {
    readonly src: string; readonly dst: string; readonly protocol: string; readonly port: number;
    readonly ingress_node_id: string | null; readonly ingress_interface_name: string | null; readonly edge_mode: boolean;
  };
  readonly verdict: string;
  readonly verdict_reason: string;
  readonly plain: boolean;
  readonly caveats: readonly string[];
  readonly warnings: readonly string[];
  readonly ecmp_truncated: boolean;
  readonly asymmetric: boolean;
  readonly blocking_hop: PathHopRef | null;
  readonly ingress: { readonly ingress_mode: string; readonly ingress_status: string; readonly candidates: readonly PathIngressCandidate[] };
  readonly executive_summary: {
    readonly headline: string;
    readonly blocking_hops: readonly PathHopRef[];
    readonly unknown_hops: readonly PathHopRef[];
    readonly asymmetry_detected: boolean;
    readonly ecmp_branch_count: number;
    readonly ecmp_truncated: boolean;
    readonly freshness_status: string;
  };
  readonly branches: readonly PathBranch[];
  readonly limitations: string;
}

/**
 * One path query. Nothing is stored on the client; the server writes one masked audit row per query (D-2). Refusals keep
 * `call`'s error shape: 400 `INVALID_REQUEST`, 409 `TOPOLOGY_NOT_BUILT`, 422 `PSEUDONYM_REFUSED`, 429
 * `PATH_QUERY_RATE_LIMIT`, 503 `POLICY_BUSY` (`error`) or `AUDIT_UNAVAILABLE` (`code`).
 */
export const queryPath = (request: PathQueryRequest) => call<PathResult>("/api/v2/path-analysis/query", "POST", request);

export interface TopologyNodeView {
  readonly node_id: string;
  readonly node_kind: string;
  readonly vendor: string;
  readonly cluster_member_ref?: string | null;
  readonly context?: string | null;
  readonly routing_context?: string | null;
  readonly active_member_device_id?: string | null;
  readonly flags?: readonly string[];
}
export interface TopologySegmentView {
  readonly segment_id: string;
  readonly segment_kind: string;
  readonly network: string | null;
  readonly override_id?: string | null;
}
export interface TopologyAttachmentView {
  readonly attachment_id: string;
  readonly node_id: string;
  readonly segment_id: string;
  readonly interface_name: string | null;
  readonly address: string | null;
  readonly address_role?: string | null;
  readonly zone: string | null;
  readonly corroborated?: boolean;
  readonly source?: string;
  readonly evidence_grade?: string | null;
}
export interface TopologySnapshotView {
  readonly status: "BUILT" | "NOT_BUILT" | string;
  readonly snapshot_id?: string;
  readonly built_at?: string;
  readonly nodes: readonly TopologyNodeView[];
  readonly segments: readonly TopologySegmentView[];
  readonly attachments: readonly TopologyAttachmentView[];
}

/** Reads the held topology (`GET /api/v2/topology`, masked for aiview) to resolve neighbour segments attached to path nodes. */
export const getTopology = () => call<TopologySnapshotView>("/api/v2/topology", "GET");

// ---- Path analysis stage A6: real-environment validation of a computed result ---------------------------------------

/** One vendor matcher's outcome on one hop: enums and a reason code only, never the device's answer. */
export interface PathValidationCheck {
  /** `CP_ROUTE`, `PAN_FIB`, `PAN_SECURITY` or `PAN_NAT`. */
  readonly check: string;
  readonly result: "MATCH" | "MISMATCH" | "NOT_EVALUABLE" | string;
  readonly reason: string;
}
export interface PathValidationHop {
  readonly ordinal: number;
  readonly branch_id: string;
  readonly hop_index: number;
  readonly node_id: string;
  readonly vendor: string;
  readonly device_id: string | null;
  readonly job_id: string | null;
  readonly job_state: string | null;
  /** `PENDING` while the device job runs; then `MATCH`, `MISMATCH` or `NOT_EVALUABLE`. */
  readonly result: "PENDING" | "MATCH" | "MISMATCH" | "NOT_EVALUABLE" | string;
  readonly checks: readonly PathValidationCheck[];
  readonly reasons: readonly string[];
  readonly evaluated_at: string | null;
}
/**
 * `POST /api/v2/path-analysis/validate` (202) and `GET /api/v2/path-analysis/validate/{id}`: the recorded state of one
 * verification. `result` is `MISMATCH` when any hop mismatches, else `PENDING` while any hop waits, else `MATCH` when
 * any hop matched, else `NOT_EVALUABLE`. Only the first `MAX_HOPS` unit hops of one branch are asked; the rest are
 * counted in `hops_not_validated`.
 */
export interface PathValidation {
  readonly validation_id: string;
  readonly submitted_at: string;
  readonly topology_snapshot_id: string;
  readonly path_verdict: string;
  readonly result: "PENDING" | "MATCH" | "MISMATCH" | "NOT_EVALUABLE" | string;
  readonly hops_not_validated: number;
  readonly hops: readonly PathValidationHop[];
  readonly limitations: string;
}

/** The server's per-request hop cap (`PathValidation.MAX_HOPS`): one job per hop, at most this many. */
export const PATH_VALIDATION_MAX_HOPS = 4;

/**
 * Asks the devices of one computed path to confirm it. The body is the path query itself (the server computes the path
 * again; the browser names no command, device, context, zone or rule) plus the branch to verify when the route splits.
 * Refusals keep `call`'s error shape: 400 `INVALID_REQUEST`, 403, 404 `BRANCH_NOT_FOUND`, 409 `TOPOLOGY_NOT_BUILT` or
 * `OUTSIDE_JOB_WINDOW`, 422 `PSEUDONYM_REFUSED`, 429 `PATH_VALIDATION_RATE_LIMIT`, 503 `POLICY_BUSY` or `AUDIT_UNAVAILABLE`.
 */
export const validatePath = (request: PathQueryRequest, branchId?: string) =>
  call<PathValidation>("/api/v2/path-analysis/validate", "POST", branchId ? { ...request, branch_id: branchId } : request);
export const getPathValidation = (validationId: string) =>
  call<PathValidation>(`/api/v2/path-analysis/validate/${encodeURIComponent(validationId)}`, "GET");
