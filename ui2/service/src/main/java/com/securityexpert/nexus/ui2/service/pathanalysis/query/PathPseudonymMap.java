package com.securityexpert.nexus.ui2.service.pathanalysis.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * <p>Resolving a masked host runs in two steps; the first that yields a raw host decides.</p>
 * <ol>
 *   <li>What the topology response showed: the host part the advice renders for each attachment address (computed
 *   purely from {@code "h/len"}, or the bare-host form for an address written without a length, such as a cluster
 *   VIP). A value aiview took from {@code GET /api/v2/topology} resolves to the host it was rendered from. The same
 *   raw address on several attachments (a VIP on both members, one range in two VRFs) is one raw value; which unit or
 *   VRF is meant is the engine's call. Two different raw hosts shown the same way are {@code AMBIGUOUS_PSEUDONYM}, and
 *   so is a shown host together with any other raw host the masker renders bare as the same value (security review
 *   2026-11-05 M-4): aiview may have seen that one bare elsewhere.</li>
 *   <li>Otherwise any form a response can render (below), for values aiview saw elsewhere or a host offset it picked
 *   inside a masked network it was shown.</li>
 * </ol>
 * <p>Why the order (2026-11-04 17:30 live symptom, 11 of 20 queries refused as ambiguous): the masker keys a pseudonym
 * network on {@code len - 4} hashed bits, so a short summary the estate routes ({@code /8}, {@code /10}, {@code /12},
 * {@code /16}) has only 16 to about 3,800 masked networks. Under the any-prefix rule a host aiview saw on a {@code /28}
 * was also "consistent" with a different raw host under such a summary, although no response renders that host with
 * that length. {@code PathPseudonymOverlapTest} reproduces it.</p>
 *
 * <p>The any-prefix step: every network whose pseudonym contains the value gives one raw candidate (the masker
 * preserves the host offset inside its subnet); subnets registered with the masker are candidates too. A candidate is
 * accepted when the submitted value is one of the forms the response masking can render for that raw host:</p>
 * <ul>
 *   <li>the host part of {@code h/len} for a real prefix containing it -- a snapshot network or a registered subnet --
 *   which is how the advice renders an interface address written with its length ({@code "address": "h/25"} becomes
 *   {@code "m/25"}); for a value with a length the masker's answer is a pure function of the text, so this form is
 *   computed exactly and does not depend on what other responses registered since; or</li>
 *   <li>{@link Masking#peek} -- what the masker hands out for the bare host now (a pseudonym already handed out, else the
 *   longest registered prefix), computed without caching.</li>
 * </ul>
 * <p>Accepting only the bare-host form (the original rule) refused most hosts of larger segments: the bare form follows
 * the longest registered prefix (a {@code /32} local route or a {@code /24} inside a {@code /23} registered by another
 * response) or a pseudonym cached before the segment's prefix was known, while the topology response shows the address
 * under its own interface prefix.</p>
 *
 * <p>One accepted raw value inside the snapshot resolves; more than one is {@code AMBIGUOUS_PSEUDONYM}; none, or one
 * outside the snapshot's networks, is {@code UNKNOWN_PSEUDONYM}; a value outside the masked space ({@code 240.0.0.0/4})
 * is a raw value and {@code RAW_VALUE_REFUSED}. A masked ingress interface name resolves against the hinted unit's
 * interfaces by the label already handed out for each, with the same three outcomes.</p>
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

        /**
         * What the response masking renders for the literal text {@code a.b.c.d/len} (host bits kept), computed purely.
         * For a value carrying its length the masker's answer depends on the text alone, never on registered state.
         */
        String maskPrefixed(String hostWithLength);

        /** The subnets registered with the masker so far (a copy). */
        List<String> registeredSubnets();

        /**
         * The raw values the masker has already handed out as exactly {@code pseudonym} (a reverse read of what it
         * cached; changes nothing). A bare host here is one some masked response rendered bare.
         */
        List<String> handedOutAs(String pseudonym);

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
    /** The snapshot's own prefixes: a host under one of them can be rendered as {@code h/len} by a response. */
    private final Set<Ipv4Prefix> ownNetworks;
    /** length -> masked network -> raw networks of that length whose pseudonym it is. */
    private final Map<Integer, Map<Long, Set<Long>>> byMaskedNetwork = new HashMap<>();
    private final Map<String, Optional<Ipv4Prefix>> registeredMemo = new ConcurrentHashMap<>();
    /**
     * Masked host -> raw hosts, for every attachment address written {@code h/len}: the host part the topology
     * response shows for it. With an explicit length the masker's answer is a pure function of the text, so this is
     * computed once per snapshot and cannot drift.
     */
    private final Map<Long, Set<Long>> shownPrefixed = new HashMap<>();
    /** Attachment addresses written without a length (a VIP as the cluster reports it): shown as the bare-host form. */
    private final Set<Long> shownBare = new TreeSet<>();
    /** The snapshot's route next hops: a route table response renders each bare (competitors of a shown value, M-4). */
    private final Set<Long> nextHops = new TreeSet<>();
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
                if (nextHops.size() < MAX_NETWORKS) {
                    r.nextHop().flatMap(Ipv4Prefix::parseHost).filter(h -> h.length().isEmpty())
                            .filter(h -> !maskedSpace(h.host())).ifPresent(h -> nextHops.add(h.host()));
                }
            }
        }
        own.removeIf(p -> p.length() < 4);
        for (TopologyGraph.Attachment a : graph.attachments()) {
            if (a.address().isEmpty()) {
                continue;
            }
            String address = a.address().get().strip();
            Optional<Ipv4Prefix.HostAddress> host = Ipv4Prefix.parseHost(address);
            if (host.isEmpty()) {
                continue;
            }
            if (host.get().length().isEmpty()) {
                shownBare.add(host.get().host());
                continue;
            }
            int length = host.get().length().get();
            Optional<Ipv4Prefix.HostAddress> shown = Optional.ofNullable(masking.maskPrefixed(address))
                    .flatMap(Ipv4Prefix::parseHost).filter(h -> h.length().filter(len -> len == length).isPresent())
                    .filter(h -> maskedSpace(h.host()));
            shown.ifPresent(h -> shownPrefixed.computeIfAbsent(h.host(), k -> new TreeSet<>()).add(host.get().host()));
        }
        this.snapshotNetworks = List.copyOf(own);
        this.ownNetworks = Set.copyOf(own);
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
        // First what the topology response showed for an attachment; other prefixes only when that yields nothing.
        Set<Long> shown = new TreeSet<>(shownPrefixed.getOrDefault(value, Set.of()));
        for (long bare : shownBare) {
            if (submitted.equals(masking.peek(text(bare)))) {
                shown.add(bare);
            }
        }
        List<Long> accepted;
        if (shown.isEmpty()) {
            accepted = acceptedUnderAnyPrefix(value, submitted);
        } else {
            // A shown hit still competes with every other raw host aiview can hold bare as the same value (security
            // review 2026-11-05 M-4): two raw values are AMBIGUOUS_PSEUDONYM across both steps (D-3).
            Set<Long> union = new TreeSet<>(shown);
            union.addAll(seenBareAs(submitted));
            accepted = List.copyOf(union);
        }
        if (accepted.size() > 1) {
            return new Refused(AMBIGUOUS_PSEUDONYM);
        }
        if (accepted.isEmpty() || snapshotNetworks.stream().noneMatch(p -> p.contains(accepted.get(0)))) {
            return new Refused(UNKNOWN_PSEUDONYM);
        }
        return new Resolved(text(accepted.get(0)));
    }

    /** Test access: the raw hosts the any-prefix rule accepts for a masked host (package-private, never logged). */
    List<Long> acceptedUnderAnyPrefix(String submitted) {
        return acceptedUnderAnyPrefix(Ipv4Prefix.parseHost(submitted).orElseThrow().host(), submitted);
    }

    private List<Long> acceptedUnderAnyPrefix(long value, String submitted) {
        // raw host -> lengths of the real prefixes (snapshot networks, registered subnets) it was derived under; a
        // candidate derived only from a fallback /24 has none and can match only the bare-host form.
        Map<Long, Set<Integer>> candidates = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<Long, Set<Long>>> byLength : byMaskedNetwork.entrySet()) {
            int length = byLength.getKey();
            long mask = mask(length);
            Set<Long> raws = byLength.getValue().get(value & mask);
            if (raws != null) {
                for (long rawNetwork : raws) {
                    Set<Integer> lengths = candidates.computeIfAbsent(rawNetwork | (value & ~mask & 0xFFFF_FFFFL),
                            raw -> new TreeSet<>());
                    if (ownNetworks.contains(new Ipv4Prefix(rawNetwork, length))) {
                        lengths.add(length);
                    }
                }
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
                candidates.computeIfAbsent(raw.get().network() | (value & ~mask & 0xFFFF_FFFFL), r -> new TreeSet<>())
                        .add(raw.get().length());
            }
        }
        List<Long> accepted = new ArrayList<>();
        for (Map.Entry<Long, Set<Integer>> candidate : candidates.entrySet()) {
            if (rendersAs(candidate.getKey(), candidate.getValue(), submitted)) {
                accepted.add(candidate.getKey());
            }
        }
        return accepted;
    }

    /**
     * The raw hosts aiview can hold bare as {@code submitted}: every bare value the masker actually handed out as it (a
     * next hop, a policy object, an IP search, an address in a reason text -- whatever a masked response rendered), and
     * every route next hop of this snapshot whose bare form is it now (a route table aiview may read next).
     *
     * <p>Deliberately not "every any-prefix candidate whose bare form matches": under a registered summary
     * ({@code /8} .. {@code /16}) the bare form of a host no response ever rendered follows the summary's few masked
     * networks, and counting those hypothetical hosts brings back the 2026-11-04 false refusals
     * ({@code PathPseudonymOverlapTest}). A host aiview never saw cannot be the one it meant.</p>
     */
    private Set<Long> seenBareAs(String submitted) {
        Set<Long> out = new TreeSet<>();
        for (String raw : masking.handedOutAs(submitted)) {
            Ipv4Prefix.parseHost(raw).filter(h -> h.length().isEmpty()).ifPresent(h -> out.add(h.host()));
        }
        for (long hop : nextHops) {
            if (submitted.equals(masking.peek(text(hop)))) {
                out.add(hop);
            }
        }
        return out;
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

    /** True when the response masking can render raw host {@code raw} as {@code submitted} (see the class comment). */
    private boolean rendersAs(long raw, Set<Integer> prefixLengths, String submitted) {
        String host = text(raw);
        for (int length : prefixLengths) {
            String masked = masking.maskPrefixed(host + "/" + length);
            if (masked != null && masked.equals(submitted + "/" + length)) {
                return true;
            }
        }
        return submitted.equals(masking.peek(host));
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
            public String maskPrefixed(String hostWithLength) {
                return ips.maskNetworkWithoutSideEffects(hostWithLength);
            }

            @Override
            public List<String> registeredSubnets() {
                return ips.registeredSubnets();
            }

            @Override
            public List<String> handedOutAs(String pseudonym) {
                return ips.handedOutAs(pseudonym);
            }

            @Override
            public Optional<String> peekInterface(String rawInterfaceName) {
                return names.peekPolicyName(INTERFACE_NAMESPACE, rawInterfaceName);
            }
        };
    }
}
