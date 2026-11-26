package nexusmeasure;

import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.Callable;
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
import com.securityexpert.nexus.ui2.service.policy.PolicyHygiene;

/**
 * Counts-only, in-pod measurement (backlog policy_insights_p2; PO decision Q8, {@code docs/design/
 * PO_DECISION_RECORD_2026_11_24_POLICY_INSIGHTS.md}): how often do the Policy Insights engines of group G1 (interval-box
 * arithmetic with a budget) end a rule evaluation NOT_PROVEN because the budget ran out, and what do they cost, on the ten
 * largest stored Check Point policies. Design section 9 (phase P2 gate) and section 3.3.
 *
 * <p>Reads only stored data over a READ ONLY connection ({@code policy_snapshot}, one row at a time). Pass 1 ranks every
 * analysable Check Point snapshot by its enabled security rules; pass 2 re-reads each of the ten largest by its key and
 * runs, with the production budgets, {@code PolicyHygiene.analyze} (the shadow engine the others read), then
 * {@code PolicyUnionCover.analyze}, {@code PolicyPartialShadow.analyze}, {@code PolicyMerge.analyze},
 * {@code PolicyReorder.analyze} and {@code PolicyProposal.allOfferedMoves}, each once, timed with thread CPU time. No
 * device contact, no write, no new command. The engine classes are package-private, so they are reached by reflection
 * (the harness is compiled against the service classes and loads beside them).
 *
 * <p>Every output field matches {@code [a-z0-9_.]+}: ordinals ({@code p01}..), engine and counter slugs, integers,
 * booleans. No policy id, hash, rule uid, name, address or exception text can reach the output; a failure prints only
 * {@code fatal<TAB><lowercase exception class>}.
 *
 * <p>{@code --selftest} runs the same measurement code over inline synthetic snapshots (a tiny work limit is passed as an
 * argument of {@link #measure}, never through the environment) and needs no database and no pod.
 */
public final class PolicyInsightsBudgetMeasure {
    static final Pattern SAFE = Pattern.compile("[a-z0-9_.]+");
    static final Pattern SAFE_LINE = Pattern.compile("[a-z0-9_.]+(\t[a-z0-9_.]+)*");
    static final int TOP = 10, DAYS = 90;
    static final long SAMPLE_MS = 20L;
    static final String P = "com.securityexpert.nexus.ui2.service.policy.";
    static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** The four interval-box engines in pipeline order; the proposal is the fifth step and has its own vocabulary. */
    static final List<String> ENGINES = List.of("union_cover", "partial_shadow", "merge", "reorder");
    static final String PROPOSAL = "proposal";
    /** What each engine decides, as counter slugs (the closed vocabulary printed for every snapshot, zero included). */
    static final Map<String, List<String>> OUTCOMES = Map.of(
            "union_cover", List.of("covered"),
            "partial_shadow", List.of("partial", "different_action"),
            "merge", List.of("candidates", "already_shadowed", "differs_in_several_cells", "different_effect",
                    "different_install_on", "anchor_blocked"),
            "reorder", List.of("safe", "safe_inspection_changes", "unsafe_different_effect", "anchor_blocked", "crosses_section"));

    /** The work limits an engine run uses: the production ones in a real run, a tiny one only through the self-test. */
    record Limits(int work, int rule) {}

    static final Limits PRODUCTION = new Limits(PolicyHygiene.TOTAL_WORK, PolicyHygiene.RULE_WORK);

    static PrintWriter out;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--selftest")) {
            String failed = selfTest();
            System.out.println(failed == null ? "selftest\tok" : "selftest\tfail\t" + failed);
            System.exit(failed == null ? 0 : 1);
            return;
        }
        if (args.length > 0 && args[0].equals("--selftest-report")) {
            // The synthetic report, as the run would print it, so the shell self-test can look for planted names in stdout.
            System.out.print(syntheticReport());
            return;
        }
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        silence();
        Path dir = Path.of(args.length > 0 ? args[0] : ".");
        out = new PrintWriter(Files.newBufferedWriter(dir.resolve("measure.out")), true);
        try {
            Report report = new Report();
            runDatabase(report);
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

    // ---- engine access (package-private classes, by reflection) ----

    static final class Engine {
        static final Method UNION, PARTIAL, MERGE, REORDER, PROPOSE;
        /** The engines' closed reason sets, read from the engines themselves. */
        static final List<String> NOT_EVALUABLE, REFUSALS;
        static final String COVERED, PARTIAL_STATUS, NOT_PROVEN, BUDGET;

        static {
            try {
                Class<?> union = Class.forName(P + "PolicyUnionCover");
                Class<?> partial = Class.forName(P + "PolicyPartialShadow");
                Class<?> merge = Class.forName(P + "PolicyMerge");
                Class<?> reorder = Class.forName(P + "PolicyReorder");
                Class<?> proposal = Class.forName(P + "PolicyProposal");
                UNION = pick(union, "analyze", 4);
                PARTIAL = pick(partial, "analyze", 5);
                MERGE = pick(merge, "analyze", 3);
                REORDER = pick(reorder, "analyze", 4);
                PROPOSE = pick(proposal, "allOfferedMoves", 3);
                NOT_EVALUABLE = strings(union, "NOT_EVALUABLE_REASONS");
                REFUSALS = strings(proposal, "REFUSALS");
                COVERED = (String) constant(union, "COVERED");
                PARTIAL_STATUS = (String) constant(partial, "PARTIAL");
                NOT_PROVEN = (String) constant(union, "NOT_PROVEN");
                BUDGET = (String) constant(union, "BUDGET");
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        /** The one overload with {@code count} parameters whose last two (the work limit and the rule limit) are ints. */
        static Method pick(Class<?> c, String name, int count) throws NoSuchMethodException {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != count) {
                    continue;
                }
                boolean ints = true;
                for (int i = count - 2; i < count; i++) {
                    ints &= m.getParameterTypes()[i] == int.class;
                }
                if (ints) {
                    m.setAccessible(true);
                    return m;
                }
            }
            throw new NoSuchMethodException(c.getSimpleName());
        }

        static Object constant(Class<?> c, String name) throws ReflectiveOperationException {
            var f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        }

        static List<String> strings(Class<?> c, String name) throws ReflectiveOperationException {
            List<String> list = new ArrayList<>();
            for (Object o : (Collection<?>) constant(c, name)) {
                list.add((String) o);
            }
            return List.copyOf(list);
        }

        static Object call(Method m, Object... args) throws Exception {
            try {
                return m.invoke(null, args);
            } catch (InvocationTargetException e) {
                Throwable t = e.getCause();
                if (t instanceof Exception ex) {
                    throw ex;
                }
                if (t instanceof Error err) {
                    throw err;
                }
                throw e;
            }
        }

        /** A record component of a package-private record. */
        static Object comp(Object record, String name) throws ReflectiveOperationException {
            Method m = record.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            return m.invoke(record);
        }

        static long num(Object record, String name) throws ReflectiveOperationException {
            return ((Number) comp(record, name)).longValue();
        }

        static Map<?, ?> map(Object record, String name) throws ReflectiveOperationException {
            return (Map<?, ?>) comp(record, name);
        }
    }

    // ---- cost: thread CPU time and sampled heap ----

    static long cpuNanos() {
        try {
            var bean = ManagementFactory.getThreadMXBean();
            return bean.isCurrentThreadCpuTimeSupported() ? bean.getCurrentThreadCpuTime() : -1L;
        } catch (Throwable unsupported) {
            return -1L;
        }
    }

    /** Off only in the self-test sweep, which runs the engines hundreds of times. */
    static boolean gcBeforeTiming = true;

    static void gc() {
        if (!gcBeforeTiming) {
            return;
        }
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

    /** Whole MB, rounded up so a cost is never reported better than it was. */
    static long mb(long bytes) {
        return (bytes + 1_048_575L) / 1_048_576L;
    }

    static long ms(long nanos) {
        return (nanos + 999_999L) / 1_000_000L;
    }

    /** Polls used heap every {@code SAMPLE_MS}; the peak is an estimate (garbage not yet collected counts), not a profiler. */
    static final class Sampler extends Thread {
        private volatile boolean stop;
        volatile long peak;

        Sampler(long floor) {
            super("policy-insights-budget-heap-sampler");
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
            interrupt();
            try {
                join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return peak;
        }
    }

    /** Runs one engine call once and records its thread CPU ms ({@code <key>.cpu_ms}) and sampled additional heap MB ({@code <key>.heap_mb}). */
    static <T> T timed(Map<String, Long> c, String key, Callable<T> body) throws Exception {
        gc();
        long before = usedHeap();
        Sampler sampler = new Sampler(before);
        sampler.start();
        long c0 = cpuNanos();
        try {
            return body.call();
        } finally {
            long c1 = cpuNanos();
            long peak = sampler.finish();
            c.put(key + ".cpu_ms", c0 < 0 || c1 < 0 ? 0L : ms(Math.max(0L, c1 - c0)));
            c.put(key + ".heap_mb", mb(Math.max(0L, peak - before)));
        }
    }

    // ---- per-snapshot engine counters ----

    static String slug(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    static boolean security(PolicySnapshot s, Section section) {
        return !PolicySnapshot.outsideSecurityEvaluation(s.metadata(), section);
    }

    /** Enabled rules of security sections (NAT and default sections are outside security evaluation). */
    static long enabledSecurityRules(PolicySnapshot s) {
        long n = 0;
        for (Section section : s.sections()) {
            if (security(s, section)) {
                for (Rule r : section.rules()) {
                    n += Boolean.FALSE.equals(r.enabled()) ? 0 : 1;
                }
            }
        }
        return n;
    }

    static long disabledSecurityRules(PolicySnapshot s) {
        long n = 0;
        for (Section section : s.sections()) {
            if (security(s, section)) {
                for (Rule r : section.rules()) {
                    n += Boolean.FALSE.equals(r.enabled()) ? 1 : 0;
                }
            }
        }
        return n;
    }

    /**
     * The subject rules of the union and partial passes: enabled, assessed by the shadow engine and not REDUNDANT or
     * CONFLICT, and, for the partial pass, not COVERED by the union pass. The engines count a subject whose own budget
     * ran out before it was read as NOT_PROVEN but not as evaluated; this count recovers those.
     */
    static long subjects(PolicySnapshot s, PolicyHygiene.Result hygiene, Object union) throws Exception {
        Map<?, ?> covers = union == null ? Map.of() : Engine.map(union, "rules");
        long n = 0;
        for (Section section : s.sections()) {
            if (!security(s, section)) {
                continue;
            }
            for (Rule r : section.rules()) {
                if (Boolean.FALSE.equals(r.enabled())) {
                    continue;
                }
                var a = hygiene.rules().get(r.id());
                if (a == null || "REDUNDANT".equals(a.shadowStatus()) || "CONFLICT".equals(a.shadowStatus())) {
                    continue;
                }
                Object cover = covers.get(r.id());
                if (cover != null && Engine.COVERED.equals(Engine.comp(cover, "status"))) {
                    continue;
                }
                n++;
            }
        }
        return n;
    }

    static void add(Map<String, Long> c, String key, long n) {
        c.merge(key, n, Long::sum);
    }

    /**
     * The shared counters of one engine: {@code evaluated} (rule evaluations attempted), {@code not_proven} (ended NOT_PROVEN
     * on the budget), {@code decided} (the rest) and {@code ne.<reason>}. {@code unread}: subjects that ran out of budget
     * before they were read; the engine counts them NOT_PROVEN but not evaluated.
     */
    static void base(Map<String, Long> c, String engine, long evaluated, long unread, long notProven, Map<?, ?> notEvaluable) {
        long attempts = evaluated + unread;
        c.put(engine + ".evaluated", attempts);
        c.put(engine + ".not_proven", notProven);
        c.put(engine + ".decided", Math.max(0L, attempts - notProven));
        for (var e : notEvaluable.entrySet()) {
            String reason = (String) e.getKey();
            add(c, engine + ".ne." + (Engine.NOT_EVALUABLE.contains(reason) ? slug(reason) : "other"), ((Number) e.getValue()).longValue());
        }
    }

    /**
     * Runs the shadow engine and the five engines once each over {@code s} at {@code limits} and returns the counters:
     * {@code <engine>.evaluated|decided|not_proven|ne.<reason>|out.<outcome>|cpu_ms|heap_mb}, {@code hygiene.cpu_ms|heap_mb},
     * {@code proposal.*}, {@code snap.rules_security}.
     */
    static Map<String, Long> measure(PolicySnapshot s, Limits limits) throws Exception {
        Map<String, Long> c = new TreeMap<>();
        c.put("snap.rules_security", enabledSecurityRules(s));
        PolicyHygiene.Result hygiene = timed(c, "hygiene",
                () -> PolicyHygiene.analyze(s, DAYS, Instant.EPOCH, PolicyHygiene.TOTAL_WORK));
        Object union = timed(c, "union_cover", () -> Engine.call(Engine.UNION, s, hygiene, limits.work(), limits.rule()));
        long unionUnread = Math.max(0L, subjects(s, hygiene, null) - Engine.num(union, "evaluated") - sum(Engine.map(union, "notEvaluable")));
        base(c, "union_cover", Engine.num(union, "evaluated"), unionUnread, Engine.num(union, "notProven"), Engine.map(union, "notEvaluable"));
        c.put("union_cover.out.covered", Engine.num(union, "covered"));

        Object partial = timed(c, "partial_shadow", () -> Engine.call(Engine.PARTIAL, s, hygiene, union, limits.work(), limits.rule()));
        long partialUnread = Math.max(0L, subjects(s, hygiene, union) - Engine.num(partial, "evaluated") - sum(Engine.map(partial, "notEvaluable")));
        base(c, "partial_shadow", Engine.num(partial, "evaluated"), partialUnread, Engine.num(partial, "notProven"), Engine.map(partial, "notEvaluable"));
        c.put("partial_shadow.out.partial", Engine.num(partial, "partial"));
        c.put("partial_shadow.out.different_action", Engine.num(partial, "differentAction"));

        Object merge = timed(c, "merge", () -> Engine.call(Engine.MERGE, s, limits.work(), limits.rule()));
        base(c, "merge", Engine.num(merge, "evaluated"), 0, Engine.num(merge, "notProven"), Engine.map(merge, "notEvaluable"));
        c.put("merge.out.candidates", Engine.num(merge, "candidates"));
        c.put("merge.out.already_shadowed", Engine.num(merge, "alreadyShadowed"));
        c.put("merge.out.differs_in_several_cells", Engine.num(merge, "differsInSeveralCells"));
        c.put("merge.out.different_effect", Engine.num(merge, "differentEffect"));
        c.put("merge.out.different_install_on", Engine.num(merge, "differentInstallOn"));
        c.put("merge.out.anchor_blocked", Engine.num(merge, "anchorBlocked"));

        Object reorder = timed(c, "reorder", () -> Engine.call(Engine.REORDER, s, partial, limits.work(), limits.rule()));
        base(c, "reorder", Engine.num(reorder, "evaluated"), 0, Engine.num(reorder, "notProven"), Engine.map(reorder, "notEvaluable"));
        c.put("reorder.out.safe", Engine.num(reorder, "safe"));
        c.put("reorder.out.safe_inspection_changes", Engine.num(reorder, "safeInspectionChanges"));
        c.put("reorder.out.unsafe_different_effect", Engine.num(reorder, "unsafeDifferentEffect"));
        c.put("reorder.out.anchor_blocked", Engine.num(reorder, "anchorBlocked"));
        c.put("reorder.out.crosses_section", Engine.num(reorder, "crossesSection"));

        Object proposal = timed(c, PROPOSAL, () -> Engine.call(Engine.PROPOSE, s, limits.work(), limits.rule()));
        long steps = ((Collection<?>) Engine.comp(proposal, "steps")).size();
        Map<?, ?> refused = Engine.map(proposal, "refused");
        long notProven = 0;
        for (var e : refused.entrySet()) {
            String word = (String) e.getKey();
            long n = ((Number) e.getValue()).longValue();
            add(c, PROPOSAL + ".ref." + (Engine.REFUSALS.contains(word) ? slug(word) : "other"), n);
            if (Engine.NOT_PROVEN.equals(word)) {
                notProven += n;
            }
        }
        c.put(PROPOSAL + ".evaluated", steps);
        c.put(PROPOSAL + ".not_proven", notProven);
        c.put(PROPOSAL + ".decided", Math.max(0L, steps - notProven));
        c.put(PROPOSAL + ".accepted", Engine.num(proposal, "accepted"));
        c.put(PROPOSAL + ".steps", steps);
        c.put(PROPOSAL + ".budget_stopped", Boolean.TRUE.equals(Engine.comp(proposal, "budgetStopped")) ? 1L : 0L);
        c.put(PROPOSAL + ".candidates_total", candidates(partial));
        return c;
    }

    /** The I4 candidates of the stored order: the rules the proposal tries (PARTIAL, every overlapping rule of the same action). */
    static long candidates(Object partial) throws Exception {
        long n = 0;
        for (Object p : Engine.map(partial, "rules").values()) {
            if (Engine.PARTIAL_STATUS.equals(Engine.comp(p, "status")) && Boolean.TRUE.equals(Engine.comp(p, "sameAction"))
                    && !((Collection<?>) Engine.comp(p, "overlapping")).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    static long sum(Map<?, ?> m) {
        long n = 0;
        for (Object v : m.values()) {
            n += ((Number) v).longValue();
        }
        return n;
    }

    // ---- report ----

    /** A ranked candidate: the key to re-read it by (never printed) and its size. */
    record Candidate(String key, long enabledRules) {}

    record Measured(String label, Map<String, Long> counters) {}

    static final class Report {
        final Map<String, Long> scope = new LinkedHashMap<>();
        final List<Candidate> candidates = new ArrayList<>();
        final List<Measured> measured = new ArrayList<>();
        long fleetEnabled, fleetDisabled;
        Limits limits = PRODUCTION;
        boolean cpuUnsupported;

        void scope(String counter, long n) {
            scope.merge(counter, n, Long::sum);
        }
    }

    /** The scope counters, all printed (zero included) so a run is comparable with the next. */
    static final List<String> SCOPE = List.of("snapshots_total", "snapshots_parse_failed", "snapshots_with_failures_skipped",
            "snapshots_vendor_not_evaluated", "snapshots_analysable", "snapshots_ranked", "ranked_reread_failed", "analysis_failed",
            "cpu_time_unsupported");

    static boolean checkPoint(PolicySnapshot s) {
        return s.metadata() != null && "CP".equals(s.metadata().vendor());
    }

    /** One scan step shared by the database run and the self-test: counts the snapshot and keeps its key if it is analysable. */
    static void rank(Report report, String key, PolicySnapshot s) {
        report.scope("snapshots_total", 1);
        if (!s.failures().isEmpty()) {
            report.scope("snapshots_with_failures_skipped", 1);
            return;
        }
        if (!checkPoint(s)) {
            report.scope("snapshots_vendor_not_evaluated", 1);
            return;
        }
        report.scope("snapshots_analysable", 1);
        long enabled = enabledSecurityRules(s);
        report.fleetEnabled += enabled;
        report.fleetDisabled += disabledSecurityRules(s);
        report.candidates.add(new Candidate(key, enabled));
    }

    /** Ten largest by enabled security rules, ties in key (policy-id) order. */
    static List<Candidate> topTen(List<Candidate> all) {
        List<Candidate> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingLong((Candidate c) -> c.enabledRules()).reversed().thenComparing(Candidate::key));
        return sorted.size() > TOP ? new ArrayList<>(sorted.subList(0, TOP)) : sorted;
    }

    /** Measures the ranked snapshots one at a time through {@code reread}; one that cannot be read or measured only counts. */
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
            if (s == null || !s.failures().isEmpty() || !checkPoint(s)) {
                report.scope("ranked_reread_failed", 1);
                continue;
            }
            try {
                report.measured.add(new Measured(label, measure(s, report.limits)));
            } catch (Exception | StackOverflowError | OutOfMemoryError failed) {
                report.scope("analysis_failed", 1);
            }
            s = null;
        }
        report.cpuUnsupported = cpuNanos() < 0;
        report.scope("cpu_time_unsupported", report.cpuUnsupported ? 1 : 0);
    }

    static String bool(boolean b) {
        return b ? "true" : "false";
    }

    static void emitReport(Report report) {
        for (String k : SCOPE) {
            emit("scope", "fleet", k, report.scope.getOrDefault(k, 0L));
        }
        emit("package", "fleet", "snapshots", report.scope.getOrDefault("snapshots_analysable", 0L));
        emit("package", "fleet", "rules_security", report.fleetEnabled);
        emit("package", "fleet", "rules_security_disabled", report.fleetDisabled);
        Map<String, Long> fleet = new TreeMap<>();
        for (Measured m : report.measured) {
            emit("package", m.label(), "rules_security", m.counters().getOrDefault("snap.rules_security", 0L));
            emitCounters(m.label(), m.counters());
            m.counters().forEach((k, v) -> fleet.merge(k, v, k.endsWith(".heap_mb") ? Math::max : Long::sum));
        }
        emit("package", "fleet", "rules_security_ranked", fleet.getOrDefault("snap.rules_security", 0L));
        emit("package", "fleet", "snapshots_measured", report.measured.size());
        emitCounters("fleet", fleet);
    }

    static void emitCounters(String label, Map<String, Long> c) {
        for (String e : ENGINES) {
            String id = label + "." + e;
            emitBase(id, e, c);
            for (String reason : Engine.NOT_EVALUABLE) {
                emit("reason", id, slug(reason), c.getOrDefault(e + ".ne." + slug(reason), 0L));
            }
            emit("reason", id, "other", c.getOrDefault(e + ".ne.other", 0L));
            for (String o : OUTCOMES.get(e)) {
                emit("outcome", id, o, c.getOrDefault(e + ".out." + o, 0L));
            }
            emitCost(id, e, c);
            emitBudget(id, e, c, c.getOrDefault(e + ".not_proven", 0L) > 0);
        }
        String id = label + "." + PROPOSAL;
        emitBase(id, PROPOSAL, c);
        for (String word : Engine.REFUSALS) {
            emit("refused", id, slug(word), c.getOrDefault(PROPOSAL + ".ref." + slug(word), 0L));
        }
        emit("refused", id, "other", c.getOrDefault(PROPOSAL + ".ref.other", 0L));
        for (String k : List.of("accepted", "steps", "candidates_total", "budget_stopped")) {
            emit("outcome", id, k, c.getOrDefault(PROPOSAL + "." + k, 0L));
        }
        emitCost(id, PROPOSAL, c);
        emitBudget(id, PROPOSAL, c, c.getOrDefault(PROPOSAL + ".not_proven", 0L) > 0 || c.getOrDefault(PROPOSAL + ".budget_stopped", 0L) > 0);
        emitCost(label + ".hygiene", "hygiene", c);
    }

    static void emitBase(String id, String engine, Map<String, Long> c) {
        for (String k : List.of("evaluated", "decided", "not_proven")) {
            emit("engine", id, k, c.getOrDefault(engine + "." + k, 0L));
        }
    }

    static void emitCost(String id, String engine, Map<String, Long> c) {
        emit("cost", id, "cpu_ms", c.getOrDefault(engine + ".cpu_ms", 0L));
        emit("cost", id, "heap_additional_mb", c.getOrDefault(engine + ".heap_mb", 0L));
    }

    /** Q8 directly: parts per million of rule evaluations that ended NOT_PROVEN on the budget (integer), and whether any did. */
    static void emitBudget(String id, String engine, Map<String, Long> c, boolean anyHit) {
        emit("budget", id, "hit_rate_ppm", hitRatePpm(c.getOrDefault(engine + ".not_proven", 0L), c.getOrDefault(engine + ".evaluated", 0L)));
        emitText("budget", id, "any_hit", bool(anyHit));
    }

    static long hitRatePpm(long notProven, long evaluated) {
        return evaluated <= 0 ? 0L : Math.multiplyExact(Math.min(notProven, evaluated), 1_000_000L) / evaluated;
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
        props.setProperty("ApplicationName", "policy-insights-budget-measure-readonly");
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

    static void runDatabase(Report report) throws Exception {
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
        // Pass 2: the ten largest, re-read one at a time by key, measured and dropped.
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

    // ---- self-test: synthetic snapshots only; no database, no pod, no device ----

    static final String NAME = "FW-TANGO-04", ADDRESS = "192.0.2.77", OTHER_ADDRESS = "198.51.100.9", KEY_PREFIX = "POL-TANGO-";

    static Cell cell(String... ids) {
        return new Cell(List.of(ids), false);
    }

    static PolicyObject object(String id, String name, String type, String... values) {
        return new PolicyObject(id, name, type, List.of(), List.of(values), "RESOLVED");
    }

    /** The documentation /24 as two halves and the whole, an outside network, and a gateway: the engines' own fixture shapes. */
    static Map<String, PolicyObject> objects() {
        Map<String, PolicyObject> o = new HashMap<>();
        o.put("any", object("any", "any", "any"));
        o.put("lo", object("lo", "OBJ-" + NAME + "-LO", "address", "ip-netmask: 192.0.2.0/25"));
        o.put("hi", object("hi", "OBJ-" + NAME + "-HI", "address", "ip-netmask: 192.0.2.128/25"));
        o.put("net", object("net", "OBJ-" + NAME + "-NET", "address", "ip-netmask: 192.0.2.0/24"));
        o.put("host", object("host", "OBJ-" + NAME + "-HOST", "address", "ipv4-address: " + ADDRESS));
        o.put("other", object("other", "OBJ-" + NAME + "-OTHER", "address", "ip-netmask: 198.51.100.0/24"));
        return o;
    }

    static Rule rule(String id, int number, String action, String source) {
        Map<String, List<String>> extras = Map.of("from", List.of("any"), "to", List.of("any"), "time", List.of("any"),
                "install-on", List.of("any"), "layer-name", List.of("LAYER-ALPHA"));
        return new Rule(id, "uuid-" + id, number, "RULE-" + NAME + "-" + id, true, cell(source), cell("any"), cell("any"),
                cell("any"), action, "Log", "comment " + OTHER_ADDRESS, extras);
    }

    static PolicySnapshot snapshot(String id, String vendor, List<Rule> access, List<Rule> nat) {
        Metadata meta = new Metadata(id, "s1", "MGR-BRAVO-01", vendor, "c1", "DOM-TANGO-01", "POL-ALPHA-01",
                "2026-11-26T08:00:00Z", "a1", List.of(new PolicySnapshot.Target("dev1", NAME, "vsys1", "IN_SYNC")));
        List<Section> sections = new ArrayList<>(List.of(new Section("s1", "OBJ-SECTION-01", "CP access layer", null, access)));
        if (!nat.isEmpty()) {
            sections.add(new Section("s2", "OBJ-SECTION-02", "CP NAT rulebase", null, nat));
        }
        return new PolicySnapshot(meta, sections, objects());
    }

    /** I3: the lower and the upper half together cover the whole /24 that a later rule names. */
    static PolicySnapshot coverSnapshot(String id) {
        return snapshot(id, "CP", List.of(rule("r1", 1, "Accept", "lo"), rule("r2", 2, "Accept", "hi"), rule("r3", 3, "Accept", "net")), List.of());
    }

    /** I4 and I7: the whole /24 overlaps the lower half before it with the same action, so its promote is a move. */
    static PolicySnapshot overlapSnapshot(String id) {
        return snapshot(id, "CP", List.of(rule("r1", 1, "Accept", "lo"), rule("r2", 2, "Accept", "net")), List.of(rule("n1", 3, "Accept", "net")));
    }

    static PolicySnapshot plain(String id, String vendor, int rules, boolean failure, boolean disabledOne) {
        List<Rule> access = new ArrayList<>();
        for (int i = 1; i <= rules; i++) {
            Rule r = rule("r" + i, i, "Accept", i % 2 == 0 ? "other" : "host");
            access.add(disabledOne && i == 1 ? new Rule(r.id(), r.uuid(), r.number(), r.name(), Boolean.FALSE, r.source(), r.destination(),
                    r.service(), r.application(), r.action(), r.log(), r.comment(), r.extras()) : r);
        }
        PolicySnapshot s = snapshot(id, vendor, access, List.of());
        return failure ? new PolicySnapshot(s.metadata(), s.sections(), s.objects(),
                List.of(new PolicySnapshot.CollectionFailure("layer", "POLICY_PAGE_TIMEOUT"))) : s;
    }

    static Map<String, Long> expected(Object... kv) {
        Map<String, Long> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], ((Number) kv[i + 1]).longValue());
        }
        return m;
    }

    /** The counters of {@code actual} without cost keys (cpu and heap are not deterministic), zero values dropped. */
    static Map<String, Long> deterministic(Map<String, Long> actual) {
        Map<String, Long> m = new TreeMap<>();
        actual.forEach((k, v) -> {
            if (v != 0 && !k.endsWith(".cpu_ms") && !k.endsWith(".heap_mb")) {
                m.put(k, v);
            }
        });
        return m;
    }

    /** Null when every check holds, else the name of the first failing check (a literal from this file). */
    static String selfTest() {
        try {
            return selfTestChecks();
        } catch (Throwable t) {
            if (System.getProperty("budget.debug") != null) {
                t.printStackTrace();
            }
            return "exception_" + t.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        }
    }

    static String check(String name, Map<String, Long> actual, Map<String, Long> expected) {
        Map<String, Long> d = deterministic(actual);
        if (System.getProperty("budget.debug") != null) {
            System.err.println(name + " " + d);
        }
        return d.equals(expected) ? null : name;
    }

    static String selfTestChecks() throws Exception {
        // The engines' constants this harness relies on are still the production ones and the closed sets are the expected size.
        if (Engine.NOT_EVALUABLE.size() != 5 || Engine.REFUSALS.size() != 5 || !Engine.BUDGET.equals("analysis budget")
                || (int) Engine.constant(Class.forName(P + "PolicyUnionCover"), "TOTAL_WORK") != PolicyHygiene.TOTAL_WORK
                || (int) Engine.constant(Class.forName(P + "PolicyProposal"), "TOTAL_WORK") != PolicyHygiene.TOTAL_WORK
                || (int) Engine.constant(Class.forName(P + "PolicyProposal"), "RULE_WORK") != PolicyHygiene.RULE_WORK) {
            return "engine_constants";
        }
        // Union cover: r3 is covered by r1 and r2 together. Partial: nothing partial. Merge, reorder, proposal: nothing to move.
        String bad = check("cover", measure(coverSnapshot("snap-cover"), PRODUCTION), COVER_EXPECTED);
        if (bad != null) {
            return bad;
        }
        // Partial and reorder: r2 (the whole /24) overlaps r1 before it with the same action; the proposal accepts the one move.
        bad = check("overlap", measure(overlapSnapshot("snap-overlap"), PRODUCTION), OVERLAP_EXPECTED);
        if (bad != null) {
            return bad;
        }
        // A tiny work limit: the engines end NOT_PROVEN on the budget, the hit rate is a positive integer, any_hit is true.
        Map<String, Long> tiny = measure(coverSnapshot("snap-tiny"), TINY);
        bad = check("tiny", tiny, TINY_EXPECTED);
        if (bad != null) {
            return bad;
        }
        // The same snapshot at the production limits is never a hit.
        if (tiny.get("union_cover.not_proven") == 0 || measure(coverSnapshot("snap-prod"), PRODUCTION).get("union_cover.not_proven") != 0) {
            return "tiny_vs_production";
        }
        String sweep = limitSweep();
        if (sweep != null) {
            return sweep;
        }
        String report = rankingChecks();
        if (report != null) {
            return report;
        }
        return outputChecks();
    }

    static final int TINY_WORK = 0, TINY_RULE = 0;
    static final Limits TINY = new Limits(TINY_WORK, TINY_RULE);
    static final Map<String, Long> COVER_EXPECTED = expected("snap.rules_security", 3, "union_cover.evaluated", 3, "union_cover.decided", 3,
            "union_cover.out.covered", 1, "partial_shadow.evaluated", 2, "partial_shadow.decided", 2, "merge.evaluated", 2,
            "merge.decided", 2, "merge.out.candidates", 2);
    static final Map<String, Long> OVERLAP_EXPECTED = expected("snap.rules_security", 2, "union_cover.evaluated", 2,
            "union_cover.decided", 2, "partial_shadow.evaluated", 2, "partial_shadow.decided", 2, "partial_shadow.out.partial", 1,
            "merge.evaluated", 1, "merge.decided", 1, "merge.out.candidates", 1, "reorder.evaluated", 1, "reorder.decided", 1,
            "reorder.out.safe", 1, "proposal.evaluated", 1, "proposal.decided", 1, "proposal.steps", 1, "proposal.accepted", 1,
            "proposal.candidates_total", 1),
            TINY_EXPECTED = expected("snap.rules_security", 3, "union_cover.evaluated", 3, "union_cover.not_proven", 3,
            "partial_shadow.evaluated", 3, "partial_shadow.not_proven", 3, "merge.evaluated", 2, "merge.not_proven", 2);

    /**
     * Every work limit from 0 to 400 over the overlap snapshot: for each engine decided + not_proven is evaluated, the hit rate is
     * in range, and some limit stops the proposal on the budget (never a partly accepted package without the flag).
     */
    static String limitSweep() throws Exception {
        gcBeforeTiming = false;
        try {
            boolean stopped = false, hitAll = false;
            for (int work = 0; work <= 400; work++) {
                Map<String, Long> c = measure(overlapSnapshot("snap-sweep"), new Limits(work, work));
                for (String e : List.of("union_cover", "partial_shadow", "merge", "reorder", PROPOSAL)) {
                    long ev = c.getOrDefault(e + ".evaluated", 0L), np = c.getOrDefault(e + ".not_proven", 0L), d = c.getOrDefault(e + ".decided", 0L);
                    if (d + np != ev || np > ev || hitRatePpm(np, ev) < 0 || hitRatePpm(np, ev) > 1_000_000L) {
                        return "sweep_identity";
                    }
                }
                if (c.getOrDefault(PROPOSAL + ".budget_stopped", 0L) == 1L) {
                    stopped = true;
                    if (c.getOrDefault(PROPOSAL + ".not_proven", 0L) < 1L) {
                        return "sweep_stopped_without_not_proven";
                    }
                }
                hitAll |= c.getOrDefault("union_cover.not_proven", 0L) > 0;
            }
            return stopped && hitAll ? null : "sweep_no_hit";
        } finally {
            gcBeforeTiming = true;
        }
    }

    /** The scan and rank steps over synthetic snapshots: scope counters, the ten largest in order, ties in key order. */
    static String rankingChecks() {
        Map<String, PolicySnapshot> store = new LinkedHashMap<>();
        for (int n = 1; n <= 10; n++) {
            store.put(String.format(Locale.ROOT, KEY_PREFIX + "%02d", n), plain("snap-" + n, "CP", n, false, n == 1));
        }
        store.put(KEY_PREFIX + "B", plain("snap-b", "CP", 11, false, false));
        store.put(KEY_PREFIX + "A", plain("snap-a", "CP", 11, false, false));
        // Larger than all the rest, so a ranking that did not skip them would put them first.
        store.put(KEY_PREFIX + "FAILED", plain("snap-failed", "CP", 50, true, false));
        store.put(KEY_PREFIX + "PAN", plain("snap-pan", "PAN", 60, false, false));
        Report report = new Report();
        store.forEach((k, v) -> rank(report, k, v));
        Map<String, Long> scope = report.scope;
        if (scope.get("snapshots_total") != 14L || scope.get("snapshots_with_failures_skipped") != 1L
                || scope.get("snapshots_vendor_not_evaluated") != 1L || scope.get("snapshots_analysable") != 12L) {
            return "rank_scope";
        }
        List<Candidate> top = topTen(report.candidates);
        List<String> keys = top.stream().map(Candidate::key).toList();
        if (top.size() != TOP || !keys.get(0).equals(KEY_PREFIX + "A") || !keys.get(1).equals(KEY_PREFIX + "B")
                || top.get(2).enabledRules() != 10 || top.get(9).enabledRules() != 3 || keys.contains(KEY_PREFIX + "FAILED")
                || keys.contains(KEY_PREFIX + "PAN")) {
            return "rank_order";
        }
        // Rule 1 of snapshot 1 is disabled: 1 + ... counts enabled rules only; the 14 sizes sum to 1..10 (minus one) plus 22.
        if (report.fleetEnabled != 55 - 1 + 22 || report.fleetDisabled != 1) {
            return "rank_fleet_counts";
        }
        // A ranked snapshot that cannot be re-read only counts.
        Report partial = new Report();
        store.forEach((k, v) -> rank(partial, k, v));
        gcBeforeTiming = false;
        try {
            measureRanked(partial, key -> key.equals(KEY_PREFIX + "A") ? null : store.get(key));
        } finally {
            gcBeforeTiming = true;
        }
        if (partial.scope.get("ranked_reread_failed") != 1L || partial.measured.size() != 9 || partial.scope.get("snapshots_ranked") != 10L
                || !partial.measured.get(0).label().equals("p02")) {
            return "rank_reread";
        }
        return null;
    }

    static String capture(Report report) {
        PrintWriter saved = out;
        StringWriter text = new StringWriter();
        out = new PrintWriter(text, true);
        try {
            emitReport(report);
        } finally {
            out = saved;
        }
        return text.toString();
    }

    static Report synthetic(Limits limits, Map<String, PolicySnapshot> store) {
        Report report = new Report();
        report.limits = limits;
        store.forEach((k, v) -> rank(report, k, v));
        gcBeforeTiming = false;
        try {
            measureRanked(report, store::get);
        } finally {
            gcBeforeTiming = true;
        }
        return report;
    }

    static Map<String, PolicySnapshot> reportStore() {
        Map<String, PolicySnapshot> store = new LinkedHashMap<>();
        store.put(KEY_PREFIX + "1", overlapSnapshot("snap-overlap"));
        store.put(KEY_PREFIX + "2", coverSnapshot("snap-cover"));
        store.put(KEY_PREFIX + "3", plain("snap-failed", "CP", 9, true, false));
        store.put(KEY_PREFIX + "4", plain("snap-pan", "PAN", 9, false, false));
        return store;
    }

    /** The whole report over synthetic data (production limits, then a tiny work limit), as the shell self-test reads it. */
    static String syntheticReport() {
        return capture(synthetic(PRODUCTION, reportStore())) + capture(synthetic(TINY, reportStore()));
    }

    /** The whole report: every line is a safe four-field line, the arithmetic is exact, no planted name or address reaches it. */
    static String outputChecks() {
        if (hitRatePpm(1, 4) != 250_000L || hitRatePpm(0, 4) != 0L || hitRatePpm(3, 3) != 1_000_000L || hitRatePpm(5, 0) != 0L) {
            return "hit_rate_arithmetic";
        }
        String production = capture(synthetic(PRODUCTION, reportStore()));
        String tiny = capture(synthetic(TINY, reportStore()));
        for (String text : List.of(production, tiny)) {
            for (String line : text.split("\n")) {
                if (!SAFE_LINE.matcher(line).matches() || line.split("\t", -1).length != 4) {
                    return "unsafe_line";
                }
            }
            String lower = text.toLowerCase(Locale.ROOT);
            for (String planted : List.of("tango", "192.0.2", "198.51.100", "uuid", "pol-", "comment", "layer", "obj-")) {
                if (lower.contains(planted)) {
                    return "planted_text_reached_output";
                }
            }
            if (text.contains("unsafe_field_dropped")) {
                return "unsafe_field";
            }
        }
        Map<String, String> p = byKey(production), t = byKey(tiny);
        // Production budgets on a tiny policy: no hit anywhere. Scope and fleet lines count what was skipped.
        if (!"4".equals(p.get("scope\tfleet\tsnapshots_total")) || !"1".equals(p.get("scope\tfleet\tsnapshots_with_failures_skipped"))
                || !"1".equals(p.get("scope\tfleet\tsnapshots_vendor_not_evaluated")) || !"2".equals(p.get("scope\tfleet\tsnapshots_ranked"))
                || !"2".equals(p.get("package\tfleet\tsnapshots_measured")) || !"5".equals(p.get("package\tfleet\trules_security_ranked"))) {
            return "report_scope";
        }
        if (!"p01".equals(rank1(production)) || !"3".equals(p.get("package\tp01\trules_security"))) {
            return "report_rank";
        }
        for (String e : List.of("union_cover", "partial_shadow", "merge", "reorder", PROPOSAL)) {
            if (!"false".equals(p.get("budget\tfleet." + e + "\tany_hit")) || !"0".equals(p.get("budget\tfleet." + e + "\thit_rate_ppm"))) {
                return "production_hit";
            }
        }
        // The overlap snapshot (2 rules) and the cover snapshot (3 rules): fleet counters are the sum of the two.
        if (!"5".equals(p.get("engine\tfleet.union_cover\tevaluated")) || !"1".equals(p.get("outcome\tfleet.union_cover\tcovered"))
                || !"1".equals(p.get("outcome\tfleet.proposal\taccepted")) || !"1".equals(p.get("outcome\tfleet.reorder\tsafe"))) {
            return "report_fleet_counters";
        }
        // The tiny limit: every rule of the cover snapshot ends NOT_PROVEN (a hit rate of 100 per cent), any_hit is true.
        if (!"true".equals(t.get("budget\tfleet.union_cover\tany_hit")) || !"true".equals(t.get("budget\tfleet.merge\tany_hit"))
                || !"true".equals(t.get("budget\tfleet.proposal\tany_hit")) || !"1".equals(t.get("outcome\tfleet.proposal\tbudget_stopped"))
                || Long.parseLong(t.get("budget\tfleet.union_cover\thit_rate_ppm")) < 1L
                || Long.parseLong(t.get("budget\tfleet.union_cover\thit_rate_ppm")) > 1_000_000L) {
            return "tiny_hit";
        }
        // The closed vocabularies are printed whole, zero included: every closed reason and every refusal word.
        for (String reason : Engine.NOT_EVALUABLE) {
            if (!p.containsKey("reason\tfleet.merge\t" + slug(reason))) {
                return "reason_vocabulary";
            }
        }
        for (String word : Engine.REFUSALS) {
            if (!p.containsKey("refused\tfleet.proposal\t" + slug(word))) {
                return "refusal_vocabulary";
            }
        }
        // The unsafe-field guard drops a line carrying a name, and an exception class is reduced to a safe token.
        StringWriter text = new StringWriter();
        PrintWriter saved = out;
        out = new PrintWriter(text, true);
        emitText("device", NAME, "x", "1");
        out = saved;
        if (!text.toString().strip().equals("unsafe_field_dropped") || SAFE.matcher(NAME).matches() || !SAFE.matcher("p01.union_cover").matches()) {
            return "unsafe_guard";
        }
        return null;
    }

    static Map<String, String> byKey(String text) {
        Map<String, String> m = new HashMap<>();
        for (String line : text.split("\n")) {
            String[] f = line.split("\t");
            m.put(f[0] + "\t" + f[1] + "\t" + f[2], f[3]);
        }
        return m;
    }

    /** The label of the first package line, which is the largest policy. */
    static String rank1(String text) {
        for (String line : text.split("\n")) {
            String[] f = line.split("\t");
            if (f[0].equals("package") && f[1].startsWith("p")) {
                return f[1];
            }
        }
        return "";
    }
}
