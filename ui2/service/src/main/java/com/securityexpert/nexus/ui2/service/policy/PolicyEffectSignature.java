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
 * The Check Point mapper stores no profile reference on an access rule (design F21 and section 7 name profile and log
 * settings as signature parts for Palo Alto; for Check Point it stores track fields), so for Check Point a rule carrying
 * no such extra has {@link Part#UNKNOWN} (never collected, never "known and empty"). {@link #sameFull} compares profile
 * extras that are present, and for Check Point claims nothing about profiles when neither rule has any.</li>
 * <li><b>Log setting</b>: Check Point's track type ({@link Rule#log()}, the resolved track name) plus the
 * {@code track-*} extras the mapper fills. A track type the snapshot did not resolve ({@code UNKNOWN}, blank, absent) is
 * not collected.</li>
 * </ul>
 * A part the snapshot did not collect is {@link Part#UNKNOWN}, and UNKNOWN is never equal to anything, another UNKNOWN
 * included. Vendors other than Check Point are not mapped in this step: every part but the verdict is UNKNOWN there.
 */
final class PolicyEffectSignature {
    private PolicyEffectSignature() {}
    static final String PROFILE_PREFIX = "profile-setting/", TRACK_PREFIX = "track-";

    /** One signature part: opaque values compared by exact equality, or UNKNOWN (not collected), which equals nothing. */
    record Part(boolean known, List<String> values) {
        static final Part UNKNOWN = new Part(false, List.of());
        Part { values = List.copyOf(values); }
        boolean same(Part other) { return known && other.known && values.equals(other.values); }
    }
    record Signature(String action, List<String> inlineLayer, Part profiles, Part log) {}

    static Signature of(String vendor, Rule rule) {
        var inline = rule.extras().getOrDefault("inline-layer", List.of());
        if (!"CP".equals(vendor)) return new Signature(rule.action(), inline, Part.UNKNOWN, Part.UNKNOWN);
        return new Signature(rule.action(), inline, profiles(rule), log(rule));
    }
    /** Same verdict effect: same action and inline layer, never an Apply Layer rule. */
    static boolean sameVerdict(Rule a, Rule b) { return PolicyPartialShadow.sameAction(a, b); }
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

    /** Profile references present on the rule are compared; none present means the part was not collected: UNKNOWN. */
    private static Part profiles(Rule rule) {
        var part = keyed(rule, PROFILE_PREFIX, null);
        return part.values().isEmpty() ? Part.UNKNOWN : part;
    }
    private static Part log(Rule rule) {
        String type = rule.log();
        if (type == null || type.isBlank() || "UNKNOWN".equals(type)) return Part.UNKNOWN;
        return keyed(rule, TRACK_PREFIX, type);
    }
    /** Every extra under {@code prefix} as "key=values", sorted, after an optional leading type. */
    private static Part keyed(Rule rule, String prefix, String type) {
        List<String> values = new ArrayList<>();
        if (type != null) values.add("type=" + type);
        new TreeMap<>(rule.extras()).forEach((key, list) -> { if (key.startsWith(prefix)) values.add(key + "=" + String.join(",", list)); });
        return new Part(true, values);
    }
}
