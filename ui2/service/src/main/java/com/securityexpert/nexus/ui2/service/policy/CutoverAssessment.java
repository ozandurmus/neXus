package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;

/**
 * Pre-cutover impact analysis for one segment that the data-centre fabric is about to redirect into a target firewall
 * ({@code docs/design/ACI_SEGMENTATION_SUPPORT_DESIGN_2026_11_13.md} section 4, step 1; PO decision record
 * 2026-11-14). Pure: uploaded traffic-log rows in, one decision per distinct flow tuple out, plus a readiness verdict
 * that is never worded "safe" and always carries its coverage list.
 *
 * <p>Laws kept: the stored-policy lookup ({@link PolicyLookup}) is the only thing that decides a flow; the upper-bound
 * match ({@link PolicyNormalization#upperBound}) only explains an UNKNOWN and never decides one; a target whose vendor
 * has no evaluable policy model is {@code NOT_EVALUABLE} for every flow; uploaded rows are read once and only tuples,
 * counts and times are kept (no raw row, header or mapping).</p>
 */
public final class CutoverAssessment {
    private CutoverAssessment() {}

    public static final String ENGINE_VERSION = "cutover-1";
    public static final int MAX_TUPLES = 50_000;
    public static final int MAX_UPLOADS = 8;
    public static final int MAX_BLOCKS = 4096;
    public static final int MAX_SOURCES_LISTED = 50;
    static final int WORK = 250_000;
    /** Vendors whose stored policy the lookup evaluates (Cisco ASA and FortiGate joined with delivery step 9 of the ASA/FortiGate policy design). */
    public static final Set<String> LOOKUP_VENDORS = Set.of("CP", "PAN", "ASA", "FGT");

    /** Vendors whose decision depends on the arrival and exit interface or zone. */
    static final Set<String> ZONE_VENDORS = Set.of("PAN", "ASA", "FGT");

    public static final String VENDOR_POLICY_NOT_COLLECTED = "VENDOR_POLICY_NOT_COLLECTED";
    public static final String VENDOR_POLICY_NOT_EVALUABLE = "VENDOR_POLICY_NOT_EVALUABLE";
    public static final String TIME_LIMIT = "ASSESSMENT_TIME_LIMIT";
    public static final String PROTOCOL = "protocol unsupported";
    public static final String ZONE_REQUIRED = "zone or target required";
    public static final String PARTIAL = "partial query coverage";
    /** Where a flow's target firewall came from: path computation (default), or an operator's labelled override. */
    public static final String SOURCE_PATH = "PATH";
    public static final String SOURCE_OVERRIDE = "OPERATOR_OVERRIDE";
    public static final Set<String> TARGET_SOURCES = Set.of(SOURCE_PATH, SOURCE_OVERRIDE);

    public static final List<String> DECISION_ORDER = List.of("WOULD_DROP", "NOT_DECIDED", "NOT_EVALUABLE", "WOULD_ALLOW");
    public static final Set<String> DECISIONS = Set.copyOf(DECISION_ORDER);
    public static final Set<String> READINESS = Set.of("BLOCKERS_FOUND", "NOT_DECIDED", "NO_BLOCKERS_IN_EVALUATED_FLOWS", "NO_EVIDENCE");
    public static final Set<String> STATUSES = Set.of("COMPLETE", "INCOMPLETE");
    public static final Set<String> DIRECTIONS = Set.of("SEGMENT_TO_PEER", "PEER_TO_SEGMENT");
    public static final Set<String> FLAGS = Set.of("ALREADY_DROPPED_TODAY", "CONDITIONAL_RULE", "OLD_PATH_ALLOWED", "OLD_PATH_DROPPED",
        "OLD_PATH_NOT_DECIDED");
    public static final Set<String> COVERAGE = Set.of("LOG_PERIOD", "LOGGING_COMPLETENESS_UNKNOWN", "UNLOGGED_FLOWS_INVISIBLE",
        "INSTALL_STATE_NOT_VERIFIED", "NAT_NOT_EVALUATED", "TARGET_NOT_EVALUABLE", "TARGET_SNAPSHOT_INCOMPLETE", "ZONE_PAIR_MISSING",
        "IPV6_FLOWS_SET_ASIDE", "INVALID_ROWS", "OUT_OF_SCOPE_FLOWS", "NON_TCP_UDP_FLOWS", "APPLICATIONS_NOT_EVALUATED",
        "ASSESSMENT_INCOMPLETE", "ACTION_VALUES_UNRECOGNISED",
        "TARGET_FROM_OPERATOR_OVERRIDE", "TARGET_FROM_ROUTED_PATH", "FABRIC_REDIRECT_NOT_MODELLED");
    /** Generic CSV action values read as "the logging firewall dropped this row". Anything else is not a drop. */
    public static final Set<String> DROP_ACTIONS = Set.of("drop", "deny", "reject", "block");
    /** Lookup reasons of a rule left undecided only by a narrowing constraint: the cases the upper bound can explain. */
    static final Set<String> NARROWING_REASONS = Set.of("time or content constraint", "user or category constraint", "time evidence not collected");
    public static final Set<String> CONSTRAINT_KINDS = Set.of("time", "schedule", "vpn", "content", "category", "source-user", "time-uncollected");
    public static final Set<String> REASONS;
    static {
        var reasons = new HashSet<>(PolicyLookup.REASONS);
        reasons.addAll(NARROWING_REASONS);
        reasons.addAll(Set.of(VENDOR_POLICY_NOT_COLLECTED, VENDOR_POLICY_NOT_EVALUABLE, TIME_LIMIT, PROTOCOL, ZONE_REQUIRED, PARTIAL,
            "first matching rule", "unsupported evaluation scope", "unsupported rule constraint"));
        REASONS = Set.copyOf(reasons);
    }
    public static final String HEADLINE_BLOCKERS = "%d of %d evaluated flows would be dropped by the target policy.";
    public static final String HEADLINE_NOT_DECIDED = "No flow is proven dropped, but %d of %d flows are not decided or not evaluable.";
    public static final String HEADLINE_NO_BLOCKERS = "No blocking flow found among %d evaluated flows. Coverage is incomplete: read the coverage list.";
    public static final String HEADLINE_NO_EVIDENCE = "No flow in the uploaded logs falls in this segment's redirect scope.";

    /**
     * One flow's decision. {@code rule} and {@code conditionalRule} are rule views or null; {@code path} lists the
     * firewalls a computed path crossed (empty for an operator override).
     */
    public record Decision(String decision, String reason, Map<String, Object> rule, Map<String, Object> conditionalRule,
            List<String> droppedConstraints, List<Map<String, Object>> path) {
        public Decision {
            if (!DECISIONS.contains(decision)) throw new IllegalArgumentException("decision");
            droppedConstraints = droppedConstraints == null ? List.of() : List.copyOf(droppedConstraints);
            path = path == null ? List.of() : List.copyOf(path);
        }
        public Decision(String decision, String reason, Map<String, Object> rule, Map<String, Object> conditionalRule, List<String> droppedConstraints) {
            this(decision, reason, rule, conditionalRule, droppedConstraints, List.of());
        }
        static Decision of(String decision, String reason) { return new Decision(decision, reason, null, null, List.of()); }
    }

    /**
     * What decides a flow: the routed path through the held topology ({@link #SOURCE_PATH}, implemented beside the path
     * engine), or an operator-chosen target's stored policy or a target neXus cannot evaluate ({@link #SOURCE_OVERRIDE}).
     */
    public interface FlowPolicy {
        Decision decide(String source, String destination, String protocol, int port, String fromZone, String toZone);
        default boolean evaluable() { return true; }
        default String vendor() { return ""; }
        default String source() { return SOURCE_OVERRIDE; }
        /** Coverage codes this kind of decision always carries. */
        default List<String> coverage() { return List.of(); }
    }

    public static FlowPolicy notEvaluable(String reason) {
        if (!Set.of(VENDOR_POLICY_NOT_COLLECTED, VENDOR_POLICY_NOT_EVALUABLE).contains(reason)) throw new IllegalArgumentException("reason");
        return new FlowPolicy() {
            @Override public Decision decide(String s, String d, String p, int port, String f, String t) { return Decision.of("NOT_EVALUABLE", reason); }
            @Override public boolean evaluable() { return false; }
        };
    }

    /** The stored policy of a target ({@code target} is the inventory device id, {@code context} the policy context, both may be empty). */
    public static FlowPolicy stored(PolicySnapshot snapshot, String target, String context) {
        if (!LOOKUP_VENDORS.contains(snapshot.metadata().vendor())) return notEvaluable(VENDOR_POLICY_NOT_EVALUABLE);
        return new Lookup(snapshot, target == null ? "" : target, context == null ? "" : context);
    }

    /** Whether a stored snapshot carries a NAT rulebase the lookup does not evaluate. */
    public static boolean natPresent(PolicySnapshot snapshot) {
        return snapshot.sections().stream().anyMatch(section -> !section.rules().isEmpty()
            && PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), section) && section.source().toUpperCase(Locale.ROOT).contains("NAT"));
    }

    private static final class Lookup implements FlowPolicy {
        private final PolicySnapshot snapshot;
        private final PolicyLookup.Prepared engine;
        private final String target, context;
        private final Map<String, Section> sections = new HashMap<>();
        private final Map<String, Rule> rules = new HashMap<>();
        Lookup(PolicySnapshot snapshot, String target, String context) {
            this.snapshot = snapshot; this.target = target; this.context = context;
            engine = PolicyLookup.prepare(snapshot, WORK);
            for (Section section : snapshot.sections()) {
                if (PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), section)) continue;
                for (Rule rule : section.rules()) if (rules.putIfAbsent(rule.id(), rule) == null) sections.put(rule.id(), section);
            }
        }
        @Override public String vendor() { return snapshot.metadata().vendor(); }
        @Override public Decision decide(String source, String destination, String protocol, int port, String fromZone, String toZone) {
            Map<String, Object> body;
            try {
                body = engine.evaluate(new PolicyLookup.Query(source, destination, protocol + "/" + port, fromZone, toZone, target, context)).body();
            } catch (IllegalArgumentException invalid) { return Decision.of("NOT_DECIDED", "unsupported evaluation scope"); }
            String reason = body.get("lookupReason") instanceof String text ? text : "unsupported rule constraint";
            return switch (String.valueOf(body.get("verdict"))) {
                case "ALLOWED" -> new Decision("WOULD_ALLOW", reason, ruleView(body.get("decidingRule")), null, List.of());
                case "DENIED" -> new Decision("WOULD_DROP", reason, ruleView(body.get("decidingRule")), null, List.of());
                case "PARTIAL" -> Decision.of("NOT_DECIDED", PARTIAL);
                default -> explain(body, reason, source, destination, protocol, port, fromZone, toZone);
            };
        }
        /** The upper bound names the first undecided rule that could match at most under a narrowing constraint; it decides nothing. */
        private Decision explain(Map<String, Object> body, String reason, String source, String destination, String protocol, int port,
                String fromZone, String toZone) {
            if (body.get("undecidableRules") instanceof List<?> undecided) for (Object item : undecided) {
                if (!(item instanceof Map<?, ?> row) || !NARROWING_REASONS.contains(row.get("lookupReason"))) continue;
                List<String> dropped = upperBoundCovers(String.valueOf(row.get("ruleId")), source, destination, protocol, port, fromZone, toZone);
                if (dropped != null) return new Decision("NOT_DECIDED", reason, null, ruleView(row), dropped);
            }
            return Decision.of("NOT_DECIDED", reason);
        }
        private List<String> upperBoundCovers(String ruleId, String source, String destination, String protocol, int port, String fromZone, String toZone) {
            Rule rule = rules.get(ruleId);
            if (rule == null) return null;
            try {
                var budget = new Budget(WORK);
                Match match = PolicyNormalization.upperBound(snapshot, sections.get(ruleId), rule, new Resolver(snapshot.objects(), budget));
                if (match.problem() != null || match.selectors().size() < 4) return null;
                var selectors = match.selectors();
                long s = PolicyRuleQuery.interval(source)[0], d = PolicyRuleQuery.interval(destination)[0];
                if (!contains(selectors.get(0), point("ipv4", s), budget) || !contains(selectors.get(1), point("ipv4", d), budget)
                        || !contains(selectors.get(2), point(protocol, port), budget) || !selectors.get(3).any()) return null;
                if (!zoneAllows(match.placements().get("from"), fromZone) || !zoneAllows(match.placements().get("to"), toZone)) return null;
                List<String> dropped = droppedKinds(rule);
                return dropped.isEmpty() ? null : dropped;
            } catch (RuntimeException unresolved) { return null; }
        }
        private List<String> droppedKinds(Rule rule) {
            List<String> out = new ArrayList<>();
            for (String key : List.of("time", "schedule", "vpn", "content", "category", "source-user")) {
                List<String> values = rule.extras().getOrDefault(key, List.of());
                boolean trivial = values.isEmpty() || values.equals(List.of("any")) || values.stream().allMatch(id -> {
                    var object = snapshot.objects().get(id);
                    return object != null && "RESOLVED".equals(object.status()) && "any".equals(object.type());
                });
                if (!trivial) out.add(key);
            }
            if (snapshot.metadata().vendor().equals("CP") && !rule.extras().containsKey("time")) out.add("time-uncollected");
            return out;
        }
    }

    private static boolean zoneAllows(Set<String> placements, String zone) {
        if (placements == null || placements.isEmpty() || placements.contains("any")) return true;
        return zone != null && !zone.isEmpty() && placements.contains(zone);
    }
    private static Selector point(String family, long value) { return new Selector(false, Map.of(family, List.of(new Range(value, value)))); }
    private static Map<String, Object> ruleView(Object value) {
        if (!(value instanceof Map<?, ?> rule)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("ruleId", "number", "name", "sectionId", "sectionName", "action"))
            if (rule.get(key) != null) out.put(key, rule.get(key));
        return out;
    }

    // ---- segment and scope ------------------------------------------------------------------------------------

    /** Canonical IPv4 CIDR blocks: host bits zero, no leading zeroes, no duplicates; refused, never corrected. */
    public static List<String> blocks(List<?> values, String field) {
        if (values == null || values.isEmpty() || values.size() > MAX_BLOCKS)
            throw new IllegalArgumentException("SEGMENT_BLOCKS: " + field + " needs 1 to 4096 blocks");
        Set<String> seen = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String text) || !text.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}/(?:[0-9]|[12][0-9]|3[0-2])"))
                throw new IllegalArgumentException("SEGMENT_BLOCK_INVALID: " + field + " takes IPv4 CIDR blocks such as 192.0.2.0/24");
            String[] parts = text.split("/");
            long address = 0;
            for (String octet : parts[0].split("\\.")) {
                int number = Integer.parseInt(octet);
                if (number > 255) throw new IllegalArgumentException("SEGMENT_BLOCK_INVALID: " + field + " takes IPv4 CIDR blocks such as 192.0.2.0/24");
                address = address << 8 | number;
            }
            int prefix = Integer.parseInt(parts[1]);
            long host = prefix == 32 ? 0 : (1L << (32 - prefix)) - 1;
            if ((address & host) != 0) throw new IllegalArgumentException("SEGMENT_BLOCK_NOT_CANONICAL: host bits must be zero in " + field);
            if (!seen.add(text)) throw new IllegalArgumentException("SEGMENT_BLOCK_DUPLICATE: a block appears twice in " + field);
        }
        return List.copyOf(seen);
    }

    /** Sorted, merged address intervals of a block list, with a binary-search membership test. */
    static final class Intervals {
        private final long[] starts, ends;
        Intervals(List<String> blocks) {
            var ranges = blocks.stream().map(PolicyRuleQuery::interval).sorted(Comparator.comparingLong(r -> r[0])).toList();
            List<long[]> merged = new ArrayList<>();
            for (long[] range : ranges) {
                if (!merged.isEmpty() && range[0] <= merged.get(merged.size() - 1)[1] + 1)
                    merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], range[1]);
                else merged.add(new long[]{range[0], range[1]});
            }
            starts = merged.stream().mapToLong(r -> r[0]).toArray(); ends = merged.stream().mapToLong(r -> r[1]).toArray();
        }
        boolean contains(long address) {
            int index = Arrays.binarySearch(starts, address);
            if (index >= 0) return true;
            index = -index - 2;
            return index >= 0 && address <= ends[index];
        }
        boolean overlaps(Intervals other) {
            for (int i = 0, j = 0; i < starts.length && j < other.starts.length;) {
                if (ends[i] < other.starts[j]) i++;
                else if (other.ends[j] < starts[i]) j++;
                else return true;
            }
            return false;
        }
    }

    /** The redirect scope: the segment's blocks and the peer blocks whose traffic with the segment is redirected. */
    public record Scope(List<String> segment, List<String> peers) {
        public Scope {
            segment = blocks(segment, "blocks"); peers = blocks(peers, "peerBlocks");
            if (new Intervals(segment).overlaps(new Intervals(peers)))
                throw new IllegalArgumentException("SCOPE_OVERLAP: segment and peer blocks must not overlap");
        }
    }

    /**
     * The target: its policy, the zone (Palo Alto), ASA interface nameif or FortiGate interface or zone the redirected traffic
     * arrives in on the segment side and on the peer side (never inferred from routing), and what is known about its snapshot.
     */
    public record Target(FlowPolicy policy, String segmentZone, String peerZone, boolean snapshotIncomplete, boolean natPresent) {
        public Target {
            Objects.requireNonNull(policy, "policy");
            segmentZone = zone(segmentZone); peerZone = zone(peerZone);
        }
        private static String zone(String value) {
            if (value == null) return "";
            if (value.length() > 200 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("ZONE_INVALID: zone names are at most 200 characters");
            return value;
        }
        /** Palo Alto zones, Cisco ASA interface names and FortiGate interfaces or zones are never inferred from routing; Check Point needs none. */
        boolean zonePairMissing() { return policy.evaluable() && ZONE_VENDORS.contains(policy.vendor()) && (segmentZone.isEmpty() || peerZone.isEmpty()); }
    }

    // ---- run --------------------------------------------------------------------------------------------------

    private static final class Tuple {
        final String direction, source, destination, protocol; final int port;
        long count, dropRows, otherRows; Instant first, last; String sourceZone = "", destinationZone = "";
        Decision decision, old;
        Tuple(String direction, String source, String destination, String protocol, int port) {
            this.direction = direction; this.source = source; this.destination = destination; this.protocol = protocol; this.port = port;
        }
        boolean alreadyDropped() { return dropRows > 0 && otherRows == 0; }
    }

    public record Result(String status, String readiness, Map<String, Object> summary, List<Map<String, Object>> flows,
            List<Map<String, Object>> missingRules) {}

    public static Run start(Scope scope, Target target, FlowPolicy oldPath) { return new Run(scope, target, oldPath); }

    /** One assessment: uploads are read one after another, then {@link #finish} evaluates every distinct tuple once. */
    public static final class Run {
        private final Target target;
        private final FlowPolicy oldPath;
        private final Intervals segment, peers;
        private final Map<String, Tuple> tuples = new HashMap<>();
        private final List<Map<String, Object>> uploads = new ArrayList<>();
        private long ipv6, invalid, outOfScope, unrecognisedActions;
        private Instant first, last;
        private boolean finished;
        private Run(Scope scope, Target target, FlowPolicy oldPath) {
            this.target = Objects.requireNonNull(target, "target"); this.oldPath = oldPath;
            segment = new Intervals(scope.segment()); peers = new Intervals(scope.peers());
        }

        public void read(InputStream input, Map<String, String> mapping) throws IOException { read(input, mapping, OfflineCsv.MAX_BYTES, OfflineCsv.MAX_ROWS); }

        void read(InputStream input, Map<String, String> mapping, long maxBytes, int maxRows) throws IOException {
            if (finished) throw new IllegalStateException("finished");
            if (uploads.size() >= MAX_UPLOADS) throw new IllegalArgumentException("CSV_FILE_LIMIT: at most 8 files per assessment");
            var csv = new OfflineCsv(input, maxBytes); var headers = csv.next(); var columns = OfflineCsv.mapping(headers, mapping);
            boolean actions = columns.containsKey("action");
            Map<String, Long> parse = new TreeMap<>();
            long rows = 0, bad = 0, inScope = 0; Instant uploadFirst = null, uploadLast = null;
            List<String> row;
            while ((row = csv.next()) != null) {
                if (++rows > maxRows) throw new IllegalArgumentException("CSV_ROW_LIMIT: maximum 2000000 rows");
                if (Thread.currentThread().isInterrupted()) throw new IOException("ASSESSMENT_INTERRUPTED");
                long count; Instant time; String source, destination, protocol, sourceZone, destinationZone, action; int port;
                try {
                    if (row.size() != headers.size()) throw new OfflineAnalysis.RowProblem("column count mismatch");
                    count = OfflineAnalysis.count(OfflineAnalysis.value(row, columns, "count"), columns.containsKey("count"));
                    time = OfflineAnalysis.time(OfflineAnalysis.value(row, columns, "time"), columns.containsKey("time"));
                    source = OfflineAnalysis.address(OfflineAnalysis.value(row, columns, "source"));
                    destination = OfflineAnalysis.address(OfflineAnalysis.value(row, columns, "destination"));
                    String text = OfflineAnalysis.value(row, columns, "port");
                    if (!text.matches("[0-9]{1,5}") || Integer.parseInt(text) > 65535) throw new OfflineAnalysis.RowProblem("invalid destination port");
                    port = Integer.parseInt(text);
                    protocol = OfflineAnalysis.value(row, columns, "protocol").toLowerCase(Locale.ROOT);
                    if (protocol.equals("6")) protocol = "tcp"; if (protocol.equals("17")) protocol = "udp";
                    if (!protocol.matches("[a-z0-9-]{1,20}")) throw new OfflineAnalysis.RowProblem("invalid protocol");
                    sourceZone = OfflineAnalysis.zone(OfflineAnalysis.rawValue(row, columns, "sourceZone"));
                    destinationZone = OfflineAnalysis.zone(OfflineAnalysis.rawValue(row, columns, "destinationZone"));
                    action = OfflineAnalysis.value(row, columns, "action").toLowerCase(Locale.ROOT);
                } catch (OfflineAnalysis.RowProblem problem) { bad++; parse.merge(problem.getMessage(), 1L, Long::sum); continue; }
                if (time != null) {
                    if (uploadFirst == null || time.isBefore(uploadFirst)) uploadFirst = time;
                    if (uploadLast == null || time.isAfter(uploadLast)) uploadLast = time;
                }
                if (source.contains(":") || destination.contains(":")) { ipv6 += count; continue; }
                long s = PolicyRuleQuery.interval(source)[0], d = PolicyRuleQuery.interval(destination)[0];
                String direction = segment.contains(s) && peers.contains(d) ? "SEGMENT_TO_PEER"
                    : peers.contains(s) && segment.contains(d) ? "PEER_TO_SEGMENT" : null;
                if (direction == null) { outOfScope += count; continue; }
                inScope += count;
                String key = direction + "|" + source + "|" + destination + "|" + protocol + "|" + port;
                Tuple tuple = tuples.get(key);
                if (tuple == null) {
                    if (tuples.size() >= MAX_TUPLES) throw new IllegalArgumentException("TUPLE_LIMIT: more than 50000 distinct flows in scope; split the segment or the period");
                    tuple = new Tuple(direction, source, destination, protocol, port); tuples.put(key, tuple);
                }
                tuple.count += count;
                if (time != null) {
                    if (tuple.first == null || time.isBefore(tuple.first)) tuple.first = time;
                    if (tuple.last == null || time.isAfter(tuple.last)) tuple.last = time;
                }
                if (tuple.sourceZone.isEmpty()) tuple.sourceZone = sourceZone;
                if (tuple.destinationZone.isEmpty()) tuple.destinationZone = destinationZone;
                if (actions && DROP_ACTIONS.contains(action)) tuple.dropRows += count;
                else {
                    tuple.otherRows += count;
                    if (actions && !Set.of("accept", "allow", "permit").contains(action)) unrecognisedActions += count;
                }
            }
            if (rows == 0) throw new IllegalArgumentException("CSV_EMPTY: at least one data row is required");
            invalid += bad;
            if (uploadFirst != null && (first == null || uploadFirst.isBefore(first))) first = uploadFirst;
            if (uploadLast != null && (last == null || uploadLast.isAfter(last))) last = uploadLast;
            var upload = new LinkedHashMap<String, Object>();
            upload.put("index", uploads.size()); upload.put("rowCount", rows); upload.put("invalidRows", bad); upload.put("inScopeFlows", inScope);
            upload.put("periodStart", text(uploadFirst)); upload.put("periodEnd", text(uploadLast));
            upload.put("parseStats", parse.entrySet().stream().map(e -> Map.<String, Object>of("statReason", e.getKey(), "count", e.getValue())).toList());
            uploads.add(upload);
        }

        public Result finish(Instant deadline) {
            if (finished) throw new IllegalStateException("finished");
            if (uploads.isEmpty()) throw new IllegalArgumentException("CSV_FILE_REQUIRED: at least one traffic-log file is required");
            finished = true;
            List<Tuple> ordered = new ArrayList<>(tuples.values());
            ordered.sort(Comparator.comparing((Tuple t) -> t.direction).thenComparing(t -> t.destination).thenComparing(t -> t.source)
                .thenComparing(t -> t.protocol).thenComparingInt(t -> t.port));
            boolean incomplete = false;
            for (Tuple tuple : ordered) {
                if (!target.policy().evaluable()) tuple.decision = target.policy().decide(tuple.source, tuple.destination, tuple.protocol, tuple.port, "", "");
                else if (!Set.of("tcp", "udp").contains(tuple.protocol)) tuple.decision = Decision.of("NOT_EVALUABLE", PROTOCOL);
                else if (target.zonePairMissing()) tuple.decision = Decision.of("NOT_DECIDED", ZONE_REQUIRED);
                else if (Instant.now().isAfter(deadline)) { tuple.decision = Decision.of("NOT_DECIDED", TIME_LIMIT); incomplete = true; }
                else {
                    boolean forward = tuple.direction.equals("SEGMENT_TO_PEER");
                    tuple.decision = target.policy().decide(tuple.source, tuple.destination, tuple.protocol, tuple.port,
                        forward ? target.segmentZone() : target.peerZone(), forward ? target.peerZone() : target.segmentZone());
                }
                if (oldPath != null && Set.of("tcp", "udp").contains(tuple.protocol) && !Instant.now().isAfter(deadline))
                    tuple.old = oldPath.decide(tuple.source, tuple.destination, tuple.protocol, tuple.port, tuple.sourceZone, tuple.destinationZone);
            }
            return result(ordered, incomplete);
        }

        private Result result(List<Tuple> ordered, boolean incomplete) {
            Map<String, Long> byDecision = new TreeMap<>(); long flows = 0, alreadyDropped = 0, relevantOpen = 0, drops = 0, applications = 0, nonTcpUdp = 0, timeLimited = 0;
            for (Tuple tuple : ordered) {
                byDecision.merge(tuple.decision.decision(), 1L, Long::sum); flows += tuple.count;
                if (tuple.alreadyDropped()) { alreadyDropped++; continue; }
                switch (tuple.decision.decision()) {
                    case "WOULD_DROP" -> drops++;
                    case "NOT_DECIDED", "NOT_EVALUABLE" -> relevantOpen++;
                    default -> { }
                }
                if ("application constraint".equals(tuple.decision.reason())) applications++;
                if (PROTOCOL.equals(tuple.decision.reason())) nonTcpUdp++;
                if (TIME_LIMIT.equals(tuple.decision.reason())) timeLimited++;
            }
            int total = ordered.size();
            String readiness = total == 0 ? "NO_EVIDENCE" : drops > 0 ? "BLOCKERS_FOUND" : relevantOpen > 0 || incomplete ? "NOT_DECIDED" : "NO_BLOCKERS_IN_EVALUATED_FLOWS";
            String headline = switch (readiness) {
                case "BLOCKERS_FOUND" -> String.format(Locale.ROOT, HEADLINE_BLOCKERS, drops, total);
                case "NOT_DECIDED" -> String.format(Locale.ROOT, HEADLINE_NOT_DECIDED, relevantOpen, total);
                case "NO_BLOCKERS_IN_EVALUATED_FLOWS" -> String.format(Locale.ROOT, HEADLINE_NO_BLOCKERS, total);
                default -> HEADLINE_NO_EVIDENCE;
            };
            List<Map<String, Object>> coverage = new ArrayList<>();
            coverage.add(coverage("LOG_PERIOD", null, Map.of("periodStart", text(first), "periodEnd", text(last))));
            coverage.add(coverage("LOGGING_COMPLETENESS_UNKNOWN", null, Map.of()));
            coverage.add(coverage("UNLOGGED_FLOWS_INVISIBLE", null, Map.of()));
            if (target.policy().source().equals(SOURCE_OVERRIDE)) coverage.add(coverage("TARGET_FROM_OPERATOR_OVERRIDE", null, Map.of()));
            for (String code : target.policy().coverage()) coverage.add(coverage(code, null, Map.of()));
            if (target.policy().evaluable()) coverage.add(coverage("INSTALL_STATE_NOT_VERIFIED", null, Map.of()));
            else coverage.add(coverage("TARGET_NOT_EVALUABLE", null, Map.of()));
            if (target.snapshotIncomplete()) coverage.add(coverage("TARGET_SNAPSHOT_INCOMPLETE", null, Map.of()));
            if (target.natPresent()) coverage.add(coverage("NAT_NOT_EVALUATED", null, Map.of()));
            if (target.zonePairMissing()) coverage.add(coverage("ZONE_PAIR_MISSING", null, Map.of()));
            if (ipv6 > 0) coverage.add(coverage("IPV6_FLOWS_SET_ASIDE", ipv6, Map.of()));
            if (invalid > 0) coverage.add(coverage("INVALID_ROWS", invalid, Map.of()));
            if (outOfScope > 0) coverage.add(coverage("OUT_OF_SCOPE_FLOWS", outOfScope, Map.of()));
            if (nonTcpUdp > 0) coverage.add(coverage("NON_TCP_UDP_FLOWS", nonTcpUdp, Map.of()));
            if (applications > 0) coverage.add(coverage("APPLICATIONS_NOT_EVALUATED", applications, Map.of()));
            if (unrecognisedActions > 0) coverage.add(coverage("ACTION_VALUES_UNRECOGNISED", unrecognisedActions, Map.of()));
            if (incomplete) coverage.add(coverage("ASSESSMENT_INCOMPLETE", timeLimited, Map.of()));

            List<Tuple> listed = new ArrayList<>(ordered);
            listed.sort(Comparator.comparingInt((Tuple t) -> DECISION_ORDER.indexOf(t.decision.decision()))
                .thenComparing(Comparator.comparingLong((Tuple t) -> t.count).reversed()));
            List<Map<String, Object>> flowRows = listed.stream().map(this::flow).toList();

            var counts = new LinkedHashMap<String, Object>();
            counts.put("tuples", (long) total); counts.put("flows", flows); counts.put("alreadyDroppedTuples", alreadyDropped);
            counts.put("blockingTuples", drops); counts.put("openTuples", relevantOpen);
            for (String decision : DECISION_ORDER) counts.put(decision, byDecision.getOrDefault(decision, 0L));
            var summary = new LinkedHashMap<String, Object>();
            summary.put("engineVersion", ENGINE_VERSION); summary.put("status", incomplete ? "INCOMPLETE" : "COMPLETE");
            summary.put("readiness", readiness); summary.put("headline", headline); summary.put("targetSource", target.policy().source());
            summary.put("counts", counts);
            summary.put("coverage", coverage); summary.put("uploads", List.copyOf(uploads));
            summary.put("actionVocabulary", List.copyOf(new TreeSet<>(DROP_ACTIONS)));
            List<Map<String, Object>> missing = missingRules(ordered);
            counts.put("missingRuleGroups", (long) missing.size());
            return new Result(incomplete ? "INCOMPLETE" : "COMPLETE", readiness, summary, flowRows, missing);
        }

        private Map<String, Object> flow(Tuple tuple) {
            var out = new LinkedHashMap<String, Object>();
            out.put("direction", tuple.direction); out.put("flowSource", tuple.source); out.put("flowDestination", tuple.destination);
            out.put("protocol", tuple.protocol); out.put("port", tuple.port); out.put("flowCount", tuple.count);
            out.put("firstSeen", text(tuple.first)); out.put("lastSeen", text(tuple.last));
            out.put("decision", tuple.decision.decision()); out.put("decisionReason", tuple.decision.reason());
            if (tuple.decision.rule() != null) out.put("decidingRule", tuple.decision.rule());
            out.put("targetSource", target.policy().source());
            if (!tuple.decision.path().isEmpty()) out.put("pathFirewalls", tuple.decision.path());
            List<String> flags = new ArrayList<>();
            if (tuple.alreadyDropped()) flags.add("ALREADY_DROPPED_TODAY");
            if (tuple.decision.conditionalRule() != null) {
                flags.add("CONDITIONAL_RULE");
                out.put("conditionalRule", tuple.decision.conditionalRule()); out.put("droppedConstraints", tuple.decision.droppedConstraints());
            }
            if (tuple.old != null) {
                flags.add(switch (tuple.old.decision()) { case "WOULD_ALLOW" -> "OLD_PATH_ALLOWED"; case "WOULD_DROP" -> "OLD_PATH_DROPPED"; default -> "OLD_PATH_NOT_DECIDED"; });
                out.put("oldPathDecision", tuple.old.decision()); out.put("oldPathReason", tuple.old.reason());
                if (tuple.old.rule() != null) out.put("oldPathRule", tuple.old.rule());
            }
            out.put("flags", flags);
            return out;
        }

        /** Tuples that would fail on the target and work today (or have no old-path reference), grouped by destination and service. */
        private List<Map<String, Object>> missingRules(List<Tuple> ordered) {
            Map<String, List<Tuple>> groups = new LinkedHashMap<>();
            for (Tuple tuple : ordered) {
                String decision = tuple.decision.decision();
                if (!Set.of("WOULD_DROP", "NOT_DECIDED").contains(decision) || tuple.alreadyDropped() || TIME_LIMIT.equals(tuple.decision.reason())) continue;
                if (tuple.old != null && !tuple.old.decision().equals("WOULD_ALLOW")) continue;
                groups.computeIfAbsent(tuple.direction + "|" + tuple.destination + "|" + tuple.protocol + "|" + tuple.port, k -> new ArrayList<>()).add(tuple);
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (List<Tuple> group : groups.values()) {
                Tuple head = group.get(0);
                TreeMap<Long, String> sources = new TreeMap<>();
                long flows = 0; Instant groupFirst = null, groupLast = null; Map<String, Long> decisions = new TreeMap<>(); Map<String, Object> oldRule = null;
                for (Tuple tuple : group) {
                    sources.put(PolicyRuleQuery.interval(tuple.source)[0], tuple.source); flows += tuple.count;
                    decisions.merge(tuple.decision.decision(), 1L, Long::sum);
                    if (tuple.first != null && (groupFirst == null || tuple.first.isBefore(groupFirst))) groupFirst = tuple.first;
                    if (tuple.last != null && (groupLast == null || tuple.last.isAfter(groupLast))) groupLast = tuple.last;
                    if (oldRule == null && tuple.old != null) oldRule = tuple.old.rule();
                }
                var row = new LinkedHashMap<String, Object>();
                row.put("direction", head.direction); row.put("flowDestination", head.destination); row.put("protocol", head.protocol); row.put("port", head.port);
                row.put("sourceCount", (long) sources.size());
                if (sources.size() <= MAX_SOURCES_LISTED) row.put("flowSources", List.copyOf(sources.values()));
                else row.put("sourceRange", sources.firstEntry().getValue() + "-" + sources.lastEntry().getValue());
                row.put("flowCount", flows); row.put("firstSeen", text(groupFirst)); row.put("lastSeen", text(groupLast));
                row.put("decisionCounts", decisions.entrySet().stream().map(e -> Map.<String, Object>of("decision", e.getKey(), "count", e.getValue())).toList());
                if (oldRule != null) row.put("oldPathRule", oldRule);
                out.add(row);
            }
            out.sort(Comparator.comparingLong((Map<String, Object> r) -> (Long) r.get("flowCount")).reversed());
            return out;
        }
    }

    private static Map<String, Object> coverage(String code, Long count, Map<String, Object> extra) {
        var out = new LinkedHashMap<String, Object>(); out.put("code", code);
        if (count != null) out.put("count", count);
        out.putAll(extra);
        return out;
    }
    private static String text(Instant value) { return value == null ? "" : value.toString(); }
}
