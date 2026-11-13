package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.function.LongFunction;

/** Only the returned aggregate shape may cross the persistence boundary. All row state is ephemeral. */
public final class OfflineAnalysis {
    private OfflineAnalysis() {}
    public static final String CAUTION = "Verify before applying; sample may be incomplete. No changes are applied.";
    public static final String COMPARISON = "Disagreement may indicate a stale snapshot or NAT/routing effects. SIEM names are compared exactly; ambiguous names are UNKNOWN.";
    public static final String ATTRIBUTION = "Counts describe attributed sample flows, not device hit counters. Period bounds use valid rows only. An ordered-layer flow can contribute to more than one rule. Unseen means not seen in attributed flows only. UNKNOWN reason counts can overlap.";
    public static final String SET_LIMIT = "Exact observed sets exceed 50 entries; no bounded narrowing suggestion is available.";
    private static final int WORK = 250_000;
    private static final int MAX_DISTINCT = 500_000;
    private static final class RowProblem extends RuntimeException {
        RowProblem(String reason) { super(reason, null, false, false); }
    }
    private static final class Traffic {
        final Section section; final Rule rule;
        final NavigableMap<Long, Long> source = new TreeMap<>(), destination = new TreeMap<>(), service = new TreeMap<>();
        long flows; Instant first, last;
        Traffic(Section section, Rule rule) { this.section = section; this.rule = rule; }
        void time(Instant time) {
            if (time != null) { if (first == null || time.isBefore(first)) first = time; if (last == null || time.isAfter(last)) last = time; }
        }
    }
    public static Map<String, Object> analyze(PolicySnapshot snapshot, InputStream input, Map<String, String> mapping) throws IOException {
        return analyze(snapshot, input, mapping, OfflineCsv.MAX_BYTES, OfflineCsv.MAX_ROWS, null);
    }
    /** One-rule mode when {@code ruleId} is not null: exact match against a rule's id or uuid, RULE_NOT_FOUND / RULE_AMBIGUOUS otherwise. */
    public static Map<String, Object> analyze(PolicySnapshot snapshot, InputStream input, Map<String, String> mapping, String ruleId) throws IOException {
        return analyze(snapshot, input, mapping, OfflineCsv.MAX_BYTES, OfflineCsv.MAX_ROWS, ruleId);
    }
    static Map<String, Object> analyze(PolicySnapshot snapshot, InputStream input, Map<String, String> mapping, long maxBytes, int maxRows) throws IOException {
        return analyze(snapshot, input, mapping, maxBytes, maxRows, null);
    }
    static Map<String, Object> analyze(PolicySnapshot snapshot, InputStream input, Map<String, String> mapping, long maxBytes, int maxRows, String ruleId) throws IOException {
        var csv = new OfflineCsv(input, maxBytes); var headers = csv.next(); var columns = OfflineCsv.mapping(headers, mapping);
        var engine = PolicyLookup.prepare(snapshot, WORK);
        var traffic = new LinkedHashMap<String, Traffic>(); var names = new HashMap<String, Set<String>>();
        for (Section section : snapshot.sections()) {
            if (PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), section)) continue;
            for (Rule rule : section.rules()) {
                if (traffic.putIfAbsent(rule.id(), new Traffic(section, rule)) != null)
                    throw new IllegalArgumentException("SNAPSHOT_IDENTITY: duplicate rule identifiers");
                names.computeIfAbsent(rule.id(), k -> new HashSet<>()).add(rule.id());
                if (rule.uuid() != null && !rule.uuid().isBlank()) names.computeIfAbsent(rule.uuid(), k -> new HashSet<>()).add(rule.id());
                if (rule.name() != null && !rule.name().isBlank()) names.computeIfAbsent(rule.name(), k -> new HashSet<>()).add(rule.id());
            }
        }
        String chosen = ruleId == null ? null : resolveRule(snapshot, traffic, ruleId);
        long otherRuleRows = 0;
        Map<String, Long> parse = new TreeMap<>(), unknown = new TreeMap<>(), verdicts = new TreeMap<>();
        long rows = 0, invalid = 0, flows = 0, attributed = 0, agreements = 0, disagreements = 0, comparisonUnknown = 0;
        int distinct = 0; Instant first = null, last = null;
        List<String> row;
        while ((row = csv.next()) != null) {
            if (++rows > maxRows) throw new IllegalArgumentException("CSV_ROW_LIMIT: maximum 2000000 rows");
            if (Thread.currentThread().isInterrupted()) throw new IOException("ANALYSIS_INTERRUPTED");
            long count; Instant time; String source, destination, protocol, port, sourceZone, destinationZone;
            try {
                if (row.size() != headers.size()) throw new RowProblem("column count mismatch");
                count = count(value(row, columns, "count"), columns.containsKey("count"));
                time = time(value(row, columns, "time"), columns.containsKey("time"));
                source = address(value(row, columns, "source")); destination = address(value(row, columns, "destination"));
                port = value(row, columns, "port");
                if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) > 65535) throw new RowProblem("invalid destination port");
                port = Integer.toString(Integer.parseInt(port));
                protocol = value(row, columns, "protocol").toLowerCase(Locale.ROOT);
                if (protocol.equals("6")) protocol = "tcp"; if (protocol.equals("17")) protocol = "udp";
                if (!protocol.matches("[a-z0-9-]{1,20}")) throw new RowProblem("invalid protocol");
                sourceZone = zone(rawValue(row, columns, "sourceZone")); destinationZone = zone(rawValue(row, columns, "destinationZone"));
            } catch (RowProblem bad) { invalid++; parse.merge(bad.getMessage(), 1L, Long::sum); continue; }
            flows += count;
            if (time != null) { if (first == null || time.isBefore(first)) first = time; if (last == null || time.isAfter(last)) last = time; }
            if (source.contains(":") || destination.contains(":") || !Set.of("tcp", "udp").contains(protocol)) {
                unknown.merge(source.contains(":") || destination.contains(":") ? "IPv6 unsupported" : "protocol unsupported", count, Long::sum);
                verdicts.merge("UNKNOWN", count, Long::sum); if (columns.containsKey("rule")) comparisonUnknown += count; continue;
            }
            var result = engine.evaluate(new PolicyLookup.Query(source, destination, protocol + "/" + port, sourceZone, destinationZone, null, null)).body();
            String verdict = (String) result.get("verdict"); verdicts.merge(verdict, count, Long::sum);
            if (!Set.of("ALLOWED", "DENIED").contains(verdict)) {
                Set<String> reasons = new LinkedHashSet<>(); unknownReasons(result, reasons);
                for (String reason : reasons) unknown.merge(reason, count, Long::sum);
                // Preserve detailed uncertainty without attributing a first proven match after an uncertain rule.
                if (columns.containsKey("rule")) comparisonUnknown += count;
                continue;
            }
            Set<String> decided = new LinkedHashSet<>();
            addDecision(result, decided);
            for (Object layer : (List<?>) result.get("layers")) {
                var decision = (Map<?, ?>) layer;
                if (Set.of("ALLOWED", "DENIED").contains(decision.get("verdict"))) addDecision(decision, decided);
            }
            if (decided.isEmpty()) { unknown.merge("no deciding rule", count, Long::sum); if (columns.containsKey("rule")) comparisonUnknown += count; continue; }
            if (chosen != null && !decided.contains(chosen)) { otherRuleRows += count; continue; }
            attributed += count;
            for (String id : decided) {
                var rule = traffic.get(id);
                if (rule == null) throw new IllegalArgumentException("SNAPSHOT_IDENTITY: deciding rule missing");
                if (chosen != null && !id.equals(chosen)) continue;
                rule.flows += count; rule.time(time);
                distinct += add(rule.source, PolicyRuleQuery.interval(source)[0], count);
                distinct += add(rule.destination, PolicyRuleQuery.interval(destination)[0], count);
                distinct += add(rule.service, (protocol.equals("udp") ? 65536L : 0) + Integer.parseInt(port), count);
                if (distinct > MAX_DISTINCT) throw new IllegalArgumentException("AGGREGATE_LIMIT: maximum 500000 distinct rule/value aggregates; split the sample");
            }
            if (columns.containsKey("rule")) {
                String reported = rawValue(row, columns, "rule"); Set<String> matches = names.get(reported);
                // There is no single deciding rule for an all-allow ordered-layer result.
                Object finalRule = result.get("decidingRule");
                if (reported.isEmpty() || finalRule == null || matches != null && matches.size() != 1) comparisonUnknown += count;
                else if (matches != null && matches.contains(((Map<?, ?>) finalRule).get("ruleId"))) agreements += count;
                else disagreements += count;
            }
        }
        if (rows == 0) throw new IllegalArgumentException("CSV_EMPTY: at least one data row is required");
        var out = new LinkedHashMap<String, Object>();
        out.put("mode", chosen == null ? "WHOLE_POLICY" : "ONE_RULE");
        if (chosen != null) { out.put("ruleId", chosen); out.put("otherRuleRows", otherRuleRows); }
        out.put("policyId", snapshot.metadata().id()); out.put("collectedAt", snapshot.metadata().collectedAt());
        out.put("rowCount", rows); out.put("invalidRows", invalid); out.put("flowCount", flows); out.put("attributedFlows", attributed);
        out.put("periodStart", text(first)); out.put("periodEnd", text(last));
        out.put("parseStats", statistics(parse)); out.put("unknownStats", statistics(unknown)); out.put("verdictStats", statistics(verdicts));
        out.put("agreementCount", agreements); out.put("disagreementCount", disagreements); out.put("comparisonUnknownCount", comparisonUnknown);
        out.put("comparisonEnabled", columns.containsKey("rule")); out.put("comparisonNote", COMPARISON); out.put("attributionNote", ATTRIBUTION);
        out.put("limitations", PolicyLookup.LIMITATIONS); out.put("caution", CAUTION);
        var results = new ArrayList<Map<String, Object>>();
        for (Traffic rule : traffic.values()) if (chosen == null || rule.rule.id().equals(chosen)) results.add(finish(snapshot, rule, rows, first, last, chosen != null));
        results.sort(Comparator.comparingLong((Map<String, Object> r) -> (Long) r.get("flowCount")).reversed());
        out.put("rules", results); return out;
    }
    /** Exact equality with a rule's id or uuid only: no name match, no normalization. Returns the resolved rule id. */
    private static String resolveRule(PolicySnapshot snapshot, Map<String, Traffic> traffic, String ruleId) {
        Set<String> found = new LinkedHashSet<>(); int matches = 0;
        for (Section section : snapshot.sections()) for (Rule rule : section.rules())
            if (ruleId.equals(rule.id()) || ruleId.equals(rule.uuid())) { matches++; found.add(rule.id()); }
        if (matches == 0) throw new IllegalArgumentException("RULE_NOT_FOUND: no rule of the snapshot has this identifier");
        if (matches > 1) throw new IllegalArgumentException("RULE_AMBIGUOUS: more than one rule of the snapshot has this identifier");
        // A rule outside security evaluation has no traffic entry and cannot be analysed.
        if (!traffic.containsKey(found.iterator().next())) throw new IllegalArgumentException("RULE_NOT_FOUND: no rule of the snapshot has this identifier");
        return found.iterator().next();
    }
    private static void unknownReasons(Map<?, ?> decision, Set<String> reasons) {
        if (decision.get("lookupReason") instanceof String reason) reasons.add(reason);
        for (String field : List.of("undecidableRules", "layers")) if (decision.get(field) instanceof List<?> nested)
            for (Object item : nested) if (item instanceof Map<?, ?> row && !"ALLOWED".equals(row.get("verdict")) && !"DENIED".equals(row.get("verdict")))
                unknownReasons(row, reasons);
    }
    private static void addDecision(Map<?, ?> result, Set<String> out) {
        if (result.get("decidingRule") instanceof Map<?, ?> rule) out.add((String) rule.get("ruleId"));
    }
    private static int add(Map<Long, Long> values, long value, long count) { boolean fresh = !values.containsKey(value); values.merge(value, count, Long::sum); return fresh ? 1 : 0; }
    private static String rawValue(List<String> row, Map<String, Integer> columns, String field) { return columns.containsKey(field) ? row.get(columns.get(field)) : ""; }
    private static String value(List<String> row, Map<String, Integer> columns, String field) { return rawValue(row, columns, field).trim(); }
    private static long count(String value, boolean present) {
        if (!present) return 1;
        if (!value.matches("[0-9]{1,10}")) throw new RowProblem("invalid count");
        long count = Long.parseLong(value); if (count < 1 || count > 1_000_000_000) throw new RowProblem("invalid count"); return count;
    }
    private static Instant time(String value, boolean present) {
        if (!present) return null;
        try {
            Instant time = value.matches("[0-9]{10}") ? Instant.ofEpochSecond(Long.parseLong(value))
                : value.matches("[0-9]{13}") ? Instant.ofEpochMilli(Long.parseLong(value)) : Instant.parse(value);
            if (time.isBefore(Instant.parse("1970-01-01T00:00:00Z")) || time.isAfter(Instant.parse("9999-12-31T23:59:59Z"))) throw new IllegalArgumentException();
            return time;
        } catch (RuntimeException invalid) { throw new RowProblem("invalid time (use ISO-8601 UTC or epoch seconds/milliseconds)"); }
    }
    private static String address(String value) {
        if (value.contains(":")) {
            if (!value.matches("[0-9a-fA-F:.]{2,45}")) throw new RowProblem("invalid IP address");
            try { java.net.InetAddress.getByName(value); return value; }
            catch (java.net.UnknownHostException invalid) { throw new RowProblem("invalid IP address"); }
        }
        try { if (!value.matches("[0-9.]{7,15}")) throw new IllegalArgumentException(); PolicyRuleQuery.interval(value); return value; }
        catch (IllegalArgumentException invalid) { throw new RowProblem("invalid IP address"); }
    }
    private static String zone(String value) {
        if (value.length() > 200 || value.chars().anyMatch(Character::isISOControl)) throw new RowProblem("invalid zone"); return value;
    }
    private static String text(Instant value) { return value == null ? "" : value.toString(); }
    private static List<Map<String, Object>> statistics(Map<String, Long> stats) {
        return stats.entrySet().stream().map(e -> Map.<String, Object>of("statReason", e.getKey(), "count", e.getValue())).toList();
    }
    private static Map<String, Object> finish(PolicySnapshot snapshot, Traffic traffic, long rows, Instant first, Instant last, boolean oneRule) {
        String[] problem = new String[1];
        var out = finish(snapshot, traffic, rows, first, last, problem);
        if (oneRule) out.put("objectUsage", Usage.list(snapshot, traffic, problem[0]));
        return out;
    }
    private static Map<String, Object> finish(PolicySnapshot snapshot, Traffic traffic, long rows, Instant first, Instant last, String[] ruleProblem) {
        Rule rule = traffic.rule; var out = new LinkedHashMap<String, Object>();
        out.put("ruleId", rule.id()); out.put("name", rule.name()); out.put("number", rule.number()); out.put("action", rule.action());
        out.put("sectionId", traffic.section.id()); out.put("sectionName", traffic.section.name()); out.put("flowCount", traffic.flows);
        out.put("firstTime", text(traffic.first)); out.put("lastTime", text(traffic.last));
        out.put("distinctSources", traffic.source.size()); out.put("distinctDestinations", traffic.destination.size()); out.put("distinctServices", traffic.service.size());
        var top = new ArrayList<Map<String, Object>>();
        top(top, traffic.source, "observedSource", OfflineAnalysis::ip); top(top, traffic.destination, "observedDestination", OfflineAnalysis::ip);
        top(top, traffic.service, "observedService", OfflineAnalysis::service);
        top.sort(Comparator.comparingLong((Map<String, Object> e) -> (Long) e.get("count")).reversed());
        out.put("topObserved", top.stream().limit(50).toList());
        out.put("zeroTraffic", traffic.flows == 0 ? "No traffic in this sample (period " + (first == null ? "UNKNOWN" : text(first)) + "–"
            + (last == null ? "UNKNOWN" : text(last)) + ", " + rows + " rows)." : ""); out.put("severity", "LOW");
        var budget = new Budget(WORK); var resolver = new Resolver(snapshot.objects(), budget);
        var unseen = new ArrayList<Map<String, Object>>();
        out.put("unseenMembers", unseen); out.put("narrowing", Map.of());
        try {
            var match = normalize(snapshot, traffic.section, rule, resolver, true);
            ruleProblem[0] = match.problem();
            if (match.problem() != null || !snapshot.failures().isEmpty()) {
                out.put("memberStatus", "UNKNOWN"); out.put("lookupReason", match.problem() == null ? "snapshot collection incomplete" : match.problem()); return out;
            }
            List<Cell> cells = List.of(rule.source(), rule.destination(), rule.service());
            List<NavigableMap<Long, Long>> observed = List.of(traffic.source, traffic.destination, traffic.service);
            for (int i = 0; i < 3; i++) {
                String family = i == 2 ? "service" : "address";
                Set<String> ids = new LinkedHashSet<>();
                for (String id : cells.get(i).refs()) members(snapshot, id, ids, new HashSet<>(), budget);
                for (String id : ids) {
                    var selector = resolver.object(id, family, new HashSet<>());
                    if (selector.any()) continue;
                    if (!seen(selector, observed.get(i), i == 2)) unseen.add(Map.of("memberId", id, "name", snapshot.objects().get(id).name(),
                        "dimension", List.of("source", "destination", "service").get(i)));
                    if (unseen.size() > 1000) throw new Unknown("analysis budget");
                }
            }
            out.put("memberStatus", "RESOLVED");
            boolean broad = match.selectors().subList(0, 3).stream().anyMatch(s -> s.any() || s.ranges().getOrDefault("ipv4", List.of()).stream().anyMatch(r -> r.end() - r.start() >= 65535));
            if (broad && traffic.flows > 0) {
                var source = cidrs(traffic.source.navigableKeySet()); var destination = cidrs(traffic.destination.navigableKeySet()); var services = ports(traffic.service.navigableKeySet());
                if (source.size() > 50 || destination.size() > 50 || services.size() > 50)
                    out.put("narrowing", Map.of("memberStatus", "UNKNOWN", "suggestionNote", SET_LIMIT));
                else out.put("narrowing", Map.of("memberStatus", "RESOLVED", "observedSource", source, "observedDestination", destination,
                    "observedService", services, "coveragePercent", 100, "coveredFlows", traffic.flows, "caution", CAUTION));
            }
        } catch (Unknown unknown) { unseen.clear(); out.put("memberStatus", "UNKNOWN"); out.put("lookupReason", unknown.getMessage()); }
        return out;
    }
    private static void members(PolicySnapshot snapshot, String id, Set<String> ids, Set<String> path, Budget budget) {
        budget.spend(); if (!path.add(id) || path.size() > 32) throw new Unknown("group cycle or depth limit");
        var object = snapshot.objects().get(id); if (object == null || !object.status().equals("RESOLVED")) throw new Unknown("unresolved object");
        ids.add(id); for (String child : object.members()) members(snapshot, child, ids, path, budget); path.remove(id);
    }
    private static boolean seen(Selector selector, NavigableMap<Long, Long> values, boolean service) {
        for (var family : selector.ranges().entrySet()) for (Range range : family.getValue()) {
            long offset = service && family.getKey().equals("udp") ? 65536 : 0;
            Long first = values.ceilingKey(range.start() + offset); if (first != null && first <= range.end() + offset) return true;
        }
        return false;
    }
    /** One-rule mode: per-object USED / NOT_SEEN / NOT_AUDITABLE / UNKNOWN states, tree order. */
    private static final class Usage {
        static final int MAX = 2000;
        static final Set<String> GROUPS = Set.of("group", "address-group", "service-group");
        static final String INCOMPLETE = "snapshot collection incomplete";
        final PolicySnapshot snapshot; final Resolver resolver; final Budget budget = new Budget(WORK);
        final List<Map<String, Object>> out = new ArrayList<>(); boolean full;
        String dimension, family, blanket, ruleProblem; boolean negated, service; Observed observed;
        Usage(PolicySnapshot snapshot) { this.snapshot = snapshot; resolver = new Resolver(snapshot.objects(), budget); }
        static List<Map<String, Object>> list(PolicySnapshot snapshot, Traffic traffic, String ruleProblem) {
            var usage = new Usage(snapshot); Rule rule = traffic.rule;
            List<Cell> cells = List.of(rule.source(), rule.destination(), rule.service());
            List<NavigableMap<Long, Long>> values = List.of(traffic.source, traffic.destination, traffic.service);
            for (int i = 0; i < 3 && !usage.full; i++) {
                Cell cell = cells.get(i);
                usage.dimension = List.of("source", "destination", "service").get(i); usage.service = i == 2; usage.family = i == 2 ? "service" : "address";
                usage.negated = cell.negated(); usage.observed = new Observed(values.get(i));
                usage.blanket = !snapshot.failures().isEmpty() ? INCOMPLETE : null;
                // A rule the lookup engine cannot evaluate gets no attributed flows, so nothing in it may be called unseen.
                usage.ruleProblem = ruleProblem == null ? null : "rule cannot be evaluated: " + ruleProblem;
                for (String id : cell.refs()) usage.node(id, null, 0, new HashSet<>());
            }
            return usage.out;
        }
        void node(String id, String parent, int depth, Set<String> path) {
            if (full) return;
            if (out.size() >= MAX) { full = true; out.add(entry(dimension, "", "", null, 0, "UNKNOWN", 0, "object limit")); return; }
            var o = snapshot.objects().get(id);
            var e = entry(dimension, id, o == null ? "" : o.name(), parent, depth, "UNKNOWN", 0, ""); int at = out.size(); out.add(e);
            boolean group = false;
            try {
                budget.spend();
                if (o == null || !o.status().equals("RESOLVED")) throw new Unknown("unresolved object");
                if (path.contains(id) || path.size() >= 32) throw new Unknown("group cycle or depth limit");
                group = GROUPS.contains(o.type());
            } catch (Unknown unknown) { state(e, "UNKNOWN", 0, unknown.getMessage()); return; }
            if (group) {
                path.add(id);
                for (String child : o.members()) node(child, id, depth + 1, path);
                path.remove(id);
            }
            if (blanket != null) state(e, "UNKNOWN", 0, blanket);
            else if (negated) state(e, "NOT_AUDITABLE", 0, "negated cell");
            else {
                classify(e, o, id, group);
                // ROU-4: nothing inside a group that cannot be resolved as a whole may be called unseen.
                if (group && e.get("state").equals("UNKNOWN"))
                    for (int i = at + 1; i < out.size() && (int) out.get(i).get("depth") > depth; i++)
                        if (out.get(i).get("state").equals("NOT_SEEN")) state(out.get(i), "UNKNOWN", 0, "enclosing group not resolved");
            }
        }
        void classify(Map<String, Object> e, PolicyObject o, String id, boolean group) {
            try {
                if (o.type().equals("any")) { state(e, "NOT_AUDITABLE", 0, "any"); return; }
                var selector = resolver.object(id, family, new HashSet<>());
                if (selector.any()) { state(e, "NOT_AUDITABLE", 0, "any"); return; }
                var intervals = new ArrayList<long[]>();
                for (var entry : selector.ranges().entrySet()) {
                    String key = entry.getKey();
                    if (!(service ? key.equals("tcp") || key.equals("udp") : key.equals("ipv4"))) continue;
                    long offset = service && key.equals("udp") ? 65536 : 0;
                    for (Range r : entry.getValue()) { budget.spend(); intervals.add(new long[]{r.start() + offset, r.end() + offset}); }
                }
                if (intervals.isEmpty()) { state(e, "NOT_AUDITABLE", 0, "not matchable"); return; }
                intervals.sort(Comparator.comparingLong((long[] r) -> r[0]));
                long total = 0, start = intervals.get(0)[0], end = intervals.get(0)[1];
                for (int i = 1; i < intervals.size(); i++) {
                    var r = intervals.get(i);
                    if (r[0] <= end + 1) end = Math.max(end, r[1]);
                    else { total += observed.between(start, end); start = r[0]; end = r[1]; }
                }
                total += observed.between(start, end);
                if (ruleProblem != null) state(e, "UNKNOWN", 0, ruleProblem);
                else state(e, total > 0 ? "USED" : "NOT_SEEN", total, "");
            } catch (Unknown unknown) {
                String reason = unknown.getMessage();
                if (reason.equals("analysis budget")) reason = "work budget exceeded";
                else if (reason.equals("unsupported service constraint") || reason.equals("unsupported address representation")) {
                    if (!group) { state(e, "NOT_AUDITABLE", 0, "not matchable"); return; }
                    reason = "a member is not matchable";
                }
                state(e, "UNKNOWN", 0, reason);
            }
        }
        static void state(Map<String, Object> e, String state, long flows, String reason) { e.put("state", state); e.put("flowCount", flows); e.put("reason", reason); }
        static Map<String, Object> entry(String dimension, String id, String name, String parent, int depth, String state, long flows, String reason) {
            var e = new LinkedHashMap<String, Object>();
            e.put("dimension", dimension); e.put("objectId", id); e.put("name", name); e.put("parentId", parent); e.put("depth", depth);
            state(e, state, flows, reason); return e;
        }
    }
    /** Prefix sums over the observed values of one dimension: sum of counts inside [lo, hi] in O(log n). */
    private static final class Observed {
        final long[] keys, cum;
        Observed(NavigableMap<Long, Long> values) {
            keys = new long[values.size()]; cum = new long[values.size() + 1]; int i = 0;
            for (var entry : values.entrySet()) { keys[i] = entry.getKey(); cum[i + 1] = cum[i] + entry.getValue(); i++; }
        }
        long between(long lo, long hi) { return cum[lower(hi + 1)] - cum[lower(lo)]; }
        private int lower(long x) { int a = 0, b = keys.length; while (a < b) { int m = (a + b) >>> 1; if (keys[m] < x) a = m + 1; else b = m; } return a; }
    }
    private static void top(List<Map<String, Object>> out, Map<Long, Long> values, String field, LongFunction<String> format) {
        values.entrySet().stream().sorted(Map.Entry.<Long, Long>comparingByValue().reversed()).limit(50)
            .forEach(e -> out.add(Map.of(field, format.apply(e.getKey()), "count", e.getValue())));
    }
    static List<String> cidrs(NavigableSet<Long> values) {
        var out = new ArrayList<String>(); Long start = null, end = null;
        for (long value : values) {
            if (end != null && value == end + 1) { end = value; continue; }
            if (start != null) cidrs(out, start, end);
            if (out.size() > 50) return out;
            start = value; end = value;
        }
        if (start != null) cidrs(out, start, end); return out;
    }
    private static void cidrs(List<String> out, long start, long end) {
        while (start <= end) {
            long block = start == 0 ? 1L << 32 : Long.lowestOneBit(start);
            while (block > end - start + 1) block >>= 1;
            int prefix = 32 - Long.numberOfTrailingZeros(block);
            out.add(ip(start) + "/" + prefix); start += block;
        }
    }
    static List<String> ports(NavigableSet<Long> values) {
        var out = new ArrayList<String>(); Long start = null, end = null;
        for (long value : values) {
            if (end != null && value == end + 1 && value / 65536 == end / 65536) { end = value; continue; }
            if (start != null) out.add(service(start) + (start.equals(end) ? "" : "-" + end % 65536));
            if (out.size() > 50) return out;
            start = value; end = value;
        }
        if (start != null) out.add(service(start) + (start.equals(end) ? "" : "-" + end % 65536)); return out;
    }
    private static String service(long value) { return (value >= 65536 ? "udp/" : "tcp/") + value % 65536; }
    private static String ip(long value) { return (value >>> 24) + "." + ((value >>> 16) & 255) + "." + ((value >>> 8) & 255) + "." + (value & 255); }
}
