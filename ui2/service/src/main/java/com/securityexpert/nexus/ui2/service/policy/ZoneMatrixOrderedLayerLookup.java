package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import static com.securityexpert.nexus.ui2.service.policy.ZoneMatrixPolicyBridge.*;
import java.util.*;

/** Bounded per-layer tuple evaluator aligned with {@link PolicyLookup} and {@link HopPolicyEvaluator#policyTargets}. */
public final class ZoneMatrixOrderedLayerLookup {
    private record LayerEntry(Section section, Rule rule, Match match, int layer, boolean universalInstallOn, Set<String> installRefs) {}
    private final PolicySnapshot snapshot;
    private final List<Integer> layers;
    private final List<LayerEntry> entries;
    private final boolean buildFailed;

    private ZoneMatrixOrderedLayerLookup(PolicySnapshot snapshot, List<Integer> layers, List<LayerEntry> entries, boolean buildFailed) {
        this.snapshot = snapshot;
        this.layers = layers;
        this.entries = entries;
        this.buildFailed = buildFailed;
    }

    static ZoneMatrixOrderedLayerLookup build(PolicySnapshot snapshot, Set<Integer> orderedLayers) {
        Budget budget = new Budget(PolicyHygiene.RULE_WORK * 4);
        try {
            Resolver resolver = new Resolver(snapshot.objects(), budget);
            CpLayers cpLayers = new CpLayers();
            List<LayerEntry> entries = new ArrayList<>();
            Set<String> inlineParents = new HashSet<>();
            for (Section section : snapshot.sections()) {
                if (PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), section)) continue;
                if (!"CP access layer".equals(section.source())) return new ZoneMatrixOrderedLayerLookup(snapshot, List.of(), List.of(), true);
                if (section.rules().isEmpty()) continue;
                if (section.parentRuleId() != null && !inlineParents.contains(section.parentRuleId())) {
                    return new ZoneMatrixOrderedLayerLookup(snapshot, List.of(), List.of(), true);
                }
                int layer = cpLayers.advance(section, budget);
                for (Rule r : section.rules()) {
                    budget.spend();
                    if ("Apply Layer".equals(r.action())) inlineParents.add(r.id());
                    if (Boolean.FALSE.equals(r.enabled())) continue;
                    Match m = PolicyNormalization.normalize(snapshot, section, r, resolver, true);
                    List<String> install = r.extras().getOrDefault("install-on", List.of());
                    boolean universal = install.stream().anyMatch(id -> {
                        PolicyObject o = snapshot.objects().get(id);
                        return o != null && ("any".equals(o.type()) && "RESOLVED".equals(o.status())
                                || HopPolicyEvaluator.policyTargets(o));
                    });
                    entries.add(new LayerEntry(section, r, m, section.parentRuleId() == null ? layer : -1,
                            universal, Set.copyOf(install)));
                }
            }
            return new ZoneMatrixOrderedLayerLookup(snapshot, List.copyOf(orderedLayers), List.copyOf(entries), false);
        } catch (Unknown u) {
            return new ZoneMatrixOrderedLayerLookup(snapshot, List.copyOf(orderedLayers), List.of(), true);
        }
    }

    OtherLayersVerdict check(
            int violatingLayer,
            List<String> targetDeviceIds,
            Set<String> rawInstallOnRefs,
            boolean installOnUnresolved,
            long srcIp,
            long dstIp,
            String service) {
        if (buildFailed || !snapshot.failures().isEmpty()
                || service == null || !service.matches("(?:tcp|udp)/(?:0|[1-9][0-9]{0,4})")) {
            return OtherLayersVerdict.UNKNOWN;
        }
        String proto = service.substring(0, 3);
        long port = Long.parseLong(service.substring(4));
        if (port > 65535) return OtherLayersVerdict.UNKNOWN;

        Budget budget = new Budget(RULE_WORK_BUDGET);
        try {
            boolean anyUnknown = false;
            for (int layer : layers) {
                if (layer == violatingLayer) continue;
                String verdict = decideLayerForRuleTargets(
                        layer, null, 0, targetDeviceIds, rawInstallOnRefs, installOnUnresolved, srcIp, dstIp, proto, port, budget);
                if ("DENIED".equals(verdict)) return OtherLayersVerdict.DENIED;
                if (!"ALLOWED".equals(verdict)) anyUnknown = true;
            }
            return anyUnknown ? OtherLayersVerdict.UNKNOWN : OtherLayersVerdict.ALL_ALLOWED;
        } catch (Unknown u) {
            return OtherLayersVerdict.UNKNOWN;
        }
    }

    private String decideLayerForRuleTargets(
            int layer,
            String parentRuleId,
            int depth,
            List<String> targetDeviceIds,
            Set<String> rawInstallOnRefs,
            boolean installOnUnresolved,
            long srcIp,
            long dstIp,
            String proto,
            long port,
            Budget budget) {
        if (!installOnUnresolved && targetDeviceIds != null && targetDeviceIds.size() > 1) {
            boolean allDenied = true, allAllowed = true;
            for (String dev : targetDeviceIds) {
                String v = decideSingleScope(layer, parentRuleId, depth, List.of(dev), Set.of(dev), false,
                        srcIp, dstIp, proto, port, budget);
                allDenied &= "DENIED".equals(v);
                allAllowed &= "ALLOWED".equals(v);
            }
            return allDenied ? "DENIED" : allAllowed ? "ALLOWED" : "UNKNOWN";
        }
        return decideSingleScope(layer, parentRuleId, depth, targetDeviceIds, rawInstallOnRefs, installOnUnresolved,
                srcIp, dstIp, proto, port, budget);
    }

    private String decideSingleScope(
            int layer,
            String parentRuleId,
            int depth,
            List<String> targetDeviceIds,
            Set<String> rawInstallOnRefs,
            boolean installOnUnresolved,
            long srcIp,
            long dstIp,
            String proto,
            long port,
            Budget budget) {
        if (depth > 32) return "UNKNOWN";
        boolean earlierUndecided = false;
        Set<String> enrolledTargets = snapshot.metadata().targets().stream()
                .map(Target::deviceId).collect(java.util.stream.Collectors.toSet());

        for (LayerEntry entry : entries) {
            budget.spend();
            if (!Objects.equals(entry.section.parentRuleId(), parentRuleId)) continue;
            if (parentRuleId == null && entry.layer != layer) continue;

            String problem = entry.match.problem();
            // PolicyNormalization.normalize(..., true) marks non-"RESOLVED" install-on (such as "Policy Targets",
            // status UNSUPPORTED) as "unresolved install-on selector". Re-evaluate universal Policy Targets cleanly.
            List<Selector> selectors = entry.match.selectors();
            if ("unresolved install-on selector".equals(problem) && entry.universalInstallOn) {
                Resolver r = new Resolver(snapshot.objects(), budget);
                Match retry = tryResolveTupleCells(snapshot, entry.rule, r);
                problem = retry.problem();
                selectors = retry.selectors();
            }

            if (problem == null) {
                if (!coversPoint(selectors.get(0), "ipv4", srcIp, budget)
                        || !coversPoint(selectors.get(1), "ipv4", dstIp, budget)
                        || !coversPoint(selectors.get(2), proto, port, budget)) {
                    continue;
                }
                if (!selectors.get(3).any()) {
                    problem = "application constraint";
                } else {
                    String placementCheck = checkPlacement(entry, enrolledTargets, targetDeviceIds, rawInstallOnRefs, installOnUnresolved);
                    if ("SKIP".equals(placementCheck)) continue;
                    if ("UNKNOWN".equals(placementCheck)) problem = "target context unproven";
                }
            }

            if (problem == null && "Apply Layer".equals(entry.rule.action())) {
                boolean hasChild = entries.stream().anyMatch(e -> entry.rule.id().equals(e.section.parentRuleId()));
                if (!hasChild) return "UNKNOWN";
                String childVerdict = decideSingleScope(-1, entry.rule.id(), depth + 1,
                        targetDeviceIds, rawInstallOnRefs, installOnUnresolved, srcIp, dstIp, proto, port, budget);
                return earlierUndecided ? "UNKNOWN" : childVerdict;
            }
            if (problem != null) {
                earlierUndecided = true;
                continue;
            }
            if (earlierUndecided) return "UNKNOWN";
            return PolicyNormalization.ALLOWING_ACTIONS.contains(entry.rule.action()) ? "ALLOWED" : "DENIED";
        }
        return "UNKNOWN";
    }

    private static Match tryResolveTupleCells(PolicySnapshot snapshot, Rule rule, Resolver resolver) {
        try {
            if (rule.enabled() == null) return new Match(rule, List.of(), Map.of(), "enabled state is unknown");
            boolean apply = "Apply Layer".equals(rule.action()) && rule.extras().getOrDefault("inline-layer", List.of()).size() == 1;
            if (!apply && !PolicyNormalization.TERMINAL_ACTIONS.contains(rule.action())) {
                return new Match(rule, List.of(), Map.of(), "non-terminal or unknown action");
            }
            for (var e : rule.extras().entrySet()) {
                String k = e.getKey();
                if ("install-on".equals(k) || (apply && "inline-layer".equals(k))) continue;
                if (Set.of("time", "vpn", "content").contains(k)) {
                    for (String id : e.getValue()) {
                        PolicyObject o = snapshot.objects().get(id);
                        if (o == null || !"RESOLVED".equals(o.status()) || !"any".equals(o.type())) {
                            return new Match(rule, List.of(), Map.of(), "time or content constraint");
                        }
                    }
                } else if (!Set.of("tag", "layer-name", "last-modified", "last-modifier", "log-setting").contains(k)
                        && !k.startsWith("track-")) {
                    return new Match(rule, List.of(), Map.of(), "unsupported rule constraint");
                }
            }
            if (!rule.extras().containsKey("time")) {
                return new Match(rule, List.of(), Map.of(), "time evidence not collected");
            }
            Selector s = resolver.cell(rule.source(), "address");
            Selector d = resolver.cell(rule.destination(), "address");
            Selector svc = resolver.cell(rule.service(), "service");
            Selector app = rule.application().refs().isEmpty() && !rule.application().negated()
                    ? new Selector(true, Map.of()) : resolver.cell(rule.application(), "application");
            return new Match(rule, List.of(s, d, svc, app), Map.of(), null);
        } catch (Unknown u) {
            if ("analysis budget".equals(u.getMessage())) throw u;
            return new Match(rule, List.of(), Map.of(), u.getMessage());
        }
    }

    private static String checkPlacement(
            LayerEntry entry,
            Set<String> enrolledTargets,
            List<String> ruleTargets,
            Set<String> rawInstallOnRefs,
            boolean installOnUnresolved) {
        if (entry.universalInstallOn) return "APPLIES";
        if (entry.installRefs.isEmpty()) return "UNKNOWN";
        if (!rawInstallOnRefs.isEmpty() && entry.installRefs.containsAll(rawInstallOnRefs)) return "APPLIES";
        if (!installOnUnresolved && ruleTargets != null && !ruleTargets.isEmpty()) {
            if (entry.installRefs.containsAll(ruleTargets)) return "APPLIES";
            if (enrolledTargets.containsAll(entry.installRefs)
                    && Collections.disjoint(entry.installRefs, ruleTargets)) {
                return "SKIP";
            }
        }
        return "UNKNOWN";
    }

    private static boolean coversPoint(Selector sel, String family, long point, Budget budget) {
        if (sel.any()) return true;
        for (Range r : sel.ranges().getOrDefault(family, List.of())) {
            budget.spend();
            if (r.start() <= point && point <= r.end()) return true;
        }
        return false;
    }
}
