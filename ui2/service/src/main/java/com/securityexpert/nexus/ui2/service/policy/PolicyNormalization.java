package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import java.util.*;

/** Shared stored-policy normalization; identifiers remain opaque. */
final class PolicyNormalization {
    private PolicyNormalization() {}
    record Range(long start, long end) {}
    record Selector(boolean any, Map<String, List<Range>> ranges) {}
    record Match(Rule rule, List<Selector> selectors, Map<String, Set<String>> placements, String problem) {}
    static final class Unknown extends RuntimeException {
        Unknown(String reason) { super(reason, null, false, false); }
    }
    static final class Budget {
        final int[] left;
        Budget(int limit) { left = new int[]{limit}; }
        void spend() { if (--left[0] < 0) throw new Unknown("analysis budget"); }
    }
    /** Section IDs hash the layer UID recursively; the UID cannot be recovered from a snapshot. */
    static final class CpLayers {
        private record Layer(int index, String label, int lastNumber) {}
        private final Map<String, Layer> parents = new HashMap<>();
        private int next;
        int advance(Section section, Budget budget) {
            if (section.parentRuleId() != null && section.parentRuleId().isBlank())
                throw new Unknown("multiple ordered layers not evaluated");
            String label = null;
            boolean missingLabel = false;
            for (Rule rule : section.rules()) {
                budget.spend();
                var labels = rule.extras().getOrDefault("layer-name", List.of());
                if (labels.size() > 1) throw new Unknown("multiple ordered layers not evaluated");
                if (labels.isEmpty() || labels.get(0).isBlank()) {
                    missingLabel = true;
                    continue;
                }
                if (label != null && !label.equals(labels.get(0))) throw new Unknown("multiple ordered layers not evaluated");
                label = labels.get(0);
            }
            if (missingLabel && label != null) throw new Unknown("multiple ordered layers not evaluated");
            Layer previous = parents.get(section.parentRuleId());
            boolean split = previous != null && label != null && previous.label() != null && !label.equals(previous.label());
            if (previous != null && (label == null || previous.label() == null || split && section.parentRuleId() != null))
                throw new Unknown("multiple ordered layers not evaluated");
            // Labels only split runs; continuation requires strictly increasing rule numbers, never label equality alone.
            int last = previous == null || split ? 0 : previous.lastNumber();
            for (Rule rule : section.rules()) {
                budget.spend();
                if (rule.number() <= last) throw new Unknown("multiple ordered layers not evaluated");
                last = rule.number();
            }
            int index = previous == null || split ? next++ : previous.index();
            parents.put(section.parentRuleId(), new Layer(index, label, last));
            return index;
        }
    }
    static final class EvaluationScope {
        private final Metadata metadata;
        private final Set<Integer> layersSeen = new HashSet<>();
        private final CpLayers cpLayers = new CpLayers();
        private int previousLayer = -1;
        private final Set<String> sectionIds = new HashSet<>();
        private Section previous;
        private int lastPhase = -1;
        private boolean orderKnown = true;
        boolean unproven;
        EvaluationScope(Metadata metadata) { this.metadata = metadata; }
        boolean advance(Section section) {
            boolean joined = false;
            if (metadata.vendor().equals("CP") && "CP access layer".equals(section.source())) {
                try {
                    int layer = cpLayers.advance(section, new Budget(Integer.MAX_VALUE));
                    joined = previous != null && "CP access layer".equals(previous.source()) && previousLayer == layer;
                    if (!joined) unproven = layersSeen.contains(layer);
                    layersSeen.add(layer);
                    if (!sectionIds.add(section.id())) unproven = true;
                    previousLayer = layer;
                } catch (Unknown unknown) {
                    unproven = true;
                }
            } else if (metadata.vendor().equals("PAN")) {
                int phase = panPhase(section);
                boolean ordered = phase >= 0 && phase >= lastPhase && sectionIds.add(section.id());
                joined = previous != null && orderKnown && ordered;
                if (previous != null && !joined) unproven = true;
                orderKnown &= ordered;
                lastPhase = phase;
            } else {
                unproven = previous != null;
            }
            previous = section;
            return joined && !unproven;
        }
        int panPhase(Section section) {
            if (section.parentRuleId() != null || metadata.id() == null || metadata.id().isBlank()
                    || metadata.sourceId() == null || metadata.sourceId().isBlank()
                    || metadata.containerId() == null || metadata.containerId().isBlank()
                    || section.source() == null || section.source().isBlank()) return -1;
            // Verify the mapper's opaque section identity; display labels do not establish phase or lineage.
            var phases = List.of("pre-rulebase", "rulebase", "post-rulebase");
            for (int i = 0; i < phases.size(); i++)
                if (ref(metadata.id(), section.source(), phases.get(i)).equals(section.id())) return i;
            return -1;
        }
    }
    static Match normalize(PolicySnapshot snapshot, Section section, Rule rule, Resolver resolver) {
        return normalize(snapshot, section, rule, resolver, false);
    }
    static Match normalize(PolicySnapshot snapshot, Section section, Rule rule, Resolver resolver, boolean lookup) {
        try {
            if (!Set.of("CP", "PAN").contains(snapshot.metadata().vendor()) || section.source().equals("CP NAT rulebase"))
                throw new Unknown("unsupported rulebase");
            List<Selector> cells = List.of(resolver.cell(rule.source(), "address"), resolver.cell(rule.destination(), "address"),
                resolver.cell(rule.service(), "service"), snapshot.metadata().vendor().equals("CP") && rule.application().refs().isEmpty()
                    && !rule.application().negated() ? new Selector(true, Map.of()) : resolver.cell(rule.application(), "application"));
            if (snapshot.metadata().vendor().equals("PAN") && (rule.extras().getOrDefault("from", List.of()).isEmpty()
                    || rule.extras().getOrDefault("to", List.of()).isEmpty())) throw new Unknown("zone evidence not collected");
            if (snapshot.metadata().vendor().equals("CP") && rule.extras().getOrDefault("install-on", List.of()).isEmpty())
                throw new Unknown("install-on evidence not collected");
            if (rule.enabled() == null) throw new Unknown("enabled state is unknown");
            if (!(lookup && snapshot.metadata().vendor().equals("CP") && rule.action().equals("Apply Layer"))
                    && !Set.of("Accept", "Drop", "Reject", "allow", "deny", "drop", "reset-client", "reset-server", "reset-both").contains(rule.action()))
                throw new Unknown("non-terminal or unknown action");
            for (var entry : rule.extras().entrySet()) {
                String key = entry.getKey(); var values = entry.getValue();
                if (lookup && key.equals("inline-layer") && rule.action().equals("Apply Layer")) continue;
                if (Set.of("time", "schedule", "vpn", "content").contains(key)) {
                    for (String id : values) {
                        resolver.budget.spend(); var object = snapshot.objects().get(id);
                        if (object == null || !object.status().equals("RESOLVED") || !object.type().equals("any")) throw new Unknown("time or content constraint");
                    }
                } else if (Set.of("source-user", "category").contains(key) && !values.equals(List.of("any"))) throw new Unknown("user or category constraint");
                else if (key.equals("rule-type") && !values.equals(List.of("universal"))) throw new Unknown("zone rule type constraint");
                else if (key.equals("install-on")) {
                    for (String id : values) {
                        resolver.budget.spend(); var object = snapshot.objects().get(id);
                        if (object == null || !object.status().equals("RESOLVED")) throw new Unknown("unresolved install-on selector");
                    }
                } else if (!Set.of("from", "to", "install-on", "source-user", "category", "rule-type", "tag", "layer-name", "last-modified", "last-modifier", "log-setting").contains(key)
                        && !key.startsWith("track-") && !key.startsWith("profile-setting/")) throw new Unknown("unsupported rule constraint");
            }
            if (snapshot.metadata().vendor().equals("CP") && !rule.extras().containsKey("time")) throw new Unknown("time evidence not collected");
            Map<String, Set<String>> placements = new HashMap<>();
            for (String key : List.of("from", "to", "install-on")) {
                Set<String> values = new HashSet<>();
                for (String value : rule.extras().getOrDefault(key, List.of())) { resolver.budget.spend(); values.add(value); }
                placements.put(key, Set.copyOf(values));
            }
            return new Match(rule, cells, placements, null);
        } catch (Unknown problem) {
            if (problem.getMessage().equals("analysis budget")) throw problem;
            return new Match(rule, List.of(), Map.of(), problem.getMessage());
        }
    }
    static boolean disjoint(Match a, Match b, Map<String, PolicyObject> objects) {
        for (String key : List.of("from", "to", "install-on")) {
            var left = a.placements().get(key); var right = b.placements().get(key);
            if (left.isEmpty() || right.isEmpty()) continue;
            if (key.equals("install-on")) {
                // Resolved group membership and wildcard targets are not distinct atomic placements.
                if (java.util.stream.Stream.concat(left.stream(), right.stream()).anyMatch(id ->
                        objects.get(id).type().equals("any") || !objects.get(id).members().isEmpty())) continue;
            } else if (left.contains("any") || right.contains("any")) continue;
            if (Collections.disjoint(left, right)) return true;
        }
        return false;
    }
    static boolean compatible(Match covering, Match covered) {
        for (String key : List.of("from", "to", "install-on")) {
            var a = covering.placements().get(key); var b = covered.placements().get(key);
            // Exact opaque selector equality is safe; do not infer topology from display names.
            if ((key.equals("from") || key.equals("to")) && a.equals(Set.of("any"))) continue;
            if (!a.equals(b)) return false;
        }
        return true;
    }
    static boolean contains(Selector a, Selector b, Budget budget) {
        if (a.any()) return true;
        if (b.any()) return false;
        for (var entry : b.ranges().entrySet()) {
            var outer = a.ranges().getOrDefault(entry.getKey(), List.of()); int i = 0;
            for (Range inner : entry.getValue()) {
                budget.spend();
                while (i < outer.size() && outer.get(i).end() < inner.start()) { budget.spend(); i++; }
                if (i == outer.size() || outer.get(i).start() > inner.start() || outer.get(i).end() < inner.end()) return false;
            }
        }
        return true;
    }
    static final class Resolver {
        final Map<String, PolicyObject> objects; final Budget budget;
        final Map<String, Selector> memo = new HashMap<>();
        Resolver(Map<String, PolicyObject> objects, Budget budget) { this.objects = objects; this.budget = budget; }
        Selector cell(Cell cell, String family) {
            if (cell.negated()) throw new Unknown("negated selector");
            if (cell.refs().isEmpty()) throw new Unknown("missing selector");
            List<Selector> parts = new ArrayList<>();
            for (String id : cell.refs()) { budget.spend(); parts.add(object(id, family, new HashSet<>())); }
            return union(parts);
        }
        Selector object(String id, String family, Set<String> path) {
            budget.spend(); String key = family + ":" + id;
            if (memo.containsKey(key)) return memo.get(key);
            var o = objects.get(id);
            if (o == null || !o.status().equals("RESOLVED")) throw new Unknown("unresolved object");
            if (!path.add(id) || path.size() > 32) throw new Unknown("group cycle or depth limit");
            Selector result;
            if (o.type().equals("any")) result = new Selector(true, Map.of());
            else if (Set.of("group", "address-group", "service-group", "application-group").contains(o.type())) {
                if (!(family.equals("address") && Set.of("group", "address-group").contains(o.type())
                        || family.equals("service") && o.type().equals("service-group")
                        || family.equals("application") && o.type().equals("application-group"))) throw new Unknown("dynamic or unsupported object type");
                if (o.members().isEmpty() || !o.values().isEmpty()) throw new Unknown("empty or dynamic group");
                List<Selector> children = new ArrayList<>();
                for (String member : o.members()) children.add(object(member, family, path));
                result = union(children);
            } else if (family.equals("address") && Set.of("address", "host", "network", "address-range").contains(o.type())) result = address(o);
            else if (family.equals("service") && Set.of("service", "service-tcp", "service-udp").contains(o.type())) result = service(o);
            else throw new Unknown("dynamic or unsupported object type");
            path.remove(id); memo.put(key, result); return result;
        }
        Selector union(List<Selector> parts) {
            Map<String, List<Range>> ranges = new TreeMap<>(); boolean any = false;
            for (Selector part : parts) {
                any |= part.any();
                for (var entry : part.ranges().entrySet()) for (Range r : entry.getValue()) { budget.spend(); ranges.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(r); }
            }
            Map<String, List<Range>> merged = new TreeMap<>();
            ranges.forEach((key, list) -> {
                list.sort(Comparator.comparingLong(Range::start)); List<Range> out = new ArrayList<>();
                for (Range r : list) {
                    budget.spend();
                    if (!out.isEmpty() && out.get(out.size() - 1).end() + 1 >= r.start()) {
                        var previous = out.remove(out.size() - 1); out.add(new Range(previous.start(), Math.max(previous.end(), r.end())));
                    } else out.add(r);
                }
                merged.put(key, List.copyOf(out));
            });
            return new Selector(any, merged);
        }
        Selector address(PolicyObject o) {
            List<Range> ranges = new ArrayList<>(); Map<String, String> fields = fields(o);
            try {
                for (var entry : fields.entrySet()) {
                    String key = entry.getKey(), value = entry.getValue();
                    if (Set.of("ipv4-address", "ip-netmask", "ip-range", "literal").contains(key)) {
                        var r = PolicyRuleQuery.interval(value); ranges.add(new Range(r[0], r[1]));
                    } else if (!Set.of("subnet4", "mask-length4", "ip-address-first", "ip-address-last").contains(key)) throw new Unknown("unsupported address representation");
                }
                if (fields.containsKey("subnet4") || fields.containsKey("mask-length4")) {
                    var r = PolicyRuleQuery.interval(fields.get("subnet4") + "/" + fields.get("mask-length4")); ranges.add(new Range(r[0], r[1]));
                }
                if (fields.containsKey("ip-address-first") || fields.containsKey("ip-address-last")) {
                    var r = PolicyRuleQuery.interval(fields.get("ip-address-first") + "-" + fields.get("ip-address-last")); ranges.add(new Range(r[0], r[1]));
                }
            } catch (IllegalArgumentException invalid) { throw new Unknown("unsupported address representation"); }
            if (ranges.isEmpty()) throw new Unknown("missing address values");
            return union(List.of(new Selector(false, Map.of("ipv4", ranges))));
        }
        Map<String, String> fields(PolicyObject o) {
            if (!o.members().isEmpty()) throw new Unknown("unsupported object members");
            Map<String, String> fields = new LinkedHashMap<>();
            for (String value : o.values()) {
                budget.spend(); int split = value.indexOf(": ");
                if (fields.put(split < 0 ? "literal" : value.substring(0, split), split < 0 ? value : value.substring(split + 2)) != null)
                    throw new Unknown("ambiguous object values");
            }
            return fields;
        }
        Selector service(PolicyObject o) {
            var fields = fields(o); Map<String, List<Range>> ranges = new TreeMap<>();
            for (var entry : fields.entrySet()) {
                String key = entry.getKey(); if (key.equals("protocol")) continue;
                String protocol = key.equals("port") ? fields.get("protocol") : key.equals("protocol/tcp/port") ? "tcp" : key.equals("protocol/udp/port") ? "udp" : null;
                if (protocol == null || !Set.of("tcp", "udp").contains(protocol)) throw new Unknown("unsupported service constraint");
                for (String port : entry.getValue().split(",", -1)) {
                    budget.spend();
                    if (!port.trim().matches("[0-9]+(?:-[0-9]+)?")) throw new Unknown("unsupported port expression");
                    try {
                        String[] ends = port.trim().split("-"); int a = Integer.parseInt(ends[0]), b = Integer.parseInt(ends[ends.length - 1]);
                        if (a < 0 || b > 65535 || a > b) throw new NumberFormatException();
                        ranges.computeIfAbsent(protocol, p -> new ArrayList<>()).add(new Range(a, b));
                    } catch (NumberFormatException invalid) { throw new Unknown("invalid port range"); }
                }
            }
            if (ranges.isEmpty()) throw new Unknown("missing service values");
            return union(List.of(new Selector(false, ranges)));
        }
    }}
