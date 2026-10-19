package com.securityexpert.nexus.ui2.service.policy;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.policy.PolicySchedule;
import java.time.Instant;
import java.util.*;

final class PolicyRuleMetrics {
    record Time(String status, List<PolicySchedule> schedules, boolean expiring) {}
    static Time time(Rule rule, Map<String, PolicyObject> objects, Instant now) { return time(rule, objects, now, ""); }
    static Time time(Rule rule, Map<String, PolicyObject> objects, Instant now, String vendor) {
        return time(rule, objects, now, vendor, new int[]{2048});
    }
    static Time time(Rule rule, Map<String, PolicyObject> objects, Instant now, String vendor, int[] budget) {
        List<String> refs = new ArrayList<>(rule.extras().getOrDefault("time", List.of()));
        refs.addAll(rule.extras().getOrDefault("schedule", List.of()));
        if (refs.isEmpty()) return new Time((vendor.equals("CP") || rule.extras().containsKey("time-uncollected")) ? "unknown" : "always", List.of(), false);
        List<PolicySchedule> schedules = new ArrayList<>();
        boolean known = true, any = false;
        for (String id : refs) {
            if (--budget[0] < 0) { known = false; break; }
            var o = objects.get(id);
            if (o != null && o.status().equals("RESOLVED") && o.type().equals("any")) any = true;
            else known &= schedules(id, objects, schedules, new HashSet<>(), budget);
        }
        if (any) return new Time("always", List.of(), false);
        List<String> states = schedules.stream().map(s -> s.status(now)).toList();
        String status = states.contains("active") ? "active" : states.contains("always") ? "always"
                : !known || states.isEmpty() || states.contains("unknown") ? "unknown"
                : states.contains("upcoming") ? "upcoming" : "expired";
        // Union: expiry is meaningful only when every alternative has a finite end.
        boolean expiring = known && !schedules.isEmpty() && schedules.stream().allMatch(s -> s.expiring(now) || s.status(now).equals("expired")) && !status.equals("expired");
        return new Time(status, schedules, expiring);
    }
    private static boolean schedules(String id, Map<String, PolicyObject> objects, List<PolicySchedule> out, Set<String> path, int[] budget) {
        var o = objects.get(id);
        if (--budget[0] < 0 || o == null || !o.status().equals("RESOLVED") || !path.add(id) || path.size() > 32) return false;
        boolean known = true;
        if (o.schedule() != null) out.add(o.schedule());
        else if (!o.type().equals("time-group") || o.members().isEmpty()) known = false;
        for (String child : o.members()) { if (budget[0] <= 0) return false; known &= schedules(child, objects, out, path, budget); }
        path.remove(id); return known;
    }
    /** Work units per rule for the breadth traversal; independent of the snapshot-wide shadowing budget. */
    static final int RULE_BUDGET = 2048;
    static final String BUDGET = "Analysis budget reached";
    static final String NO_BROAD = "No broad selectors in resolved objects";
    static final String INVENTORY = "Group members resolved from the domain object inventory";
    /**
     * Fixed vocabulary for why a selector cannot be assessed. Each names the cause instead of one generic sentence, so the
     * remaining Unknown verdicts can be counted per cause (no names, no values).
     */
    static final String NOT_COLLECTED = "Unresolved: object not in collected evidence";
    static final String APPLICATION_NOT_COLLECTED = "Unresolved: application not in collected configuration";
    static final String SERVICE_NOT_COLLECTED = "Unresolved: service not in collected configuration";
    static final String PRECEDENCE = "Unresolved: object precedence unknown";
    static final String EXCLUSION = "Unresolved: group with exclusion";
    static final String UNSUPPORTED = "Unresolved: unsupported object type";
    static final String REGION = "Unresolved: region object";
    static final String FQDN = "Unresolved: FQDN object";
    static final String EMPTY_GROUP = "Unresolved: empty group";
    static final String NO_VALUES = "Unresolved: object without values";
    static final String EMPTY_SELECTOR = "Unresolved: empty selector";
    static final String CYCLE = "Unresolved: group cycle or depth limit";
    static final String PORT = "Unresolved: unsupported port expression";
    static final String DYNAMIC = "Unresolved: dynamic membership";
    /** PO 2026-10-20: a named Palo Alto predefined App-ID or service is a specific selection, 0 breadth points. */
    static final String PREDEFINED = "Predefined App-ID/service, ports not collected";
    /** PO 2026-10-20: application any adds no point when the service is specific (including application-default). */
    static final String ANY_APPLICATION_SPECIFIC_SERVICE = "Any application (0 points: service is specific)";
    static final Map<String, String> DYNAMIC_BY_TYPE = Map.of(
        "access-role", "Unresolved: access role", "dynamic-object", "Unresolved: dynamic object",
        "dns-domain", "Unresolved: DNS domain", "security-zone", "Unresolved: security zone",
        "updatable-object", "Unresolved: updatable object", "address-group", "Unresolved: dynamic address group",
        "external-list", "Unresolved: external dynamic list", "dynamic-user-group", "Unresolved: dynamic user group",
        "application-filter", "Unresolved: application filter");
    static final Set<String> FIXED_REASONS;
    static {
        var all = new LinkedHashSet<>(List.of(BUDGET, NO_BROAD, INVENTORY, NOT_COLLECTED, APPLICATION_NOT_COLLECTED,
            SERVICE_NOT_COLLECTED, PRECEDENCE, EXCLUSION, UNSUPPORTED, REGION, FQDN, EMPTY_GROUP, NO_VALUES, EMPTY_SELECTOR,
            CYCLE, PORT, DYNAMIC, PREDEFINED, ANY_APPLICATION_SPECIFIC_SERVICE, "Broad service range", "Service: application default ports"));
        all.addAll(DYNAMIC_BY_TYPE.values());
        FIXED_REASONS = Set.copyOf(all);
    }
    private static final Set<String> ADDRESS_SERVICE_TYPES = Set.of("any", "address", "host", "network", "address-range", "group",
        "address-group", "service", "service-tcp", "service-udp", "service-group", "application-group", "application", "gateway");

    static Map<String, Object> permissiveness(Rule r, Map<String, PolicyObject> objects) {
        return permissiveness(r, objects, new int[]{RULE_BUDGET}, "");
    }
    static Map<String, Object> permissiveness(Rule r, Map<String, PolicyObject> objects, int[] budget) {
        return permissiveness(r, objects, budget, "");
    }
    /**
     * Scoring per PO_DECISION_RECORD_2026_10_20_POLICY_PERMISSIVENESS_SCORING.md (documented in
     * POLICY_HYGIENE_W1_A_NOTES.md): Any source, Any destination and Any service 3 points each; Any application 1 point,
     * but 0 when the service is specific or application-default; each Large CIDR (IPv4 prefix <= 16) or Broad service
     * range (>= 1024 ports) 1 point; 0 Low, 1-3 Medium, >= 4 High. Any unassessable selector makes the verdict Unknown
     * irrespective of the score, and the Unknown names its causes. {@code vendor} "PAN" enables the predefined rule.
     */
    static Map<String, Object> permissiveness(Rule r, Map<String, PolicyObject> objects, int[] budget, String vendor) {
        boolean pan = "PAN".equals(vendor);
        Set<String> reasons = new LinkedHashSet<>(), gaps = new LinkedHashSet<>(); boolean known = true;
        for (var entry : List.of(Map.entry("source", r.source()), Map.entry("destination", r.destination()), Map.entry("service", r.service()), Map.entry("application", r.application()))) {
            if (entry.getKey().equals("application") && entry.getValue().refs().isEmpty() && !entry.getValue().negated()) continue;
            Cell cell = entry.getValue();
            if (cell.negated()) { reasons.add("Negated " + entry.getKey() + " needs analysis"); known = false; continue; }
            if (cell.refs().isEmpty()) { gaps.add(EMPTY_SELECTOR); known = false; }
            for (String id : cell.refs()) {
                if (budget[0] <= 0) { gaps.add(BUDGET); known = false; break; }
                known &= breadth(id, entry.getKey(), objects, reasons, gaps, new HashSet<>(), budget, pan);
            }
        }
        if (reasons.contains("Any application") && !reasons.contains("Any service")) {
            var ordered = new ArrayList<>(reasons); ordered.set(ordered.indexOf("Any application"), ANY_APPLICATION_SPECIFIC_SERVICE);
            reasons = new LinkedHashSet<>(ordered);
        }
        int score = 0;
        for (String field : List.of("source", "destination", "service")) if (reasons.contains("Any " + field)) score += 3;
        if (reasons.contains("Any application")) score += 1;
        score += (int) reasons.stream().filter(s -> s.startsWith("Large ") || s.startsWith("Broad ")).count();
        if (known && score == 0 && !reasons.contains(ANY_APPLICATION_SPECIFIC_SERVICE)) reasons.add(NO_BROAD);
        reasons.addAll(gaps);
        return Map.of("level", !known ? "Unknown" : score >= 4 ? "High" : score >= 1 ? "Medium" : "Low", "reasons", List.copyOf(reasons), "score", score);
    }
    private static boolean fail(Set<String> gaps, String reason) { gaps.add(reason); return false; }
    private static boolean breadth(String id, String field, Map<String, PolicyObject> objects, Set<String> reasons, Set<String> gaps,
            Set<String> path, int[] budget, boolean pan) {
        if (--budget[0] < 0) return fail(gaps, BUDGET);
        var o = objects.get(id);
        if (o == null) return fail(gaps, NOT_COLLECTED);
        if (!path.add(id) || path.size() > 32) return fail(gaps, CYCLE);
        switch (o.status()) {
            case "RESOLVED" -> { }
            case "DYNAMIC" -> { return fail(gaps, DYNAMIC_BY_TYPE.getOrDefault(o.type(), DYNAMIC)); }
            case "UNKNOWN" -> { return fail(gaps, PRECEDENCE); }
            case "UNSUPPORTED" -> { return fail(gaps, o.type().equals("group") ? EXCLUSION : UNSUPPORTED); }
            case "UNRESOLVED" -> {
                // A Palo Alto rule names an App-ID or service that is not in the collected configuration: a predefined
                // (vendor content) object. PO 2026-10-20: a specific selection, 0 breadth points ("any" is never this).
                if (pan && o.type().equals("unresolved") && (field.equals("application") || field.equals("service"))) {
                    reasons.add(PREDEFINED); path.remove(id); return true;
                }
                return fail(gaps, field.equals("application") ? APPLICATION_NOT_COLLECTED
                    : field.equals("service") ? SERVICE_NOT_COLLECTED : NOT_COLLECTED);
            }
            default -> { return fail(gaps, field.equals("application") ? APPLICATION_NOT_COLLECTED
                : field.equals("service") ? SERVICE_NOT_COLLECTED : NOT_COLLECTED); }
        }
        if (!ADDRESS_SERVICE_TYPES.contains(o.type())) return fail(gaps, o.type().equals("region") ? REGION : UNSUPPORTED);
        if (o.type().equals("any")) { reasons.add("Any " + field); path.remove(id); return true; }
        // A named application (Check Point application/site or category, Palo Alto custom application) is a specific
        // selection: the documented model scores only Any application. A gateway object is the gateway's own addresses.
        if (o.type().equals("application") || o.type().equals("gateway") && o.members().isEmpty()) { path.remove(id); return true; }
        // Palo Alto application-default: the selected applications on their standard ports only (PAN-OS 9.0 App-ID
        // release note); not Any service. The mapper resolves only the reserved keyword itself to this shape.
        if (o.type().equals("service") && o.name().equals("application-default") && o.members().isEmpty() && o.values().isEmpty()) {
            reasons.add("Service: application default ports"); path.remove(id); return true;
        }
        if (o.members().isEmpty() && o.values().isEmpty())
            return fail(gaps, Set.of("group", "address-group", "service-group", "application-group").contains(o.type()) ? EMPTY_GROUP : NO_VALUES);
        for (String v : o.values()) {
            if (--budget[0] < 0) return fail(gaps, BUDGET);
            if (v.startsWith("fqdn:")) return fail(gaps, FQDN);
            if (v.startsWith("dynamic/")) return fail(gaps, DYNAMIC_BY_TYPE.get("address-group"));
            if (v.matches(".*\\/(?:[0-9]|1[0-6])$") || v.matches("mask-length4: (?:[0-9]|1[0-6])")) reasons.add("Large CIDR in " + field);
            if (v.startsWith("port: ") || v.matches("protocol/(tcp|udp)/port: .*")) {
                for (String range : v.substring(v.indexOf(": ") + 2).split(",")) {
                    String[] ends = range.trim().split("-");
                    try { if (ends.length == 2 && Integer.parseInt(ends[1]) - Integer.parseInt(ends[0]) >= 1024) reasons.add("Broad service range"); }
                    catch (NumberFormatException ignored) { return fail(gaps, PORT); }
                }
            }
        }
        boolean known = true;
        for (String child : o.members()) {
            if (budget[0] <= 0) return fail(gaps, BUDGET);
            known &= breadth(child, field, objects, reasons, gaps, path, budget, pan);
        }
        path.remove(id); return known;
    }
}
