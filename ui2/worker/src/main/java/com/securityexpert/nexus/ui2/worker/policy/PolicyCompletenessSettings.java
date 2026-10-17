package com.securityexpert.nexus.ui2.worker.policy;

import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The three trial settings of the policy completeness readers (PO approval 2026-10-18, partial-first rule: one device or one
 * domain, one call). Every reader is OFF unless its setting names its one trial target; an unset, blank or malformed
 * value is OFF (fail closed). Each value is an opaque reference, never a name:
 * <ul>
 *   <li>{@code UI2_POLICY_PAN_CONFIG_LOG_TRIAL_SOURCE} (property {@code ui2.policy.pan.config-log.trial-source}): the
 *       device id of the one Panorama whose config log P-2/P-3/P-4 may read.</li>
 *   <li>{@code UI2_POLICY_CP_SHOW_CHANGES_TRIAL_DOMAIN} (property {@code ui2.policy.cp.show-changes.trial-domain}): the
 *       opaque container ref of the one Check Point domain C-1/C-2 may read (the {@code domainRef} of a policy request).</li>
 *   <li>{@code UI2_POLICY_PAN_NAT_HITS_TRIAL_FIREWALL} (property {@code ui2.policy.pan.nat-hits.trial-firewall}): the device
 *       id of the one firewall P-1 may read, first VSYS context only, one call.</li>
 * </ul>
 */
public record PolicyCompletenessSettings(Optional<String> panConfigLogTrialSource, Optional<String> cpShowChangesTrialDomain,
        Optional<String> panNatHitsTrialFirewall) {
    public static final String PAN_CONFIG_LOG_ENV = "UI2_POLICY_PAN_CONFIG_LOG_TRIAL_SOURCE";
    public static final String PAN_CONFIG_LOG_PROPERTY = "ui2.policy.pan.config-log.trial-source";
    public static final String CP_SHOW_CHANGES_ENV = "UI2_POLICY_CP_SHOW_CHANGES_TRIAL_DOMAIN";
    public static final String CP_SHOW_CHANGES_PROPERTY = "ui2.policy.cp.show-changes.trial-domain";
    public static final String PAN_NAT_HITS_ENV = "UI2_POLICY_PAN_NAT_HITS_TRIAL_FIREWALL";
    public static final String PAN_NAT_HITS_PROPERTY = "ui2.policy.pan.nat-hits.trial-firewall";

    private static final Pattern OPAQUE_REF = Pattern.compile("[A-Za-z0-9._:-]{1,80}");

    public static final PolicyCompletenessSettings OFF =
            new PolicyCompletenessSettings(Optional.empty(), Optional.empty(), Optional.empty());

    public PolicyCompletenessSettings {
        panConfigLogTrialSource = valid(panConfigLogTrialSource);
        cpShowChangesTrialDomain = valid(cpShowChangesTrialDomain);
        panNatHitsTrialFirewall = valid(panNatHitsTrialFirewall);
    }

    public static PolicyCompletenessSettings fromEnvironment() {
        return from(System::getProperty, System::getenv);
    }

    static PolicyCompletenessSettings from(Function<String, String> properties, Function<String, String> environment) {
        return new PolicyCompletenessSettings(
                setting(properties, environment, PAN_CONFIG_LOG_PROPERTY, PAN_CONFIG_LOG_ENV),
                setting(properties, environment, CP_SHOW_CHANGES_PROPERTY, CP_SHOW_CHANGES_ENV),
                setting(properties, environment, PAN_NAT_HITS_PROPERTY, PAN_NAT_HITS_ENV));
    }

    private static Optional<String> setting(Function<String, String> properties, Function<String, String> environment,
            String property, String variable) {
        String value = properties.apply(property);
        if (value == null) value = environment.apply(variable);
        return Optional.ofNullable(value);
    }

    private static Optional<String> valid(Optional<String> value) {
        return value.map(String::strip).filter(v -> OPAQUE_REF.matcher(v).matches());
    }

    public boolean anyEnabled() {
        return panConfigLogTrialSource.isPresent() || cpShowChangesTrialDomain.isPresent() || panNatHitsTrialFirewall.isPresent();
    }

    /** True only when the setting names exactly this Panorama source. */
    public boolean panConfigLogTargets(String sourceId) { return panConfigLogTrialSource.filter(sourceId::equals).isPresent(); }
    /** True only when the setting names exactly this domain container ref. */
    public boolean cpShowChangesTargets(String containerRef) { return cpShowChangesTrialDomain.filter(containerRef::equals).isPresent(); }
    /** True only when the setting names exactly this firewall device. */
    public boolean panNatHitsTargets(String deviceId) { return panNatHitsTrialFirewall.filter(deviceId::equals).isPresent(); }

    @Override public String toString() {
        return "PolicyCompletenessSettings[panConfigLog=" + panConfigLogTrialSource.isPresent() + ", cpShowChanges="
                + cpShowChangesTrialDomain.isPresent() + ", panNatHits=" + panNatHitsTrialFirewall.isPresent() + "]";
    }
}
