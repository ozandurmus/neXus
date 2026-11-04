package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;

/**
 * Loads the synthetic golden estates under {@code src/test/resources/pathanalysis/topology/} and renders a graph as
 * stable text keyed by fixture labels (hostname, context, table, network), never by the salted ids, so a golden file
 * reads as the topology it asserts.
 */
final class TopologyFixtures {

    static final byte[] SALT = "synthetic-topology-golden-salt-0001".getBytes(StandardCharsets.UTF_8);
    static final Instant BUILT_AT = Instant.parse("2026-11-04T07:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private TopologyFixtures() {
    }

    static JsonNode resource(String name) {
        try (InputStream in = TopologyFixtures.class.getResourceAsStream("/pathanalysis/topology/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String text(String name) {
        try (InputStream in = TopologyFixtures.class.getResourceAsStream("/pathanalysis/topology/" + name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<TopologyInput.DeviceFacts> devices(JsonNode estate) {
        List<TopologyInput.DeviceFacts> out = new ArrayList<>();
        for (JsonNode d : estate.get("devices")) {
            out.add(new TopologyInput.DeviceFacts(d.get("device_id").asText(), d.get("vendor").asText(),
                    opt(d, "hostname"), opt(d, "cluster_member_ref"),
                    d.has("run") ? Optional.of(run(d.get("device_id").asText(), d.get("run"))) : Optional.empty()));
        }
        return out;
    }

    static List<TopologyLinkOverride> overrides(JsonNode file) {
        List<TopologyLinkOverride> out = new ArrayList<>();
        for (JsonNode o : file.get("overrides")) {
            out.add(new TopologyLinkOverride(o.get("override_id").asText(), o.get("action").asText(),
                    o.get("link_kind").asText(), opt(o.get("a"), 0), o.get("a").get(1).asText(), o.get("a").get(2).asText(),
                    opt(o.get("b"), 0), o.get("b").get(1).asText(), o.get("b").get(2).asText(), opt(o, "assumed_subnet"),
                    o.get("reason_code").asText(), opt(o, "note"), "synthetic-actor", BUILT_AT.minusSeconds(86_400),
                    opt(o, "revoked_at").map(Instant::parse), opt(o, "revoked_at").map(x -> "synthetic-actor"),
                    opt(o, "orphaned_at").map(Instant::parse)));
        }
        return out;
    }

    static TopologyInput input(List<TopologyInput.DeviceFacts> devices, List<TopologyLinkOverride> overrides) {
        return new TopologyInput("tps-" + UUID.nameUUIDFromBytes("golden".getBytes(StandardCharsets.UTF_8)), BUILT_AT,
                devices, overrides, SALT);
    }

    private static InventoryRun run(String deviceId, JsonNode r) {
        List<InventoryContext> contexts = new ArrayList<>();
        int seq = 0;
        for (JsonNode c : r.get("contexts")) {
            List<InventoryInterface> interfaces = new ArrayList<>();
            for (JsonNode i : c.get("interfaces")) {
                List<InventoryAddress> addresses = new ArrayList<>();
                for (JsonNode a : i.get("addresses")) {
                    addresses.add(new InventoryAddress("addr-" + deviceId + "-" + (seq++), a.get("address").asText(),
                            InventoryAddress.FAMILY_IPV4, a.get("role").asText()));
                }
                interfaces.add(new InventoryInterface("if-" + deviceId + "-" + (seq++), i.get("name").asText(),
                        Optional.empty(), InventoryInterface.KIND_PHYSICAL, InventoryInterface.STATE_UP, addresses,
                        Optional.empty(), Optional.empty(), opt(i, "zone"), opt(i, "routing_context"),
                        Optional.of(opt(i, "grade").orElse(InventoryInterface.GRADE_RUNTIME))));
            }
            List<InventoryRoute> routes = new ArrayList<>();
            for (JsonNode rt : c.get("routes")) {
                routes.add(new InventoryRoute("route-" + deviceId + "-" + (seq++), rt.get("destination").asText(),
                        opt(rt, "next_hop"), opt(rt, "interface"), rt.get("protocol").asText(), opt(rt, "table"),
                        rt.has("active") ? Optional.of(rt.get("active").asBoolean()) : Optional.empty(), Optional.empty(),
                        Optional.empty(), opt(rt, "kind")));
            }
            contexts.add(new InventoryContext(c.get("context").asText(), interfaces, routes));
        }
        List<InventoryHaFact> ha = new ArrayList<>();
        if (r.has("ha")) {
            for (JsonNode h : r.get("ha")) {
                ha.add(new InventoryHaFact("ha-" + deviceId + "-" + (seq++), h.get("context").asText(),
                        h.get("role").asText(), Optional.empty(), InventoryHaFact.SOURCE_CP_CPHAPROB_STAT));
            }
        }
        return new InventoryRun(r.get("run_id").asText(), deviceId, "job-" + deviceId,
                Instant.parse(r.get("collected_at").asText()), contexts.size(), contexts, ha);
    }

    private static Optional<String> opt(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? Optional.of(node.get(field).asText()) : Optional.empty();
    }

    private static Optional<String> opt(JsonNode array, int index) {
        return array.get(index).isNull() ? Optional.empty() : Optional.of(array.get(index).asText());
    }

    // ---- golden rendering ----------------------------------------------------------------------------------------

    static String render(TopologyGraph g) {
        Map<String, String> nodeLabel = new LinkedHashMap<>();
        Map<String, String> segmentLabel = new LinkedHashMap<>();
        for (TopologyGraph.Segment s : g.segments()) {
            segmentLabel.put(s.segmentId(), s.network().map(Ipv4Prefix::toString).orElse("-") + " " + s.kind());
        }
        Map<String, String> hostnameOf = new LinkedHashMap<>();
        for (TopologyGraph.Node n : g.nodes()) {
            n.members().forEach(m -> hostnameOf.put(m.deviceId(), m.hostname().orElse(m.deviceId())));
            if (n.kind() == TopologyGraph.NodeKind.CLOUD) {
                continue;
            }
            nodeLabel.put(n.nodeId(), n.members().stream().map(m -> m.hostname().orElse(m.deviceId()))
                    .collect(Collectors.joining("+")) + "/" + n.context() + "/" + n.routingTable().orElse("?"));
        }
        for (TopologyGraph.Node n : g.nodes()) {
            if (n.kind() == TopologyGraph.NodeKind.CLOUD) {
                String seg = g.attachmentsOf(n.nodeId()).stream().map(a -> segmentLabel.get(a.segmentId())).findFirst()
                        .orElse("?");
                nodeLabel.put(n.nodeId(), "CLOUD@" + seg);
            }
        }
        List<String> lines = new ArrayList<>();
        var c = g.counts();
        lines.add("COUNTS devices=" + c.devices() + " without_inventory=" + c.devicesWithoutInventory() + " nodes="
                + c.nodes() + " cluster_units=" + c.clusterUnits() + " clouds=" + c.clouds() + " segments="
                + c.segments() + " attachments=" + c.attachments() + " conflicts=" + c.conflicts() + " overrides_applied="
                + c.overridesApplied() + " overrides_unmatched=" + c.overridesUnmatched());
        List<String> block = new ArrayList<>();
        for (TopologyGraph.Node n : g.nodes()) {
            block.add("NODE " + nodeLabel.get(n.nodeId()) + " kind=" + n.kind() + " vendor=" + n.vendor()
                    + " active=" + n.activeMemberDeviceId().map(hostnameOf::get).orElse("-")
                    + " roles=" + n.members().stream().map(m -> m.haRole().orElse("?")).toList()
                    + " flags=" + new TreeSet<>(n.flags().stream().map(Enum::name).toList())
                    + " routes=" + n.routes().size());
        }
        block.sort(null);
        lines.addAll(block);
        block = new ArrayList<>();
        for (TopologyGraph.Segment s : g.segments()) {
            List<String> on = g.attachmentsOn(s.segmentId()).stream()
                    .map(a -> nodeLabel.get(a.nodeId()) + a.interfaceName().map(i -> ":" + i).orElse("")
                            + a.address().map(x -> "@" + x).orElse("") + (a.corroborated() ? "" : "(uncorroborated)")
                            + (a.source() == TopologyGraph.Attachment.Source.OVERRIDE ? "(override)" : ""))
                    .sorted().toList();
            block.add("SEGMENT " + segmentLabel.get(s.segmentId()) + s.overrideId().map(o -> " override=" + o).orElse("")
                    + " " + on);
        }
        block.sort(null);
        lines.addAll(block);
        block = new ArrayList<>();
        for (List<String> edge : g.directEdges()) {
            block.add("EDGE " + edge.stream().map(nodeLabel::get).sorted().toList());
        }
        block.sort(null);
        lines.addAll(block);
        block = new ArrayList<>();
        for (TopologyGraph.NextHopEdge e : g.nextHops()) {
            block.add("NEXT_HOP " + nodeLabel.get(e.fromNodeId()) + " -> " + nodeLabel.get(e.toNodeId()) + " via "
                    + segmentLabel.get(e.segmentId()) + " routes=" + e.routeCount());
        }
        block.sort(null);
        lines.addAll(block);
        block = new ArrayList<>();
        for (TopologyGraph.Conflict k : g.conflicts()) {
            block.add("CONFLICT " + k.kind() + " " + k.resolution() + " device=" + k.deviceId().map(hostnameOf::get).orElse("-")
                    + " peer=" + k.peerDeviceId().map(hostnameOf::get).orElse("-") + " context=" + k.context().orElse("-")
                    + " network=" + k.network().map(Ipv4Prefix::toString).orElse("-")
                    + " override=" + k.overrideId().orElse("-")
                    + " nodes=" + k.nodeIds().stream().map(nodeLabel::get).sorted().toList());
        }
        block.sort(null);
        lines.addAll(block);
        g.overrides().stream().sorted(Comparator.comparing(TopologyGraph.OverrideResult::overrideId))
                .forEach(o -> lines.add("OVERRIDE " + o.overrideId() + " " + o.status()
                        + o.reason().map(r -> " " + r).orElse("")));
        return String.join("\n", lines) + "\n";
    }
}
