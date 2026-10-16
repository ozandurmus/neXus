package com.securityexpert.nexus.ui2.packetanalysis.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyJson;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** json.dumps / json.loads as the ingest stores identities, inventories and JSON columns. */
class PyJsonTest {

    @Test
    void dumpsDefaultAndCompactSeparators() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", 1L);
        m.put("a", Arrays.asList(null, true, 2.0, "x"));
        m.put("n", Map.of());
        assertEquals("{\"b\": 1, \"a\": [null, true, 2.0, \"x\"], \"n\": {}}", PyJson.dumps(m));
        assertEquals("{\"b\":1,\"a\":[null,true,2.0,\"x\"],\"n\":{}}", PyJson.dumpsCompact(m));
    }

    @Test
    void stringsAreAsciiEscaped() {
        assertEquals("\"a\\\"b\\\\c\\n\\r\\t\\b\\f\\u0001\\u007f\\u00e9\\u4e2d\\ud83d\\ude00/\"",
                PyJson.dumps("a\"b\\c\n\r\t\b\f\u0001\u007fé中😀/"));
    }

    @Test
    void floatsUseTheReferenceRepr() {
        assertEquals("[1e-05, 1e+16, 40.5239999294281, NaN, -Infinity, -0.0]",
                PyJson.dumps(List.of(1e-5, 1e16, 40.5239999294281, Double.NaN, Double.NEGATIVE_INFINITY, -0.0)));
    }

    @Test
    void loadsKeepsOrderAndTypes() {
        Object v = PyJson.loads("{\"z\": [1, 2.5, -3e2, 18446744073709551621], \"a\": {\"x\": null},"
                + " \"s\": \"\\u00e9\\n\", \"t\": true}");
        assertEquals("{\"z\": [1, 2.5, -300.0, 18446744073709551621], \"a\": {\"x\": null}, \"s\": \"\\u00e9\\n\","
                + " \"t\": true}", PyJson.dumps(v));
        assertEquals(new BigInteger("18446744073709551621"), ((List<?>) ((Map<?, ?>) v).get("z")).get(3));
        assertEquals(1L, ((List<?>) ((Map<?, ?>) v).get("z")).get(0));
    }
}
