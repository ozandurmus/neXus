package com.securityexpert.nexus.ui2.service.policy;

import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import com.securityexpert.nexus.ui2.service.policy.PolicyRegion.Box;
import com.securityexpert.nexus.ui2.service.policy.PolicyRegion.Dim;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Design section 9, phase P2 "differential tests": the G1 engine ({@link PolicyRegion}) against a brute-force enumeration
 * over a tiny universe, for randomized small synthetic rulebases. Every dimension's universe is a few values of a few
 * families plus one value of a family no rule names (what {@code any} holds beyond every listed family). Synthetic values only.
 */
class PolicyRegionDifferentialTest {
    /** Per dimension: the families a rule may name and their values 0..size-1; "zz" is the unnamed family. */
    static final List<Map<String, Integer>> FAMILIES = List.of(Map.of("ipv4", 8), Map.of("ipv4", 8), Map.of("tcp", 8, "udp", 6), Map.of("app", 4));
    record Point(String[] families, long[] values) {}
    static final List<List<Object[]>> AXES = new ArrayList<>();
    static {
        for (var families : FAMILIES) {
            List<Object[]> axis = new ArrayList<>();
            new TreeMap<>(families).forEach((family, size) -> { for (long v = 0; v < size; v++) axis.add(new Object[]{family, v}); });
            axis.add(new Object[]{"zz", 0L});
            AXES.add(axis);
        }
    }
    static List<Point> universe() {
        List<Point> out = new ArrayList<>();
        for (var a : AXES.get(0)) for (var b : AXES.get(1)) for (var c : AXES.get(2)) for (var d : AXES.get(3))
            out.add(new Point(new String[]{(String) a[0], (String) b[0], (String) c[0], (String) d[0]}, new long[]{(long) a[1], (long) b[1], (long) c[1], (long) d[1]}));
        return out;
    }
    static boolean in(Box box, Point p) { return box.contains(List.of(p.families()), p.values()); }

    /** A random resolved selector: any, or one or two families of the dimension with one to three ranges each (merged). */
    static Selector selector(Random random, int dimension) {
        if (random.nextInt(dimension == 3 ? 2 : 5) == 0) return new Selector(true, Map.of());
        var families = new ArrayList<>(new TreeMap<>(FAMILIES.get(dimension)).keySet());
        Collections.shuffle(families, random);
        Map<String, List<Range>> ranges = new TreeMap<>();
        for (String family : families.subList(0, 1 + random.nextInt(families.size()))) {
            int size = FAMILIES.get(dimension).get(family);
            List<Range> list = new ArrayList<>();
            for (int n = 1 + random.nextInt(3); n > 0; n--) { long a = random.nextInt(size), b = a + random.nextInt(size - (int) a); list.add(new Range(a, b)); }
            ranges.put(family, PolicyRegion.union(list, List.of(), new Budget(Integer.MAX_VALUE)));
        }
        return new Selector(false, ranges);
    }
    static Box box(Random random) {
        List<Selector> selectors = new ArrayList<>();
        for (int i = 0; i < PolicyRegion.DIMENSIONS; i++) selectors.add(selector(random, i));
        return Box.of(selectors);
    }

    @Test void theResidualEqualsTheBruteForceEnumerationOnRandomRulebases() {
        var universe = universe();
        var random = new Random(20261125L);
        int covered = 0, partial = 0;
        for (int trial = 0; trial < 300; trial++) {
            Box subject = box(random);
            List<Box> earlier = new ArrayList<>();
            for (int n = 1 + random.nextInt(6); n > 0; n--) earlier.add(box(random));
            // Bias some trials towards covers: earlier boxes cut from the subject's own selectors.
            if (trial % 3 == 0) earlier.addAll(slices(subject, random));
            var result = PolicyRegion.residual(subject, earlier, new Budget(Integer.MAX_VALUE));
            assertEquals(PolicyRegion.Status.PROVEN, result.status());
            assertExact(universe, subject, earlier, result, "trial " + trial);
            if (result.covered()) covered++; else if (result.boxes().size() > 0 && !result.contributors().isEmpty()) partial++;
        }
        assertTrue(covered >= 20, "covers exercised: " + covered);
        assertTrue(partial >= 20, "partial residuals exercised: " + partial);
    }

    @Test void anExhaustedBudgetIsNotProvenNeverAWrongProven() {
        var universe = universe();
        var random = new Random(7L);
        int notProven = 0, proven = 0;
        for (int trial = 0; trial < 300; trial++) {
            Box subject = box(random);
            List<Box> earlier = new ArrayList<>(slices(subject, random));
            for (int n = random.nextInt(4); n > 0; n--) earlier.add(box(random));
            var result = PolicyRegion.residual(subject, earlier, new Budget(1 + random.nextInt(150)));
            if (result.status() == PolicyRegion.Status.NOT_PROVEN) {
                notProven++;
                assertEquals("analysis budget", result.reason());
                assertFalse(result.covered(), "a budget verdict is never a cover");
                assertTrue(result.boxes().isEmpty() && result.contributors().isEmpty());
            } else {
                proven++;
                assertExact(universe, subject, earlier, result, "trial " + trial);
            }
        }
        assertTrue(notProven >= 30, "budget exhaustion exercised: " + notProven);
        assertTrue(proven >= 30, "small budgets that suffice exercised: " + proven);
    }

    @Test void rangeOperationsHoldAtTheEndsOfTheValueSpace() {
        var budget = new Budget(Integer.MAX_VALUE);
        long min = Long.MIN_VALUE, max = Long.MAX_VALUE;
        assertEquals(List.of(new Range(min, -1), new Range(11, max)), PolicyRegion.subtract(List.of(new Range(min, max)), List.of(new Range(0, 10)), budget));
        assertEquals(List.of(), PolicyRegion.subtract(List.of(new Range(5, 6)), List.of(new Range(min, max)), budget));
        assertEquals(List.of(new Range(min + 1, max - 1)), PolicyRegion.subtract(List.of(new Range(min, max)), List.of(new Range(min, min), new Range(max, max)), budget));
        assertEquals(List.of(new Range(min, max)), PolicyRegion.union(List.of(new Range(min, 0)), List.of(new Range(1, max), new Range(max, max)), budget));
        assertEquals(List.of(new Range(3, 4), new Range(8, 9)), PolicyRegion.intersect(List.of(new Range(0, 4), new Range(8, 20)), List.of(new Range(3, 9)), budget));
        // any minus a finite set is still any outside it; a finite set minus any is empty; any is never empty.
        Dim any = Dim.ANY, some = new Dim(false, Map.of("ipv4", List.of(new Range(0, 10))));
        assertFalse(PolicyRegion.minus(any, some, budget).empty());
        assertFalse(PolicyRegion.minus(any, some, budget).contains("ipv4", 5));
        assertTrue(PolicyRegion.minus(any, some, budget).contains("ipv6", 5));
        assertTrue(PolicyRegion.minus(some, any, budget).empty());
        assertNull(Dim.of(new Selector(false, Map.of(), Set.of(PolicyNormalization.APPLICATION_DEFAULT))), "an opaque token has no box");
    }

    @Test void sourcePortFamiliesAgreeWithBruteForceEnumerationAndFailClosedOnBudgetOrMalformedKeys() {
        List<Integer> sampleSports = List.of(0, 1, 500, 1024, 2000, 65535);
        List<Long> sampleDports = List.of(1L, 2L, 3L, 99L);
        List<Object[]> serviceAxis = new ArrayList<>();
        for (String proto : List.of("tcp", "udp")) {
            for (int sp : sampleSports) {
                for (long dp : sampleDports) {
                    serviceAxis.add(new Object[]{proto + "|s=" + sp + "-" + sp, dp});
                }
            }
        }
        serviceAxis.add(new Object[]{"icmp", 1L});
        serviceAxis.add(new Object[]{"icmp", 99L});
        serviceAxis.add(new Object[]{"zz", 0L});

        List<Object[]> addrAxis = List.of(new Object[]{"ipv4", 0L}, new Object[]{"ipv4", 1L}, new Object[]{"ipv4", 99L}, new Object[]{"zz", 0L});
        List<Object[]> appAxis = List.of(new Object[]{"app", 0L}, new Object[]{"app", 99L}, new Object[]{"zz", 0L});

        List<Point> spUniverse = new ArrayList<>();
        for (var src : addrAxis) {
            for (var dst : addrAxis) {
                for (var svc : serviceAxis) {
                    for (var app : appAxis) {
                        spUniverse.add(new Point(
                            new String[]{(String) src[0], (String) dst[0], (String) svc[0], (String) app[0]},
                            new long[]{(long) src[1], (long) dst[1], (long) svc[1], (long) app[1]}));
                    }
                }
            }
        }

        Random random = new Random(20261126L);
        int covers = 0, partials = 0, overlapsDetected = 0;
        for (int trial = 0; trial < 260; trial++) {
            Box subject = sourcePortBox(random);
            List<Box> earlier = new ArrayList<>();
            for (int n = 1 + random.nextInt(5); n > 0; n--) earlier.add(sourcePortBox(random));
            if (trial % 3 == 0) {
                // Plant a complementary source-port split (0..1023 and 1024..65535) of the subject's TCP/UDP range.
                earlier.addAll(sourcePortSlices(subject));
            }
            var full = PolicyRegion.residual(subject, earlier, new Budget(Integer.MAX_VALUE));
            assertEquals(PolicyRegion.Status.PROVEN, full.status());
            assertExact(spUniverse, subject, earlier, full, "sp trial " + trial);
            if (full.covered()) covers++;
            else if (!full.contributors().isEmpty()) partials++;

            for (Box prior : earlier) {
                boolean engineIntersects = PolicyPartialShadow.intersects(subject, prior, new Budget(Integer.MAX_VALUE));
                boolean bruteIntersects = spUniverse.stream().anyMatch(p -> in(subject, p) && in(prior, p));
                assertEquals(bruteIntersects, engineIntersects, "intersects mismatch in sp trial " + trial);
                if (engineIntersects) overlapsDetected++;
            }

            var limited = PolicyRegion.residual(subject, earlier, new Budget(1 + random.nextInt(90)));
            if (limited.status() == PolicyRegion.Status.NOT_PROVEN) {
                assertEquals("analysis budget", limited.reason());
                assertFalse(limited.covered());
                assertTrue(limited.boxes().isEmpty() && limited.contributors().isEmpty());
            } else {
                assertExact(spUniverse, subject, earlier, limited, "sp limited trial " + trial);
            }
        }
        assertTrue(covers >= 25, "source-port covers exercised: " + covers);
        assertTrue(partials >= 25, "source-port partials exercised: " + partials);
        assertTrue(overlapsDetected >= 50, "source-port overlaps exercised: " + overlapsDetected);

        // Plain tcp vs tcp|s=1024-65535 MUST intersect on port 443 and split into tcp|s=0-1023 on subtraction.
        var budget = new Budget(Integer.MAX_VALUE);
        Dim plainTcp = new Dim(false, Map.of("tcp", List.of(new Range(443, 443))));
        Dim ephemeralTcp = new Dim(false, Map.of("tcp|s=1024-65535", List.of(new Range(443, 443))));
        Dim lowTcp = new Dim(false, Map.of("tcp|s=0-1023", List.of(new Range(443, 443))));
        assertFalse(PolicyRegion.intersect(plainTcp, ephemeralTcp, budget).empty());
        assertEquals(lowTcp, PolicyRegion.minus(plainTcp, ephemeralTcp, budget));
        assertTrue(PolicyRegion.minus(PolicyRegion.minus(plainTcp, ephemeralTcp, budget), lowTcp, budget).empty());

        // Malformed source-port family keys fail closed to null Dim.
        for (String bad : List.of("tcp|s=", "icmp|s=1-2", "tcp|s=70000-70001", "tcp|s=20-10", "tcp|s=1-10,10-20", "tcp|s=abc")) {
            assertNull(Dim.of(new Selector(false, Map.of(bad, List.of(new Range(80, 80))))), bad);
        }
    }

    private static Box sourcePortBox(Random random) {
        Selector src = random.nextInt(5) == 0 ? new Selector(true, Map.of())
            : new Selector(false, Map.of("ipv4", List.of(new Range(random.nextInt(2), 1))));
        Selector dst = random.nextInt(5) == 0 ? new Selector(true, Map.of())
            : new Selector(false, Map.of("ipv4", List.of(new Range(0, random.nextInt(2)))));
        Selector svc;
        if (random.nextInt(6) == 0) {
            svc = new Selector(true, Map.of());
        } else {
            List<List<Range>> spChoices = List.of(
                List.of(new Range(0, 65535)),
                List.of(new Range(0, 1023)),
                List.of(new Range(1024, 65535)),
                List.of(new Range(1, 2000)),
                List.of(new Range(0, 1), new Range(2000, 65535)));
            Map<String, List<Range>> map = new TreeMap<>();
            for (int k = 1 + random.nextInt(3); k > 0; k--) {
                String proto = random.nextInt(3) == 0 ? "udp" : "tcp";
                List<Range> sp = spChoices.get(random.nextInt(spChoices.size()));
                String key = sp.size() == 1 && sp.get(0).start() == 0 && sp.get(0).end() == 65535
                    ? proto : PolicyNormalization.sourceFamily(proto, sp);
                long d1 = 1 + random.nextInt(3), d2 = d1 + random.nextInt(4 - (int) d1);
                map.merge(key, List.of(new Range(d1, d2)), (a, b) -> PolicyRegion.union(a, b, new Budget(Integer.MAX_VALUE)));
            }
            if (random.nextInt(8) == 0) map.put("icmp", List.of(new Range(1, 1)));
            svc = new Selector(false, map);
        }
        Selector app = random.nextBoolean() ? new Selector(true, Map.of())
            : new Selector(false, Map.of("app", List.of(new Range(0, 0))));
        return Box.of(List.of(src, dst, svc, app));
    }

    private static List<Box> sourcePortSlices(Box subject) {
        Dim d = subject.dims().get(2);
        if (d.complement()) return List.of();
        List<Box> out = new ArrayList<>();
        for (String proto : List.of("tcp", "udp")) {
            List<Range> plain = d.ranges().get(proto);
            if (plain == null) continue;
            for (String half : List.of(proto + "|s=0-1023", proto + "|s=1024-65535")) {
                List<Dim> dims = new ArrayList<>(subject.dims());
                dims.set(2, new Dim(false, Map.of(half, plain)));
                out.add(new Box(dims));
            }
        }
        return out;
    }

    /** Two to four boxes that split the subject on one dimension (plus noise), so their union often covers it. */
    static List<Box> slices(Box subject, Random random) {
        int axis = random.nextInt(PolicyRegion.DIMENSIONS);
        Dim d = subject.dims().get(axis);
        List<Box> out = new ArrayList<>();
        if (d.complement()) {
            // An any dimension is covered only by any: one slice keeps it, the rest of the subject is cut on another axis.
            out.add(subject);
            return out;
        }
        for (var e : d.ranges().entrySet()) for (Range r : e.getValue()) {
            long mid = r.start() + (r.end() - r.start()) / 2;
            for (Range part : List.of(new Range(r.start(), mid), new Range(Math.min(mid + 1, r.end()), r.end()))) {
                List<Dim> dims = new ArrayList<>(subject.dims());
                dims.set(axis, new Dim(false, Map.of(e.getKey(), List.of(part))));
                if (random.nextInt(4) > 0) out.add(new Box(dims));
            }
        }
        return out;
    }

    static void assertExact(List<Point> universe, Box subject, List<Box> earlier, PolicyRegion.Residual result, String where) {
        boolean[] contributes = new boolean[earlier.size()];
        for (Point p : universe) {
            boolean inSubject = in(subject, p);
            int first = -1;
            for (int k = 0; k < earlier.size() && first < 0; k++) if (in(earlier.get(k), p)) first = k;
            int hits = 0;
            for (Box b : result.boxes()) if (in(b, p)) hits++;
            assertTrue(hits <= 1, where + ": residual boxes overlap");
            assertEquals(inSubject && first < 0, hits == 1, where + ": residual membership at " + Arrays.toString(p.families()) + Arrays.toString(p.values()));
            if (inSubject && first >= 0) contributes[first] = true;
        }
        List<Integer> expected = new ArrayList<>();
        for (int k = 0; k < contributes.length; k++) if (contributes[k]) expected.add(k);
        assertEquals(expected, result.contributors(), where + ": contributors");
        assertEquals(result.boxes().isEmpty(), result.covered(), where);
    }
}
