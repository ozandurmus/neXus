package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The aiview projection of cutover segments and assessments: an allowlist by field name. Addresses and blocks go
 * through the subnet-preserving masker, rule views through {@link PolicyPrivacy} (the rule viewer's pseudonyms),
 * closed vocabularies pass only when they are members, and every field not named here is withheld.
 */
public final class CutoverPrivacy {
    private CutoverPrivacy() {}
    public static final String WITHHELD = "Withheld in AIView";
    private static final Set<String> OPAQUE = Set.of("segmentId", "assessmentId", "targetDeviceId", "targetPolicyId", "oldPolicyId",
        "oldDeviceId", "engineVersion", "deviceId", "topologySnapshotId");
    private static final Set<String> RULES = Set.of("decidingRule", "oldPathRule", "conditionalRule");
    private static final Set<String> TIMES = Set.of("firstSeen", "lastSeen", "periodStart", "periodEnd", "createdAt", "updatedAt",
        "targetCollectedAt", "oldCollectedAt", "currentCollectedAt");
    private static final Set<String> ADDRESSES = Set.of("flowSource", "flowDestination", "flowSources");
    private static final Set<String> BLOCKS = Set.of("blocks", "peerBlocks");
    private static final Set<String> ZONES = Set.of("segmentZone", "peerZone");
    private static final Set<String> ACTORS = Set.of("actor", "createdBy", "updatedBy");
    public static final Set<String> STALE_REASONS = Set.of("SEGMENT_CHANGED", "TARGET_POLICY_CHANGED", "OLD_POLICY_CHANGED", "POLICY_NOT_FOUND", "TOPOLOGY_CHANGED");
    private static final Set<String> PARSE_REASONS = Set.of("column count mismatch", "invalid count",
        "invalid time (use ISO-8601 UTC or epoch seconds/milliseconds)", "invalid IP address", "invalid destination port",
        "invalid protocol", "invalid zone");
    private static final Set<String> VENDORS = Set.of("CP", "PAN", "");
    private static final List<Pattern> HEADLINES = List.of(
        Pattern.compile(Pattern.quote(CutoverAssessment.HEADLINE_BLOCKERS).replace("%d", "\\E[0-9]{1,9}\\Q")),
        Pattern.compile(Pattern.quote(CutoverAssessment.HEADLINE_NOT_DECIDED).replace("%d", "\\E[0-9]{1,9}\\Q")),
        Pattern.compile(Pattern.quote(CutoverAssessment.HEADLINE_NO_BLOCKERS).replace("%d", "\\E[0-9]{1,9}\\Q")),
        Pattern.compile(Pattern.quote(CutoverAssessment.HEADLINE_NO_EVIDENCE)));

    public static Map<String, Object> mask(Map<String, Object> body, TopologyNamePseudonymizer names, SubnetPreservingIpMasker ips) {
        return mask(body, names, ips, Set.of());
    }

    /** {@code pathReasons}: the closed set of path-engine reasons a routed-path decision may carry. */
    public static Map<String, Object> mask(Map<String, Object> body, TopologyNamePseudonymizer names, SubnetPreservingIpMasker ips,
            Set<String> pathReasons) {
        @SuppressWarnings("unchecked") Map<String, Object> out = (Map<String, Object>) mask(body, "", names, ips, pathReasons);
        return out;
    }

    static Object mask(Object value, String key, TopologyNamePseudonymizer names, SubnetPreservingIpMasker ips) {
        return mask(value, key, names, ips, Set.of());
    }

    private static Object mask(Object value, String key, TopologyNamePseudonymizer names, SubnetPreservingIpMasker ips, Set<String> pathReasons) {
        if (value == null || value instanceof Boolean || value instanceof Number) return value;
        if (RULES.contains(key) && value instanceof Map<?, ?>) return PolicyPrivacy.mask(value, "", names, ips);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), mask(v, String.valueOf(k), names, ips, pathReasons)));
            return out;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> mask(item, key, names, ips, pathReasons)).toList();
        if (!(value instanceof String text)) return WITHHELD;
        if (text.isEmpty()) return text;
        if (OPAQUE.contains(key)) return text;
        if (TIMES.contains(key)) {
            try { return java.time.Instant.parse(text).toString(); } catch (RuntimeException invalid) { return ""; }
        }
        if (ADDRESSES.contains(key)) return text.matches("[0-9.]{7,15}") ? ips.maskText(text) : WITHHELD;
        if (key.equals("sourceRange")) {
            String[] ends = text.split("-");
            return ends.length == 2 && ends[0].matches("[0-9.]{7,15}") && ends[1].matches("[0-9.]{7,15}")
                ? ips.maskText(ends[0]) + "-" + ips.maskText(ends[1]) : WITHHELD;
        }
        if (BLOCKS.contains(key)) return text.matches("[0-9.]{7,15}/[0-9]{1,2}") ? ips.mask(text) : WITHHELD;
        if (ZONES.contains(key)) return PolicyPrivacy.mask(text, "from", names, ips);
        if (ACTORS.contains(key)) return names.maskPolicyName("last-modifier", text);
        if (key.equals("name")) return names.maskPolicyName("segment", text);
        if (key.equals("decision") || key.equals("oldPathDecision")) return CutoverAssessment.DECISIONS.contains(text) ? text : "NOT_DECIDED";
        if (key.equals("decisionReason") || key.equals("oldPathReason"))
            return CutoverAssessment.REASONS.contains(text) || PolicyHygiene.REASONS.contains(text) || pathReasons.contains(text)
                ? text : "unsupported rule constraint";
        if (key.equals("targetSource")) return CutoverAssessment.TARGET_SOURCES.contains(text) ? text : "UNKNOWN";
        if (key.equals("hopVerdict")) return Set.of("ALLOWED", "DENIED", "UNKNOWN", "CONDITIONAL").contains(text) ? text : "UNKNOWN";
        if (key.equals("direction")) return CutoverAssessment.DIRECTIONS.contains(text) ? text : "UNKNOWN";
        if (key.equals("flags")) return CutoverAssessment.FLAGS.contains(text) ? text : "UNKNOWN";
        if (key.equals("droppedConstraints")) return CutoverAssessment.CONSTRAINT_KINDS.contains(text) ? text : "UNKNOWN";
        if (key.equals("readiness")) return CutoverAssessment.READINESS.contains(text) ? text : "NOT_DECIDED";
        if (key.equals("status")) return CutoverAssessment.STATUSES.contains(text) ? text : "INCOMPLETE";
        if (key.equals("code")) return CutoverAssessment.COVERAGE.contains(text) ? text : "UNKNOWN";
        if (key.equals("staleReasons")) return STALE_REASONS.contains(text) ? text : "UNKNOWN";
        if (key.equals("statReason")) return PARSE_REASONS.contains(text) ? text : "UNKNOWN";
        if (key.equals("actionVocabulary")) return CutoverAssessment.DROP_ACTIONS.contains(text) ? text : "UNKNOWN";
        if (key.equals("protocol")) return text.matches("[a-z0-9-]{1,20}") ? text : "UNKNOWN";
        if (key.equals("vendor") || key.equals("targetVendor")) return VENDORS.contains(text) ? text : "UNKNOWN";
        if (key.equals("kind")) return Set.of("FLOW", "MISSING_RULE").contains(text) ? text : "UNKNOWN";
        if (key.equals("headline")) return HEADLINES.stream().anyMatch(p -> p.matcher(text).matches()) ? text : WITHHELD;
        return WITHHELD;
    }
}
