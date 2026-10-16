package com.securityexpert.nexus.ui2.packetanalysis.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The reference csv reader/writer semantics the ingest depends on. */
class PyCsvTest {

    private static List<List<String>> read(String text, char delimiter) {
        PyCsv.CsvReader r = new PyCsv.CsvReader(new StringReader(text), delimiter);
        List<List<String>> out = new ArrayList<>();
        List<String> rec;
        while ((rec = r.next()) != null) {
            out.add(rec);
        }
        return out;
    }

    @Test
    void tsharkStyleQuotedTabRecords() {
        assertEquals(List.of(List.of("1", "", "a\"b", ""), List.of("2", "x y", "", "")),
                read("\"1\"\t\t\"a\"\"b\"\t\n\"2\"\t\"x y\"\t\t\n", '\t'));
    }

    @Test
    void emptyLinesAreEmptyRecordsAndNewlinesAreUniversal() {
        assertEquals(List.of(List.of("a"), List.of(), List.of("b", "c"), List.of("d")),
                read("a\r\n\nb\tc\rd", '\t'));
    }

    @Test
    void quotedFieldsSpanLinesAndCarriageReturnsBecomeNewlines() {
        assertEquals(List.of(List.of("x\ny\nz", "w")), read("\"x\r\ny\rz\"\tw\n", '\t'));
    }

    @Test
    void lenientQuoteHandling() {
        // Text after a closing quote is kept; a quote inside an unquoted field is literal.
        assertEquals(List.of(List.of("ab c", "d\"e")), read("\"ab\" c\td\"e\n", '\t'));
    }

    @Test
    void endOfInputInsideAQuotedFieldKeepsTheField() {
        assertEquals(List.of(List.of("a", "bc\n")), read("a\t\"bc\n", '\t'));
        assertNull(new PyCsv.CsvReader(new StringReader(""), '\t').next());
    }

    @Test
    void writerQuotesMinimallyAndUsesReferenceText() throws IOException {
        StringWriter w = new StringWriter();
        PyCsv.writeRow(w, new Object[] {"a,b", "q\"", null, "", true, false, 1L, 0.1, 1e16, 1.5e-7, "x\ny", "plain"},
                ',');
        assertEquals("\"a,b\",\"q\"\"\",,,True,False,1,0.1,1e+16,1.5e-07,\"x\ny\",plain\r\n", w.toString());
        StringWriter single = new StringWriter();
        PyCsv.writeRow(single, new Object[] {""}, ',');
        assertEquals("\"\"\r\n", single.toString());
    }

    @Test
    void fieldLimit() {
        String big = "\"" + "x".repeat(PyCsv.FIELD_LIMIT + 1) + "\"\n";
        assertThrows(IllegalArgumentException.class, () -> read(big, '\t'));
    }
}
