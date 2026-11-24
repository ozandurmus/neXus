package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import static com.securityexpert.nexus.ui2.service.policy.ZoneMatrixPolicyBridge.*;
import java.util.*;

/**
 * NAT rule-bound construction for the zone matrix (§5.6, ZM-14), split out of {@link ZoneMatrixPolicyBridge}
 * (behaviour-preserving move). Builds {@link NatRuleBound} candidates from stored Check Point and Palo Alto NAT
 * rulebases; stored snapshots only, never a network or device read.
 */
final class ZoneMatrixNatBounds {

    private ZoneMatrixNatBounds() {}

    private static final List<String> PAN_NAT_SECTIONS = List.of(PAN_PRE_NAT, PAN_LOCAL_NAT, PAN_POST_NAT);

    static List<NatRuleBound> extractNatRules(PolicySnapshot snapshot, String vendor) {
        List<NatRuleBound> out = new ArrayList<>();
        if ("CP".equals(vendor)) {
            for (Section section : snapshot.sections()) {
                if (!"CP NAT rulebase".equals(section.source())) continue;
                for (Rule rule : section.rules()) {
                    if (Boolean.FALSE.equals(rule.enabled())) continue;
                    List<String> transDstRefs = rule.extras().get("translated-destination");
                    if (transDstRefs == null || transDstRefs.isEmpty()) continue;
                    if (transDstRefs.size() == 1 && HopNatEvaluator.original(snapshot.objects().get(transDstRefs.get(0)))) {
                        continue;
                    }
                    out.add(buildCpNatBound(snapshot, rule, transDstRefs));
                }
            }
        } else if ("PAN".equals(vendor)) {
            for (String phase : PAN_NAT_SECTIONS) {
                for (Section section : snapshot.sections()) {
                    if (!phase.equals(section.name())) continue;
                    for (Rule rule : section.rules()) {
                        if (Boolean.FALSE.equals(rule.enabled())) continue;
                        if ("no-nat".equals(rule.action())) continue;
                        Map<String, List<String>> extras = rule.extras();
                        boolean forwardDnat = rule.action().endsWith("destination-nat")
                                || extras.containsKey("translated-destination")
                                || extras.containsKey("translated-destination-dynamic")
                                || extras.containsKey("destination-dynamic-distribution")
                                || extras.containsKey("translated-destination-dynamic-port");
                        if (forwardDnat) {
                            out.add(buildPanForwardDnatBound(snapshot, rule));
                        }
                        if (List.of("static-ip").equals(extras.get("source-translation-type"))
                                && List.of("yes").equals(extras.get("source-translation-bi-directional"))) {
                            out.add(buildPanReverseStaticNatBound(snapshot, rule));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static NatRuleBound buildCpNatBound(PolicySnapshot snapshot, Rule rule, List<String> transDstRefs) {
        Budget budget = new Budget(RULE_WORK_BUDGET);
        try {
            ServiceBound svc = resolveServiceAndApplication(snapshot, rule.service(), new Cell(List.of(), false), "CP", budget);
            boolean svcAny = svc.notEvaluableReason() != null || svc.upperAny();
            Map<String, List<PortRange>> svcPorts = svcAny ? allPorts() : svc.upperPorts();

            AddressBound origDst = resolveAddressCell(snapshot, rule.destination(), false, budget);
            boolean origUnresolved = origDst.notEvaluableReason() != null || origDst.dynamicAddress() || origDst.accessRole();
            List<IpRange> origRanges = origUnresolved ? List.of(new IpRange(0, IPV4_MAX_LONG)) : origDst.upperIpv4();

            if (rule.enabled() == null || !Set.of("static", "hide").contains(rule.action()) || origUnresolved) {
                return new NatRuleBound(rule.id(), rule.number(), origRanges, List.of(), svcAny, svcPorts, List.of(), true);
            }
            AddressBound transDst = resolveAddressCell(snapshot, new Cell(transDstRefs, false), false, budget);
            if (transDst.notEvaluableReason() != null || transDst.hasIpv6() || transDst.upperIpv4().isEmpty()) {
                return new NatRuleBound(rule.id(), rule.number(), origRanges, List.of(), svcAny, svcPorts, List.of(), true);
            }
            return new NatRuleBound(rule.id(), rule.number(), origRanges, transDst.explicitIpv4(),
                    svcAny, svcPorts, transDst.upperIpv4(), false);
        } catch (Unknown u) {
            return new NatRuleBound(rule.id(), rule.number(), List.of(new IpRange(0, IPV4_MAX_LONG)),
                    List.of(), true, allPorts(), List.of(), true);
        }
    }

    private static NatRuleBound buildPanForwardDnatBound(PolicySnapshot snapshot, Rule rule) {
        Budget budget = new Budget(RULE_WORK_BUDGET);
        try {
            Map<String, List<String>> extras = rule.extras();
            ServiceBound svc = resolveServiceAndApplication(snapshot, rule.service(), new Cell(List.of(), false), "PAN", budget);
            boolean svcAny = svc.notEvaluableReason() != null || svc.upperAny() || svc.panApplicationDefault();
            Map<String, List<PortRange>> svcPorts = svcAny ? allPorts() : svc.upperPorts();

            AddressBound origDst = resolveAddressCell(snapshot, rule.destination(), false, budget);
            boolean origUnresolved = origDst.notEvaluableReason() != null || origDst.dynamicAddress();
            List<IpRange> origRanges = origUnresolved ? List.of(new IpRange(0, IPV4_MAX_LONG)) : origDst.upperIpv4();

            boolean unsupportedType = !extras.getOrDefault("nat-type", List.of("ipv4")).equals(List.of("ipv4"))
                    || extras.containsKey("active-active-device-binding")
                    || extras.containsKey("translated-destination-dynamic")
                    || extras.containsKey("destination-dynamic-distribution")
                    || extras.containsKey("translated-destination-dynamic-port");
            List<String> transDstRefs = extras.getOrDefault("translated-destination", List.of());
            if (rule.enabled() == null || unsupportedType || origUnresolved || transDstRefs.isEmpty()) {
                return new NatRuleBound(rule.id(), rule.number(), origRanges, List.of(), svcAny, svcPorts, List.of(), true);
            }
            AddressBound transDst = resolveAddressCell(snapshot, new Cell(transDstRefs, false), false, budget);
            if (transDst.notEvaluableReason() != null || transDst.hasIpv6() || transDst.upperIpv4().isEmpty()) {
                return new NatRuleBound(rule.id(), rule.number(), origRanges, List.of(), svcAny, svcPorts, List.of(), true);
            }
            return new NatRuleBound(rule.id(), rule.number(), origRanges, transDst.explicitIpv4(),
                    svcAny, svcPorts, transDst.upperIpv4(), false);
        } catch (Unknown u) {
            return new NatRuleBound(rule.id(), rule.number(), List.of(new IpRange(0, IPV4_MAX_LONG)),
                    List.of(), true, allPorts(), List.of(), true);
        }
    }

    private static NatRuleBound buildPanReverseStaticNatBound(PolicySnapshot snapshot, Rule rule) {
        Budget budget = new Budget(RULE_WORK_BUDGET);
        try {
            List<String> transSrcRefs = rule.extras().getOrDefault("translated-source", List.of());
            AddressBound origDst = resolveAddressCell(snapshot, new Cell(transSrcRefs, false), false, budget);
            boolean origUnresolved = origDst.notEvaluableReason() != null || origDst.hasIpv6() || origDst.upperIpv4().isEmpty();
            List<IpRange> origRanges = origUnresolved ? List.of(new IpRange(0, IPV4_MAX_LONG)) : origDst.upperIpv4();
            if (rule.enabled() == null || origUnresolved) {
                return new NatRuleBound(rule.id(), rule.number(), origRanges, List.of(), true, allPorts(), List.of(), true);
            }
            AddressBound transDst = resolveAddressCell(snapshot, rule.source(), false, budget);
            if (transDst.notEvaluableReason() != null || transDst.hasIpv6() || transDst.upperIpv4().isEmpty()) {
                return new NatRuleBound(rule.id(), rule.number(), origRanges, List.of(), true, allPorts(), List.of(), true);
            }
            return new NatRuleBound(rule.id(), rule.number(), origRanges, transDst.explicitIpv4(),
                    true, allPorts(), transDst.upperIpv4(), false);
        } catch (Unknown u) {
            return new NatRuleBound(rule.id(), rule.number(), List.of(new IpRange(0, IPV4_MAX_LONG)),
                    List.of(), true, allPorts(), List.of(), true);
        }
    }
}
