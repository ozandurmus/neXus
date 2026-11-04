package com.securityexpert.nexus.ui2.service.pathanalysis.engine;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.securityexpert.nexus.ui2.service.pathanalysis.topology.Ipv4Prefix;

/**
 * One path question (PATH_ANALYSIS_DESIGN.md §5, FROZEN; F§4.1 adopted with the §5 restrictions): an IPv4 source and
 * destination host, TCP or UDP, a destination port and an optional ingress hint. IPv4 only; the source port is not
 * modelled. A malformed query is refused here ({@link IllegalArgumentException}, the API layer's 400); malformed
 * topology or policy data never throws inside the engine -- it yields {@code UNKNOWN} with a reason.
 *
 * @param ingress  where the traffic enters the known topology; empty = inferred (labelled {@code INFERRED})
 * @param edgeMode decision D-4: answer "up to the edge" ({@code ALLOWED_TO_EDGE}) for paths that leave known topology
 */
public record PathQuery(String source, String destination, String protocol, int destinationPort,
        Optional<IngressHint> ingress, boolean edgeMode, Limits limits) {

    public static final Set<String> PROTOCOLS = Set.of("tcp", "udp");

    /** Start at this unit (opaque topology node id), optionally on this interface of it. */
    public record IngressHint(String nodeId, Optional<String> interfaceName) {
        public IngressHint {
            Objects.requireNonNull(nodeId, "nodeId");
            interfaceName = interfaceName == null ? Optional.empty() : interfaceName.filter(s -> !s.isBlank());
        }
    }

    /**
     * Bounds (§5): ECMP up to 8 branches per hop and 16 in total; hop limit 32 (F§4.1); a work budget counting unit
     * visits, forward and return. A caller may lower a bound (tests, a cheaper preview), never raise it above the default.
     */
    public record Limits(int maxHops, int ecmpPerHop, int maxBranches, int workBudget) {
        public static final Limits DEFAULT = new Limits(32, 8, 16, 4_000);

        public Limits {
            if (maxHops < 1 || maxHops > 32 || ecmpPerHop < 1 || ecmpPerHop > 8 || maxBranches < 1 || maxBranches > 16
                    || workBudget < 1 || workBudget > 4_000) {
                throw new IllegalArgumentException("Path limits out of range");
            }
        }
    }

    public PathQuery {
        requireHost(source);
        requireHost(destination);
        if (protocol == null || !PROTOCOLS.contains(protocol)) {
            throw new IllegalArgumentException("Path protocol must be tcp or udp");
        }
        if (destinationPort < 1 || destinationPort > 65535) {
            throw new IllegalArgumentException("Path destination port out of range");
        }
        ingress = ingress == null ? Optional.empty() : ingress;
        limits = limits == null ? Limits.DEFAULT : limits;
    }

    public static PathQuery of(String source, String destination, String protocol, int destinationPort) {
        return new PathQuery(source, destination, protocol, destinationPort, Optional.empty(), false, Limits.DEFAULT);
    }

    public PathQuery withIngress(String nodeId, String interfaceName) {
        return new PathQuery(source, destination, protocol, destinationPort,
                Optional.of(new IngressHint(nodeId, Optional.ofNullable(interfaceName))), edgeMode, limits);
    }

    public PathQuery withEdgeMode(boolean edge) {
        return new PathQuery(source, destination, protocol, destinationPort, ingress, edge, limits);
    }

    public PathQuery withLimits(Limits newLimits) {
        return new PathQuery(source, destination, protocol, destinationPort, ingress, edgeMode, newLimits);
    }

    /** A dotted-quad host without a prefix length, in its canonical text (no leading zeros). */
    private static void requireHost(String value) {
        var host = Ipv4Prefix.parseHost(value);
        if (host.isEmpty() || host.get().length().isPresent() || !host.get().hostText().equals(value)) {
            throw new IllegalArgumentException("Path address must be an IPv4 host");
        }
    }
}
