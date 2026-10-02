package com.securityexpert.nexus.ui2.worker.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.policy.*;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import java.time.Instant;
import java.util.*;
import org.w3c.dom.Element;

/** In-memory projections only; unsupported values and missing members remain unknown. */
final class PolicyHitCounts {
    private PolicyHitCounts() {}
    static Long counter(String value) {
        try { return value.matches("[0-9]{1,19}") ? Long.valueOf(value) : null; }
        catch (NumberFormatException invalid) { return null; }
    }
    static String timestamp(String value) {
        try {
            if (value.matches("[0-9]{1,19}")) {
                long seconds = Long.parseLong(value);
                return seconds == 0 ? null : Instant.ofEpochSecond(seconds).toString();
            }
            return Instant.parse(value).toString();
        } catch (RuntimeException invalid) { return null; }
    }
    private static String cpDate(JsonNode node) {
        return timestamp(node.isObject() ? node.path("iso-8601").asText("") : node.asText(""));
    }
    static HitCounts checkPoint(JsonNode node, String collectedAt) {
        if (!node.isObject()) return null;
        String level = node.path("level").asText("");
        // No vendor free-form text is retained as telemetry.
        if (!Set.of("zero", "low", "medium", "high").contains(level)) level = null;
        return new HitCounts(counter(node.path("value").asText("")), cpDate(node.path("first-date")),
                cpDate(node.path("last-date")), "mds", collectedAt, level, List.of());
    }
    static Map<String, FirewallHits> pan(Element response, Target target, String collectedAt) {
        if (!response.getTagName().equals("response") || !"success".equals(response.getAttribute("status")))
            throw new IllegalArgumentException("POLICY_HITS_INVALID");
        var contexts = PolicyXml.selectRelative(response, "result/rule-hit-count/vsys/entry").stream()
                .filter(e -> e.getAttribute("name").equals(target.context())).toList();
        if (contexts.size() != 1) throw new IllegalArgumentException("POLICY_HITS_CONTEXT_MISMATCH");
        Map<String, FirewallHits> counts = new LinkedHashMap<>();
        for (Element base : PolicyXml.selectRelative(contexts.get(0), "rule-base/entry")) {
            if (!base.getAttribute("name").equals("security")) continue;
            for (Element rule : PolicyXml.selectRelative(base, "rules/entry")) {
                String name = rule.getAttribute("name");
                if (name.isEmpty() || counts.containsKey(name)) throw new IllegalArgumentException("POLICY_HITS_AMBIGUOUS");
                counts.put(name, new FirewallHits(target.deviceId(), target.context(), counter(text(rule, "hit-count")),
                        timestamp(text(rule, "first-hit-timestamp")), timestamp(text(rule, "last-hit-timestamp")),
                        timestamp(text(rule, "rule-creation-timestamp")), timestamp(text(rule, "rule-modification-timestamp")), collectedAt));
            }
        }
        return counts;
    }
    private static String text(Element e, String path) { return PolicyXml.firstRelativeText(e, path).orElse(""); }
    static PolicySnapshot aggregate(PolicySnapshot snapshot, List<Map<String, FirewallHits>> members) {
        var occurrences = new HashMap<String, Integer>();
        snapshot.sections().forEach(s -> s.rules().forEach(r -> occurrences.merge(r.name(), 1, Integer::sum)));
        var sections = snapshot.sections().stream().map(s -> new Section(s.id(), s.name(), s.source(), s.parentRuleId(),
            s.rules().stream().map(r -> {
                // The proposed op returns rule names; ambiguous inherited names cannot be joined.
                if (occurrences.get(r.name()) != 1) return r;
                var breakdown = members.stream().map(m -> m.get(r.name())).filter(Objects::nonNull).toList();
                if (breakdown.isEmpty()) return r;
                boolean complete = breakdown.size() == snapshot.metadata().targets().size()
                        && breakdown.stream().allMatch(h -> h.hits() != null);
                Long hits = complete ? 0L : null;
                if (complete) try { for (var h : breakdown) hits = Math.addExact(hits, h.hits()); }
                catch (ArithmeticException overflow) { hits = null; }
                String first = complete ? earliest(breakdown.stream().map(FirewallHits::firstHit).toList()) : null;
                String last = complete && breakdown.stream().allMatch(h -> h.hits() == 0 || h.lastHit() != null) ? latest(breakdown.stream().map(FirewallHits::lastHit).toList()) : null;
                return r.withHitCounts(new HitCounts(hits, first, last, "device", latest(breakdown.stream()
                        .map(FirewallHits::collectedAt).toList()), null, breakdown));
            }).toList())).toList();
        return new PolicySnapshot(snapshot.metadata(), sections, snapshot.objects(), snapshot.failures());
    }
    private static String earliest(List<String> dates) {
        return dates.stream().filter(Objects::nonNull).map(Instant::parse).min(Comparator.naturalOrder()).map(Instant::toString).orElse(null);
    }
    private static String latest(List<String> dates) {
        return dates.stream().filter(Objects::nonNull).map(Instant::parse).max(Comparator.naturalOrder()).map(Instant::toString).orElse(null);
    }
}
