package com.securityexpert.nexus.ui2.packetanalysis.privacy;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The classification of every string-bearing key in Packet Analysis responses (the inventory table in
 * {@code ui2/packet-analysis/README.md}, "Masked projection"). A key is classified by name; a few keys depend on the
 * enclosing context (the nearest enclosing key listed in {@link #CONTEXTS}) or on a sibling ({@code vendor}).
 * A key that is not listed is {@link Field#UNCLASSIFIED}: its strings are tokenized, never passed through.
 */
public final class PayloadFields {

    /** How a string value is treated in the masked projection. */
    public enum Field {
        /** Engine vocabulary: enum codes, opaque ids, hashes, timestamps. Unchanged. */
        KEEP,
        /** Engine prose, labels, Wireshark filters. Kept, but payload values seen in the response are replaced. */
        TEXT,
        /** Addresses and flow tuples. Kept here; the service's IP masker rewrites them for the replay viewer. */
        ADDRESS,
        /** DNS question, HTTP Host, TLS SNI: a {@code HOST-} token, port kept. */
        HOST,
        /** An HTTP request line (method, tokenized target, version), or a host when the value is an SNI. */
        HTTP_REQUEST,
        /** A request URI: each path segment and the query become tokens. */
        URI,
        /** SIP Call-ID: a {@code CALL-} token. */
        CALL_ID,
        /** SIP Contact / Via / From / To and similar identities: a {@code SIP-} token. */
        SIP_IDENTITY,
        /** Certificate subject / issuer names: a {@code CERT-} token. */
        CERT_NAME,
        /** Vendor trailer free text (F5 reset cause text): a {@code VTXT-} token. */
        VENDOR_TEXT,
        /** Vendor trailer names (F5 virtual server, Check Point fw monitor interface): a {@code VNAME-} token. */
        VENDOR_NAME,
        /** Names mode: an import source or capture file name, as the capture-name pseudonym. */
        CAPTURE_NAME,
        /** Names mode: a capture or Fortinet interface name, as the interface pseudonym. */
        INTERFACE_NAME,
        /** Names mode: a file system path, redacted. */
        FILE_PATH,
        /** Not classified: an opaque {@code TXT-} token (fail closed). */
        UNCLASSIFIED
    }

    /** Keys whose subtree is a context for {@link #field}; container keys are contexts too. */
    static final Set<String> CONTEXTS = Set.of("fortinet_conversion", "source_metadata", "resets");

    /** Keys whose whole subtree (strings, lists, before/after maps) takes one treatment. */
    static final Map<String, Field> CONTAINERS = Map.ofEntries(
            Map.entry("subject", Field.CERT_NAME),
            Map.entry("issuer", Field.CERT_NAME),
            Map.entry("sip.Contact", Field.SIP_IDENTITY),
            Map.entry("sip.Via", Field.SIP_IDENTITY),
            Map.entry("sip.From", Field.SIP_IDENTITY),
            Map.entry("sip.To", Field.SIP_IDENTITY),
            Map.entry("sip.Request-Line", Field.SIP_IDENTITY),
            Map.entry("sdp.connection_info.address", Field.ADDRESS),
            Map.entry("sdp.media.port", Field.KEEP),
            Map.entry("xff", Field.ADDRESS));

    private static final Map<String, Field> FIELDS = new HashMap<>();

    private static void put(Field field, String... keys) {
        for (String key : keys) {
            if (FIELDS.put(key, field) != null) throw new IllegalStateException("Duplicate classification: " + key);
        }
    }

    static {
        // ---- payload-derived values (HTTP, TLS, SIP, DNS, vendor trailers) ----
        put(Field.HOST, "host", "sni", "question", "qname", "dns_name", "server_name");
        put(Field.HTTP_REQUEST, "line", "request");
        put(Field.URI, "uri", "http.request.uri");
        put(Field.CALL_ID, "call_id", "sip.Call-ID", "Call-ID");
        put(Field.SIP_IDENTITY, "contact", "via", "sip_from", "sip_to");
        put(Field.VENDOR_TEXT, "f5ethtrailer.rstcausetxt");
        put(Field.VENDOR_NAME, "f5ethtrailer.vip", "fw1.interface");
        put(Field.INTERFACE_NAME, "interface");
        put(Field.FILE_PATH, "path");
        // F5 / Check Point trailer fields that are codes, ids or numbers.
        put(Field.KEEP, "f5ethtrailer.flowid", "f5ethtrailer.peerid", "f5ethtrailer.peerport",
                "f5ethtrailer.peerremoteport", "f5ethtrailer.peerlocalport", "f5ethtrailer.ingress",
                "f5ethtrailer.tmm", "f5ethtrailer.slot", "f5ethtrailer.rstcause", "f5ethtrailer.rstcauseval",
                "fw1.direction", "fw1.chain", "fw1.uuid", "http.request.method", "http.request.version",
                "sip.CSeq.method", "sip.Method", "sip.Status-Code", "sip.CSeq.seq", "sip.Via.branch");
        put(Field.ADDRESS, "f5ethtrailer.peeraddr", "f5ethtrailer.peeraddr6", "f5ethtrailer.peerremoteaddr",
                "f5ethtrailer.peerremoteaddr6", "f5ethtrailer.peerlocaladdr", "f5ethtrailer.peerlocaladdr6");

        // ---- addresses and flow tuples (rewritten by the IP masker for the replay viewer) ----
        put(Field.ADDRESS, "src", "dst", "address", "addresses", "actual_address", "advertised_address", "before",
                "after", "client", "server", "client_cidrs", "client_ips", "server_ips", "source", "tuple_a",
                "tuple_b", "flow", "flows", "client_flow", "server_flow", "resolver", "resolvers", "flow_id",
                "tuple_key", "reverse_tuple", "tuple", "canon", "canon_reverse", "source_cidr", "client_ip",
                "server_ip",
                "network", "pre_nat_src", "pre_nat_dst", "post_nat_src", "post_nat_dst", "translated_src",
                "translated_dst", "observed_addresses", "matched_addresses", "interface_addresses", "next_hop",
                "destination", "answers", "group", "cidr", "mac_address", "observed_macs", "member_macs");

        // ---- engine prose, labels and filters ----
        put(Field.TEXT, "aggregate_note", "badge", "baseline_method", "boundary_loss_heading", "caveat",
                "clock_caveat", "content_filter", "display_filter", "flow_filter", "filter_note", "display_label",
                "downstream_summary", "evidence_note", "evidence_time_source", "explanation", "headline",
                "icmp_feedback", "id", "ip_id_note", "label", "limitations", "location", "max_added_reason",
                "meaning", "note", "notes", "per_hop_added", "quality_note", "quality_notes", "quality_reason",
                "range_note", "reason", "reasons", "rx_share_reason", "scope", "segment_label", "silence_note",
                "summary", "symptom_note", "time_source", "tooltip", "verdict", "warnings", "warning",
                "window_reason", "title", "unknown", "media_reason", "message", "error", "detail", "text",
                "forward_labels", "reverse_labels", "forward", "reverse", "evidence_rank", "observation_point",
                "description", "hint", "help", "lead", "lead_degradation", "headline_detail", "caption", "subtitle",
                "heading", "action_text", "suggestion", "recommendation", "inventory_reason",
                "evidence_separation_note", "expected", "value",
                // device, point and capture names: the name pseudonymizer rewrites them for the replay viewer
                "device", "file", "name", "project", "capture_name", "display_name", "point_label", "hostname", "zone",
                "rule_name", "policy_name", "section_name", "interface_name", "vendor_interface",
                // neXus correlation (inventory and policy): names the name pseudonymizer rewrites for the replay viewer
                "cluster_member_ref", "source_zone", "destination_zone", "ingress_zone", "egress_zone",
                "ingress_interface_name", "egress_interface_name", "matched_source_objects",
                "matched_destination_objects", "matched_service_objects", "policy_warning", "virtual_system",
                "virtual_systems", "member");

        // ---- engine vocabulary: enums, opaque ids, hashes, times ----
        put(Field.KEEP, "a", "b", "adapter", "after_sha256", "before_sha256", "payload_sha256", "bounding_points",
                "bypassed_points", "capture_id", "capture_ids", "cause", "classification", "clock_domain",
                "component", "conclusion", "confidence", "coverage", "cycle_id", "direction", "domain",
                "engine_version", "evidence_stage", "finding_ids", "first_point", "hop", "hops", "ip_id_pattern",
                "kind", "latency_confidence", "loss_hop", "matching_mode", "method", "metric", "onset_status",
                "packet_key", "payload_transform", "period", "point", "point_a", "point_b", "points",
                "recovery_class", "related_loss_segments", "retry_kind", "return_point", "schema_name", "segment",
                "severity", "side", "stage", "stage_source", "status", "tcp_options", "translation", "type", "unit",
                "upstream_status", "vendor", "utc", "local", "vendor_stage", "media_status", "proto", "protocol",
                "fingerprint", "fingerprints", "case_id", "job_id", "state", "format", "created_at", "expires_at",
                "uploaded_at", "requested_at", "started_at", "finished_at", "generated_at", "sha256", "version",
                "segments_id", "client_id", "server_id", "flowid", "peerid", "tmm", "closed_by", "sender",
                "selected", "origin", "mode", "sort", "filter_by", "timezone", "report_timezone", "clock_group",
                "clock_confidence", "link_type", "dlt", "encapsulation", "family", "role", "verdict_code", "code",
                "rule_id", "policy_id", "section_id", "device_id", "interface_id", "ingress_interface_id",
                "egress_interface_id", "evidence", "nat_point_a", "nat_point_b", "point_id", "capture",
                "timestamp_precision", "time_precision", "resolution", "path_kind", "category", "check",
                "tie_break", "order", "basis", "close_kind", "closed_label", "closed_short", "event_id",
                "fragment_id", "path_key", "prime_suspects", "proof_id", "reassembly", "segments", "source_domain",
                "action", "collected_at", "policy_collected_at", "policy_status", "base_key", "far_response_key",
                "frame_hash", "payload_hash", "response_key", "signature", "exported_at", "correlated_at",
                "inventory_status", "observed_points");
    }

    private PayloadFields() {
    }

    /** The context for the children of a map found under {@code key}, given the enclosing context. */
    public static String context(String key, String enclosing) {
        if (key != null && (CONTEXTS.contains(key) || CONTAINERS.containsKey(key))) return key;
        return enclosing;
    }

    /** The treatment of a string under {@code key}, inside {@code context}, in the map {@code owner}. */
    public static Field field(String key, String context, Map<?, ?> owner) {
        if (context != null && CONTAINERS.containsKey(context)) return CONTAINERS.get(context);
        if (key == null) return Field.UNCLASSIFIED;
        Field container = CONTAINERS.get(key);
        if (container != null) return container;
        if ("fortinet_conversion".equals(context) || "source_metadata".equals(context)) {
            switch (key) {
                case "source", "clock_group" -> {
                    return Field.CAPTURE_NAME;
                }
                case "path" -> {
                    return Field.FILE_PATH;
                }
                default -> {
                }
            }
        }
        if ("resets".equals(context) && "reason".equals(key)) return Field.VENDOR_TEXT;
        if (owner != null && "fortinet".equals(owner.get("vendor"))
                && ("label".equals(key) || "vendor_stage".equals(key))) {
            return Field.INTERFACE_NAME;
        }
        return FIELDS.getOrDefault(key, Field.UNCLASSIFIED);
    }

    /** True when a key has an explicit classification (outside of any context). */
    public static boolean classified(String key) {
        return FIELDS.containsKey(key) || CONTAINERS.containsKey(key);
    }
}
