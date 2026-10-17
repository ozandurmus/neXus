package com.securityexpert.nexus.ui2.worker.policy;

import java.time.Duration;
import java.util.*;

/**
 * Which Check Point domains a scheduled automatic run may collect "changed only" (reuse an unchanged domain by its
 * last published session). Every other domain of a scheduled run is read fully. Manual requests are not affected.
 *
 * <p>Setting {@code ui2.policy.cp.changed-only-domains} / {@code UI2_POLICY_CP_CHANGED_ONLY_DOMAINS}: comma-separated
 * opaque domain references (the policy container id), {@code *} for every domain, or {@code none}. The default is the
 * partial trial (PO 2026-10-18): exactly one domain, the smallest by rule count in the last complete run when it was
 * chosen (read-only query, 2026-10-18: 917 rules in 2 packages). Widen the setting to extend the trial.
 *
 * <p>{@code ui2.policy.cp.changed-only-max-age-hours} / {@code UI2_POLICY_CP_CHANGED_ONLY_MAX_AGE_HOURS} (default 24):
 * a domain is reused only while its reused evidence was read at most this long ago, so hit counts on a reused
 * domain are never older than one daily full run, and a domain read before a pause is read fully again.
 */
final class CpChangedOnlyDomains {
    static final String TRIAL_DOMAIN = "09b087a8-d1bc-30ce-bcf9-5cd5027ab10b";
    private static final String OPAQUE_REF = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final boolean all;
    private final Set<String> domains;
    private final Duration maxAge;

    private CpChangedOnlyDomains(boolean all, Set<String> domains, Duration maxAge) {
        this.all = all; this.domains = Set.copyOf(domains); this.maxAge = maxAge;
    }

    static CpChangedOnlyDomains configured() {
        return parse(System.getProperty("ui2.policy.cp.changed-only-domains",
                System.getenv().getOrDefault("UI2_POLICY_CP_CHANGED_ONLY_DOMAINS", TRIAL_DOMAIN)),
            System.getProperty("ui2.policy.cp.changed-only-max-age-hours",
                System.getenv().getOrDefault("UI2_POLICY_CP_CHANGED_ONLY_MAX_AGE_HOURS", "24")));
    }

    /** Fails closed at construction: an unreadable value is a configuration error, never a silent widening. */
    static CpChangedOnlyDomains parse(String value, String maxAgeHours) {
        long hours;
        try { hours = Long.parseLong(maxAgeHours.trim()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("POLICY_CP_CHANGED_ONLY_MAX_AGE_INVALID"); }
        if (hours < 1 || hours > 168) throw new IllegalArgumentException("POLICY_CP_CHANGED_ONLY_MAX_AGE_INVALID");
        String text = value == null ? "" : value.trim();
        if (text.equals("*")) return new CpChangedOnlyDomains(true, Set.of(), Duration.ofHours(hours));
        if (text.isEmpty() || text.equalsIgnoreCase("none")) return new CpChangedOnlyDomains(false, Set.of(), Duration.ofHours(hours));
        Set<String> refs = new LinkedHashSet<>();
        for (String part : text.split(",", -1)) {
            String ref = part.trim();
            // Opaque references are compared exactly; no case or format normalization.
            if (!ref.matches(OPAQUE_REF)) throw new IllegalArgumentException("POLICY_CP_CHANGED_ONLY_DOMAINS_INVALID");
            refs.add(ref);
        }
        return new CpChangedOnlyDomains(false, refs, Duration.ofHours(hours));
    }

    boolean includes(String domainRef) { return all || domains.contains(domainRef); }
    boolean none() { return !all && domains.isEmpty(); }
    Duration maxAge() { return maxAge; }
    @Override public String toString() { return all ? "*" : domains.isEmpty() ? "none" : domains.size() + " domain(s)"; }
}
