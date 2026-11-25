package com.securityexpert.nexus.ui2.service.zonematrix;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.policy.ZoneMatrixPolicyBridge;
import com.securityexpert.nexus.ui2.service.policy.ZoneMatrixPolicyBridge.*;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

/**
 * Pure deterministic Zone Matrix evaluation engine — delivery step 3 of {@code docs/design/ZONE_MATRIX_CONTRACT.md}
 * (FROZEN, rev 2, 2026-11-13, sections 3.3, 4, 5.2–5.9, 7.2, 9 and 10.1 item 3).
 *
 * <p>No database, network or device access occurs here. Given one stored {@link PolicySnapshot} and one published
 * {@link ZoneMatrixContent} version, the same inputs and {@link #ENGINE_VERSION} always produce identical findings
 * ({@code ZM-9}).</p>
 */
public final class ZoneMatrixEvaluator {
    private ZoneMatrixEvaluator() {}

    public static final String ENGINE_VERSION = "step3-v1";

    public static final String OUTCOME_VIOLATION = "VIOLATION";
    public static final String OUTCOME_COMPLIANT = "COMPLIANT";
    public static final String OUTCOME_NOT_EVALUABLE = "NOT_EVALUABLE";
    public static final String OUTCOME_UNKNOWN = "UNKNOWN";
    public static final String OUTCOME_NOT_APPLICABLE = "NOT_APPLICABLE";

    public static final Set<String> NOT_EVALUABLE_REASONS = Set.of(
            "unresolved object",
            "group cycle or depth limit",
            "empty or dynamic group",
            "dynamic or unsupported object type",
            "negated selector",
            "unsupported service constraint",
            "unsupported port expression",
            "application ports not collected",
            "application constraint",
            "IPv6 not evaluated",
            "enabled state is unknown",
            "action semantics not modelled",
            "inline layer cleanup not collected",
            "depends on user identity",
            "NAT translation unresolved");

    public static final Set<String> UNKNOWN_REASONS = Set.of(
            "analysis budget",
            "unsupported evaluation scope");

    private static final List<IpRange> RFC1918_RANGES = List.of(
            new IpRange(0x0A00_0000L, 0x0AFF_FFFFL), // 10.0.0.0/8
            new IpRange(0xAC10_0000L, 0xAC1F_FFFFL), // 172.16.0.0/12
            new IpRange(0xC0A8_0000L, 0xC0A8_FFFFL)  // 192.168.0.0/16
    );

    /**Version metadata attached to every evaluation run and finding ({@code ZM-3}). */
    public record VersionCitation(String versionId, int versionNumber, String contentSha256) {
        public VersionCitation {
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(contentSha256, "contentSha256");
        }
    }

    /** Concrete witness tuple for one violated cell (§5.7 step 5, gated behind {@code zone_matrix_zones_read}). */
    public record Witness(
            String sourceRange,
            String destinationRange,
            String service,
            long sampleSourceIp,
            long sampleDestinationIp) {}

    /** Violation detail on one {@code (sourceZone, destinationZone)} matrix cell. */
    public record CellViolation(
            String sourceZone,
            String destinationZone,
            String statement,
            String severity,
            List<String> notAllowedServices,
            String natView,
            Witness witness) {
        public CellViolation {
            notAllowedServices = List.copyOf(notAllowedServices);
        }
    }

    /** Finding identity per §5.9: {@code (policyId, ruleUid, inlineParentPath, violatedZonePairs)}. */
    public record FindingIdentity(
            String policyId,
            String ruleUid,
            List<String> inlineParentPath,
            List<String> violatedPairs) {
        public FindingIdentity {
            inlineParentPath = List.copyOf(inlineParentPath);
            violatedPairs = List.copyOf(violatedPairs);
        }
    }

    /** Evaluated rule record (emitted for {@code VIOLATION}, {@code COMPLIANT}, {@code NOT_EVALUABLE}, {@code UNKNOWN}, and ended schedules). */
    public record RuleEvaluation(
            FindingIdentity identity,
            String ruleId,
            String ruleUid,
            int ruleNumber,
            String ruleName,
            String sectionId,
            String sectionName,
            List<String> inlineParentPath,
            List<String> targetDeviceIds,
            List<String> vendorFromZones,
            List<String> vendorToZones,
            String outcome,
            String reason,
            String highestSeverity,
            Set<String> flags,
            String coveringRuleId,
            List<String> touchedPairs,
            List<CellViolation> violatedCells,
            List<String> undecidedParts,
            String natViewSummary) {
        public RuleEvaluation {
            inlineParentPath = List.copyOf(inlineParentPath);
            targetDeviceIds = List.copyOf(targetDeviceIds);
            vendorFromZones = List.copyOf(vendorFromZones);
            vendorToZones = List.copyOf(vendorToZones);
            flags = Collections.unmodifiableSet(new LinkedHashSet<>(flags));
            touchedPairs = List.copyOf(touchedPairs);
            violatedCells = List.copyOf(violatedCells);
            undecidedParts = List.copyOf(undecidedParts);
        }
    }

    /** Explicit coverage item (§5.2, §7.1, {@code ZM-7}, {@code ZM-20}). */
    public record CoverageItem(String code, String description) {}

    /** Rule touching unassigned private RFC 1918 / IPv6 ULA space (§3.3). */
    public record ZoneDefinitionGap(
            String ruleId,
            int ruleNumber,
            String ruleName,
            String sectionName,
            List<String> unassignedPrivateRanges) {
        public ZoneDefinitionGap {
            unassignedPrivateRanges = List.copyOf(unassignedPrivateRanges);
        }
    }

    /** Counts of {@code NOT_APPLICABLE} rules by category (§5.2, §5.4, {@code ZM-6}, {@code ZM-24}). */
    public record NotApplicableSummary(
            int disabled,
            int denyType,
            int outsideSecurityRulebase,
            int applyLayerDecidedByInline,
            int scheduleEnded,
            List<EndedScheduleItem> endedSchedules) {
        public record EndedScheduleItem(String ruleId, int ruleNumber, String ruleName, String detail) {}
        public int total() {
            return disabled + denyType + outsideSecurityRulebase + applyLayerDecidedByInline + scheduleEnded;
        }
    }

    /** §7.2 Headline summary across one or more evaluated policies. */
    public record Headline(
            int distinctViolatingRules,
            int violatedZonePairs,
            int criticalRules,
            int coveredByEarlierRule,
            int conditionalRules,
            int outsideHeadlineOrderedLayer,
            int notEvaluableRules,
            int unknownRules,
            int incompletePolicies,
            int notEvaluatedPolicies,
            boolean exact,
            String text,
            String outsideHeadlineText) {}

    /** Complete pure evaluation output for one policy against one published version. */
    public record EvaluationResult(
            String engineVersion,
            VersionCitation version,
            String policyId,
            String policyName,
            String sourceId,
            String sourceName,
            String containerId,
            String containerName,
            String vendor,
            String collectedAt,
            List<PolicySnapshot.Target> targets,
            boolean evaluated,
            String notEvaluatedReason,
            String coverage,
            List<CoverageItem> coverageItems,
            int failureCount,
            Map<String, Integer> outcomeCounts,
            NotApplicableSummary notApplicable,
            Headline headline,
            List<RuleEvaluation> rules,
            List<ZoneDefinitionGap> zoneDefinitionGaps,
            boolean hygieneBudgetReached,
            long durationMillis,
            long hygieneMillis) {}

    /** Compiled version index reusable across multiple policies in a fleet run or scale benchmark. */
    public static final class CompiledVersion {
        final VersionCitation citation;
        final ZoneMatrixContent content;
        final ZonePartition partition;
        final String[] endpointIds;
        final Map<String, Integer> endpointIndex;
        final long[] segStarts;
        final long[] segEnds;
        final int[] segEndpoint;
        final CellSpec[][] grid;
        final List<IpRange> unassignedRfc1918;
        final List<ZonePartition.Segment> unassignedIpv6Segments;

        private CompiledVersion(
                VersionCitation citation,
                ZoneMatrixContent content,
                ZonePartition partition,
                String[] endpointIds,
                Map<String, Integer> endpointIndex,
                long[] segStarts,
                long[] segEnds,
                int[] segEndpoint,
                CellSpec[][] grid,
                List<IpRange> unassignedRfc1918,
                List<ZonePartition.Segment> unassignedIpv6Segments) {
            this.citation = citation;
            this.content = content;
            this.partition = partition;
            this.endpointIds = endpointIds;
            this.endpointIndex = endpointIndex;
            this.segStarts = segStarts;
            this.segEnds = segEnds;
            this.segEndpoint = segEndpoint;
            this.grid = grid;
            this.unassignedRfc1918 = unassignedRfc1918;
            this.unassignedIpv6Segments = unassignedIpv6Segments;
        }

        public VersionCitation citation() { return citation; }
        public ZoneMatrixContent content() { return content; }
    }

    private record CellSpec(
            String sourceId,
            String destinationId,
            String statement,
            String severity,
            int severityRank,
            List<PortRange> tcpAllowed,
            List<PortRange> udpAllowed,
            Set<String> panApplications) {}

    /** Compiles a published {@link ZoneMatrixContent} version once; recomputes {@code sha256} ({@code ZM-3}). */
    public static CompiledVersion compile(String versionId, int versionNumber, ZoneMatrixContent content) {
        Objects.requireNonNull(content, "content");
        ZonePartition partition = content.validate();
        String recomputedSha = content.sha256();
        VersionCitation citation = new VersionCitation(versionId, versionNumber, recomputedSha);

        int n = content.zones().size() + 1;
        String[] endpointIds = new String[n];
        Map<String, Integer> endpointIndex = new HashMap<>(n * 2);
        endpointIds[0] = ZoneMatrixContent.UNASSIGNED;
        endpointIndex.put(ZoneMatrixContent.UNASSIGNED, 0);
        for (int i = 0; i < content.zones().size(); i++) {
            String zid = content.zones().get(i).id();
            endpointIds[i + 1] = zid;
            endpointIndex.put(zid, i + 1);
        }

        List<ZonePartition.Segment> v4Segments = partition.segments(ZoneBlock.Family.IPV4);
        long[] starts = new long[v4Segments.size()];
        long[] ends = new long[v4Segments.size()];
        int[] ep = new int[v4Segments.size()];
        List<IpRange> unassignedV4 = new ArrayList<>();
        for (int i = 0; i < v4Segments.size(); i++) {
            ZonePartition.Segment s = v4Segments.get(i);
            starts[i] = s.start().longValueExact();
            ends[i] = s.end().longValueExact();
            ep[i] = s.zoneId() == null ? 0 : endpointIndex.get(s.zoneId());
            if (s.zoneId() == null) {
                unassignedV4.add(new IpRange(starts[i], ends[i]));
            }
        }
        List<IpRange> unassignedRfc1918 = ZoneMatrixPolicyBridge.intersectIpRanges(unassignedV4, RFC1918_RANGES);
        List<ZonePartition.Segment> unassignedV6 = partition.segments(ZoneBlock.Family.IPV6).stream()
                .filter(s -> s.zoneId() == null)
                .toList();

        ZoneMatrixContent.Statements statements = content.statements();
        CellSpec[][] grid = new CellSpec[n][n];
        for (int s = 0; s < n; s++) {
            for (int d = 0; d < n; d++) {
                ZoneMatrixContent.Effective eff = statements.of(endpointIds[s], endpointIds[d]);
                List<PortRange> tcp = new ArrayList<>();
                List<PortRange> udp = new ArrayList<>();
                Set<String> apps = new TreeSet<>();
                for (String entryText : eff.services()) {
                    ServiceEntry se = ServiceEntry.parse(entryText);
                    if (se.kind() == ServiceEntry.Kind.TCP) tcp.add(new PortRange(se.low(), se.high()));
                    else if (se.kind() == ServiceEntry.Kind.UDP) udp.add(new PortRange(se.low(), se.high()));
                    else if (se.kind() == ServiceEntry.Kind.APP_PAN) apps.add(se.token()); // exact name (§4.3)
                }
                grid[s][d] = new CellSpec(
                        endpointIds[s],
                        endpointIds[d],
                        eff.statement(),
                        eff.severity(),
                        severityRank(eff.severity()),
                        ZoneMatrixPolicyBridge.mergePortRanges(tcp),
                        ZoneMatrixPolicyBridge.mergePortRanges(udp),
                        Collections.unmodifiableSet(apps));
            }
        }

        return new CompiledVersion(
                citation, content, partition, endpointIds, endpointIndex,
                starts, ends, ep, grid, unassignedRfc1918, unassignedV6);
    }

    /** Evaluates one stored policy snapshot against a published zone-matrix version. */
    public static EvaluationResult evaluate(
            PolicySnapshot snapshot,
            String versionId,
            int versionNumber,
            ZoneMatrixContent content,
            Instant now) {
        return evaluate(snapshot, compile(versionId, versionNumber, content), now);
    }

    /** Evaluates one stored policy snapshot against a pre-compiled zone-matrix version. */
    public static EvaluationResult evaluate(PolicySnapshot snapshot, CompiledVersion compiled, Instant now) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(compiled, "compiled");
        Objects.requireNonNull(now, "now");
        long t0 = System.nanoTime();

        PolicySnapshot.Metadata meta = snapshot.metadata();
        String vendor = meta == null || meta.vendor() == null ? "" : meta.vendor();
        if (!"CP".equals(vendor) && !"PAN".equals(vendor)) {
            Headline emptyHeadline = buildHeadline(compiled.citation.versionNumber(), List.of(), "INCOMPLETE", 0, 0, 1);
            return new EvaluationResult(
                    ENGINE_VERSION,
                    compiled.citation,
                    meta == null ? "" : meta.id(),
                    meta == null ? "" : meta.name(),
                    meta == null ? "" : meta.sourceId(),
                    meta == null ? "" : meta.sourceName(),
                    meta == null ? "" : meta.containerId(),
                    meta == null ? "" : meta.containerName(),
                    vendor,
                    meta == null ? null : meta.collectedAt(),
                    meta == null ? List.of() : meta.targets(),
                    false,
                    "not evaluated: policy not collected",
                    "INCOMPLETE",
                    List.of(new CoverageItem("POLICY_NOT_COLLECTED", "not evaluated: policy not collected")),
                    snapshot.failures().size(),
                    Map.of(OUTCOME_VIOLATION, 0, OUTCOME_COMPLIANT, 0, OUTCOME_NOT_EVALUABLE, 0, OUTCOME_UNKNOWN, 0),
                    new NotApplicableSummary(0, 0, 0, 0, 0, List.of()),
                    emptyHeadline,
                    List.of(),
                    List.of(),
                    false,
                    (System.nanoTime() - t0) / 1_000_000L,
                    0L);
        }

        PreparedPolicy prepared = ZoneMatrixPolicyBridge.prepare(snapshot, now);
        List<IpRange> portSplitVipRanges = computePortSplitVipRanges(compiled, prepared.natRules());

        int naDisabled = 0;
        int naDeny = 0;
        int naOutside = 0;
        int naApplyLayer = 0;
        int naScheduleEnded = 0;
        List<NotApplicableSummary.EndedScheduleItem> endedSchedules = new ArrayList<>();

        Map<String, Integer> outcomeCounts = new LinkedHashMap<>();
        outcomeCounts.put(OUTCOME_VIOLATION, 0);
        outcomeCounts.put(OUTCOME_COMPLIANT, 0);
        outcomeCounts.put(OUTCOME_NOT_EVALUABLE, 0);
        outcomeCounts.put(OUTCOME_UNKNOWN, 0);

        List<RuleEvaluation> evaluatedRules = new ArrayList<>();
        List<ZoneDefinitionGap> gaps = new ArrayList<>();

        for (ScopedRule sr : prepared.scopedRules()) {
            PolicySnapshot.Rule rule = sr.rule();
            if (sr.outsideSecurityRulebase() && !sr.panDefaultOverrideSection()) {
                naOutside++;
                continue;
            }
            RuleUpperBound bound = prepared.ruleBounds().get(rule.id());
            if (bound == null) continue;

            if ("NOT_APPLICABLE".equals(bound.kind())) {
                switch (bound.notApplicableKind()) {
                    case "disabled" -> naDisabled++;
                    case "deny" -> naDeny++;
                    case "schedule_ended" -> {
                        naScheduleEnded++;
                        endedSchedules.add(new NotApplicableSummary.EndedScheduleItem(
                                rule.id(), rule.number(), rule.name(), bound.notApplicableDetail()));
                    }
                    default -> naOutside++;
                }
                continue;
            }

            // Record §3.3 zone-definition gaps on active security rules before deciding outcome.
            ZoneDefinitionGap gap = detectZoneDefinitionGap(compiled, prepared, sr, bound);
            if (gap != null) gaps.add(gap);

            if ("UNKNOWN".equals(bound.kind())) {
                RuleEvaluation ev = makeNonViolating(meta.id(), sr, bound, OUTCOME_UNKNOWN, bound.unknownReason(), Set.of());
                evaluatedRules.add(ev);
                outcomeCounts.merge(OUTCOME_UNKNOWN, 1, Integer::sum);
                continue;
            }

            if ("NOT_EVALUABLE".equals(bound.kind())) {
                RuleEvaluation ev = makeNonViolating(meta.id(), sr, bound, OUTCOME_NOT_EVALUABLE,
                        bound.notEvaluableReason(), bound.baseFlags());
                evaluatedRules.add(ev);
                outcomeCounts.merge(OUTCOME_NOT_EVALUABLE, 1, Integer::sum);
                continue;
            }

            if ("APPLY_LAYER".equals(bound.kind())) {
                RuleEvaluation applyParent = evaluateApplyLayerParent(compiled, prepared, portSplitVipRanges, sr, bound);
                if (applyParent == null) {
                    naApplyLayer++;
                    continue;
                }
                evaluatedRules.add(applyParent);
                outcomeCounts.merge(applyParent.outcome(), 1, Integer::sum);
                continue;
            }

            RuleEvaluation ev = evaluateSecurityRule(compiled, prepared, portSplitVipRanges, sr, bound);
            evaluatedRules.add(ev);
            outcomeCounts.merge(ev.outcome(), 1, Integer::sum);
        }

        List<CoverageItem> coverageItems = computeCoverageItems(prepared);
        String coverage = coverageItems.isEmpty() && snapshot.failures().isEmpty() ? "COMPLETE" : "INCOMPLETE";
        int incompleteCount = "INCOMPLETE".equals(coverage) ? 1 : 0;
        Headline headline = buildHeadline(
                compiled.citation.versionNumber(),
                evaluatedRules,
                coverage,
                outcomeCounts.get(OUTCOME_NOT_EVALUABLE),
                outcomeCounts.get(OUTCOME_UNKNOWN),
                incompleteCount,
                0);

        long durationMillis = (System.nanoTime() - t0) / 1_000_000L;
        return new EvaluationResult(
                ENGINE_VERSION,
                compiled.citation,
                meta.id(),
                meta.name(),
                meta.sourceId(),
                meta.sourceName(),
                meta.containerId(),
                meta.containerName(),
                vendor,
                meta.collectedAt(),
                meta.targets(),
                true,
                null,
                coverage,
                coverageItems,
                snapshot.failures().size(),
                Collections.unmodifiableMap(outcomeCounts),
                new NotApplicableSummary(naDisabled, naDeny, naOutside, naApplyLayer, naScheduleEnded, List.copyOf(endedSchedules)),
                headline,
                List.copyOf(evaluatedRules),
                List.copyOf(gaps),
                prepared.hygieneBudgetReached(),
                durationMillis,
                prepared.hygieneMillis());
    }

    // ---- Rule evaluation (Apply Layer, inline child, Ask/Inform, Accept/allow, NAT dual view) --------------------------

    private static RuleEvaluation evaluateApplyLayerParent(
            CompiledVersion compiled,
            PreparedPolicy prepared,
            List<IpRange> portSplitVipRanges,
            ScopedRule sr,
            RuleUpperBound bound) {
        // §5.2, ZM-17: Evaluate Apply Layer parent as an Accept upper bound first.
        CombinedViews asAccept = evaluateCombinedViews(compiled, prepared, portSplitVipRanges, sr, bound, true);
        if (OUTCOME_COMPLIANT.equals(asAccept.outcome)) {
            return buildRuleEvaluation(prepared.snapshot().metadata().id(), sr, bound, asAccept, prepared);
        }
        // Otherwise: if its inline layer ends in an explicit universal catch-all rule, parent is NOT_APPLICABLE;
        // else NOT_EVALUABLE "inline layer cleanup not collected" (never "action semantics not modelled").
        if (Boolean.TRUE.equals(prepared.inlineLayerHasExplicitCatchAll().get(sr.rule().id()))) {
            return null;
        }
        return makeNonViolating(prepared.snapshot().metadata().id(), sr, bound,
                OUTCOME_NOT_EVALUABLE, "inline layer cleanup not collected", bound.baseFlags());
    }

    private static RuleEvaluation evaluateSecurityRule(
            CompiledVersion compiled,
            PreparedPolicy prepared,
            List<IpRange> portSplitVipRanges,
            ScopedRule sr,
            RuleUpperBound bound) {
        boolean askOrInform = "ASK_INFORM".equals(bound.kind());

        // §5.2: For a Check Point inline child rule, its own match is enough to prove COMPLIANT;
        // the intersection with the parent's match is required to prove VIOLATION.
        if (!sr.inlineParentPath().isEmpty()) {
            CombinedViews own = evaluateCombinedViews(compiled, prepared, portSplitVipRanges,
                    new ScopedRule(sr.section(), sr.rule(), false, false, false, sr.cpOrderedLayer(), List.of()),
                    bound, true);
            if (OUTCOME_COMPLIANT.equals(own.outcome)) {
                return buildRuleEvaluation(prepared.snapshot().metadata().id(), sr, bound, own, prepared);
            }
        }

        CombinedViews combined = evaluateCombinedViews(compiled, prepared, portSplitVipRanges, sr, bound, askOrInform);
        return buildRuleEvaluation(prepared.snapshot().metadata().id(), sr, bound, combined, prepared);
    }

    private record EffectiveBounds(
            AddressBound source,
            AddressBound destination,
            ServiceBound service,
            Set<String> flags,
            String blockingNotEvaluableReason) {}

    private static EffectiveBounds intersectWithInlineParents(
            PreparedPolicy prepared, ScopedRule sr, RuleUpperBound child) {
        AddressBound src = child.source();
        AddressBound dst = child.destination();
        ServiceBound svc = child.service();
        Set<String> flags = new LinkedHashSet<>(child.baseFlags());

        if (hardAddressFailure(src)) {
            return new EffectiveBounds(src, dst, svc, flags, src.notEvaluableReason());
        }
        if (hardAddressFailure(dst)) {
            return new EffectiveBounds(src, dst, svc, flags, dst.notEvaluableReason());
        }
        if (svc.notEvaluableReason() != null) {
            return new EffectiveBounds(src, dst, svc, flags, svc.notEvaluableReason());
        }

        for (String parentId : sr.inlineParentPath()) {
            RuleUpperBound parent = prepared.ruleBounds().get(parentId);
            if (parent == null || !parent.evaluableRule()) {
                String reason = parent != null && parent.notEvaluableReason() != null
                        ? parent.notEvaluableReason() : "inline layer cleanup not collected";
                return new EffectiveBounds(src, dst, svc, flags, reason);
            }
            if (hardAddressFailure(parent.source())) {
                return new EffectiveBounds(src, dst, svc, flags, parent.source().notEvaluableReason());
            }
            if (hardAddressFailure(parent.destination())) {
                return new EffectiveBounds(src, dst, svc, flags, parent.destination().notEvaluableReason());
            }
            if (parent.service().notEvaluableReason() != null) {
                return new EffectiveBounds(src, dst, svc, flags, parent.service().notEvaluableReason());
            }
            flags.addAll(parent.baseFlags());
            if (parent.source().accessRole()) flags.add("IDENTITY_IGNORED");
            src = intersectAddress(src, parent.source());
            dst = intersectAddress(dst, parent.destination());
            svc = intersectService(svc, parent.service());
        }
        return new EffectiveBounds(src, dst, svc, flags, null);
    }

    private static boolean hardAddressFailure(AddressBound b) {
        return b.notEvaluableReason() != null && !b.accessRole() && !b.dynamicAddress();
    }

    private static AddressBound intersectAddress(AddressBound a, AddressBound b) {
        List<IpRange> upper = ZoneMatrixPolicyBridge.intersectIpRanges(a.upperIpv4(), b.upperIpv4());
        List<IpRange> lower = ZoneMatrixPolicyBridge.intersectIpRanges(a.lowerIpv4(), b.lowerIpv4());
        List<IpRange> explicit = ZoneMatrixPolicyBridge.mergeIpRanges(concat(a.explicitIpv4(), b.explicitIpv4()));
        boolean hasIpv6 = a.hasIpv6() && b.hasIpv6();
        boolean accessRole = a.accessRole() || b.accessRole();
        boolean dynamic = a.dynamicAddress() || b.dynamicAddress();
        String reason = accessRole ? "depends on user identity"
                : dynamic ? "dynamic or unsupported object type"
                : null;
        return new AddressBound(upper, lower, explicit, a.ulaRanges(), hasIpv6, accessRole, dynamic, reason);
    }

    private static ServiceBound intersectService(ServiceBound a, ServiceBound b) {
        boolean cpApp = a.cpApplicationConstraint() || b.cpApplicationConstraint();
        boolean panAppDefault = a.panApplicationDefault() || b.panApplicationDefault();
        boolean upperAny = a.upperAny() && b.upperAny();
        boolean lowerAny = !cpApp && a.lowerAny() && b.lowerAny();
        Map<String, List<PortRange>> upperPorts = intersectPortMap(a.upperAny(), a.upperPorts(), b.upperAny(), b.upperPorts());
        Map<String, List<PortRange>> lowerPorts = cpApp
                ? Map.of()
                : intersectPortMap(a.lowerAny(), a.lowerPorts(), b.lowerAny(), b.lowerPorts());
        // Inline-layer intersection is Check Point only; application names are never carried through it (fail closed).
        return new ServiceBound(upperAny, lowerAny, panAppDefault, cpApp, upperPorts, lowerPorts, null);
    }

    private static Map<String, List<PortRange>> intersectPortMap(
            boolean aAny, Map<String, List<PortRange>> a, boolean bAny, Map<String, List<PortRange>> b) {
        if (aAny && bAny) return ZoneMatrixPolicyBridge.allPorts();
        if (aAny) return b;
        if (bAny) return a;
        Map<String, List<PortRange>> out = new TreeMap<>();
        for (String proto : List.of("tcp", "udp")) {
            List<PortRange> inter = ZoneMatrixPolicyBridge.intersectPortRanges(
                    a.getOrDefault(proto, List.of()), b.getOrDefault(proto, List.of()));
            if (!inter.isEmpty()) out.put(proto, inter);
        }
        return Collections.unmodifiableMap(out);
    }

    private record SingleViewResult(
            String viewLabel,
            Set<String> touchedPairs,
            List<CellViolation> violations,
            boolean upperCompliant,
            String undecidedReason) {}

    private record CombinedViews(
            String outcome,
            String reason,
            Set<String> flags,
            List<String> touchedPairs,
            List<CellViolation> violations,
            List<String> undecidedParts,
            String natViewSummary) {}

    private static CombinedViews evaluateCombinedViews(
            CompiledVersion compiled,
            PreparedPolicy prepared,
            List<IpRange> portSplitVipRanges,
            ScopedRule sr,
            RuleUpperBound rawBound,
            boolean upperBoundOnly) {
        EffectiveBounds eff = intersectWithInlineParents(prepared, sr, rawBound);
        Set<String> flags = new LinkedHashSet<>(eff.flags);

        if (eff.blockingNotEvaluableReason != null) {
            return new CombinedViews(OUTCOME_NOT_EVALUABLE, eff.blockingNotEvaluableReason,
                    flags, List.of(), List.of(), List.of(), "ORIGINAL");
        }

        AddressBound src = eff.source;
        AddressBound dst = eff.destination;
        ServiceBound svc = eff.service;
        boolean hasIpv6 = src.hasIpv6() || dst.hasIpv6();

        // 1. Evaluate original (as-written) view.
        SingleViewResult original = evaluateOneView(compiled, src, dst, svc, upperBoundOnly, "ORIGINAL");

        // 2. Evaluate matching NAT translated views (§5.6, ZM-14).
        List<SingleViewResult> translatedViews = new ArrayList<>();
        boolean hasUnresolvedNat = false;
        boolean touchesPortSplitVip = !ZoneMatrixPolicyBridge.intersectIpRanges(dst.upperIpv4(), portSplitVipRanges).isEmpty();
        boolean anyNatMatched = false;

        for (NatRuleBound nat : prepared.natRules()) {
            List<IpRange> upperOverlap = ZoneMatrixPolicyBridge.intersectIpRanges(dst.upperIpv4(), nat.originalDest());
            if (upperOverlap.isEmpty()) continue;

            ServiceBound slice = sliceServiceForNat(svc, nat);
            if (slice == null) continue; // Service provably disjoint from NAT rule's service.

            anyNatMatched = true;
            if (nat.unresolved()) {
                hasUnresolvedNat = true;
                continue;
            }
            List<IpRange> lowerOverlap = ZoneMatrixPolicyBridge.intersectIpRanges(dst.lowerIpv4(), nat.originalDest());
            AddressBound transDst = new AddressBound(
                    nat.translatedDest(),
                    lowerOverlap.isEmpty() ? List.of() : nat.translatedDest(),
                    nat.explicitTranslatedDest(),
                    List.of(),
                    false,
                    false,
                    dst.dynamicAddress(),
                    dst.dynamicAddress() ? "dynamic or unsupported object type" : null);
            translatedViews.add(evaluateOneView(compiled, src, transDst, slice, upperBoundOnly, "NAT:" + nat.ruleId()));
        }

        if (anyNatMatched && touchesPortSplitVip) {
            flags.add("NAT_PORT_SPLIT");
        }

        Set<String> allTouched = new LinkedHashSet<>(original.touchedPairs);
        for (SingleViewResult tv : translatedViews) allTouched.addAll(tv.touchedPairs);

        // Collect all proven violations across original + translated views.
        List<CellViolation> mergedViolations = mergeCellViolations(original, translatedViews);

        // Collect undecided parts alongside any violation (§5.4).
        Set<String> undecidedSet = new LinkedHashSet<>();
        if (original.undecidedReason != null) undecidedSet.add(original.undecidedReason);
        for (SingleViewResult tv : translatedViews) {
            if (tv.undecidedReason != null) undecidedSet.add(tv.undecidedReason);
        }
        if (hasUnresolvedNat) undecidedSet.add("NAT translation unresolved");
        if (hasIpv6 && !src.accessRole() && !src.dynamicAddress() && !dst.dynamicAddress()) {
            undecidedSet.add("IPv6 not evaluated");
        }

        if (!mergedViolations.isEmpty() && !upperBoundOnly) {
            if (src.accessRole()) flags.add("IDENTITY_IGNORED");
            if (original.violations.isEmpty()) {
                // Only translated view(s) violated (§5.6).
                flags.add("NAT_MAY_APPLY");
            }
            String natSummary = original.violations.isEmpty() ? "TRANSLATED"
                    : translatedViews.stream().anyMatch(v -> !v.violations.isEmpty()) ? "BOTH" : "ORIGINAL";
            return new CombinedViews(OUTCOME_VIOLATION, null, flags,
                    List.copyOf(allTouched), mergedViolations, List.copyOf(undecidedSet), natSummary);
        }

        // No violation proven: check if EVERY view is upper-bound COMPLIANT and no family/NAT is undecided (§5.4, ZM-4).
        boolean allViewsUpperCompliant = original.upperCompliant
                && translatedViews.stream().allMatch(v -> v.upperCompliant);

        if (allViewsUpperCompliant && !hasUnresolvedNat && !hasIpv6) {
            Set<String> compliantFlags = new LinkedHashSet<>(flags);
            compliantFlags.remove("IDENTITY_IGNORED");
            compliantFlags.remove("TIME_IGNORED");
            compliantFlags.remove("CONDITION_IGNORED");
            String natSummary = translatedViews.isEmpty() ? "ORIGINAL" : "BOTH";
            return new CombinedViews(OUTCOME_COMPLIANT, null, compliantFlags,
                    List.copyOf(allTouched), List.of(), List.of(), natSummary);
        }

        // Determine the single authoritative NOT_EVALUABLE reason from the fixed §5.4 vocabulary.
        String primaryReason = pickNotEvaluableReason(
                upperBoundOnly, src, dst, svc, original, translatedViews, hasUnresolvedNat, hasIpv6);
        String natSummary = translatedViews.isEmpty() && !hasUnresolvedNat ? "ORIGINAL" : "BOTH";
        return new CombinedViews(OUTCOME_NOT_EVALUABLE, primaryReason, flags,
                List.copyOf(allTouched), List.of(), List.copyOf(undecidedSet), natSummary);
    }

    private static String pickNotEvaluableReason(
            boolean upperBoundOnly,
            AddressBound src,
            AddressBound dst,
            ServiceBound svc,
            SingleViewResult original,
            List<SingleViewResult> translatedViews,
            boolean hasUnresolvedNat,
            boolean hasIpv6) {
        if (upperBoundOnly) return "action semantics not modelled";
        if (src.accessRole()) return "depends on user identity";
        if (src.dynamicAddress() || dst.dynamicAddress()) return "dynamic or unsupported object type";
        if (original.undecidedReason != null) return original.undecidedReason;
        for (SingleViewResult tv : translatedViews) {
            if (tv.undecidedReason != null) return tv.undecidedReason;
        }
        if (hasUnresolvedNat) return "NAT translation unresolved";
        if (svc.cpApplicationConstraint()) return "application constraint";
        if (svc.panApplicationDefault()) return "application ports not collected";
        if (hasIpv6) return "IPv6 not evaluated";
        return "action semantics not modelled";
    }

    private static ServiceBound sliceServiceForNat(ServiceBound svc, NatRuleBound nat) {
        if (nat.serviceAny()) return svc;
        if (svc.panApplicationDefault()) return svc;
        Map<String, List<PortRange>> upper = intersectPortMap(svc.upperAny(), svc.upperPorts(), false, nat.originalService());
        if (upper.isEmpty()) return null;
        Map<String, List<PortRange>> lower = svc.cpApplicationConstraint()
                ? Map.of()
                : intersectPortMap(svc.lowerAny(), svc.lowerPorts(), false, nat.originalService());
        return new ServiceBound(false, false, false, svc.cpApplicationConstraint(), upper, lower, null);
    }

    private static SingleViewResult evaluateOneView(
            CompiledVersion compiled,
            AddressBound src,
            AddressBound dst,
            ServiceBound svc,
            boolean upperBoundOnly,
            String viewLabel) {
        // Empty IPv4 intersection (e.g. inline child disjoint from parent) or empty TCP/UDP intersection:
        boolean serviceEmpty = !svc.panApplicationDefault() && !svc.upperAny() && svc.upperPorts().isEmpty();
        if (src.upperIpv4().isEmpty() || dst.upperIpv4().isEmpty() || serviceEmpty) {
            return new SingleViewResult(viewLabel, Set.of(), List.of(), true, null);
        }

        ZoneTouches upperSrc = touchZones(compiled, src.upperIpv4());
        ZoneTouches upperDst = touchZones(compiled, dst.upperIpv4());
        ZoneTouches lowerSrc = upperBoundOnly ? ZoneTouches.EMPTY : touchZones(compiled, src.lowerIpv4());
        ZoneTouches lowerDst = upperBoundOnly ? ZoneTouches.EMPTY : touchZones(compiled, dst.lowerIpv4());

        Set<String> touchedPairs = new LinkedHashSet<>();
        List<CellViolation> violations = new ArrayList<>();
        boolean upperCompliant = true;
        String firstUndecidedReason = null;

        for (int si = 0; si < upperSrc.count; si++) {
            int sIdx = upperSrc.zones[si];
            for (int di = 0; di < upperDst.count; di++) {
                int dIdx = upperDst.zones[di];
                CellSpec cell = compiled.grid[sIdx][dIdx];
                touchedPairs.add(cell.sourceId + "->" + cell.destinationId);

                // 1. Check upper-bound compliance on this cell.
                String cellUpperProblem = checkUpperCell(cell, svc);
                if (cellUpperProblem != null) {
                    upperCompliant = false;
                    if (firstUndecidedReason == null) firstUndecidedReason = cellUpperProblem;
                }

                // 2. Check lower-bound proven violation on this cell.
                if (!upperBoundOnly && lowerSrc.has(sIdx) && lowerDst.has(dIdx)) {
                    LowerViolation lv = checkLowerCell(cell, svc);
                    if (lv != null) {
                        long sStart = lowerSrc.firstStart[sIdx], sEnd = lowerSrc.firstEnd[sIdx];
                        long dStart = lowerDst.firstStart[dIdx], dEnd = lowerDst.firstEnd[dIdx];
                        Witness witness = new Witness(
                                formatIpInterval(sStart, sEnd),
                                formatIpInterval(dStart, dEnd),
                                lv.witnessService,
                                sStart,
                                dStart);
                        violations.add(new CellViolation(
                                cell.sourceId,
                                cell.destinationId,
                                cell.statement,
                                cell.severity,
                                lv.notAllowedServices,
                                viewLabel,
                                witness));
                    }
                }
            }
        }

        return new SingleViewResult(viewLabel, touchedPairs, violations, upperCompliant, firstUndecidedReason);
    }

    private static String checkUpperCell(CellSpec cell, ServiceBound svc) {
        if ("ALLOW_ANY".equals(cell.statement)) return null;
        if ("DENY_ALL".equals(cell.statement)) {
            return svc.cpApplicationConstraint() ? "application constraint" : "denied";
        }
        // ALLOW_LISTED:
        if (svc.panApplicationDefault()) return panApplicationCellProblem(cell, svc);
        if (svc.upperAny()) {
            return svc.cpApplicationConstraint() ? "application constraint" : "denied";
        }
        List<PortRange> tcpDiff = ZoneMatrixPolicyBridge.subtractPortRanges(
                svc.upperPorts().getOrDefault("tcp", List.of()), cell.tcpAllowed);
        List<PortRange> udpDiff = ZoneMatrixPolicyBridge.subtractPortRanges(
                svc.upperPorts().getOrDefault("udp", List.of()), cell.udpAllowed);
        if (tcpDiff.isEmpty() && udpDiff.isEmpty()) return null;
        return svc.cpApplicationConstraint() ? "application constraint" : "denied";
    }

    /**
     * §4.3 (delivery step 7): a Palo Alto {@code application-default} rule is inside an {@code ALLOW_LISTED} cell only
     * when every one of its application names is listed in the cell as {@code app:pan:<name>} (exact string equality).
     * Everything else stays undecided, never a violation: a cell without application entries, or a name the cell does
     * not list (its standard ports are not collected), gives "application ports not collected"; a name set that did not
     * resolve gives its resolution reason; application {@code any} (not decided by §4.3) keeps "application ports not
     * collected".
     */
    private static String panApplicationCellProblem(CellSpec cell, ServiceBound svc) {
        if (cell.panApplications.isEmpty()) return "application ports not collected";
        if (!svc.panApplicationNamesKnown()) {
            return svc.panApplicationProblem() != null ? svc.panApplicationProblem() : "application ports not collected";
        }
        return cell.panApplications.containsAll(svc.panApplicationNames()) ? null : "application ports not collected";
    }

    private record LowerViolation(List<String> notAllowedServices, String witnessService) {}

    private static LowerViolation checkLowerCell(CellSpec cell, ServiceBound svc) {
        if ("ALLOW_ANY".equals(cell.statement)) return null;
        if (svc.panApplicationDefault()) {
            if ("DENY_ALL".equals(cell.statement)) {
                return new LowerViolation(List.of("application-default"), "application-default");
            }
            return null; // ALLOW_LISTED with application-default is NOT_EVALUABLE ("application ports not collected").
        }
        if (svc.lowerAny()) {
            if ("DENY_ALL".equals(cell.statement)) {
                return new LowerViolation(List.of("any"), "tcp/443");
            }
            // ZM-5: service = any in ALLOW_LISTED is ALWAYS a VIOLATION.
            List<PortRange> tcpDiff = ZoneMatrixPolicyBridge.subtractPortRanges(
                    List.of(new PortRange(0, 65535)), cell.tcpAllowed);
            List<PortRange> udpDiff = ZoneMatrixPolicyBridge.subtractPortRanges(
                    List.of(new PortRange(0, 65535)), cell.udpAllowed);
            List<String> parts = formatPortDiff(tcpDiff, udpDiff);
            if (parts.isEmpty()) {
                return new LowerViolation(List.of("any"), "any");
            }
            String witnessSvc = !tcpDiff.isEmpty() ? "tcp/" + tcpDiff.get(0).low() : "udp/" + udpDiff.get(0).low();
            List<String> withAny = new ArrayList<>(parts);
            if (!withAny.contains("any")) withAny.add(0, "any");
            return new LowerViolation(withAny, witnessSvc);
        }
        if (svc.lowerPorts().isEmpty()) return null;
        List<PortRange> reqTcp = svc.lowerPorts().getOrDefault("tcp", List.of());
        List<PortRange> reqUdp = svc.lowerPorts().getOrDefault("udp", List.of());
        if ("DENY_ALL".equals(cell.statement)) {
            List<String> parts = formatPortDiff(reqTcp, reqUdp);
            String witnessSvc = !reqTcp.isEmpty() ? "tcp/" + reqTcp.get(0).low() : "udp/" + reqUdp.get(0).low();
            return new LowerViolation(parts, witnessSvc);
        }
        List<PortRange> tcpDiff = ZoneMatrixPolicyBridge.subtractPortRanges(reqTcp, cell.tcpAllowed);
        List<PortRange> udpDiff = ZoneMatrixPolicyBridge.subtractPortRanges(reqUdp, cell.udpAllowed);
        if (tcpDiff.isEmpty() && udpDiff.isEmpty()) return null;
        List<String> parts = formatPortDiff(tcpDiff, udpDiff);
        String witnessSvc = !tcpDiff.isEmpty() ? "tcp/" + tcpDiff.get(0).low() : "udp/" + udpDiff.get(0).low();
        return new LowerViolation(parts, witnessSvc);
    }

    private static List<CellViolation> mergeCellViolations(
            SingleViewResult original, List<SingleViewResult> translatedViews) {
        Map<String, CellViolation> byPair = new LinkedHashMap<>();
        List<SingleViewResult> all = new ArrayList<>(1 + translatedViews.size());
        all.add(original);
        all.addAll(translatedViews);
        for (SingleViewResult view : all) {
            for (CellViolation cv : view.violations) {
                String key = cv.sourceZone() + "->" + cv.destinationZone();
                CellViolation prev = byPair.get(key);
                if (prev == null) {
                    byPair.put(key, cv);
                } else {
                    Set<String> mergedServices = new LinkedHashSet<>(prev.notAllowedServices());
                    mergedServices.addAll(cv.notAllowedServices());
                    String natLabel = prev.natView().equals(cv.natView()) ? prev.natView() : "BOTH";
                    byPair.put(key, new CellViolation(
                            prev.sourceZone(), prev.destinationZone(), prev.statement(), prev.severity(),
                            List.copyOf(mergedServices), natLabel, prev.witness()));
                }
            }
        }
        return List.copyOf(byPair.values());
    }

    private static RuleEvaluation buildRuleEvaluation(
            String policyId,
            ScopedRule sr,
            RuleUpperBound bound,
            CombinedViews combined,
            PreparedPolicy prepared) {
        PolicySnapshot.Rule rule = sr.rule();
        String ruleUid = ruleUid(rule);
        Set<String> flags = new LinkedHashSet<>(combined.flags);
        String coveringRuleId = null;
        String highestSeverity = null;

        if (OUTCOME_VIOLATION.equals(combined.outcome)) {
            int maxRank = -1;
            for (CellViolation cv : combined.violations) {
                int r = severityRank(cv.severity());
                if (r > maxRank) {
                    maxRank = r;
                    highestSeverity = cv.severity();
                }
            }
            // §5.5, ZM-25: Hygiene CONFLICT deny cover & INLINE_LAYER_COVER.
            HygieneCover hc = prepared.hygieneCovers().get(rule.id());
            if (hc != null) {
                if (hc.coveredByEarlierDeny()) flags.add("COVERED_BY_EARLIER_DENY");
                if (hc.inlineLayerCover()) flags.add("INLINE_LAYER_COVER");
                coveringRuleId = hc.coveringRuleId();
            }
            // §5.5, ZM-16: Check Point multiple ordered layers witness check.
            if ("CP".equals(prepared.vendor()) && prepared.orderedLayerCount() > 1) {
                OtherLayersVerdict verdict = checkRuleWitnessesInOtherLayers(prepared, sr, bound, flags, combined.violations);
                if (verdict == OtherLayersVerdict.DENIED) {
                    flags.add("DENIED_BY_OTHER_ORDERED_LAYER");
                } else if (verdict == OtherLayersVerdict.UNKNOWN) {
                    flags.add("OTHER_ORDERED_LAYERS_UNKNOWN");
                }
            }
        }

        List<String> violatedPairs = combined.violations.stream()
                .map(cv -> cv.sourceZone() + "->" + cv.destinationZone())
                .sorted()
                .toList();
        FindingIdentity identity = new FindingIdentity(policyId, ruleUid, sr.inlineParentPath(), violatedPairs);

        // Normalize internal sentinel reason if any remained.
        String reason = combined.reason;
        if (OUTCOME_NOT_EVALUABLE.equals(combined.outcome) && !NOT_EVALUABLE_REASONS.contains(reason)) {
            reason = "action semantics not modelled";
        }

        return new RuleEvaluation(
                identity,
                rule.id(),
                ruleUid,
                rule.number(),
                rule.name(),
                sr.section().id(),
                sr.section().name(),
                sr.inlineParentPath(),
                bound.targetDeviceIds(),
                rule.extras().getOrDefault("from", List.of()),
                rule.extras().getOrDefault("to", List.of()),
                combined.outcome,
                reason,
                highestSeverity,
                flags,
                coveringRuleId,
                combined.touchedPairs,
                combined.violations,
                OUTCOME_VIOLATION.equals(combined.outcome) ? combined.undecidedParts : List.of(),
                combined.natViewSummary);
    }

    private static OtherLayersVerdict checkRuleWitnessesInOtherLayers(
            PreparedPolicy prepared,
            ScopedRule sr,
            RuleUpperBound bound,
            Set<String> flags,
            List<CellViolation> violations) {
        boolean anyUnknown = false;
        boolean installUnresolved = flags.contains("INSTALL_ON_UNRESOLVED");
        for (CellViolation cv : violations) {
            Witness w = cv.witness();
            OtherLayersVerdict v = ZoneMatrixPolicyBridge.checkOtherOrderedLayers(
                    prepared,
                    sr.cpOrderedLayer(),
                    bound.targetDeviceIds(),
                    bound.rawInstallOnRefs(),
                    installUnresolved,
                    w.sampleSourceIp(),
                    w.sampleDestinationIp(),
                    w.service());
            if (v == OtherLayersVerdict.ALL_ALLOWED) {
                return OtherLayersVerdict.ALL_ALLOWED;
            }
            if (v == OtherLayersVerdict.UNKNOWN) {
                anyUnknown = true;
            }
        }
        return anyUnknown ? OtherLayersVerdict.UNKNOWN : OtherLayersVerdict.DENIED;
    }

    private static RuleEvaluation makeNonViolating(
            String policyId,
            ScopedRule sr,
            RuleUpperBound bound,
            String outcome,
            String reason,
            Set<String> flags) {
        PolicySnapshot.Rule rule = sr.rule();
        String uid = ruleUid(rule);
        FindingIdentity id = new FindingIdentity(policyId, uid, sr.inlineParentPath(), List.of());
        return new RuleEvaluation(
                id,
                rule.id(),
                uid,
                rule.number(),
                rule.name(),
                sr.section().id(),
                sr.section().name(),
                sr.inlineParentPath(),
                bound.targetDeviceIds(),
                rule.extras().getOrDefault("from", List.of()),
                rule.extras().getOrDefault("to", List.of()),
                outcome,
                reason,
                null,
                flags,
                null,
                List.of(),
                List.of(),
                List.of(),
                "ORIGINAL");
    }

    // ---- Port-split VIP pre-computation (§5.6) -------------------------------------------------------------------------

    private static List<IpRange> computePortSplitVipRanges(CompiledVersion compiled, List<NatRuleBound> natRules) {
        List<NatRuleBound> portSpecific = natRules.stream()
                .filter(n -> !n.unresolved() && !n.serviceAny() && !n.originalDest().isEmpty())
                .toList();
        if (portSpecific.size() < 2) return List.of();

        List<BitSet> dstZoneSets = new ArrayList<>(portSpecific.size());
        for (NatRuleBound n : portSpecific) {
            ZoneTouches touches = touchZones(compiled, n.translatedDest());
            BitSet bs = new BitSet(compiled.endpointIds.length);
            for (int i = 0; i < touches.count; i++) bs.set(touches.zones[i]);
            dstZoneSets.add(bs);
        }

        List<IpRange> splitRanges = new ArrayList<>();
        for (int i = 0; i < portSpecific.size(); i++) {
            for (int j = i + 1; j < portSpecific.size(); j++) {
                if (dstZoneSets.get(i).equals(dstZoneSets.get(j))) continue;
                List<IpRange> overlap = ZoneMatrixPolicyBridge.intersectIpRanges(
                        portSpecific.get(i).originalDest(), portSpecific.get(j).originalDest());
                splitRanges.addAll(overlap);
            }
        }
        return ZoneMatrixPolicyBridge.mergeIpRanges(splitRanges);
    }

    // ---- Zone-definition gaps (§3.3: unassigned RFC 1918 & IPv6 ULA) ---------------------------------------------------

    private static ZoneDefinitionGap detectZoneDefinitionGap(
            CompiledVersion compiled,
            PreparedPolicy prepared,
            ScopedRule sr,
            RuleUpperBound bound) {
        if (bound.source() == null && bound.destination() == null) return null;
        List<IpRange> explicitV4 = new ArrayList<>();
        List<UlaRange> ulaV6 = new ArrayList<>();
        if (bound.source() != null) {
            explicitV4.addAll(bound.source().explicitIpv4());
            ulaV6.addAll(bound.source().ulaRanges());
        }
        if (bound.destination() != null) {
            explicitV4.addAll(bound.destination().explicitIpv4());
            ulaV6.addAll(bound.destination().ulaRanges());
            for (NatRuleBound nat : prepared.natRules()) {
                if (nat.unresolved() || nat.explicitTranslatedDest().isEmpty()) continue;
                if (!ZoneMatrixPolicyBridge.intersectIpRanges(bound.destination().upperIpv4(), nat.originalDest()).isEmpty()) {
                    explicitV4.addAll(nat.explicitTranslatedDest());
                }
            }
        }

        List<String> unassignedPrivate = new ArrayList<>();
        if (!explicitV4.isEmpty() && !compiled.unassignedRfc1918.isEmpty()) {
            List<IpRange> mergedV4 = ZoneMatrixPolicyBridge.mergeIpRanges(explicitV4);
            for (IpRange hit : ZoneMatrixPolicyBridge.intersectIpRanges(mergedV4, compiled.unassignedRfc1918)) {
                unassignedPrivate.add(formatIpInterval(hit.start(), hit.end()));
            }
        }
        if (!ulaV6.isEmpty() && !compiled.unassignedIpv6Segments.isEmpty()) {
            for (UlaRange ula : ulaV6) {
                for (ZonePartition.Segment seg : compiled.unassignedIpv6Segments) {
                    BigInteger s = ula.start().max(seg.start());
                    BigInteger e = ula.end().min(seg.end());
                    if (s.compareTo(e) <= 0) {
                        String text = s.equals(e)
                                ? ZoneBlock.addressText(ZoneBlock.Family.IPV6, s)
                                : ZoneBlock.addressText(ZoneBlock.Family.IPV6, s) + "-"
                                        + ZoneBlock.addressText(ZoneBlock.Family.IPV6, e);
                        if (!unassignedPrivate.contains(text)) unassignedPrivate.add(text);
                    }
                }
            }
        }
        if (unassignedPrivate.isEmpty()) return null;
        return new ZoneDefinitionGap(
                sr.rule().id(), sr.rule().number(), sr.rule().name(), sr.section().name(), unassignedPrivate);
    }

    // ---- Coverage (§5.2, §5.9, ZM-7, ZM-20) ----------------------------------------------------------------------------

    private static List<CoverageItem> computeCoverageItems(PreparedPolicy prepared) {
        List<CoverageItem> items = new ArrayList<>();
        PolicySnapshot snapshot = prepared.snapshot();
        if (!snapshot.failures().isEmpty()) {
            items.add(new CoverageItem("SNAPSHOT_COLLECTION_INCOMPLETE",
                    "snapshot has collection failures (" + snapshot.failures().size() + ")"));
        }
        if ("PAN".equals(prepared.vendor())) {
            if (!prepared.hasPanIntrazoneNonAllowOverride()) {
                items.add(new CoverageItem("PAN_INTRAZONE_DEFAULT_ALLOW",
                        "intrazone default allow; not evaluated"));
            }
            if (prepared.panoramaSourced()) {
                items.add(new CoverageItem("PAN_LOCAL_RULES_NOT_COLLECTED",
                        "local rules not collected"));
                items.add(new CoverageItem("PAN_DEFAULT_RULE_LEVELS_NOT_COLLECTED",
                        "firewall-level default rule overrides not collected"));
            }
        } else if ("CP".equals(prepared.vendor())) {
            items.add(new CoverageItem("CP_GLOBAL_IMPLIED_RULES_NOT_COLLECTED",
                    "Global Properties implied rules not collected"));
            boolean anyInlineMissingCatchAll = prepared.inlineLayerHasExplicitCatchAll().values().stream()
                    .anyMatch(v -> !Boolean.TRUE.equals(v));
            if (!prepared.allOrderedLayersHaveExplicitCatchAll() || anyInlineMissingCatchAll) {
                items.add(new CoverageItem("CP_IMPLICIT_CLEANUP_NOT_COLLECTED",
                        "implicit cleanup action not collected"));
            }
        }
        return List.copyOf(items);
    }

    // ---- Headline (§7.2, ZM-21, ZM-26, ZM-30) --------------------------------------------------------------------------

    /** Aggregates §7.2 headline across multiple evaluated and non-evaluated policies. */
    public static Headline buildHeadline(List<EvaluationResult> results) {
        if (results == null || results.isEmpty()) {
            return buildHeadline(0, List.of(), "INCOMPLETE", 0, 0, 0, 0);
        }
        int versionNumber = results.get(0).version().versionNumber();
        List<RuleEvaluation> allRules = new ArrayList<>();
        int notEvaluable = 0;
        int unknown = 0;
        int incompletePolicies = 0;
        int notEvaluatedPolicies = 0;
        for (EvaluationResult r : results) {
            if (!r.evaluated()) {
                notEvaluatedPolicies++;
                continue;
            }
            allRules.addAll(r.rules());
            notEvaluable += r.outcomeCounts().getOrDefault(OUTCOME_NOT_EVALUABLE, 0);
            unknown += r.outcomeCounts().getOrDefault(OUTCOME_UNKNOWN, 0);
            if (!"COMPLETE".equals(r.coverage())) {
                incompletePolicies++;
            }
        }
        String coverage = (incompletePolicies == 0 && notEvaluatedPolicies == 0) ? "COMPLETE" : "INCOMPLETE";
        return buildHeadline(versionNumber, allRules, coverage, notEvaluable, unknown, incompletePolicies, notEvaluatedPolicies);
    }

    public static Headline buildHeadline(
            int versionNumber,
            List<RuleEvaluation> rules,
            String coverage,
            int notEvaluableCount,
            int unknownCount,
            int notEvaluatedPolicies) {
        return buildHeadline(versionNumber, rules, coverage, notEvaluableCount, unknownCount,
                "INCOMPLETE".equals(coverage) ? 1 : 0, notEvaluatedPolicies);
    }

    public static Headline buildHeadline(
            int versionNumber,
            List<RuleEvaluation> rules,
            String coverage,
            int notEvaluableCount,
            int unknownCount,
            int incompletePolicies,
            int notEvaluatedPolicies) {
        // Group VIOLATION findings by (policyId, ruleUid) (§7.2, ZM-21).
        record RuleKey(String policyId, String ruleUid) {}
        Map<RuleKey, List<RuleEvaluation>> headlineByRule = new LinkedHashMap<>();
        Set<RuleKey> outsideHeadlineRules = new LinkedHashSet<>();
        Set<String> headlineZonePairs = new LinkedHashSet<>();

        for (RuleEvaluation ev : rules) {
            if (!OUTCOME_VIOLATION.equals(ev.outcome())) continue;
            RuleKey key = new RuleKey(ev.identity().policyId(), ev.ruleUid());
            if (ev.flags().contains("DENIED_BY_OTHER_ORDERED_LAYER")
                    || ev.flags().contains("OTHER_ORDERED_LAYERS_UNKNOWN")) {
                outsideHeadlineRules.add(key);
                continue;
            }
            headlineByRule.computeIfAbsent(key, k -> new ArrayList<>()).add(ev);
            for (CellViolation cv : ev.violatedCells()) {
                headlineZonePairs.add(cv.sourceZone() + "->" + cv.destinationZone());
            }
        }
        // A shared ruleUid that violated in the headline in one scope is already in the headline, so remove from outside count.
        outsideHeadlineRules.removeAll(headlineByRule.keySet());

        int n = headlineByRule.size();
        int p = headlineZonePairs.size();
        int critical = 0;
        int covered = 0;
        int conditional = 0;
        for (List<RuleEvaluation> group : headlineByRule.values()) {
            boolean isCrit = group.stream().anyMatch(e -> "CRITICAL".equals(e.highestSeverity()));
            boolean isCov = group.stream().anyMatch(e ->
                    e.flags().contains("COVERED_BY_EARLIER_DENY") || e.flags().contains("INLINE_LAYER_COVER"));
            boolean isCond = group.stream().anyMatch(e ->
                    e.flags().contains("IDENTITY_IGNORED") || e.flags().contains("TIME_IGNORED")
                            || e.flags().contains("CONDITION_IGNORED"));
            if (isCrit) critical++;
            if (isCov) covered++;
            if (isCond) conditional++;
        }

        int x = outsideHeadlineRules.size();
        boolean exact = "COMPLETE".equals(coverage)
                && incompletePolicies == 0
                && notEvaluableCount == 0
                && unknownCount == 0
                && notEvaluatedPolicies == 0;

        String text;
        if (n == 0) {
            // §7.2, ZM-7, ZM-26: Never "0" unless every covered policy is COMPLETE and nothing is NOT_EVALUABLE or UNKNOWN.
            text = exact
                    ? "0 rules violate the zone matrix (v" + versionNumber + ")"
                    : "no proven violations in evaluated rules";
        } else {
            String countPrefix = exact ? Integer.toString(n) : "at least " + n;
            String ruleWord = n == 1 ? "rule violates" : "rules violate";
            String pairWord = p == 1 ? "zone pair" : "zone pairs";
            text = countPrefix + " " + ruleWord + " the zone matrix (v" + versionNumber + ") across "
                    + p + " " + pairWord + "; " + critical + " critical, of which " + covered
                    + " covered by an earlier rule and " + conditional + " conditional (identity / time)";
        }
        String outsideText = x > 0
                ? "+" + x + " outside the headline (other ordered layer denies or unknown)"
                : null;

        return new Headline(
                n, p, critical, covered, conditional, x,
                notEvaluableCount, unknownCount, incompletePolicies, notEvaluatedPolicies,
                exact, text, outsideText);
    }

    // ---- Primitive partition lookup (exact interval arithmetic, zero sampling) -----------------------------------------

    private static final class ZoneTouches {
        static final ZoneTouches EMPTY = new ZoneTouches(new int[0], 0, new boolean[0], new long[0], new long[0]);
        final int[] zones;
        final int count;
        final boolean[] present;
        final long[] firstStart;
        final long[] firstEnd;

        ZoneTouches(int[] zones, int count, boolean[] present, long[] firstStart, long[] firstEnd) {
            this.zones = zones;
            this.count = count;
            this.present = present;
            this.firstStart = firstStart;
            this.firstEnd = firstEnd;
        }

        boolean has(int zoneIdx) {
            return zoneIdx < present.length && present[zoneIdx];
        }
    }

    private static ZoneTouches touchZones(CompiledVersion compiled, List<IpRange> ranges) {
        if (ranges.isEmpty()) return ZoneTouches.EMPTY;
        int epCount = compiled.endpointIds.length;
        boolean[] present = new boolean[epCount];
        long[] firstStart = new long[epCount];
        long[] firstEnd = new long[epCount];
        int[] touched = new int[epCount];
        int count = 0;

        long[] segStarts = compiled.segStarts;
        long[] segEnds = compiled.segEnds;
        int[] segEp = compiled.segEndpoint;
        int segLen = segStarts.length;

        for (IpRange r : ranges) {
            int idx = firstSegmentEndingAtOrAfter(segEnds, r.start());
            while (idx < segLen && segStarts[idx] <= r.end()) {
                int z = segEp[idx];
                if (!present[z]) {
                    present[z] = true;
                    firstStart[z] = Math.max(r.start(), segStarts[idx]);
                    firstEnd[z] = Math.min(r.end(), segEnds[idx]);
                    touched[count++] = z;
                }
                idx++;
            }
        }
        Arrays.sort(touched, 0, count);
        return new ZoneTouches(touched, count, present, firstStart, firstEnd);
    }

    private static int firstSegmentEndingAtOrAfter(long[] segEnds, long target) {
        int low = 0, high = segEnds.length - 1, ans = segEnds.length;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (segEnds[mid] >= target) {
                ans = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return ans;
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "LOW" -> 0;
            case "MEDIUM" -> 1;
            case "HIGH" -> 2;
            case "CRITICAL" -> 3;
            default -> 1;
        };
    }

    private static String ruleUid(PolicySnapshot.Rule rule) {
        return rule.uuid() != null && !rule.uuid().isBlank() ? rule.uuid() : rule.id();
    }

    private static List<String> formatPortDiff(List<PortRange> tcp, List<PortRange> udp) {
        List<String> out = new ArrayList<>(tcp.size() + udp.size());
        for (PortRange r : tcp) out.add(r.low() == r.high() ? "tcp/" + r.low() : "tcp/" + r.low() + "-" + r.high());
        for (PortRange r : udp) out.add(r.low() == r.high() ? "udp/" + r.low() : "udp/" + r.low() + "-" + r.high());
        return List.copyOf(out);
    }

    private static String formatIpInterval(long start, long end) {
        String a = ipv4Text(start);
        return start == end ? a : a + "-" + ipv4Text(end);
    }

    private static String ipv4Text(long v) {
        return ((v >>> 24) & 255) + "." + ((v >>> 16) & 255) + "." + ((v >>> 8) & 255) + "." + (v & 255);
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        List<T> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }
}
