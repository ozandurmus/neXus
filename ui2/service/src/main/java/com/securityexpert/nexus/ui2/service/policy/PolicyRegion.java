package com.securityexpert.nexus.ui2.service.policy;

import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import java.util.*;

/**
 * G1 of POLICY_INSIGHTS_DESIGN_2026_11_24.md (FROZEN), decided by the PO decision record of the same date, Q8:
 * interval-box arithmetic with a work budget; exhausting the budget is NOT_PROVEN, never a guess. Pure: no I/O, no
 * snapshot, no vendor knowledge -- the caller turns a resolved {@link Match} into a {@link Box} and decides which earlier
 * boxes may be subtracted (same evaluation scope, compatible placement; see {@link PolicyUnionCover}).
 *
 * <p>A box is the product of the four selectors the shadow engine already resolves (source, destination, service,
 * application), exactly as {@link PolicyNormalization#contains} reads them. One dimension ({@link Dim}) is either a
 * finite union of inclusive ranges per family, or everything except such a union ({@code complement}); a selector of
 * {@code any} is the complement of nothing. Families are opaque keys compared by equality ({@code ipv4}, {@code tcp},
 * {@code udp}, {@code icmp}, a source-port family): two distinct keys are treated as two disjoint value sets, and the
 * universe holds every family, including families no rule names. That reading never makes a region smaller than it is:
 * where two keys could share values (a source-port family beside a plain family of the same protocol) a cover is simply
 * not found. A complement dimension is never empty for the same reason. Opaque tokens (Palo Alto application-default and
 * application identities) have no box: {@link Dim#of} answers null and the caller reports the rule NOT_EVALUABLE.
 *
 * <p>{@link #residual} computes the effective region of design gap G1, "match(r) minus the union of earlier matches", as
 * disjoint boxes, and which earlier boxes removed something from it. Every step spends one {@link Budget} unit; running
 * out answers {@link Status#NOT_PROVEN} with reason "analysis budget". A {@link Status#PROVEN} residual is exact.
 */
final class PolicyRegion {
    private PolicyRegion() {}
    static final int DIMENSIONS = 4;
    private static final long MIN = Long.MIN_VALUE, MAX = Long.MAX_VALUE;

    /** One dimension: {@code complement == false}: the union of {@code ranges}; true: every value of every family except them. */
    record Dim(boolean complement, Map<String, List<Range>> ranges) {
        static final Dim ANY = new Dim(true, Map.of());
        Dim {
            Map<String, List<Range>> copy = new TreeMap<>();
            ranges.forEach((family, list) -> { if (!list.isEmpty()) copy.put(family, List.copyOf(list)); });
            ranges = Collections.unmodifiableMap(copy);
        }
        /** The dimension of a resolved selector, or null when it carries opaque tokens or an invalid source-port family. */
        static Dim of(Selector selector) {
            if (!selector.tokens().isEmpty() || !validSourceFamilies(selector.ranges())) return null;
            return selector.any() ? ANY : new Dim(false, selector.ranges());
        }
        boolean empty() { return !complement && ranges.isEmpty(); }
        /** Whether one value of one family (or source-port family {@code tcp|s=...}/{@code udp|s=...}) lies in this dimension. */
        boolean contains(String family, long value) {
            String proto = protocol(family);
            if (!family.contains("|s=") && !hasSourcePortKey(ranges, proto)) {
                return inRanges(ranges.getOrDefault(family, List.of()), value) != complement;
            }
            List<Range> querySources = sources(family);
            List<Map.Entry<List<Range>, List<Range>>> protoEntries = protocolEntries(ranges, proto);
            if (querySources.size() == 1 && querySources.get(0).start() == querySources.get(0).end()) {
                long s = querySources.get(0).start();
                boolean in = false;
                for (var entry : protoEntries) {
                    if (inRanges(entry.getKey(), s) && inRanges(entry.getValue(), value)) { in = true; break; }
                }
                return in != complement;
            }
            Budget check = new Budget(Integer.MAX_VALUE);
            TreeSet<Long> cuts = new TreeSet<>();
            for (Range r : querySources) { cuts.add(r.start()); cuts.add(r.end() + 1); }
            for (var entry : protoEntries) for (Range r : entry.getKey()) { cuts.add(r.start()); cuts.add(r.end() + 1); }
            for (Range r : querySources) {
                for (Long from : cuts.subSet(r.start(), true, r.end(), true)) {
                    Long next = cuts.higher(from);
                    long to = Math.min(r.end(), next == null ? r.end() : next - 1);
                    List<Range> slice = sliceDestinations(protoEntries, from, to, check);
                    if (inRanges(slice, value) == complement) return false;
                }
            }
            return true;
        }
        /** The lowest and highest value over every family; [MIN, MAX] for a complement. Disjoint spans prove disjoint dimensions. */
        long[] span() {
            if (complement) return new long[]{MIN, MAX};
            long lo = MAX, hi = MIN;
            for (var list : ranges.values()) { lo = Math.min(lo, list.get(0).start()); hi = Math.max(hi, list.get(list.size() - 1).end()); }
            return new long[]{lo, hi};
        }
    }

    /** A product of {@link #DIMENSIONS} dimensions. */
    record Box(List<Dim> dims) {
        Box { if (dims.size() != DIMENSIONS) throw new IllegalArgumentException("A box has four dimensions"); dims = List.copyOf(dims); }
        /** The box of a resolved match, or null when a selector carries opaque tokens. */
        static Box of(List<Selector> selectors) {
            List<Dim> dims = new ArrayList<>(DIMENSIONS);
            for (Selector s : selectors) { Dim d = Dim.of(s); if (d == null) return null; dims.add(d); }
            return dims.size() == DIMENSIONS ? new Box(dims) : null;
        }
        boolean contains(List<String> families, long[] values) {
            for (int i = 0; i < DIMENSIONS; i++) if (!dims.get(i).contains(families.get(i), values[i])) return false;
            return true;
        }
        /** Per dimension [lo, hi] spans, packed; see {@link Dim#span}. */
        long[] spans() {
            long[] out = new long[2 * DIMENSIONS];
            for (int i = 0; i < DIMENSIONS; i++) { long[] s = dims.get(i).span(); out[2 * i] = s[0]; out[2 * i + 1] = s[1]; }
            return out;
        }
    }

    /** PROVEN: the residual is exact. NOT_PROVEN: the budget ran out ({@code reason} "analysis budget"). */
    enum Status { PROVEN, NOT_PROVEN }
    /**
     * The effective region of a subject: {@code boxes} (disjoint) is what no earlier box takes, {@code contributors} the
     * indexes (into the earlier list) of the boxes that removed a non-empty part, in list order. Empty for NOT_PROVEN.
     */
    record Residual(Status status, String reason, List<Box> boxes, List<Integer> contributors) {
        /** Proven, and nothing of the subject is left: the earlier boxes together hold all of it. */
        boolean covered() { return status == Status.PROVEN && boxes.isEmpty(); }
    }

    /** {@code subject} minus the union of {@code earlier}, every step paid from {@code budget}. */
    static Residual residual(Box subject, List<Box> earlier, Budget budget) {
        try {
            List<Box> residual = List.of(subject);
            List<Integer> contributors = new ArrayList<>();
            for (int k = 0; k < earlier.size() && !residual.isEmpty(); k++) {
                Box b = earlier.get(k);
                List<Box> next = new ArrayList<>();
                boolean took = false;
                for (Box r : residual) {
                    budget.spend();
                    List<Box> pieces = minus(r, b, budget);
                    if (pieces == null) next.add(r); else { took = true; next.addAll(pieces); }
                }
                if (took) contributors.add(k);
                residual = next;
            }
            return new Residual(Status.PROVEN, null, List.copyOf(residual), List.copyOf(contributors));
        } catch (Unknown limit) {
            return new Residual(Status.NOT_PROVEN, limit.getMessage(), List.of(), List.of());
        }
    }

    /**
     * {@code a} minus {@code b} as at most four disjoint boxes, or null when the two are disjoint (nothing taken). The
     * classic split: for each dimension in turn, the part of the current box outside {@code b} on that dimension is a
     * piece, and the current box narrows to the intersection there.
     */
    static List<Box> minus(Box a, Box b, Budget budget) {
        List<Dim> inter = new ArrayList<>(DIMENSIONS);
        for (int i = 0; i < DIMENSIONS; i++) {
            Dim d = intersect(a.dims().get(i), b.dims().get(i), budget);
            if (d.empty()) return null;
            inter.add(d);
        }
        List<Box> pieces = new ArrayList<>(DIMENSIONS);
        List<Dim> current = new ArrayList<>(a.dims());
        for (int i = 0; i < DIMENSIONS; i++) {
            Dim outside = minus(current.get(i), b.dims().get(i), budget);
            if (!outside.empty()) { List<Dim> piece = new ArrayList<>(current); piece.set(i, outside); pieces.add(new Box(piece)); }
            current.set(i, inter.get(i));
        }
        return pieces;
    }

    static Dim intersect(Dim a, Dim b, Budget budget) {
        if (hasSourcePortFamily(a.ranges(), b.ranges())) return intersectWithSourcePorts(a, b, budget);
        if (!a.complement() && !b.complement()) return new Dim(false, perFamily(a.ranges(), b.ranges(), true, budget));
        if (!a.complement()) return new Dim(false, perFamily(a.ranges(), b.ranges(), false, budget));
        if (!b.complement()) return new Dim(false, perFamily(b.ranges(), a.ranges(), false, budget));
        return new Dim(true, union(a.ranges(), b.ranges(), budget));
    }
    static Dim minus(Dim a, Dim b, Budget budget) {
        // a minus (everything except X) = a intersect X; a minus X = a intersect (everything except X).
        return intersect(a, new Dim(!b.complement(), b.ranges()), budget);
    }

    private static boolean validSourceFamilies(Map<String, List<Range>> ranges) {
        for (String family : ranges.keySet()) {
            int at = family.indexOf("|s=");
            if (at < 0) continue;
            String proto = family.substring(0, at);
            if (!"tcp".equals(proto) && !"udp".equals(proto)) return false;
            String spec = family.substring(at + 3);
            if (spec.isEmpty()) return false;
            long prevEnd = -1;
            for (String part : spec.split(",", -1)) {
                String[] ends = part.split("-", -1);
                if (ends.length != 2) return false;
                try {
                    long s = Long.parseLong(ends[0]), e = Long.parseLong(ends[1]);
                    if (s < 0 || e > 65535 || s > e || s <= prevEnd) return false;
                    prevEnd = e;
                } catch (NumberFormatException invalid) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasSourcePortFamily(Map<String, List<Range>> a, Map<String, List<Range>> b) {
        for (String k : a.keySet()) if (k.contains("|s=")) return true;
        for (String k : b.keySet()) if (k.contains("|s=")) return true;
        return false;
    }

    private static boolean hasSourcePortKey(Map<String, List<Range>> ranges, String proto) {
        for (String k : ranges.keySet()) if (k.contains("|s=") && protocol(k).equals(proto)) return true;
        return false;
    }

    private static boolean inRanges(List<Range> ranges, long value) {
        for (Range r : ranges) if (r.start() <= value && value <= r.end()) return true;
        return false;
    }

    private static List<Map.Entry<List<Range>, List<Range>>> protocolEntries(Map<String, List<Range>> ranges, String proto) {
        List<Map.Entry<List<Range>, List<Range>>> out = new ArrayList<>();
        for (var e : ranges.entrySet()) {
            if (protocol(e.getKey()).equals(proto)) out.add(Map.entry(sources(e.getKey()), e.getValue()));
        }
        return out;
    }

    private static List<Range> sliceDestinations(List<Map.Entry<List<Range>, List<Range>>> entries, long from, long to, Budget budget) {
        List<Range> single = null;
        List<Range> combined = null;
        for (var e : entries) {
            boolean holds = false;
            for (Range s : e.getKey()) {
                budget.spend();
                if (s.start() <= from && s.end() >= to) { holds = true; break; }
            }
            if (!holds) continue;
            if (single == null && combined == null) single = e.getValue();
            else {
                if (combined == null) { combined = new ArrayList<>(single); single = null; }
                combined.addAll(e.getValue());
            }
        }
        if (single != null) return single;
        if (combined == null) return List.of();
        return union(combined, List.of(), budget);
    }

    /**
     * Exact intersection of two dimensions when {@code tcp} or {@code udp} carries source-port families ({@code tcp|s=...}):
     * partitions {@code [0, 65535]} at every source-port boundary so plain and source-port-restricted families of the same
     * protocol share their overlapping {@code (sourcePort, destPort)} points instead of acting as disjoint family keys.
     */
    private static Dim intersectWithSourcePorts(Dim a, Dim b, Budget budget) {
        Set<String> sourceProtos = new TreeSet<>();
        for (String k : a.ranges().keySet()) if (k.contains("|s=")) sourceProtos.add(protocol(k));
        for (String k : b.ranges().keySet()) if (k.contains("|s=")) sourceProtos.add(protocol(k));

        Map<String, List<Range>> aPlain = new TreeMap<>(), bPlain = new TreeMap<>();
        a.ranges().forEach((k, v) -> { if (!sourceProtos.contains(protocol(k))) aPlain.put(k, v); });
        b.ranges().forEach((k, v) -> { if (!sourceProtos.contains(protocol(k))) bPlain.put(k, v); });

        Map<String, List<Range>> out;
        if (!a.complement() && !b.complement()) out = new TreeMap<>(perFamily(aPlain, bPlain, true, budget));
        else if (!a.complement()) out = new TreeMap<>(perFamily(aPlain, bPlain, false, budget));
        else if (!b.complement()) out = new TreeMap<>(perFamily(bPlain, aPlain, false, budget));
        else out = new TreeMap<>(union(aPlain, bPlain, budget));

        for (String proto : sourceProtos) {
            var aEntries = protocolEntries(a.ranges(), proto);
            var bEntries = protocolEntries(b.ranges(), proto);
            TreeSet<Long> cuts = new TreeSet<>();
            cuts.add(0L);
            cuts.add(65536L);
            for (var e : aEntries) for (Range r : e.getKey()) {
                budget.spend();
                cuts.add(Math.max(0L, r.start()));
                cuts.add(Math.min(65536L, r.end() + 1));
            }
            for (var e : bEntries) for (Range r : e.getKey()) {
                budget.spend();
                cuts.add(Math.max(0L, r.start()));
                cuts.add(Math.min(65536L, r.end() + 1));
            }
            Map<List<Range>, List<Range>> byDest = new LinkedHashMap<>();
            Long prev = null;
            for (Long point : cuts) {
                if (prev != null && prev < point) {
                    budget.spend();
                    long from = prev, to = point - 1;
                    List<Range> da = sliceDestinations(aEntries, from, to, budget);
                    List<Range> db = sliceDestinations(bEntries, from, to, budget);
                    List<Range> res;
                    if (!a.complement() && !b.complement()) {
                        res = da.isEmpty() || db.isEmpty() ? List.of() : intersect(da, db, budget);
                    } else if (!a.complement()) {
                        res = da.isEmpty() ? List.of() : db.isEmpty() ? da : subtract(da, db, budget);
                    } else if (!b.complement()) {
                        res = db.isEmpty() ? List.of() : da.isEmpty() ? db : subtract(db, da, budget);
                    } else {
                        res = da.isEmpty() ? db : db.isEmpty() ? da : union(da, db, budget);
                    }
                    if (!res.isEmpty()) {
                        List<Range> srcs = byDest.computeIfAbsent(res, d -> new ArrayList<>());
                        if (!srcs.isEmpty() && srcs.get(srcs.size() - 1).end() + 1 == from) {
                            Range last = srcs.get(srcs.size() - 1);
                            srcs.set(srcs.size() - 1, new Range(last.start(), to));
                        } else {
                            srcs.add(new Range(from, to));
                        }
                    }
                }
                prev = point;
            }
            for (var entry : byDest.entrySet()) {
                budget.spend();
                List<Range> srcs = entry.getValue();
                boolean every = srcs.size() == 1 && srcs.get(0).start() == 0 && srcs.get(0).end() == 65535;
                out.put(every ? proto : sourceFamily(proto, srcs), entry.getKey());
            }
        }
        return new Dim(a.complement() && b.complement(), out);
    }

    /** Per family of {@code a}: {@code a} intersect {@code b} ({@code intersect}) or {@code a} minus {@code b}. */
    private static Map<String, List<Range>> perFamily(Map<String, List<Range>> a, Map<String, List<Range>> b, boolean intersect, Budget budget) {
        Map<String, List<Range>> out = new TreeMap<>();
        for (var e : a.entrySet()) {
            budget.spend();
            var other = b.getOrDefault(e.getKey(), List.of());
            var r = intersect ? intersect(e.getValue(), other, budget) : subtract(e.getValue(), other, budget);
            if (!r.isEmpty()) out.put(e.getKey(), r);
        }
        return out;
    }
    private static Map<String, List<Range>> union(Map<String, List<Range>> a, Map<String, List<Range>> b, Budget budget) {
        Map<String, List<Range>> out = new TreeMap<>(a);
        for (var e : b.entrySet()) { budget.spend(); out.merge(e.getKey(), e.getValue(), (x, y) -> union(x, y, budget)); }
        return out;
    }

    // ---- sorted, merged lists of inclusive ranges ----
    static List<Range> intersect(List<Range> a, List<Range> b, Budget budget) {
        List<Range> out = new ArrayList<>();
        for (int i = 0, j = 0; i < a.size() && j < b.size(); ) {
            budget.spend();
            Range x = a.get(i), y = b.get(j);
            long lo = Math.max(x.start(), y.start()), hi = Math.min(x.end(), y.end());
            if (lo <= hi) out.add(new Range(lo, hi));
            if (x.end() < y.end()) i++; else j++;
        }
        return out;
    }
    static List<Range> subtract(List<Range> a, List<Range> b, Budget budget) {
        List<Range> out = new ArrayList<>();
        int j = 0;
        for (Range x : a) {
            long start = x.start(); boolean done = false;
            while (j < b.size() && b.get(j).end() < start) { budget.spend(); j++; }
            for (int k = j; k < b.size() && b.get(k).start() <= x.end(); k++) {
                budget.spend();
                Range y = b.get(k);
                if (y.start() > start) out.add(new Range(start, y.start() - 1));
                if (y.end() >= x.end()) { done = true; break; }
                start = y.end() + 1;
            }
            budget.spend();
            if (!done) out.add(new Range(start, x.end()));
        }
        return out;
    }
    static List<Range> union(List<Range> a, List<Range> b, Budget budget) {
        List<Range> all = new ArrayList<>(a.size() + b.size()); all.addAll(a); all.addAll(b);
        all.sort(Comparator.comparingLong(Range::start));
        List<Range> out = new ArrayList<>();
        for (Range r : all) {
            budget.spend();
            if (!out.isEmpty()) {
                Range last = out.get(out.size() - 1);
                if (last.end() == MAX || last.end() + 1 >= r.start()) { out.set(out.size() - 1, new Range(last.start(), Math.max(last.end(), r.end()))); continue; }
            }
            out.add(r);
        }
        return out;
    }
}
