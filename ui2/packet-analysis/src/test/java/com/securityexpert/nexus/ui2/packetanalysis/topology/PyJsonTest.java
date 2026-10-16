package com.securityexpert.nexus.ui2.packetanalysis.topology;

import static com.securityexpert.nexus.ui2.packetanalysis.topology.Py.dict;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The reference json.dumps/json.loads/repr text over the dynamic value model. */
class PyJsonTest {

    @Test
    void compactDumpUsesReferenceSeparatorsAndAsciiEscapes() {
        Map<String, Object> v = dict("badge", "Suggested topology — review & confirm", "n", Py.i(3),
                "x", Py.f(220.0), "tiny", Py.f(1e-05), "nan", Py.f(Double.NaN), "none", null, "ok", true,
                "list", List.of(), "map", Map.of(), "ctl", "a\tb\u007f\"\\");
        assertEquals("{\"badge\": \"Suggested topology \\u2014 review & confirm\", \"n\": 3, \"x\": 220.0, "
                + "\"tiny\": 1e-05, \"nan\": NaN, \"none\": null, \"ok\": true, \"list\": [], \"map\": {}, "
                + "\"ctl\": \"a\\tb\\u007f\\\"\\\\\"}", PyJson.dumps(v));
    }

    @Test
    void indentedDumpMatchesReferenceLayout() {
        Map<String, Object> v = dict("a", List.of(Py.i(1), dict("b", List.of())), "c", Map.of());
        assertEquals("{\n  \"a\": [\n    1,\n    {\n      \"b\": []\n    }\n  ],\n  \"c\": {}\n}",
                PyJson.dumps(v, 2));
    }

    @Test
    void astralCharactersBecomeSurrogatePairs() {
        assertEquals("\"\\ud83d\\ude00\"", PyJson.dumps("😀"));
    }

    @Test
    void loadsKeepsIntsAndFloatsApartAndKeyOrder() {
        Object v = PyJson.loads("{\"b\": 1, \"a\": 1.0, \"c\": [NaN, -Infinity, 1e400], \"b\": 2}");
        Map<?, ?> m = (Map<?, ?>) v;
        assertEquals(List.of("b", "a", "c"), new ArrayList<>(m.keySet()), "a repeated key keeps its first position");
        assertEquals(new PyInt(2), m.get("b"));
        assertEquals(new PyFloat(1.0), m.get("a"));
        List<?> c = (List<?>) m.get("c");
        assertTrue(Double.isNaN(((PyFloat) c.get(0)).value()));
        assertEquals(Double.NEGATIVE_INFINITY, ((PyFloat) c.get(1)).value());
        assertEquals(Double.POSITIVE_INFINITY, ((PyFloat) c.get(2)).value());
    }

    @Test
    void reprFollowsReferenceQuotingAndContainers() {
        assertEquals("['a.pcap', 'b.pcap']", Py.repr(List.of("a.pcap", "b.pcap")));
        assertEquals("\"it's\"", Py.repr("it's"));
        assertEquals("'say \"x\" it\\'s'", Py.repr("say \"x\" it's"));
        assertEquals("(64.0, 63.0)", Py.repr(PyTuple.of(Py.f(64), Py.f(63))));
        assertEquals("(1,)", Py.repr(PyTuple.of(Py.i(1))));
        assertEquals("{'erspan2': 9}", Py.repr(dict("erspan2", Py.i(9))));
        assertEquals("[None, True, 1e+16]", Py.repr(java.util.Arrays.asList(null, true, Py.f(1e16))));
        assertEquals("'\\x00\\né\\x85'", Py.repr("\u0000\né\u0085"));
    }

    @Test
    void equalityAndTruthinessFollowTheReference() {
        assertTrue(Py.eq(List.of(Py.f(1.0), Py.f(1.0)), List.of(Py.i(1), Py.i(1))));
        assertTrue(Py.eq(true, Py.i(1)));
        assertFalse(Py.eq(List.of(Py.i(1)), PyTuple.of(Py.i(1))));
        assertFalse(Py.truthy(Py.i(0)));
        assertFalse(Py.truthy(Map.of()));
        assertTrue(Py.truthy(Py.f(-0.5)));
        assertEquals("b", Py.or(Py.i(0), "b"));
    }
}
