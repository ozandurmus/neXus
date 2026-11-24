/**
 * Packet Analysis workspace client (cases, captures, telemetry reads, flow/chart/ladder/waterfall/timeseries/topology/
 * DNS/field-diff views, exports and compare). Split out of adminApi.ts unchanged; every existing importer still reaches
 * these names through `./adminApi` (re-exported there).
 */
import { apiCall as call } from "./adminApi";
const PA = "/api/v2/packet-analysis";

export interface PacketRef {
  readonly point: string;
  readonly file: string;
  readonly capture_id: string;
  readonly frame: number;
  readonly display_filter: string;
  readonly content_filter?: string | null;
  readonly flow_filter?: string | null;
  readonly filter_note?: string;
  readonly observed_time: number | null;
  readonly corrected_time: number | null;
  readonly vendor?: Record<string, string>;
  readonly byte_ranges?: readonly { readonly start_seq: number; readonly end_seq: number; readonly bytes: number }[];
  readonly range_note?: string;
  readonly sequence_translation?: {
    readonly observed_seq: number;
    readonly observed_ack: number;
    readonly canonical_seq: number;
    readonly canonical_ack: number;
    readonly seq_offset: number;
    readonly ack_offset: number;
    readonly reason: string | null;
  };
}

export interface PacketTopologyPoint {
  readonly id: string;
  readonly label: string;
  readonly device: string;
  readonly kind: string;
  readonly side: string;
  readonly stage_capture?: boolean;
  readonly clock_domain?: string | null;
  readonly host_capture?: boolean;
  readonly capture_id: string;
  readonly interface: number | null;
  readonly vendor?: string;
  readonly vendor_stage?: string | null;
  readonly vendor_interface?: string | null;
  readonly inspection_complete?: boolean;
  readonly matching_layer?: "inner" | "outer" | null;
  readonly source_cidr: string | null;
  readonly payload_transform?: "none" | "proxy" | "alg" | "ssl_inspection";
  readonly translation: string;
  readonly x: number;
  readonly y: number;
}

export interface PacketNatMapping {
  readonly point_a: string;
  readonly point_b: string;
  readonly tuple_a: string;
  readonly tuple_b: string;
  readonly samples?: number;
  readonly evidence?: readonly PacketRef[];
}

export interface PacketTopology {
  readonly points: readonly PacketTopologyPoint[];
  readonly forward: readonly string[];
  readonly reverse: readonly string[];
  readonly client_cidrs: readonly string[];
  readonly nat_mappings: readonly PacketNatMapping[];
  readonly clock_overrides?: Record<string, { readonly offset_ms: number; readonly drift_ppm: number }>;
  readonly min_eligible_ratio: number;
  readonly duplicate_us?: number;
  readonly stall_ms?: number;
  readonly match_window_ms?: number;
  readonly start?: number | null;
  readonly end?: number | null;
}

export interface PacketCaptureInventory {
  readonly id: string;
  readonly path: string;
  readonly name: string;
  readonly state: string;
  readonly checkpoint: number;
  readonly error: string | null;
  readonly inventory: {
    readonly warnings?: readonly string[];
    readonly timestamps_validated?: boolean;
    readonly start?: number | null;
    readonly end?: number | null;
    readonly duration?: number | null;
    readonly packet_count?: number;
    readonly prefilter_total?: number | null;
    readonly prefilter_retained?: number | null;
    readonly prefilter_excluded?: number;
    readonly prefilter_skipped?: boolean;
    readonly truncated?: number;
    readonly possible_offload?: number;
    readonly ifdrop?: number | null;
    readonly osdrop?: number | null;
    readonly format?: string;
  };
}

export interface PacketFinding {
  readonly id: string;
  readonly type: string;
  readonly severity: string;
  readonly hop: string;
  readonly direction: string;
  readonly time_range: readonly number[];
  readonly confidence: string;
  readonly summary: string;
  readonly headline?: string;
  readonly downstream_summary?: string;
  readonly downstream_count?: number;
  readonly chronic_loss?: boolean;
  readonly evidence: readonly PacketRef[];
  readonly evidence_note: string;
  readonly tooltip?: string;
  readonly metrics: Record<string, number>;
}

export interface PacketSegment {
  readonly id: string;
  readonly point_a: string;
  readonly point_b: string;
  readonly label: string;
  readonly location: string;
  readonly direction: string;
  readonly reason: string | null;
  readonly matched: number;
  readonly p50_ms: number | null;
  readonly p95_ms: number | null;
  readonly max_ms: number | null;
  readonly loss_percent: number | null;
  readonly eligible_packets: number;
  readonly eligible_ratio: number | null;
  readonly excluded_counts: Record<string, number>;
  readonly classes: Record<string, number>;
  readonly offset_uncertainty_ms: number | null;
  readonly latency_uncertainty_ms?: number;
  readonly quality_notes?: readonly string[];
  readonly loss_suspect?: boolean;
  readonly chronic_loss?: boolean;
  readonly symptom_note?: string;
  readonly headline?: string;
  readonly onset_status?: string;
  readonly onset_quality_notes?: { readonly capture_misses: number; readonly unknown_events: number; readonly reasons: readonly string[] };
  readonly onset_reasons?: readonly { readonly metric: string; readonly reason: string }[];
}

export interface PacketQualityPoint {
  readonly point: string;
  readonly file?: string;
  readonly ifdrop: number | null;
  readonly osdrop: number | null;
  readonly packets: number;
  readonly excluded: number;
  readonly prefilter_excluded?: number;
  readonly truncated: number;
  readonly possible_offload: number;
  readonly unknown_direction: number;
  readonly index_scope?: {
    readonly input_frames: number;
    readonly retained_frames: number;
    readonly input_flows: number;
    readonly retained_flows: number;
    readonly excluded_frames: number;
    readonly excluded_flows: number;
  };
  readonly evidence: readonly PacketRef[];
}

export interface PacketAnalysisReport {
  readonly verdict: string;
  readonly scope: string;
  readonly flow_count: number;
  readonly window: {
    readonly start: number | null;
    readonly end: number | null;
    readonly common_start: number | null;
    readonly common_end: number | null;
  };
  readonly clocks: Record<string, {
    readonly offset: number | null;
    readonly drift_ppm: number;
    readonly epoch: number;
    readonly uncertainty: number | null;
    readonly confidence: string;
    readonly reason: string;
    readonly samples: number;
    readonly evidence: readonly PacketRef[];
  }>;
  readonly coverage: readonly { readonly point: string; readonly start: number | null; readonly end: number | null }[];
  readonly quality: readonly PacketQualityPoint[];
  readonly nat_suggestions: readonly PacketNatMapping[];
  readonly segments: readonly PacketSegment[];
  readonly findings: readonly PacketFinding[];
  readonly limitations: readonly string[];
  readonly auto_order_suggestion?: { readonly points: readonly string[]; readonly reason: string };
  readonly onsets?: {
    readonly status: string;
    readonly summary: string;
    readonly directions?: Record<string, {
      readonly prime_suspects: readonly string[];
      readonly propagation_order: readonly { readonly time: number; readonly segments: readonly string[] }[];
      readonly caveat: string;
    }>;
    readonly items: readonly {
      readonly segment: string;
      readonly metric: string;
      readonly bucket: number;
      readonly time: number;
      readonly explanation: string;
      readonly scope?: string;
      readonly evidence: readonly PacketRef[];
    }[];
  };
  readonly cycles?: PacketCycleReport;
  readonly incident?: {
    readonly headline?: string;
    readonly quality_note?: string;
    readonly window?: { readonly start: number; readonly end: number } | null;
    readonly timeline?: readonly Record<string, number | null>[];
    readonly loss_groups?: readonly { readonly hop: string; readonly headline: string; readonly counts: Record<string, number>; readonly evidence?: readonly PacketRef[] }[];
    readonly quality?: readonly { readonly point_a: string; readonly point_b: string; readonly direction: string; readonly evidence: readonly PacketRef[] }[];
    readonly hold_release?: readonly {
      readonly id: string; readonly hop: string; readonly time_range: readonly number[]; readonly summary: string; readonly tooltip: string;
      readonly metrics: { readonly wire_spacing_ms: readonly number[]; readonly receive_spacing_ms: readonly number[] };
      readonly evidence: readonly PacketRef[];
    }[];
  };
  readonly host_path?: {
    readonly note?: string;
    readonly scope?: string;
    readonly window_reason?: string | null;
    readonly local_service?: { readonly p95_ms?: number | null; readonly cycle_aggregate?: { readonly p95_ms?: number | null } };
    readonly items?: readonly {
      readonly hop: string; readonly direction: string; readonly max_ms: number; readonly uncertainty_ms: number;
      readonly headline?: string; readonly delay_share_percent?: number; readonly affected_flow_count?: number;
      readonly window: { readonly start?: number; readonly end?: number; readonly recovery_confirmed?: boolean; readonly status?: string; readonly reason?: string | null };
      readonly periods?: readonly { readonly period: string; readonly p95_ms: number | null; readonly max_ms: number | null; readonly bytes: number }[];
      readonly flow_details?: readonly { readonly flow: string; readonly p95_ms: number | null; readonly max_ms: number | null; readonly reason?: string; readonly evidence: readonly PacketRef[] }[];
      readonly evidence: readonly PacketRef[];
    }[];
  };
  readonly delivery?: {
    readonly note: string;
    readonly series: readonly {
      readonly point_a: string; readonly point_b: string; readonly direction: string; readonly start: number;
      readonly delayed: number; readonly dropped: number; readonly capture_misses: number; readonly unknown: number;
      readonly max_delay_ms: number | null; readonly max_recovery_ms: number | null;
    }[];
  };
  readonly sessions?: {
    readonly note: string; readonly scope: string; readonly timeout_seconds: number;
    readonly points: readonly {
      readonly point: string; readonly label: string; readonly status: string; readonly syn_sent: number | null;
      readonly synack_seen: number | null; readonly established: number | null; readonly closed: number | null;
      readonly tooltip: string; readonly evidence: readonly PacketRef[];
    }[];
    readonly buckets: readonly {
      readonly point: string; readonly start: number; readonly end: number; readonly coverage: string;
      readonly syns: number | null; readonly synacks: number | null; readonly established: number | null;
      readonly closed: number | null; readonly half_open: number | null;
    }[];
    readonly failed_handshake_count: number;
    readonly failed_handshakes: readonly { readonly point_a: string; readonly point_b: string; readonly time: number; readonly reason: string; readonly evidence: readonly PacketRef[] }[];
  };
  readonly security?: {
    readonly note: string;
    readonly thresholds?: Record<string, number>;
    readonly quality?: readonly { readonly point: string; readonly status: string; readonly reason: string }[];
  };
  readonly microbursts?: {
    readonly note: string; readonly total: number; readonly window_ms: number;
    readonly points: readonly {
      readonly point: string; readonly label: string; readonly peak_observed_mbps: number | null; readonly baseline_mbps: number;
      readonly threshold_mbps: number; readonly burst_count: number | null; readonly max_duration_ms: number | null;
      readonly frequency_hz: number | null; readonly tooltip: string; readonly evidence: readonly PacketRef[];
    }[];
    readonly items: readonly {
      readonly point: string; readonly start: number; readonly end: number; readonly corrected_start: number | null;
      readonly peak_mbps: number; readonly duration_ms: number | null; readonly min_interarrival_us: number | null;
      readonly tooltip: string; readonly evidence: readonly PacketRef[];
      readonly loss_associations: readonly { readonly segment: string; readonly ordering: string; readonly reason: string; readonly evidence: readonly PacketRef[] }[];
    }[];
  };
  readonly application_status?: readonly { readonly point: string; readonly reason: string | null; readonly cached: boolean }[];
  readonly proxies?: {
    readonly note: string;
    readonly transaction_count: number;
    readonly transactions: readonly {
      readonly device: string; readonly kind: string; readonly request?: string; readonly status: string; readonly reason: string | null;
      readonly client_flow?: string | null; readonly server_flow?: string | null;
      readonly request_dwell?: { readonly duration_ms: number | null; readonly uncertainty_ms: number | null; readonly reason: string | null };
      readonly response_dwell?: { readonly duration_ms: number | null; readonly uncertainty_ms: number | null; readonly reason: string | null };
      readonly evidence: readonly PacketRef[];
    }[];
    readonly legs?: readonly { readonly point: string; readonly flow: string; readonly packets: number; readonly retransmissions: number; readonly resets: number; readonly loss_by_class: Record<string, number> }[];
    readonly reset_notes?: readonly { readonly device: string; readonly status: string; readonly reason: string; readonly evidence: readonly PacketRef[] }[];
  };
  readonly tls?: readonly { readonly status: string; readonly device?: string | null; readonly point?: string; readonly sni: string; readonly reason: string; readonly evidence: readonly PacketRef[] }[];
  readonly confirmed_device_drop_count?: number;
  readonly confirmed_device_drops?: readonly { readonly device: string; readonly stage: string; readonly count: number }[];
  readonly vendor_device_event_count?: number;
  readonly vendor_device_events?: readonly { readonly device: string; readonly stage: string; readonly status: string; readonly reason: string; readonly evidence: readonly PacketRef[] }[];
  readonly f5?: {
    readonly note?: string;
    readonly connection_count?: number;
    readonly pairs: readonly { readonly flowid: string; readonly peerid: string; readonly tmm: string; readonly role: string; readonly reason: string | null; readonly client_flow?: string; readonly server_flow?: string }[];
    readonly requests: readonly { readonly device: string; readonly request: string; readonly request_dwell_ms: number | null; readonly clock_uncertainty_ms: number; readonly reason: string | null; readonly note: string; readonly evidence: readonly PacketRef[] }[];
    readonly resets: readonly { readonly device: string; readonly reason: string; readonly time: number | null; readonly evidence: readonly PacketRef[] }[];
  };
  readonly udp?: {
    readonly note: string; readonly scope: string; readonly total: number;
    readonly summary: readonly {
      readonly point: string; readonly protocol: string; readonly endpoint: string; readonly requests: number; readonly answered: number;
      readonly unanswered: number; readonly unknown: number; readonly no_response_expected: number;
      readonly p50_ms: number | null; readonly p95_ms: number | null; readonly max_ms: number | null;
    }[];
    readonly items: readonly {
      readonly point: string; readonly frame: number; readonly protocol: string; readonly status: string; readonly latency_ms: number | null;
      readonly hop: string | null; readonly reason: string; readonly tooltip: string; readonly evidence: readonly PacketRef[];
    }[];
  };
  readonly rtp?: {
    readonly note: string; readonly scope: string;
    readonly streams: readonly {
      readonly point: string; readonly flow: string; readonly direction: string; readonly ssrc: number; readonly packets: number; readonly gaps: number;
      readonly duplicates: number; readonly reordered: number; readonly jitter_p95_ms: number | null; readonly jitter_max_ms: number | null;
      readonly evidence: readonly PacketRef[];
    }[];
    readonly one_way: readonly { readonly point: string; readonly seen_direction: string; readonly reason: string; readonly stopping_hop: string | null; readonly evidence: readonly PacketRef[] }[];
  };
  readonly sip?: {
    readonly note: string; readonly scope: string; readonly total: number;
    readonly comparisons: readonly {
      readonly call_id: string; readonly hop: string; readonly status: string; readonly reason: string; readonly media_status?: string;
      readonly changes: Record<string, { readonly before: unknown; readonly after: unknown }>; readonly evidence: readonly PacketRef[]; readonly tooltip: string;
    }[];
    readonly one_way: readonly { readonly call_id: string; readonly hop: string; readonly reason: string; readonly evidence: readonly PacketRef[]; readonly tooltip: string }[];
  };
  readonly sequence_translations?: readonly {
    readonly ingress: string; readonly egress: string; readonly flow: string | null; readonly status: string; readonly reason: string | null;
    readonly boundary_forward_offset: number | null; readonly boundary_reverse_offset: number | null; readonly samples: number;
    readonly evidence: readonly PacketRef[];
  }[];
  readonly offload_points?: readonly { readonly point: string; readonly large_frames: number; readonly note: string }[];
  readonly fragment_points?: readonly { readonly point: string; readonly status: string; readonly datagrams: number; readonly physical_fragments: number }[];
  readonly tunnel_points?: readonly { readonly point: string; readonly selected: number; readonly unselected: number; readonly layer: string | null; readonly truncated_inner: number }[];
  readonly nexus_correlation?: PacketNexusCorrelation | null;
}

export interface PacketCycleReport {
  readonly note?: string;
  readonly scope?: string;
  readonly stall_ms?: number;
  readonly stalled_total?: number;
  readonly flow_ports?: readonly {
    readonly point: string; readonly flow: string; readonly port: number; readonly cycles: number; readonly stalled: number;
    readonly p95_ms: number | null; readonly max_ms: number | null; readonly before_p95_ms?: number | null;
    readonly event_p95_ms?: number | null; readonly recovery_p95_ms?: number | null; readonly per_hop_added?: string | null;
  }[];
  readonly periods?: readonly { readonly point: string; readonly flow: string; readonly period: string; readonly cycles: number; readonly p95_ms: number | null; readonly reason?: string }[];
  readonly pairs?: readonly { readonly hop: string; readonly component: string; readonly added_ms: number | null; readonly uncertainty_ms: number; readonly reason?: string }[];
  readonly top?: readonly {
    readonly point: string; readonly cycle_id?: string; readonly flow?: string; readonly port?: number; readonly request_first?: number;
    readonly period?: string; readonly cycle_ms?: number | null; readonly turnaround_ms?: number | null; readonly reason?: string;
    readonly evidence?: readonly PacketRef[];
  }[];
}

export type PacketNexusCorrelationStatus = "MATCH" | "AMBIGUOUS" | "NOT_EVALUABLE" | "NO_MATCH";

export interface PacketHopCorrelationCandidate {
  readonly device_id: string;
  readonly hostname: string | null;
  readonly cluster_member_ref?: string | null;
  readonly virtual_system?: string | null;
  readonly interface_id: string;
  readonly interface_name: string | null;
  readonly zone: string | null;
  readonly mac_address: string | null;
  readonly interface_addresses: readonly string[];
  readonly evidence: readonly string[];
  readonly matched_addresses: readonly string[];
  readonly reason: string;
}

export interface PacketHopCorrelation {
  readonly point_id: string;
  readonly point_label: string;
  readonly capture_id: string | null;
  readonly capture_name: string | null;
  readonly side: string;
  readonly status: PacketNexusCorrelationStatus;
  readonly reason: string;
  readonly evidence: readonly string[];
  readonly device_id?: string | null;
  readonly hostname?: string | null;
  readonly cluster_member_ref?: string | null;
  readonly virtual_system?: string | null;
  readonly interface_id?: string | null;
  readonly interface_name?: string | null;
  readonly zone?: string | null;
  readonly mac_address?: string | null;
  readonly interface_addresses?: readonly string[];
  readonly matched_addresses?: readonly string[];
  readonly capture_proves: {
    readonly point_id: string;
    readonly point_label: string;
    readonly capture_id: string | null;
    readonly capture_name: string | null;
    readonly side: string;
    readonly vendor_interface: string | null;
    readonly observed_macs: readonly string[];
    readonly observed_addresses: readonly string[];
  };
  readonly configured_inventory_says: Record<string, unknown> | null;
  readonly candidates: readonly PacketHopCorrelationCandidate[];
}

export interface PacketFlowCorrelationFirewallMatch {
  readonly device_id: string;
  readonly hostname: string | null;
  readonly cluster_member_ref?: string | null;
  readonly ingress_interface_id?: string | null;
  readonly ingress_interface_name?: string | null;
  readonly source_zone?: string | null;
  readonly egress_interface_id?: string | null;
  readonly egress_interface_name?: string | null;
  readonly destination_zone?: string | null;
  readonly status: PacketNexusCorrelationStatus;
  readonly reason: string;
  readonly evidence: readonly string[];
  readonly policy_id?: string | null;
  readonly policy_name?: string | null;
  readonly policy_status?: string | null;
  readonly policy_stale?: boolean;
  readonly policy_incomplete?: boolean;
  readonly rule_id?: string | null;
  readonly rule_number?: number | null;
  readonly rule_name?: string | null;
  readonly section_id?: string | null;
  readonly section_name?: string | null;
  readonly action?: string | null;
  readonly matched_source_objects?: readonly string[];
  readonly matched_destination_objects?: readonly string[];
  readonly matched_service_objects?: readonly string[];
  readonly evaluated_src?: string | null;
  readonly evaluated_sport?: number | null;
  readonly evaluated_dst?: string | null;
  readonly evaluated_dport?: number | null;
  readonly evaluated_proto?: string | null;
  readonly nat_applied?: boolean;
}

export interface PacketFlowCorrelation {
  readonly flow: string;
  readonly tuple: string;
  readonly proto: string;
  readonly src: string;
  readonly sport: number | null;
  readonly dst: string;
  readonly dport: number | null;
  readonly nat_confirmed: boolean;
  readonly pre_nat_src: string;
  readonly pre_nat_sport: number | null;
  readonly pre_nat_dst: string;
  readonly pre_nat_dport: number | null;
  readonly post_nat_src: string;
  readonly post_nat_sport: number | null;
  readonly post_nat_dst: string;
  readonly post_nat_dport: number | null;
  readonly status: PacketNexusCorrelationStatus;
  readonly reason: string;
  readonly evidence: readonly string[];
  readonly device_id?: string | null;
  readonly hostname?: string | null;
  readonly cluster_member_ref?: string | null;
  readonly ingress_interface_id?: string | null;
  readonly ingress_interface_name?: string | null;
  readonly source_zone?: string | null;
  readonly egress_interface_id?: string | null;
  readonly egress_interface_name?: string | null;
  readonly destination_zone?: string | null;
  readonly policy_id?: string | null;
  readonly policy_name?: string | null;
  readonly policy_status?: string | null;
  readonly policy_stale?: boolean;
  readonly policy_incomplete?: boolean;
  readonly rule_id?: string | null;
  readonly rule_number?: number | null;
  readonly rule_name?: string | null;
  readonly section_id?: string | null;
  readonly section_name?: string | null;
  readonly action?: string | null;
  readonly capture_proves: {
    readonly flow: string;
    readonly tuple: string;
    readonly proto: string;
    readonly pre_nat_src: string;
    readonly pre_nat_sport: number | null;
    readonly pre_nat_dst: string;
    readonly pre_nat_dport: number | null;
    readonly nat_confirmed: boolean;
    readonly post_nat_src: string;
    readonly post_nat_sport: number | null;
    readonly post_nat_dst: string;
    readonly post_nat_dport: number | null;
    readonly observed_point_ids: readonly string[];
  };
  readonly configured_policy_says: Record<string, unknown> | null;
  readonly candidates: readonly PacketFlowCorrelationFirewallMatch[];
  readonly firewall_matches: readonly PacketFlowCorrelationFirewallMatch[];
}

export interface PacketNexusCorrelation {
  readonly case_id: string;
  readonly correlated_at: string;
  readonly inventory_status: string;
  readonly inventory_reason: string;
  readonly policy_snapshot_count: number;
  readonly evidence_separation_note: string;
  readonly hops: readonly PacketHopCorrelation[];
  readonly flows: readonly PacketFlowCorrelation[];
}

export interface PacketCaseWorkspaceState {
  readonly version: string;
  readonly project: string;
  readonly captures: readonly PacketCaptureInventory[];
  readonly topology: PacketTopology;
  readonly report: PacketAnalysisReport | null;
  readonly suggested_topology?: Record<string, unknown> | null;
  readonly nexus_correlation?: PacketNexusCorrelation | null;
}

export interface PacketFlowItem {
  readonly flow: string;
  readonly tuple: string;
  readonly start: number | null;
  readonly end: number | null;
  readonly bytes: number;
  readonly points: number;
  readonly confirmed_device_drop: number;
  readonly impactful_loss: number;
  readonly recovered_loss: number;
  readonly unrecovered_loss: number;
  readonly handshake_blocked: number;
  readonly unknown_events: number;
  readonly capture_miss: number;
  readonly retrans_observations: number;
  readonly max_stall_ms: number | null;
  readonly max_rtt_ms: number | null;
  readonly has_reset: boolean;
  readonly handshake_incomplete: boolean;
  readonly reset_origin?: string | null;
  readonly handshake_rtt_ms?: number | null;
  readonly matching_unknown_reason?: string | null;
}

export interface PacketFlowPage {
  readonly total: number;
  readonly items: readonly PacketFlowItem[];
}

export interface PacketLadderView {
  readonly total: number;
  readonly flow_filters?: readonly { readonly capture_id: string; readonly file: string; readonly display_filter: string }[];
  readonly local_metrics?: readonly {
    readonly point: string;
    readonly tuple: string;
    readonly retransmissions: number;
    readonly zero_windows: number;
    readonly resets: number;
    readonly max_rtt_ms: number | null;
    readonly handshake: {
      readonly syn_frame: number;
      readonly synack_frame: number;
      readonly syn_to_synack_ms: number;
      readonly synack_to_ack_ms: number | null;
    } | null;
  }[];
  readonly items: readonly {
    readonly packet_key: string;
    readonly ts: number | null;
    readonly retrans: boolean;
    readonly seq: number;
    readonly ack: number;
    readonly flags: number;
    readonly length: number;
    readonly direction: string;
    readonly evidence: readonly PacketRef[];
  }[];
  readonly events: readonly { readonly point_b: string; readonly kind: string; readonly ts: number; readonly packet_key: string }[];
}

export interface PacketWaterfallBar {
  readonly label: string;
  readonly kind: string;
  readonly duration_ms: number | null;
  readonly start_ms: number | null;
  readonly uncertainty_ms: number | null;
  readonly reason: string | null;
  readonly note: string;
  readonly evidence: readonly PacketRef[];
}

export interface PacketWaterfallView {
  readonly items: readonly { readonly kind: string; readonly title: string; readonly bars: readonly PacketWaterfallBar[]; readonly note?: string }[];
  readonly requests: readonly { readonly index: number; readonly label: string; readonly time: number | null }[];
  readonly reason: string | null;
}

export interface PacketDiagnosisItem {
  readonly flow: string;
  readonly direction: string;
  readonly classification: string;
  readonly reason: string;
  readonly tooltip: string;
  readonly evidence: readonly PacketRef[];
  readonly metrics: {
    readonly max_receive_window: number | null;
    readonly max_bytes_in_flight: number | null;
    readonly zero_windows: number;
    readonly window_full: number;
    readonly window_shrinks: number;
    readonly ack_delay_p95_ms: number | null;
    readonly path_p95_ms: number | null;
    readonly service_ms: number | null;
    readonly loss_count: number;
  };
}

export interface PacketRetryPoint {
  readonly flow: string;
  readonly point: string;
  readonly direction: string;
  readonly tooltip: string;
  readonly reason: string | null;
  readonly rto: number | null;
  readonly fast: number | null;
  readonly spurious: number | null;
  readonly sack: number | null;
  readonly unknown: number | null;
  readonly duplicate_acks: number | null;
}

export interface PacketDiagnosisView {
  readonly items: readonly PacketDiagnosisItem[];
  readonly reason: string | null;
  readonly note?: string;
  readonly retransmissions?: {
    readonly points: readonly PacketRetryPoint[];
    readonly segments: readonly (PacketRetryPoint & { readonly hop: string })[];
    readonly events: readonly { readonly reason: string; readonly retry_kind: string; readonly point: string; readonly evidence: readonly PacketRef[] }[];
    readonly reordering: readonly { readonly location: string; readonly hop: string; readonly tooltip: string; readonly reason: string; readonly evidence: readonly PacketRef[] }[];
    readonly note: string;
  };
}

export interface PacketStreamChartsView {
  readonly point?: string;
  readonly direction?: string;
  readonly aligned?: boolean;
  readonly points: readonly { readonly point: string; readonly label: string }[];
  readonly packets: readonly {
    readonly frame: number;
    readonly time: number;
    readonly kind: string;
    readonly sequence: number | null;
    readonly length: number;
    readonly window_edge: number | null;
    readonly retransmission: boolean | null;
  }[];
  readonly buckets: readonly {
    readonly time: number;
    readonly throughput_bps: number | null;
    readonly rtt_ms: number | null;
    readonly data_frame: number | null;
    readonly rtt_frame: number | null;
    readonly coverage: string;
  }[];
  readonly note?: string;
  readonly reason: string | null;
  readonly sampling_note?: string;
  readonly clock_uncertainty_ms?: number | null;
}

export interface PacketTimeseriesView {
  readonly start: number;
  readonly end: number;
  readonly bucket_seconds: number;
  readonly bucket_count: number;
  readonly metrics: Record<string, { readonly label: string; readonly unit: string; readonly tooltip: string }>;
  readonly items: readonly ({
    readonly segment: string;
    readonly direction: string;
    readonly bucket: number;
    readonly start: number;
    readonly end: number;
    readonly coverage: string;
    readonly reason: string | null;
  } & Record<string, number | string | null>)[];
}

export interface PacketTopologyOverlayView {
  readonly segments: readonly {
    readonly id: string;
    readonly loss_percent: number | null;
    readonly p95_ms: number | null;
    readonly retrans_observations: number;
    readonly reason: string | null;
    readonly classes: Record<string, number>;
  }[];
  readonly nodes: readonly {
    readonly device: string;
    readonly observations: number;
    readonly retrans_observations: number;
    readonly supported_loss_events: number;
    readonly confirmed_device_drops: number;
    readonly findings: number;
  }[];
  readonly note: string;
}

export interface PacketTopologyInspectView {
  readonly findings: readonly PacketFinding[];
  readonly flows: PacketFlowPage;
}

export interface PacketEventPageView {
  readonly total: number;
  readonly items: readonly {
    readonly id: string;
    readonly kind: string;
    readonly reason: string;
    readonly ts: number | null;
    readonly recovery_ms: number | null;
    readonly evidence: readonly PacketRef[];
  }[];
}

export interface PacketDnsView {
  readonly items: readonly {
    readonly point: string;
    readonly frame: number;
    readonly question: string | null;
    readonly resolver: string;
    readonly status: string;
    readonly reason: string;
    readonly tooltip: string;
    readonly latency_ms: number | null;
    readonly location?: string;
    readonly evidence: readonly PacketRef[];
    readonly corrected: number | null;
  }[];
  readonly total: number;
  readonly summary: readonly {
    readonly point: string;
    readonly resolver: string;
    readonly queries: number;
    readonly matchable_queries: number;
    readonly answered: number;
    readonly timed_out_queries: number;
    readonly unknown_queries: number;
    readonly p50_ms: number | null;
    readonly p95_ms: number | null;
    readonly max_ms: number | null;
  }[];
  readonly points: readonly { readonly point: string; readonly label: string; readonly kind: string }[];
  readonly timeout_ms?: number;
  readonly tooltip: string;
  readonly note?: string;
  readonly reason: string | null;
}

export interface PacketFieldDiffView {
  readonly note: string;
  readonly items: readonly {
    readonly location: string;
    readonly tooltip: string;
    readonly packet_key: string;
    readonly fields: Record<string, { readonly before: unknown; readonly after: unknown; readonly status: string }>;
    readonly evidence: readonly PacketRef[];
  }[];
}

function paQuery(params: Record<string, string | number | boolean | null | undefined | readonly (string | number)[]>): string {
  const search = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === "") continue;
    if (Array.isArray(v)) {
      for (const item of v) search.append(k, String(item));
    } else {
      search.set(k, String(v));
    }
  }
  const s = search.toString();
  return s ? `?${s}` : "";
}

export const getPacketCaseState = (caseId: string) =>
  call<PacketCaseWorkspaceState>(`${PA}/cases/${encodeURIComponent(caseId)}/state`, "GET");
export const getPacketCaseCorrelation = (caseId: string) =>
  call<PacketNexusCorrelation>(`${PA}/cases/${encodeURIComponent(caseId)}/correlation`, "GET");

/** Packet Analysis > case > Device events (GET .../device-events): stored system-log facts of the path firewalls around one finding. */
export type PacketDeviceEventsResult = "CLEAN" | "EVENTS" | "NOT_COLLECTED" | "NOT_PERMITTED" | "UNKNOWN";
export interface PacketDeviceEventsFindingChoice {
  readonly id: string; readonly type: string | null; readonly severity: string | null; readonly hop: string | null;
  readonly hop_label: string | null; readonly start: string | null;
}
export interface PacketDeviceEventsFinding extends PacketDeviceEventsFindingChoice {
  readonly confidence: string | null; readonly direction: string | null; readonly end: string | null;
  readonly figures: {
    readonly event_count: number | null; readonly max_recovery_ms: number | null; readonly p95_ms: number | null;
    readonly max_ms: number | null; readonly loss_percent: number | null;
  };
}
export interface PacketDeviceEvent {
  readonly kind: "CPWD_LINE" | "CRASH_ALERT" | "GAP" | string;
  readonly source: string; readonly time: string | null; readonly time_basis: string; readonly device_time: string | null; readonly text: string;
}
export interface PacketDeviceEventsSource {
  readonly source_key: string; readonly last_outcome: string | null; readonly last_run_at: string | null;
  readonly chunks_overlapping: number; readonly lines_in_overlapping_chunks: number; readonly chunks_without_line_time: number;
}
export interface PacketDeviceEventsRow {
  readonly label: string; readonly kind: string | null; readonly on_hop: boolean;
  readonly points: readonly { readonly id: string; readonly label: string; readonly side: string | null; readonly on_hop: boolean }[];
  readonly link: {
    readonly state: "LINKED" | "NOT_LINKED"; readonly reason: string | null; readonly device_id: string | null; readonly device_name: string | null;
    readonly vendor: string | null; readonly correlation_status: string | null;
  };
  readonly result: PacketDeviceEventsResult; readonly result_reason: string | null; readonly event_count: number | null;
  readonly events: readonly PacketDeviceEvent[]; readonly sources: readonly PacketDeviceEventsSource[]; readonly coverage: "COVERED" | "NONE";
}
export interface PacketDeviceEventsView {
  readonly case_id: string; readonly masked: boolean; readonly window_seconds: number;
  readonly findings: readonly PacketDeviceEventsFindingChoice[];
  readonly finding: PacketDeviceEventsFinding | null;
  readonly hop: {
    readonly id: string; readonly label: string | null; readonly point_a: string | null; readonly point_b: string | null;
    readonly direction: string | null; readonly location: string | null; readonly reason: string | null;
  } | null;
  readonly window: { readonly from: string; readonly to: string; readonly start: string; readonly half_seconds: number } | null;
  readonly devices: readonly PacketDeviceEventsRow[];
  readonly notes: readonly string[];
}
export const getPacketCaseDeviceEvents = (caseId: string, params: { finding?: string; window_seconds?: number } = {}) =>
  call<PacketDeviceEventsView>(`${PA}/cases/${encodeURIComponent(caseId)}/device-events${paQuery(params)}`, "GET");
export const getPacketCaseFlows = (caseId: string, params: {
  offset?: number; limit?: number; search?: string; filter?: string; sort?: string; start?: number | null; end?: number | null;
} = {}) => call<PacketFlowPage>(`${PA}/cases/${encodeURIComponent(caseId)}/flows${paQuery(params)}`, "GET");
export const getPacketCaseFrames = (caseId: string, params: {
  flow: string; point: string; frames: readonly number[];
}) => call<readonly PacketRef[]>(`${PA}/cases/${encodeURIComponent(caseId)}/frames${paQuery(params)}`, "GET");
export const getPacketCaseDiagnosis = (caseId: string, flow: string) =>
  call<PacketDiagnosisView>(`${PA}/cases/${encodeURIComponent(caseId)}/diagnosis${paQuery({ flow })}`, "GET");
export const getPacketCaseCharts = (caseId: string, params: {
  flow: string; point?: string; direction?: string; start?: number | null; end?: number | null; local?: boolean;
}) => call<PacketStreamChartsView>(`${PA}/cases/${encodeURIComponent(caseId)}/charts${paQuery(params)}`, "GET");
export const getPacketCaseLadder = (caseId: string, params: {
  flow: string; offset?: number; limit?: number; start?: number | null; end?: number | null;
}) => call<PacketLadderView>(`${PA}/cases/${encodeURIComponent(caseId)}/ladder${paQuery(params)}`, "GET");
export const getPacketCaseWaterfall = (caseId: string, params: {
  flow: string; request_index?: number; start?: number | null; end?: number | null;
}) => call<PacketWaterfallView>(`${PA}/cases/${encodeURIComponent(caseId)}/waterfall${paQuery(params)}`, "GET");
export const getPacketCaseTimeseries = (caseId: string, params: {
  segment?: string; start?: number | null; end?: number | null;
} = {}) => call<PacketTimeseriesView>(`${PA}/cases/${encodeURIComponent(caseId)}/timeseries${paQuery(params)}`, "GET");
export const getPacketCaseTopologyOverlay = (caseId: string, params: {
  start?: number | null; end?: number | null;
} = {}) => call<PacketTopologyOverlayView>(`${PA}/cases/${encodeURIComponent(caseId)}/topology/overlay${paQuery(params)}`, "GET");
export const getPacketCaseTopologyInspect = (caseId: string, params: {
  point?: string; segment?: string; start?: number | null; end?: number | null;
}) => call<PacketTopologyInspectView>(`${PA}/cases/${encodeURIComponent(caseId)}/topology/inspect${paQuery(params)}`, "GET");
export const getPacketCaseFindings = (caseId: string, params: {
  hop?: string; start?: number | null; end?: number | null;
} = {}) => call<{ readonly items: readonly PacketFinding[] }>(`${PA}/cases/${encodeURIComponent(caseId)}/findings${paQuery(params)}`, "GET");
export const getPacketCaseEvents = (caseId: string, params: {
  point_a: string; point_b: string; direction: string; offset?: number; limit?: number; start?: number | null; end?: number | null;
}) => {
  // The service (like the reference /api/events) names the boundary points `a` and `b`.
  const { point_a, point_b, ...rest } = params;
  return call<PacketEventPageView>(
    `${PA}/cases/${encodeURIComponent(caseId)}/events${paQuery({ a: point_a, b: point_b, ...rest })}`, "GET");
};
export const getPacketCaseDns = (caseId: string, params: {
  offset?: number; limit?: number; point?: string; resolver?: string; start?: number | null; end?: number | null;
} = {}) => call<PacketDnsView>(`${PA}/cases/${encodeURIComponent(caseId)}/dns${paQuery(params)}`, "GET");
export const getPacketCaseFieldDiff = (caseId: string, packetKey: string) =>
  call<PacketFieldDiffView>(`${PA}/cases/${encodeURIComponent(caseId)}/field-diff${paQuery({ packet_key: packetKey })}`, "GET");
export const getPacketCaseFindingsExport = (caseId: string) =>
  call<Record<string, unknown>>(`${PA}/cases/${encodeURIComponent(caseId)}/export/findings`, "GET");
export const savePacketCaseTopology = (caseId: string, topology: PacketTopology) =>
  call<PacketCaseWorkspaceState>(`${PA}/cases/${encodeURIComponent(caseId)}/topology`, "PUT", topology);
export type PacketExportFormat = "html" | "json" | "csv" | "xlsx";
/** Browser download of the engine export (raw-view roles only; the server ledgers it before the first byte). */
export const packetCaseExportHref = (caseId: string, format: PacketExportFormat) =>
  `${PA}/cases/${encodeURIComponent(caseId)}/export/download${paQuery({ format })}`;
/**
 * Browser download of the expert report workbook (Özet, Zaman Çizelgesi, Hop Analizi, Örnek Olaylar, Yöntem ve Veri).
 * Every packet-analysis reader may use it: raw-view roles get the engine values, every other reader (the replay viewer
 * included) the masked projection -- the server decides, the page never does. {@code start}/{@code end} (epoch seconds)
 * set the event window; without them the engine's first onset splits before and after.
 */
export const packetCaseExpertReportHref = (
  caseId: string,
  params: { start?: number | null; end?: number | null; top?: number } = {},
) => `${PA}/cases/${encodeURIComponent(caseId)}/export/expert-report${paQuery(params)}`;
export const getPacketCaseSessionEvidence = (caseId: string, params: { point: string; start: number; end: number }) =>
  call<{ readonly evidence: readonly PacketRef[]; readonly note: string }>(
    `${PA}/cases/${encodeURIComponent(caseId)}/sessions/evidence${paQuery(params)}`, "GET");
export const getPacketCaseReferenceTemplate = (caseId: string) =>
  call<Record<string, unknown>>(`${PA}/cases/${encodeURIComponent(caseId)}/reference/template`, "GET");
export interface PacketCompareResult {
  readonly items?: readonly {
    readonly id: string; readonly description: string; readonly metric: string;
    readonly expected: unknown; readonly value: unknown; readonly status: string; readonly reason?: string | null;
  }[];
  readonly counts?: Record<string, number>;
  readonly diff?: {
    readonly metrics: readonly unknown[]; readonly findings: readonly unknown[];
    readonly metric_difference_count: number; readonly truncated: boolean; readonly note: string;
  };
}
export const comparePacketCase = (caseId: string, input: { reference?: unknown; against?: unknown }) =>
  call<PacketCompareResult>(`${PA}/cases/${encodeURIComponent(caseId)}/compare`, "POST", input);
