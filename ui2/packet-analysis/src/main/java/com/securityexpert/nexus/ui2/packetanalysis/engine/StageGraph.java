package com.securityexpert.nexus.ui2.packetanalysis.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The explicit stage graph that replaces the implicit call order of the reference {@code report.analyze}.
 * Every stage names the reference function it ports, the DuckDB tables/views it reads and those it creates
 * or rewrites; construction verifies that each read is produced by an earlier stage or is a base table of
 * the ingested project. Stages run in the reference order; every reference stage is ported.
 */
public final class StageGraph {
    /** Base tables written by ingest/topology, read but never created by the engine. */
    public static final Set<String> BASE = Set.of("packets", "captures", "settings", "excluded_frames");

    public record Stage(String name, String reference, List<String> reads, List<String> creates, String snapshot,
            Consumer<EngineContext> body) {
    }

    private final List<Stage> stages = new ArrayList<>();

    private StageGraph add(String name, String reference, List<String> reads, List<String> creates, String snapshot,
            Consumer<EngineContext> body) {
        stages.add(new Stage(name, reference, reads, creates, snapshot, body));
        return this;
    }


    public List<Stage> stages() {
        return List.copyOf(stages);
    }

    /** The core graph in the reference order of {@code report.analyze} and {@code _run_secondary_detectors}. */
    public static StageGraph core() {
        StageGraph g = new StageGraph()
                .add("selection", "analysis.selection.select_observations (+ capture_layers.apply_layers + fragments.normalize_fragments)",
                        List.of("packets", "captures", "settings"),
                        List.of("obs", "byte_flows", "analysis_scope", "f5_connections", "layer_cache", "layer_packets",
                                "wire_observations", "fragment_members", "fragment_groups"), "selection",
                        Preparation::selectObservations)
                .add("nat_suggestions", "analysis.matching._find_nat_suggestions", List.of("obs", "captures"),
                        List.of("nat_candidates"), null, Preparation::findNatSuggestions)
                .add("canonical_tuples", "analysis.matching._canonicalize_tuples", List.of("obs", "f5_connections"),
                        List.of("tuple_lookup", "obs"), null, Preparation::canonicalizeTuples)
                .add("index_scope", "analysis.selection.restrict_index_scope", List.of("obs", "analysis_scope"),
                        List.of("client_tuples", "scoped_flows", "index_scope", "obs"), null, Preparation::restrictIndexScope)
                .add("sequence_translation", "translation.normalize_sequences", List.of("obs"),
                        List.of("sequence_models", "translation_links", "sequence_map", "obs"), null,
                        Preparation::normalizeSequences)
                .add("proxy_legs", "matching.partition_proxy_legs", List.of("obs"), List.of("proxy_leg_names", "obs"), null,
                        Preparation::partitionProxyLegs)
                .add("prefix_keys", "analysis.matching.prepare (prefix_lengths, nat_point_tuples)", List.of("obs"),
                        List.of("prefix_lengths", "obs", "nat_point_tuples"), null, Preparation::prefixKeys)
                .add("udp_index", "udp_index.index_udp", List.of("obs", "captures", "settings"),
                        List.of("udp_decode_cache", "udp_decoded"), null, UdpIndex::stage)
                .add("sip_keys", "analysis.matching.prepare (SIP packet keys)", List.of("obs", "udp_decoded"), List.of("obs"),
                        null, Preparation::sipKeys)
                .add("occurrences", "matching.prepare_occurrences", List.of("obs"),
                        List.of("span_duplicates", "obs", "calibration"), null, Preparation::prepareOccurrences)
                .add("byte_calibration", "byte_ranges.add_byte_calibration", List.of("obs", "calibration"),
                        List.of("byte_calibration", "calibration"), "prepare", Preparation::byteCalibration)
                .add("application_index", "application_index.index_applications (isolated tshark pass + apply_tcp_analysis)",
                        List.of("obs", "captures", "settings"), List.of("app_protocol", "app_protocol_cache", "tcp_point_analysis",
                                "tcp_state_raw", "tcp_state_mapping", "obs"), null, ApplicationIndex::stage)
                .add("clocks", "analysis.clocks.align", List.of("obs", "calibration", "captures"),
                        List.of("clock_values", "obs"), "align", Clocks::align)
                .add("match_occurrences", "matching.match_occurrences(obs)", List.of("obs", "calibration"),
                        List.of("occurrences", "repeated_keys", "time_shifts", "repeat_obs", "anchor_points", "anchors",
                                "balanced_pairs", "unmatched_repeats", "before_candidates", "after_candidates", "nearest",
                                "assigned", "obs"), null, ctx -> Matching.matchOccurrences(ctx, "obs"))
                .add("modified_payloads", "integrity.payloads.link_modified_payloads", List.of("obs"),
                        List.of("modified_payload_pairs", "obs"), null, Matching::linkModifiedPayloads)
                .add("tcp_sessions", "matching.link_tcp_sessions", List.of("obs", "translation_links", "byte_calibration"),
                        List.of("session_map", "obs"), null, Matching::linkTcpSessions)
                .add("translation_unknown", "matching.propagate_translation_unknown", List.of("obs", "sequence_models"),
                        List.of("obs"), "matching", Matching::propagateTranslationUnknown)
                .add("byte_ranges", "byte_ranges.prepare_byte_ranges", List.of("obs", "calibration", "byte_calibration"),
                        List.of("byte_flows", "range_intervals", "range_atoms", "byte_work_units", "byte_obs", "byte_pairs",
                                "byte_links", "byte_missing_atoms", "byte_missing", "byte_recovered_atoms", "byte_recoveries",
                                "observation_matches"), "bytes", Matching::prepareByteRanges)
                .add("delivery_prepare", "delivery.prepare_delivery (observation_matches filter)",
                        List.of("obs", "observation_matches"),
                        List.of("delivery_edges", "delivery_repeats", "delivery_candidates", "delivery_id_policy",
                                "delivery_rejections", "unfiltered_observation_matches", "observation_matches"), null,
                        Matching::prepareDelivery)
                .add("flow_filters", "evidence.prepare_flow_filters", List.of("obs"), List.of("flow_filters"), "flow_filters",
                        ctx -> Evidence.prepareFlowFilters(ctx.db))
                .add("proxy_correlate", "proxy_analysis.correlate + proxy_waterfall.add_dwell", List.of("obs", "app_protocol"),
                        List.of("proxy_requests", "http_pdus", "proxy_pairs"), null, Proxies::correlateStage)
                .add("suggestion_evidence", "report.analyze (NAT suggestion evidence)", List.of("obs", "flow_filters"),
                        List.of(), null, CoreReport::suggestionEvidence)
                .add("coverage", "report.analyze (coverage and window)", List.of("obs"), List.of(), null,
                        CoreReport::coverageAndWindow)
                .add("events_table", "report.analyze (events table, inventories)", List.of("captures"), List.of("events"), null,
                        CoreReport::eventsAndInventories)
                .add("device_proofs", "device_drops.prepare_proofs",
                        List.of("obs", "captures"), List.of("device_proofs", "vendor_points", "inspection_candidates"), null,
                        DeviceDrops::prepareProofs)
                .add("segments", "analysis.segments.build_segments + analysis.classification",
                        List.of("obs", "observation_matches", "nat_point_tuples", "tuple_lookup", "fragment_groups", "byte_flows",
                                "byte_missing", "byte_missing_atoms", "byte_obs", "byte_recoveries", "events", "device_proofs"),
                        List.of("missing", "byte_later", "recoveries", "retry_counts", "acked", "failures", "events",
                                "device_assignments", "device_proofs"), null,
                        Segments::buildSegments)
                .add("device_unplaced", "device_drops.add_unplaced",
                        List.of("device_proofs", "obs", "events"), List.of("events", "device_proofs"), null,
                        DeviceDrops::addUnplaced)
                .add("flow_summary_quality", "analysis.segments.build_flow_summary_and_quality",
                        List.of("obs", "events", "byte_flows", "byte_obs", "index_scope", "captures"), List.of("flow_summary"), null,
                        Segments::buildFlowSummaryAndQuality)
                .add("rank_findings", "report._run_secondary_detectors (severity/time sort)", List.of(), List.of(), null,
                        CoreReport::rankFindings)
                .add("headlines", "headlines.add_headlines", List.of("events", "obs"), List.of(), "core",
                        Headlines::addHeadlines)
                .add("timeseries", "timeseries.build_timeseries",
                        List.of("obs", "observation_matches", "events"), List.of("segment_buckets", "bucket_obs"), null,
                        Timeseries::build)
                .add("lifecycle", "security.build_lifecycle",
                        List.of("obs", "events"),
                        List.of("session_syns", "session_replies", "session_acks", "session_lifecycle", "session_points",
                                "session_buckets", "lifecycle_window", "handshake_stops"), null,
                        Security::lifecycle)
                .add("onsets", "onset.add_onsets + proxy_analysis.restrict_onset_domains",
                        List.of("segment_buckets", "events", "observation_matches", "obs"), List.of("onset_candidates"), null,
                        Onsets::build)
                .add("microbursts", "microbursts.build_microbursts",
                        List.of("wire_observations", "captures"),
                        List.of("burst_clocks", "burst_wire", "microburst_windows", "burst_baseline", "burst_candidates",
                                "microbursts"), null,
                        Microbursts::build)
                .add("integrity", "integrity.analyze_integrity",
                        List.of("obs", "observation_matches", "modified_payload_pairs", "events", "captures"),
                        List.of("integrity_window", "integrity_obs", "integrity_edges"), null, Integrity::analyze)
                .add("fragment_findings", "fragments.fragment_findings", List.of("obs", "fragment_groups"), List.of(), null,
                        FragmentFindings::run)
                .add("downstream_grouping",
                        "report._run_secondary_detectors (integrity findings) + integrity.group_downstream_findings",
                        List.of(), List.of(), null, Integrity::groupDownstreamStage)
                .add("proxy_resets", "proxy_resets.reset_checks", List.of("obs", "events", "proxy_requests", "proxy_pairs"),
                        List.of("proxy_opaque_edges"), null, Proxies::resetStage)
                .add("tls", "tls_analysis.tls_checks", List.of("obs", "proxy_requests", "proxy_pairs", "app_protocol"),
                        List.of(), null, Tls::stage)
                .add("security", "security.security_checks",
                        List.of("captures", "session_lifecycle", "session_buckets", "events", "device_proofs", "obs"),
                        List.of("security_sessions", "security_cohorts", "security_points", "security_fanout", "pressure_baseline",
                                "pressure_spikes"), null,
                        Security::checks)
                .add("udp", "udp.build_udp", List.of("obs", "udp_decoded", "udp_decode_cache", "events", "captures"),
                        List.of("udp_protocol", "udp_transactions"), null, UdpTransactions::stage)
                .add("rtp", "rtp.build_rtp", List.of("obs", "udp_decoded", "events", "observation_matches"),
                        List.of("rtp_input", "rtp_packets", "rtp_streams"), null, Rtp::stage)
                .add("sip", "sip.build_sip", List.of("obs", "udp_decoded", "udp_decode_cache", "rtp_input"),
                        List.of("sip_messages", "rtp_call_packets"), null, Sip::stage)
                .add("host_path", "host_path.host_path",
                        List.of("observation_matches", "obs"),
                        List.of("host_edges", "host_transit", "host_path_series", "host_bursts"), null,
                        HostPath::hostPath)
                .add("cycles", "cycles.build_cycles",
                        List.of("obs"),
                        List.of("cycle_points", "cycle_windows", "cycle_series", "cycle_flow_ports", "cycle_periods",
                                "cycle_edges", "cycle_pairs", "cycle_top", "cycle_top_points"), null,
                        Cycles::buildCycles)
                .add("host_summary", "host_path.finish_host_summary",
                        List.of("cycle_points"), List.of(), null,
                        HostPath::finishHostSummary)
                .add("polish_findings", "host_path.polish_findings",
                        List.of(), List.of(), null,
                        HostPath::polishFindings)
                .add("chronic_losses", "host_path.classify_chronic_losses",
                        List.of("events", "obs"), List.of(), null,
                        HostPath::classifyChronicLosses)
                .add("cycle_findings", "cycles.cycle_findings",
                        List.of("cycle_pairs", "cycle_windows", "obs"), List.of("cycle_degradations"), null,
                        Cycles::cycleFindings)
                .add("delivery", "delivery.delivery_report",
                        List.of("delivery_rejections", "cycle_edges", "obs", "delivery_candidates", "events"),
                        List.of("delivery_positions", "delivery_events", "delivery_series", "adjacent_match_rates"), null,
                        DeliveryReport::deliveryReport)
                .add("hold_release", "hold_release.hold_release",
                        List.of("obs"), List.of("copy_trains"), null,
                        DeliveryReport::holdRelease)
                .add("host_loss_groups", "host_path.group_host_losses",
                        List.of(), List.of(), null,
                        HostPath::groupHostLosses)
                .add("aggregates", "aggregates.add_aggregates",
                        List.of("cycle_points", "cycle_pairs", "obs", "packets", "host_transit"),
                        List.of("aggregate_boundaries", "aggregate_cycles", "aggregate_added", "aggregate_copies",
                                "aggregate_host_clocks", "aggregate_host_packets"), null,
                        Aggregates::addAggregates)
                .add("incident", "incident.build_incident",
                        List.of("host_path_series", "delivery_series", "obs", "cycle_top_points", "cycle_windows",
                                "cycle_pairs", "cycle_top", "cycle_edges", "cycle_flow_ports", "cycle_periods",
                                "adjacent_match_rates", "delivery_events"),
                        List.of("cycle_details", "cycle_flow_detail"), null,
                        Incident::buildIncident)
                .add("flow_columns", "pilot.flow_columns",
                        List.of("obs", "flow_summary"), List.of("flow_summary"), null,
                        SessionEvents::flowColumns)
                .add("session_events", "session_events.session_report + fold_storm_resets",
                        List.of("obs", "events"), List.of("sess_obs", "sess_flows"), null,
                        SessionEvents::sessionReport)
                .add("f5", "vendors.f5_report", List.of("obs", "f5_connections"), List.of(), null, F5Vendor::stage)
                .add("device_report", "device_drops.device_report",
                        List.of("device_proofs", "obs"), List.of(), "det_a",
                        DeviceDrops::deviceReport);
        g.validate();
        return g;
    }

    void validate() {
        Set<String> available = new LinkedHashSet<>(BASE);
        for (Stage s : stages) {
            for (String read : s.reads()) {
                if (!available.contains(read) && !s.creates().contains(read)) {
                    throw new IllegalStateException("Stage " + s.name() + " reads " + read + " before any stage creates it");
                }
            }
            available.addAll(s.creates());
        }
    }
}
