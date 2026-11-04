package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.Attachment;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.NextHop;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.Node;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.NodeKind;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph.Segment;

/** Indexes built once per graph so a next-hop lookup costs one node's attachments, not the whole snapshot. */
final class NextHopResolver {

    private final Map<String, Segment> segmentById = new HashMap<>();
    private final Map<String, List<Attachment>> attachmentsByNode = new HashMap<>();
    /** segment id -> host address text -> node ids attached there at that address. */
    private final Map<String, Map<String, Set<String>>> holders = new HashMap<>();
    private final Map<String, String> cloudBySegment = new HashMap<>();

    NextHopResolver(List<Node> nodes, List<Segment> segments, List<Attachment> attachments) {
        segments.forEach(s -> segmentById.put(s.segmentId(), s));
        Set<String> clouds = new TreeSet<>();
        nodes.stream().filter(n -> n.kind() == NodeKind.CLOUD).forEach(n -> clouds.add(n.nodeId()));
        for (Attachment a : attachments) {
            attachmentsByNode.computeIfAbsent(a.nodeId(), k -> new ArrayList<>()).add(a);
            if (clouds.contains(a.nodeId())) {
                cloudBySegment.putIfAbsent(a.segmentId(), a.nodeId());
            }
            a.address().flatMap(Ipv4Prefix::parseHost).ifPresent(h -> holders
                    .computeIfAbsent(a.segmentId(), k -> new HashMap<>())
                    .computeIfAbsent(h.hostText(), k -> new TreeSet<>()).add(a.nodeId()));
        }
    }

    NextHop resolve(String nodeId, Optional<String> egressInterface, String nextHopAddress) {
        Optional<Ipv4Prefix.HostAddress> hop = Ipv4Prefix.parseHost(nextHopAddress)
                .filter(h -> h.length().isEmpty() || h.length().get() == 32);
        if (hop.isEmpty()) {
            return new NextHop.Unreachable();
        }
        long address = hop.get().host();
        Optional<Attachment> egress = attachmentsByNode.getOrDefault(nodeId, List.of()).stream()
                .filter(a -> segmentById.get(a.segmentId()).network().filter(p -> p.contains(address)).isPresent())
                .min(Comparator.comparing((Attachment a) -> egressInterface.isEmpty()
                                || !a.interfaceName().equals(egressInterface))
                        .thenComparing(a -> -segmentById.get(a.segmentId()).network().orElseThrow().length())
                        .thenComparing(Attachment::segmentId));
        if (egress.isEmpty()) {
            return new NextHop.Unreachable();
        }
        String segmentId = egress.get().segmentId();
        Set<String> held = new TreeSet<>(holders.getOrDefault(segmentId, Map.of())
                .getOrDefault(Ipv4Prefix.text(address), Set.of()));
        held.remove(nodeId);
        if (held.size() == 1) {
            return new NextHop.ToNode(held.iterator().next(), segmentId);
        }
        if (held.size() > 1) {
            return new NextHop.Ambiguous(segmentId, List.copyOf(held));
        }
        String cloud = cloudBySegment.get(segmentId);
        return cloud != null ? new NextHop.ToCloud(cloud, segmentId) : new NextHop.Unheld(segmentId);
    }
}
