package com.securityexpert.nexus.ui2.service.pathanalysis.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.securityexpert.nexus.ui2.jobs.pathvalidation.HopPlan;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation.Check;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult;

/**
 * Stage A6: which hops of a computed path result can be checked against their device's own matcher, and with which
 * values. Pure: no I/O but the stored-inventory lookup it is handed. Every value of a plan comes from the result (the
 * hop's tuple, route, zones, contexts, deciding rules) and is validated by {@link PathValidation}; VSID, virtual router
 * and zones must also exist in the device's latest stored inventory. A check whose expectation is UNKNOWN in the result
 * is not planned (nothing would be learned by asking the device), and a hop without any plannable check is recorded
 * NOT_EVALUABLE without contacting anything.
 *
 * <p>One branch is validated (the requested one, else the first), its first {@link PathValidation#MAX_HOPS} unit hops;
 * the rest are counted in {@code hopsNotValidated}.</p>
 */
public final class PathValidationPlanner {

    /** One hop to store; {@code plan} present when a job will be admitted for it. */
    public record PlannedHop(int ordinal, String branchId, int hopIndex, String nodeId, String vendor,
            Optional<String> deviceId, Optional<HopPlan> plan, List<String> reasons) {
        public PlannedHop {
            reasons = List.copyOf(reasons);
        }
    }

    public record Plan(String branchId, List<PlannedHop> hops, int hopsNotValidated) {
        public Plan {
            hops = List.copyOf(hops);
        }

        public long runnable() {
            return hops.stream().filter(h -> h.plan().isPresent()).count();
        }
    }

    static final String PHYSICAL = InventoryContext.PHYSICAL;
    /** The approved PAN literals name no vsys: only a single-vsys firewall's own context is targeted. */
    static final String PAN_DEFAULT_VSYS = "vsys1";
    private static final Set<String> FORWARDING = Set.of("IP", "CONNECTED", "DISCARD");

    private PathValidationPlanner() {
    }

    /** Empty when {@code branchId} names no branch of the result. */
    public static Optional<Plan> plan(PathResult result, Optional<String> branchId,
            Function<String, Optional<InventoryRun>> latestInventory) {
        Optional<PathResult.Branch> branch = branchId.isPresent()
                ? result.branches().stream().filter(b -> b.branchId().equals(branchId.get())).findFirst()
                : result.branches().stream().findFirst();
        if (branchId.isPresent() && branch.isEmpty()) {
            return Optional.empty();
        }
        if (branch.isEmpty()) {
            return Optional.of(new Plan("b1", List.of(), 0));
        }
        List<PathResult.Hop> units = branch.get().hops().stream().filter(h -> !"CLOUD".equals(h.nodeKind())).toList();
        List<PlannedHop> planned = new ArrayList<>();
        for (PathResult.Hop hop : units.subList(0, Math.min(units.size(), PathValidation.MAX_HOPS))) {
            planned.add(hop(planned.size() + 1, branch.get().branchId(), hop, latestInventory));
        }
        return Optional.of(new Plan(branch.get().branchId(), planned, Math.max(0, units.size() - PathValidation.MAX_HOPS)));
    }

    private static PlannedHop hop(int ordinal, String branchId, PathResult.Hop hop,
            Function<String, Optional<InventoryRun>> latestInventory) {
        String vendor = HopPlan.CHECK_POINT.equals(hop.vendor()) || HopPlan.PALO_ALTO.equals(hop.vendor()) ? hop.vendor() : "other";
        List<String> reasons = new ArrayList<>();
        Optional<HopPlan> plan = Optional.empty();
        if ("other".equals(vendor)) {
            reasons.add("VENDOR_UNSUPPORTED");
        } else if (hop.deviceId().isEmpty()) {
            reasons.add("ACTIVE_MEMBER_UNKNOWN");
        } else {
            Optional<InventoryRun> run = latestInventory.apply(hop.deviceId().get());
            Optional<InventoryContext> context = run.flatMap(r -> r.contexts().stream()
                    .filter(c -> c.context().equals(hop.context())).findFirst());
            if (run.isEmpty()) {
                reasons.add("INVENTORY_UNAVAILABLE");
            } else if (context.isEmpty()) {
                reasons.add("CONTEXT_NOT_IN_INVENTORY");
            } else {
                plan = HopPlan.CHECK_POINT.equals(vendor) ? checkPoint(hop, reasons) : paloAlto(hop, context.get(), reasons);
            }
        }
        plan = plan.filter(p -> p.invalid().isEmpty());
        if (plan.isEmpty() && reasons.isEmpty()) {
            reasons.add("PLAN_INVALID");
        }
        return new PlannedHop(ordinal, branchId, hop.index(), hop.nodeId(), vendor, hop.deviceId(), plan, reasons);
    }

    private static Optional<HopPlan> checkPoint(PathResult.Hop hop, List<String> reasons) {
        String vsid = null;
        if (!PHYSICAL.equals(hop.context())) {
            if (!PathValidation.vsid(hop.context())) {
                reasons.add("CONTEXT_UNSUPPORTED");
                return Optional.empty();
            }
            vsid = hop.context();
        }
        if (hop.routingTable().filter(t -> !"main".equals(t)).isPresent()) {
            reasons.add("ROUTING_TABLE_NOT_MAIN");
            return Optional.empty();
        }
        Optional<HopPlan.Expect> expect = routeExpect(hop, reasons, true);
        if (expect.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new HopPlan(PathValidation.PLAN_VERSION, HopPlan.CHECK_POINT, hop.deviceId().get(), hop.context(),
                vsid, null, hop.packetOut().destination(), null, null, protocolNumber(hop.packetIn().protocol()),
                hop.packetIn().destinationPort(), null, null, null, List.of(Check.CP_ROUTE), expect.get()));
    }

    /** The routing expectation, or empty (with a reason) when the result does not decide it. */
    private static Optional<HopPlan.Expect> routeExpect(PathResult.Hop hop, List<String> reasons, boolean checkPoint) {
        if (!PathValidation.ipv4(hop.packetOut().destination())) {
            reasons.add("DESTINATION_INVALID");
            return Optional.empty();
        }
        if (hop.route().isEmpty()) {
            if (!checkPoint) {
                reasons.add("NO_ROUTE_NOT_COMPARABLE");
                return Optional.empty();
            }
            return Optional.of(new HopPlan.Expect("NO_ROUTE", null, null, false, null, null, null, null));
        }
        PathResult.RouteUsed route = hop.route().get();
        Optional<String> kind = route.nextHopKind().filter(FORWARDING::contains);
        if (kind.isEmpty()) {
            reasons.add("ENGINE_ROUTE_UNKNOWN");
            return Optional.empty();
        }
        Optional<String> egress = route.interfaceName().or(() -> hop.egress().interfaceName());
        if (!"DISCARD".equals(kind.get()) && egress.isEmpty()) {
            reasons.add("ENGINE_INTERFACE_UNKNOWN");
            return Optional.empty();
        }
        return Optional.of(new HopPlan.Expect(kind.get(), egress.orElse(null),
                "IP".equals(kind.get()) ? route.nextHop().filter(PathValidation::ipv4).orElse(null) : null,
                route.ecmpBranch(), null, null, null, null));
    }

    private static Optional<HopPlan> paloAlto(PathResult.Hop hop, InventoryContext context, List<String> reasons) {
        if (!PAN_DEFAULT_VSYS.equals(hop.context())) {
            reasons.add("VSYS_TARGET_NOT_APPROVED");
            return Optional.empty();
        }
        List<Check> checks = new ArrayList<>();
        List<InventoryInterface> interfaces = context.interfaces();

        String vr = hop.routingTable().orElse(null);
        HopPlan.Expect route = null;
        if (vr == null || !PathValidation.name(vr)
                || interfaces.stream().noneMatch(i -> i.routingContext().filter(vr::equals).isPresent())) {
            reasons.add("VIRTUAL_ROUTER_UNKNOWN");
        } else {
            Optional<HopPlan.Expect> expect = routeExpect(hop, reasons, false);
            if (expect.isPresent()) {
                checks.add(Check.PAN_FIB);
                route = expect.get();
            }
        }

        String fromZone = hop.ingress().zone().filter(z -> zoneKnown(z, interfaces)).orElse(null);
        String toZone = hop.egress().zone().filter(z -> zoneKnown(z, interfaces)).orElse(null);
        String securityVerdict = null, securityRule = null, natStatus = null, natRule = null, natToZone = null;
        boolean tcp = "tcp".equals(hop.packetIn().protocol());
        boolean tuple = PathValidation.ipv4(hop.packetIn().source()) && PathValidation.ipv4(hop.packetIn().destination())
                && PathValidation.port(hop.packetIn().destinationPort());
        if (!tcp) {
            reasons.add(PathValidation.PROTOCOL_NOT_APPROVED);
        } else if (hop.policyStale()) {
            reasons.add("POLICY_STALE");
        } else if (!tuple) {
            reasons.add("TUPLE_INVALID");
        } else if (fromZone == null) {
            reasons.add("ZONE_UNKNOWN");
        } else {
            Optional<PathResult.Security> security = hop.security();
            Optional<String> rule = security.filter(s -> "ALLOWED".equals(s.verdict()) || "DENIED".equals(s.verdict()))
                    .flatMap(PathResult.Security::decidingRule).map(r -> r.get("name")).filter(String.class::isInstance)
                    .map(String.class::cast).filter(n -> !n.isBlank());
            if (toZone == null) {
                reasons.add("ZONE_UNKNOWN");
            } else if (rule.isEmpty()) {
                reasons.add("ENGINE_RULE_UNKNOWN");
            } else {
                checks.add(Check.PAN_SECURITY);
                securityVerdict = security.get().verdict();
                securityRule = rule.get();
            }
            Optional<PathResult.NatApplied> nat = hop.nat()
                    .filter(n -> "TRANSLATED".equals(n.status()) || "NO_TRANSLATION".equals(n.status()));
            boolean destinationTranslated = !hop.packetIn().destination().equals(hop.packetOut().destination())
                    || hop.packetIn().destinationPort() != hop.packetOut().destinationPort();
            if (nat.isEmpty()) {
                reasons.add("ENGINE_NAT_UNKNOWN");
            } else if (destinationTranslated || toZone == null) {
                // NAT is matched on the pre-NAT egress zone, which the result does not carry after a destination change.
                reasons.add("NAT_ZONE_NOT_IN_RESULT");
            } else {
                Optional<String> first = nat.get().rules().stream().findFirst().map(r -> r.get("name"))
                        .filter(String.class::isInstance).map(String.class::cast);
                if (!nat.get().rules().isEmpty() && first.filter(n -> !n.isBlank()).isEmpty()) {
                    reasons.add("ENGINE_NAT_UNKNOWN");
                } else {
                    checks.add(Check.PAN_NAT);
                    natStatus = nat.get().status();
                    natRule = first.orElse(null);
                    natToZone = toZone;
                }
            }
        }
        if (checks.isEmpty()) {
            return Optional.empty();
        }
        HopPlan.Expect expect = new HopPlan.Expect(route == null ? null : route.routeKind(),
                route == null ? null : route.egressInterface(), route == null ? null : route.nextHop(),
                route != null && route.ecmp(), securityVerdict, securityRule, natStatus, natRule);
        boolean policy = checks.contains(Check.PAN_SECURITY) || checks.contains(Check.PAN_NAT);
        return Optional.of(new HopPlan(PathValidation.PLAN_VERSION, HopPlan.PALO_ALTO, hop.deviceId().get(), hop.context(),
                null, checks.contains(Check.PAN_FIB) ? vr : null,
                checks.contains(Check.PAN_FIB) ? hop.packetOut().destination() : null,
                policy ? hop.packetIn().source() : null, policy ? hop.packetIn().destination() : null,
                protocolNumber(hop.packetIn().protocol()), hop.packetIn().destinationPort(), policy ? fromZone : null,
                checks.contains(Check.PAN_SECURITY) ? toZone : null, natToZone, checks, expect));
    }

    private static boolean zoneKnown(String zone, List<InventoryInterface> interfaces) {
        return PathValidation.name(zone) && interfaces.stream().anyMatch(i -> i.zone().filter(zone::equals).isPresent());
    }

    private static int protocolNumber(String protocol) {
        return Map.of("tcp", PathValidation.TCP, "udp", PathValidation.UDP).getOrDefault(protocol, 0);
    }
}
