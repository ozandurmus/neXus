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
        var result = parse("CHECKPOINT,HARDWARE,Example Appliance,,2030-02-28,,\"Vendor note, with \"\"quotes\"\"\"\r\n");
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
        var result = parse("CHECKPOINT,HARDWARE,Example Appliance,,2030-01-01,,\nCHECKPOINT,HARDWARE,Example Appliance,,2031-01-01,,\n");
        assertEquals(1, result.errors().size());
        assertEquals(3, result.errors().getFirst().row());
    }

    @Test void malformedHeadersQuotesKindsVendorsAndLimitsFailClosed() {
        assertFalse(LifecycleCatalogImport.parse("product,vendor\nx,y", "synthetic-actor", NOW).errors().isEmpty());
        assertFalse(parse("CHECKPOINT,HARDWARE,\"unclosed").errors().isEmpty());
        assertFalse(parse("CHECKPOINT,OTHER,Example Appliance,,,,\n").errors().isEmpty());
        assertFalse(parse("OTHER,HARDWARE,Example Appliance,,,,\n").errors().isEmpty());
        assertFalse(LifecycleCatalogImport.parse("x".repeat(1_000_001), "synthetic-actor", NOW).errors().isEmpty());
    }
}
