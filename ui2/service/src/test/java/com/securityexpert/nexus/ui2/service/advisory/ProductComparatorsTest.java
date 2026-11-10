package com.securityexpert.nexus.ui2.service.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductComparatorsTest {

    private final ProductComparator pan = new PanOsComparator();
    private final ProductComparator forti = new FortinetComparator(AdvisoryModel.PRODUCT_FORTIOS);
    private final ProductComparator asa = new CiscoAsaComparator();

    @Test
    void panOsParsesHotfixAndOrders() {
        assertEquals(Optional.of("11.1.10-h7"), pan.canonical("11.1.10-h7"));
        assertEquals(Optional.of("11.1.10"), pan.canonical("11.1.10"));
        assertEquals(Optional.of("11.1"), pan.train("11.1.10-h7"));
        assertTrue(pan.compare("11.1.10", "11.1.10-h1") < 0);
        assertEquals(0, pan.compare("11.1.10", "11.1.10-h0"));
        assertTrue(pan.compare("11.1.9-h20", "11.1.10") < 0);
        assertTrue(pan.compare("11.2.0", "11.1.30") > 0);
        assertTrue(pan.compare("11.1.2", "11.1.10") < 0, "numeric, not textual");
    }

    @Test
    void panOsRejectsEverythingElse() {
        for (String s : new String[] {"11.1.10-c12", "11.1", "11.1.10-h", "11.1.10-hx", "v11.1.10", " 11.1.10", "11.1.10 ", "", "abc", "11.1.10.1", "011.1.10"}) {
            assertTrue(pan.canonical(s).isEmpty(), s);
        }
        assertTrue(pan.canonical(null).isEmpty());
        assertTrue(pan.train("11.1").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> pan.compare("11.1", "11.1.2"));
    }

    @Test
    void fortinetIgnoresBuild() {
        assertEquals(Optional.of("7.4.12"), forti.canonical("v7.4.12 build2902"));
        assertEquals(Optional.of("7.4.12"), forti.canonical("7.4.12"));
        assertEquals(Optional.of("7.4.12"), forti.canonical("v7.4.12"));
        assertEquals(Optional.of("7.4"), forti.train("7.4.12"));
        assertTrue(forti.compare("7.4.9", "7.4.12") < 0);
        assertEquals(0, forti.compare("7.4.12", "7.4.12"));
        for (String s : new String[] {"v7.4", "7.4.12 build", "7.4.12-beta", "7.4.12 build29x", "FortiOS 7.4.12", ""}) {
            assertTrue(forti.canonical(s).isEmpty(), s);
        }
        assertThrows(IllegalArgumentException.class, () -> forti.compare("v7.4.12", "7.4.12"));
    }

    @Test
    void ciscoAsaConvertsParenthesisAndKeepsMissingPartDistinct() {
        assertEquals(Optional.of("9.18.4.50"), asa.canonical("9.18(4)50"));
        assertEquals(Optional.of("9.18.4"), asa.canonical("9.18(4)"));
        assertEquals(Optional.of("9.18.4.50"), asa.canonical("9.18.4.50"));
        assertEquals(Optional.of("9.18"), asa.train("9.18.4.50"));
        assertTrue(asa.compare("9.18.4", "9.18.4.0") != 0);
        assertTrue(asa.compare("9.18.4", "9.18.4.0") < 0);
        assertTrue(asa.compare("9.18.4.9", "9.18.4.50") < 0);
        assertTrue(asa.compare("9.18.4.50", "9.18.5") < 0);
        for (String s : new String[] {"9.18", "9.8(4.45)", "9.18(4", "9.18(4)x", "ASA 9.18(4)", ""}) {
            assertTrue(asa.canonical(s).isEmpty(), s);
        }
    }
}
