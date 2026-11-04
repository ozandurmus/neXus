package com.securityexpert.nexus.ui2.service.pathanalysis.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.Ipv4Prefix;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;

/**
 * The aiview reverse map of one topology snapshot (PO record 2026-11-03 D-3, PATH_ANALYSIS_DESIGN.md §2 G9 and §6).
 * Built in memory from the snapshot's own prefixes (segment networks, attachment prefixes, route destinations and the
 * {@code /24} networks the masker falls back to for an unregistered host), each masked with the masker's side-effect-free
 * network function. Never persisted, never logged: {@link #toString()} carries counts only.
 *
 * <p>Resolving a masked host: every network whose pseudonym contains the value gives one raw candidate (the masker
 * preserves the host offset inside its subnet); subnets registered with the masker are candidates too. A candidate is
 * accepted only when {@link Masking#peek} -- what the masker hands out for it now, computed without caching -- equals the
 * submitted value. One accepted value inside the snapshot resolves; more than one is {@code AMBIGUOUS_PSEUDONYM}; none,
 * or one outside the snapshot's networks, is {@code UNKNOWN_PSEUDONYM}; a value outside the masked space
 * ({@code 240.0.0.0/4}) is a raw value and {@code RAW_VALUE_REFUSED}. A masked ingress interface name resolves against
 * the hinted unit's interfaces by the label already handed out for each, with the same three outcomes.</p>
 *
 * <p>Resolution has no side effect (D-3 condition 4): no masker cache entry, no subnet registration, no pseudonym
 * registry claim. The only state written is this map's own memo of registered-subnet pseudonyms.</p>
 */
public final class PathPseudonymMap {

    public static final String RAW_VALUE_REFUSED = "RAW_VALUE_REFUSED";
    public static final String UNKNOWN_PSEUDONYM = "UNKNOWN_PSEUDONYM";
    public static final String AMBIGUOUS_PSEUDONYM = "AMBIGUOUS_PSEUDONYM";

    /** Upper bound on the networks one snapshot contributes (the fallback /24s of a /16 are 256). */
    static final int MAX_NETWORKS = 200_000;
    /** Upper bound on registered subnets considered (and memoised) per snapshot. */
    static final int MAX_REGISTERED = 100_000;
    private static final String INTERFACE_NAMESPACE = "interface";

    /** The masker operations the map uses; production wraps the service maskers, tests may collide on purpose. */
    public interface Masking {
        /** What the masker answers for a host now, without caching or registering anything. */
        String peek(String host);

        /** The pseudonym of {@code a.b.c.d/len} (host bits clear), computed purely. */
        String maskNetwork(String cidr);

        /** The subnets registered with the masker so far (a copy). */
        List<String> registeredSubnets();

        /** The interface label already handed out for this raw name, or empty; never claims one. */
        Optional<String> peekInterface(String rawInterfaceName);
    }

    /** {@link Resolved} or {@link Refused}; a refused code is internal (audit), never shown to the caller. */
    public sealed interface Resolution permits Resolved, Refused {
    }

    public record Resolved(String raw) implements Resolution {
        @Override
        public String toString() {
            return "Resolved[withheld]";
        }
    }

    public record Refused(String code) implements Resolution {
    }

    private final TopologyGraph graph;
    private final Masking masking;
    private final List<Ipv4Prefix> snapshotNetworks;
    /** length -> masked network -> raw networks of that length whose pseudonym it is. */
    private final Map<Integer, Map<Long, Set<Long>>> byMaskedNetwork = new HashMap<>();
    private final Map<String, Optional<Ipv4Prefix>> registeredMemo = new ConcurrentHashMap<>();
    private final int networks;

    private PathPseudonymMap(TopologyGraph graph, Masking masking) {
        this.graph = graph;
        this.masking = masking;
        Set<Ipv4Prefix> own = new TreeSet<>();
        graph.segments().forEach(s -> s.network().ifPresent(own::add));
        for (TopologyGraph.Attachment a : graph.attachments()) {
            a.address().flatMap(Ipv4Prefix::parseHost).flatMap(Ipv4Prefix.HostAddress::prefix).ifPresent(own::add);
        }
        for (TopologyGraph.Node n : graph.nodes()) {
            for (InventoryRoute r : n.routes()) {
                Ipv4Prefix.parse(r.destination()).ifPresent(own::add);
            }
        }
        own.removeIf(p -> p.length() < 4);
        this.snapshotNetworks = List.copyOf(own);
        Set<Ipv4Prefix> all = new LinkedHashSet<>(own);
        for (Ipv4Prefix p : own) {
            if (all.size() >= MAX_NETWORKS) {
                break;
            }
            if (p.length() >= 24) {
                all.add(new Ipv4Prefix(p.network(), 24));
            } else if (p.length() >= 16) {
                for (long i = 0; i < (1L << (24 - p.length())) && all.size() < MAX_NETWORKS; i++) {
                    all.add(new Ipv4Prefix(p.network() + (i << 8), 24));
                }
            }
        }
        int count = 0;
        for (Ipv4Prefix p : all) {
            if (count >= MAX_NETWORKS) {
                break;
            }
            Optional<Ipv4Prefix> masked = maskedNetwork(p);
            if (masked.isPresent()) {
                byMaskedNetwork.computeIfAbsent(p.length(), len -> new HashMap<>())
                        .computeIfAbsent(masked.get().network(), net -> new LinkedHashSet<>()).add(p.network());
                count++;
            }
        }
        this.networks = count;
    }

    /** Builds the map of one snapshot; the caller keeps one per held snapshot (see {@link PathPseudonymResolver}). */
    public static PathPseudonymMap build(TopologyGraph graph, Masking masking) {
        return new PathPseudonymMap(Objects.requireNonNull(graph, "graph"), Objects.requireNonNull(masking, "masking"));
    }

    public String snapshotId() {
        return graph.snapshotId();
    }

    boolean isFor(TopologyGraph other) {
        return graph == other;
    }

    /** True for a value in the masker's synthetic space {@code 240.0.0.0/4}; anything else is a raw address. */
    static boolean maskedSpace(long host) {
        return (host >>> 28) == 0xF;
    }

    /** Resolves one masked IPv4 host (canonical dotted quad, no prefix length). */
    public Resolution resolveAddress(String submitted) {
        Optional<Ipv4Prefix.HostAddress> parsed = Ipv4Prefix.parseHost(submitted);
        if (parsed.isEmpty() || parsed.get().length().isPresent() || !maskedSpace(parsed.get().host())) {
            return new Refused(RAW_VALUE_REFUSED);
        }
        long value = parsed.get().host();
        Set<Long> candidates = new LinkedHashSet<>();
        for (Map.Entry<Integer, Map<Long, Set<Long>>> byLength : byMaskedNetwork.entrySet()) {
            long mask = mask(byLength.getKey());
            Set<Long> raws = byLength.getValue().get(value & mask);
            if (raws != null) {
                raws.forEach(raw -> candidates.add(raw | (value & ~mask & 0xFFFF_FFFFL)));
            }
        }
        List<String> registered = masking.registeredSubnets();
        for (int i = 0; i < registered.size() && i < MAX_REGISTERED; i++) {
            String text = registered.get(i);
            Optional<Ipv4Prefix> raw = Ipv4Prefix.parse(text);
            if (raw.isEmpty() || raw.get().length() < 4) {
                continue;
            }
            Optional<Ipv4Prefix> masked = registeredMemo.size() < MAX_REGISTERED
                    ? registeredMemo.computeIfAbsent(text, t -> maskedNetwork(raw.get()))
                    : maskedNetwork(raw.get());
            long mask = mask(raw.get().length());
            if (masked.isPresent() && masked.get().network() == (value & mask)) {
                candidates.add(raw.get().network() | (value & ~mask & 0xFFFF_FFFFL));
            }
        }
        List<Long> accepted = new ArrayList<>();
        for (long candidate : candidates) {
            if (submitted.equals(masking.peek(text(candidate)))) {
                accepted.add(candidate);
            }
        }
        if (accepted.size() > 1) {
            return new Refused(AMBIGUOUS_PSEUDONYM);
        }
        if (accepted.isEmpty() || snapshotNetworks.stream().noneMatch(p -> p.contains(accepted.get(0)))) {
            return new Refused(UNKNOWN_PSEUDONYM);
        }
        return new Resolved(text(accepted.get(0)));
    }

    /** Resolves a masked interface label among the interfaces of one unit of this snapshot. */
    public Resolution resolveInterface(String nodeId, String submitted) {
        if (submitted == null || submitted.isBlank()) {
            return new Refused(UNKNOWN_PSEUDONYM);
        }
        Set<String> names = new TreeSet<>();
        graph.attachmentsOf(nodeId).forEach(a -> a.interfaceName().ifPresent(names::add));
        List<String> matched = new ArrayList<>();
        boolean raw = false;
        for (String name : names) {
            if (masking.peekInterface(name).filter(submitted::equals).isPresent()) {
                matched.add(name);
            }
            raw |= name.equals(submitted);
        }
        if (matched.size() > 1) {
            return new Refused(AMBIGUOUS_PSEUDONYM);
        }
        if (matched.isEmpty()) {
            return new Refused(raw ? RAW_VALUE_REFUSED : UNKNOWN_PSEUDONYM);
        }
        return new Resolved(matched.get(0));
    }

    private Optional<Ipv4Prefix> maskedNetwork(Ipv4Prefix raw) {
        String masked = masking.maskNetwork(raw.toString());
        return Ipv4Prefix.parseHost(masked).filter(h -> h.length().filter(len -> len == raw.length()).isPresent())
                .filter(h -> maskedSpace(h.host())).map(h -> new Ipv4Prefix(h.host(), raw.length()));
    }

    static long mask(int length) {
        return length == 0 ? 0L : (0xFFFF_FFFFL << (32 - length)) & 0xFFFF_FFFFL;
    }

    static String text(long value) {
        return ((value >> 24) & 0xFF) + "." + ((value >> 16) & 0xFF) + "." + ((value >> 8) & 0xFF) + "." + (value & 0xFF);
    }

    /** Counts only: the map's contents must never reach a log. */
    @Override
    public String toString() {
        return "PathPseudonymMap[networks=" + networks + "]";
    }

    /** The production {@link Masking} over the service maskers. */
    public static Masking over(com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker ips,
            com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer names) {
        Objects.requireNonNull(ips, "ips");
        Objects.requireNonNull(names, "names");
        return new Masking() {
            @Override
            public String peek(String host) {
                return ips.peek(host);
            }

            @Override
            public String maskNetwork(String cidr) {
                return ips.maskNetworkWithoutSideEffects(cidr);
            }

            @Override
            public List<String> registeredSubnets() {
                return ips.registeredSubnets();
            }

            @Override
            public Optional<String> peekInterface(String rawInterfaceName) {
                return names.peekPolicyName(INTERFACE_NAMESPACE, rawInterfaceName);
            }
        };
    }
}
