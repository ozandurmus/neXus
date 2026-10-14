package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import java.util.*;

/** Bounded management-intent lookup. Never represents observed reachability. */
public final class PolicyLookup {
    private PolicyLookup() {}
    public static final String LIMITATIONS = "Policy intent only. NAT, routing and anti-spoofing are not evaluated; IPv6 is unsupported.";
    private static final String CP_CLEANUP = "implicit cleanup action not collected";
    private static final String PAN_INTRAZONE = "no rule matched; intrazone-default applies (allow by default, override not collected)";
    private static final String PAN_INTERZONE = "no rule matched; interzone-default applies (deny by default, override not collected)";
    public static final Set<String> REASONS = Set.of(CP_CLEANUP, PAN_INTRAZONE, PAN_INTERZONE,
        "multiple ordered layers not evaluated", "ordered layer results", "all ordered layers allowed", "first matching rule", "earlier rule could match",
        "partial query coverage", "analysis budget", "snapshot collection incomplete", "unsupported evaluation scope",
        "inline layer evaluation unsupported", "zone or target required", "target context unproven", "application constraint",
        "service-wide query requires partitioning", "unsupported rule constraint");
    public record Query(String source, String destination, String service, String sourceZone, String destinationZone, String target, String context) {
        public Query {
            address(source); address(destination);
            if (service == null || !service.matches("any|(?:tcp|udp)/(?:0|[1-9][0-9]{0,4})")) throw invalid();
            if (!service.equals("any") && Integer.parseInt(service.substring(4)) > 65535) throw invalid();
            sourceZone = optional(sourceZone); destinationZone = optional(destinationZone);
            target = optional(target); context = optional(context);
        }
        public Map<String, Object> view() {
            return Map.of("querySource", source, "queryDestination", destination, "queryService", service,
                "sourceZone", sourceZone, "destinationZone", destinationZone, "target", target, "context", context);
        }
    }
    private record Entry(Section section, Rule rule, Match match, int layer) {}
    private record Evaluation(List<Entry> entries, List<Integer> layers) {}
    private record Decision(String verdict, String reason, Map<String, Object> rule, List<Map<String, Object>> undecidable,
            List<Map<String, Object>> layers) {
        Decision(String verdict, String reason, Map<String, Object> rule, List<Map<String, Object>> undecidable) {
            this(verdict, reason, rule, undecidable, List.of());
        }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid lookup query"); }
    private static String optional(String value) {
        if (value == null) return "";
        if (value.length() > 200 || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }
    private static Range address(String value) {
        if (value == null || value.length() > 18 || !value.matches("[0-9.]+(?:/(?:[0-9]|[12][0-9]|3[0-2]))?")) throw invalid();
        long[] range = PolicyRuleQuery.interval(value); return new Range(range[0], range[1]);
    }
    public static PolicyResponse evaluate(PolicySnapshot snapshot, Query query, int workLimit) {
        return prepare(snapshot, workLimit).evaluate(query);
    }
    /** Normalize once for a streamed sample; every row still uses the same first-match evaluator and budget. */
    public static Prepared prepare(PolicySnapshot snapshot, int workLimit) { return new Prepared(snapshot, workLimit); }
    public static final class Prepared {
        private final PolicySnapshot snapshot;
        private final int workLimit;
        private final Evaluation evaluation;
        private final String problem;
        private Prepared(PolicySnapshot snapshot, int workLimit) {
            if (workLimit < 0) throw invalid();
            this.snapshot = snapshot;
            var budget = new Budget(workLimit);
            Evaluation parsed = null; String failure = null;
            try { parsed = entries(snapshot, budget); }
            catch (Unknown unknown) { failure = unknown.getMessage(); }
            evaluation = parsed; problem = failure; this.workLimit = Math.max(0, budget.left[0]);
        }
        public PolicyResponse evaluate(Query query) {
            return PolicyLookup.evaluate(snapshot, query, workLimit, evaluation, problem);
        }
    }
    private static PolicyResponse evaluate(PolicySnapshot snapshot, Query query, int workLimit, Evaluation evaluation, String problem) {
        var budget = new Budget(workLimit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policyId", snapshot.metadata().id()); result.put("collectedAt", snapshot.metadata().collectedAt());
        result.put("query", query.view()); result.put("limitations", LIMITATIONS);
        result.put("layers", List.of()); result.put("decidingRule", null); result.put("undecidableRules", List.of()); result.put("parts", List.of());
        try {
            if (!snapshot.failures().isEmpty()) throw new Unknown("snapshot collection incomplete");
            if ((!query.context().isEmpty() || snapshot.metadata().vendor().equals("PAN") && !query.target().isEmpty()) && snapshot.metadata().targets().stream().noneMatch(t ->
                    (query.context().isEmpty() || t.context().equals(query.context())) && (query.target().isEmpty() || t.deviceId().equals(query.target()))))
                throw new Unknown("target context unproven");
            if (problem != null) throw new Unknown(problem);
            List<Entry> entries = evaluation.entries();
            Range source = address(query.source()), destination = address(query.destination());
            var sources = partitions(source, entries, 0, budget); var destinations = partitions(destination, entries, 1, budget);
            if ((long) sources.size() * destinations.size() > 128) throw new Unknown("analysis budget");
            List<Map<String, Object>> parts = new ArrayList<>(); List<Decision> decisions = new ArrayList<>();
            for (Range s : sources) for (Range d : destinations) {
                Decision decision = decide(snapshot, evaluation, query, s, d, budget); decisions.add(decision);
                var part = new LinkedHashMap<String, Object>();
                part.put("querySource", rangeText(s)); part.put("queryDestination", rangeText(d));
                part.put("verdict", decision.verdict()); part.put("lookupReason", decision.reason());
                part.put("layers", decision.layers()); part.put("decidingRule", decision.rule()); part.put("undecidableRules", decision.undecidable()); parts.add(part);
            }
            Decision first = decisions.get(0);
            boolean same = decisions.stream().allMatch(first::equals);
            boolean unknown = decisions.stream().anyMatch(d -> d.verdict().equals("UNKNOWN"));
            result.put("verdict", unknown ? "UNKNOWN" : same ? first.verdict() : "PARTIAL");
            result.put("lookupReason", same ? first.reason() : "partial query coverage");
            result.put("decidingRule", same ? first.rule() : null);
            if (same) result.put("layers", first.layers());
            result.put("undecidableRules", decisions.stream().flatMap(d -> d.undecidable().stream()).distinct().toList());
            if (!same) result.put("parts", parts);
        } catch (Unknown unknown) {
            result.put("verdict", "UNKNOWN"); result.put("lookupReason", unknown.getMessage());
        }
        return new PolicyResponse(result);
    }
    private static Evaluation entries(PolicySnapshot snapshot, Budget budget) {
        var resolver = new Resolver(snapshot.objects(), budget); List<Entry> entries = new ArrayList<>();
        var scope = new EvaluationScope(snapshot.metadata()); Set<String> sectionIds = new HashSet<>();
        Set<String> ruleIds = new HashSet<>(); var cpLayers = new CpLayers();
        Set<Integer> layers = new LinkedHashSet<>();
        Set<String> inlineParents = new HashSet<>();
        for (Section section : snapshot.sections()) {
            budget.spend();
            if (section.source().equals("CP NAT rulebase")) continue;
            if (!sectionIds.add(section.id())) throw new Unknown("unsupported evaluation scope");
            int layer = -1;
            if (snapshot.metadata().vendor().equals("PAN")) {
                if (scope.panPhase(section) < 0) throw new Unknown("unsupported evaluation scope");
                scope.advance(section); if (scope.unproven) throw new Unknown("unsupported evaluation scope");
            } else if (snapshot.metadata().vendor().equals("CP") && section.source().equals("CP access layer")) {
                if (section.parentRuleId() != null && !inlineParents.contains(section.parentRuleId())) throw new Unknown("unsupported evaluation scope");
                layer = cpLayers.advance(section, budget);
                if (section.parentRuleId() == null) layers.add(layer);
            } else throw new Unknown("unsupported evaluation scope");
            for (Rule rule : section.rules()) {
                budget.spend();
                if (!ruleIds.add(rule.id())) throw new Unknown("unsupported evaluation scope");
                if (rule.action().equals("Apply Layer")) inlineParents.add(rule.id());
                if (Boolean.FALSE.equals(rule.enabled())) continue;
                entries.add(new Entry(section, rule, normalize(snapshot, section, rule, resolver, true), layer));
            }
        }
        if (sectionIds.isEmpty()) throw new Unknown("unsupported evaluation scope");
        return new Evaluation(entries, List.copyOf(layers));
    }
    private static List<Range> partitions(Range query, List<Entry> entries, int index, Budget budget) {
        TreeSet<Long> cuts = new TreeSet<>(List.of(query.start(), query.end() + 1));
        for (Entry entry : entries) {
            budget.spend(); if (entry.match().problem() != null) continue;
            for (Range r : entry.match().selectors().get(index).ranges().getOrDefault("ipv4", List.of())) {
                budget.spend();
                if (r.start() > query.start() && r.start() <= query.end()) cuts.add(r.start());
                if (r.end() >= query.start() && r.end() < query.end()) cuts.add(r.end() + 1);
                if (cuts.size() > 129) throw new Unknown("analysis budget");
            }
        }
        List<Range> parts = new ArrayList<>(); Long previous = null;
        for (long cut : cuts) { if (previous != null) parts.add(new Range(previous, cut - 1)); previous = cut; }
        return parts;
    }
    private static Decision decide(PolicySnapshot snapshot, Evaluation evaluation, Query query, Range source, Range destination, Budget budget) {
        if (!snapshot.metadata().vendor().equals("CP"))
            return decide(snapshot, evaluation.entries(), query, source, destination, budget, null, -1, 0);
        if (evaluation.layers().isEmpty()) throw new Unknown("multiple ordered layers not evaluated");
        List<Map<String, Object>> results = new ArrayList<>(), undecidable = new ArrayList<>();
        boolean unknown = false;
        for (int layer : evaluation.layers()) {
            Decision decision = decide(snapshot, evaluation.entries(), query, source, destination, budget, null, layer, 0);
            if (evaluation.layers().size() == 1) return decision;
            var result = new LinkedHashMap<String, Object>();
            result.put("number", results.size() + 1); result.put("verdict", decision.verdict());
            result.put("lookupReason", decision.reason()); result.put("decidingRule", decision.rule());
            result.put("undecidableRules", decision.undecidable()); results.add(result);
            undecidable.addAll(decision.undecidable());
            if (undecidable.size() > 128) throw new Unknown("analysis budget");
            if (decision.verdict().equals("DENIED"))
                return new Decision("DENIED", decision.reason(), decision.rule(), List.copyOf(undecidable), List.copyOf(results));
            unknown |= decision.verdict().equals("UNKNOWN");
        }
        return new Decision(unknown ? "UNKNOWN" : "ALLOWED", unknown ? "ordered layer results" : "all ordered layers allowed",
            null, List.copyOf(undecidable), List.copyOf(results));
    }
    private static Decision decide(PolicySnapshot snapshot, List<Entry> entries, Query query, Range source, Range destination, Budget budget, String parent, int layer, int depth) {
        if (depth > 32) throw new Unknown("analysis budget");
        List<Map<String, Object>> unknown = new ArrayList<>();
        for (Entry entry : entries) {
            budget.spend();
            if (!Objects.equals(entry.section().parentRuleId(), parent) || parent == null && entry.layer() != layer) continue;
            String problem = entry.match().problem();
            if (problem == null) {
                var selectors = entry.match().selectors();
                if (!covers(selectors.get(0), "ipv4", source, budget) || !covers(selectors.get(1), "ipv4", destination, budget)) continue;
                var service = selectors.get(2);
                if (query.service().equals("any")) { if (!service.any()) problem = "service-wide query requires partitioning"; }
                else {
                    String protocol = query.service().substring(0, 3); long port = Long.parseLong(query.service().substring(4));
                    if (!covers(service, protocol, new Range(port, port), budget)) continue;
                }
                if (!selectors.get(3).any()) problem = "application constraint";
                for (String key : List.of("from", "to", "install-on")) {
                    Set<String> placements = entry.match().placements().get(key);
                    if (placements.isEmpty()) continue;
                    boolean any = key.equals("install-on") ? placements.stream().anyMatch(id -> snapshot.objects().get(id).type().equals("any")) : placements.contains("any");
                    if (any) continue;
                    String chosen = key.equals("from") ? query.sourceZone() : key.equals("to") ? query.destinationZone() : query.target();
                    if (chosen.isEmpty()) problem = "zone or target required";
                    else if (key.equals("install-on")) {
                        // Inventory device IDs and object IDs have no proven join; equality is the only supported binding.
                        if (!placements.contains(chosen)) problem = "target context unproven";
                    } else if (!placements.contains(chosen)) { problem = "NO_MATCH"; break; }
                }
            }
            if ("NO_MATCH".equals(problem)) continue;
            if (problem == null && entry.rule().action().equals("Apply Layer")) {
                boolean hasChild = false;
                for (Section section : snapshot.sections()) {
                    budget.spend();
                    if (entry.rule().id().equals(section.parentRuleId())) hasChild = true;
                }
                if (!hasChild) problem = "inline layer evaluation unsupported";
                else {
                    Decision child = decide(snapshot, entries, query, source, destination, budget, entry.rule().id(), -1, depth + 1);
                    unknown.addAll(child.undecidable());
                    if (unknown.size() > 128) throw new Unknown("analysis budget");
                    return new Decision(unknown.isEmpty() ? child.verdict() : "UNKNOWN", unknown.isEmpty() ? child.reason() : "earlier rule could match", child.rule(), List.copyOf(unknown));
                }
            }
            if (problem != null) {
                if (unknown.size() >= 128) throw new Unknown("analysis budget");
                var row = new LinkedHashMap<>(rule(entry)); row.put("lookupReason", problem); unknown.add(row); continue;
            }
            return new Decision(unknown.isEmpty() ? (Set.of("Accept", "allow").contains(entry.rule().action()) ? "ALLOWED" : "DENIED") : "UNKNOWN",
                unknown.isEmpty() ? "first matching rule" : "earlier rule could match", rule(entry), List.copyOf(unknown));
        }
        String reason = snapshot.metadata().vendor().equals("CP") ? CP_CLEANUP
            : query.sourceZone().isEmpty() || query.destinationZone().isEmpty() ? "zone or target required"
            : query.sourceZone().equals(query.destinationZone()) ? PAN_INTRAZONE : PAN_INTERZONE;
        return new Decision("UNKNOWN", unknown.isEmpty() ? reason : "earlier rule could match", null, List.copyOf(unknown));
    }
    private static boolean covers(Selector selector, String family, Range range, Budget budget) {
        return contains(selector, new Selector(false, Map.of(family, List.of(range))), budget);
    }
    private static Map<String, Object> rule(Entry entry) {
        return Map.of("ruleId", entry.rule().id(), "number", entry.rule().number(), "name", entry.rule().name(),
            "sectionId", entry.section().id(), "sectionName", entry.section().name(), "action", entry.rule().action());
    }
    private static String rangeText(Range range) { return ip(range.start()) + (range.start() == range.end() ? "" : "-" + ip(range.end())); }
    private static String ip(long value) { return (value >>> 24) + "." + ((value >>> 16) & 255) + "." + ((value >>> 8) & 255) + "." + (value & 255); }
}
