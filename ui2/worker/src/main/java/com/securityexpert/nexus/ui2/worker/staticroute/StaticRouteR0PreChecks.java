package com.securityexpert.nexus.ui2.worker.staticroute;

import static com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.CHECK_3_DEFAULT_ROUTE;
import static com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.CHECK_4_ROUTE_PRECONDITION;
import static com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.CHECK_5_NEXTHOP_CONNECTED;
import static com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.CHECK_6_CONTROL_PLANE_OVERLAP;
import static com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.STATUS_FAIL;
import static com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.STATUS_PASS;
import static com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.STATUS_UNKNOWN;
import static com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.STATUS_WARN;
import static com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.longestPrefixMatch;
import static com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.normalizeDeviceIpv4;
import static com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.parsePrefixLen;
import static com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.parseRoutePrefix;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.ExistingRouteAttributes;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.PreCheckVerdict;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.ValidatedRequest;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.ConfiguredRoutesParse;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.ConfiguredStaticRoute;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.PreCheckEvaluation;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.RibParse;

/**
 * Stage A / Stage B pre-check evaluator (Contract §4.1 Checks 1..6), split out of {@link StaticRouteR0Parsers} so the
 * vendor-specific R0 parsers and the check rules can be reviewed in isolation. Behaviour is unchanged.
 */
final class StaticRouteR0PreChecks {

    private StaticRouteR0PreChecks() {
    }

    /**
     * Evaluates Contract §4.1 Checks 1..6 for either Stage A (against stored inventory) or Stage B (against live R0
     * device reads plus stored interface inventory and protected control-plane addresses).
     */
    static PreCheckEvaluation evaluate(
            String stage,
            ValidatedRequest req,
            PreCheckVerdict check1TargetReady,
            PreCheckVerdict check2IdentityHaMgmt,
            ConfiguredRoutesParse configuredRoutes,
            RibParse ribParse,
            List<InventoryInterface> contextInterfaces,
            List<String> protectedControlPlaneIps) {

        List<PreCheckVerdict> checks = new ArrayList<>(6);
        checks.add(check1TargetReady);
        checks.add(check2IdentityHaMgmt);

        // Check 3: DEFAULT_ROUTE_GUARD (0.0.0.0/0 is unconditionally forbidden)
        boolean isDefault = req.prefix().prefixLen() == 0 || "0.0.0.0/0".equals(req.prefix().canonicalCidr());
        checks.add(new PreCheckVerdict(
                3,
                CHECK_3_DEFAULT_ROUTE,
                stage,
                isDefault ? STATUS_FAIL : STATUS_PASS,
                isDefault ? "DEFAULT_ROUTE_FORBIDDEN" : "NON_DEFAULT_PREFIX",
                isDefault ? "Default route 0.0.0.0/0 is unconditionally forbidden." : "Destination prefix is non-default."));

        // Check 4: ROUTE_STATE_AND_ATTRIBUTE_PRECONDITION
        Optional<ExistingRouteAttributes> matchedExisting = Optional.empty();
        Optional<String> preRouteSha = Optional.empty();
        if (configuredRoutes == null) {
            // Stage A when only stored RIB is available: evaluate against stored RIB static routes.
            checks.add(evaluateStageARouteStateFromRib(stage, req, ribParse));
        } else if (!STATUS_PASS.equals(configuredRoutes.status())) {
            checks.add(new PreCheckVerdict(
                    4,
                    CHECK_4_ROUTE_PRECONDITION,
                    stage,
                    configuredRoutes.status(),
                    configuredRoutes.reasonCode(),
                    "Configured static-route table could not be verified."));
        } else if (ribParse == null || !STATUS_PASS.equals(ribParse.status())) {
            checks.add(new PreCheckVerdict(
                    4,
                    CHECK_4_ROUTE_PRECONDITION,
                    stage,
                    ribParse == null ? STATUS_UNKNOWN : ribParse.status(),
                    ribParse == null ? "RIB_UNAVAILABLE" : ribParse.reasonCode(),
                    "Live RIB could not be verified."));
        } else {
            preRouteSha = Optional.ofNullable(configuredRoutes.tableSha256());
            RoutePreconditionOutcome c4 = evaluateStageBRoutePrecondition(stage, req, configuredRoutes.routes(), ribParse.routes());
            checks.add(c4.verdict());
            matchedExisting = c4.existingRoute();
        }

        // Check 5: NEXTHOP_ON_CONNECTED_SUBNET
        checks.add(evaluateCheck5ConnectedSubnet(stage, req, contextInterfaces, ribParse == null ? List.of() : ribParse.routes()));

        // Check 6: CONTROL_PLANE_AND_OVERLAP_GUARD
        checks.add(evaluateCheck6ControlPlaneAndOverlap(
                stage,
                req,
                contextInterfaces,
                ribParse,
                protectedControlPlaneIps == null ? List.of() : protectedControlPlaneIps));

        String overall = STATUS_PASS;
        String stopCode = null;
        for (PreCheckVerdict c : checks) {
            if (STATUS_FAIL.equals(c.status())) {
                overall = STATUS_FAIL;
                if (stopCode == null) {
                    stopCode = c.reasonCode();
                }
            } else if (STATUS_UNKNOWN.equals(c.status()) && !STATUS_FAIL.equals(overall)) {
                overall = STATUS_UNKNOWN;
                if (stopCode == null) {
                    stopCode = c.reasonCode();
                }
            }
        }
        return new PreCheckEvaluation(overall, stopCode, List.copyOf(checks), matchedExisting, preRouteSha);
    }

    private record RoutePreconditionOutcome(
            PreCheckVerdict verdict,
            Optional<ExistingRouteAttributes> existingRoute) {
    }

    private static PreCheckVerdict evaluateStageARouteStateFromRib(
            String stage,
            ValidatedRequest req,
            RibParse ribParse) {
        if (ribParse == null || !STATUS_PASS.equals(ribParse.status())) {
            return new PreCheckVerdict(
                    4, CHECK_4_ROUTE_PRECONDITION, stage, STATUS_UNKNOWN,
                    ribParse == null ? "INVENTORY_UNAVAILABLE" : ribParse.reasonCode(),
                    "Stored routing table is not available for Stage A check.");
        }
        String targetCidr = req.prefix().canonicalCidr();
        String targetNh = req.nextHopIp();
        boolean inRib = ribParse.routes().stream().anyMatch(r ->
                targetCidr.equals(r.destination())
                        && r.nextHop().filter(targetNh::equals).isPresent()
                        && (InventoryRoute.PROTOCOL_STATIC.equals(r.protocol())
                            || InventoryRoute.PROTOCOL_DEFAULT.equals(r.protocol())));
        if (StaticRoutePreview.OP_ADD.equals(req.operation())) {
            if (inRib) {
                return new PreCheckVerdict(
                        4, CHECK_4_ROUTE_PRECONDITION, stage, STATUS_FAIL, "ROUTE_ALREADY_EXISTS",
                        "Static route already exists in stored routing table.");
            }
            return new PreCheckVerdict(
                    4, CHECK_4_ROUTE_PRECONDITION, stage, STATUS_PASS, "ROUTE_ABSENT_IN_INVENTORY",
                    "No conflicting static route in stored inventory.");
        } else {
            if (!inRib) {
                return new PreCheckVerdict(
                        4, CHECK_4_ROUTE_PRECONDITION, stage, STATUS_FAIL, "ROUTE_NOT_FOUND",
                        "Target static route was not found in stored routing table.");
            }
            return new PreCheckVerdict(
                    4, CHECK_4_ROUTE_PRECONDITION, stage, STATUS_PASS, "ROUTE_PRESENT_IN_INVENTORY",
                    "Target static route exists in stored routing table.");
        }
    }

    private static RoutePreconditionOutcome evaluateStageBRoutePrecondition(
            String stage,
            ValidatedRequest req,
            List<ConfiguredStaticRoute> configRoutes,
            List<InventoryRoute> ribRoutes) {

        String targetCidr = req.prefix().canonicalCidr();
        String targetNh = req.nextHopIp();

        // First check for unsupported attributes or template provenance on any configured route for targetCidr / routeName
        for (ConfiguredStaticRoute r : configRoutes) {
            boolean samePrefix = targetCidr.equals(r.destinationCidr());
            boolean sameName = req.routeName() != null && req.routeName().equals(r.routeName());
            if (samePrefix || sameName) {
                if (r.templateManaged()) {
                    return new RoutePreconditionOutcome(
                            new PreCheckVerdict(4, CHECK_4_ROUTE_PRECONDITION, stage, STATUS_FAIL,
                                    "PANORAMA_TEMPLATE_MANAGED",
                                    "Static route has Panorama template provenance (src=tpl)."),
                            Optional.empty());
                }
                if (r.unsupportedAttribute()) {
                    return new RoutePreconditionOutcome(
                            new PreCheckVerdict(4, CHECK_4_ROUTE_PRECONDITION, stage, STATUS_FAIL,
                                    "UNSUPPORTED_ROUTE_ATTRIBUTE",
                                    "Target prefix carries unsupported route attribute: "
                                            + Objects.toString(r.unsupportedDetail(), "special")),
                            Optional.empty());
                }
            }
        }

        // Also check if live RIB has a discard/blackhole route for targetCidr
        boolean ribDiscard = ribRoutes.stream().anyMatch(r ->
                targetCidr.equals(r.destination())
                        && r.nextHopKind().filter(InventoryRoute.NEXT_HOP_DISCARD::equals).isPresent());
        if (ribDiscard) {
            return new RoutePreconditionOutcome(
                    new PreCheckVerdict(4, CHECK_4_ROUTE_PRECONDITION, stage, STATUS_FAIL,
                            "UNSUPPORTED_ROUTE_ATTRIBUTE",
                            "Live RIB carries a discard/blackhole route for the target prefix."),
                    Optional.empty());
        }

        List<ConfiguredStaticRoute> exactMatches = configRoutes.stream()
                .filter(r -> targetCidr.equals(r.destinationCidr()) && targetNh.equals(r.nextHopIp()))
                .filter(r -> req.interfaceName() == null || r.interfaceName() == null
                        || req.interfaceName().equalsIgnoreCase(r.interfaceName()))
                .toList();

        boolean sameRouteNameInConfig = StaticRoutePreview.VENDOR_PALO_ALTO.equals(req.vendor())
                && req.routeName() != null
                && configRoutes.stream().anyMatch(r -> req.routeName().equals(r.routeName()));

        boolean inRib = ribRoutes.stream().anyMatch(r ->
                targetCidr.equals(r.destination())
                        && r.nextHop().filter(targetNh::equals).isPresent());

        if (StaticRoutePreview.OP_ADD.equals(req.operation())) {
            if (!exactMatches.isEmpty() || sameRouteNameInConfig || inRib) {
                return new RoutePreconditionOutcome(
                        new PreCheckVerdict(4, CHECK_4_ROUTE_PRECONDITION, stage, STATUS_FAIL,
                                "ROUTE_ALREADY_EXISTS",
                                "Static route (or routeName) already exists in configured static routes or live RIB."),
                        Optional.empty());
            }
            return new RoutePreconditionOutcome(
                    new PreCheckVerdict(4, CHECK_4_ROUTE_PRECONDITION, stage, STATUS_PASS,
                            "ROUTE_ABSENT_VERIFIED",
                            "Target static route is absent from configured static routes and live RIB."),
                    Optional.empty());
        } else {
            if (exactMatches.isEmpty()) {
                return new RoutePreconditionOutcome(
                        new PreCheckVerdict(4, CHECK_4_ROUTE_PRECONDITION, stage, STATUS_FAIL,
                                "ROUTE_NOT_FOUND",
                                "Target static route does not exist in configured static routes."),
                        Optional.empty());
            }
            if (exactMatches.size() > 1) {
                return new RoutePreconditionOutcome(
                        new PreCheckVerdict(4, CHECK_4_ROUTE_PRECONDITION, stage, STATUS_FAIL,
                                "ROUTE_MATCH_AMBIGUOUS",
                                "Multiple configured static routes matched the delete specification."),
                        Optional.empty());
            }
            ConfiguredStaticRoute matched = exactMatches.get(0);
            ExistingRouteAttributes attrs = new ExistingRouteAttributes(
                    matched.routeName(),
                    matched.interfaceName(),
                    matched.distance(),
                    matched.metric(),
                    matched.priority(),
                    matched.comment());
            return new RoutePreconditionOutcome(
                    new PreCheckVerdict(4, CHECK_4_ROUTE_PRECONDITION, stage, STATUS_PASS,
                            "ROUTE_FOUND_UNIQUE",
                            "Exact static route verified in configured static routes."),
                    Optional.of(attrs));
        }
    }

    private record ConnectedSubnet(
            String interfaceName,
            int networkInt,
            int prefixLen,
            int localIpInt,
            String state) {
    }

    private static PreCheckVerdict evaluateCheck5ConnectedSubnet(
            String stage,
            ValidatedRequest req,
            List<InventoryInterface> interfaces,
            List<InventoryRoute> ribRoutes) {

        List<ConnectedSubnet> subnets = extractConnectedSubnets(req, interfaces, ribRoutes);
        if (subnets.isEmpty()) {
            return new PreCheckVerdict(
                    5, CHECK_5_NEXTHOP_CONNECTED, stage, STATUS_UNKNOWN, "CONNECTED_SUBNETS_UNAVAILABLE",
                    "No operational connected IPv4 subnets available for routing context.");
        }

        int nhInt = req.nextHopInt();
        // Check if nextHopIp equals ANY local interface IP in the context first
        for (ConnectedSubnet cs : subnets) {
            if (cs.localIpInt() != 0 && cs.localIpInt() == nhInt) {
                return new PreCheckVerdict(
                        5, CHECK_5_NEXTHOP_CONNECTED, stage, STATUS_FAIL, "NEXTHOP_IS_LOCAL_INTERFACE_IP",
                        "nextHopIp equals a local interface address of the firewall.");
            }
        }

        List<ConnectedSubnet> containing = new ArrayList<>();
        for (ConnectedSubnet cs : subnets) {
            if (!InventoryInterface.STATE_UP.equalsIgnoreCase(cs.state())) {
                continue;
            }
            if (!StaticRoutePreview.prefixContains(cs.networkInt(), cs.prefixLen(), nhInt)) {
                continue;
            }
            // Reject network and broadcast address for subnets with prefixLen <= 30
            if (cs.prefixLen() <= 30) {
                int mask = StaticRoutePreview.netmaskFromPrefixLen(cs.prefixLen());
                int netAddr = cs.networkInt() & mask;
                int bcastAddr = netAddr | ~mask;
                if (nhInt == netAddr || nhInt == bcastAddr) {
                    return new PreCheckVerdict(
                            5, CHECK_5_NEXTHOP_CONNECTED, stage, STATUS_FAIL, "NEXTHOP_NOT_ON_CONNECTED_SUBNET",
                            "nextHopIp equals the subnet network or broadcast address.");
                }
            }
            containing.add(cs);
        }

        if (containing.isEmpty()) {
            return new PreCheckVerdict(
                    5, CHECK_5_NEXTHOP_CONNECTED, stage, STATUS_FAIL, "NEXTHOP_NOT_ON_CONNECTED_SUBNET",
                    "nextHopIp does not lie on any operational (UP) directly connected subnet.");
        }

        if (req.interfaceName() != null) {
            boolean interfaceMatched = containing.stream()
                    .anyMatch(cs -> req.interfaceName().equalsIgnoreCase(cs.interfaceName()));
            if (!interfaceMatched) {
                return new PreCheckVerdict(
                        5, CHECK_5_NEXTHOP_CONNECTED, stage, STATUS_FAIL, "NEXTHOP_INTERFACE_MISMATCH",
                        "nextHopIp belongs to connected interface " + containing.get(0).interfaceName()
                                + ", not " + req.interfaceName() + ".");
            }
        }

        return new PreCheckVerdict(
                5, CHECK_5_NEXTHOP_CONNECTED, stage, STATUS_PASS, "NEXTHOP_CONNECTED_VERIFIED",
                "nextHopIp lies on operational connected subnet via " + containing.get(0).interfaceName() + ".");
    }

    private static PreCheckVerdict evaluateCheck6ControlPlaneAndOverlap(
            String stage,
            ValidatedRequest req,
            List<InventoryInterface> interfaces,
            RibParse ribParse,
            List<String> protectedControlPlaneIps) {

        if (ribParse == null || !STATUS_PASS.equals(ribParse.status())) {
            return new PreCheckVerdict(
                    6, CHECK_6_CONTROL_PLANE_OVERLAP, stage, STATUS_UNKNOWN, "RIB_UNAVAILABLE",
                    "Routing table is unavailable to evaluate control-plane and overlap guards.");
        }

        List<InventoryRoute> ribRoutes = ribParse.routes();
        List<ConnectedSubnet> connected = extractConnectedSubnets(req, interfaces, ribRoutes);
        int destNet = req.prefix().networkInt();
        int destLen = req.prefix().prefixLen();

        // 6(b): Connected subnet overlap
        for (ConnectedSubnet cs : connected) {
            if (StaticRoutePreview.prefixesOverlap(destNet, destLen, cs.networkInt(), cs.prefixLen())) {
                return new PreCheckVerdict(
                        6, CHECK_6_CONTROL_PLANE_OVERLAP, stage, STATUS_FAIL, "OVERLAPS_CONNECTED_SUBNET",
                        "destinationPrefix overlaps directly connected subnet on " + cs.interfaceName() + ".");
            }
        }

        // 6(a): Control-plane best-path protection (enforced for BOTH ADD and DELETE)
        for (String protectedIp : protectedControlPlaneIps) {
            Optional<String> normIp = normalizeDeviceIpv4(protectedIp);
            if (normIp.isEmpty()) {
                continue;
            }
            int cpIpInt = StaticRoutePreview.parseIpv4ToInt(normIp.get(), "CONTROL_PLANE_IP_INVALID");
            if (!StaticRoutePreview.prefixContains(destNet, destLen, cpIpInt)) {
                continue;
            }
            Optional<InventoryRoute> currentBest = longestPrefixMatch(ribRoutes, cpIpInt);
            int currentBestLen = currentBest.flatMap(r -> parsePrefixLen(r.destination())).orElse(-1);
            if (StaticRoutePreview.OP_ADD.equals(req.operation())) {
                // Adding a route with prefixLen >= currentBestLen that covers a control-plane IP alters or shadows its best path
                if (destLen >= currentBestLen) {
                    return new PreCheckVerdict(
                            6, CHECK_6_CONTROL_PLANE_OVERLAP, stage, STATUS_FAIL, "MANAGEMENT_LOCKOUT_RISK",
                            "Candidate ADD would alter or shadow the active best path to a protected control-plane endpoint.");
                }
            } else {
                // Deleting the active best route (or a route of the same prefix length) covering a control-plane IP
                if (currentBestLen == destLen) {
                    return new PreCheckVerdict(
                            6, CHECK_6_CONTROL_PLANE_OVERLAP, stage, STATUS_FAIL, "MANAGEMENT_LOCKOUT_RISK",
                            "Candidate DELETE would remove or alter the active best path to a protected control-plane endpoint.");
                }
            }
        }

        // 6(c): Specific overlap on ADD (no equal or more-specific route in live RIB inside destinationPrefix)
        if (StaticRoutePreview.OP_ADD.equals(req.operation())) {
            for (InventoryRoute r : ribRoutes) {
                Optional<StaticRoutePreview.ParsedPrefix> rp = parseRoutePrefix(r.destination());
                if (rp.isEmpty() || rp.get().prefixLen() == 0) {
                    continue;
                }
                if (rp.get().prefixLen() >= destLen
                        && StaticRoutePreview.prefixContains(destNet, destLen, rp.get().networkInt())) {
                    return new PreCheckVerdict(
                            6, CHECK_6_CONTROL_PLANE_OVERLAP, stage, STATUS_FAIL,
                            "OVERLAPS_EXISTING_OR_MORE_SPECIFIC_ROUTE",
                            "An equal or more-specific route already exists in the routing table inside destinationPrefix.");
                }
            }
        }

        // 6(d): Check Point anti-spoofing warning (R0-CP-5 is UNKNOWN -> raises CP_ANTISPOOFING_DEFINED_BY_ROUTES_WARNING)
        if (StaticRoutePreview.VENDOR_CHECK_POINT.equals(req.vendor())) {
            return new PreCheckVerdict(
                    6, CHECK_6_CONTROL_PLANE_OVERLAP, stage, STATUS_WARN,
                    "CP_ANTISPOOFING_DEFINED_BY_ROUTES_WARNING",
                    "Control-plane and overlap checks passed; Check Point anti-spoofing topology mode is UNKNOWN in R0 "
                            + "(requires explicit confirmation if anti-spoofing is defined by routes).");
        }

        return new PreCheckVerdict(
                6, CHECK_6_CONTROL_PLANE_OVERLAP, stage, STATUS_PASS, "CONTROL_PLANE_AND_OVERLAP_SAFE",
                "Control-plane best-path protection and prefix overlap checks passed.");
    }

    private static List<ConnectedSubnet> extractConnectedSubnets(
            ValidatedRequest req,
            List<InventoryInterface> interfaces,
            List<InventoryRoute> ribRoutes) {

        List<ConnectedSubnet> out = new ArrayList<>();
        if (interfaces != null) {
            for (InventoryInterface iface : interfaces) {
                if (StaticRoutePreview.VENDOR_PALO_ALTO.equals(req.vendor())
                        && iface.routingContext().isPresent()
                        && !req.routingContext().equals(iface.routingContext().get())) {
                    continue;
                }
                for (InventoryAddress addr : iface.addresses()) {
                    if (!InventoryAddress.FAMILY_IPV4.equalsIgnoreCase(addr.family())) {
                        continue;
                    }
                    String raw = addr.address();
                    int slash = raw.indexOf('/');
                    if (slash <= 0) {
                        continue;
                    }
                    Optional<String> ipPart = normalizeDeviceIpv4(raw.substring(0, slash));
                    String lenPart = raw.substring(slash + 1);
                    if (ipPart.isEmpty() || !lenPart.matches("\\d{1,2}")) {
                        continue;
                    }
                    int len = Integer.parseInt(lenPart);
                    if (len < 1 || len > 32) {
                        continue;
                    }
                    int localInt = StaticRoutePreview.parseIpv4ToInt(ipPart.get(), "INTERFACE_IP_INVALID");
                    int mask = StaticRoutePreview.netmaskFromPrefixLen(len);
                    out.add(new ConnectedSubnet(iface.name(), localInt & mask, len, localInt, iface.state()));
                }
            }
        }
        // Also supplement from directly connected active RIB routes if interfaces had no address entries
        if (ribRoutes != null) {
            for (InventoryRoute r : ribRoutes) {
                if (!InventoryRoute.PROTOCOL_CONNECTED.equals(r.protocol())
                        || r.active().filter(b -> !b).isPresent()) {
                    continue;
                }
                Optional<StaticRoutePreview.ParsedPrefix> pp = parseRoutePrefix(r.destination());
                if (pp.isEmpty() || pp.get().prefixLen() == 0) {
                    continue;
                }
                String ifName = r.interfaceName().orElse("connected");
                boolean alreadyPresent = out.stream().anyMatch(cs ->
                        cs.networkInt() == pp.get().networkInt() && cs.prefixLen() == pp.get().prefixLen());
                if (!alreadyPresent) {
                    out.add(new ConnectedSubnet(
                            ifName, pp.get().networkInt(), pp.get().prefixLen(), 0, InventoryInterface.STATE_UP));
                }
            }
        }
        return out;
    }
}
