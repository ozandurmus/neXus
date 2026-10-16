package com.securityexpert.nexus.ui2.packetanalysis.export;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyArith;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import com.securityexpert.nexus.ui2.packetanalysis.topology.Py;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of the reference {@code reference.py}: local reference checks, reference template generation, and
 * semantic run diffs without evaluation or network access.
 */
public final class ReferenceCompare {
    public static final Object MISSING = new Object();

    public static final Set<String> CHECK_KINDS = Set.of(
            "abs_tol",
            "rel_tol_pct",
            "max",
            "min",
            "equals",
            "window_tol_s");

    public static final List<String> TEMPLATE_PATHS = List.of(
            "$.flow_count",
            "$.window.common_start",
            "$.window.common_end",
            "$.host_path.window",
            "$.host_path.local_service.cycle_aggregate.p95_ms",
            "$.host_path.local_service.cycle_aggregate.samples",
            "$.host_path.items[0].affected_flow_count",
            "$.host_path.items[0].max_ms",
            "$.finding_count",
            "$.cycles.total",
            "$.cycles.stalled_total",
            "$.cycles.max_added_ms",
            "$.cycles.event.total",
            "$.cycles.event.stalled_total",
            "$.cycles.event.max_added_ms",
            "$.cycles.repeat_groups.forward",
            "$.cycles.repeat_groups.reverse",
            "$.host_path.rx_share",
            "$.host_path.max_silence_ms",
            "$.host_path.event.rx_share",
            "$.host_path.event.max_silence_ms",
            "$.onsets.retrans_peak_time",
            "$.onsets.retrans_peak_count",
            "$.host_path.items[0].affected_flows_ge_stall");

    public static final Set<String> IGNORED_DIFF_KEYS = Set.of(
            "generated_at",
            "exported_at",
            "id",
            "capture_id",
            "evidence",
            "evidence_refs",
            "frame",
            "evidence_key",
            "request_frame",
            "response_frame",
            "request_last_frame",
            "evidence_frame",
            "evidence_frame_a",
            "evidence_frame_b");

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "\\.([A-Za-z_][A-Za-z0-9_-]*)|\\[(\\d+)\\]|\\['([^']+)'\\]|\\[\"([^\"]+)\"\\]");

    private ReferenceCompare() {
    }

    /** Reference {@code ReferenceItem} model and validator. */
    public record ReferenceItem(String id, String description, String metric, Object expected,
            Map<String, Object> check) {
        private static final Set<String> ALLOWED_KEYS = Set.of("id", "description", "metric", "expected", "check");

        @SuppressWarnings("unchecked")
        public static ReferenceItem modelValidate(Map<String, Object> raw) {
            if (raw == null) {
                throw new IllegalArgumentException("ReferenceItem must be an object");
            }
            for (String k : raw.keySet()) {
                if (!ALLOWED_KEYS.contains(k)) {
                    throw new IllegalArgumentException("Extra inputs are not permitted: " + k);
                }
            }
            if (!(raw.get("id") instanceof String id) || id.isEmpty() || id.length() > 200) {
                throw new IllegalArgumentException("id must be a string of length 1..200");
            }
            if (!(raw.get("description") instanceof String description) || description.length() > 2000) {
                throw new IllegalArgumentException("description must be a string of length 0..2000");
            }
            if (!(raw.get("metric") instanceof String metric) || metric.length() < 2 || metric.length() > 2000) {
                throw new IllegalArgumentException("metric must be a string of length 2..2000");
            }
            if (!raw.containsKey("expected")) {
                throw new IllegalArgumentException("expected is required");
            }
            Object expected = raw.get("expected");
            if (!(raw.get("check") instanceof Map<?, ?> checkRaw)) {
                throw new IllegalArgumentException("check must be an object");
            }
            Map<String, Object> check = new LinkedHashMap<>((Map<String, Object>) checkRaw);
            if (check.size() != 1) {
                throw new IllegalArgumentException("Choose exactly one supported check");
            }
            Map.Entry<String, Object> single = check.entrySet().iterator().next();
            String kind = single.getKey();
            Object value = single.getValue();
            if (!CHECK_KINDS.contains(kind)) {
                throw new IllegalArgumentException("Choose exactly one supported check");
            }
            if (!"equals".equals(kind)) {
                if (!number(value) || (!"min".equals(kind) && !"max".equals(kind) && ((Number) value).doubleValue() < 0.0)) {
                    throw new IllegalArgumentException(
                            "Tolerance must be a finite non-negative number; bounds must be finite");
                }
            }
            parsePath(metric);
            return new ReferenceItem(id, description, metric, expected, check);
        }

        public Map<String, Object> modelDump() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("description", description);
            out.put("metric", metric);
            out.put("expected", expected);
            out.put("check", new LinkedHashMap<>(check));
            return out;
        }
    }

    /** Reference {@code Reference} model and validator. */
    public record Reference(long schemaVersion, List<ReferenceItem> items) {
        private static final Set<String> ALLOWED_KEYS = Set.of("schema_version", "items");

        @SuppressWarnings("unchecked")
        public static Reference modelValidate(Object rawObj) {
            if (!(rawObj instanceof Map<?, ?> rawMap)) {
                throw new IllegalArgumentException("Reference must be an object");
            }
            Map<String, Object> raw = (Map<String, Object>) rawMap;
            for (String k : raw.keySet()) {
                if (!ALLOWED_KEYS.contains(k)) {
                    throw new IllegalArgumentException("Extra inputs are not permitted: " + k);
                }
            }
            long schemaVersion = 1L;
            if (raw.containsKey("schema_version")) {
                Object sv = raw.get("schema_version");
                if (sv instanceof Boolean || !(sv instanceof Long || sv instanceof Integer)
                        || ((Number) sv).longValue() != 1L) {
                    throw new IllegalArgumentException("schema_version must be 1");
                }
                schemaVersion = ((Number) sv).longValue();
            }
            if (!(raw.get("items") instanceof List<?> rawItems) || rawItems.size() > 10000) {
                throw new IllegalArgumentException("items must be a list of at most 10000 elements");
            }
            List<ReferenceItem> items = new ArrayList<>(rawItems.size());
            Set<String> ids = new LinkedHashSet<>();
            for (Object itemObj : rawItems) {
                if (!(itemObj instanceof Map<?, ?> itemMap)) {
                    throw new IllegalArgumentException("Each item must be an object");
                }
                ReferenceItem item = ReferenceItem.modelValidate((Map<String, Object>) itemMap);
                ids.add(item.id());
                items.add(item);
            }
            if (ids.size() != items.size()) {
                throw new IllegalArgumentException("Reference item IDs must be unique");
            }
            return new Reference(schemaVersion, items);
        }
    }

    public static List<Object> parsePath(String path) {
        if (path == null || !path.startsWith("$")) {
            throw new IllegalArgumentException("JSONPath must start with $");
        }
        List<Object> tokens = new ArrayList<>();
        int position = 1;
        Matcher matcher = TOKEN_PATTERN.matcher(path);
        while (position < path.length()) {
            matcher.region(position, path.length());
            if (!matcher.lookingAt()) {
                throw new IllegalArgumentException("Supported JSONPath: object fields and explicit array indices");
            }
            if (matcher.group(2) != null) {
                tokens.add(Long.parseLong(matcher.group(2)));
            } else if (matcher.group(1) != null) {
                tokens.add(matcher.group(1));
            } else if (matcher.group(3) != null) {
                tokens.add(matcher.group(3));
            } else {
                tokens.add(matcher.group(4));
            }
            position = matcher.end();
        }
        return tokens;
    }

    public static Object resolve(Object data, String path) {
        Object value = data;
        for (Object key : parsePath(path)) {
            if (key instanceof Long idx) {
                if (!(value instanceof List<?> list) || idx < 0 || idx >= list.size()) {
                    return MISSING;
                }
                value = list.get(idx.intValue());
            } else if (key instanceof String k) {
                if (!(value instanceof Map<?, ?> map) || !map.containsKey(k)) {
                    return MISSING;
                }
                value = map.get(k);
            } else {
                return MISSING;
            }
        }
        return value;
    }

    public static boolean number(Object value) {
        if (value instanceof Boolean || !(value instanceof Number n)) {
            return false;
        }
        return Double.isFinite(n.doubleValue());
    }

    public static List<Object> windowValues(Object value) {
        if (value instanceof Map<?, ?> m) {
            return Arrays.asList(m.get("start"), m.get("end"));
        }
        if (value instanceof List<?> l && l.size() == 2) {
            return Arrays.asList(l.get(0), l.get(1));
        }
        return Arrays.asList(null, null);
    }

    public static Map<String, Object> compareReference(Object data, Object reference) {
        Reference ref = Reference.modelValidate(reference);
        List<Map<String, Object>> results = new ArrayList<>(ref.items().size());
        for (ReferenceItem item : ref.items()) {
            Object value = resolve(data, item.metric());
            Map.Entry<String, Object> checkEntry = item.check().entrySet().iterator().next();
            String kind = checkEntry.getKey();
            Object threshold = checkEntry.getValue();
            String reason = null;
            Boolean passed = null;
            if (value == MISSING || value == null) {
                reason = "Metric path missing or value not computed";
            } else if ("equals".equals(kind)) {
                passed = FieldDiff.pyEquals(value, threshold);
            } else if ("window_tol_s".equals(kind)) {
                List<Object> actual = windowValues(value);
                List<Object> expected = windowValues(item.expected());
                if (!number(actual.get(0)) || !number(actual.get(1))
                        || !number(expected.get(0)) || !number(expected.get(1))) {
                    reason = "Window endpoints not computed or expected window invalid";
                } else {
                    PyNum tol = FieldDiff.toPyNum((Number) threshold);
                    PyNum d0 = PyArith.abs(PyArith.sub(
                            FieldDiff.toPyNum((Number) actual.get(0)),
                            FieldDiff.toPyNum((Number) expected.get(0))));
                    PyNum d1 = PyArith.abs(PyArith.sub(
                            FieldDiff.toPyNum((Number) actual.get(1)),
                            FieldDiff.toPyNum((Number) expected.get(1))));
                    passed = PyArith.le(d0, tol) && PyArith.le(d1, tol);
                }
            } else if (!number(value)) {
                reason = "Metric is not a finite numeric value";
            } else if ("max".equals(kind) || "min".equals(kind)) {
                PyNum vNum = FieldDiff.toPyNum((Number) value);
                PyNum tNum = FieldDiff.toPyNum((Number) threshold);
                passed = "max".equals(kind) ? PyArith.le(vNum, tNum) : PyArith.ge(vNum, tNum);
            } else if (!number(item.expected())) {
                reason = "Expected value is not numeric";
            } else if ("abs_tol".equals(kind)) {
                PyNum diff = PyArith.abs(PyArith.sub(
                        FieldDiff.toPyNum((Number) value),
                        FieldDiff.toPyNum((Number) item.expected())));
                passed = PyArith.le(diff, FieldDiff.toPyNum((Number) threshold));
            } else {
                PyNum diff = PyArith.abs(PyArith.sub(
                        FieldDiff.toPyNum((Number) value),
                        FieldDiff.toPyNum((Number) item.expected())));
                PyNum bound = PyArith.trueDiv(
                        PyArith.mul(PyArith.abs(FieldDiff.toPyNum((Number) item.expected())),
                                FieldDiff.toPyNum((Number) threshold)),
                        new PyInt(100));
                passed = PyArith.le(diff, bound);
            }
            Map<String, Object> row = item.modelDump();
            row.put("value", value == MISSING ? null : value);
            row.put("status", passed == null ? "not-computed" : passed ? "pass" : "fail");
            row.put("reason", reason);
            results.add(row);
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String s : List.of("pass", "fail", "not-computed")) {
            long c = 0L;
            for (Map<String, Object> r : results) {
                if (s.equals(r.get("status"))) {
                    c++;
                }
            }
            counts.put(s, c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", results);
        out.put("counts", counts);
        return out;
    }

    public static Map<String, Object> referenceTemplate(Object report) {
        List<Map<String, Object>> items = new ArrayList<>(TEMPLATE_PATHS.size());
        for (int n = 0; n < TEMPLATE_PATHS.size(); n++) {
            String path = TEMPLATE_PATHS.get(n);
            Object v = resolve(report, path);
            Map<String, Object> check = new LinkedHashMap<>();
            if (path.endsWith(".window")) {
                check.put("window_tol_s", 1L);
            } else {
                check.put("abs_tol", 0L);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", "metric-" + n);
            item.put("description", path);
            item.put("metric", path);
            item.put("expected", v == MISSING ? null : v);
            item.put("check", check);
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", 1L);
        out.put("items", items);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> unwrap(Object data) {
        if (!(data instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Analysis report/export must be an object");
        }
        Object inner = map.containsKey("report") ? map.get("report") : data;
        if (!(inner instanceof Map<?, ?> innerMap)) {
            throw new IllegalArgumentException("Analysis report/export must be an object");
        }
        return (Map<String, Object>) innerMap;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compareRuns(Object beforeRaw, Object afterRaw) {
        Map<String, Object> before = unwrap(beforeRaw);
        Map<String, Object> after = unwrap(afterRaw);
        List<Map<String, Object>> changes = new ArrayList<>();
        long[] total = {0L};
        walkDiff(before, after, "$", changes, total);

        Map<List<Object>, List<Object>> a = groupFindings(before);
        Map<List<Object>, List<Object>> b = groupFindings(after);
        List<List<Object>> allKeys = new ArrayList<>();
        Set<List<Object>> seenKeys = new LinkedHashSet<>(a.keySet());
        seenKeys.addAll(b.keySet());
        allKeys.addAll(seenKeys);
        allKeys.sort((k1, k2) -> Py.compareStr(tupleRepr(k1), tupleRepr(k2)));

        List<Map<String, Object>> findings = new ArrayList<>();
        for (List<Object> key : allKeys) {
            List<Object> av = a.get(key);
            List<Object> bv = b.get(key);
            if (!FieldDiff.pyEquals(av, bv)) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("type", key.get(0));
                diff.put("hop", key.get(1));
                diff.put("direction", key.get(2));
                diff.put("status", !a.containsKey(key) ? "added" : !b.containsKey(key) ? "removed" : "changed");
                diff.put("before", av);
                diff.put("after", bv);
                findings.add(diff);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metrics", changes);
        out.put("metric_difference_count", total[0]);
        out.put("truncated", total[0] > changes.size());
        out.put("findings", findings);
        out.put("note",
                "Generated timestamps, IDs and frame-reference metadata are ignored; analysis values and finding metrics are compared. List order remains significant.");
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void walkDiff(Object a, Object b, String path, List<Map<String, Object>> changes, long[] total) {
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            Set<String> keys = new TreeSet<>(Py.STR_ORDER);
            for (Object k : ma.keySet()) {
                keys.add(String.valueOf(k));
            }
            for (Object k : mb.keySet()) {
                keys.add(String.valueOf(k));
            }
            for (String k : keys) {
                if (!IGNORED_DIFF_KEYS.contains(k) && !"findings".equals(k)) {
                    Object av = ma.containsKey(k) ? ma.get(k) : MISSING;
                    Object bv = mb.containsKey(k) ? mb.get(k) : MISSING;
                    walkDiff(av, bv, path + "." + k, changes, total);
                }
            }
        } else if (a instanceof List<?> la && b instanceof List<?> lb) {
            int max = Math.max(la.size(), lb.size());
            for (int i = 0; i < max; i++) {
                Object av = i < la.size() ? la.get(i) : MISSING;
                Object bv = i < lb.size() ? lb.get(i) : MISSING;
                walkDiff(av, bv, path + "[" + i + "]", changes, total);
            }
        } else if (!diffEquals(a, b)) {
            total[0]++;
            if (changes.size() < 1000) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("metric", path);
                item.put("before", a == MISSING ? null : a);
                item.put("after", b == MISSING ? null : b);
                item.put("status", (a == MISSING || b == MISSING) ? "missing" : "changed");
                changes.add(item);
            }
        }
    }

    private static boolean diffEquals(Object a, Object b) {
        if (a == MISSING || b == MISSING) {
            return a == b;
        }
        return FieldDiff.pyEquals(a, b);
    }

    @SuppressWarnings("unchecked")
    private static Object cleanFinding(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (!IGNORED_DIFF_KEYS.contains(k)) {
                    out.put(k, cleanFinding(e.getValue()));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object v : list) {
                out.add(cleanFinding(v));
            }
            return out;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<List<Object>, List<Object>> groupFindings(Map<String, Object> report) {
        Map<List<Object>, List<Object>> result = new LinkedHashMap<>();
        Object rawFindings = report.get("findings");
        if (!(rawFindings instanceof List<?> list)) {
            return result;
        }
        for (Object fObj : list) {
            if (!(fObj instanceof Map<?, ?> f)) {
                continue;
            }
            List<Object> key = Arrays.asList(f.get("type"), f.get("hop"), f.get("direction"));
            Map<String, Object> filtered = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : f.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (!IGNORED_DIFF_KEYS.contains(k) && !"headline".equals(k) && !"summary".equals(k)) {
                    filtered.put(k, e.getValue());
                }
            }
            Object cleaned = cleanFinding(filtered);
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(cleaned);
        }
        return result;
    }

    private static String tupleRepr(List<Object> tuple) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < tuple.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(Py.repr(tuple.get(i)));
        }
        if (tuple.size() == 1) {
            sb.append(",");
        }
        sb.append(")");
        return sb.toString();
    }
}
