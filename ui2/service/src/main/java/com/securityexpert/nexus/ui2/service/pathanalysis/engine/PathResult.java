package com.securityexpert.nexus.ui2.service.pathanalysis.engine;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.securityexpert.nexus.ui2.service.policy.HopPolicyEvaluator.Tuple;

/**
 * The answer to one {@link PathQuery} (F§4.7 output contract plus the A§4 {@code executive_summary} block). Never
 * persisted (decision D-2). Units are named by their opaque topology node id only; addresses, interface names, zones and
 * rule maps are the stored (unmasked) values -- projecting them for a viewer (aiview masking) is the API stage's job.
 * Lists are in a deterministic order: branches in exploration order (routes sorted by next hop, interface, route id),
 * hops by index.
 *
 * @param verdict     {@code ALLOWED | DENIED | PARTIAL | UNKNOWN | ALLOWED_TO_EDGE}
 * @param plain       {@code ALLOWED} with no caveat; a caveated allow is never plain
 * @param blockingHop the first DENIED hop of the first blocked branch ("who blocks this")
 */
public record PathResult(PathQuery query, String topologySnapshotId, Instant computedAt, String verdict, String reason,
        boolean plain, List<String> caveats, List<String> warnings, Ingress ingress, List<Branch> branches,
        boolean ecmpTruncated, boolean asymmetric, Optional<HopRef> blockingHop, ExecutiveSummary executiveSummary,
        String limitations) {

    public static final String LIMITATIONS = "Management intent only (stored inventory and policy), not observed traffic. "
            + "IPv4 only; source port not modelled; Check Point implied rules and anti-spoofing not evaluated; "
            + "return traffic is traced by routing only.";

    public PathResult {
        caveats = List.copyOf(caveats);
        warnings = List.copyOf(warnings);
        branches = List.copyOf(branches);
    }

    /** How the first hop was found: {@code GIVEN} (hint) or {@code INFERRED}; {@code status} RESOLVED or a reason. */
    public record Ingress(String mode, String status, List<IngressCandidate> candidates) {
        public Ingress {
            candidates = List.copyOf(candidates);
        }
    }

    public record IngressCandidate(String nodeId, Optional<String> attachmentId, Optional<String> segmentId) {
    }

    public record HopRef(String branchId, int hopIndex) {
    }

    /**
     * One forward alternative (an ingress candidate or an ECMP choice). {@code termination} is DELIVERED,
     * EXITS_KNOWN_TOPOLOGY, BLOCKED or an UNKNOWN cause (NO_ROUTE, ROUTING_LOOP, HOP_LIMIT, ...).
     */
    public record Branch(String branchId, String verdict, String reason, String termination, List<Hop> hops,
            ReturnPath returnPath, List<String> caveats, List<String> warnings, Optional<Integer> blockingHopIndex) {
        public Branch {
            hops = List.copyOf(hops);
            caveats = List.copyOf(caveats);
            warnings = List.copyOf(warnings);
        }
    }

    /** Where a hop enters or leaves its unit. Every field may be empty (UNKNOWN). */
    public record Endpoint(Optional<String> attachmentId, Optional<String> segmentId, Optional<String> interfaceName,
            Optional<String> zone) {
        public static final Endpoint NONE = new Endpoint(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** The {@code device_route} row a hop forwarded on (a reference; routes are never copied). */
    public record RouteUsed(String routeId, String destination, Optional<String> nextHop, Optional<String> interfaceName,
            String protocol, Optional<String> nextHopKind, boolean ecmpBranch) {
    }

    /** The A2 NAT outcome of a hop: status TRANSLATED / NO_TRANSLATION / UNKNOWN, the deciding rule(s). */
    public record NatApplied(String status, String reason, boolean applied, List<Map<String, Object>> rules,
            List<Map<String, Object>> undecidableRules) {
        public NatApplied {
            rules = List.copyOf(rules);
            undecidableRules = List.copyOf(undecidableRules);
        }
    }

    /** One A2 access evaluation, the PolicyLookup body pieces the hop view shows. */
    public record Security(String verdict, String reason, Optional<Map<String, Object>> decidingRule,
            List<Map<String, Object>> undecidableRules) {
        public Security {
            undecidableRules = List.copyOf(undecidableRules);
        }
    }

    /**
     * One unit on a branch. {@code ownVerdict} is the hop's own evaluation (ALLOWED / DENIED / UNKNOWN);
     * {@code verdict} is its place in the lattice: {@code CONDITIONAL} when an earlier hop is UNKNOWN and the own
     * evaluation is decided. {@code postNatSecurity} is present only for a Check Point hop with destination NAT.
     * When {@code policyStale}, the security fields are the last-known evaluation shown as context only.
     */
    public record Hop(int index, String nodeId, String nodeKind, String vendor, String context,
            Optional<String> routingTable, Optional<String> deviceId, Endpoint ingress, Endpoint egress, Tuple packetIn,
            Tuple packetOut, Optional<RouteUsed> route, Optional<NatApplied> nat, Optional<Security> security,
            Optional<Security> postNatSecurity, boolean policyStale, Optional<Instant> policyCollectedAt,
            String ownVerdict, String verdict, String reason, List<String> reasons, List<String> caveats,
            List<String> notes, Set<String> flags) {
        public Hop {
            reasons = List.copyOf(reasons);
            caveats = List.copyOf(caveats);
            notes = List.copyOf(notes);
            flags = Set.copyOf(flags);
        }
    }

    /**
     * The routing-only return walk of one forward branch (§5, F§4.5). Status SYMMETRIC, ASYMMETRIC (the return
     * crosses a stateful unit the forward path did not, or skips one it did), INCOMPLETE (the walk ended without
     * proving either) or NOT_COMPUTED.
     */
    public record ReturnPath(String status, String reason, List<ReturnBranch> branches) {
        public ReturnPath {
            branches = List.copyOf(branches);
        }

        public static ReturnPath notComputed(String reason) {
            return new ReturnPath(PathReasons.NOT_COMPUTED, reason, List.of());
        }
    }

    public record ReturnBranch(String termination, String status, List<ReturnHop> hops) {
        public ReturnBranch {
            hops = List.copyOf(hops);
        }
    }

    /**
     * {@code source}/{@code destination} are the reverse packet after the inverse of the forward NAT at this unit
     * ({@code reverseTranslated}); the destination port of return traffic is the unmodelled source port.
     */
    public record ReturnHop(int index, String nodeId, String source, String destination, boolean reverseTranslated,
            Optional<RouteUsed> route, Endpoint egress, List<String> reasons) {
        public ReturnHop {
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * A§4 summary. The headline is template text over the verdict, hop index, reason code and rule number only -- never a
     * name or an address -- so it needs no masking.
     *
     * @param freshnessStatus {@code CURRENT}, or {@code STALE} when any hop's policy is stale or out of sync
     */
    public record ExecutiveSummary(String headline, List<HopRef> blockingHops, List<HopRef> unknownHops,
            boolean asymmetryDetected, int ecmpBranchCount, boolean ecmpTruncated, String freshnessStatus) {
        public ExecutiveSummary {
            blockingHops = List.copyOf(blockingHops);
            unknownHops = List.copyOf(unknownHops);
        }
    }
}
