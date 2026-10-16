package com.securityexpert.nexus.ui2.packetanalysis.engine;

import static com.securityexpert.nexus.ui2.packetanalysis.engine.PyOps.dict;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyConvert;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyMath;
import com.securityexpert.nexus.ui2.packetanalysis.topology.Py;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reference {@code onset.py} (explainable, sustained departures from early loss-free buckets), {@code
 * symptoms.label_retransmissions} and {@code proxy_analysis.restrict_onset_domains} (a pure function of the onset
 * section and the clock models, applied right after {@code add_onsets} in the reference).
 */
public final class Onsets {
    static final long WINDOW_SECONDS = 15;
    static final long MIN_EVENTS = 3;
    static final long MIN_EVENT_BUCKETS = 2;
    /** COUNT_SIGNALS: metric to (count key, denominator key or null). */
    static final Map<String, String[]> COUNT_SIGNALS = new LinkedHashMap<>();
    static final Set<String> NETWORK_SIGNALS = Set.of("loss_percent", "latency_p95_ms", "failed_handshakes");
    static final List<String> LOSS_KINDS = List.of("recovered_loss", "impactful_loss", "unrecovered_loss", "handshake_blocked",
            "confirmed_device_drop");
    static final String SYMPTOM = "symptom observed here (sender retransmits)";
    static final String OUTSIDE = "Outside classified common window; loss rates unknown";

    static {
        COUNT_SIGNALS.put("loss_percent", new String[] {"loss_count", "eligible_packets"});
        COUNT_SIGNALS.put("retrans_percent", new String[] {"retransmissions", "tcp_packets"});
        COUNT_SIGNALS.put("failed_handshakes", new String[] {"failed_handshake_count", null});
        COUNT_SIGNALS.put("resets", new String[] {"resets", null});
        COUNT_SIGNALS.put("zero_windows", new String[] {"zero_windows", null});
    }

    private Onsets() {
    }

    static Object measurement(Map<String, Object> row, String metric) {
        if (!COUNT_SIGNALS.containsKey(metric)) {
            return row.get(metric);
        }
        String[] keys = COUNT_SIGNALS.get(metric);
        if (keys[1] != null) {
            Object denominator = PyOps.get(row, keys[1], 0L);
            return PyOps.truthy(denominator) ? (Object) PyOps.div(PyOps.mul(100L, PyOps.get(row, keys[0], 0L)), denominator) : null;
        }
        return row.get(keys[0]);
    }

    private static Object spreadOf(List<Object> samples, Object center) {
        List<Object> deviations = new ArrayList<>();
        for (Object v : samples) {
            deviations.add(PyOps.obj(com.securityexpert.nexus.ui2.packetanalysis.numeric.PyArith.abs(
                    PyOps.num(PyOps.sub(v, center)))));
        }
        return PyOps.median(deviations);
    }

    private static Object sumKey(List<Map<String, Object>> rows, String key) {
        List<Object> values = new ArrayList<>();
        for (Map<String, Object> v : rows) {
            values.add(v.get(key));
        }
        return PyOps.sum(values);
    }

    /** {@code detect_series}: (item, reason); only an early consecutive healthy prefix seeds the baseline. */
    static Object[] detectSeries(List<Row> source, String metric, Object floor) {
        final long baselineCount = 5;
        final long multiplier = 6;
        List<Map<String, Object>> values = new ArrayList<>();
        for (Row v : source) {
            Map<String, Object> copy = new LinkedHashMap<>(v);
            copy.put(metric, measurement(v, metric));
            values.add(copy);
        }
        List<Map<String, Object>> complete = new ArrayList<>();
        for (Map<String, Object> v : values) {
            if ("capturing".equals(v.get("coverage")) && !OUTSIDE.equals(v.get("reason"))) {
                complete.add(v);
            }
        }
        java.util.function.Predicate<Map<String, Object>> usable = x -> x.get(metric) != null
                && PyOps.gt(PyOps.get(x, "matchable_packets", x.get("eligible_packets")), 0L);
        java.util.function.Predicate<Map<String, Object>> healthy = x -> usable.test(x)
                && (!metric.equals("loss_percent") || PyOps.numEq(x.get(metric), 0L));
        List<Map<String, Object>> prefix = new ArrayList<>();
        int afterPrefix = complete.size();
        for (int i = 0; i < complete.size(); i++) {
            Map<String, Object> row = complete.get(i);
            if (usable.test(row)) {
                prefix.add(row);
                if (prefix.size() == baselineCount) {
                    afterPrefix = i + 1;
                    break;
                }
            }
        }
        long laterUsable = complete.subList(afterPrefix, complete.size()).stream().filter(usable).count();
        if (prefix.size() < baselineCount || laterUsable < 2) {
            return new Object[] {null, "unknown: insufficient covered, matchable samples for baseline and confirmation"};
        }
        if (!prefix.stream().allMatch(healthy)) {
            return new Object[] {null, "unknown: the early measured baseline already contains network loss"};
        }
        List<Object> initial = new ArrayList<>();
        for (Map<String, Object> v : prefix) {
            initial.add(v.get(metric));
        }
        Object center = PyOps.median(initial);
        Object mad = spreadOf(initial, center);
        Object robust = PyOps.mul(PyOps.mul(multiplier, 1.4826), mad);
        if (!COUNT_SIGNALS.containsKey(metric) && (PyOps.gt(PyOps.sub(maxOf(initial), minOf(initial)), PyOps.max(floor, robust))
                || PyOps.gt(mad, PyOps.max(floor, PyOps.mul(abs(center), 0.25))))) {
            return new Object[] {null, "unknown: early baseline is not stable enough"};
        }
        Object bucketWidth = PyOps.sub(prefix.get(0).get("end"), prefix.get(0).get("start"));
        long windowBuckets = Math.max(MIN_EVENT_BUCKETS, PyMath.ceil(PyOps.div(WINDOW_SECONDS, bucketWidth)));
        Object windowSeconds = PyOps.mul(windowBuckets, bucketWidth);
        List<Map<String, Object>> history = new ArrayList<>(prefix);
        Map<String, Object> candidate = null;
        List<Map<String, Object>> recent = new ArrayList<>(prefix);
        Object previous = prefix.get(prefix.size() - 1).get("bucket");
        boolean gap = false;
        for (Map<String, Object> x : complete.subList(afterPrefix, complete.size())) {
            if (!usable.test(x) || !PyOps.numEq(x.get("bucket"), PyOps.add(previous, 1L))) {
                candidate = null;
                history = new ArrayList<>();
                recent = new ArrayList<>();
                gap = true;
            }
            previous = x.get("bucket");
            if (!usable.test(x)) {
                continue;
            }
            if (history.size() < baselineCount) {
                if (healthy.test(x)) {
                    history.add(x);
                } else {
                    history = new ArrayList<>();
                }
                continue;
            }
            recent.add(x);
            List<Map<String, Object>> kept = new ArrayList<>();
            Object horizon = PyOps.sub(x.get("end"), windowSeconds);
            for (Map<String, Object> v : recent) {
                if (PyOps.gt(v.get("end"), horizon)) {
                    kept.add(v);
                }
            }
            recent = kept;
            List<Map<String, Object>> reference = history.subList(Math.max(0, history.size() - (int) baselineCount), history.size());
            List<Object> samples = new ArrayList<>();
            for (Map<String, Object> v : reference) {
                samples.add(v.get(metric));
            }
            Object baseline = PyOps.median(samples);
            Object spread = spreadOf(samples, baseline);
            Object threshold = PyOps.add(baseline, PyOps.max(floor, PyOps.mul(PyOps.mul(multiplier, 1.4826), spread)));
            if (COUNT_SIGNALS.containsKey(metric)) {
                String countKey = COUNT_SIGNALS.get(metric)[0];
                String denominatorKey = COUNT_SIGNALS.get(metric)[1];
                Object exposure = denominatorKey != null ? sumKey(recent, denominatorKey) : (Object) (long) recent.size();
                Object referenceExposure = denominatorKey != null ? sumKey(reference, denominatorKey)
                        : (Object) (long) reference.size();
                long scale = denominatorKey != null ? 100 : 1;
                baseline = PyOps.div(PyOps.mul(scale, sumKey(reference, countKey)), referenceExposure);
                threshold = PyOps.add(baseline, PyOps.max(floor, PyOps.mul(PyOps.mul(multiplier, 1.4826), spread)));
                Object count = sumKey(recent, countKey);
                Object value = PyOps.truthy(exposure) ? (Object) PyOps.div(PyOps.mul(scale, count), exposure) : (Object) 0L;
                Object expected = PyOps.div(PyOps.mul(baseline, exposure), scale);
                long countThreshold = Math.max(MIN_EVENTS, Math.max(PyMath.ceil(PyOps.dbl(PyOps.add(expected, MIN_EVENTS))),
                        PyMath.floor(PyOps.div(PyOps.mul(threshold, exposure), scale)) + 1));
                Object lastReference = reference.get(reference.size() - 1).get("bucket");
                List<Map<String, Object>> changed = new ArrayList<>();
                for (Map<String, Object> v : recent) {
                    Object weight = denominatorKey != null ? v.get(denominatorKey) : (Object) 1L;
                    if (PyOps.gt(v.get("bucket"), lastReference)
                            && PyOps.gt(v.get(countKey), PyOps.div(PyOps.mul(baseline, weight), scale))) {
                        changed.add(v);
                    }
                }
                if (PyOps.ge(count, countThreshold) && changed.size() >= MIN_EVENT_BUCKETS) {
                    Map<String, Object> first = changed.get(0);
                    List<Object> baselineBuckets = new ArrayList<>();
                    for (Map<String, Object> v : reference) {
                        baselineBuckets.add(v.get("bucket"));
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("metric", metric);
                    item.put("baseline_value", baseline);
                    item.put("mad", spread);
                    item.put("threshold", threshold);
                    item.put("multiplier", multiplier);
                    item.put("minimum_delta", floor);
                    item.put("baseline_method", "reference event count / exposure");
                    item.put("baseline_buckets", baselineBuckets);
                    item.put("bucket", first.get("bucket"));
                    item.put("first_event_bucket", first.get("bucket"));
                    item.put("first_crossing_bucket", x.get("bucket"));
                    item.put("confirmed_bucket", x.get("bucket"));
                    item.put("time", first.get("start"));
                    item.put("end", first.get("end"));
                    item.put("value", value);
                    item.put("first_bucket_value", first.get(metric));
                    item.put("window_start", recent.get(0).get("start"));
                    item.put("window_end", x.get("end"));
                    item.put("window_seconds", windowSeconds);
                    item.put("window_buckets", windowBuckets);
                    item.put("window_events", count);
                    item.put("window_denominator", exposure);
                    item.put("expected_events", expected);
                    item.put("count_threshold", countThreshold);
                    item.put("event_buckets", (long) changed.size());
                    item.put("min_events", MIN_EVENTS);
                    return new Object[] {item, null};
                }
                continue;
            }
            if (PyOps.gt(x.get(metric), threshold)) {
                if (candidate != null && PyOps.numEq(x.get("bucket"), PyOps.add(candidate.get("bucket"), 1L))
                        && PyOps.gt(x.get(metric), candidate.get("threshold"))) {
                    candidate.put("confirmed_bucket", x.get("bucket"));
                    return new Object[] {candidate, null};
                }
                List<Object> baselineBuckets = new ArrayList<>();
                for (Map<String, Object> v : reference) {
                    baselineBuckets.add(v.get("bucket"));
                }
                candidate = new LinkedHashMap<>();
                candidate.put("metric", metric);
                candidate.put("baseline_value", baseline);
                candidate.put("mad", spread);
                candidate.put("threshold", threshold);
                candidate.put("multiplier", multiplier);
                candidate.put("minimum_delta", floor);
                candidate.put("baseline_buckets", baselineBuckets);
                candidate.put("bucket", x.get("bucket"));
                candidate.put("first_crossing_bucket", x.get("bucket"));
                candidate.put("time", x.get("start"));
                candidate.put("end", x.get("end"));
                candidate.put("value", x.get(metric));
            } else {
                candidate = null;
                if (healthy.test(x)) {
                    history.add(x);
                    history = new ArrayList<>(history.subList(Math.max(0, history.size() - (int) baselineCount), history.size()));
                }
            }
        }
        return new Object[] {null, gap ? "unknown: coverage or unavailable metric samples limit onset chronology"
                : "No sustained departure from the early healthy baseline"};
    }

    private static Object abs(Object v) {
        return PyOps.lt(v, 0L) ? PyOps.mul(v, -1L) : v;
    }

    static Object maxOf(List<Object> values) {
        Object best = values.get(0);
        for (Object v : values.subList(1, values.size())) {
            if (PyOps.gt(v, best)) {
                best = v;
            }
        }
        return best;
    }

    static Object minOf(List<Object> values) {
        Object best = values.get(0);
        for (Object v : values.subList(1, values.size())) {
            if (PyOps.lt(v, best)) {
                best = v;
            }
        }
        return best;
    }

    /** {@code status_summary(segments)}: (status, counts, fallback summary). */
    static Object[] statusSummary(List<Map<String, Object>> segments) {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Map<String, Object> s : segments) {
            String status = (String) s.get("onset_status");
            counts.put(status, (Long) PyOps.get(counts, status, 0L) + 1);
        }
        long valid = 0;
        for (Map.Entry<String, Object> e : counts.entrySet()) {
            if (!"unknown".equals(e.getKey())) {
                valid += (Long) e.getValue();
            }
        }
        long missing = (Long) PyOps.get(counts, "unknown", 0L);
        if (PyOps.truthy(counts.get("detected"))) {
            return new Object[] {"detected", counts, ""};
        }
        if (valid > 0) {
            return new Object[] {missing > 0 || PyOps.truthy(counts.get("partial")) ? "partial" : "none", counts,
                "No sustained onset in " + valid + " segment(s) with measured baselines; " + missing
                        + " segment(s) have unknown onset status. See per-segment results and quality notes."};
        }
        return new Object[] {"unknown", counts, "Onset unknown: no segment has sufficient covered, matchable baseline samples."};
    }

    @SuppressWarnings("unchecked")
    static void build(EngineContext ctx) {
        TimeEvents te = TimeEvents.of(ctx);
        te.onsets = addOnsets(ctx, te);
        restrictOnsetDomains(ctx, te.onsets);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> addOnsets(EngineContext ctx, TimeEvents te) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        List<Map<String, Object>> segments = ctx.segments;
        List<Map<String, Object>> detections = new ArrayList<>();
        for (Map<String, Object> s : segments) {
            if ("device_stage".equals(s.get("location"))) {
                s.put("onset_status", "unknown");
                s.put("onsets", new ArrayList<>());
                s.put("onset_reasons", PyOps.list(dict("metric", "loss_percent", "reason",
                        "Standalone drop-stage evidence has no healthy path baseline")));
                s.put("onset_quality_notes", dict("capture_misses", 0L, "unknown_events", 0L, "reasons", new ArrayList<>()));
                continue;
            }
            List<Row> values = db.rows(Sql.get("onset", "segment_buckets"), s.get("id"));
            List<Object> misses = new ArrayList<>();
            List<Object> unknowns = new ArrayList<>();
            Set<String> reasons = new TreeSet<>(Py.STR_ORDER);
            for (Row v : values) {
                misses.add(v.get("capture_misses"));
                unknowns.add(v.get("unknown_events"));
                if (PyOps.truthy(v.get("reason")) && "capturing".equals(v.get("coverage"))) {
                    reasons.add(v.str("reason"));
                }
            }
            Map<String, Object> qualityNotes = dict("capture_misses", PyOps.sum(misses), "unknown_events", PyOps.sum(unknowns),
                    "reasons", new ArrayList<>(reasons));
            s.put("onset_quality_notes", qualityNotes);
            List<Map<String, Object>> unknown = new ArrayList<>();
            List<Map<String, Object>> found = new ArrayList<>();
            Object offset = s.get("offset_uncertainty_ms");
            Object latencyFloor = PyOps.max(1.0, PyOps.mul(2L, PyOps.truthy(offset) ? offset : 0L));
            Object[][] metrics = {{"loss_percent", 0.0}, {"latency_p95_ms", latencyFloor}, {"retrans_percent", 0.0},
                {"failed_handshakes", 0.0}, {"resets", 0.0}, {"zero_windows", 0.0}};
            for (Object[] mf : metrics) {
                String metric = (String) mf[0];
                Object floor = mf[1];
                Object[] result = metric.equals("latency_p95_ms") ? latencyWindow(new ArrayList<>(values), floor, metric)
                        : detectSeries(values, metric, floor);
                Map<String, Object> item = (Map<String, Object>) result[0];
                String reason = (String) result[1];
                if (item == null) {
                    unknown.add(dict("metric", metric, "reason", reason, "status", reason.startsWith("unknown:") ? "unknown" : "none"));
                    continue;
                }
                String a = (String) s.get("point_a");
                String b = (String) s.get("point_b");
                boolean hostBoundary = topology.points.stream().anyMatch(p -> p.hostCapture() && (p.id().equals(a) || p.id().equals(b)));
                item.put("segment", s.get("id"));
                item.put("label", s.get("label"));
                item.put("direction", s.get("direction"));
                item.put("point_a", a);
                item.put("point_b", b);
                item.put("time_labels", Headlines.timeLabels(PyOps.dbl(item.get("time")), topology.reportTimezone));
                item.put("clock_uncertainty_ms", s.get("offset_uncertainty_ms"));
                item.put("quality_notes", qualityNotes);
                item.put("scope", metric.equals("latency_p95_ms") && hostBoundary ? "host_capture_boundary"
                        : NETWORK_SIGNALS.contains(metric) ? "network_segment" : "capture_signal");
                List<Map<String, Object>> refs;
                if (metric.equals("loss_percent") || metric.equals("failed_handshakes")) {
                    String kinds = metric.equals("failed_handshakes") ? "('handshake_blocked')"
                            : "('recovered_loss','impactful_loss','unrecovered_loss','handshake_blocked','confirmed_device_drop')";
                    Object[] event = db.one(Sql.fmt("onset", "first_event", "kinds", kinds), a, b, s.get("direction"),
                            item.get("time"), item.get("end"));
                    refs = event != null ? Evidence.evidence(db, "o.packet_key IN (?,?,?)", Arrays.asList(event)) : new ArrayList<>();
                } else if (List.of("retrans_percent", "resets", "zero_windows").contains(metric)) {
                    String condition = switch (metric) {
                        case "retrans_percent" -> "o.retrans";
                        case "resets" -> "o.proto='TCP' AND (o.flags&4)>0";
                        default -> "o.zero_window";
                    };
                    refs = Evidence.evidence(db, "o.point=? AND o.direction=? AND (" + condition + ") AND o.corrected>=? AND o.corrected<?",
                            Arrays.asList(a, s.get("direction"), item.get("time"), item.get("end")), 8);
                } else {
                    Object[] pair = db.one(Sql.get("onset", "max_pair"), a, b, s.get("direction"), item.get("time"), item.get("end"));
                    refs = pair != null ? Evidence.evidence(db, "o.packet_key IN (?,?)", Arrays.asList(pair)) : new ArrayList<>();
                }
                item.put("evidence", refs);
                item.put("explanation", metric + ": baseline " + PyOps.fmt(item.get("baseline_value"), ".3f") + "; threshold "
                        + PyOps.fmt(item.get("threshold"), ".3f") + " (median + max(" + PyOps.fmt(floor, ".3f")
                        + ", 6 × 1.4826 × MAD)); first crossing bucket " + PyOps.str(item.get("bucket")) + " has "
                        + PyOps.fmt(item.get("value"), ".3f") + ", confirmed in bucket " + PyOps.str(item.get("confirmed_bucket")) + ".");
                if (item.containsKey("window_events")) {
                    String unit = COUNT_SIGNALS.get(metric)[1] != null ? "%" : " events/bucket";
                    item.put("explanation", metric + ": baseline " + PyOps.fmt(item.get("baseline_value"), ".3f") + unit
                            + "; robust threshold " + PyOps.fmt(item.get("threshold"), ".3f") + unit + " (MAD "
                            + PyOps.fmt(item.get("mad"), ".3f") + ", multiplier 6 × 1.4826). Rolling up to "
                            + PyOps.fmt(item.get("window_seconds"), "g") + " s window: " + PyOps.str(item.get("window_events"))
                            + " events, expected " + PyOps.fmt(item.get("expected_events"), ".3f") + "; count threshold "
                            + PyOps.str(item.get("count_threshold")) + " (minimum " + MIN_EVENTS + " excess events in "
                            + MIN_EVENT_BUCKETS + " buckets). First event bucket " + PyOps.str(item.get("bucket"))
                            + "; first crossing bucket " + PyOps.str(item.get("first_crossing_bucket")) + ".");
                }
                if (!NETWORK_SIGNALS.contains(metric)) {
                    item.put("explanation", item.get("explanation")
                            + " Local TCP observation; not evidence that this hop caused network loss.");
                }
                found.add(item);
                detections.add(item);
            }
            s.put("onsets", found);
            Set<Object> statuses = new LinkedHashSet<>();
            for (Map<String, Object> x : unknown) {
                statuses.add(x.get("status"));
            }
            s.put("onset_status", !found.isEmpty() ? "detected" : statuses.size() > 1 ? "partial"
                    : statuses.isEmpty() ? "unknown" : statuses.iterator().next());
            s.put("onset_reasons", unknown);
            if (!found.isEmpty()) {
                Map<String, Object> first = PyOps.minBy(found, PyOps.byNum(x -> x.get("time")));
                Map<String, Object> labels = (Map<String, Object>) first.get("time_labels");
                String text = " Change-point onset at " + labels.get("local") + " / " + labels.get("utc") + "; "
                        + first.get("explanation") + " Timing uncertainty includes the " + PyOps.fmt(te.bucketSeconds, "g")
                        + " s bucket and capture clock estimates.";
                String headline = s.containsKey("headline") ? (String) s.get("headline")
                        : "Observed " + first.get("metric") + " change at " + s.get("label") + ".";
                s.put("headline", headline + text);
            }
        }
        labelRetransmissions(ctx, segments, detections);
        List<Map<String, Object>> networkDetections = new ArrayList<>();
        for (Map<String, Object> x : detections) {
            if (List.of("network_segment", "host_capture_boundary").contains(x.get("scope"))) {
                networkDetections.add(x);
            }
        }
        Map<String, Object> directions = new LinkedHashMap<>();
        for (String d : List.of("forward", "reverse")) {
            List<Map<String, Object>> ofDirection = new ArrayList<>();
            for (Map<String, Object> x : networkDetections) {
                if (d.equals(x.get("direction"))) {
                    ofDirection.add(x);
                }
            }
            ofDirection.sort((x, y) -> {
                int c = PyOps.compare(x.get("time"), y.get("time"));
                return c != 0 ? c : PyOps.compareStr((String) x.get("segment"), (String) y.get("segment"));
            });
            Map<Object, Map<String, Object>> firstBySegment = new LinkedHashMap<>();
            for (Map<String, Object> x : ofDirection) {
                firstBySegment.putIfAbsent(x.get("segment"), x);
            }
            List<Map<String, Object>> groups = new ArrayList<>();
            for (Map<String, Object> x : firstBySegment.values()) {
                Object cu = x.get("clock_uncertainty_ms");
                double uncertainty = PyOps.div(PyOps.truthy(cu) ? cu : 0L, 1000L);
                Object lower = PyOps.sub(x.get("time"), uncertainty);
                Object upper = PyOps.add(PyOps.get(x, "onset_bucket_end", x.get("end")), uncertainty);
                if (groups.isEmpty() || PyOps.ge(lower, groups.get(groups.size() - 1).get("end"))) {
                    groups.add(dict("time", x.get("time"), "end", upper, "segments", PyOps.list(x.get("segment"))));
                } else {
                    Map<String, Object> last = groups.get(groups.size() - 1);
                    PyOps.lst(last.get("segments")).add(x.get("segment"));
                    last.put("end", PyOps.max(last.get("end"), upper));
                }
            }
            directions.put(d, dict("propagation_order", groups, "prime_suspects",
                    groups.isEmpty() ? new ArrayList<>() : groups.get(0).get("segments"), "first_time",
                    groups.isEmpty() ? null : groups.get(0).get("time"), "caveat",
                    "Temporal order is evidence, not proof of causation. Overlapping buckets are tied; clock uncertainty may further limit order."));
        }
        Object[] status = statusSummary(segments);
        String summary;
        if (!networkDetections.isEmpty()) {
            Map<String, Object> earliest = PyOps.minBy(networkDetections, PyOps.byNum(x -> x.get("time")));
            Object bound = PyOps.get(earliest, "onset_bucket_end", earliest.get("end"));
            Set<String> suspects = new LinkedHashSet<>();
            for (Map<String, Object> x : networkDetections) {
                if (PyOps.lt(x.get("time"), bound)) {
                    suspects.add(x.get("label") + " (" + x.get("direction") + ")");
                }
            }
            Map<String, Object> labels = (Map<String, Object>) earliest.get("time_labels");
            summary = "First detected degradation: " + String.join(", ", suspects) + " at " + labels.get("local") + " / "
                    + labels.get("utc") + ". Earliest segments are prime suspects, not proven causes; same-bucket order is unresolved and clock uncertainty applies.";
        } else if (!detections.isEmpty()) {
            summary = "Local TCP signal onsets observed. These do not identify a network-loss hop; see per-segment evidence and quality notes.";
        } else {
            summary = (String) status[2];
        }
        return dict("status", status[0], "status_counts", status[1], "items", orderedOnsets(detections, directions), "directions",
                directions, "summary", summary, "baseline_buckets", 5L, "confirmation_buckets", 2L, "count_window_seconds",
                WINDOW_SECONDS, "minimum_events", MIN_EVENTS);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> orderedOnsets(List<Map<String, Object>> items, Map<String, Object> directions) {
        Set<Object> primes = new LinkedHashSet<>();
        for (Object d : directions.values()) {
            Object ps = ((Map<String, Object>) d).get("prime_suspects");
            if (ps != null) {
                primes.addAll((List<Object>) ps);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(items);
        Comparator<Map<String, Object>> order = Comparator.comparingInt(x -> "capture_signal".equals(x.get("scope")) ? 2
                : primes.contains(x.get("segment")) ? 0 : 1);
        order = order.thenComparing((x, y) -> PyOps.compare(PyOps.get(x, "time", 0L), PyOps.get(y, "time", 0L)))
                .thenComparing((x, y) -> PyOps.compareStr((String) x.get("segment"), (String) y.get("segment")))
                .thenComparing((x, y) -> PyOps.compareStr((String) x.get("metric"), (String) y.get("metric")));
        out.sort(order);
        return out;
    }

    /** {@code proxy_analysis.restrict_onset_domains}: unaligned proxy clock domains cannot rank onsets. */
    @SuppressWarnings("unchecked")
    static void restrictOnsetDomains(EngineContext ctx, Map<String, Object> onsets) {
        Map<String, Topology.Point> points = ctx.points;
        List<Map<String, Object>> items = (List<Map<String, Object>>) onsets.get("items");
        for (Map<String, Object> item : items) {
            Topology.Point point = item.containsKey("point_a") ? points.get(item.get("point_a")) : null;
            if (!item.containsKey("point_a")) {
                point = points.get(((String) item.get("segment")).split(":", -1)[1]);
            } else if (point == null) {
                throw new IllegalStateException("KeyError: " + item.get("point_a"));
            }
            if (point != null) {
                ClockModel model = ctx.models.get(point.captureId());
                if (model == null) {
                    throw new IllegalStateException("KeyError: " + point.captureId());
                }
                item.put("clock_domain", model.domain);
            } else {
                item.put("clock_domain", null);
            }
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) onsets.get("directions")).entrySet()) {
            Set<Object> domains = new LinkedHashSet<>();
            for (Map<String, Object> i : items) {
                if (e.getKey().equals(i.get("direction")) && "network_segment".equals(i.get("scope"))) {
                    domains.add(i.get("clock_domain"));
                }
            }
            if (domains.size() > 1) {
                Map<String, Object> value = (Map<String, Object>) e.getValue();
                value.put("propagation_order", new ArrayList<>());
                value.put("prime_suspects", new ArrayList<>());
                value.put("first_time", null);
                value.put("caveat", "Unknown cross-proxy propagation order: independent clock domains have no alignment evidence.");
            }
        }
        Set<Object> domains = new LinkedHashSet<>();
        for (Map<String, Object> i : items) {
            if ("network_segment".equals(i.get("scope"))) {
                domains.add(i.get("clock_domain"));
            }
        }
        if (domains.size() > 1) {
            onsets.put("summary",
                    "Onsets detected per segment; first hop and propagation order across proxy legs are unknown because their clock domains are unaligned.");
        }
        onsets.put("items", orderedOnsets(items, (Map<String, Object>) onsets.get("directions")));
    }

    /** {@code symptoms.label_retransmissions}: a sender's retransmission is a symptom, not a loss location. */
    @SuppressWarnings("unchecked")
    static void labelRetransmissions(EngineContext ctx, List<Map<String, Object>> segments, List<Map<String, Object>> detections) {
        Map<Object, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> s : segments) {
            byId.put(s.get("id"), s);
        }
        for (Map<String, Object> segment : segments) {
            Map<String, Object> classes = (Map<String, Object>) segment.get("classes");
            segment.put("loss_suspect", LOSS_KINDS.stream().anyMatch(k -> PyOps.gt(PyOps.get(classes, k, 0L), 0L)));
        }
        for (Map<String, Object> item : detections) {
            if (!"retrans_percent".equals(item.get("metric"))) {
                continue;
            }
            Map<String, Object> segment = byId.get(item.get("segment"));
            String direction = (String) item.get("direction");
            List<String> path = ctx.topology.path(direction);
            int index = path.indexOf((String) segment.get("point_a"));
            if (index < 0) {
                throw new IllegalArgumentException(segment.get("point_a") + " is not in list");
            }
            List<Object[]> found = ctx.db.tuples(Sql.get("onset", "retrans_losses"), direction, item.get("window_end"),
                    item.get("window_start"), segment.get("point_a"), direction, item.get("window_start"), item.get("window_end"));
            List<String> downstream = new ArrayList<>();
            for (Object[] r : found) {
                String a = (String) r[0];
                if (path.contains(a) && path.indexOf(a) > index) {
                    downstream.add(direction + ":" + a + ":" + r[1]);
                }
            }
            List<String> sorted = Py.sortedStr(downstream);
            item.put("display_label", SYMPTOM);
            item.put("suspect", false);
            item.put("related_loss_segments", new ArrayList<>(sorted));
            item.put("explanation", SYMPTOM + ". " + item.get("explanation"));
            if (!downstream.isEmpty()) {
                item.put("explanation", item.get("explanation")
                        + " Independently supported downstream loss for contributing flows: " + String.join(", ", sorted) + ".");
                segment.put("symptom_note", SYMPTOM);
                if (!PyOps.truthy(segment.get("loss_suspect"))) {
                    segment.put("headline", SYMPTOM + ". " + PyOps.get(segment, "headline", ""));
                }
            }
        }
    }

    /** {@code latency_window}: a bounded sustained excursion and its first recovered bucket. */
    static Object[] latencyWindow(List<? extends Map<String, Object>> values, Object floor, String metric) {
        List<Map<String, Object>> valid = new ArrayList<>();
        for (Map<String, Object> v : values) {
            if (v.get(metric) != null && "capturing".equals(PyOps.get(v, "coverage", "capturing")) && !PyOps.truthy(v.get("reason"))) {
                valid.add(v);
            }
        }
        if (valid.size() < 8) {
            return new Object[] {null, "unknown: insufficient matched baseline and recovery samples"};
        }
        List<Object> baseline = new ArrayList<>();
        for (Map<String, Object> v : valid.subList(0, 5)) {
            baseline.add(v.get(metric));
        }
        Object center = PyOps.median(baseline);
        Object mad = spreadOf(baseline, center);
        Object robust = PyOps.mul(PyOps.mul(6L, 1.4826), mad);
        Object threshold = PyOps.add(center, PyOps.max(floor, robust));
        if (PyOps.gt(PyOps.sub(maxOf(baseline), minOf(baseline)), PyOps.max(floor, robust))) {
            return new Object[] {null, "unknown: unstable early latency baseline"};
        }
        List<Integer> changed = new ArrayList<>();
        Integer startIndex = null;
        for (int i = 5; i < valid.size(); i++) {
            Map<String, Object> v = valid.get(i);
            if (PyOps.gt(v.get(metric), threshold)) {
                List<Integer> kept = new ArrayList<>();
                Object horizon = PyOps.sub(v.get("start"), 15L);
                for (int j : changed) {
                    if (PyOps.ge(valid.get(j).get("start"), horizon)) {
                        kept.add(j);
                    }
                }
                kept.add(i);
                changed = kept;
                if (changed.size() >= 3) {
                    startIndex = changed.get(0);
                    break;
                }
            }
        }
        if (startIndex == null) {
            return new Object[] {null, "No sustained departure from the early healthy baseline"};
        }
        int last = startIndex;
        List<Map<String, Object>> quiet = new ArrayList<>();
        Map<String, Object> recovery = null;
        for (int index = startIndex + 1; index < valid.size(); index++) {
            Map<String, Object> v = valid.get(index);
            if (PyOps.gt(v.get(metric), threshold)) {
                last = index;
                quiet = new ArrayList<>();
            } else {
                quiet.add(v);
                if (quiet.size() == 3) {
                    recovery = quiet.get(0);
                    break;
                }
            }
        }
        Map<String, Object> first = valid.get(startIndex);
        Object lo = valid.get(0).get("start");
        Object hi = recovery != null ? recovery.get("start") : valid.get(valid.size() - 1).get("end");
        for (Map<String, Object> v : values) {
            if (PyOps.le(lo, v.get("start")) && PyOps.lt(v.get("start"), hi)
                    && (!"capturing".equals(PyOps.get(v, "coverage", "capturing")) || PyOps.truthy(v.get("reason")))) {
                return new Object[] {null, "unknown: capture/quality gap interrupts baseline or event chronology"};
            }
        }
        List<Object> baselineBuckets = new ArrayList<>();
        for (Map<String, Object> v : valid.subList(0, 5)) {
            baselineBuckets.add(bucketOf(v));
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("metric", metric);
        item.put("time", first.get("start"));
        item.put("end", hi);
        item.put("bucket", bucketOf(first));
        item.put("first_crossing_bucket", bucketOf(first));
        item.put("confirmed_bucket", bucketOf(valid.get(changed.get(changed.size() - 1))));
        item.put("onset_bucket_end", first.get("end"));
        item.put("baseline_value", center);
        item.put("mad", mad);
        item.put("threshold", threshold);
        item.put("multiplier", 6L);
        item.put("minimum_delta", floor);
        item.put("value", first.get(metric));
        item.put("baseline_buckets", baselineBuckets);
        item.put("recovery_confirmed", recovery != null);
        item.put("last_delayed_bucket", valid.get(last).get("start"));
        item.put("baseline_method",
                "early matched latency median/MAD; three crossings within 15 s; three healthy observations confirm recovery");
        return new Object[] {item, null};
    }

    private static Object bucketOf(Map<String, Object> v) {
        if (v.containsKey("bucket")) {
            return v.get("bucket");
        }
        Object start = v.get("start");
        return start instanceof Long ? start : (Object) PyConvert.toInt(PyOps.dbl(start));
    }
}
