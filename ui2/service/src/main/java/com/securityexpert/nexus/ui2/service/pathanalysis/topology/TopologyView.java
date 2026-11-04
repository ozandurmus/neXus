package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code topology_read} body. Raw for every role that reads inventory raw; for the replay viewer (aiview) the
 * same body goes through {@code PrivacyMaskingResponseBodyAdvice}, so its keys are chosen to land on the existing
 * masking rules: {@code network}/{@code address}/{@code assumed_subnet} -> {@code SubnetPreservingIpMasker},
 * {@code hostname} (with {@code device_id}) and {@code cluster_member_ref} -> {@code TopologyNamePseudonymizer},
 * {@code routing_context} -> the shared "vrf" pseudonym, {@code interface_name} and {@code zone} -> their
 * pseudonyms. The override note is left out of a masked body entirely (D-8), and the advice also withholds it.
 */
public final class TopologyView {

    private TopologyView() {
    }

    public static Map<String, Object> topology(Optional<TopologyGraph> held,
            Optional<TopologyRepository.SnapshotHeader> latest) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", held.isPresent() ? "BUILT" : "NOT_BUILT");
        body.put("latest_snapshot", latest.map(TopologyView::header).orElse(null));
        // A held graph older than the newest persisted header (another build happened elsewhere) is flagged, not hidden.
        body.put("held_is_latest", held.isPresent() && latest.isPresent()
                && held.get().snapshotId().equals(latest.get().snapshotId()));
        if (held.isEmpty()) {
            for (String key : List.of("nodes", "segments", "attachments", "direct_edges", "next_hops", "conflicts")) {
                body.put(key, List.of());
            }
            return body;
        }
        TopologyGraph g = held.get();
        body.put("snapshot_id", g.snapshotId());
        body.put("built_at", g.builtAt().toString());
        body.put("counts", counts(g.counts()));
        body.put("nodes", g.nodes().stream().map(TopologyView::node).toList());
        body.put("segments", g.segments().stream().map(TopologyView::segment).toList());
        body.put("attachments", g.attachments().stream().map(TopologyView::attachment).toList());
        body.put("direct_edges", g.segments().stream()
                .filter(s -> s.kind() == TopologyGraph.SegmentKind.POINT_TO_POINT).map(s -> {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("segment_id", s.segmentId());
                    edge.put("node_ids", g.attachmentsOn(s.segmentId()).stream().map(TopologyGraph.Attachment::nodeId)
                            .distinct().sorted().toList());
                    return edge;
                }).toList());
        body.put("next_hops", g.nextHops().stream().map(e -> {
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("from_node_id", e.fromNodeId());
            edge.put("segment_id", e.segmentId());
            edge.put("to_node_id", e.toNodeId());
            edge.put("route_count", e.routeCount());
            return edge;
        }).toList());
        body.put("conflicts", g.conflicts().stream().map(TopologyView::conflict).toList());
        return body;
    }

    public static Map<String, Object> header(TopologyRepository.SnapshotHeader h) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshot_id", h.snapshotId());
        body.put("built_at", h.builtAt().toString());
        body.put("inventory_fingerprint", h.fingerprints().inventory());
        body.put("override_fingerprint", h.fingerprints().overrides());
        body.put("counts", counts(h.counts()));
        body.put("conflicts_stored", h.conflictsStored());
        body.put("build_millis", h.buildMillis());
        return body;
    }

    public static Map<String, Object> overrides(List<TopologyService.OverrideView> views, boolean masked) {
        List<Object> items = new ArrayList<>();
        for (TopologyService.OverrideView view : views) {
            items.add(override(view, masked));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("overrides", items);
        return body;
    }

    public static Map<String, Object> override(TopologyService.OverrideView view, boolean masked) {
        TopologyLinkOverride o = view.override();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("override_id", o.overrideId());
        body.put("override_action", o.action());
        body.put("link_kind", o.linkKind());
        body.put("a", endpoint(o.aDeviceId(), o.aContext(), o.aInterfaceName()));
        body.put("b", endpoint(o.bDeviceId(), o.bContext(), o.bInterfaceName()));
        body.put("assumed_subnet", o.assumedSubnet().orElse(null));
        body.put("reason_code", o.reasonCode());
        if (!masked) {
            // D-8: the note and the author fingerprint are never part of the aiview body.
            body.put("override_note", o.note().orElse(null));
            body.put("created_by", o.createdBy());
        }
        body.put("created_at", o.createdAt().toString());
        body.put("revoked_at", o.revokedAt().map(Object::toString).orElse(null));
        body.put("orphaned", o.orphaned());
        body.put("override_status", view.status());
        body.put("no_match_reason", view.reason().orElse(null));
        return body;
    }

    private static Map<String, Object> endpoint(Optional<String> deviceId, String context, String interfaceName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_id", deviceId.orElse(null));
        body.put("context", context);
        body.put("interface_name", interfaceName);
        return body;
    }

    private static Map<String, Object> counts(TopologyGraph.Counts c) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("devices", c.devices());
        body.put("devices_without_inventory", c.devicesWithoutInventory());
        body.put("nodes", c.nodes());
        body.put("cluster_units", c.clusterUnits());
        body.put("clouds", c.clouds());
        body.put("segments", c.segments());
        body.put("attachments", c.attachments());
        body.put("conflicts", c.conflicts());
        body.put("overrides_applied", c.overridesApplied());
        body.put("overrides_unmatched", c.overridesUnmatched());
        return body;
    }

    private static Map<String, Object> node(TopologyGraph.Node n) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("node_id", n.nodeId());
        body.put("node_kind", n.kind().name());
        body.put("vendor", n.vendor());
        body.put("cluster_member_ref", n.clusterMemberRef().orElse(null));
        body.put("context", n.context().isEmpty() ? null : n.context());
        body.put("routing_context", n.routingTable().orElse(null));
        body.put("active_member_device_id", n.activeMemberDeviceId().orElse(null));
        body.put("flags", n.flags().stream().map(Enum::name).sorted().toList());
        body.put("members", n.members().stream().map(m -> {
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("device_id", m.deviceId());
            member.put("hostname", m.hostname().orElse(null));
            member.put("ha_role", m.haRole().orElse(null));
            member.put("run_id", m.runId().orElse(null));
            member.put("collected_at", m.collectedAt().map(Object::toString).orElse(null));
            return member;
        }).toList());
        body.put("route_count", n.routes().size());
        return body;
    }

    private static Map<String, Object> segment(TopologyGraph.Segment s) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("segment_id", s.segmentId());
        body.put("segment_kind", s.kind().name());
        body.put("network", s.network().map(Ipv4Prefix::toString).orElse(null));
        body.put("override_id", s.overrideId().orElse(null));
        return body;
    }

    private static Map<String, Object> attachment(TopologyGraph.Attachment a) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attachment_id", a.attachmentId());
        body.put("node_id", a.nodeId());
        body.put("segment_id", a.segmentId());
        body.put("interface_name", a.interfaceName().orElse(null));
        body.put("address", a.address().orElse(null));
        body.put("address_role", a.addressRole().orElse(null));
        body.put("zone", a.zone().orElse(null));
        body.put("corroborated", a.corroborated());
        body.put("source", a.source().name());
        body.put("evidence_grade", a.evidenceGrade().orElse(null));
        return body;
    }

    private static Map<String, Object> conflict(TopologyGraph.Conflict c) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conflict_id", c.conflictId());
        body.put("conflict_kind", c.kind().name());
        body.put("resolution", c.resolution().name());
        body.put("device_id", c.deviceId().orElse(null));
        body.put("peer_device_id", c.peerDeviceId().orElse(null));
        body.put("context", c.context().orElse(null));
        body.put("network", c.network().map(Ipv4Prefix::toString).orElse(null));
        body.put("override_id", c.overrideId().orElse(null));
        body.put("node_ids", c.nodeIds());
        return body;
    }
}
