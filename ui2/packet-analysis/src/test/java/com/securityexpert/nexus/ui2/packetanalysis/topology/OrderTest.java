package com.securityexpert.nexus.ui2.packetanalysis.topology;

import static com.securityexpert.nexus.ui2.packetanalysis.topology.Py.dict;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TTL / microsecond unit ordering and clock isolation on small inline statistics. */
class OrderTest {

    private final Map<String, Map<String, Object>> ttl = new HashMap<>();
    private final Map<Order.PairKey, Map<String, Object>> pairs = new HashMap<>();
    private final Map<String, String> domains = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();

    private void capture(String cid, Double fwd, Double rev, long frame) {
        ttl.put(cid, dict("capture_id", cid, "fwd_ttl", fwd == null ? null : Py.f(fwd),
                "rev_ttl", rev == null ? null : Py.f(rev), "fwd_n", Py.i(4), "rev_n", Py.i(4),
                "fwd_frame", Py.i(frame), "rev_frame", Py.i(frame + 1)));
        names.put(cid, cid + ".pcap");
    }

    private void pair(String a, String b, String dir, double deltaUs, double ttlStep) {
        pairs.put(new Order.PairKey(a, b, dir), dict("a", a, "b", b, "dir", dir, "n", Py.i(5),
                "delta_us", Py.f(deltaUs), "spread_us", Py.f(1.0), "ttl_step", Py.f(ttlStep),
                "frame_a", Py.i(10), "frame_b", Py.i(20)));
    }

    private Order.Comparison cmp(List<String> u1, List<String> u2) {
        return Order.compareUnits(u1, u2, ttl, domains, pairs, names);
    }

    @Test
    void ttlStepOrdersUnitsAndFallsBackToFrameEvidence() {
        capture("a", 64.0, 62.0, 1);
        capture("b", 63.0, 63.0, 7);
        Order.Comparison c = cmp(List.of("a"), List.of("b"));
        assertEquals(-1, c.order());
        assertNull(c.error());
        assertEquals(List.of("a", "b"), List.of(c.refs().get(0).get("capture_id"), c.refs().get(1).get("capture_id")));
        assertEquals("frame.number == 7", c.refs().get(1).get("display_filter"));
        assertEquals(1, cmp(List.of("b"), List.of("a")).order());
    }

    @Test
    void conflictingForwardAndReverseTtlIsAmbiguous() {
        capture("a", 64.0, 64.0, 1);
        capture("b", 63.0, 63.0, 1);
        Order.Comparison c = cmp(List.of("a"), List.of("b"));
        assertEquals(0, c.order());
        assertEquals("Conflicting forward and reverse TTL order between ['a.pcap'] and ['b.pcap'].", c.error());
    }

    @Test
    void crossingForwardProfilesAreAmbiguous() {
        capture("a1", 64.0, null, 1);
        capture("a2", 60.0, null, 1);
        capture("b", 62.0, null, 1);
        Order.Comparison c = cmp(List.of("a1", "a2"), List.of("b"));
        assertEquals("Ambiguous forward TTL profile between ['a1.pcap', 'a2.pcap'] ((64.0, 60.0)) and ['b.pcap'] "
                + "((62.0, 62.0)).", c.error());
    }

    @Test
    void equalTtlsWithoutSharedClockAreNeverGuessed() {
        capture("tap_1", 60.0, 60.0, 1);
        capture("tap_2", 60.0, 60.0, 1);
        pair("tap_1", "tap_2", "forward", 2_500_000.0, 0.0);
        pair("tap_1", "tap_2", "reverse", 2_500_000.0, 0.0);
        Order.Comparison c = cmp(List.of("tap_1"), List.of("tap_2"));
        assertEquals(0, c.order());
        assertEquals("Ambiguous path order between ['tap_1.pcap'] and ['tap_2.pcap']: equal TTLs and no shared-clock "
                + "microsecond separation.", c.error());
    }

    @Test
    void sharedClockMicrosecondsOrderEqualTtls() {
        capture("a", 60.0, 60.0, 1);
        capture("b", 60.0, 60.0, 1);
        domains.put("a", "appliance");
        domains.put("b", "appliance");
        pair("a", "b", "forward", 20.0, 0.0);
        pair("a", "b", "reverse", -20.0, 0.0);
        Order.Comparison c = cmp(List.of("a"), List.of("b"));
        assertEquals(-1, c.order());
        assertEquals(Py.i(10), c.refs().get(0).get("frame"), "shared-clock evidence comes from the pair statistic");

        pair("a", "b", "reverse", 20.0, 0.0);
        assertEquals("Conflicting microsecond transit order between ['a.pcap'] and ['b.pcap'].",
                cmp(List.of("a"), List.of("b")).error());

        pair("a", "b", "forward", 0.5, 0.0);
        pair("a", "b", "reverse", -0.5, 0.0);
        assertEquals(0, cmp(List.of("a"), List.of("b")).order(), "sub-microsecond deltas do not separate");
    }

    @Test
    void timestampsAgainstAStrictTtlOrderFlagAClockConflict() {
        capture("a", 64.0, 62.0, 1);
        capture("b", 63.0, 63.0, 1);
        domains.put("a", "appliance");
        domains.put("b", "appliance");
        pair("a", "b", "forward", -80.0, 1.0);
        Order.Comparison c = cmp(List.of("a"), List.of("b"));
        assertEquals(-1, c.order(), "TTL wins");
        assertTrue(c.usConflict());
        pair("a", "b", "forward", 30.0, 1.0);
        assertFalse(cmp(List.of("a"), List.of("b")).usConflict());
    }

    @Test
    void isolatingAConflictingClockSeparatesTheLessSupportedUnit() {
        for (String c : List.of("a", "b", "c")) {
            domains.put(c, "appliance");
            names.put(c, c + ".pcap");
        }
        Map<String, Point> points = new LinkedHashMap<>();
        for (String c : List.of("a", "b", "c")) {
            Point p = Point.of(c, c, c, c, Py.i(0), Py.i(0));
            p.clockDomain = "appliance";
            points.put(c, p);
        }
        List<List<String>> units = List.of(List.of("a"), List.of("b"), List.of("c"));
        Map<Integer, Integer> wins = Map.of(0, 2, 1, 1, 2, 0);
        List<Object> warnings = new ArrayList<>();
        List<Object> inferences = new ArrayList<>(List.of(
                dict("kind", "shared_clock", "capture_ids", List.of("a", "b")),
                dict("kind", "shared_clock", "capture_ids", List.of("b", "c")),
                dict("kind", "client_cidrs", "capture_ids", List.of("a"))));
        List<Object> kept = Order.isolateConflictingClocks(units, List.of(new Order.UnitPair(0, 1)), wins, domains,
                points, names, warnings, inferences);
        assertNull(points.get("a").clockDomain);
        assertEquals("appliance", points.get("b").clockDomain);
        assertEquals(List.of("a.pcap timestamps are inconsistent with its TTL position; treated as a separate clock "
                + "(offset fitted)"), warnings);
        assertEquals(2, kept.size());
        assertEquals(List.of("b", "c"), ((Map<?, ?>) kept.get(0)).get("capture_ids"));
        assertEquals(List.of(), Order.isolateConflictingClocks(units, List.of(), wins, domains, points, names,
                warnings, List.of()), "no conflict: inferences unchanged");
    }

    @Test
    void aDomainLeftWithOneMemberIsDissolved() {
        domains.put("a", "appliance");
        domains.put("b", "appliance");
        names.put("a", "a.pcap");
        names.put("b", "b.pcap");
        Map<String, Point> points = new LinkedHashMap<>();
        for (String c : List.of("a", "b")) {
            Point p = Point.of(c, c, c, c, Py.i(0), Py.i(0));
            p.clockDomain = "appliance";
            points.put(c, p);
        }
        Order.isolateConflictingClocks(List.of(List.of("a"), List.of("b")), List.of(new Order.UnitPair(0, 1)),
                Map.of(0, 1, 1, 0), domains, points, names, new ArrayList<>(), new ArrayList<>());
        assertTrue(domains.isEmpty());
        assertNull(points.get("b").clockDomain);
    }

    @Test
    void adjacentMismatchWarningNamesTheInterfaceWords() {
        names.put("x", "ark_fw_out.pcap");
        names.put("y", "ark_fw_in.pcap");
        pair("x", "y", "forward", 20.0, 1.0);
        pair("x", "y", "reverse", -20.0, -1.0);
        List<Object> warnings = new ArrayList<>();
        Order.checkAdjacentMismatchWarnings(List.of(0, 1), List.of(List.of("x"), List.of("y")), pairs, names, warnings);
        assertEquals(List.of("File names suggest ark_fw_in.pcap -> ark_fw_out.pcap, packets show ark_fw_out.pcap -> "
                + "ark_fw_in.pcap (names may describe interfaces, not direction)"), warnings);
        names.put("y", "fw_b.pcap");
        List<Object> none = new ArrayList<>();
        Order.checkAdjacentMismatchWarnings(List.of(0, 1), List.of(List.of("x"), List.of("y")), pairs, names, none);
        assertTrue(none.isEmpty(), "only an ingress-named second capture is a mismatch");
    }
}
