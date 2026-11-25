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
    // Cisco ASA (A0-ASA-1..5, PO_DECISION_RECORD_2026_11_14_ASA_FORTIGATE_POLICY_MODEL.md item 4)
    static final String ASA_IMPLICIT_DENY = "no rule matched; implicit deny of the access list";
    static final String ASA_LEVEL_ALLOW = "no access list applies; higher to lower security level is allowed by default";
    static final String ASA_LEVEL_UNPROVEN = "no access list applies; security-level default unproven";
    static final String ASA_SAME_INTER = "same security level; same-security-traffic inter-interface is not permitted";
    static final String ASA_SAME_INTRA = "same interface; same-security-traffic intra-interface is not permitted";
    static final String ASA_INTERFACE_UNKNOWN = "interface or security level not in the policy";
    static final String ASA_RESULTS = "access list results";
    // FortiGate (A0-FG-3, A0-FG-10)
    static final String FGT_IMPLICIT_DENY = "no rule matched; implicit deny";
    static final String FGT_INTRAZONE_UNKNOWN = "no rule matched; zone intrazone setting not evaluated";
    /** A0-FG-18: an earlier ordinary policy and a later VIP policy both match; whether VIP policies take priority is unproven. */
    static final String FGT_VIP_PRIORITY_UNPROVEN = "FGT_VIP_PRIORITY_UNPROVEN";
    /** A0-FG-20: a deny policy with the default match-vip may also match the packet after the VIP changed its destination. */
    static final String FGT_MATCH_VIP_UNPROVEN = "FGT_MATCH_VIP_UNPROVEN";
    public static final Set<String> REASONS = Set.of(ASA_IMPLICIT_DENY, ASA_LEVEL_ALLOW, ASA_LEVEL_UNPROVEN, ASA_SAME_INTER, ASA_SAME_INTRA,
        ASA_INTERFACE_UNKNOWN, ASA_RESULTS, FGT_IMPLICIT_DENY, FGT_INTRAZONE_UNKNOWN, FGT_VIP_PRIORITY_UNPROVEN, FGT_MATCH_VIP_UNPROVEN, CP_CLEANUP, PAN_INTRAZONE, PAN_INTERZONE,
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
    /** {@code prior}: for an ASA rule without a full match, what of it did resolve (null when nothing may be relied on). */
    private record Entry(Section section, Rule rule, Match match, int layer, Partial prior) {
        Entry(Section section, Rule rule, Match match, int layer) { this(section, rule, match, layer, null); }
    }
    private record Evaluation(List<Entry> entries, List<Integer> layers, Asa asa) {
        Evaluation(List<Entry> entries, List<Integer> layers) { this(entries, layers, null); }
    }
    /**
     * Normalization work for one ASA access list, spent once per list and prepared lookup. An ASA policy can hold tens of
     * thousands of access rules (one measured unit: about 26,000), far more than the query budget could normalize up
     * front, while one packet meets at most three lists (ingress "in", global, egress "out"): those are normalized when
     * a query first needs them, each with this budget, and kept for the following rows of a streamed sample.
     */
    static final int ASA_LIST_WORK = 2_000_000;
    /**
     * An ASA snapshot's access lists by binding ("in:IF", "out:IF", "global", each a layer number) and its interface
     * table (nameif to security level; null level = unknown) with the same-security-traffic flags, all as the mapper stored
     * them (section identity verified by its opaque id, never by display name).
     */
    private record Asa(Map<String, Integer> lists, Map<String, Integer> levels, Set<String> interfaces, boolean inter, boolean intra, boolean known,
            Map<Integer, Section> sections, Map<Integer, List<Entry>> normalized) {}
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
            if (evaluation.asa() != null) {
                evaluation = asaLists(snapshot, evaluation, query);
                // An ASA list holds up to thousands of rules with large object groups; the query's own first-match pass over
                // its (at most three) lists gets the list budget, still a bounded, request-sized amount of work.
                budget = new Budget(Math.max(workLimit, ASA_LIST_WORK));
            }
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
        var scope = new EvaluationScope<Object>(snapshot.metadata(), () -> null); Set<String> sectionIds = new HashSet<>();
        Set<String> ruleIds = new HashSet<>(); var cpLayers = new CpLayers();
        Set<Integer> layers = new LinkedHashSet<>();
        Set<String> inlineParents = new HashSet<>();
        Map<String, Integer> asaLists = new LinkedHashMap<>();
        Map<Integer, Section> asaSections = new HashMap<>();
        for (Section section : snapshot.sections()) {
            budget.spend();
            if (PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), section)) continue;
            if (!sectionIds.add(section.id())) throw new Unknown("unsupported evaluation scope");
            int layer = -1;
            if (snapshot.metadata().vendor().equals("PAN")) {
                if (scope.panPhase(section) < 0) throw new Unknown("unsupported evaluation scope");
                scope.advance(section); if (scope.unproven) throw new Unknown("unsupported evaluation scope");
            } else if (snapshot.metadata().vendor().equals("CP") && section.source().equals("CP access layer")) {
                if (section.parentRuleId() != null && !inlineParents.contains(section.parentRuleId())) throw new Unknown("unsupported evaluation scope");
                layer = cpLayers.advance(section, budget);
                if (section.parentRuleId() == null) layers.add(layer);
            } else if (snapshot.metadata().vendor().equals("FGT") && section.source().equals("FGT firewall policy")) {
                // One policy table per VDOM snapshot, in printed (sequence) order (A0-FG-1/2).
                if (sectionIds.size() > 1) throw new Unknown("unsupported evaluation scope");
            } else if (snapshot.metadata().vendor().equals("ASA") && section.source().equals("ASA access-group")) {
                String binding = asaBinding(snapshot, section);
                if (binding == null || asaLists.containsKey(binding)) throw new Unknown("unsupported evaluation scope");
                layer = asaLists.size(); asaLists.put(binding, layer);
            } else throw new Unknown("unsupported evaluation scope");
            if (layer >= 0 && snapshot.metadata().vendor().equals("ASA")) {
                asaSections.put(layer, section);
                for (Rule rule : section.rules()) { budget.spend(); if (!ruleIds.add(rule.id())) throw new Unknown("unsupported evaluation scope"); }
                continue;
            }
            for (Rule rule : section.rules()) {
                budget.spend();
                if (!ruleIds.add(rule.id())) throw new Unknown("unsupported evaluation scope");
                if (rule.action().equals("Apply Layer")) inlineParents.add(rule.id());
                if (Boolean.FALSE.equals(rule.enabled())) continue;
                entries.add(new Entry(section, rule, normalize(snapshot, section, rule, resolver, true), layer));
            }
        }
        if (snapshot.metadata().vendor().equals("ASA")) return new Evaluation(entries, List.of(), asaInterfaces(snapshot, asaLists, asaSections));
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
        if (snapshot.metadata().vendor().equals("ASA")) return asa(snapshot, evaluation, query, source, destination, budget);
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
        return decide(snapshot, entries, query, source, destination, budget, parent, l -> l == layer, depth);
    }
    private static Decision decide(PolicySnapshot snapshot, List<Entry> entries, Query query, Range source, Range destination, Budget budget, String parent,
            java.util.function.IntPredicate layers, int depth) {
        if (depth > 32) throw new Unknown("analysis budget");
        List<Map<String, Object>> unknown = new ArrayList<>();
        boolean fgt = snapshot.metadata().vendor().equals("FGT");
        List<VipTarget> vips = null;
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            budget.spend();
            if (!Objects.equals(entry.section().parentRuleId(), parent) || parent == null && !layers.test(entry.layer())) continue;
            String problem = entry.match().problem();
            if (problem == null) {
                var selectors = entry.match().selectors();
                if (!covers(selectors.get(0), "ipv4", source, budget)) continue;
                if (!covers(selectors.get(1), "ipv4", destination, budget)) {
                    // A0-FG-20: a deny policy whose destination misses the queried (pre-DNAT) address may still match the
                    // address a VIP maps it to; that is not decided here, so the policy stays an earlier unknown.
                    if (fgt) {
                        if (vips == null) vips = vipTargets(snapshot, budget);
                        if (matchVipMayMatch(entry, query, source, destination, vips, budget)) {
                            if (unknown.size() >= 128) throw new Unknown("analysis budget");
                            var row = new LinkedHashMap<>(rule(entry)); row.put("lookupReason", FGT_MATCH_VIP_UNPROVEN); unknown.add(row);
                        }
                    }
                    continue;
                }
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
            // An ASA rule the lookup cannot fully evaluate still cannot match a packet outside what did resolve of it
            // (PolicyNormalization#partial: its other conditions only narrow). Disjoint from this query: pass over it.
            if (problem != null && entry.prior() != null && disjoint(entry.prior(), source, destination, query)) continue;
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
            if (fgt && unknown.isEmpty()) {
                // A0-FG-18: a later VIP policy that may match the packet too: which of the two wins is not proven.
                List<Map<String, Object>> later = laterVipPolicies(snapshot, entries, index, query, source, destination, budget);
                if (!later.isEmpty()) return new Decision("UNKNOWN", FGT_VIP_PRIORITY_UNPROVEN, rule(entry), List.copyOf(later));
            }
            return new Decision(unknown.isEmpty() ? (ALLOWING_ACTIONS.contains(entry.rule().action()) ? "ALLOWED" : "DENIED") : "UNKNOWN",
                unknown.isEmpty() ? "first matching rule" : "earlier rule could match", rule(entry), List.copyOf(unknown));
        }
        if (snapshot.metadata().vendor().equals("FGT")) {
            String fallThrough = fgtFallThrough(snapshot, budget);
            if (fallThrough.equals(FGT_IMPLICIT_DENY) && unknown.isEmpty()) return new Decision("DENIED", FGT_IMPLICIT_DENY, null, List.of());
            return new Decision("UNKNOWN", unknown.isEmpty() ? fallThrough : "earlier rule could match", null, List.copyOf(unknown));
        }
        String reason = snapshot.metadata().vendor().equals("CP") ? CP_CLEANUP
            : query.sourceZone().isEmpty() || query.destinationZone().isEmpty() ? "zone or target required"
            : query.sourceZone().equals(query.destinationZone()) ? PAN_INTRAZONE : PAN_INTERZONE;
        return new Decision("UNKNOWN", unknown.isEmpty() ? reason : "earlier rule could match", null, List.copyOf(unknown));
    }
    /**
     * A0-FG-18 (MEDIUM: "Policies with VIPs applied have priority over typical firewall policies", the effect on order is
     * not stated): the policies after {@code matched} that carry a VIP and that the packet may also match. A VIP policy is
     * never a full match here (its destination is a VIP object), so what bounds it is what resolves of it: source and
     * service, and the ingress interface; its destination is never used (a VIP policy may match the extip or the mapped
     * address, A0-FG-19). A VIP policy no resolved dimension rules out is listed, and the caller answers UNKNOWN.
     */
    private static List<Map<String, Object>> laterVipPolicies(PolicySnapshot snapshot, List<Entry> entries, int matched, Query query, Range source,
            Range destination, Budget budget) {
        List<Map<String, Object>> found = new ArrayList<>(); Resolver resolver = null;
        Entry first = entries.get(matched);
        for (int i = matched + 1; i < entries.size(); i++) {
            Entry later = entries.get(i); budget.spend();
            if (later.layer() != first.layer() || !Objects.equals(later.section().parentRuleId(), first.section().parentRuleId())) continue;
            if (!later.rule().extras().containsKey("fgt-vip")) continue;
            if (resolver == null) resolver = new Resolver(snapshot.objects(), budget);
            if (!vipPolicyMayMatch(snapshot, later, query, source, destination, resolver)) continue;
            if (found.size() >= 128) throw new Unknown("analysis budget");
            var row = new LinkedHashMap<>(rule(later)); row.put("lookupReason", FGT_VIP_PRIORITY_UNPROVEN); found.add(row);
        }
        return found;
    }
    private static boolean vipPolicyMayMatch(PolicySnapshot snapshot, Entry vip, Query query, Range source, Range destination, Resolver resolver) {
        var rule = vip.rule(); var extras = new LinkedHashMap<>(rule.extras()); extras.remove("fgt-vip");
        var stripped = new Rule(rule.id(), rule.uuid(), rule.number(), rule.name(), rule.enabled(), rule.source(), rule.destination(), rule.service(),
            rule.application(), rule.action(), rule.log(), rule.comment(), extras);
        Partial prior = partial(snapshot, vip.section(), stripped, resolver);
        if (prior == null) return true;
        var selectors = new ArrayList<>(prior.selectors()); selectors.set(1, null);
        if (disjoint(new Partial(prior.rule(), selectors, prior.placements()), source, destination, query)) return false;
        Set<String> from = prior.placements().getOrDefault("from", Set.of());
        return from.isEmpty() || from.contains("any") || query.sourceZone().isEmpty() || from.contains(query.sourceZone());
    }
    /** A static VIP of the VDOM: the external address range(s) and the mapped range(s); {@code null} where a value did not parse. */
    private record VipTarget(List<long[]> external, List<long[]> mapped, boolean portForward) {}
    private static List<VipTarget> vipTargets(PolicySnapshot snapshot, Budget budget) {
        List<VipTarget> out = new ArrayList<>();
        for (PolicyObject object : snapshot.objects().values()) {
            budget.spend();
            if (object.values().stream().noneMatch(v -> v.startsWith("vip-mapped: "))) continue;
            List<long[]> external = new ArrayList<>(), mapped = new ArrayList<>(); boolean portForward = false;
            for (String value : object.values()) {
                try {
                    if (value.startsWith("ip-netmask: ")) external.add(PolicyRuleQuery.interval(value.substring(12)));
                    else if (value.startsWith("ip-range: ")) external.add(PolicyRuleQuery.interval(value.substring(10)));
                    else if (value.startsWith("vip-mapped: ")) for (String part : value.substring(12).split(",")) mapped.add(PolicyRuleQuery.interval(part));
                    else if (value.startsWith("vip-mappedport: ")) portForward = true;
                } catch (IllegalArgumentException invalid) { external = null; break; }
            }
            // A VIP whose addresses did not parse may translate anything: no range is known.
            out.add(external == null || external.isEmpty() || mapped.isEmpty() ? new VipTarget(null, null, true) : new VipTarget(external, mapped, portForward));
        }
        return out;
    }
    /**
     * A0-FG-20 (the default {@code match-vip enable} is HIGH, 7.4 CLI table; "a deny policy also matches the VIP's mapped
     * address" is MEDIUM): whether a deny policy that does not cover the queried address may still match the address a
     * VIP translates it to. An accept policy's {@code match-vip} is not used. A policy carrying an explicit match-vip or
     * match-vip-only option is already an unsupported rule constraint, so only the default reaches here. Source and
     * ingress interface are not changed by destination NAT and may rule the policy out; the destination interface is
     * routed on the mapped address and the service port may be translated, so those are used only where no port is mapped.
     */
    private static boolean matchVipMayMatch(Entry entry, Query query, Range source, Range destination, List<VipTarget> vips, Budget budget) {
        if (ALLOWING_ACTIONS.contains(entry.rule().action()) || entry.rule().extras().containsKey("fgt-vip")) return false;
        var selectors = entry.match().selectors(); boolean portForward = false, relevant = false, mappedOverlap = false;
        for (VipTarget vip : vips) {
            budget.spend();
            if (vip.external() == null) { relevant = true; mappedOverlap = true; portForward = true; continue; }
            boolean hit = false;
            for (long[] e : vip.external()) hit |= e[0] <= destination.end() && e[1] >= destination.start();
            if (!hit) continue;
            relevant = true; portForward |= vip.portForward();
            for (long[] m : vip.mapped()) mappedOverlap |= overlaps(selectors.get(1), "ipv4", new Range(m[0], m[1]));
        }
        if (!relevant || !mappedOverlap) return false;
        if (!portForward && !query.service().equals("any")) {
            String protocol = query.service().substring(0, 3); long port = Long.parseLong(query.service().substring(4));
            if (!covers(selectors.get(2), protocol, new Range(port, port), budget)) return false;
        }
        Set<String> from = entry.match().placements().getOrDefault("from", Set.of());
        return from.isEmpty() || from.contains("any") || query.sourceZone().isEmpty() || from.contains(query.sourceZone());
    }
    /**
     * FortiGate after the last policy (A0-FG-3, A0-FG-10): the mapper's implicit section holds a synthetic intrazone
     * allow per zone with {@code intrazone allow}, then the implicit deny. With only the implicit deny, a packet no policy
     * matched is denied; a zone's intrazone allow is not evaluated here (zone membership is not compared by name), so it
     * makes the fall-through UNKNOWN. A missing or ambiguous implicit section is UNKNOWN as well.
     */
    private static String fgtFallThrough(PolicySnapshot snapshot, Budget budget) {
        int sections = 0; boolean denyOnly = false;
        for (Section section : snapshot.sections()) {
            budget.spend();
            if (!"FGT implicit".equals(section.source())) continue;
            sections++;
            denyOnly = section.rules().size() == 1 && "deny".equals(section.rules().get(0).action())
                && Boolean.TRUE.equals(section.rules().get(0).enabled())
                && List.of("any").equals(section.rules().get(0).extras().get("from")) && List.of("any").equals(section.rules().get(0).extras().get("to"));
        }
        return sections == 1 && denyOnly ? FGT_IMPLICIT_DENY : FGT_INTRAZONE_UNKNOWN;
    }
    /**
     * The evaluation a query needs on an ASA policy: the ingress "in" list, then the global list, then the egress "out"
     * list, each normalized once (with {@link #ASA_LIST_WORK}) and reused. A list too large for that budget makes the
     * query UNKNOWN ("analysis budget"), never a partial answer.
     */
    private static Evaluation asaLists(PolicySnapshot snapshot, Evaluation evaluation, Query query) {
        Asa asa = evaluation.asa(); List<Entry> entries = new ArrayList<>();
        for (String key : List.of("in:" + query.sourceZone(), "global", "out:" + query.destinationZone())) {
            Integer layer = asa.lists().get(key);
            if (layer == null) continue;
            List<Entry> list = asa.normalized().computeIfAbsent(layer, l -> {
                var budget = new Budget(ASA_LIST_WORK); var resolver = new Resolver(snapshot.objects(), budget); Section section = asa.sections().get(l);
                List<Entry> out = new ArrayList<>();
                try {
                    for (Rule rule : section.rules()) {
                        budget.spend();
                        if (Boolean.FALSE.equals(rule.enabled())) continue;
                        Match match = normalize(snapshot, section, rule, resolver, true);
                        out.add(new Entry(section, rule, match, l, match.problem() == null ? null : partial(snapshot, section, rule, resolver)));
                    }
                } catch (Unknown exhausted) { return null; }
                return List.copyOf(out);
            });
            if (list == null) throw new Unknown("analysis budget");
            entries.addAll(list);
        }
        return new Evaluation(entries, List.of(), asa);
    }
    /** The binding an ASA access-group section stands for, proven by the mapper's opaque section id, or null. */
    private static String asaBinding(PolicySnapshot snapshot, Section section) {
        String id = snapshot.metadata().id(), name = section.name();
        if (id == null || id.isBlank() || name == null || section.parentRuleId() != null) return null;
        if (name.equals("global") && ref(id, "global").equals(section.id())) return "global";
        for (String direction : List.of("in", "out")) {
            String suffix = " " + direction;
            if (name.endsWith(suffix)) {
                String nameif = name.substring(0, name.length() - suffix.length());
                if (!nameif.isEmpty() && ref(id, direction, nameif).equals(section.id())) return direction + ":" + nameif;
            }
        }
        return null;
    }
    /** The mapper's interface table object ("asa-interfaces": nameif / security-level pairs and the same-security flags). */
    private static Asa asaInterfaces(PolicySnapshot snapshot, Map<String, Integer> lists, Map<Integer, Section> sections) {
        Map<String, Integer> levels = new HashMap<>(); Set<String> interfaces = new HashSet<>();
        boolean inter = false, intra = false, known = false; int found = 0;
        for (var object : snapshot.objects().values()) {
            if (!"asa-interfaces".equals(object.type())) continue;
            found++; known = "RESOLVED".equals(object.status());
            String nameif = null;
            for (String value : object.values()) {
                int split = value.indexOf(": "); if (split < 0) { known = false; continue; }
                String key = value.substring(0, split), v = value.substring(split + 2);
                switch (key) {
                    case "nameif" -> { nameif = v; interfaces.add(v); }
                    case "physical" -> { if (nameif == null) known = false; }
                    case "security-level" -> {
                        if (nameif == null) { known = false; break; }
                        try { levels.put(nameif, Integer.parseInt(v)); } catch (NumberFormatException unknown) { levels.put(nameif, null); }
                        nameif = null;
                    }
                    case "same-security-inter" -> inter = v.equals("true");
                    case "same-security-intra" -> intra = v.equals("true");
                    default -> known = false;
                }
            }
        }
        return new Asa(Map.copyOf(lists), Collections.unmodifiableMap(levels), Set.copyOf(interfaces), inter, intra, known && found == 1,
            Map.copyOf(sections), Collections.synchronizedMap(new HashMap<>()));
    }
    /**
     * Cisco ASA, one packet from ingress interface {@code sourceZone} to egress interface {@code destinationZone} (nameif):
     * <ol>
     * <li>Same security level: denied before any access rule unless same-security-traffic permits it (A0-ASA-5).</li>
     * <li>Inbound: the ingress interface's "in" list, then the global list, then the implicit deny (A0-ASA-1, A0-ASA-3).
     * With neither list, higher to lower security level is allowed (A0-ASA-4a); anything else is UNKNOWN (A0-ASA-4b/4c
     * stay UNKNOWN by the PO decision record 2026-11-14 item 4).</li>
     * <li>Outbound: the egress interface's "out" list, then its implicit deny (A0-ASA-1, A0-ASA-2).</li>
     * </ol>
     * Both directions must allow: a DENIED step decides, otherwise any UNKNOWN step makes the answer UNKNOWN. Addresses are
     * the real (untranslated) ones (A0-ASA-6); NAT and routing are not evaluated.
     */
    private static Decision asa(PolicySnapshot snapshot, Evaluation evaluation, Query query, Range source, Range destination, Budget budget) {
        String in = query.sourceZone(), out = query.destinationZone(); Asa asa = evaluation.asa();
        if (in.isEmpty() || out.isEmpty()) return new Decision("UNKNOWN", "zone or target required", null, List.of());
        if (asa == null || !asa.known() || !asa.interfaces().contains(in) || !asa.interfaces().contains(out)
                || asa.levels().get(in) == null || asa.levels().get(out) == null)
            return new Decision("UNKNOWN", ASA_INTERFACE_UNKNOWN, null, List.of());
        int levelIn = asa.levels().get(in), levelOut = asa.levels().get(out);
        if (in.equals(out) && !asa.intra()) return new Decision("DENIED", ASA_SAME_INTRA, null, List.of());
        if (!in.equals(out) && levelIn == levelOut && !asa.inter()) return new Decision("DENIED", ASA_SAME_INTER, null, List.of());
        List<Map<String, Object>> steps = new ArrayList<>(); List<Map<String, Object>> undecidable = new ArrayList<>();
        Integer inbound = asa.lists().get("in:" + in), global = asa.lists().get("global"), outbound = asa.lists().get("out:" + out);
        Decision first;
        if (inbound == null && global == null)
            first = levelIn > levelOut ? new Decision("ALLOWED", ASA_LEVEL_ALLOW, null, List.of()) : new Decision("UNKNOWN", ASA_LEVEL_UNPROVEN, null, List.of());
        else first = list(snapshot, evaluation, query, source, destination, budget, l -> Objects.equals(l, inbound) || Objects.equals(l, global));
        Decision second = outbound == null ? null : list(snapshot, evaluation, query, source, destination, budget, l -> l == outbound);
        for (Decision step : second == null ? List.of(first) : List.of(first, second)) {
            var row = new LinkedHashMap<String, Object>();
            row.put("number", steps.size() + 1); row.put("verdict", step.verdict()); row.put("lookupReason", step.reason());
            row.put("decidingRule", step.rule()); row.put("undecidableRules", step.undecidable()); steps.add(row);
            undecidable.addAll(step.undecidable());
            if (undecidable.size() > 128) throw new Unknown("analysis budget");
        }
        if (second == null) return new Decision(first.verdict(), first.reason(), first.rule(), first.undecidable(), List.copyOf(steps));
        for (Decision step : List.of(first, second))
            if (step.verdict().equals("DENIED")) return new Decision("DENIED", step.reason(), step.rule(), step.undecidable(), List.copyOf(steps));
        boolean unknown = first.verdict().equals("UNKNOWN") || second.verdict().equals("UNKNOWN");
        return new Decision(unknown ? "UNKNOWN" : "ALLOWED", ASA_RESULTS, null, List.copyOf(undecidable), List.copyOf(steps));
    }
    /** First match over the given access lists in stored order; no match and nothing undecidable is the implicit deny. */
    private static Decision list(PolicySnapshot snapshot, Evaluation evaluation, Query query, Range source, Range destination, Budget budget,
            java.util.function.IntPredicate layers) {
        Decision decision = decide(snapshot, evaluation.entries(), query, source, destination, budget, null, layers, 0);
        if (decision.rule() == null && decision.undecidable().isEmpty()) return new Decision("DENIED", ASA_IMPLICIT_DENY, null, List.of());
        return decision;
    }
    private static boolean disjoint(Partial prior, Range source, Range destination, Query query) {
        var selectors = prior.selectors();
        if (selectors.get(0) != null && !overlaps(selectors.get(0), "ipv4", source)) return true;
        if (selectors.get(1) != null && !overlaps(selectors.get(1), "ipv4", destination)) return true;
        if (selectors.get(2) != null && !query.service().equals("any")) {
            long port = Long.parseLong(query.service().substring(4));
            if (!overlaps(selectors.get(2), query.service().substring(0, 3), new Range(port, port))) return true;
        }
        return false;
    }
    /** Some address or port of {@code range} lies in the selector (an any selector overlaps everything). */
    private static boolean overlaps(Selector selector, String family, Range range) {
        if (selector.any()) return true;
        for (Range r : selector.ranges().getOrDefault(family, List.of())) if (r.start() <= range.end() && r.end() >= range.start()) return true;
        return false;
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
