package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySchedule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import java.math.BigInteger;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Public bridge exposing stored-policy normalization, upper-bound matching, NAT translation extraction, hygiene
 * cover verdicts and Check Point ordered-layer witness checks to {@code service.zonematrix} without widening the
 * package-private internals of {@link PolicyNormalization}, {@link HopNatEvaluator} or {@link HopPolicyEvaluator}.
 *
 * <p>Implements delivery step 3 of {@code docs/design/ZONE_MATRIX_CONTRACT.md} (FROZEN, rev 2, 2026-11-13): stored
 * snapshots only, never a network or device read.</p>
 */
public final class ZoneMatrixPolicyBridge {
    private ZoneMatrixPolicyBridge() {}

    public static final int RULE_WORK_BUDGET = PolicyHygiene.RULE_WORK;
    public static final int HYGIENE_WORK_BUDGET = PolicyHygiene.TOTAL_WORK;
    public static final Duration SCHEDULE_TIMEZONE_MARGIN = Duration.ofHours(14);

    private static final BigInteger ULA_START = new BigInteger("fc000000000000000000000000000000", 16);
    private static final BigInteger ULA_END = new BigInteger("fdffffffffffffffffffffffffffffff", 16);
    static final long IPV4_MAX_LONG = 0xFFFF_FFFFL;

    public static final Set<String> DENY_ACTIONS = Set.of(
            "Drop", "Reject", "deny", "drop", "reset-client", "reset-server", "reset-both");
    public static final Set<String> ALLOW_ACTIONS = Set.of("Accept", "allow");
    public static final Set<String> ASK_INFORM_ACTIONS = Set.of("Ask", "Inform");
    public static final Set<String> CATCH_ALL_TERMINAL_ACTIONS = Set.of("Accept", "Drop", "Reject");
    private static final Set<String> PAN_DEFAULT_SECTIONS = Set.of(PAN_PRE_DEFAULT, PAN_POST_DEFAULT, PAN_LOCAL_DEFAULT);

    /** Inclusive IPv4 interval {@code [start, end]} in {@code 0..0xFFFFFFFFL}. */
    public record IpRange(long start, long end) {
        public IpRange {
            if (start < 0 || end > IPV4_MAX_LONG || start > end)
                throw new IllegalArgumentException("invalid IPv4 range");
        }
    }

    /** Inclusive TCP/UDP port interval {@code [low, high]} in {@code 0..65535}. */
    public record PortRange(int low, int high) {
        public PortRange {
            if (low < 0 || high > 65535 || low > high)
                throw new IllegalArgumentException("invalid port range");
        }
    }

    /** Referenced IPv6 Unique Local Address interval ({@code fc00::/7}) for §3.3 zone-definition gap reporting. */
    public record UlaRange(BigInteger start, BigInteger end) {}

    /**
     * Resolved address cell for {@code upperBoundMatch}.
     *
     * <ul>
     *   <li>{@code upperIpv4}: sorted, merged IPv4 ranges of the upper bound (for {@code any}, dynamic objects and
     *       access roles this is {@code 0.0.0.0-255.255.255.255}).</li>
     *   <li>{@code lowerIpv4}: sorted, merged IPv4 ranges proven to match (or, for an access role, the role's
     *       {@code networks} bound for proving {@code VIOLATION + IDENTITY_IGNORED}).</li>
     *   <li>{@code explicitIpv4}: sorted, merged non-{@code any} IPv4 ranges explicitly named by objects in the cell,
     *       used for §3.3 unassigned RFC 1918 gap detection.</li>
     *   <li>{@code hasIpv6}: true when the upper bound includes IPv6 ({@code any}, dynamic object, access role).</li>
     *   <li>{@code notEvaluableReason}: non-null when the cell cannot be decided cleanly as static IPv4.</li>
     * </ul>
     */
    public record AddressBound(
            List<IpRange> upperIpv4,
            List<IpRange> lowerIpv4,
            List<IpRange> explicitIpv4,
            List<UlaRange> ulaRanges,
            boolean hasIpv6,
            boolean accessRole,
            boolean dynamicAddress,
            String notEvaluableReason) {}

    /**
     * Resolved service + application upper/lower port bounds for {@code upperBoundMatch}.
     *
     * <ul>
     *   <li>{@code upperAny}: upper bound permits any protocol/port (e.g. {@code service = any} or Check Point
     *       {@code application-site} in {@code service}).</li>
     *   <li>{@code lowerAny}: rule concretely permits {@code any} service without a Check Point application-site
     *       narrowing constraint.</li>
     *   <li>{@code panApplicationDefault}: Palo Alto {@code service = application-default}.</li>
     *   <li>{@code cpApplicationConstraint}: Check Point {@code application-site} / {@code application-group} narrows
     *       matching; {@code COMPLIANT} iff {@code upperPorts}/{@code upperAny} lies inside every touched cell, else
     *       {@code NOT_EVALUABLE "application constraint"} (unless concrete sibling {@code lowerPorts} already violate).</li>
     * </ul>
     */
    public record ServiceBound(
            boolean upperAny,
            boolean lowerAny,
            boolean panApplicationDefault,
            boolean cpApplicationConstraint,
            Map<String, List<PortRange>> upperPorts,
            Map<String, List<PortRange>> lowerPorts,
            String notEvaluableReason) {}

    /**
     * Upper-bound classification of a single rule in a security section or Palo Alto default-override section.
     */
    public record RuleUpperBound(
            String kind,
            String notApplicableKind,
            String notApplicableDetail,
            String unknownReason,
            String notEvaluableReason,
            AddressBound source,
            AddressBound destination,
            ServiceBound service,
            Set<String> baseFlags,
            List<String> targetDeviceIds,
            Set<String> rawInstallOnRefs) {
        /** {@code kind} values: {@code EVALUATE}, {@code APPLY_LAYER}, {@code ASK_INFORM}, {@code NOT_APPLICABLE}, {@code NOT_EVALUABLE}, {@code UNKNOWN}. */
        public boolean evaluableRule() {
            return "EVALUATE".equals(kind) || "APPLY_LAYER".equals(kind) || "ASK_INFORM".equals(kind);
        }
    }

    /** One security or non-security rule in stored evaluation order with its scope lineage. */
    public record ScopedRule(
            Section section,
            Rule rule,
            boolean outsideSecurityRulebase,
            boolean panDefaultOverrideSection,
            boolean scopeUnproven,
            int cpOrderedLayer,
            List<String> inlineParentPath) {}

    /** Destination or static NAT rule extracted from the same snapshot (§5.6). */
    public record NatRuleBound(
            String ruleId,
            int ruleNumber,
            List<IpRange> originalDest,
            List<IpRange> explicitTranslatedDest,
            boolean serviceAny,
            Map<String, List<PortRange>> originalService,
            List<IpRange> translatedDest,
            boolean unresolved) {}

    /** Hygiene cover information for a rule (§5.5, {@code ZM-25}). */
    public record HygieneCover(boolean coveredByEarlierDeny, boolean inlineLayerCover, String coveringRuleId) {}

    public enum OtherLayersVerdict { ALL_ALLOWED, DENIED, UNKNOWN }

    /** Pre-analysed snapshot ready for pure zone-matrix evaluation. */
    public record PreparedPolicy(
            PolicySnapshot snapshot,
            String vendor,
            boolean panoramaSourced,
            boolean hasPanIntrazoneNonAllowOverride,
            int orderedLayerCount,
            boolean allOrderedLayersHaveExplicitCatchAll,
            Map<String, Boolean> inlineLayerHasExplicitCatchAll,
            Map<String, Boolean> inlineLayerPresent,
            List<ScopedRule> scopedRules,
            Map<String, RuleUpperBound> ruleBounds,
            List<NatRuleBound> natRules,
            Map<String, HygieneCover> hygieneCovers,
            boolean hygieneBudgetReached,
            long hygieneMillis,
            OrderedLayerLookup orderedLayerLookup) {}

    /** Joins stored Check Point domain object inventory when available (same as {@link PolicyQueryService#analysis}). */
    public static PolicySnapshot withDomainObjects(PolicyQueryService queryService, PolicySnapshot snapshot) {
        if (queryService == null || snapshot == null || snapshot.metadata() == null
                || !"CP".equals(snapshot.metadata().vendor())) {
            return snapshot;
        }
        try {
            PolicyDomainIndex index = queryService.domainIndex(
                    snapshot.metadata().sourceId(), snapshot.metadata().containerId());
            Map<String, PolicyObject> merged = PolicyQueryService.breadthObjects(snapshot, index);
            return merged == snapshot.objects()
                    ? snapshot
                    : new PolicySnapshot(snapshot.metadata(), snapshot.sections(), merged, snapshot.failures());
        } catch (RuntimeException unavailable) {
            return snapshot;
        }
    }

    /** Prepares a stored snapshot for deterministic zone-matrix evaluation. */
    public static PreparedPolicy prepare(PolicySnapshot rawSnapshot, Instant now) {
        Objects.requireNonNull(rawSnapshot, "snapshot");
        Objects.requireNonNull(now, "now");
        PolicySnapshot snapshot = rawSnapshot.withPageSplitsMerged();
        String vendor = snapshot.metadata() == null || snapshot.metadata().vendor() == null
                ? "" : snapshot.metadata().vendor();

        boolean localPan = "PAN".equals(vendor)
                && snapshot.sections().stream().anyMatch(s -> PAN_LOCAL_RULES.equals(s.name()));
        boolean panoramaSourced = "PAN".equals(vendor) && !localPan;
        boolean hasPanIntrazoneNonAllowOverride = localPan && hasPanNonAllowIntrazoneOverride(snapshot);

        // 1. Classify sections and rules in stored order, tracking CP ordered layers and inline parent paths.
        List<ScopedRule> scopedRules = new ArrayList<>();
        Set<Integer> orderedLayers = new LinkedHashSet<>();
        Map<Integer, List<Rule>> orderedLayerRules = new LinkedHashMap<>();
        Map<String, List<Rule>> inlineLayerRulesByParent = new LinkedHashMap<>();
        Map<String, Boolean> inlineLayerBroken = new HashMap<>();

        if ("CP".equals(vendor)) {
            classifyCheckPointSections(snapshot, scopedRules, orderedLayers, orderedLayerRules,
                    inlineLayerRulesByParent, inlineLayerBroken);
        } else if ("PAN".equals(vendor)) {
            classifyPaloAltoSections(snapshot, scopedRules);
        }

        // 2. Compute per-rule upperBoundMatch with per-rule work budget.
        Map<String, RuleUpperBound> ruleBounds = new LinkedHashMap<>();
        for (ScopedRule sr : scopedRules) {
            if (sr.outsideSecurityRulebase() && !sr.panDefaultOverrideSection()) continue;
            if (sr.scopeUnproven()) {
                ruleBounds.put(sr.rule().id(), new RuleUpperBound(
                        "UNKNOWN", null, null, "unsupported evaluation scope", null,
                        null, null, null, Set.of(), resolveTargets(snapshot, sr.rule(), false), Set.of()));
                continue;
            }
            if (sr.panDefaultOverrideSection()) {
                ruleBounds.put(sr.rule().id(), evaluatePanDefaultRule(snapshot, sr.rule()));
            } else {
                ruleBounds.put(sr.rule().id(), computeUpperBound(snapshot, sr.rule(), now));
            }
        }

        // 3. Determine explicit universal catch-all rules for CP ordered layers and inline layers.
        boolean allOrderedCatchAll = !orderedLayers.isEmpty();
        for (int layer : orderedLayers) {
            List<Rule> rules = orderedLayerRules.getOrDefault(layer, List.of());
            if (!endsWithUniversalCatchAll(snapshot, rules, null)) {
                allOrderedCatchAll = false;
            }
        }
        Map<String, Boolean> inlineCatchAll = new HashMap<>();
        Map<String, Boolean> inlinePresent = new HashMap<>();
        for (ScopedRule sr : scopedRules) {
            if ("CP".equals(vendor) && "Apply Layer".equals(sr.rule().action())) {
                String parentId = sr.rule().id();
                boolean hasFailure = snapshot.failures().stream().anyMatch(f ->
                        !PolicySnapshot.ADVISORY_REASONS.contains(f.reason()) && !PolicySnapshot.unknownPart(f));
                List<Rule> children = inlineLayerRulesByParent.get(parentId);
                boolean present = children != null && !Boolean.TRUE.equals(inlineLayerBroken.get(parentId));
                inlinePresent.put(parentId, present);
                boolean catchAll = present && !hasFailure && endsWithUniversalCatchAll(snapshot, children, sr.rule());
                inlineCatchAll.put(parentId, catchAll);
            }
        }

        // 4. Extract NAT dual-view candidates (§5.6).
        List<NatRuleBound> natRules = ZoneMatrixNatBounds.extractNatRules(snapshot, vendor);

        // 5. Run PolicyHygiene within its snapshot budget to obtain proven CONFLICT deny covers & INLINE_LAYER_COVER (§5.5, ZM-25).
        long hStart = System.nanoTime();
        Map<String, HygieneCover> hygieneCovers = new HashMap<>();
        boolean hygieneBudgetReached = false;
        if ("CP".equals(vendor) || "PAN".equals(vendor)) {
            PolicyHygiene.Result hygiene = PolicyHygiene.analyze(snapshot, 90, now, HYGIENE_WORK_BUDGET);
            hygieneBudgetReached = hygiene.budgetReached();
            Map<String, Rule> rulesById = new HashMap<>();
            for (Section s : snapshot.sections()) for (Rule r : s.rules()) rulesById.put(r.id(), r);
            hygiene.rules().forEach((ruleId, assessment) -> {
                String coveringId = assessment.coveringRuleId();
                if (coveringId == null) return;
                boolean inlineCover = assessment.findings().stream()
                        .anyMatch(f -> PolicyHygiene.INLINE_LAYER_COVER.equals(f.evidence()));
                Rule coveringRule = rulesById.get(coveringId);
                boolean conflictByDeny = "CONFLICT".equals(assessment.shadowStatus())
                        && coveringRule != null && DENY_ACTIONS.contains(coveringRule.action());
                if (conflictByDeny || inlineCover) {
                    hygieneCovers.put(ruleId, new HygieneCover(conflictByDeny, inlineCover, coveringId));
                }
            });
        }
        long hygieneMillis = (System.nanoTime() - hStart) / 1_000_000L;

        OrderedLayerLookup lookup = "CP".equals(vendor) && orderedLayers.size() > 1
                ? OrderedLayerLookup.build(snapshot, orderedLayers)
                : null;

        return new PreparedPolicy(
                snapshot,
                vendor,
                panoramaSourced,
                hasPanIntrazoneNonAllowOverride,
                orderedLayers.size(),
                allOrderedCatchAll,
                Map.copyOf(inlineCatchAll),
                Map.copyOf(inlinePresent),
                List.copyOf(scopedRules),
                Collections.unmodifiableMap(ruleBounds),
                List.copyOf(natRules),
                Map.copyOf(hygieneCovers),
                hygieneBudgetReached,
                hygieneMillis,
                lookup);
    }

    private static boolean hasPanNonAllowIntrazoneOverride(PolicySnapshot snapshot) {
        for (Section s : snapshot.sections()) {
            if (!PAN_LOCAL_DEFAULT.equals(s.name())) continue;
            for (Rule r : s.rules()) {
                if (Boolean.FALSE.equals(r.enabled()) || r.enabled() == null) continue;
                if (List.of("intrazone").equals(r.extras().get("rule-type")) && DENY_ACTIONS.contains(r.action())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void classifyCheckPointSections(
            PolicySnapshot snapshot,
            List<ScopedRule> out,
            Set<Integer> orderedLayers,
            Map<Integer, List<Rule>> orderedLayerRules,
            Map<String, List<Rule>> inlineLayerRulesByParent,
            Map<String, Boolean> inlineLayerBroken) {
        record LayerFrame(int layer, String parentRuleId, List<String> path, boolean unproven) {}
        CpLayers cpLayers = new CpLayers();
        Set<Integer> layersSeen = new HashSet<>();
        Set<String> sectionIds = new HashSet<>();
        Set<String> ruleIds = new HashSet<>();
        Deque<LayerFrame> open = new ArrayDeque<>();
        Section previousAccess = null;
        boolean broken = false;

        for (Section section : snapshot.sections()) {
            if (PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), section)) {
                for (Rule r : section.rules()) {
                    out.add(new ScopedRule(section, r, true, false, false, -1, List.of()));
                }
                continue;
            }
            if (!"CP access layer".equals(section.source())) {
                broken = true;
                for (Rule r : section.rules()) {
                    out.add(new ScopedRule(section, r, false, false, true, -1, List.of()));
                }
                continue;
            }
            if (section.rules().isEmpty()) continue;

            int layer = -1;
            boolean sectionUnproven = broken;
            List<String> parentPath = List.of();
            int rootOrderedLayer = -1;

            if (!sectionUnproven) {
                try {
                    layer = cpLayers.advance(section, new Budget(Integer.MAX_VALUE));
                } catch (Unknown u) {
                    sectionUnproven = true;
                    broken = true;
                    open.clear();
                }
            }
            if (!sectionUnproven) {
                LayerFrame existing = null;
                for (LayerFrame candidate : open) {
                    if (candidate.layer == layer) { existing = candidate; break; }
                }
                if (existing != null) {
                    while (open.peek() != existing) open.pop();
                    if (!sectionIds.add(section.id())) {
                        sectionUnproven = true;
                    } else {
                        sectionUnproven = existing.unproven;
                    }
                } else {
                    boolean entered = section.parentRuleId() != null && entersInline(previousAccess, section.parentRuleId());
                    boolean seen = !layersSeen.add(layer);
                    boolean dupSection = !sectionIds.add(section.id());
                    if (seen || dupSection || (section.parentRuleId() != null && !entered)) {
                        sectionUnproven = true;
                        if (section.parentRuleId() != null) inlineLayerBroken.put(section.parentRuleId(), true);
                        open.clear();
                        broken = true;
                    } else {
                        if (!entered) {
                            open.clear();
                            LayerFrame frame = new LayerFrame(layer, null, List.of(), false);
                            open.push(frame);
                            orderedLayers.add(layer);
                        } else {
                            LayerFrame parentFrame = open.peek();
                            List<String> nextPath = new ArrayList<>(parentFrame == null ? List.of() : parentFrame.path);
                            nextPath.add(section.parentRuleId());
                            LayerFrame frame = new LayerFrame(layer, section.parentRuleId(), List.copyOf(nextPath),
                                    parentFrame != null && parentFrame.unproven);
                            open.push(frame);
                        }
                    }
                }
                if (!open.isEmpty() && !sectionUnproven) {
                    parentPath = open.peek().path;
                    rootOrderedLayer = open.descendingIterator().next().layer;
                }
            }

            previousAccess = section;
            for (Rule r : section.rules()) {
                boolean dupRule = !ruleIds.add(r.id());
                boolean ruleUnproven = sectionUnproven || dupRule;
                if (ruleUnproven && section.parentRuleId() != null) {
                    inlineLayerBroken.put(section.parentRuleId(), true);
                }
                out.add(new ScopedRule(section, r, false, false, ruleUnproven, rootOrderedLayer, parentPath));
                if (!ruleUnproven) {
                    if (section.parentRuleId() == null && rootOrderedLayer >= 0) {
                        orderedLayerRules.computeIfAbsent(rootOrderedLayer, k -> new ArrayList<>()).add(r);
                    } else if (section.parentRuleId() != null) {
                        inlineLayerRulesByParent.computeIfAbsent(section.parentRuleId(), k -> new ArrayList<>()).add(r);
                    }
                }
            }
        }
    }

    private static boolean entersInline(Section previous, String parentRuleId) {
        if (previous == null || !"CP access layer".equals(previous.source()) || previous.rules().isEmpty()) return false;
        Rule last = previous.rules().get(previous.rules().size() - 1);
        return parentRuleId.equals(last.id()) && "Apply Layer".equals(last.action())
                && last.extras().getOrDefault("inline-layer", List.of()).size() == 1;
    }

    private static void classifyPaloAltoSections(PolicySnapshot snapshot, List<ScopedRule> out) {
        EvaluationScope<Object> scope = new EvaluationScope<>(snapshot.metadata(), Object::new);
        Set<String> sectionIds = new HashSet<>();
        Set<String> ruleIds = new HashSet<>();
        boolean broken = false;

        for (Section section : snapshot.sections()) {
            if (PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), section)) {
                boolean defaultSection = PAN_DEFAULT_SECTIONS.contains(section.name());
                for (Rule r : section.rules()) {
                    out.add(new ScopedRule(section, r, true, defaultSection, false, -1, List.of()));
                }
                continue;
            }
            if (section.rules().isEmpty()) continue;
            boolean sectionUnproven = broken;
            if (!sectionUnproven) {
                boolean localSection = PAN_LOCAL_RULES.equals(section.name()) && section.parentRuleId() == null;
                if (!localSection && scope.panPhase(section) < 0) {
                    sectionUnproven = true;
                    broken = true;
                } else if (localSection) {
                    if (!sectionIds.add(section.id())) {
                        sectionUnproven = true;
                        broken = true;
                    }
                } else {
                    scope.advance(section);
                    if (scope.unproven) {
                        sectionUnproven = true;
                        broken = true;
                    }
                }
            }
            for (Rule r : section.rules()) {
                boolean dupRule = !ruleIds.add(r.id());
                out.add(new ScopedRule(section, r, false, false, sectionUnproven || dupRule, -1, List.of()));
            }
        }
    }

    private static RuleUpperBound evaluatePanDefaultRule(PolicySnapshot snapshot, Rule rule) {
        List<String> targets = resolveTargets(snapshot, rule, false);
        if (Boolean.FALSE.equals(rule.enabled())) {
            return new RuleUpperBound("NOT_APPLICABLE", "disabled", null, null, null,
                    null, null, null, Set.of(), targets, Set.of());
        }
        if (rule.enabled() == null) {
            return new RuleUpperBound("NOT_EVALUABLE", null, null, null, "enabled state is unknown",
                    null, null, null, Set.of("DEFAULT_RULE"), targets, Set.of());
        }
        if (DENY_ACTIONS.contains(rule.action())) {
            return new RuleUpperBound("NOT_APPLICABLE", "deny", null, null, null,
                    null, null, null, Set.of(), targets, Set.of());
        }
        if ("allow".equals(rule.action())) {
            AddressBound anyAddr = new AddressBound(
                    List.of(new IpRange(0, IPV4_MAX_LONG)),
                    List.of(new IpRange(0, IPV4_MAX_LONG)),
                    List.of(),
                    List.of(),
                    true,
                    false,
                    false,
                    null);
            ServiceBound anySvc = new ServiceBound(
                    true,
                    true,
                    false,
                    false,
                    allPorts(),
                    allPorts(),
                    null);
            return new RuleUpperBound("EVALUATE", null, null, null, null,
                    anyAddr, anyAddr, anySvc, Set.of("DEFAULT_RULE"), targets, Set.of());
        }
        return new RuleUpperBound("NOT_EVALUABLE", null, null, null, "action semantics not modelled",
                null, null, null, Set.of("DEFAULT_RULE"), targets, Set.of());
    }

    private static RuleUpperBound computeUpperBound(PolicySnapshot snapshot, Rule rule, Instant now) {
        Budget budget = new Budget(RULE_WORK_BUDGET);
        String vendor = snapshot.metadata().vendor();
        boolean cp = "CP".equals(vendor);
        try {
            Set<String> flags = new LinkedHashSet<>();
            InstallOnResolution installOn = cp ? resolveCpInstallOn(snapshot, rule, budget)
                    : new InstallOnResolution(resolveTargets(snapshot, rule, false), false, Set.of());
            if (installOn.unresolved()) flags.add("INSTALL_ON_UNRESOLVED");

            if (Boolean.FALSE.equals(rule.enabled())) {
                return new RuleUpperBound("NOT_APPLICABLE", "disabled", null, null, null,
                        null, null, null, Set.of(), installOn.targetDeviceIds(), installOn.rawRefs());
            }
            if (rule.enabled() == null) {
                return new RuleUpperBound("NOT_EVALUABLE", null, null, null, "enabled state is unknown",
                        null, null, null, Set.copyOf(flags), installOn.targetDeviceIds(), installOn.rawRefs());
            }
            if (DENY_ACTIONS.contains(rule.action())) {
                return new RuleUpperBound("NOT_APPLICABLE", "deny", null, null, null,
                        null, null, null, Set.of(), installOn.targetDeviceIds(), installOn.rawRefs());
            }

            // Schedule / time check (§5.3, §5.4, ZM-24): ended one-time schedule -> NOT_APPLICABLE "schedule ended <date>".
            ScheduleCheck sched = checkSchedule(snapshot, rule, vendor, now, budget);
            if (sched.endedDate() != null) {
                return new RuleUpperBound("NOT_APPLICABLE", "schedule_ended",
                        "schedule ended " + sched.endedDate(), null, null,
                        null, null, null, Set.of(), installOn.targetDeviceIds(), installOn.rawRefs());
            }
            if (sched.timeConstraintPresent()) {
                flags.add("TIME_IGNORED");
            }

            // Action classification (§5.2, ZM-17).
            String kind;
            if (cp && "Apply Layer".equals(rule.action())) {
                kind = "APPLY_LAYER";
            } else if (cp && ASK_INFORM_ACTIONS.contains(rule.action())) {
                kind = "ASK_INFORM";
            } else if (ALLOW_ACTIONS.contains(rule.action())) {
                kind = "EVALUATE";
            } else {
                return new RuleUpperBound("NOT_EVALUABLE", null, null, null, "action semantics not modelled",
                        null, null, null, Set.copyOf(flags), installOn.targetDeviceIds(), installOn.rawRefs());
            }

            // Narrowing extras (§5.3, §5.4).
            String unknownExtra = inspectExtrasAndFlags(snapshot, rule, vendor, flags, budget);
            if (unknownExtra != null) {
                return new RuleUpperBound("NOT_EVALUABLE", null, null, null, unknownExtra,
                        null, null, null, Set.copyOf(flags), installOn.targetDeviceIds(), installOn.rawRefs());
            }

            AddressBound source = resolveAddressCell(snapshot, rule.source(), cp, budget);
            AddressBound destination = resolveAddressCell(snapshot, rule.destination(), false, budget);
            ServiceBound service = resolveServiceAndApplication(snapshot, rule.service(), rule.application(), vendor, budget);

            return new RuleUpperBound(kind, null, null, null, null,
                    source, destination, service, Set.copyOf(flags), installOn.targetDeviceIds(), installOn.rawRefs());
        } catch (Unknown exhausted) {
            if ("analysis budget".equals(exhausted.getMessage())) {
                return new RuleUpperBound("UNKNOWN", null, null, "analysis budget", null,
                        null, null, null, Set.of(), resolveTargets(snapshot, rule, false), Set.of());
            }
            return new RuleUpperBound("NOT_EVALUABLE", null, null, null, mapReason(exhausted.getMessage()),
                    null, null, null, Set.of(), resolveTargets(snapshot, rule, false), Set.of());
        }
    }

    private record InstallOnResolution(List<String> targetDeviceIds, boolean unresolved, Set<String> rawRefs) {}

    private static InstallOnResolution resolveCpInstallOn(PolicySnapshot snapshot, Rule rule, Budget budget) {
        List<String> allTargets = snapshot.metadata().targets().stream().map(Target::deviceId).distinct().toList();
        List<String> refs = rule.extras().getOrDefault("install-on", List.of());
        if (refs.isEmpty()) {
            return new InstallOnResolution(allTargets, !allTargets.isEmpty(), Set.of());
        }
        Set<String> enrolledIds = new LinkedHashSet<>(allTargets);
        List<String> narrowed = new ArrayList<>();
        boolean universal = false;
        boolean unresolved = false;
        for (String id : refs) {
            budget.spend();
            PolicyObject obj = snapshot.objects().get(id);
            if (obj != null && ("any".equals(obj.type()) && "RESOLVED".equals(obj.status())
                    || HopPolicyEvaluator.policyTargets(obj))) {
                universal = true;
            } else if (enrolledIds.contains(id)) {
                if (!narrowed.contains(id)) narrowed.add(id);
            } else {
                unresolved = true;
            }
        }
        if (universal) {
            return new InstallOnResolution(allTargets, false, Set.copyOf(refs));
        }
        if (!unresolved && !narrowed.isEmpty()) {
            return new InstallOnResolution(List.copyOf(narrowed), false, Set.copyOf(refs));
        }
        return new InstallOnResolution(allTargets, true, Set.copyOf(refs));
    }

    private static List<String> resolveTargets(PolicySnapshot snapshot, Rule rule, boolean ignored) {
        return snapshot.metadata().targets().stream().map(Target::deviceId).distinct().toList();
    }

    private record ScheduleCheck(boolean timeConstraintPresent, String endedDate) {}

    private static ScheduleCheck checkSchedule(PolicySnapshot snapshot, Rule rule, String vendor, Instant now, Budget budget) {
        String key = "CP".equals(vendor) ? "time" : "schedule";
        List<String> refs = rule.extras().getOrDefault(key, List.of());
        if (refs.isEmpty()) return new ScheduleCheck(false, null);

        boolean anyNonAny = false;
        boolean allEndedOneTime = true;
        LocalDate latestEndDate = null;

        for (String refId : refs) {
            budget.spend();
            if ("any".equals(refId)) {
                allEndedOneTime = false;
                continue;
            }
            PolicyObject obj = snapshot.objects().get(refId);
            if (obj != null && "RESOLVED".equals(obj.status()) && "any".equals(obj.type())) {
                allEndedOneTime = false;
                continue;
            }
            anyNonAny = true;
            if (obj == null || !"RESOLVED".equals(obj.status()) || obj.schedule() == null
                    || !"one-time".equals(obj.schedule().kind()) || obj.schedule().end() == null) {
                allEndedOneTime = false;
                continue;
            }
            PolicySchedule sched = obj.schedule();
            Instant endInstant = parseScheduleEnd(sched.end(), sched.timezone());
            if (endInstant == null) {
                allEndedOneTime = false;
                continue;
            }
            Instant cutoff = sched.timezoneKnown() ? now : now.minus(SCHEDULE_TIMEZONE_MARGIN);
            if (!endInstant.isBefore(cutoff)) {
                allEndedOneTime = false;
            } else {
                LocalDate endDate = extractScheduleDate(sched.end(), sched.timezone());
                if (endDate != null && (latestEndDate == null || endDate.isAfter(latestEndDate))) {
                    latestEndDate = endDate;
                }
            }
        }
        if (anyNonAny && allEndedOneTime && latestEndDate != null) {
            return new ScheduleCheck(true, latestEndDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
        }
        return new ScheduleCheck(anyNonAny, null);
    }

    private static Instant parseScheduleEnd(String endText, String timezone) {
        try {
            ZoneId zone = timezone == null || timezone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timezone);
            try { return OffsetDateTime.parse(endText).toInstant(); }
            catch (RuntimeException ignored) { return LocalDateTime.parse(endText).atZone(zone).toInstant(); }
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static LocalDate extractScheduleDate(String endText, String timezone) {
        try {
            ZoneId zone = timezone == null || timezone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timezone);
            try { return OffsetDateTime.parse(endText).atZoneSameInstant(zone).toLocalDate(); }
            catch (RuntimeException ignored) { return LocalDateTime.parse(endText).toLocalDate(); }
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String inspectExtrasAndFlags(
            PolicySnapshot snapshot, Rule rule, String vendor, Set<String> flags, Budget budget) {
        for (var entry : rule.extras().entrySet()) {
            budget.spend();
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (Set.of("from", "to", "install-on", "time", "schedule", "inline-layer",
                    "tag", "layer-name", "last-modified", "last-modifier", "log-setting", "time-uncollected").contains(key)
                    || key.startsWith("track-") || key.startsWith("profile-setting/")) {
                continue;
            }
            if ("source-user".equals(key)) {
                if (!values.isEmpty() && !values.equals(List.of("any"))) flags.add("IDENTITY_IGNORED");
                continue;
            }
            if ("category".equals(key)) {
                if (!values.isEmpty() && !values.equals(List.of("any"))) flags.add("CONDITION_IGNORED");
                continue;
            }
            if (Set.of("vpn", "content").contains(key)) {
                for (String id : values) {
                    budget.spend();
                    PolicyObject o = snapshot.objects().get(id);
                    if (o == null || !"RESOLVED".equals(o.status()) || !"any".equals(o.type())) {
                        flags.add("CONDITION_IGNORED");
                    }
                }
                continue;
            }
            if ("rule-type".equals(key)) {
                // Palo Alto universal / intrazone / interzone only narrows placement by firewall zone (§5.2).
                if (Set.of(List.of("universal"), List.of("intrazone"), List.of("interzone")).contains(values)) continue;
                return "dynamic or unsupported object type";
            }
            if ("PAN".equals(vendor) && (PolicyNormalization.untargeted(snapshot, rule, key)
                    || PolicyNormalization.targetNarrows(snapshot, rule, key))) {
                continue;
            }
            return "dynamic or unsupported object type";
        }
        return null;
    }

    // ---- Address cell resolution (§5.3, §5.4, §5.7, ZM-15, ZM-19, ZM-27) ----------------------------------------------

    static AddressBound resolveAddressCell(
            PolicySnapshot snapshot, Cell cell, boolean allowAccessRole, Budget budget) {
        if (cell.negated()) {
            return new AddressBound(List.of(), List.of(), List.of(), List.of(), false, false, false, "negated selector");
        }
        if (cell.refs().isEmpty()) {
            return new AddressBound(List.of(), List.of(), List.of(), List.of(), false, false, false, "unresolved object");
        }

        List<UlaRange> ulaRanges = new ArrayList<>();
        collectUlaRanges(snapshot.objects(), cell.refs(), new HashSet<>(), ulaRanges, budget);

        List<IpRange> upper = new ArrayList<>();
        List<IpRange> lower = new ArrayList<>();
        List<IpRange> explicit = new ArrayList<>();
        boolean hasIpv6 = false;
        boolean accessRole = false;
        boolean dynamicAddr = false;
        String hardProblem = null;

        for (String id : cell.refs()) {
            budget.spend();
            AddressPart part = resolveAddressObject(snapshot.objects(), id, allowAccessRole, new HashSet<>(), budget);
            if (part.problem != null) {
                if (hardProblem == null || "IPv6 not evaluated".equals(part.problem)) {
                    hardProblem = part.problem;
                }
                continue;
            }
            upper.addAll(part.upper);
            lower.addAll(part.lower);
            explicit.addAll(part.explicit);
            hasIpv6 |= part.hasIpv6;
            accessRole |= part.accessRole;
            dynamicAddr |= part.dynamicAddress;
        }

        if (hardProblem != null) {
            return new AddressBound(List.of(), List.of(), mergeIpRanges(explicit, budget), List.copyOf(ulaRanges),
                    "IPv6 not evaluated".equals(hardProblem), false, false, hardProblem);
        }

        String candidateReason = accessRole ? "depends on user identity"
                : dynamicAddr ? "dynamic or unsupported object type"
                : null;
        return new AddressBound(
                mergeIpRanges(upper, budget),
                mergeIpRanges(lower, budget),
                mergeIpRanges(explicit, budget),
                List.copyOf(ulaRanges),
                hasIpv6,
                accessRole,
                dynamicAddr,
                candidateReason);
    }

    private record AddressPart(
            List<IpRange> upper,
            List<IpRange> lower,
            List<IpRange> explicit,
            boolean hasIpv6,
            boolean accessRole,
            boolean dynamicAddress,
            String problem) {
        static AddressPart fail(String problem) {
            return new AddressPart(List.of(), List.of(), List.of(), false, false, false, problem);
        }
    }

    private static AddressPart resolveAddressObject(
            Map<String, PolicyObject> objects,
            String id,
            boolean allowAccessRole,
            Set<String> path,
            Budget budget) {
        budget.spend();
        PolicyObject o = objects.get(id);
        if (o == null) return AddressPart.fail("unresolved object");
        if ("CYCLE".equals(o.status()) || "LIMIT".equals(o.status()) || !path.add(id) || path.size() > 32) {
            return AddressPart.fail("group cycle or depth limit");
        }
        try {
            if ("UNRESOLVED".equals(o.status()) || "UNKNOWN".equals(o.status())) {
                return AddressPart.fail("unresolved object");
            }
            if (allowAccessRole && "access-role".equals(o.type()) && "DYNAMIC".equals(o.status())) {
                List<IpRange> roleNets = resolveAccessRoleNetworks(objects, o, budget);
                return new AddressPart(
                        List.of(new IpRange(0, IPV4_MAX_LONG)),
                        roleNets,
                        roleNets,
                        true,
                        true,
                        false,
                        null);
            }
            if (isDynamicAddressObject(o)) {
                return new AddressPart(
                        List.of(new IpRange(0, IPV4_MAX_LONG)),
                        List.of(),
                        List.of(),
                        true,
                        false,
                        true,
                        null);
            }
            if (!"RESOLVED".equals(o.status())) {
                return AddressPart.fail("dynamic or unsupported object type");
            }
            if ("any".equals(o.type())) {
                return new AddressPart(
                        List.of(new IpRange(0, IPV4_MAX_LONG)),
                        List.of(new IpRange(0, IPV4_MAX_LONG)),
                        List.of(),
                        true,
                        false,
                        false,
                        null);
            }
            if (Set.of("group", "address-group").contains(o.type())) {
                List<String> nonColorValues = stripCosmeticValues(o.values());
                if (!nonColorValues.isEmpty()) {
                    // Palo Alto dynamic address group (match filter stored in values).
                    return new AddressPart(
                            List.of(new IpRange(0, IPV4_MAX_LONG)),
                            List.of(),
                            List.of(),
                            true,
                            false,
                            true,
                            null);
                }
                if (o.members().isEmpty()) {
                    return AddressPart.fail("empty or dynamic group");
                }
                List<IpRange> upper = new ArrayList<>(), lower = new ArrayList<>(), explicit = new ArrayList<>();
                boolean ipv6 = false, role = false, dyn = false;
                String groupProblem = null;
                for (String member : o.members()) {
                    AddressPart child = resolveAddressObject(objects, member, allowAccessRole, path, budget);
                    if (child.problem != null) {
                        if (groupProblem == null || "IPv6 not evaluated".equals(child.problem)) {
                            groupProblem = child.problem;
                        }
                    } else {
                        upper.addAll(child.upper);
                        lower.addAll(child.lower);
                        explicit.addAll(child.explicit);
                        ipv6 |= child.hasIpv6;
                        role |= child.accessRole;
                        dyn |= child.dynamicAddress;
                    }
                }
                if (groupProblem != null) return AddressPart.fail(groupProblem);
                return new AddressPart(upper, lower, explicit, ipv6, role, dyn, null);
            }
            if (Set.of("address", "host", "network", "address-range").contains(o.type())) {
                if (hasFqdnValue(o)) {
                    return new AddressPart(
                            List.of(new IpRange(0, IPV4_MAX_LONG)),
                            List.of(),
                            List.of(),
                            true,
                            false,
                            true,
                            null);
                }
                if (hasIpv6Value(o)) {
                    // Step 3 (§5.7 step 2, ZM-19): any IPv6 value in an address object yields "IPv6 not evaluated";
                    // per-family resolution for mixed objects is delivery step 7 (§10.1 item 7).
                    return AddressPart.fail("IPv6 not evaluated");
                }
                PolicyObject cleaned = stripCosmeticObject(o);
                Resolver plain = new Resolver(Map.of(o.id(), cleaned), budget);
                Selector sel = plain.address(cleaned);
                List<IpRange> ranges = toIpRanges(sel.ranges().getOrDefault("ipv4", List.of()));
                return new AddressPart(ranges, ranges, ranges, false, false, false, null);
            }
            return AddressPart.fail("dynamic or unsupported object type");
        } catch (Unknown u) {
            if ("analysis budget".equals(u.getMessage())) throw u;
            return AddressPart.fail(hasIpv6Value(o) ? "IPv6 not evaluated" : mapReason(u.getMessage()));
        } finally {
            path.remove(id);
        }
    }

    private static List<IpRange> resolveAccessRoleNetworks(
            Map<String, PolicyObject> objects, PolicyObject roleObj, Budget budget) {
        if (!roleObj.members().isEmpty() || roleObj.values().isEmpty()) return List.of();
        List<IpRange> ranges = new ArrayList<>();
        for (String value : roleObj.values()) {
            budget.spend();
            if (!value.startsWith("networks: ")) return List.of();
            String target = value.substring("networks: ".length()).trim();
            if ("any".equals(target)) {
                ranges.add(new IpRange(0, IPV4_MAX_LONG));
                continue;
            }
            AddressPart net = resolveAddressObject(objects, target, false, new HashSet<>(), budget);
            if (net.problem != null || net.dynamicAddress || net.accessRole) return List.of();
            ranges.addAll(net.lower);
        }
        return mergeIpRanges(ranges, budget);
    }

    private static boolean isDynamicAddressObject(PolicyObject o) {
        if ("DYNAMIC".equals(o.status())) {
            return Set.of("dynamic-object", "updatable-object", "dns-domain", "security-zone",
                    "address-group", "external-list", "region").contains(o.type());
        }
        return Set.of("dynamic-object", "updatable-object", "dns-domain", "security-zone", "external-list").contains(o.type());
    }

    private static boolean hasFqdnValue(PolicyObject o) {
        for (String v : o.values()) {
            if (v.startsWith("fqdn: ")) return true;
        }
        return false;
    }

    private static boolean hasIpv6Value(PolicyObject o) {
        if (o == null) return false;
        for (String v : o.values()) {
            if (v.startsWith("color: ")) continue;
            if (v.startsWith("ipv6-address: ") || v.startsWith("subnet6: ") || v.startsWith("mask-length6: ")
                    || v.startsWith("ipv6-address-first: ") || v.startsWith("ipv6-address-last: ")) {
                return true;
            }
            int split = v.indexOf(": ");
            String key = split < 0 ? "literal" : v.substring(0, split);
            String val = split < 0 ? v : v.substring(split + 2);
            if (Set.of("ip-netmask", "ip-range", "literal", "ip-address-first", "ip-address-last").contains(key)
                    && val.indexOf(':') >= 0) {
                return true;
            }
        }
        return false;
    }

    private static void collectUlaRanges(
            Map<String, PolicyObject> objects,
            List<String> refs,
            Set<String> visited,
            List<UlaRange> out,
            Budget budget) {
        for (String id : refs) {
            budget.spend();
            if (!visited.add(id) || visited.size() > 512) continue;
            PolicyObject o = objects.get(id);
            if (o == null) continue;
            extractObjectUla(o, out);
            if (!o.members().isEmpty()) {
                collectUlaRanges(objects, o.members(), visited, out, budget);
            }
        }
    }

    private static void extractObjectUla(PolicyObject o, List<UlaRange> out) {
        String subnet6 = null, mask6 = null;
        for (String v : o.values()) {
            if (v.startsWith("subnet6: ")) subnet6 = v.substring("subnet6: ".length()).trim();
            else if (v.startsWith("mask-length6: ")) mask6 = v.substring("mask-length6: ".length()).trim();
            else if (v.startsWith("ipv6-address: ")) addUlaCidrOrAddr(v.substring("ipv6-address: ".length()).trim(), out);
            else if (v.startsWith("ip-netmask: ")) {
                String val = v.substring("ip-netmask: ".length()).trim();
                if (val.indexOf(':') >= 0) addUlaCidrOrAddr(val, out);
            } else if (v.indexOf(": ") < 0 && v.indexOf(':') >= 0) {
                addUlaCidrOrAddr(v.trim(), out);
            }
        }
        if (subnet6 != null && mask6 != null) {
            addUlaCidrOrAddr(subnet6 + "/" + mask6, out);
        }
    }

    private static void addUlaCidrOrAddr(String text, List<UlaRange> out) {
        try {
            int slash = text.indexOf('/');
            String addr = slash < 0 ? text : text.substring(0, slash);
            int prefix = slash < 0 ? 128 : Integer.parseInt(text.substring(slash + 1));
            if (prefix < 0 || prefix > 128) return;
            BigInteger ip = parseIpv6BigInt(addr);
            if (ip == null) return;
            BigInteger mask = prefix == 128 ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(128 - prefix).subtract(BigInteger.ONE);
            BigInteger start = ip.andNot(mask);
            BigInteger end = start.or(mask);
            BigInteger iStart = start.max(ULA_START);
            BigInteger iEnd = end.min(ULA_END);
            if (iStart.compareTo(iEnd) <= 0) {
                out.add(new UlaRange(iStart, iEnd));
            }
        } catch (RuntimeException ignored) {
            // Non-canonical IPv6 representations stay governed by normal cell resolution.
        }
    }

    private static BigInteger parseIpv6BigInt(String text) {
        try {
            java.net.InetAddress inet = java.net.InetAddress.getByName(text);
            byte[] bytes = inet.getAddress();
            if (bytes.length != 16) return null;
            return new BigInteger(1, bytes);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Service & application resolution (§4.2, §4.3, §5.4, ZM-5) ----------------------------------------------------

    static ServiceBound resolveServiceAndApplication(
            PolicySnapshot snapshot, Cell serviceCell, Cell appCell, String vendor, Budget budget) {
        if (serviceCell.negated() || appCell.negated()) {
            return new ServiceBound(false, false, false, false, Map.of(), Map.of(), "negated selector");
        }
        if (serviceCell.refs().isEmpty()) {
            return new ServiceBound(false, false, false, false, Map.of(), Map.of(), "unresolved object");
        }

        // Palo Alto built-in application-default service.
        if ("PAN".equals(vendor) && isPanApplicationDefault(snapshot, serviceCell)) {
            budget.spend();
            return new ServiceBound(false, false, true, false, Map.of(), Map.of(), null);
        }

        // Check Point application column (§4.3): application-site / application-group narrows rule match;
        // upper-bound port set equals the service column.
        boolean cpAppInAppColumn = false;
        if ("CP".equals(vendor) && !appCell.refs().isEmpty()) {
            String appCheck = inspectCpApplicationColumn(snapshot.objects(), appCell.refs(), new HashSet<>(), budget);
            if ("CP_APP".equals(appCheck)) {
                cpAppInAppColumn = true;
            } else if (appCheck != null) {
                return new ServiceBound(false, false, false, false, Map.of(), Map.of(), appCheck);
            }
        }

        boolean upperAny = false;
        boolean lowerAny = false;
        boolean cpAppInServiceColumn = false;
        Map<String, List<PortRange>> upperMap = new TreeMap<>();
        Map<String, List<PortRange>> lowerMap = new TreeMap<>();

        for (String id : serviceCell.refs()) {
            budget.spend();
            ServicePart part = resolveServiceObject(snapshot.objects(), id, "CP".equals(vendor), new HashSet<>(), budget);
            if (part.problem != null) {
                return new ServiceBound(false, false, false, false, Map.of(), Map.of(), part.problem);
            }
            upperAny |= part.upperAny;
            lowerAny |= part.lowerAny;
            cpAppInServiceColumn |= part.cpApp;
            mergePortMaps(upperMap, part.upperPorts);
            mergePortMaps(lowerMap, part.lowerPorts);
        }

        boolean cpApp = cpAppInAppColumn || cpAppInServiceColumn;
        Map<String, List<PortRange>> normUpper = upperAny ? allPorts() : normalizePortMap(upperMap, budget);
        Map<String, List<PortRange>> normLower = cpAppInAppColumn
                ? Map.of()
                : (lowerAny ? allPorts() : normalizePortMap(lowerMap, budget));
        boolean effectiveLowerAny = !cpAppInAppColumn && lowerAny;

        return new ServiceBound(upperAny, effectiveLowerAny, false, cpApp, normUpper, normLower, null);
    }

    private static String inspectCpApplicationColumn(
            Map<String, PolicyObject> objects, List<String> refs, Set<String> path, Budget budget) {
        boolean hasAppConstraint = false;
        for (String id : refs) {
            budget.spend();
            if (!path.add(id) || path.size() > 32) return "group cycle or depth limit";
            try {
                PolicyObject o = objects.get(id);
                if (o == null || "UNRESOLVED".equals(o.status()) || "UNKNOWN".equals(o.status())) return "unresolved object";
                if ("CYCLE".equals(o.status()) || "LIMIT".equals(o.status())) return "group cycle or depth limit";
                if ("any".equals(o.type()) && "RESOLVED".equals(o.status())) continue;
                if (isCpApplicationObject(o)) {
                    if ("application-group".equals(o.type()) && o.members().isEmpty() && "RESOLVED".equals(o.status())) {
                        return "empty or dynamic group";
                    }
                    hasAppConstraint = true;
                } else {
                    return "application constraint";
                }
            } finally {
                path.remove(id);
            }
        }
        return hasAppConstraint ? "CP_APP" : null;
    }

    private record ServicePart(
            boolean upperAny,
            boolean lowerAny,
            boolean cpApp,
            Map<String, List<PortRange>> upperPorts,
            Map<String, List<PortRange>> lowerPorts,
            String problem) {
        static ServicePart fail(String problem) {
            return new ServicePart(false, false, false, Map.of(), Map.of(), problem);
        }
    }

    private static ServicePart resolveServiceObject(
            Map<String, PolicyObject> objects, String id, boolean cp, Set<String> path, Budget budget) {
        budget.spend();
        PolicyObject o = objects.get(id);
        if (o == null) return ServicePart.fail("unresolved object");
        if ("CYCLE".equals(o.status()) || "LIMIT".equals(o.status()) || !path.add(id) || path.size() > 32) {
            return ServicePart.fail("group cycle or depth limit");
        }
        try {
            if ("UNRESOLVED".equals(o.status()) || "UNKNOWN".equals(o.status())) {
                return ServicePart.fail("unresolved object");
            }
            if (cp && isCpApplicationObject(o)) {
                if ("application-group".equals(o.type()) && o.members().isEmpty() && "RESOLVED".equals(o.status())) {
                    return ServicePart.fail("empty or dynamic group");
                }
                // §4.3: Check Point application-site / application-group in the service column has port upper bound = any.
                return new ServicePart(true, false, true, allPorts(), Map.of(), null);
            }
            if (!"RESOLVED".equals(o.status())) {
                return ServicePart.fail("unsupported service constraint");
            }
            if ("any".equals(o.type())) {
                return new ServicePart(true, true, false, allPorts(), allPorts(), null);
            }
            if ("service-group".equals(o.type()) || (cp && "group".equals(o.type()))) {
                List<String> nonColor = stripCosmeticValues(o.values());
                if (o.members().isEmpty() || !nonColor.isEmpty()) {
                    return ServicePart.fail("empty or dynamic group");
                }
                boolean uAny = false, lAny = false, cpApp = false;
                Map<String, List<PortRange>> uMap = new TreeMap<>(), lMap = new TreeMap<>();
                for (String member : o.members()) {
                    ServicePart child = resolveServiceObject(objects, member, cp, path, budget);
                    if (child.problem != null) return child;
                    uAny |= child.upperAny;
                    lAny |= child.lowerAny;
                    cpApp |= child.cpApp;
                    mergePortMaps(uMap, child.upperPorts);
                    mergePortMaps(lMap, child.lowerPorts);
                }
                return new ServicePart(uAny, lAny, cpApp, uMap, lMap, null);
            }
            if (Set.of("service", "service-tcp", "service-udp").contains(o.type())) {
                PolicyObject cleaned = stripCosmeticObject(o);
                Resolver plain = new Resolver(Map.of(o.id(), cleaned), budget);
                Selector sel = plain.service(cleaned);
                Map<String, List<PortRange>> ports = new TreeMap<>();
                for (var entry : sel.ranges().entrySet()) {
                    if (!Set.of("tcp", "udp").contains(entry.getKey())) {
                        return ServicePart.fail("unsupported service constraint");
                    }
                    ports.put(entry.getKey(), toPortRanges(entry.getValue()));
                }
                return new ServicePart(false, false, false, ports, ports, null);
            }
            if (o.type().startsWith("service-")) {
                return ServicePart.fail("unsupported service constraint");
            }
            return ServicePart.fail("dynamic or unsupported object type");
        } catch (Unknown u) {
            if ("analysis budget".equals(u.getMessage())) throw u;
            return ServicePart.fail(mapServiceReason(u.getMessage()));
        } finally {
            path.remove(id);
        }
    }

    private static boolean isCpApplicationObject(PolicyObject o) {
        if (Set.of("application", "application-site", "application-group").contains(o.type())) return true;
        for (String v : o.values()) {
            if (v.startsWith("native-type: application-site")) return true;
        }
        return false;
    }

    private static boolean isPanApplicationDefault(PolicySnapshot snapshot, Cell serviceCell) {
        if (serviceCell.negated() || serviceCell.refs().size() != 1) return false;
        String id = serviceCell.refs().get(0);
        PolicyObject o = snapshot.objects().get(id);
        if (o == null || !"RESOLVED".equals(o.status()) || !"service".equals(o.type())
                || !o.values().isEmpty() || !o.members().isEmpty()) {
            return false;
        }
        String metaId = snapshot.metadata().id();
        return metaId != null && !metaId.isBlank() && id.equals(ref(metaId, "service", "application-default"));
    }

    // ---- Explicit universal catch-all check (§5.2, ZM-17) --------------------------------------------------------------

    private static boolean endsWithUniversalCatchAll(PolicySnapshot snapshot, List<Rule> rules, Rule inlineParent) {
        if (rules == null || rules.isEmpty()) return false;
        for (Rule r : rules) {
            if (!Boolean.TRUE.equals(r.enabled())) continue;
            if (!CATCH_ALL_TERMINAL_ACTIONS.contains(r.action())) continue;
            if (isUniversalCatchAllMatch(snapshot, r, inlineParent)) return true;
        }
        return false;
    }

    private static boolean isUniversalCatchAllMatch(PolicySnapshot snapshot, Rule r, Rule inlineParent) {
        if (r.source().negated() || r.destination().negated() || r.service().negated() || r.application().negated()) {
            return false;
        }
        if (!cellIsAny(snapshot.objects(), r.source()) || !cellIsAny(snapshot.objects(), r.destination())
                || !cellIsAny(snapshot.objects(), r.service())) {
            return false;
        }
        if (!r.application().refs().isEmpty() && !cellIsAny(snapshot.objects(), r.application())) {
            return false;
        }
        for (String key : List.of("time", "vpn", "content")) {
            for (String id : r.extras().getOrDefault(key, List.of())) {
                if ("any".equals(id)) continue;
                PolicyObject o = snapshot.objects().get(id);
                if (o == null || !"RESOLVED".equals(o.status()) || !"any".equals(o.type())) return false;
            }
        }
        List<String> installOn = r.extras().getOrDefault("install-on", List.of());
        if (installOn.isEmpty()) return false;
        boolean universalInstall = installOn.stream().anyMatch(id -> {
            PolicyObject o = snapshot.objects().get(id);
            return o != null && ("any".equals(o.type()) && "RESOLVED".equals(o.status())
                    || HopPolicyEvaluator.policyTargets(o));
        });
        if (!universalInstall) {
            if (inlineParent == null) return false;
            Set<String> parentInstall = Set.copyOf(inlineParent.extras().getOrDefault("install-on", List.of()));
            if (parentInstall.isEmpty() || !Set.copyOf(installOn).containsAll(parentInstall)) return false;
        }
        return true;
    }

    private static boolean cellIsAny(Map<String, PolicyObject> objects, Cell cell) {
        if (cell.negated() || cell.refs().isEmpty()) return false;
        for (String id : cell.refs()) {
            PolicyObject o = objects.get(id);
            if (o != null && "RESOLVED".equals(o.status()) && "any".equals(o.type())) return true;
        }
        return false;
    }

    // ---- NAT dual-view extraction moved to ZoneMatrixNatBounds (§5.6, ZM-14) --------------------------------------------

    // ---- Check Point multiple ordered-layers witness check (§5.5, ZM-16) -----------------------------------------------

    public static OtherLayersVerdict checkOtherOrderedLayers(
            PreparedPolicy prepared,
            int violatingLayer,
            List<String> targetDeviceIds,
            Set<String> rawInstallOnRefs,
            boolean installOnUnresolved,
            long witnessSrcIp,
            long witnessDstIp,
            String witnessService) {
        if (prepared.orderedLayerLookup() == null || violatingLayer < 0) {
            return OtherLayersVerdict.ALL_ALLOWED;
        }
        return prepared.orderedLayerLookup().check(
                violatingLayer, targetDeviceIds, rawInstallOnRefs, installOnUnresolved,
                witnessSrcIp, witnessDstIp, witnessService);
    }

    /** Bounded per-layer tuple evaluator aligned with {@link PolicyLookup} and {@link HopPolicyEvaluator#policyTargets}. */
    public static final class OrderedLayerLookup {
        private record LayerEntry(Section section, Rule rule, Match match, int layer, boolean universalInstallOn, Set<String> installRefs) {}
        private final PolicySnapshot snapshot;
        private final List<Integer> layers;
        private final List<LayerEntry> entries;
        private final boolean buildFailed;

        private OrderedLayerLookup(PolicySnapshot snapshot, List<Integer> layers, List<LayerEntry> entries, boolean buildFailed) {
            this.snapshot = snapshot;
            this.layers = layers;
            this.entries = entries;
            this.buildFailed = buildFailed;
        }

        static OrderedLayerLookup build(PolicySnapshot snapshot, Set<Integer> orderedLayers) {
            Budget budget = new Budget(PolicyHygiene.RULE_WORK * 4);
            try {
                Resolver resolver = new Resolver(snapshot.objects(), budget);
                CpLayers cpLayers = new CpLayers();
                List<LayerEntry> entries = new ArrayList<>();
                Set<String> inlineParents = new HashSet<>();
                for (Section section : snapshot.sections()) {
                    if (PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), section)) continue;
                    if (!"CP access layer".equals(section.source())) return new OrderedLayerLookup(snapshot, List.of(), List.of(), true);
                    if (section.rules().isEmpty()) continue;
                    if (section.parentRuleId() != null && !inlineParents.contains(section.parentRuleId())) {
                        return new OrderedLayerLookup(snapshot, List.of(), List.of(), true);
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
                return new OrderedLayerLookup(snapshot, List.copyOf(orderedLayers), List.copyOf(entries), false);
            } catch (Unknown u) {
                return new OrderedLayerLookup(snapshot, List.copyOf(orderedLayers), List.of(), true);
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

    // ---- Interval & object helpers -------------------------------------------------------------------------------------

    private static PolicyObject stripCosmeticObject(PolicyObject o) {
        List<String> cleaned = stripCosmeticValues(o.values());
        return cleaned.size() == o.values().size()
                ? o : new PolicyObject(o.id(), o.name(), o.type(), o.members(), cleaned, o.status(), o.schedule());
    }

    private static List<String> stripCosmeticValues(List<String> values) {
        List<String> out = new ArrayList<>(values.size());
        for (String v : values) {
            if (v.startsWith("color: ")) continue;
            out.add(v);
        }
        return out;
    }

    private static String mapReason(String raw) {
        if (raw == null) return "dynamic or unsupported object type";
        return switch (raw) {
            case "negated selector" -> "negated selector";
            case "missing selector", "unresolved object" -> "unresolved object";
            case "group cycle or depth limit" -> "group cycle or depth limit";
            case "empty or dynamic group" -> "empty or dynamic group";
            case "unsupported service constraint", "missing service values" -> "unsupported service constraint";
            case "unsupported port expression", "invalid port range" -> "unsupported port expression";
            case "IPv6 not evaluated" -> "IPv6 not evaluated";
            default -> "dynamic or unsupported object type";
        };
    }

    private static String mapServiceReason(String raw) {
        if (raw == null) return "unsupported service constraint";
        return switch (raw) {
            case "negated selector" -> "negated selector";
            case "missing selector", "unresolved object" -> "unresolved object";
            case "group cycle or depth limit" -> "group cycle or depth limit";
            case "empty or dynamic group" -> "empty or dynamic group";
            case "unsupported port expression", "invalid port range" -> "unsupported port expression";
            case "dynamic or unsupported object type" -> "dynamic or unsupported object type";
            default -> "unsupported service constraint";
        };
    }

    private static List<IpRange> toIpRanges(List<Range> ranges) {
        List<IpRange> out = new ArrayList<>(ranges.size());
        for (Range r : ranges) out.add(new IpRange(r.start(), r.end()));
        return List.copyOf(out);
    }

    private static List<PortRange> toPortRanges(List<Range> ranges) {
        List<PortRange> out = new ArrayList<>(ranges.size());
        for (Range r : ranges) out.add(new PortRange((int) r.start(), (int) r.end()));
        return List.copyOf(out);
    }

    public static List<IpRange> mergeIpRanges(List<IpRange> input) {
        return mergeIpRanges(input, new Budget(Integer.MAX_VALUE));
    }

    private static List<IpRange> mergeIpRanges(List<IpRange> input, Budget budget) {
        if (input.isEmpty()) return List.of();
        List<IpRange> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparingLong(IpRange::start));
        List<IpRange> out = new ArrayList<>(sorted.size());
        for (IpRange r : sorted) {
            budget.spend();
            if (!out.isEmpty()) {
                IpRange last = out.get(out.size() - 1);
                if (last.end() == IPV4_MAX_LONG || last.end() + 1 >= r.start()) {
                    out.set(out.size() - 1, new IpRange(last.start(), Math.max(last.end(), r.end())));
                    continue;
                }
            }
            out.add(r);
        }
        return List.copyOf(out);
    }

    public static List<IpRange> intersectIpRanges(List<IpRange> a, List<IpRange> b) {
        if (a.isEmpty() || b.isEmpty()) return List.of();
        List<IpRange> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            IpRange x = a.get(i), y = b.get(j);
            long start = Math.max(x.start(), y.start());
            long end = Math.min(x.end(), y.end());
            if (start <= end) out.add(new IpRange(start, end));
            if (x.end() <= y.end()) i++;
            if (y.end() <= x.end()) j++;
        }
        return List.copyOf(out);
    }

    public static List<PortRange> mergePortRanges(List<PortRange> input) {
        if (input.isEmpty()) return List.of();
        List<PortRange> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparingInt(PortRange::low));
        List<PortRange> out = new ArrayList<>(sorted.size());
        for (PortRange r : sorted) {
            if (!out.isEmpty() && out.get(out.size() - 1).high() + 1 >= r.low()) {
                PortRange last = out.get(out.size() - 1);
                out.set(out.size() - 1, new PortRange(last.low(), Math.max(last.high(), r.high())));
            } else {
                out.add(r);
            }
        }
        return List.copyOf(out);
    }

    public static List<PortRange> intersectPortRanges(List<PortRange> a, List<PortRange> b) {
        if (a.isEmpty() || b.isEmpty()) return List.of();
        List<PortRange> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            PortRange x = a.get(i), y = b.get(j);
            int low = Math.max(x.low(), y.low());
            int high = Math.min(x.high(), y.high());
            if (low <= high) out.add(new PortRange(low, high));
            if (x.high() <= y.high()) i++;
            if (y.high() <= x.high()) j++;
        }
        return List.copyOf(out);
    }

    /** Exact interval difference {@code required \ allowed} for sorted, merged port ranges. */
    public static List<PortRange> subtractPortRanges(List<PortRange> required, List<PortRange> allowed) {
        if (required.isEmpty()) return List.of();
        if (allowed.isEmpty()) return required;
        List<PortRange> out = new ArrayList<>();
        int j = 0;
        for (PortRange req : required) {
            int cursor = req.low();
            while (j < allowed.size() && allowed.get(j).high() < cursor) j++;
            int k = j;
            while (k < allowed.size() && allowed.get(k).low() <= req.high()) {
                PortRange alw = allowed.get(k);
                if (alw.low() > cursor) {
                    out.add(new PortRange(cursor, alw.low() - 1));
                }
                if (alw.high() >= req.high()) {
                    cursor = req.high() + 1;
                    break;
                }
                cursor = alw.high() + 1;
                k++;
            }
            if (cursor <= req.high()) {
                out.add(new PortRange(cursor, req.high()));
            }
        }
        return List.copyOf(out);
    }

    private static void mergePortMaps(Map<String, List<PortRange>> target, Map<String, List<PortRange>> add) {
        add.forEach((proto, list) -> target.computeIfAbsent(proto, k -> new ArrayList<>()).addAll(list));
    }

    private static Map<String, List<PortRange>> normalizePortMap(Map<String, List<PortRange>> raw, Budget budget) {
        Map<String, List<PortRange>> out = new TreeMap<>();
        raw.forEach((proto, list) -> {
            budget.spend();
            List<PortRange> merged = mergePortRanges(list);
            if (!merged.isEmpty()) out.put(proto, merged);
        });
        return Collections.unmodifiableMap(out);
    }

    public static Map<String, List<PortRange>> allPorts() {
        return Map.of(
                "tcp", List.of(new PortRange(0, 65535)),
                "udp", List.of(new PortRange(0, 65535)));
    }
}
