package nexusmeasure;

import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.platform.SecretFile;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Metadata;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Section;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Target;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneBlock;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixContent;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator;

/**
 * Counts-only, in-pod measurement (backlog zone_matrix_delivery): contract section 9 "Performance budget" and the heap
 * part of ZM-39 on the ten largest stored policies, against a synthetic 500-zone version at the block cap
 * ({@code docs/design/ZONE_MATRIX_CONTRACT.md} sections 5.1 and 9, acceptance ZM-12 and ZM-39).
 *
 * <p>Reads only stored data over a READ ONLY connection (policy_snapshot, one row at a time). The zone-matrix version
 * is built in memory exactly like {@code ZoneMatrixScaleMeasurementTest.buildFiveHundredZoneTwoHundredThousandBlockMatrix}
 * (500 synthetic zones, 200,000 disjoint /30 blocks, two service sets, 1,200 explicit cells, a DENY_ALL default) and
 * compiled with the public {@code ZoneMatrixEvaluator.compile}; it never touches stored matrix data, no matrix version
 * is read or written, no database write, no device contact. The engine is reached through its public API only
 * ({@code compile}, {@code evaluate}); no reflection is needed.
 *
 * <p>Every output field matches {@code [a-z0-9_.]+}: ordinals ({@code p01}..), vendor tokens, fixed counter and reason
 * slugs, integers, booleans. No policy id, hash, rule uid, name, address, zone name or exception text can reach the
 * output; a failure prints only {@code fatal<TAB><lowercase exception class>}.
 *
 * <p>{@code --selftest} runs the same measurement code over inline synthetic snapshots and a small synthetic version;
 * it needs no database and no pod.
 */
public final class ZoneMatrixScaleMeasure {
    static final Pattern SAFE = Pattern.compile("[a-z0-9_.]+");
    static final Pattern SAFE_LINE = Pattern.compile("[a-z0-9_.]+(\t[a-z0-9_.]+)*");
    static final int TOP = 10;
    static final long LARGEST_MS = 30_000L, ALL_TEN_MS = 180_000L, LARGEST_MB = 512L;
    static final long SAMPLE_MS = 20L;
    static final Instant FIXED_NOW = Instant.parse("2026-11-25T09:00:00Z");
    static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    static final List<String> OUTCOMES = List.of(ZoneMatrixEvaluator.OUTCOME_VIOLATION, ZoneMatrixEvaluator.OUTCOME_COMPLIANT,
            ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE, ZoneMatrixEvaluator.OUTCOME_UNKNOWN, ZoneMatrixEvaluator.OUTCOME_NOT_APPLICABLE);

    static PrintWriter out;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--selftest")) {
            String failed = selfTest();
            System.out.println(failed == null ? "selftest\tok" : "selftest\tfail\t" + failed);
            System.exit(failed == null ? 0 : 1);
            return;
        }
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        silence();
        Path dir = Path.of(args.length > 0 ? args[0] : ".");
        out = new PrintWriter(Files.newBufferedWriter(dir.resolve("measure.out")), true);
        try {
            Report report = new Report();
            Version version = buildVersion(500, 400, 400);
            runDatabase(report, version);
            emitReport(report);
            out.println("done");
        } catch (Throwable t) {
            String name = t.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            out.println("fatal\t" + (SAFE.matcher(name).matches() ? name : "other"));
        }
        out.close();
    }

    static void silence() {
        try {
            java.util.logging.LogManager.getLogManager().reset();
        } catch (Throwable ignored) {
            // best effort: library logging must not reach the output
        }
        try {
            Object root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            Class<?> level = Class.forName("ch.qos.logback.classic.Level");
            root.getClass().getMethod("setLevel", level).invoke(root, level.getField("OFF").get(null));
        } catch (Throwable ignored) {
            // best effort
        }
    }

    // ---- the synthetic section 9 version (mirrors ZoneMatrixScaleMeasurementTest) ----

    /** The synthetic version and its measured compile. {@code compiled} is null until {@link #compile} ran. */
    static final class Version {
        final ZoneMatrixContent content;
        final int zones, blocks, cells;
        ZoneMatrixEvaluator.CompiledVersion compiled;
        long compileMs, retainedMb;

        Version(ZoneMatrixContent content, int zones, int blocks, int cells) {
            this.content = content;
            this.zones = zones;
            this.blocks = blocks;
            this.cells = cells;
        }
    }

    /**
     * {@code zones} synthetic zones of {@code blocksPerZone} disjoint /30 blocks each (inside 100.64.0.0/10, test space),
     * two service sets and {@code cellRows} rows of three explicit cells (ALLOW_LISTED, ALLOW_ANY, DENY_ALL), default
     * DENY_ALL. (500, 400, 400) is the contract's full scale ceiling: 200,000 blocks and 1,200 cells.
     */
    static Version buildVersion(int zoneCount, int blocksPerZone, int cellRows) {
        List<ZoneMatrixContent.Zone> zones = new ArrayList<>(zoneCount);
        long baseIp = (100L << 24) | (64L << 16);
        int cursor = 0;
        for (int z = 0; z < zoneCount; z++) {
            String zoneId = String.format(Locale.ROOT, "00000000-0000-4000-8000-%012d", z + 1);
            List<ZoneBlock> blocks = new ArrayList<>(blocksPerZone);
            for (int b = 0; b < blocksPerZone; b++) {
                blocks.add(new ZoneBlock(ZoneBlock.Family.IPV4, BigInteger.valueOf(baseIp + (long) cursor * 4L), 30));
                cursor++;
            }
            zones.add(new ZoneMatrixContent.Zone(zoneId, "Z-SYNTH-" + (z + 1), "", "APPLICATION", blocks));
        }
        List<ZoneMatrixContent.ServiceSet> sets = List.of(
                new ZoneMatrixContent.ServiceSet("web", List.of("tcp/80", "tcp/443")),
                new ZoneMatrixContent.ServiceSet("db", List.of("tcp/1521", "tcp/5432")));
        List<ZoneMatrixContent.Cell> cells = new ArrayList<>(cellRows * 3);
        for (int i = 0; i < cellRows; i++) {
            String src = zones.get(i).id();
            cells.add(new ZoneMatrixContent.Cell(src, zones.get((i + 1) % zoneCount).id(), "ALLOW_LISTED",
                    List.of("set:web", "tcp/8443"), "HIGH", "synthetic tier", "", "EMPTY", null, null));
            cells.add(new ZoneMatrixContent.Cell(src, zones.get((i + 2) % zoneCount).id(), "ALLOW_ANY", List.of(),
                    "MEDIUM", "synthetic peer", "", "EMPTY", null, null));
            cells.add(new ZoneMatrixContent.Cell(src, zones.get((i + 3) % zoneCount).id(), "DENY_ALL", List.of(),
                    "CRITICAL", "synthetic isolation", "", "EMPTY", null, null));
        }
        ZoneMatrixContent.Matrix matrix = new ZoneMatrixContent.Matrix("DENY_ALL", "HIGH", null, sets, cells);
        return new Version(new ZoneMatrixContent(zones, matrix), zoneCount, zoneCount * blocksPerZone, cells.size());
    }

    /** Compiles the version once, measuring wall-clock and the heap the compiled index retains (after best-effort GC). */
    static void compile(Version v) {
        gc();
        long before = usedHeap();
        long t0 = System.nanoTime();
        v.compiled = ZoneMatrixEvaluator.compile("00000000-0000-4000-8000-000000500200", 1, v.content);
        v.compileMs = (System.nanoTime() - t0) / 1_000_000L;
        gc();
        v.retainedMb = mb(Math.max(0L, usedHeap() - before));
    }

    // ---- measurement ----

    static void gc() {
        System.gc();
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.gc();
    }

    static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /** Whole MB, rounded up so a budget is never reported better than it was. */
    static long mb(long bytes) {
        return (bytes + 1_048_575L) / 1_048_576L;
    }

    /** Polls used heap every {@code SAMPLE_MS}; the peak is an estimate (garbage not yet collected counts), not a profiler. */
    static final class Sampler extends Thread {
        private volatile boolean stop;
        volatile long peak;

        Sampler(long floor) {
            super("zm-scale-heap-sampler");
            setDaemon(true);
            peak = floor;
        }

        @Override
        public void run() {
            while (!stop) {
                long used = usedHeap();
                if (used > peak) {
                    peak = used;
                }
                try {
                    Thread.sleep(SAMPLE_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        long finish() {
            stop = true;
            long used = usedHeap();
            if (used > peak) {
                peak = used;
            }
            try {
                join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return peak;
        }
    }

    /** One measured evaluation of one ranked policy. */
    static final class Measured {
        String label, vendor;
        long securityRules, ruleCount;
        boolean evaluated, hygieneBudgetReached, complete;
        long durationMs, hygieneMs, heapBeforeMb, heapPeakMb, heapAdditionalMb;
        final Map<String, Long> outcomes = new LinkedHashMap<>();
        final Map<String, Long> reasons = new LinkedHashMap<>();
    }

    /** The closed reason vocabulary the evaluator returns, as output slugs (lower-case, non-alphanumerics to underscores). */
    static List<String> reasonKeys() {
        List<String> keys = new ArrayList<>();
        for (String r : sorted(ZoneMatrixEvaluator.NOT_EVALUABLE_REASONS)) {
            keys.add("not_evaluable." + slug(r));
        }
        for (String r : sorted(ZoneMatrixEvaluator.UNKNOWN_REASONS)) {
            keys.add("unknown." + slug(r));
        }
        keys.add("not_evaluable.other");
        keys.add("unknown.other");
        return keys;
    }

    static List<String> sorted(Set<String> set) {
        List<String> l = new ArrayList<>(set);
        l.sort(Comparator.naturalOrder());
        return l;
    }

    static String slug(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /** Reason key for one rule: a member of the evaluator's closed set, else {@code other}; free text never passes. */
    static String reasonKey(String outcome, String reason) {
        boolean ne = ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE.equals(outcome);
        Set<String> closed = ne ? ZoneMatrixEvaluator.NOT_EVALUABLE_REASONS : ZoneMatrixEvaluator.UNKNOWN_REASONS;
        return (ne ? "not_evaluable." : "unknown.") + (reason != null && closed.contains(reason) ? slug(reason) : "other");
    }

    /** Measures one evaluate: warm nothing, GC best effort, sampler around the single call. */
    static Measured measure(String label, PolicySnapshot s, Version v) {
        Measured m = new Measured();
        m.label = label;
        m.vendor = vendorSlug(s.metadata().vendor());
        m.securityRules = securityRules(s);
        m.ruleCount = s.ruleCount();
        for (String k : reasonKeys()) {
            m.reasons.put(k, 0L);
        }
        for (String o : OUTCOMES) {
            m.outcomes.put(o, 0L);
        }
        gc();
        long before = usedHeap();
        Sampler sampler = new Sampler(before);
        sampler.start();
        long t0 = System.nanoTime();
        ZoneMatrixEvaluator.EvaluationResult res;
        try {
            res = ZoneMatrixEvaluator.evaluate(s, v.compiled, FIXED_NOW);
        } finally {
            m.durationMs = (System.nanoTime() - t0) / 1_000_000L;
            m.heapPeakMb = mb(sampler.finish());
        }
        m.heapBeforeMb = mb(before);
        m.heapAdditionalMb = mb(Math.max(0L, sampler.peak - before));
        m.evaluated = res.evaluated();
        m.complete = "COMPLETE".equals(res.coverage());
        m.hygieneBudgetReached = res.hygieneBudgetReached();
        m.hygieneMs = res.hygieneMillis();
        for (String o : List.of(ZoneMatrixEvaluator.OUTCOME_VIOLATION, ZoneMatrixEvaluator.OUTCOME_COMPLIANT,
                ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE, ZoneMatrixEvaluator.OUTCOME_UNKNOWN)) {
            m.outcomes.put(o, (long) res.outcomeCounts().getOrDefault(o, 0));
        }
        m.outcomes.put(ZoneMatrixEvaluator.OUTCOME_NOT_APPLICABLE, (long) res.notApplicable().total());
        for (ZoneMatrixEvaluator.RuleEvaluation r : res.rules()) {
            if (ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE.equals(r.outcome()) || ZoneMatrixEvaluator.OUTCOME_UNKNOWN.equals(r.outcome())) {
                m.reasons.merge(reasonKey(r.outcome(), r.reason()), 1L, Long::sum);
            }
        }
        return m;
    }

    static String vendorSlug(String vendor) {
        return "CP".equals(vendor) ? "cp" : "PAN".equals(vendor) ? "pan" : "other";
    }

    static boolean evaluable(PolicySnapshot s) {
        String v = s.metadata() == null ? null : s.metadata().vendor();
        return "CP".equals(v) || "PAN".equals(v);
    }

    static boolean security(PolicySnapshot s, Section section) {
        return !PolicySnapshot.outsideSecurityEvaluation(s.metadata(), section);
    }

    /** Enabled plus disabled rules of security sections (NAT and default sections are outside security evaluation). */
    static long securityRules(PolicySnapshot s) {
        long n = 0;
        for (Section section : s.sections()) {
            if (security(s, section)) {
                n += section.rules().size();
            }
        }
        return n;
    }

    // ---- report ----

    /** A ranked candidate: the key to re-read it by (never printed) and its size. */
    record Candidate(String key, long securityRules) {}

    static final class Report {
        final Map<String, Long> scope = new LinkedHashMap<>();
        final List<Candidate> candidates = new ArrayList<>();
        final List<Measured> measured = new ArrayList<>();
        Version version;
        boolean complete = true;

        void scope(String counter, long n) {
            scope.merge(counter, n, Long::sum);
        }
    }

    /** The scope counters, all printed (zero included) so a run is comparable with the next. */
    static final List<String> SCOPE = List.of("snapshots_total", "snapshots_parse_failed", "snapshots_with_failures_skipped",
            "snapshots_vendor_not_evaluated", "snapshots_analysable", "snapshots_ranked", "ranked_reread_failed", "evaluation_failed");

    /** One scan step shared by the database run and the self-test: counts the snapshot and keeps its key if it is analysable. */
    static void rank(Report report, String key, PolicySnapshot s) {
        report.scope("snapshots_total", 1);
        if (!s.failures().isEmpty()) {
            report.scope("snapshots_with_failures_skipped", 1);
            return;
        }
        if (!evaluable(s)) {
            report.scope("snapshots_vendor_not_evaluated", 1);
            return;
        }
        report.scope("snapshots_analysable", 1);
        report.candidates.add(new Candidate(key, securityRules(s)));
    }

    /** Ten largest by security rules, ties in key (policy-id) order; the list is cut to {@code TOP}. */
    static List<Candidate> topTen(List<Candidate> all) {
        List<Candidate> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingLong((Candidate c) -> c.securityRules()).reversed().thenComparing(Candidate::key));
        return sorted.size() > TOP ? new ArrayList<>(sorted.subList(0, TOP)) : sorted;
    }

    /** Evaluates the ranked policies one at a time through {@code reread}; a policy that cannot be read or evaluated only counts. */
    static void measureRanked(Report report, Function<String, PolicySnapshot> reread) {
        List<Candidate> top = topTen(report.candidates);
        report.scope("snapshots_ranked", top.size());
        int ordinal = 0;
        for (Candidate c : top) {
            ordinal++;
            String label = String.format(Locale.ROOT, "p%02d", ordinal);
            PolicySnapshot s;
            try {
                s = reread.apply(c.key());
            } catch (RuntimeException unreadable) {
                s = null;
            }
            if (s == null || !s.failures().isEmpty() || !evaluable(s)) {
                report.scope("ranked_reread_failed", 1);
                report.complete = false;
                continue;
            }
            try {
                Measured m = measure(label, s, report.version);
                if (!m.evaluated) {
                    report.scope("evaluation_failed", 1);
                    report.complete = false;
                    continue;
                }
                report.measured.add(m);
            } catch (Exception | StackOverflowError | OutOfMemoryError failed) {
                report.scope("evaluation_failed", 1);
                report.complete = false;
            }
            s = null;
        }
        if (top.size() < TOP) {
            report.complete = false;
        }
    }

    static String bool(boolean b) {
        return b ? "true" : "false";
    }

    static void emitReport(Report report) {
        Version v = report.version;
        for (String k : SCOPE) {
            emit("scope", "fleet", k, report.scope.getOrDefault(k, 0L));
        }
        emit("version", "synthetic", "zones", v.zones);
        emit("version", "synthetic", "blocks", v.blocks);
        emit("version", "synthetic", "cells", v.cells);
        emit("version", "synthetic", "compile_ms", v.compileMs);
        emit("version", "synthetic", "retained_heap_mb", v.retainedMb);
        long sumMs = 0;
        long sumRules = 0;
        for (Measured m : report.measured) {
            sumMs += m.durationMs;
            sumRules += m.securityRules;
            emitText("package", m.label, "vendor", m.vendor);
            emit("package", m.label, "security_rules", m.securityRules);
            emit("package", m.label, "rules_total", m.ruleCount);
            emit("eval", m.label, "duration_ms", m.durationMs);
            emit("eval", m.label, "hygiene_ms", m.hygieneMs);
            emit("eval", m.label, "heap_before_mb", m.heapBeforeMb);
            emit("eval", m.label, "heap_peak_mb", m.heapPeakMb);
            emit("eval", m.label, "heap_additional_mb", m.heapAdditionalMb);
            emitText("eval", m.label, "coverage_complete", bool(m.complete));
            emitText("eval", m.label, "hygiene_budget_reached", bool(m.hygieneBudgetReached));
            for (String o : OUTCOMES) {
                emit("outcome", m.label, o.toLowerCase(Locale.ROOT), m.outcomes.getOrDefault(o, 0L));
            }
            for (String k : reasonKeys()) {
                emit("reason", m.label, k, m.reasons.getOrDefault(k, 0L));
            }
        }
        emit("sum", "fleet", "evaluated_policies", report.measured.size());
        emit("sum", "fleet", "security_rules", sumRules);
        emit("sum", "fleet", "duration_ms", sumMs);
        boolean first = !report.measured.isEmpty() && report.measured.get(0).label.equals("p01");
        Measured p01 = first ? report.measured.get(0) : null;
        emitText("budget", "fleet", "largest_within_30s", p01 == null ? "not_measured" : bool(p01.durationMs <= LARGEST_MS));
        emitText("budget", "fleet", "largest_within_512mb", p01 == null ? "not_measured" : bool(p01.heapAdditionalMb <= LARGEST_MB));
        emitText("budget", "fleet", "all_ten_within_180s",
                !report.complete || report.measured.size() != TOP ? "not_measured" : bool(sumMs <= ALL_TEN_MS));
    }

    static void emit(String kind, String id, String key, long value) {
        emitText(kind, id, key, Long.toString(value));
    }

    static void emitText(String kind, String id, String key, String value) {
        String line = kind + "\t" + id + "\t" + key + "\t" + value;
        for (String f : line.split("\t", -1)) {
            if (!SAFE.matcher(f).matches()) {
                out.println("unsafe_field_dropped");
                return;
            }
        }
        out.println(line);
    }

    // ---- database (read only) ----

    static Connection connect() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_USER_FILE")), "db_user").strip());
        props.setProperty("password", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_PASSWORD_FILE")), "db_password").strip());
        props.setProperty("ApplicationName", "zm-scale-measure-readonly");
        Connection conn = new org.postgresql.Driver().connect(System.getenv("UI2_DB_URL"), props);
        conn.setAutoCommit(false);
        conn.setReadOnly(true);
        try (Statement st = conn.createStatement()) {
            st.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
            st.execute("SET statement_timeout = '120s'");
        }
        return conn;
    }

    static PolicySnapshot parse(String json) throws Exception {
        return MAPPER.readValue(json, PolicySnapshot.class).withPageSplitsMerged().withHitLevels();
    }

    static void runDatabase(Report report, Version version) throws Exception {
        report.version = version;
        // Pass 1: every stored snapshot one at a time, in policy-id order; only the key and the size are kept.
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "select policy_id, snapshot::text from policy_snapshot order by policy_id")) {
            ps.setFetchSize(1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    PolicySnapshot s;
                    try {
                        s = parse(rs.getString(2));
                    } catch (Exception bad) {
                        report.scope("snapshots_total", 1);
                        report.scope("snapshots_parse_failed", 1);
                        continue;
                    }
                    rank(report, key, s);
                }
            }
            conn.commit();
        }
        if (report.candidates.isEmpty()) {
            report.complete = false;
            compile(version);
            return;
        }
        compile(version);
        // Pass 2: the ten largest, re-read one at a time by key, evaluated and dropped.
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "select snapshot::text from policy_snapshot where policy_id = ?")) {
            measureRanked(report, key -> {
                try {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? parse(rs.getString(1)) : null;
                    }
                } catch (Exception e) {
                    throw new IllegalStateException("reread");
                }
            });
            conn.commit();
        }
    }

    // ---- self-test: synthetic snapshots and a small synthetic version only; no database, no pod, no device ----

    static final int ST_ZONES = 8, ST_BLOCKS = 4;

    static PolicyObject object(String id, String type, List<String> members, String... values) {
        return new PolicyObject(id, id, type, members, List.of(values), "RESOLVED");
    }

    /**
     * A synthetic policy of {@code rules} security rules over the small version's zones (zone z is the /28 at
     * 100.64.0.0 + 16 z). Rules rotate through destinations one, two and three zones away, so the cells ALLOW_LISTED,
     * ALLOW_ANY and DENY_ALL are all hit; every 7th rule is disabled, every 11th names an IPv6 address.
     */
    static PolicySnapshot synthetic(String id, String vendor, int rules, boolean checkpoint) {
        boolean cp = vendor.equals("CP");
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", object("obj-any", "any", List.of(), "any"));
        objects.put("svc-https", object("svc-https", "service", List.of(),
                cp ? new String[] {"protocol: tcp", "port: 443"} : new String[] {"protocol/tcp/port: 443"}));
        objects.put("v6-doc", object("v6-doc", "address", List.of(), "ipv6-address: 2001:db8::10"));
        long base = (100L << 24) | (64L << 16);
        for (int z = 0; z < ST_ZONES; z++) {
            long start = base + (long) z * ST_BLOCKS * 4L;
            String ip = ((start >>> 24) & 0xFF) + "." + ((start >>> 16) & 0xFF) + "." + ((start >>> 8) & 0xFF) + "." + (start & 0xFF);
            objects.put("addr-" + z, object("addr-" + z, cp ? "network" : "address", List.of(),
                    cp ? new String[] {"subnet4: " + ip, "mask-length4: 28"} : new String[] {"ip-netmask: " + ip + "/28"}));
        }
        Cell any = new Cell(List.of("obj-any"), false);
        Map<String, List<String>> extras = cp
                ? Map.of("layer-name", List.of("Network"), "install-on", List.of("obj-any"), "time", List.of("obj-any"))
                : Map.of("from", List.of("any"), "to", List.of("any"));
        List<Rule> list = new ArrayList<>(rules);
        for (int r = 0; r < rules; r++) {
            int src = r % ST_ZONES;
            int dst = (src + 1 + r % 3) % ST_ZONES;
            Cell srcCell = r % 11 == 5 ? new Cell(List.of("v6-doc"), false) : new Cell(List.of("addr-" + src), false);
            String ruleId = id + "-r" + (r + 1);
            list.add(new Rule(ruleId, "uid-" + ruleId, r + 1, "SYNTH-RULE-" + (r + 1), r % 7 == 6 ? Boolean.FALSE : Boolean.TRUE,
                    srcCell, new Cell(List.of("addr-" + dst), false), new Cell(List.of("svc-https"), false), any,
                    cp ? "Accept" : "allow", "Log", "", extras));
        }
        List<Section> sections = cp
                ? List.of(new Section("sec-access", "Network", "CP access layer", null, list))
                : List.of(new Section(PolicySnapshot.ref(id, "PAN device-group", "pre-rulebase"), "Pre rules", "PAN device-group", null, list));
        Metadata meta = new Metadata(id, "synth-src", "FW-ALPHA-01", vendor, "synth-dom", "DOM-BRAVO-01", "SYNTH-" + id,
                "2026-11-25T08:00:00Z", "art-" + id, List.of(new Target("synth-gw", "FW-TANGO-04", "vsys1", "IN_SYNC")));
        List<PolicySnapshot.CollectionFailure> failures = checkpoint
                ? List.of(new PolicySnapshot.CollectionFailure("layer-x", "SYNTHETIC_FAILURE"))
                : List.of();
        return new PolicySnapshot(meta, sections, objects, failures);
    }

    /** Null when every check holds, else the name of the first failing check (a literal from this file). */
    static String selfTest() {
        try {
            return selfTestChecks();
        } catch (Throwable t) {
            if (System.getProperty("zm.debug") != null) {
                t.printStackTrace();
            }
            return "exception_" + t.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        }
    }

    static String selfTestChecks() throws Exception {
        // 1. the closed reason vocabulary: every slug is output-safe, free text never passes.
        for (String k : reasonKeys()) {
            if (!SAFE.matcher(k).matches()) {
                return "reason_key_unsafe";
            }
        }
        if (!reasonKey("NOT_EVALUABLE", "free text 192.0.2.1 FW-TANGO-04").equals("not_evaluable.other")
                || !reasonKey("UNKNOWN", "analysis budget").equals("unknown.analysis_budget")
                || !reasonKey("NOT_EVALUABLE", "IPv6 not evaluated").equals("not_evaluable.ipv6_not_evaluated")) {
            return "reason_key_mapping";
        }
        // 2. inline snapshots: 12 analysable (ranks must come out in size order), one checkpoint and one other vendor,
        // both larger than the rest so a ranking that did not skip them would put them first.
        Map<String, PolicySnapshot> stored = new LinkedHashMap<>();
        int[] sizes = {40, 22, 31, 18, 27, 36, 14, 25, 33, 20, 16, 29};
        for (int i = 0; i < sizes.length; i++) {
            String id = String.format(Locale.ROOT, "snap-%02d", i + 1);
            stored.put(id, synthetic(id, i % 2 == 0 ? "CP" : "PAN", sizes[i], false));
        }
        stored.put("snap-13", synthetic("snap-13", "CP", 90, true));
        stored.put("snap-14", synthetic("snap-14", "FGT", 80, false));
        // The reader goes through the same JSON path as the database run does (snapshot::text).
        Map<String, String> json = new LinkedHashMap<>();
        for (Map.Entry<String, PolicySnapshot> e : stored.entrySet()) {
            json.put(e.getKey(), MAPPER.writeValueAsString(e.getValue()));
        }
        Report report = new Report();
        report.version = buildVersion(ST_ZONES, ST_BLOCKS, ST_ZONES);
        compile(report.version);
        for (Map.Entry<String, String> e : json.entrySet()) {
            rank(report, e.getKey(), parse(e.getValue()));
        }
        if (report.scope.get("snapshots_total") != 14L || report.scope.get("snapshots_with_failures_skipped") != 1L
                || report.scope.get("snapshots_vendor_not_evaluated") != 1L || report.scope.get("snapshots_analysable") != 12L) {
            return "scope_counts";
        }
        List<Candidate> top = topTen(report.candidates);
        long[] expectSize = {40, 36, 33, 31, 29, 27, 25, 22, 20, 18};
        String[] expectKey = {"snap-01", "snap-06", "snap-09", "snap-03", "snap-12", "snap-05", "snap-08", "snap-02", "snap-10", "snap-04"};
        if (top.size() != 10) {
            return "top_ten_size";
        }
        for (int i = 0; i < 10; i++) {
            if (top.get(i).securityRules() != expectSize[i] || !top.get(i).key().equals(expectKey[i])) {
                return "top_ten_order";
            }
        }
        // tie order: equal sizes keep policy-id order
        List<Candidate> ties = topTen(List.of(new Candidate("b", 5), new Candidate("a", 5), new Candidate("c", 9)));
        if (!ties.get(0).key().equals("c") || !ties.get(1).key().equals("a") || !ties.get(2).key().equals("b")) {
            return "top_ten_ties";
        }
        // 3. measure the ten through the reader (parse of the stored JSON), then the whole output.
        measureRanked(report, key -> {
            try {
                return parse(json.get(key));
            } catch (Exception e) {
                throw new IllegalStateException("reread");
            }
        });
        if (report.measured.size() != 10 || !report.complete || report.scope.getOrDefault("evaluation_failed", 0L) != 0L) {
            return "measured_ten";
        }
        for (int i = 0; i < 10; i++) {
            Measured m = report.measured.get(i);
            long sum = 0;
            for (long v : m.outcomes.values()) {
                sum += v;
            }
            if (m.securityRules != expectSize[i] || !m.label.equals(String.format(Locale.ROOT, "p%02d", i + 1)) || sum <= 0
                    || m.outcomes.get("NOT_APPLICABLE") <= 0 || m.durationMs < 0 || m.heapAdditionalMb < 0) {
                return "measured_counts";
            }
        }
        Measured first = report.measured.get(0);
        if (first.outcomes.get("VIOLATION") <= 0 || first.outcomes.get("COMPLIANT") <= 0
                || first.reasons.get("not_evaluable.ipv6_not_evaluated") <= 0) {
            return "measured_outcomes";
        }
        java.io.StringWriter sw = new java.io.StringWriter();
        out = new PrintWriter(sw, true);
        emitReport(report);
        out.flush();
        String text = sw.toString();
        for (String line : text.split("\n")) {
            if (!SAFE_LINE.matcher(line).matches()) {
                return "output_unsafe_line";
            }
            if (line.startsWith("unsafe_field_dropped")) {
                return "output_dropped_field";
            }
        }
        for (String needle : new String[] {"snap-", "FW-", "192.", "100.64", "uid-", "SYNTH"}) {
            if (text.contains(needle)) {
                return "output_leak";
            }
        }
        // 4. budget tokens on this fixture: tiny policies are far inside every limit.
        for (String want : new String[] {"budget\tfleet\tlargest_within_30s\ttrue", "budget\tfleet\tlargest_within_512mb\ttrue",
                "budget\tfleet\tall_ten_within_180s\ttrue", "scope\tfleet\tsnapshots_ranked\t10", "version\tsynthetic\tzones\t8"}) {
            if (!text.contains(want + "\n") && !text.endsWith(want)) {
                return "budget_tokens_true";
            }
        }
        // 5. the tokens turn false / not_measured from the numbers, not by accident.
        Report slow = new Report();
        slow.version = report.version;
        slow.complete = true;
        for (int i = 0; i < 10; i++) {
            Measured m = new Measured();
            m.label = String.format(Locale.ROOT, "p%02d", i + 1);
            m.vendor = "cp";
            m.durationMs = i == 0 ? 30_001L : 17_000L;
            m.heapAdditionalMb = i == 0 ? 513L : 1L;
            slow.measured.add(m);
        }
        String slowText = capture(slow);
        if (!slowText.contains("largest_within_30s\tfalse") || !slowText.contains("largest_within_512mb\tfalse")
                || !slowText.contains("all_ten_within_180s\tfalse")) {
            return "budget_tokens_false";
        }
        slow.measured.get(0).durationMs = 30_000L;
        slow.measured.get(0).heapAdditionalMb = 512L;
        for (int i = 1; i < 10; i++) {
            slow.measured.get(i).durationMs = 16_666L;
        }
        String edge = capture(slow);
        if (!edge.contains("largest_within_30s\ttrue") || !edge.contains("largest_within_512mb\ttrue")
                || !edge.contains("all_ten_within_180s\ttrue")) {
            return "budget_tokens_edge";
        }
        slow.measured.remove(9);
        slow.complete = false;
        String partial = capture(slow);
        if (!partial.contains("all_ten_within_180s\tnot_measured") || !partial.contains("largest_within_30s\ttrue")) {
            return "budget_tokens_partial";
        }
        Report none = new Report();
        none.version = report.version;
        none.complete = false;
        String notMeasured = capture(none);
        if (!notMeasured.contains("largest_within_30s\tnot_measured") || !notMeasured.contains("largest_within_512mb\tnot_measured")
                || !notMeasured.contains("all_ten_within_180s\tnot_measured")) {
            return "budget_tokens_not_measured";
        }
        // 6. the unsafe-field guard drops a line that carries a name.
        sw = new java.io.StringWriter();
        out = new PrintWriter(sw, true);
        emitText("package", "FW-TANGO-04", "vendor", "cp");
        emitText("package", "p01", "vendor", "cp");
        out.flush();
        if (!sw.toString().equals("unsafe_field_dropped\n" + "package\tp01\tvendor\tcp\n")) {
            return "unsafe_field_guard";
        }
        // 7. a ranked policy that cannot be re-read only counts and makes the run incomplete.
        Report gone = new Report();
        gone.version = report.version;
        gone.candidates.add(new Candidate("missing", 3));
        measureRanked(gone, key -> null);
        if (gone.scope.getOrDefault("ranked_reread_failed", 0L) != 1L || gone.complete || !gone.measured.isEmpty()) {
            return "reread_failure";
        }
        return null;
    }

    static String capture(Report report) {
        java.io.StringWriter sw = new java.io.StringWriter();
        out = new PrintWriter(sw, true);
        emitReport(report);
        out.flush();
        return sw.toString();
    }
}
