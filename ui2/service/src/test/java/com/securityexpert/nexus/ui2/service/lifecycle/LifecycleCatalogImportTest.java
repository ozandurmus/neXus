package com.securityexpert.nexus.ui2.service.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LifecycleCatalogImportTest {
    private static final String HEADER = String.join(",", LifecycleCatalogImport.HEADER) + "\r\n";
    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
    private LifecycleCatalogImport.Result parse(String rows) {
        return LifecycleCatalogImport.parse(HEADER + rows, "synthetic-actor", NOW);
    }

    @Test void validQuotedCsvAndBlankDatesCarryImportProvenance() {
        var result = parse("CHECKPOINT,HARDWARE,6600 Appliance,,2030-02-28,,\"Vendor note, with \"\"quotes\"\"\"\r\n");
        assertTrue(result.errors().isEmpty());
        var entry = result.entries().getFirst();
        assertNull(entry.endOfSale());
        assertEquals("2030-02-28", entry.endOfSupport().toString());
        assertEquals("Vendor note, with \"quotes\"", entry.note());
        assertEquals("IMPORT", entry.source());
        assertEquals("synthetic-actor", entry.importedBy());
        assertEquals(NOW, entry.importedAt());
    }

    @Test void invalidDatesAreReportedByRowAndDoNotEchoInput() {
        var result = parse("CHECKPOINT,SOFTWARE,R81.20,,2030-02-30,,\nPALOALTO,SOFTWARE,PAN-OS 10.2,,31/12/2030,,\n");
        assertEquals(2, result.errors().size());
        assertEquals(2, result.errors().getFirst().row());
        assertEquals(3, result.errors().get(1).row());
        assertTrue(result.errors().getFirst().reason().contains("end_of_support"));
        assertFalse(result.errors().toString().contains("2030-02-30"));
    }

    @Test void duplicateKeyWithinFileIsAnErrorRatherThanLastRowWins() {
        var result = parse("CHECKPOINT,HARDWARE,6600,,2030-01-01,,\nCHECKPOINT,HARDWARE,6600,,2031-01-01,,\n");
        assertEquals(1, result.errors().size());
        assertEquals(3, result.errors().getFirst().row());
        // Two spellings of one model have one match key: the second row is the same product.
        var spelled = parse("CHECKPOINT,HARDWARE,6600,,2030-01-01,,\nCHECKPOINT,HARDWARE,Check Point 6600,,2030-01-01,,\n");
        assertEquals(1, spelled.errors().size());
    }

    @Test void aProductOfAVendorWithKeyRulesMustGiveAKeyAndOtherVendorsKeepFreeText() {
        var refused = parse("CHECKPOINT,HARDWARE,Example Appliance,,2030-01-01,,\nFORTINET,SOFTWARE,7.4,,2030-01-01,,\n"
                + "CISCO_ASA,SOFTWARE,ASA 9.18,,2027-11-30,,\nFORTINET,SOFTWARE,FortiOS 7.4,,2028-11-11,,\n"
                + "RADWARE,SOFTWARE,ExampleOS 1.2,,2030-01-01,,\nPALOALTO,HARDWARE,PA-3220,2023-08-31,2028-08-31,,\n");
        assertEquals(2, refused.errors().size());
        assertEquals(2, refused.errors().get(0).row());
        assertTrue(refused.errors().get(0).reason().contains("expected a model such as 6600"));
        assertEquals(3, refused.errors().get(1).row());
        assertTrue(refused.errors().get(1).reason().contains("FortiOS 7.4 or FortiManager 7.4"));
        assertEquals(4, refused.entries().size());
    }

    @Test void malformedHeadersQuotesKindsVendorsAndLimitsFailClosed() {
        assertFalse(LifecycleCatalogImport.parse("product,vendor\nx,y", "synthetic-actor", NOW).errors().isEmpty());
        assertFalse(parse("CHECKPOINT,HARDWARE,\"unclosed").errors().isEmpty());
        assertFalse(parse("CHECKPOINT,OTHER,Example Appliance,,,,\n").errors().isEmpty());
        assertFalse(parse("OTHER,HARDWARE,Example Appliance,,,,\n").errors().isEmpty());
        assertFalse(LifecycleCatalogImport.parse("x".repeat(1_000_001), "synthetic-actor", NOW).errors().isEmpty());
    }
}
