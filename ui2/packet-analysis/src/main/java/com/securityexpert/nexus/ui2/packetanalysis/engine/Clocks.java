package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.Point;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyBuiltins;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyMath;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyStatistics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Reference {@code clock.py} (envelope fit, shared-clock inference, transit quality) and {@code analysis/clocks.py}. */
public final class Clocks {
    private Clocks() {
    }

    /** One calibration sample: (reference time, other time, forward). */
    public record Sample(double a, double b, boolean forward) {
    }

    // ------------------------------------------------------------------ clock.py

    public static ClockModel fitClock(List<Sample> samples) {
        if (samples.isEmpty()) {
            return new ClockModel();
        }
        double epoch = samples.get(0).a();
        double last = samples.get(0).a();
        for (Sample s : samples) {
            epoch = PyBuiltins.min(epoch, s.a());
            last = PyBuiltins.max(last, s.a());
        }
        double span = last - epoch;
        List<Double> forward = new ArrayList<>();
        List<Double> reverse = new ArrayList<>();
        for (Sample s : samples) {
            (s.forward() ? forward : reverse).add(s.b() - s.a());
        }
        if (Math.min(forward.size(), reverse.size()) < 3) {
            ClockModel m = new ClockModel();
            m.epoch = new PyFloat(epoch);
            m.samples = samples.size();
            return m;
        }
        Map<Long, List<Sample>> buckets = new LinkedHashMap<>();
        for (Sample s : samples) {
            long bucket = Math.min(19, PyMath.trunc((s.a() - epoch) / PyBuiltins.max(span, 0.001) * 20));
            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(new Sample(s.a(), s.b() - s.a(), s.forward()));
        }
        List<double[]> envelopes = new ArrayList<>();
        for (List<Sample> points : buckets.values()) {
            List<Double> fw = new ArrayList<>();
            List<Double> rv = new ArrayList<>();
            double[] times = new double[points.size()];
            for (int i = 0; i < points.size(); i++) {
                Sample p = points.get(i);
                (p.forward() ? fw : rv).add(p.b());
                times[i] = p.a();
            }
            if (fw.size() >= 2 && rv.size() >= 2) {
                double upper = PyStatistics.quantiles(array(fw), 100, PyStatistics.Method.INCLUSIVE)[0];
                double[] rq = PyStatistics.quantiles(array(rv), 100, PyStatistics.Method.INCLUSIVE);
                double lower = rq[rq.length - 1];
                envelopes.add(new double[] {PyStatistics.median(times) - epoch, (upper + lower) / 2});
            }
        }
        double drift = 0.0;
        if (span >= 10 && envelopes.size() >= 4) {
            List<Double> slopes = new ArrayList<>();
            for (int i = 0; i < envelopes.size(); i++) {
                double[] a = envelopes.get(i);
                for (int j = i + 1; j < envelopes.size(); j++) {
                    double[] b = envelopes.get(j);
                    if (Math.abs(b[0] - a[0]) > span / 5) {
                        slopes.add((b[1] - a[1]) / (b[0] - a[0]));
                    }
                }
            }
            if (!slopes.isEmpty()) {
                drift = PyStatistics.median(array(slopes));
            }
        }
        if (Math.abs(drift) > 0.005) {
            ClockModel m = new ClockModel();
            m.epoch = new PyFloat(epoch);
            m.samples = samples.size();
            m.reason = "Estimated drift exceeds 5000 ppm";
            return m;
        }
        List<Double> fw = new ArrayList<>();
        List<Double> rv = new ArrayList<>();
        for (Sample s : samples) {
            (s.forward() ? fw : rv).add(s.b() - s.a() - drift * (s.a() - epoch));
        }
        double[] fq = PyStatistics.quantiles(array(fw), 100, PyStatistics.Method.INCLUSIVE);
        double[] rq = PyStatistics.quantiles(array(rv), 100, PyStatistics.Method.INCLUSIVE);
        double upper = fq[0];
        double lower = rq[rq.length - 1];
        double jitter = PyBuiltins.min(0.002, PyStatistics.median(new double[] {3 * (fq[24] - fq[0]), 3 * (rq[98] - rq[74])}));
        if (lower > upper + PyBuiltins.max(2 * jitter, 1e-6)) {
            ClockModel m = new ClockModel();
            m.epoch = new PyFloat(epoch);
            m.samples = samples.size();
            m.timestampJitter = new PyFloat(jitter);
            m.reason = "No feasible jitter-bounded bidirectional percentile envelope";
            return m;
        }
        ClockModel m = new ClockModel();
        m.offset = new PyFloat((upper + lower) / 2);
        m.drift = new PyFloat(drift);
        m.epoch = new PyFloat(epoch);
        m.uncertainty = new PyFloat(Math.abs(upper - lower) / 2 + jitter);
        m.confidence = "estimated";
        m.samples = samples.size();
        m.timestampJitter = new PyFloat(jitter);
        m.reason = "Bidirectional p1/p99 delay envelope; minimum-path symmetry assumed; timestamp jitter and envelope "
                + "infeasibility are included in uncertainty" + (drift != 0.0 ? "; drift fitted" : "; offset-only fit");
        return m;
    }

    /** Near-zero stable, positive transit in both directional orders is bounded evidence, not proof. */
    public static Double inferSharedClock(List<Sample> samples) {
        if (samples.isEmpty()) {
            return null;
        }
        double hi = samples.get(0).a();
        double lo = samples.get(0).a();
        for (Sample s : samples) {
            hi = PyBuiltins.max(hi, s.a());
            lo = PyBuiltins.min(lo, s.a());
        }
        if (hi - lo < 5) {
            return null;
        }
        List<List<Double>> groups = new ArrayList<>();
        for (boolean direction : new boolean[] {true, false}) {
            List<Double> g = new ArrayList<>();
            for (Sample s : samples) {
                if (s.forward() == direction) {
                    g.add(s.forward() ? s.b() - s.a() : s.a() - s.b());
                }
            }
            groups.add(g);
        }
        if (Math.min(groups.get(0).size(), groups.get(1).size()) < 8) {
            return null;
        }
        double c0 = PyStatistics.median(array(groups.get(0)));
        double c1 = PyStatistics.median(array(groups.get(1)));
        if (PyBuiltins.min(c0, c1) < 0 || PyBuiltins.max(c0, c1) > 0.00025) {
            return null;
        }
        List<Double> residuals = new ArrayList<>();
        for (List<Double> g : groups) {
            for (double x : g) {
                residuals.add(Math.abs(x));
            }
        }
        double[] q = PyStatistics.quantiles(array(residuals), 20, PyStatistics.Method.INCLUSIVE);
        double bound = q[q.length - 1];
        return bound <= 0.0005 ? bound : null;
    }

    /** {@code transit_quality(stats, tolerance_ms)}. */
    public static Map<String, Object> transitQuality(Map<String, Object> stats, double toleranceMs) {
        toleranceMs = PyBuiltins.max(0.001, toleranceMs);
        Object p50 = stats.get("p50_ms");
        boolean systematic = p50 != null && ((Number) p50).doubleValue() < -toleranceMs;
        Object outliersValue = stats.get("negative_outliers");
        long outliers = outliersValue == null ? 0 : ((Number) outliersValue).longValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("systematic_negative", systematic);
        out.put("latency_uncertainty_ms", toleranceMs);
        List<Object> notes = new ArrayList<>();
        if (outliers != 0) {
            notes.add(outliers + " negative transit outlier(s) exceed ±"
                    + com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFormat.format(toleranceMs, ".3f")
                    + " ms timestamp/offset uncertainty; retained as quality evidence");
        }
        out.put("quality_notes", notes);
        return out;
    }

    static double[] array(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    // ------------------------------------------------------------------ analysis/clocks.py

    private static void inheritDomains(Map<String, Set<String>> groups, Map<String, ClockModel> models, Topology topology,
            String ref) {
        for (Map.Entry<String, Set<String>> e : groups.entrySet()) {
            List<String> members = new ArrayList<>(e.getValue());
            // sorted(members, key=lambda c: (c not in overrides, c != ref, c))
            members.sort((x, y) -> {
                int c = Boolean.compare(!topology.clockOverrides.containsKey(x), !topology.clockOverrides.containsKey(y));
                if (c != 0) {
                    return c;
                }
                c = Boolean.compare(!x.equals(ref), !y.equals(ref));
                return c != 0 ? c : x.compareTo(y);
            });
            ClockModel known = null;
            for (String cid : members) {
                if (models.containsKey(cid) && models.get(cid).offset != null) {
                    known = models.get(cid);
                    break;
                }
            }
            if (known != null) {
                for (String cid : e.getValue()) {
                    ClockModel m = known.copy();
                    m.sourceDomain = e.getKey();
                    m.confidence = "declared_shared";
                    m.reason = "User-declared shared appliance clock; no relative fitting";
                    models.put(cid, m);
                }
            }
        }
    }

    private static boolean calibratePair(EngineContext ctx, String a, String b) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        Map<String, Point> points = ctx.points;
        Map<String, ClockModel> models = ctx.models;
        String aid = points.get(a).captureId();
        String cid = points.get(b).captureId();
        List<Object[]> data = db.tuples(Sql.get("clocks", "calibration_pairs"), a, b);
        Set<Object> directions = new LinkedHashSet<>();
        for (Object[] r : data) {
            directions.add(r[2]);
        }
        if ((points.get(a).hostCapture() || points.get(b).hostCapture() || points.get(b).kind().equals("Server"))
                && (data.size() < 6 || directions.size() < 2)) {
            data = db.tuples(Sql.get("clocks", "clock_range_candidates"), a, b, a, b);
        }
        boolean forwardBefore = topology.precedes(a, b, "forward");
        boolean reverseBefore = topology.precedes(a, b, "reverse");
        Double sharedBound = null;
        boolean near = false;
        for (Object[] r : data) {
            if (Math.abs((Double) r[1] - (Double) r[0]) <= 0.0005) {
                near = true;
                break;
            }
        }
        if (near && !points.get(a).hostCapture() && !points.get(b).hostCapture() && empty(points.get(a).clockDomain())
                && empty(points.get(b).clockDomain())) {
            List<Sample> samples = new ArrayList<>();
            for (Object[] r : data) {
                double x = (Double) r[0];
                double y = (Double) r[1];
                boolean f = (Boolean) r[2];
                boolean order = f ? forwardBefore : reverseBefore;
                samples.add(new Sample(x, x + (y - x) * (order == f ? 1 : -1), f));
            }
            sharedBound = inferSharedClock(samples);
        }
        if (sharedBound != null) {
            ClockModel source = models.get(aid);
            ClockModel m = source.copy();
            m.sourceDomain = source.sourceDomain != null && !source.sourceDomain.isEmpty() ? source.sourceDomain : aid;
            m.uncertainty = source.uncertainty != null
                    ? pyMaxNum(source.uncertainty, new PyFloat(sharedBound)) : null;
            m.confidence = "inferred_shared";
            m.reason = "Consistent near-zero positive transit in both directions; shared clock inferred, small offset "
                    + "remains indistinguishable from transit";
            models.put(cid, m);
            m.samples = data.size();
            m.evidence = new ArrayList<>();
            for (boolean forward : new boolean[] {true, false}) {
                Object[] sample = null;
                for (Object[] r : data) {
                    if (r[2].equals(forward)) {
                        sample = r;
                        break;
                    }
                }
                if (sample != null) {
                    m.evidence.addAll(Evidence.evidence(db, "(o.point=? AND o.frame=?) OR(o.point=? AND o.frame=?)",
                            Arrays.asList(a, sample[3], b, sample[4]), 2));
                }
            }
            return true;
        }
        List<Sample> samples = new ArrayList<>();
        ClockModel ma = models.get(aid);
        for (Object[] r : data) {
            boolean f = (Boolean) r[2];
            samples.add(new Sample(ma.correct((Double) r[0]), (Double) r[1], f ? forwardBefore : reverseBefore));
        }
        ClockModel model = fitClock(samples);
        if (model.offset == null && (!models.containsKey(cid) || model.samples > models.get(cid).samples)) {
            models.put(cid, model);
        }
        if (model.offset != null) {
            model.domain = ma.domain;
            model.uncertainty = ma.uncertainty != null
                    ? com.securityexpert.nexus.ui2.packetanalysis.numeric.PyArith.add(
                            orZero(model.uncertainty), ma.uncertainty)
                    : null;
            List<Object[]> anchors = new ArrayList<>(data);
            anchors.sort((x, y) -> {
                double kx = (Double) x[1] - ma.correct((Double) x[0]);
                double ky = (Double) y[1] - ma.correct((Double) y[0]);
                return kx < ky ? -1 : kx > ky ? 1 : 0;
            });
            List<Object[]> chosen = new ArrayList<>(anchors.subList(0, Math.min(2, anchors.size())));
            chosen.addAll(anchors.subList(Math.max(0, anchors.size() - 2), anchors.size()));
            for (Object[] r : chosen) {
                model.evidence.addAll(Evidence.evidence(db, "(o.point=? AND o.frame=?) OR (o.point=? AND o.frame=?)",
                        Arrays.asList(a, r[3], b, r[4]), 2));
            }
            models.put(cid, model);
            return true;
        }
        return false;
    }

    /** {@code x or 0}: None and numeric zero are falsy. */
    static PyNum orZero(PyNum x) {
        if (x == null || (x instanceof PyInt i && i.value() == 0) || (x instanceof PyFloat f && f.value() == 0.0)) {
            return new PyInt(0);
        }
        return x;
    }

    private static PyNum pyMaxNum(PyNum a, PyNum b) {
        return com.securityexpert.nexus.ui2.packetanalysis.numeric.PyArith.gt(b, a) ? b : a;
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }

    private static void applyFortinetAndJitter(EngineContext ctx, String ref) {
        Db db = ctx.db;
        Map<String, ClockModel> models = ctx.models;
        Map<Object, List<Object[]>> groups = new LinkedHashMap<>();
        for (Object[] r : db.tuples(Sql.get("clocks", "capture_inventories"))) {
            String cid = (String) r[0];
            Map<?, ?> inv = (Map<?, ?>) Evidence.loads((String) r[1]);
            Object sm = inv.get("source_metadata");
            Map<?, ?> source = sm instanceof Map<?, ?> m && !m.isEmpty() ? m : Map.of();
            if (models.containsKey(cid) && "fortinet".equals(source.get("adapter"))) {
                Object group = source.containsKey("clock_group") ? source.get("clock_group") : cid;
                groups.computeIfAbsent(group, k -> new ArrayList<>()).add(new Object[] {cid, source});
            }
        }
        for (List<Object[]> group : groups.values()) {
            ClockModel known = null;
            for (Object[] g : group) {
                if (models.get((String) g[0]).offset != null) {
                    known = models.get((String) g[0]);
                    break;
                }
            }
            for (Object[] g : group) {
                String cid = (String) g[0];
                Map<?, ?> source = (Map<?, ?>) g[1];
                if (known != null) {
                    ClockModel m = known.copy();
                    m.reason = "Interfaces split from one Fortinet text clock";
                    m.confidence = "shared_source";
                    models.put(cid, m);
                }
                if ("low".equals(source.get("clock_confidence"))) {
                    ClockModel m = models.get(cid);
                    m.confidence = "low";
                    m.uncertainty = null;
                    m.reason = "Relative Fortinet timestamps anchored by a user-supplied start time";
                }
            }
        }
        boolean special = ctx.topology.points.stream()
                .anyMatch(p -> p.stageCapture() || !empty(p.clockDomain()) || p.hostCapture());
        for (Map.Entry<String, ClockModel> e : models.entrySet()) {
            ClockModel model = e.getValue();
            if (model.offset == null || e.getKey().equals(ref) || !special) {
                continue;
            }
            double noise = ((Number) db.scalar(Sql.get("clocks", "jitter_noise"), ref, e.getKey())).doubleValue();
            model.timestampJitter = pyMaxNum(model.timestampJitter, new PyFloat(noise));
        }
    }

    static void align(EngineContext ctx) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        Map<String, Point> points = ctx.points;
        List<String> order = new ArrayList<>(new LinkedHashSet<>(concat(topology)));
        Map<String, Set<String>> groups = new LinkedHashMap<>();
        for (Point p : topology.points) {
            if (!empty(p.clockDomain())) {
                groups.computeIfAbsent(p.clockDomain(), k -> new TreeSet<>()).add(p.captureId());
            }
        }
        Map<String, ClockModel> models = ctx.models;
        models.clear();
        String ref = points.get(order.get(0)).captureId();
        Object epochValue = db.scalar(Sql.get("clocks", "capture_epoch"), ref);
        PyNum epoch = epochValue == null || ((Double) epochValue) == 0.0 ? new PyInt(0) : new PyFloat((Double) epochValue);
        if (epochValue != null && ((Double) epochValue) == 0.0) {
            epoch = new PyInt(0); // `min(ts) or 0`: a zero float is falsy
        }
        ClockModel reference = ClockModel.of(new PyInt(0), new PyInt(0), epoch, new PyInt(0), "reference",
                "Reference capture clock", 0);
        reference.domain = ref;
        models.put(ref, reference);
        for (Map.Entry<String, Topology.ClockOverride> e : topology.clockOverrides.entrySet()) {
            ClockModel m = ClockModel.of(new PyFloat(e.getValue().offsetMs() / 1000), new PyFloat(e.getValue().driftPpm() / 1e6),
                    epoch, null, "override", "User-supplied clock correction; uncertainty unverified", 0);
            m.domain = ref;
            models.put(e.getKey(), m);
        }
        inheritDomains(groups, models, topology, ref);
        boolean fullProxy = topology.points.stream().anyMatch(p -> p.translation().equals("full_proxy"));
        for (int pass = 0; pass < order.size(); pass++) {
            boolean changed = false;
            for (String b : order) {
                String cid = points.get(b).captureId();
                if (models.containsKey(cid) && models.get(cid).offset != null) {
                    continue;
                }
                List<String> byDistance = new ArrayList<>(order);
                int ib = order.indexOf(b);
                byDistance.sort((x, y) -> Integer.compare(Math.abs(order.indexOf(x) - ib), Math.abs(order.indexOf(y) - ib)));
                for (String a : byDistance) {
                    String aid = points.get(a).captureId();
                    if (!models.containsKey(aid) || models.get(aid).offset == null) {
                        continue;
                    }
                    if (calibratePair(ctx, a, b)) {
                        inheritDomains(groups, models, topology, ref);
                        changed = true;
                        break;
                    }
                }
            }
            if (!changed) {
                if (fullProxy) {
                    String missing = null;
                    for (String p : order) {
                        String c = points.get(p).captureId();
                        if (!models.containsKey(c) || models.get(c).offset == null) {
                            missing = c;
                            break;
                        }
                    }
                    if (missing != null && !missing.isEmpty()) {
                        Object localEpoch = db.scalar(Sql.get("clocks", "capture_epoch"), missing);
                        if (localEpoch != null) {
                            ClockModel m = ClockModel.of(new PyInt(0), new PyInt(0), new PyFloat((Double) localEpoch),
                                    new PyInt(0), "reference", "Independent TCP leg reference; cross-domain timing unknown", 0);
                            m.domain = missing;
                            models.put(missing, m);
                            continue;
                        }
                    }
                }
                break;
            }
        }
        for (String p : order) {
            String c = points.get(p).captureId();
            if (!models.containsKey(c)) {
                ClockModel m = new ClockModel();
                m.epoch = epoch;
                models.put(c, m);
            }
        }
        for (Map.Entry<String, Set<String>> e : groups.entrySet()) {
            boolean any = false;
            for (String c : e.getValue()) {
                if (models.containsKey(c) && models.get(c).offset != null) {
                    any = true;
                }
            }
            if (!any) {
                String anchor = ((TreeSet<String>) e.getValue()).first();
                ClockModel m = ClockModel.of(new PyInt(0), new PyInt(0), epoch, new PyInt(0), "declared_shared",
                        "Independent declared clock domain; cross-domain timing unknown", 0);
                m.domain = "shared:" + e.getKey();
                m.sourceDomain = e.getKey();
                models.put(anchor, m);
            }
        }
        inheritDomains(groups, models, topology, ref);
        applyFortinetAndJitter(ctx, ref);
        db.exec(Sql.get("clocks", "clock_values"));
        List<Object[]> values = new ArrayList<>();
        for (Map.Entry<String, ClockModel> e : models.entrySet()) {
            ClockModel m = e.getValue();
            values.add(new Object[] {e.getKey(), ClockModel.value(m.epoch),
                m.offset == null ? null : ClockModel.value(m.offset), ClockModel.value(m.drift)});
        }
        db.execMany(Sql.get("clocks", "clock_values_insert"), values);
        db.exec(Sql.get("clocks", "corrected_obs"));
    }

    private static List<String> concat(Topology topology) {
        List<String> out = new ArrayList<>(topology.forward);
        out.addAll(topology.reverse);
        for (Point p : topology.points) {
            if (p.vendor().equals("paloalto")) {
                out.add(p.id());
            }
        }
        return out;
    }
}
