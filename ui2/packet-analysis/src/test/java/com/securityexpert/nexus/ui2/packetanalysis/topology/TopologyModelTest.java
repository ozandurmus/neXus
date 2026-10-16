package com.securityexpert.nexus.ui2.packetanalysis.topology;

import static com.securityexpert.nexus.ui2.packetanalysis.topology.Py.dict;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Model validation and dump semantics of the topology records (reference test_pilot / test_analysis cases). */
@SuppressWarnings("unchecked")
class TopologyModelTest {

    private static Map<String, Object> stageTopology() {
        List<Object> points = new ArrayList<>();
        String[][] defs = {{"a_rx", "A", "ingress"}, {"a_tx", "A", "egress"}, {"b_rx", "B", "ingress"},
            {"b_tx", "B", "egress"}, {"mirror", "Switch", "both"}, {"host", "Server", "both"}};
        List<Object> forward = new ArrayList<>();
        for (String[] d : defs) {
            points.add(dict("id", d[0], "label", d[0], "device", d[1], "side", d[2], "capture_id", d[0],
                    "stage_capture", !d[2].equals("both")));
            forward.add(d[0]);
        }
        return dict("points", points, "forward", forward);
    }

    @Test
    void stageOrderIsRxThenTxInBothDirections() {
        Topology t = Topology.validate(stageTopology());
        assertEquals(List.of("a_rx", "a_tx", "b_rx", "b_tx", "mirror", "host"), t.forward);
        assertEquals(List.of("host", "mirror", "b_rx", "b_tx", "a_rx", "a_tx"), t.reverse);
    }

    @Test
    void stageDeclarationIsPerDeviceAndWireOrderIsUnchanged() {
        Map<String, Object> wire = stageTopology();
        for (Object p : (List<?>) wire.get("points")) {
            ((Map<String, Object>) p).put("stage_capture", false);
        }
        Topology t = Topology.validate(wire);
        assertTrue(t.reverse.isEmpty(), "no stage device: reverse stays empty (path() mirrors forward)");
        ((Map<String, Object>) ((List<?>) wire.get("points")).get(0)).put("stage_capture", true);
        t = Topology.validate(wire);
        assertTrue(t.points.get(1).stageCapture, "the other point of device A becomes a stage capture");
        assertEquals(List.of("a_rx", "a_tx"), t.reverse.subList(4, 6));
    }

    @Test
    void stageCaptureNeedsADirectionalSide() {
        Map<String, Object> t = stageTopology();
        ((Map<String, Object>) ((List<?>) t.get("points")).get(1)).put("side", "both");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Topology.validate(t));
        assertTrue(e.getMessage().contains("Stage captures require rx/ingress or tx/egress side"));
    }

    @Test
    void declaredClockDomainCannotConflictForOneFile() {
        Map<String, Object> t = stageTopology();
        List<?> pts = (List<?>) t.get("points");
        ((Map<String, Object>) pts.get(0)).put("clock_domain", "x");
        ((Map<String, Object>) pts.get(1)).put("capture_id", "a_rx");
        ((Map<String, Object>) pts.get(1)).put("clock_domain", "y");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Topology.validate(t)).getMessage()
                .contains("conflicting clock domains"));

        Map<String, Object> t2 = stageTopology();
        List<?> pts2 = (List<?>) t2.get("points");
        ((Map<String, Object>) pts2.get(0)).put("clock_domain", "appliance");
        ((Map<String, Object>) pts2.get(1)).put("clock_domain", "appliance");
        t2.put("clock_overrides", dict("a_rx", dict("offset_ms", Py.i(0)), "a_tx", dict("offset_ms", Py.i(10))));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Topology.validate(t2)).getMessage()
                .contains("conflicting clock corrections"));

        // equal corrections (0 and 0.0) are one correction
        t2.put("clock_overrides", dict("a_rx", dict("offset_ms", Py.i(0)), "a_tx", dict("offset_ms", Py.f(0.0))));
        Topology.validate(t2);
    }

    @Test
    void pathsAndIdsAreChecked() {
        Map<String, Object> dup = stageTopology();
        ((Map<String, Object>) ((List<?>) dup.get("points")).get(1)).put("id", "a_rx");
        dup.put("forward", new ArrayList<>());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Topology.validate(dup)).getMessage()
                .contains("Capture point IDs must be unique"));
        Map<String, Object> missing = stageTopology();
        ((List<Object>) missing.get("forward")).add("nowhere");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Topology.validate(missing)).getMessage()
                .contains("Paths must contain existing capture points"));

        Map<String, Object> cycle = dict("points", List.of(dict("id", "a", "label", "a", "device", "a",
                "capture_id", "c")), "forward", List.of("a", "a"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Topology.validate(cycle)).getMessage()
                .contains("cycles are unsupported"));
        Map<String, Object> noCidr = dict("client_cidrs", List.of());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Topology.validate(noCidr)).getMessage()
                .contains("At least one client CIDR"));
        assertThrows(IllegalArgumentException.class, () -> Topology.validate(dict("client_cidrs", List.of("10.0.0.300/8"))));
        assertThrows(IllegalArgumentException.class, () -> Topology.validate(dict("start", Py.i(5), "end", Py.i(5))));
    }

    @Test
    void pointValidators() {
        Map<String, Object> base = dict("id", "p-1", "label", "x", "device", "x", "capture_id", "c");
        Point p = Point.validate(base);
        assertEquals("Generic", p.kind);
        Map<String, Object> badId = new java.util.LinkedHashMap<>(base);
        badId.put("id", "a.b");
        assertThrows(IllegalArgumentException.class, () -> Point.validate(badId));
        Map<String, Object> newline = new java.util.LinkedHashMap<>(base);
        newline.put("id", "ab\n");
        assertThrows(IllegalArgumentException.class, () -> Point.validate(newline));
        Map<String, Object> cp = new java.util.LinkedHashMap<>(base);
        cp.put("vendor", "checkpoint");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Point.validate(cp)).getMessage()
                .contains("Check Point inspection stage"));
        Map<String, Object> pa = new java.util.LinkedHashMap<>(base);
        pa.put("vendor", "paloalto");
        pa.put("vendor_stage", "receive");
        assertEquals("ingress", Point.validate(pa).side);
        Map<String, Object> f5 = new java.util.LinkedHashMap<>(base);
        f5.put("vendor", "f5");
        f5.put("vendor_stage", "client");
        assertEquals("full_proxy", Point.validate(f5).translation);
        Map<String, Object> inf = new java.util.LinkedHashMap<>(base);
        inf.put("x", Py.f(Double.POSITIVE_INFINITY));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Point.validate(inf)).getMessage()
                .contains("Coordinates must be finite"));
    }

    @Test
    void dumpKeepsTheIntOrFloatTypeOfEachValue() {
        Topology t = new Topology();
        String text = PyJson.dumps(t.dump());
        assertTrue(text.startsWith("{\"cycle_stall_ms\": 100, \"cycle_top_n\": 20, \"rtp_port_ranges\": []"), text);
        assertTrue(text.contains("\"security_scan_failed_ratio\": 0.8"));
        assertTrue(text.endsWith("\"stall_ms\": 200, \"match_window_ms\": 2000, \"start\": null, \"end\": null}"));
        Topology v = Topology.validate(dict("cycle_stall_ms", Py.i(100), "rtp_port_ranges", List.of(List.of(Py.i(1000),
                Py.i(2000)))));
        String vt = PyJson.dumps(v.dump());
        assertTrue(vt.startsWith("{\"cycle_stall_ms\": 100.0, \"cycle_top_n\": 20, \"rtp_port_ranges\": [[1000, 2000]]"),
                vt);
        Point p = Point.of("a", "a", "a", "c", Py.i(240), Py.i(0));
        assertEquals("240.0", p.x.repr(), "constructed points validate coordinates to float");
        p.x = Py.i(220);
        assertTrue(PyJson.dumps(p.dump()).endsWith("\"x\": 220, \"y\": 0.0}"), "assignment is not validated");
    }

    @Test
    void natTupleValidationAndNormalization() {
        NatMapping m = NatMapping.validate(dict("point_a", "a", "point_b", "b",
                "tuple_a", "[\"TCP\", \"10.0.0.1\", 40, \"10.0.0.2\", 50]",
                "tuple_b", "[\"TCP\",\"10.0.0.1\",40,\"10.0.0.2\",50]"));
        assertEquals(m.tupleA(), m.tupleB());
        assertEquals("[\"TCP\",\"10.0.0.1\",40,\"10.0.0.2\",50]", m.tupleA());
        assertThrows(IllegalArgumentException.class,
                () -> NatMapping.validate(dict("point_a", "a", "point_b", "b", "tuple_a", "[]", "tuple_b", "[]")));
        assertThrows(IllegalArgumentException.class, () -> NatMapping.validate(dict("point_a", "a", "point_b", "b",
                "tuple_a", "[\"TCP\",\"10.0.0.1\",true,\"10.0.0.2\",50]", "tuple_b", "[]")));
        assertThrows(IllegalArgumentException.class, () -> NatMapping.validate(dict("point_a", "a", "point_b", "b",
                "tuple_a", "[\"TCP\",\"10.0.0.01\",1,\"10.0.0.2\",50]", "tuple_b", "[]")));
    }

    @Test
    void ipSyntax() {
        assertTrue(IpNetworks.isAddress("2001:db8::1"));
        assertTrue(IpNetworks.isAddress("::ffff:192.0.2.1"));
        assertTrue(IpNetworks.isAddress("::"));
        assertFalse(IpNetworks.isAddress("1::2::3"));
        assertFalse(IpNetworks.isAddress("1:2:3:4:5:6:7:8:9"));
        assertFalse(IpNetworks.isAddress("example.com"));
        IpNetworks.network("10.0.0.0/255.0.0.0");
        IpNetworks.network("2001:db8::/32");
        assertThrows(IllegalArgumentException.class, () -> IpNetworks.network("10.0.0.0/33"));
    }
}
