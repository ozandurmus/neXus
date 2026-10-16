package com.securityexpert.nexus.ui2.packetanalysis.topology;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import com.securityexpert.nexus.ui2.packetanalysis.store.AnalysisFailure;
import com.securityexpert.nexus.ui2.packetanalysis.store.AnalysisLimitException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The analysis window guard: when the analysis range would exceed the packet or seconds budget, the analysis is
 * bounded to a window that does fit, chosen deterministically, and the window is recorded with the run.
 *
 * <p>The analysis range is what the engine would analyse: the topology's own {@code start}/{@code end} when the user
 * set them, else the common coverage of the captures in the path. Three outcomes, none of which touches a result:
 * <ul>
 *   <li><b>explicit</b> - the topology has its own window: it is kept as given;</li>
 *   <li><b>full</b> - no capture has more than {@code maxPacketsPerCapture} packets in the range (and the range is
 *       not longer than {@code maxSeconds}, when set): the topology is left untouched, the engine analyses exactly
 *       what it always did;</li>
 *   <li><b>guarded</b> - the range exceeds the budget: the topology gets {@code start}/{@code end} of the window that
 *       covers the most captures (each with at least {@value #MIN_PACKETS} packets) while no capture exceeds the
 *       budget; among equal windows the widest, then the earliest, wins. This is the window selection of the
 *       reference measurements (S16: at most 150,000 packets per capture), made a function of the stored packets
 *       only, so the same input gives the same window. When no window covers two captures the run fails with
 *       {@value AnalysisFailure#WINDOW_REQUIRED} and asks for an explicit window.</li>
 * </ul>
 * The decision is stored in the project setting {@value #SETTING} (only when it is {@code guarded}), so a later run
 * recognises its own default window (start and end equal the stored ones) and recomputes it instead of treating it
 * as the user's choice.
 */
public final class AnalysisWindowGuard {

    /** Project setting holding the last guarded decision. */
    public static final String SETTING = "analysis_window";
    /** System property: packets per capture above which the range is bounded (0 or less: no packet budget). */
    public static final String MAX_PACKETS_PROPERTY = "packetanalysis.window.max_packets_per_capture";
    /** System property: seconds above which the range is bounded (0 or less: no seconds budget, the default). */
    public static final String MAX_SECONDS_PROPERTY = "packetanalysis.window.max_seconds";
    public static final long DEFAULT_MAX_PACKETS_PER_CAPTURE = 150_000L;
    /** A capture counts as covered by a window when it has at least this many packets in it. */
    public static final int MIN_PACKETS = 20;
    /** Candidate window widths in seconds, widest first (S16). */
    static final double[] WIDTHS = {60.0, 30.0, 10.0, 5.0, 2.0, 1.0, 0.5, 0.2, 0.1};
    /** At most this many start positions are tried per width. */
    private static final int MAX_STARTS = 4000;

    private AnalysisWindowGuard() {
    }

    /** The budget; {@link #enabled()} is false when both limits are off. */
    public record Budget(long maxPacketsPerCapture, double maxSeconds) {

        public static Budget fromSystemProperties() {
            return new Budget(longProperty(MAX_PACKETS_PROPERTY, DEFAULT_MAX_PACKETS_PER_CAPTURE),
                    (double) longProperty(MAX_SECONDS_PROPERTY, 0L));
        }

        public static Budget disabled() {
            return new Budget(0, 0);
        }

        public boolean enabled() {
            return maxPacketsPerCapture > 0 || maxSeconds > 0;
        }

        boolean fits(long packets, double seconds) {
            return (maxPacketsPerCapture <= 0 || packets <= maxPacketsPerCapture)
                    && (maxSeconds <= 0 || seconds <= maxSeconds);
        }

        private static long longProperty(String name, long dflt) {
            String value = System.getProperty(name);
            return value == null || value.isBlank() ? dflt : Long.parseLong(value.strip());
        }
    }

    /**
     * What the guard decided. {@code start}/{@code end} are set for {@code explicit} and {@code guarded}.
     *
     * @param mode        {@code disabled}, {@code explicit}, {@code full} or {@code guarded}
     * @param reason      {@code budget} for a guarded window
     * @param rangePackets the largest packet count of one capture in the analysis range (before bounding)
     * @param windowPackets the largest packet count of one capture in the chosen window
     * @param captures    captures with at least {@value #MIN_PACKETS} packets in the chosen window
     */
    public record Decision(String mode, String reason, Double start, Double end, double rangeSeconds,
            long rangePackets, long windowPackets, int captures, Budget budget) {

        public boolean guarded() {
            return "guarded".equals(mode);
        }

        /** The stored and reported form (numbers and fixed words only: no name, address or path). */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", mode);
            m.put("reason", reason);
            m.put("start", start == null ? null : Py.f(start));
            m.put("end", end == null ? null : Py.f(end));
            m.put("seconds", start == null ? null : Py.f(end - start));
            m.put("range_seconds", Py.f(rangeSeconds));
            m.put("range_max_packets_per_capture", Py.i(rangePackets));
            m.put("window_max_packets_per_capture", Py.i(windowPackets));
            m.put("window_captures", Py.i(captures));
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("max_packets_per_capture", Py.i(budget.maxPacketsPerCapture()));
            b.put("max_seconds", Py.f(budget.maxSeconds()));
            m.put("budget", b);
            if (guarded()) {
                m.put("note", "analysed window: " + trim(start) + " - " + trim(end) + " (" + trim(end - start)
                        + " s), reason: budget (the range has up to " + rangePackets + " packets per capture, "
                        + "budget " + budget.maxPacketsPerCapture() + ")");
            }
            return m;
        }

        private static String trim(double v) {
            return String.format(java.util.Locale.ROOT, "%.6f", v).replaceAll("\\.?0+$", "");
        }
    }

    /**
     * Decides the window for {@code topology}; for a guarded decision sets its {@code start}/{@code end} and stores
     * the decision in the project. Reads packet counts and timestamps only.
     *
     * @throws AnalysisLimitException {@value AnalysisFailure#WINDOW_REQUIRED} when the range exceeds the budget and
     *                                no window covers two captures
     */
    public static Decision apply(ProjectDb project, Topology topology, Budget budget) {
        if (!budget.enabled()) {
            return new Decision("disabled", null, null, null, 0, 0, 0, 0, budget);
        }
        Object previous;
        try (ProjectDb.Db db = project.connect()) {
            previous = db.get(SETTING, null);
        }
        if (previous instanceof Map<?, ?> p && "guarded".equals(p.get("mode"))
                && same(topology.settings.get("start"), p.get("start")) && same(topology.settings.get("end"), p.get("end"))) {
            topology.settings.put("start", null);
            topology.settings.put("end", null);
        }
        Double explicitStart = number(topology.settings.get("start"));
        Double explicitEnd = number(topology.settings.get("end"));
        List<String> captureIds = usedCaptures(topology);
        if (explicitStart != null || explicitEnd != null) {
            forget(project, previous);
            return new Decision("explicit", null, explicitStart, explicitEnd,
                    explicitStart != null && explicitEnd != null ? explicitEnd - explicitStart : 0, 0, 0, 0, budget);
        }
        if (captureIds.isEmpty()) {
            forget(project, previous);
            return new Decision("full", null, null, null, 0, 0, 0, 0, budget);
        }

        // 1. The range the engine would analyse and the busiest capture in it.
        double[] range;
        long[] counts;
        try (ProjectDb.Db db = project.connect()) {
            range = analysisRange(db, captureIds);
            counts = countsIn(db, captureIds, range[0], range[1]);
        }
        long rangePackets = Arrays.stream(counts).max().orElse(0L);
        double rangeSeconds = range[1] - range[0];
        if (budget.fits(rangePackets, rangeSeconds)) {
            forget(project, previous);
            return new Decision("full", null, null, null, rangeSeconds, rangePackets, rangePackets, 0, budget);
        }

        // 2. Over budget: the timestamps in the range, per capture, sorted.
        double[][] stamps;
        try (ProjectDb.Db db = project.connect()) {
            stamps = timestamps(db, captureIds, counts, range[0], range[1]);
        }
        Window w = search(stamps, range[0], range[1], budget);
        if (w == null) {
            throw new AnalysisLimitException(AnalysisFailure.WINDOW_REQUIRED, AnalysisFailure.WINDOW_REQUIRED_MESSAGE,
                    null);
        }
        topology.settings.put("start", new PyFloat(w.start));
        topology.settings.put("end", new PyFloat(w.start + w.width));
        Decision decision = new Decision("guarded", "budget", w.start, w.start + w.width, rangeSeconds, rangePackets,
                w.maxPackets, w.covered, budget);
        try (ProjectDb.Db db = project.connect()) {
            db.set(SETTING, decision.toMap());
        }
        return decision;
    }

    /** A chosen window. */
    record Window(double start, double width, int covered, long maxPackets) {
    }

    /**
     * The window search over sorted timestamps (one array per capture): for every width (widest first) and start
     * (a fixed grid from {@code lo}, plus the latest start), a window is feasible when no capture has more than the
     * budget's packets in it (and the width is within the seconds budget); the best is the one covering the most
     * captures with at least {@value #MIN_PACKETS} packets, then the widest, then the earliest. At least two covered
     * captures are required; otherwise {@code null}.
     */
    static Window search(double[][] stamps, double lo, double hi, Budget budget) {
        double duration = hi - lo;
        Window best = null;
        for (double width : widths(budget)) {
            if (width > duration) {
                continue;
            }
            double span = duration - width;
            double step = Math.max(width / 2, duration / MAX_STARTS);
            Set<Double> starts = new TreeSet<>();
            for (int k = 0; k * step <= span; k++) {
                starts.add(lo + k * step);
            }
            starts.add(lo + span);
            for (double start : starts) {
                double end = start + width;
                int covered = 0;
                long max = 0;
                boolean feasible = true;
                for (double[] ts : stamps) {
                    long n = lowerBound(ts, end, true) - lowerBound(ts, start, false);
                    if (budget.maxPacketsPerCapture() > 0 && n > budget.maxPacketsPerCapture()) {
                        feasible = false;
                        break;
                    }
                    max = Math.max(max, n);
                    if (n >= MIN_PACKETS) {
                        covered++;
                    }
                }
                if (!feasible) {
                    continue;
                }
                // Key: covered captures, then width, then the earlier start (S16: (score, w, -start)).
                if (best == null || covered > best.covered
                        || (covered == best.covered && (width > best.width
                                || (width == best.width && start < best.start)))) {
                    best = new Window(start, width, covered, max);
                }
            }
        }
        return best != null && best.covered >= 2 ? best : null;
    }

    /** Candidate widths: the standard list, within the seconds budget when there is one, widest first. */
    static List<Double> widths(Budget budget) {
        TreeSet<Double> set = new TreeSet<>(java.util.Comparator.reverseOrder());
        for (double w : WIDTHS) {
            if (budget.maxSeconds() <= 0 || w <= budget.maxSeconds()) {
                set.add(w);
            }
        }
        if (budget.maxSeconds() > 0) {
            set.add(budget.maxSeconds());
        }
        return new ArrayList<>(set);
    }

    /** Index of the first element {@code >= value} ({@code afterEqual} false) or {@code > value} (true). */
    private static int lowerBound(double[] sorted, double value, boolean afterEqual) {
        int lo = 0;
        int hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (afterEqual ? sorted[mid] <= value : sorted[mid] < value) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** The captures of the path: forward and reverse points, else every point; sorted ids, no duplicates. */
    static List<String> usedCaptures(Topology topology) {
        Set<String> pointIds = new LinkedHashSet<>(topology.forward);
        pointIds.addAll(topology.reverse);
        TreeSet<String> ids = new TreeSet<>();
        for (Point p : topology.points) {
            if (pointIds.isEmpty() || pointIds.contains(p.id)) {
                if (p.captureId != null && !p.captureId.isEmpty()) {
                    ids.add(p.captureId);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * The engine's range: the common coverage of the used captures; when a capture has no packets or the coverages
     * do not overlap the engine uses no window, and the range is all the packets of the used captures.
     */
    private static double[] analysisRange(ProjectDb.Db db, List<String> ids) {
        List<Map<String, Object>> rows = db.rows("SELECT capture_id, min(ts) AS lo, max(ts) AS hi FROM packets "
                + "WHERE capture_id IN (" + placeholders(ids.size()) + ") GROUP BY capture_id ORDER BY capture_id",
                ids);
        double commonLo = Double.NEGATIVE_INFINITY;
        double commonHi = Double.POSITIVE_INFINITY;
        double allLo = Double.POSITIVE_INFINITY;
        double allHi = Double.NEGATIVE_INFINITY;
        for (Map<String, Object> r : rows) {
            double lo = ((Number) number(r.get("lo"))).doubleValue();
            double hi = ((Number) number(r.get("hi"))).doubleValue();
            commonLo = Math.max(commonLo, lo);
            commonHi = Math.min(commonHi, hi);
            allLo = Math.min(allLo, lo);
            allHi = Math.max(allHi, hi);
        }
        if (rows.isEmpty()) {
            return new double[] {0, 0};
        }
        if (rows.size() == ids.size() && commonLo < commonHi) {
            return new double[] {commonLo, commonHi};
        }
        return new double[] {allLo, allHi};
    }

    /** Packets per capture (in {@code ids} order) with {@code lo <= ts <= hi}. */
    private static long[] countsIn(ProjectDb.Db db, List<String> ids, double lo, double hi) {
        List<Object> params = new ArrayList<>(ids);
        params.add(lo);
        params.add(hi);
        long[] counts = new long[ids.size()];
        Map<String, Integer> index = indexOf(ids);
        db.scan("SELECT capture_id, count(*) FROM packets WHERE capture_id IN (" + placeholders(ids.size())
                + ") AND ts>=? AND ts<=? GROUP BY capture_id", params, row -> {
                    Integer i = index.get(row.getString(1));
                    if (i != null) {
                        counts[i] = row.getLong(2);
                    }
                });
        return counts;
    }

    private static double[][] timestamps(ProjectDb.Db db, List<String> ids, long[] counts, double lo, double hi) {
        double[][] stamps = new double[ids.size()][];
        int[] fill = new int[ids.size()];
        Map<String, Integer> index = indexOf(ids);
        for (int i = 0; i < stamps.length; i++) {
            stamps[i] = new double[(int) counts[i]];
        }
        List<Object> params = new ArrayList<>(ids);
        params.add(lo);
        params.add(hi);
        db.scan("SELECT capture_id, ts FROM packets WHERE capture_id IN (" + placeholders(ids.size())
                + ") AND ts>=? AND ts<=?", params, row -> {
                    Integer i = index.get(row.getString(1));
                    if (i != null && fill[i] < stamps[i].length) {
                        stamps[i][fill[i]++] = row.getDouble(2);
                    }
                });
        for (int i = 0; i < stamps.length; i++) {
            if (fill[i] < stamps[i].length) {
                stamps[i] = Arrays.copyOf(stamps[i], fill[i]);
            }
            Arrays.sort(stamps[i]);
        }
        return stamps;
    }

    private static Map<String, Integer> indexOf(List<String> ids) {
        Map<String, Integer> index = new java.util.HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            index.put(ids.get(i), i);
        }
        return index;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private static void forget(ProjectDb project, Object previous) {
        if (previous != null) {
            try (ProjectDb.Db db = project.connect()) {
                db.set(SETTING, null);
            }
        }
    }

    private static Double number(Object value) {
        return value instanceof PyNum n ? (n.isInt() ? (double) ((com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt) n).value()
                : ((PyFloat) n).value()) : null;
    }

    private static boolean same(Object topologyValue, Object stored) {
        Double a = number(topologyValue);
        Double b = number(stored);
        return a != null && b != null && a.doubleValue() == b.doubleValue();
    }
}
