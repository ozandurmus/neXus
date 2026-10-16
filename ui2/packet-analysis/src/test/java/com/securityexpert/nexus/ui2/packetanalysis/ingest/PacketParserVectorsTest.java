package com.securityexpert.nexus.ui2.packetanalysis.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Field record to packets row, against reference vectors (tools/packet_analysis/parity/l1_parser_vectors.py): the
 * CSV line written for the row must be identical, which pins every value's type and text (ints, float repr,
 * True/False, empty for None, JSON columns, hashes, quoting).
 */
class PacketParserVectorsTest {

    private static List<String> fields(JsonNode c) {
        if (c.get("fields").isInt()) {
            assertEquals(TsharkFields.BASE_FIELDS.size(), c.get("fields").asInt());
            return TsharkFields.BASE_FIELDS;
        }
        List<String> out = new ArrayList<>();
        c.get("fields").forEach(f -> out.add(f.asText()));
        return out;
    }

    @Test
    void rowsMatchTheReference() throws IOException {
        JsonNode cases;
        try (InputStream in = getClass().getResourceAsStream("/packetanalysis/ingest/parser_vectors.json")) {
            if (in == null) {
                fail("missing parser_vectors.json");
            }
            cases = new ObjectMapper().readTree(in);
        }
        for (JsonNode c : cases) {
            List<String> values = new ArrayList<>();
            c.get("values").forEach(v -> values.add(v.asText()));
            List<String> fields = fields(c);
            int prefix = c.get("prefix").asInt();
            String name = c.get("name").asText();
            if (c.has("error")) {
                assertEquals("ValueError", c.get("error").asText(), name);
                assertThrows(IllegalArgumentException.class,
                        () -> PacketParser.parse(values, "cid0", prefix, fields), name);
                continue;
            }
            StringWriter w = new StringWriter();
            PyCsv.writeRow(w, PacketParser.parse(values, "cid0", prefix, fields), ',');
            assertEquals(c.get("csv").asText(), w.toString(), name);
        }
    }

    @Test
    void numberRules() {
        assertEquals(1L, PacketParser.number("True", null));
        assertEquals(0L, PacketParser.number("False", null));
        assertEquals(0x18L, PacketParser.number("0x0018", null));
        assertEquals(-1L, PacketParser.number("", -1L));
        assertEquals(null, PacketParser.number("", null));
        assertEquals(42L, PacketParser.number(" 42 ", null));
        assertThrows(IllegalArgumentException.class, () -> PacketParser.number("0X18", null));
        assertThrows(IllegalArgumentException.class, () -> PacketParser.number("1.5", null));
        assertEquals("a", PacketParser.first(java.util.Map.of("k", "a,b"), "k"));
        assertEquals("", PacketParser.first(java.util.Map.of(), "k"));
    }

    @Test
    void fromHexAndStrip() {
        assertEquals(3, PacketParser.fromHex("0a 0B\t1f").length);
        assertThrows(IllegalArgumentException.class, () -> PacketParser.fromHex("0"));
        assertThrows(IllegalArgumentException.class, () -> PacketParser.fromHex("0 a"));
        assertEquals("", PacketParser.strip("0000", '0'));
        assertEquals("1", PacketParser.strip("00100", '0'));
        assertEquals("102", PacketParser.strip("01020", '0'));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                PacketParser.sha256Hex(new byte[0]));
    }
}
