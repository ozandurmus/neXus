package com.securityexpert.nexus.ui2.service.policy;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.policy.PolicySchedule;
import java.time.Instant;
import java.util.*;

final class PolicyRuleMetrics {
    record Time(String status, List<PolicySchedule> schedules, boolean expiring) {}
    static Time time(Rule rule, Map<String, PolicyObject> objects, Instant now) { return time(rule, objects, now, ""); }
    static Time time(Rule rule, Map<String, PolicyObject> objects, Instant now, String vendor) {
        List<String> refs = new ArrayList<>(rule.extras().getOrDefault("time", List.of()));
        refs.addAll(rule.extras().getOrDefault("schedule", List.of()));
        if (refs.isEmpty()) return new Time((vendor.equals("CP") || rule.extras().containsKey("time-uncollected")) ? "unknown" : "always", List.of(), false);
        List<PolicySchedule> schedules = new ArrayList<>();
        boolean known = true, any = false;
        for (String id : refs) {
            var o = objects.get(id);
            if (o != null && o.type().equals("any")) any = true;
            else known &= schedules(id, objects, schedules, new HashSet<>());
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
    private static boolean schedules(String id, Map<String, PolicyObject> objects, List<PolicySchedule> out, Set<String> path) {
        var o = objects.get(id);
        if (o == null || !o.status().equals("RESOLVED") || !path.add(id) || path.size() > 32) return false;
        boolean known = true;
        if (o.schedule() != null) out.add(o.schedule());
        else if (!o.type().equals("time-group") || o.members().isEmpty()) known = false;
        for (String child : o.members()) known &= schedules(child, objects, out, path);
        path.remove(id); return known;
    }
    static Map<String, Object> permissiveness(Rule r, Map<String, PolicyObject> objects) {
        Set<String> reasons = new LinkedHashSet<>(); boolean known = true;
        for (var entry : Map.of("source", r.source(), "destination", r.destination(), "service", r.service(), "application", r.application()).entrySet()) {
            if (entry.getKey().equals("application") && entry.getValue().refs().isEmpty()) continue;
            Cell cell = entry.getValue();
            if (cell.negated()) { reasons.add("Negated " + entry.getKey() + " needs analysis"); known = false; continue; }
            if (cell.refs().isEmpty()) known = false;
            for (String id : cell.refs()) known &= breadth(id, entry.getKey(), objects, reasons, new HashSet<>());
        }
        long any = reasons.stream().filter(s -> s.startsWith("Any ")).count();
        int score = (int) any * 2 + (int) reasons.stream().filter(s -> s.startsWith("Large ") || s.startsWith("Broad ")).count();
        if (!known) reasons.add("Incomplete objects; assessment requires analysis");
        else if (score == 0) reasons.add("No broad selectors in resolved objects");
        return Map.of("level", !known ? "Unknown" : score >= 4 ? "High" : score >= 1 ? "Medium" : "Low", "reasons", List.copyOf(reasons));
    }
    private static boolean breadth(String id, String field, Map<String, PolicyObject> objects, Set<String> reasons, Set<String> path) {
        var o = objects.get(id);
        if (o == null || !o.status().equals("RESOLVED") || !path.add(id) || path.size() > 32) return false;
        if (o.type().equals("any")) reasons.add("Any " + field);
        for (String v : o.values()) {
            if (v.matches(".*\\/(?:[0-9]|1[0-6])$") || v.matches("mask-length4: (?:[0-9]|1[0-6])")) reasons.add("Large CIDR in " + field);
            if (v.startsWith("port: ") || v.matches("protocol/(tcp|udp)/port: .*")) {
                for (String range : v.substring(v.indexOf(": ") + 2).split(",")) {
                    String[] ends = range.trim().split("-");
                    try { if (ends.length == 2 && Integer.parseInt(ends[1]) - Integer.parseInt(ends[0]) >= 1024) reasons.add("Broad service range"); }
                    catch (NumberFormatException ignored) { return false; }
                }
            }
        }
        boolean known = true;
        for (String child : o.members()) known &= breadth(child, field, objects, reasons, path);
        path.remove(id); return known;
    }
}
