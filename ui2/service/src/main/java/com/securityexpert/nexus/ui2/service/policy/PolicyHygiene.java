package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import java.time.*;
import java.util.*;

/** Pure stored-evidence analysis over proven, ordered evaluation scopes. */
public final class PolicyHygiene {
    private PolicyHygiene() {}
    public static final Set<String> CLASSES = Set.of("shadowed", "conflict", "disabled", "unused", "expired", "broad", "unknown");
    public static final Set<String> SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");
    public record Finding(String findingClass, String severity, String evidence) {}
    public record Assessment(String shadowStatus, String shadowReason, String coveringRuleId,
            List<Finding> findings, Map<String, Object> permissiveness, String timeStatus, boolean expiring,
            String counterWindow, String hitSource, String hitsCollectedAt) {}
    public record Result(Map<String, Assessment> rules, boolean budgetReached) {}
    private record Range(long start, long end) {}
    private record Selector(boolean any, Map<String, List<Range>> ranges) {}
    private record Match(Rule rule, List<Selector> selectors, Map<String, Set<String>> placements, String problem) {}
    private static final class Unknown extends RuntimeException {
        Unknown(String reason) { super(reason, null, false, false); }
    }
    private static final class Budget {
        final int[] left;
        Budget(int limit) { left = new int[]{limit}; }
        void spend() { if (--left[0] < 0) throw new Unknown("analysis budget"); }
    }
    public static Result analyze(PolicySnapshot snapshot, int days, Instant now, int workLimit) {
        if (days < 1 || days > 36500 || workLimit < 0) throw new IllegalArgumentException("Invalid hygiene limits");
        Budget budget = new Budget(workLimit);
        var resolver = new Resolver(snapshot.objects(), budget);
        Map<String, Assessment> results = new LinkedHashMap<>();
        var scope = new EvaluationScope(snapshot.metadata());
        List<Match> earlier = new ArrayList<>();
        boolean earlierUnknown = false;
        for (Section section : snapshot.sections()) {
            if (!scope.advance(section)) {
                earlier.clear();
                earlierUnknown = false;
            }
            for (Rule rule : section.rules()) {
                List<Finding> findings = new ArrayList<>();
                String status = "UNKNOWN", reason = "analysis budget", covering = null;
                Map<String, Object> broad = Map.of("level", "Unknown", "reasons", List.of("Analysis budget reached"), "score", 0);
                String timeStatus = "unknown"; boolean expiring = false;
                if (Boolean.FALSE.equals(rule.enabled())) findings.add(finding("disabled", "LOW", "Rule is disabled."));
                else if (rule.enabled() == null) findings.add(unknown("Enabled state is unknown."));
                try {
                    budget.spend();
                    Match match = normalize(snapshot, section, rule, resolver);
                    broad = PolicyRuleMetrics.permissiveness(rule, snapshot.objects(), budget.left);
                    var time = PolicyRuleMetrics.time(rule, snapshot.objects(), now, snapshot.metadata().vendor(), budget.left);
                    budget.spend();
                    timeStatus = time.status(); expiring = time.expiring();
                    if (timeStatus.equals("expired")) findings.add(finding("expired", "LOW", "Stored schedule has expired."));
                    else if (expiring) findings.add(finding("expired", "LOW", "Stored schedule expires within 14 days."));
                    else if (timeStatus.equals("unknown")) findings.add(unknown("Schedule evidence is incomplete."));
                    if (broad.get("level").equals("Unknown")) findings.add(unknown("Permissiveness cannot be established from resolved selectors."));
                    else if (!broad.get("level").equals("Low")) {
                        var reasons = (List<?>) broad.get("reasons");
                        boolean anyAccept = accepting(rule.action()) && reasons.containsAll(List.of("Any source", "Any destination", "Any service"))
                            && (reasons.contains("Any application") || snapshot.metadata().vendor().equals("CP") && rule.application().refs().isEmpty());
                        findings.add(finding("broad", anyAccept ? "HIGH" : "MEDIUM", anyAccept
                            ? "Accept rule matches any source, destination, service and application."
                            : "Broad selectors are present; see permissiveness reasons."));
                    }
                    reason = match.problem();
                    if (scope.unproven) reason = "earlier sections not analysed";
                    if (reason == null) {
                        status = "NOT_SHADOWED";
                        if (earlierUnknown) reason = "earlier rule has unsupported constraints";
                        for (Match prior : earlier) {
                            budget.spend();
                            if (disjoint(prior, match, snapshot.objects())) continue;
                            if (!compatible(prior, match)) { reason = "zone or install-on coverage is unproven"; continue; }
                            boolean covers = true;
                            for (int i = 0; i < 4 && covers; i++) covers = contains(prior.selectors().get(i), match.selectors().get(i), budget);
                            if (!covers) continue;
                            covering = prior.rule().id();
                            status = prior.rule().action().equals(rule.action()) ? "REDUNDANT" : "CONFLICT";
                            reason = null;
                            findings.add(finding(status.equals("CONFLICT") ? "conflict" : "shadowed", status.equals("CONFLICT") ? "HIGH" : "MEDIUM",
                                status.equals("CONFLICT") ? "Earlier enabled rule fully covers this match with a different action."
                                    : "Earlier enabled rule fully covers this match with the same action."));
                            break;
                        }
                        if (covering == null && reason != null) status = "UNKNOWN";
                    }
                    if (!Boolean.FALSE.equals(rule.enabled())) {
                        if (match.problem() == null) earlier.add(match);
                        else earlierUnknown = true;
                    }
                } catch (Unknown limit) { status = "UNKNOWN"; reason = limit.getMessage(); }
                if (status.equals("NOT_SHADOWED") && !snapshot.failures().isEmpty()) {
                    status = "UNKNOWN"; reason = "snapshot collection incomplete";
                }
                if (status.equals("UNKNOWN")) findings.add(unknown("Shadowing: " + reason + "."));
                String window = unused(snapshot, rule, days, findings);
                var hits = rule.hitCounts();
                results.put(rule.id(), new Assessment(status, reason, covering, List.copyOf(findings), broad, timeStatus, expiring,
                    window, hits == null ? "UNKNOWN" : hits.source(), hits == null ? null : hits.collectedAt()));
            }
        }
        return new Result(Collections.unmodifiableMap(results), budget.left[0] < 0);
    }
    private static final class EvaluationScope {
        private final Metadata metadata;
        private final Set<String> parents = new HashSet<>();
        private final Set<String> sectionIds = new HashSet<>();
        private Section previous;
        private int lastNumber, lastPhase = -1;
        private boolean orderKnown = true;
        boolean unproven;
        EvaluationScope(Metadata metadata) { this.metadata = metadata; }
        boolean advance(Section section) {
            boolean joined = false;
            if (metadata.vendor().equals("CP") && "CP access layer".equals(section.source())) {
                joined = previous != null && "CP access layer".equals(previous.source())
                    && Objects.equals(previous.parentRuleId(), section.parentRuleId());
                if (!joined) {
                    unproven = parents.contains(section.parentRuleId());
                    lastNumber = 0;
                    orderKnown = true;
                }
                parents.add(section.parentRuleId());
                boolean ordered = orderKnown && sectionIds.add(section.id())
                    && (section.parentRuleId() == null || !section.parentRuleId().isBlank());
                for (Rule rule : section.rules()) {
                    ordered &= rule.number() > lastNumber;
                    lastNumber = rule.number();
                }
                if (joined && !ordered) unproven = true;
                orderKnown = ordered;
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
        private int panPhase(Section section) {
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
    private static Finding finding(String cls, String severity, String evidence) { return new Finding(cls, severity, evidence); }
    private static Finding unknown(String evidence) { return finding("unknown", "LOW", evidence); }
    private static boolean accepting(String action) { return Set.of("Accept", "allow").contains(action); }
    private static Match normalize(PolicySnapshot snapshot, Section section, Rule rule, Resolver resolver) {
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
            if (!Set.of("Accept", "Drop", "Reject", "allow", "deny", "drop", "reset-client", "reset-server", "reset-both").contains(rule.action()))
                throw new Unknown("non-terminal or unknown action");
            for (var entry : rule.extras().entrySet()) {
                String key = entry.getKey(); var values = entry.getValue();
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
    private static boolean disjoint(Match a, Match b, Map<String, PolicyObject> objects) {
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
    private static boolean compatible(Match covering, Match covered) {
        for (String key : List.of("from", "to", "install-on")) {
            var a = covering.placements().get(key); var b = covered.placements().get(key);
            // Exact opaque selector equality is safe; do not infer topology from display names.
            if ((key.equals("from") || key.equals("to")) && a.equals(Set.of("any"))) continue;
            if (!a.equals(b)) return false;
        }
        return true;
    }
    private static boolean contains(Selector a, Selector b, Budget budget) {
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
    private static String unused(PolicySnapshot snapshot, Rule rule, int days, List<Finding> findings) {
        var h = rule.hitCounts(); String window = "Counter window unknown; first hit is not a counter start.";
        if (h == null || h.hits() == null || h.hits() < 0 || parse(h.collectedAt()) == null) {
            findings.add(unknown("Hit counts were not collected or are invalid.")); return window;
        }
        if (h.firewalls().stream().anyMatch(f -> f.hits() == null) || ("device".equals(h.source()) && snapshot.metadata().targets().stream()
                .anyMatch(t -> h.firewalls().stream().noneMatch(f -> f.deviceId().equals(t.deviceId()) && Objects.equals(f.context(), t.context()))))) {
            findings.add(unknown("Hit counts are missing for a target.")); return window;
        }
        Instant at = parse(h.collectedAt()), last = parse(h.lastHit());
        if (h.lastHit() != null && (last == null || last.isAfter(at))) {
            findings.add(unknown("Last-hit timestamp is invalid.")); return window;
        }
        Instant newestCreation = h.firewalls().stream().map(f -> parse(f.createdAt())).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        if (newestCreation != null) {
            long age = Duration.between(newestCreation, at).toDays();
            // Creation is only an upper bound: a later counter reset may shorten it further.
            window = "Counter window unknown; bounded above by rule age: " + Math.max(0, age) + " days.";
            if (h.hits() == 0 && age < days) { findings.add(unknown("Zero-hit counter window is shorter than the threshold.")); return window; }
        }
        if (h.hits() == 0 && last != null) findings.add(unknown("Zero hits contradict a recorded last hit."));
        else if (h.hits() == 0) findings.add(finding("unused", "LOW", "Zero collected hits; counter reset/window is unknown."));
        else if (last == null) findings.add(unknown("Last-hit time was not collected."));
        else if (!last.isAfter(at.minus(Duration.ofDays(days)))) findings.add(finding("unused", "LOW", "Last hit predates the threshold at counter collection time."));
        return window;
    }
    private static Instant parse(String value) { try { return Instant.parse(value); } catch (RuntimeException invalid) { return null; } }

    private static final class Resolver {
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
    }
}
