package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;

/**
 * The reference of one Palo Alto rule as the Panorama config log and the policy history both can name it (design
 * {@code PAN_CHANGE_ACTOR_FROM_PANORAMA_CONFIG_LOG_2026_11_11.md} section 4.3; PO decision record 2026-11-12).
 *
 * <p>The log entry names a rule by its configuration {@code path}; the policy history names it by the snapshot section
 * ({@code Pre rules}, {@code Post NAT rules}, ...), the scope that owns the section (Shared or a device group) and the
 * rule name. Both are reduced to the same canonical string and only a keyed hash of it is ever stored, so no device-group
 * or rule name reaches the evidence table.</p>
 *
 * <p><b>UNKNOWN (design section 8):</b> the path form below is the one the vendor's own configuration paths use and the
 * one {@link PanoramaConfigLogReader} already reads for the device group; whether a Panorama config-log entry for a rule
 * edit uses exactly it is unmeasured. A path that does not match is simply not a reference (empty), never a guess.</p>
 */
public final class PanRuleRef {
    /** Reserved value for the Shared scope; a device group is {@code dg:<name>}. */
    static final String SHARED = "shared";
    private static final String DOMAIN = "policy-change-rule-v1";
    private static final String QUOTED = "(?:'([^'\\r\\n]{1,255})'|\"([^\"\\r\\n]{1,255})\")";
    private static final String RULEBASE = "/(pre|post)-rulebase/(security|nat)/rules/entry\\[@name=" + QUOTED + "\\]";
    private static final Pattern DEVICE_GROUP_RULE = Pattern.compile(
            "^/config/devices/entry\\[@name='localhost\\.localdomain'\\]/device-group/entry\\[@name=" + QUOTED + "\\]" + RULEBASE);
    private static final Pattern SHARED_RULE = Pattern.compile("^/config/shared" + RULEBASE);
    /** Marker {@code PanLogRedactor} writes in place of a redacted value; a reference built from one would be a guess. */
    private static final String REDACTED = PanLogRedactor.REDACTED;

    /** @param scope {@code shared} or {@code dg:<name>}; @param phase pre, post or local; @param kind security or nat */
    public record Parts(String scope, String phase, String kind, String rule) {
        /** Canonical text the keyed hash is computed over; fields are separated by NUL, which no name can carry. */
        String canonical() { return scope + "\u0000" + phase + "\u0000" + kind + "\u0000" + rule; }
    }

    private PanRuleRef() {}

    /** The rule a configuration path names, or empty when the path names no rule of a Panorama rulebase. */
    public static Optional<Parts> fromPath(String path) {
        if (path == null || path.isEmpty()) return Optional.empty();
        Matcher dg = DEVICE_GROUP_RULE.matcher(path);
        if (dg.matches()) return build("dg:" + (dg.group(1) != null ? dg.group(1) : dg.group(2)), dg.group(3), dg.group(4),
                dg.group(5) != null ? dg.group(5) : dg.group(6));
        Matcher shared = SHARED_RULE.matcher(path);
        if (shared.matches()) return build(SHARED, shared.group(1), shared.group(2),
                shared.group(3) != null ? shared.group(3) : shared.group(4));
        return Optional.empty();
    }

    /**
     * The rule a policy-history rule names. {@code scopeName} is the snapshot section's source ({@code Shared} or a device
     * group name), {@code sectionName} the section's name.
     */
    public static Optional<Parts> fromSnapshot(String scopeName, String sectionName, String ruleName) {
        if (scopeName == null || scopeName.isEmpty() || sectionName == null) return Optional.empty();
        String phase;
        String kind;
        switch (sectionName) {
            case "Pre rules" -> { phase = "pre"; kind = "security"; }
            case "Post rules" -> { phase = "post"; kind = "security"; }
            case "Pre NAT rules" -> { phase = "pre"; kind = "nat"; }
            case "Post NAT rules" -> { phase = "post"; kind = "nat"; }
            default -> { return Optional.empty(); } // local and default sections have no Panorama rulebase path
        }
        return build("Shared".equals(scopeName) ? SHARED : "dg:" + scopeName, phase, kind, ruleName);
    }

    private static Optional<Parts> build(String scope, String phase, String kind, String rule) {
        if (rule == null || rule.isEmpty() || scope.contains(REDACTED) || rule.contains(REDACTED)) return Optional.empty();
        return Optional.of(new Parts(scope, phase, kind, rule));
    }

    /** The keyed hash stored as {@code rule_ref_hmac}. */
    public static String hmac(HostnameFingerprint fingerprint, Parts parts) {
        byte[] bytes = parts.canonical().getBytes(StandardCharsets.UTF_8);
        return fingerprint.ofContent(DOMAIN, bytes, 0, bytes.length);
    }
}
