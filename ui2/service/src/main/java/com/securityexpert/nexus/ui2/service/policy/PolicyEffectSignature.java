package com.securityexpert.nexus.ui2.service.policy;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import java.util.*;

/**
 * G3 of POLICY_INSIGHTS_DESIGN_2026_11_24.md (FROZEN): the effect signature of a rule -- action, inline-layer reference,
 * profile references and log setting -- so a reorder can tell "the verdict stays" from "the verdict and the inspection
 * stay" (decision record Q2). Pure: reads one stored rule, nothing else.
 *
 * <p>Only what the snapshot model already stores is read; no key is invented:
 * <ul>
 * <li><b>Verdict part</b>: {@link Rule#action()} and the {@code inline-layer} extra, compared by
 * {@link PolicyPartialShadow#sameAction} (which is {@link PolicyHygiene#sameAction} with an Apply Layer rule never equal
 * to anything).</li>
 * <li><b>Profile references</b>: the snapshot model's {@code profile-setting/*} extras (opaque values, exact equality).
 * Palo Alto's {@code PanoramaPolicyMapper} collects {@code profile-setting/group} and six individual
 * {@code profile-setting/profiles/*} types while omitting {@code data-filtering} and recording no marker for other
 * {@code <profile-setting>} children. Because PAN-OS {@code <profile-setting>} is a choice of {@code <group>} or
 * {@code <profiles>}, only a valid {@code profile-setting/group} with no other {@code profile-setting/*} keys is known
 * on Palo Alto; absent {@code profile-setting/*}, individual {@code profile-setting/profiles/*}, or empty/{@code UNKNOWN}
 * values fail closed to {@link Part#UNKNOWN}. The Check Point mapper stores no profile reference on an
 * access rule (design F21 and section 7), so for Check Point a rule carrying no such extra has {@link Part#UNKNOWN}
 * (never collected, never "known and empty"). {@link #sameFull} compares profile extras when collected, and for Check
 * Point claims nothing about profiles when neither rule has any.</li>
 * <li><b>Log setting</b>: Check Point's track type ({@link Rule#log()}, the resolved track name) plus the
 * {@code track-*} extras; Palo Alto's {@link Rule#log()} (strictly {@code start=(yes|no), end=(yes|no)}, failing closed
 * on absent, malformed, or {@code UNKNOWN} values) plus the {@code log-setting} extra (log-forwarding profile, omitted
 * when none is configured). An uncollected or {@code UNKNOWN} value yields {@link Part#UNKNOWN}.</li>
 * </ul>
 * A part the snapshot did not collect is {@link Part#UNKNOWN}, and UNKNOWN is never equal to anything, another UNKNOWN
 * included. Vendors other than Check Point and Palo Alto are not mapped in this step: every part but the verdict is
 * UNKNOWN there.
 */
final class PolicyEffectSignature {
    private PolicyEffectSignature() {}
    static final String PROFILE_PREFIX = "profile-setting/", PROFILE_GROUP = "profile-setting/group",
        TRACK_PREFIX = "track-", LOG_SETTING = "log-setting";

    /** One signature part: opaque values compared by exact equality, or UNKNOWN (not collected), which equals nothing. */
    record Part(boolean known, List<String> values) {
        static final Part UNKNOWN = new Part(false, List.of());
        Part { values = List.copyOf(values); }
        boolean same(Part other) { return known && other.known && values.equals(other.values); }
    }
    record Signature(String action, List<String> inlineLayer, Part profiles, Part log) {}

    static Signature of(String vendor, Rule rule) {
        var inline = rule.extras().getOrDefault("inline-layer", List.of());
        if ("CP".equals(vendor)) return new Signature(rule.action(), inline, cpProfiles(rule), cpLog(rule));
        if ("PAN".equals(vendor)) return new Signature(rule.action(), inline, panProfiles(rule), panLog(rule));
        return new Signature(rule.action(), inline, Part.UNKNOWN, Part.UNKNOWN);
    }
    /** Same verdict effect: known equal action and inline layer, never an Apply Layer rule. */
    static boolean sameVerdict(Rule a, Rule b) {
        return knownAction(a) && knownAction(b) && PolicyPartialShadow.sameAction(a, b);
    }
    /**
     * Same full signature: the same verdict effect and a collected, equal log setting. Profile references are compared
     * when the vendor collects them; Check Point collects none, so there the part is not evaluated and the result says
     * nothing about profiles (the I7 evidence text says so).
     */
    static boolean sameFull(String vendor, Rule a, Rule b) {
        if (!sameVerdict(a, b)) return false;
        var x = of(vendor, a); var y = of(vendor, b);
        boolean profilesOk = !x.profiles().known() && !y.profiles().known() && "CP".equals(vendor) || x.profiles().same(y.profiles());
        return profilesOk && x.log().same(y.log());
    }

    private static boolean knownAction(Rule rule) {
        String action = rule.action();
        return action != null && !action.isBlank() && !"UNKNOWN".equals(action);
    }

    /** Check Point: profile references present on the rule are compared; none present means not collected (UNKNOWN). */
    private static Part cpProfiles(Rule rule) {
        var part = keyed(rule, PROFILE_PREFIX, null);
        return part.values().isEmpty() ? Part.UNKNOWN : part;
    }
    private static Part cpLog(Rule rule) {
        String type = rule.log();
        if (type == null || type.isBlank() || "UNKNOWN".equals(type)) return Part.UNKNOWN;
        return keyed(rule, TRACK_PREFIX, type);
    }

    /**
     * Palo Alto: {@code PanoramaPolicyMapper} omits {@code profile-setting/profiles/data-filtering} and records no marker
     * for other {@code <profile-setting>} children, so neither absent {@code profile-setting/*} nor individual
     * {@code profile-setting/profiles/*} proves no uncollected profile is attached. Because PAN-OS {@code <profile-setting>}
     * is a choice of {@code <group>} or {@code <profiles>}, only a valid {@code profile-setting/group} with no other
     * {@code profile-setting/*} keys is known; everything else fails closed to {@link Part#UNKNOWN}.
     */
    private static Part panProfiles(Rule rule) {
        List<String> group = null;
        for (var entry : rule.extras().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(PROFILE_PREFIX)) continue;
            if (!PROFILE_GROUP.equals(key) || !validExtraValues(entry.getValue())) return Part.UNKNOWN;
            group = entry.getValue();
        }
        return group == null ? Part.UNKNOWN : new Part(true, List.of(PROFILE_GROUP + "=" + String.join(",", group)));
    }
    /** Palo Alto: {@link Rule#log()} plus optional {@code log-setting} extra; any uncollected/UNKNOWN/malformed token fails closed. */
    private static Part panLog(Rule rule) {
        String type = rule.log();
        if (!validPanLogType(type)) return Part.UNKNOWN;
        List<String> values = new ArrayList<>();
        values.add("type=" + type);
        if (rule.extras().containsKey(LOG_SETTING)) {
            List<String> forwarding = rule.extras().get(LOG_SETTING);
            if (!validExtraValues(forwarding)) return Part.UNKNOWN;
            values.add(LOG_SETTING + "=" + String.join(",", forwarding));
        }
        return new Part(true, values);
    }
    private static boolean validPanLogType(String type) {
        return type != null && type.matches("start=(yes|no), end=(yes|no)");
    }
    private static boolean validExtraValues(List<String> list) {
        if (list == null || list.isEmpty()) return false;
        for (String v : list) if (v == null || v.isBlank() || "UNKNOWN".equals(v)) return false;
        return true;
    }

    /** Every extra under {@code prefix} as "key=values", sorted, after an optional leading type. */
    private static Part keyed(Rule rule, String prefix, String type) {
        List<String> values = new ArrayList<>();
        if (type != null) values.add("type=" + type);
        new TreeMap<>(rule.extras()).forEach((key, list) -> { if (key.startsWith(prefix)) values.add(key + "=" + String.join(",", list)); });
        return new Part(true, values);
    }
}
