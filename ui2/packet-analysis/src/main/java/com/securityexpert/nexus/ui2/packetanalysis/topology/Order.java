package com.securityexpert.nexus.ui2.packetanalysis.topology;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TTL and microsecond ordering of capture units and separate-clock conflict isolation ({@code topology/order.py}).
 *
 * <p>A unit is one capture or a stage pair {@code [rx, tx]}. Rows are the reference SQL result dicts:
 * {@code ttlByCid} per capture ({@code fwd_ttl}, {@code rev_ttl}, {@code fwd_n}, {@code fwd_frame}, ...) and
 * {@code pairStats} per ordered capture pair and direction ({@code n}, {@code delta_us}, {@code ttl_step},
 * {@code frame_a}, {@code frame_b}).
 */
public final class Order {

    private Order() {
    }

    /** The key of a directional pair statistic: {@code (a, b, dir)}. */
    public record PairKey(String a, String b, String dir) {
    }

    /** {@code query_ttl_and_pair_stats} result. */
    public record Stats(Map<String, Map<String, Object>> ttlByCid, Map<PairKey, Map<String, Object>> pairStats) {
    }

    /** {@code compare_units} result: -1 when u1 precedes u2, +1 when u2 precedes u1, 0 when ambiguous. */
    public record Comparison(int order, String error, List<Map<String, Object>> refs, boolean usConflict) {
    }

    /** {@code query_ttl_and_pair_stats(db, ready_ids, client_ips)}. */
    public static Stats queryTtlAndPairStats(ProjectDb.Db db, List<String> readyIds, List<String> clientIps) {
        Map<String, String> fields = Map.of("placeholders", ProjectDb.placeholders(readyIds.size()),
                "ip_placeholders", ProjectDb.placeholders(clientIps.size()));
        List<Object> params = new ArrayList<>(readyIds);
        for (int i = 0; i < 8; i++) {
            params.addAll(clientIps);
        }
        params.addAll(readyIds);
        Map<String, Map<String, Object>> ttlByCid = new LinkedHashMap<>();
        for (Map<String, Object> r : db.rows(ProjectDb.sql("order_ttl_by_capture", fields), params)) {
            ttlByCid.put((String) r.get("capture_id"), r);
        }
        List<Object> pairParams = new ArrayList<>(clientIps);
        pairParams.addAll(clientIps);
        pairParams.addAll(readyIds);
        Map<PairKey, Map<String, Object>> pairStats = new LinkedHashMap<>();
        for (Map<String, Object> r : db.rows(ProjectDb.sql("order_pair_stats", fields), pairParams)) {
            pairStats.put(new PairKey((String) r.get("a"), (String) r.get("b"), (String) r.get("dir")), r);
        }
        return new Stats(ttlByCid, pairStats);
    }

    private static double[] fwdProfile(List<String> u, Map<String, Map<String, Object>> ttl) {
        List<Object> vals = new ArrayList<>();
        for (String c : u) {
            Object v = ttl.get(c).get("fwd_ttl");
            if (v != null) {
                vals.add(v);
            }
        }
        return vals.isEmpty() ? null
                : new double[] {Py.num(vals.get(0)).toDouble(), Py.num(vals.get(vals.size() - 1)).toDouble()};
    }

    private static double[] revProfile(List<String> u, Map<String, Map<String, Object>> ttl) {
        List<Object> vals = new ArrayList<>();
        for (String c : u) {
            Object v = ttl.get(c).get("rev_ttl");
            if (v != null) {
                vals.add(v);
            }
        }
        return vals.isEmpty() ? null
                : new double[] {Py.num(vals.get(vals.size() - 1)).toDouble(), Py.num(vals.get(0)).toDouble()};
    }

    private static String tuple(double[] t) {
        return Py.repr(PyTuple.of(new PyFloat(t[0]), new PyFloat(t[1])));
    }

    private static String fileList(List<String> u, Map<String, String> names) {
        List<Object> files = new ArrayList<>();
        for (String c : u) {
            files.add(names.get(c));
        }
        return Py.repr(files);
    }

    private static boolean differ(double[] a, double[] b) {
        return a[0] != b[0] || a[1] != b[1];
    }

    private static Comparison ambiguous(String reason) {
        return new Comparison(0, reason, List.of(), false);
    }

    /** {@code compare_units(u1, u2, ttl_by_cid, clock_domain_of, pair_stats, names)}. */
    public static Comparison compareUnits(List<String> u1, List<String> u2, Map<String, Map<String, Object>> ttlByCid,
            Map<String, String> clockDomainOf, Map<PairKey, Map<String, Object>> pairStats,
            Map<String, String> names) {
        double[] f1 = fwdProfile(u1, ttlByCid);
        double[] f2 = fwdProfile(u2, ttlByCid);
        double[] r1 = revProfile(u1, ttlByCid);
        double[] r2 = revProfile(u2, ttlByCid);
        int ttlDir = 0;
        if (f1 != null && f2 != null) {
            if (f1[0] >= f2[0] && f1[1] >= f2[1] && (f1[0] > f2[0] || f1[1] > f2[1])) {
                ttlDir = -1;
            } else if (f2[0] >= f1[0] && f2[1] >= f1[1] && (f2[0] > f1[0] || f2[1] > f1[1])) {
                ttlDir = 1;
            } else if (differ(f1, f2)) {
                return ambiguous("Ambiguous forward TTL profile between " + fileList(u1, names) + " (" + tuple(f1)
                        + ") and " + fileList(u2, names) + " (" + tuple(f2) + ").");
            }
        }
        if (r1 != null && r2 != null) {
            int revDir = 0;
            if (r1[0] <= r2[0] && r1[1] <= r2[1] && (r1[0] < r2[0] || r1[1] < r2[1])) {
                revDir = -1;
            } else if (r2[0] <= r1[0] && r2[1] <= r1[1] && (r2[0] < r1[0] || r2[1] < r1[1])) {
                revDir = 1;
            } else if (differ(r1, r2)) {
                return ambiguous("Ambiguous reverse TTL profile between " + fileList(u1, names) + " (" + tuple(r1)
                        + ") and " + fileList(u2, names) + " (" + tuple(r2) + ").");
            }
            if (ttlDir != 0 && revDir != 0 && ttlDir != revDir) {
                return ambiguous("Conflicting forward and reverse TTL order between " + fileList(u1, names) + " and "
                        + fileList(u2, names) + ".");
            }
            ttlDir = ttlDir != 0 ? ttlDir : revDir;
        }

        String aFwd = u1.get(u1.size() - 1);
        String bFwd = u2.get(0);
        String aRev = u1.get(0);
        String bRev = u2.get(u2.size() - 1);
        boolean sameClock = clockDomainOf.get(aFwd) != null && clockDomainOf.get(aFwd).equals(clockDomainOf.get(bFwd));
        Map<String, Object> fwdStat = pairStats.get(new PairKey(aFwd, bFwd, "forward"));
        Map<String, Object> revStat = pairStats.get(new PairKey(aRev, bRev, "reverse"));
        Object fUs = fwdStat != null && Py.ge(fwdStat.get("n"), Py.i(2)) ? fwdStat.get("delta_us") : null;
        Object rUs = revStat != null && Py.ge(revStat.get("n"), Py.i(2)) ? revStat.get("delta_us") : null;
        int fUsDir = (fUs != null && Math.abs(Py.num(fUs).toDouble()) >= 1.0) ? (Py.gt(fUs, Py.i(0)) ? -1 : 1) : 0;
        int rUsDir = (rUs != null && Math.abs(Py.num(rUs).toDouble()) >= 1.0) ? (Py.lt(rUs, Py.i(0)) ? -1 : 1) : 0;
        boolean strictTtlSep = (f1 != null && f2 != null && (f1[1] > f2[0] || f2[1] > f1[0]))
                || (r1 != null && r2 != null && (r1[1] < r2[0] || r2[1] < r1[0]));
        List<Map<String, Object>> refs = new ArrayList<>();
        Map<String, Object> statForRef = fwdStat != null ? fwdStat : revStat;
        if (sameClock && statForRef != null) {
            refs.add(Suggest.ref((String) statForRef.get("a"), names, statForRef.get("frame_a")));
            refs.add(Suggest.ref((String) statForRef.get("b"), names, statForRef.get("frame_b")));
        }

        boolean usConflict = false;
        int chosen;
        if (ttlDir != 0) {
            if (strictTtlSep && ((fUsDir != 0 && fUsDir != ttlDir) || (rUsDir != 0 && rUsDir != ttlDir))) {
                usConflict = true;
            }
            chosen = ttlDir;
        } else {
            int usDir = 0;
            if (sameClock && (fwdStat != null || revStat != null)) {
                if (fUsDir != 0 && rUsDir != 0 && fUsDir != rUsDir) {
                    return ambiguous("Conflicting microsecond transit order between " + fileList(u1, names) + " and "
                            + fileList(u2, names) + ".");
                }
                usDir = fUsDir != 0 ? fUsDir : rUsDir;
            }
            if (usDir == 0) {
                return ambiguous("Ambiguous path order between " + fileList(u1, names) + " and " + fileList(u2, names)
                        + ": equal TTLs and no shared-clock microsecond separation.");
            }
            chosen = usDir;
        }

        if (refs.isEmpty()) {
            String ca = chosen < 0 ? aFwd : u2.get(u2.size() - 1);
            String cb = chosen < 0 ? bFwd : u1.get(0);
            Object fa = Py.or(ttlByCid.get(ca).get("fwd_frame"), ttlByCid.get(ca).get("rev_frame"));
            Object fb = Py.or(ttlByCid.get(cb).get("fwd_frame"), ttlByCid.get(cb).get("rev_frame"));
            if (Py.truthy(fa) && Py.truthy(fb)) {
                refs.add(Suggest.ref(ca, names, fa));
                refs.add(Suggest.ref(cb, names, fb));
            }
        }
        return new Comparison(chosen, null, refs, usConflict);
    }

    private static int compareKeys(long[] a, long[] b) {
        for (int i = 0; i < a.length; i++) {
            int c = Long.compare(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** A unit index pair {@code (i, j)}. */
    public record UnitPair(int a, int b) {
    }

    /**
     * {@code isolate_conflicting_clocks}: for each unit pair whose microsecond order contradicts a strict TTL order,
     * the less trusted unit leaves its shared clock domain; returns the inferences without the shared-clock items
     * that involve a separated capture.
     */
    public static List<Object> isolateConflictingClocks(List<List<String>> units, List<UnitPair> conflicting,
            Map<Integer, Integer> wins, Map<String, String> clockDomainOf, Map<String, Point> pointsByCid,
            Map<String, String> names, List<Object> warnings, List<Object> inferences) {
        if (conflicting.isEmpty()) {
            return inferences;
        }
        Map<Integer, Integer> conflictCounts = new HashMap<>();
        Map<Integer, Integer> cleanShared = new HashMap<>();
        for (int idx = 0; idx < units.size(); idx++) {
            conflictCounts.put(idx, 0);
            cleanShared.put(idx, units.get(idx).size() > 1 ? 1 : 0);
        }
        for (UnitPair p : conflicting) {
            conflictCounts.merge(p.a, 1, Integer::sum);
            conflictCounts.merge(p.b, 1, Integer::sum);
        }
        for (int ia = 0; ia < units.size(); ia++) {
            for (int ib = ia + 1; ib < units.size(); ib++) {
                if (!conflicting.contains(new UnitPair(ia, ib))) {
                    String ca = units.get(ia).get(units.get(ia).size() - 1);
                    String cb = units.get(ib).get(0);
                    String da = clockDomainOf.get(ca);
                    if (da != null && !da.isEmpty() && da.equals(clockDomainOf.get(cb))) {
                        cleanShared.merge(ia, 1, Integer::sum);
                        cleanShared.merge(ib, 1, Integer::sum);
                    }
                }
            }
        }
        Set<Integer> separate = new HashSet<>();
        for (UnitPair p : conflicting) {
            long[] keyA = {cleanShared.get(p.a), -conflictCounts.get(p.a), units.get(p.a).size(), -wins.get(p.a)};
            long[] keyB = {cleanShared.get(p.b), -conflictCounts.get(p.b), units.get(p.b).size(), -wins.get(p.b)};
            separate.add(compareKeys(keyA, keyB) <= 0 ? p.a : p.b);
        }
        Set<String> separateCids = new HashSet<>();
        for (int idx : separate) {
            separateCids.addAll(units.get(idx));
        }
        List<Integer> ordered = new ArrayList<>(separate);
        java.util.Collections.sort(ordered);
        ordered.sort((x, y) -> Integer.compare(-wins.get(x), -wins.get(y)));
        for (int idx : ordered) {
            for (String cid : units.get(idx)) {
                clockDomainOf.remove(cid);
                pointsByCid.get(cid).clockDomain = null;
                String msg = names.get(cid) + " timestamps are inconsistent with its TTL position; "
                        + "treated as a separate clock (offset fitted)";
                if (!warnings.contains(msg)) {
                    warnings.add(msg);
                }
            }
        }
        Map<String, List<String>> domainMembers = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : new ArrayList<>(clockDomainOf.entrySet())) {
            domainMembers.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        for (List<String> members : domainMembers.values()) {
            if (members.size() < 2) {
                for (String cid : members) {
                    clockDomainOf.remove(cid);
                    pointsByCid.get(cid).clockDomain = null;
                }
            }
        }
        List<Object> out = new ArrayList<>();
        for (Object o : inferences) {
            @SuppressWarnings("unchecked")
            Map<String, Object> inf = (Map<String, Object>) o;
            boolean drop = false;
            if ("shared_clock".equals(inf.get("kind"))) {
                for (Object cid : (List<?>) inf.getOrDefault("capture_ids", List.of())) {
                    if (separateCids.contains(cid)) {
                        drop = true;
                    }
                }
            }
            if (!drop) {
                out.add(inf);
            }
        }
        return out;
    }

    /** {@code check_adjacent_mismatch_warnings}: name/direction mismatch between adjacent single captures. */
    public static void checkAdjacentMismatchWarnings(List<Integer> orderedIndices, List<List<String>> units,
            Map<PairKey, Map<String, Object>> pairStats, Map<String, String> names, List<Object> warnings) {
        for (int k = 0; k + 1 < orderedIndices.size(); k++) {
            List<String> ua = units.get(orderedIndices.get(k));
            List<String> ub = units.get(orderedIndices.get(k + 1));
            if (ua.size() == 1 && ub.size() == 1) {
                String ca = ua.get(0);
                String cb = ub.get(0);
                Map<String, Object> f = pairStats.get(new PairKey(ca, cb, "forward"));
                Map<String, Object> r = pairStats.get(new PairKey(ca, cb, "reverse"));
                if (f != null && r != null && Py.gt(f.get("ttl_step"), Py.i(0)) && Py.gt(f.get("delta_us"), Py.i(0))
                        && Py.lt(r.get("ttl_step"), Py.i(0)) && Py.lt(r.get("delta_us"), Py.i(0))) {
                    String warn = Suggest.mismatchWarning(names.get(ca), names.get(cb));
                    if (warn != null && !warnings.contains(warn)) {
                        warnings.add(warn);
                    }
                }
            }
        }
    }
}
