package com.securityexpert.nexus.ui2.worker.failover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * PO 2026-10-24 (GR-7): failover check 6 on PAN-OS 12 or later sums show session meter's current sessions over every
 * entry. Synthetic responses only; any unusable shape is null (UNKNOWN), never a guessed or partial count.
 */
class PanSessionMeterParserTest {
    private static String ok(String entries) {
        return "<response status=\"success\"><result>" + entries + "</result></response>";
    }
    private static String entry(String vsys, String current) {
        return "<entry>" + (vsys == null ? "" : "<vsys>" + vsys + "</vsys>") + "<maximum>0</maximum>"
                + (current == null ? "" : "<current>" + current + "</current>") + "<throttled>0</throttled></entry>";
    }

    @Test void aSingleVsysIsItsCurrentCount() {
        assertEquals(1234L, PanFailoverChecks.sessionMeter(ok(entry("1", "1234"))));
        assertEquals(0L, PanFailoverChecks.sessionMeter(ok(entry("1", "0"))), "zero is a measured count, not UNKNOWN");
    }

    @Test void multipleVsysAreSummed() {
        assertEquals(600L, PanFailoverChecks.sessionMeter(ok(entry("1", "100") + entry("2", "200") + entry("3", "300"))));
        // Per vsys and dataplane on a multi-dataplane model: distinct (vsys, dp) pairs are all counted.
        assertEquals(30L, PanFailoverChecks.sessionMeter(ok(
                "<entry><vsys>1</vsys><dp>0</dp><current>10</current></entry><entry><vsys>1</vsys><dp>1</dp><current>20</current></entry>")));
        // One wrapping element between result and the entries is accepted.
        assertEquals(15L, PanFailoverChecks.sessionMeter(ok("<meter>" + entry("1", "5") + entry("2", "10") + "</meter>")));
    }

    @Test void anEntryWithoutAVsysLeafIsToleratedAndCounted() {
        assertEquals(50L, PanFailoverChecks.sessionMeter(ok(entry(null, "20") + entry("2", "30"))));
        assertEquals(40L, PanFailoverChecks.sessionMeter(ok(entry(null, "20") + entry(null, "20"))));
    }

    @Test void missingEntriesOrCountsAreUnknown() {
        assertNull(PanFailoverChecks.sessionMeter(ok("")), "no entry at all");
        assertNull(PanFailoverChecks.sessionMeter("<response status=\"success\"><result/></response>"));
        assertNull(PanFailoverChecks.sessionMeter(ok(entry("1", "100") + entry("2", null))), "one entry without current");
        assertFalse(PanFailoverChecks.sessionMeterFieldFound(ok("")));
        assertTrue(PanFailoverChecks.sessionMeterFieldFound(ok(entry("1", "x"))));
        assertNull(PanFailoverChecks.sessionMeter(null));
        assertNull(PanFailoverChecks.sessionMeter(""));
    }

    @Test void malformedOrErrorAnswersAreUnknown() {
        assertNull(PanFailoverChecks.sessionMeter("<response status=\"error\"><msg>synthetic</msg></response>"));
        assertNull(PanFailoverChecks.sessionMeter("not xml"));
        assertNull(PanFailoverChecks.sessionMeter("<response status=\"success\"><result><entry><current>1"), "truncated");
        assertNull(PanFailoverChecks.sessionMeter(ok(entry("1", "-5"))));
        assertNull(PanFailoverChecks.sessionMeter(ok(entry("1", "12k"))));
        assertNull(PanFailoverChecks.sessionMeter(ok("<entry><vsys>1</vsys><current>1</current><current>2</current></entry>")),
                "two current leaves in one entry");
        assertNull(PanFailoverChecks.sessionMeter(ok(entry("1", "10") + entry("1", "10"))), "the same vsys twice is ambiguous");
        assertNull(PanFailoverChecks.sessionMeter(ok(entry("1", "9223372036854775807") + entry("2", "1"))), "overflow");
        assertNull(PanFailoverChecks.sessionMeter(ok("<a>" + entry("1", "1") + "</a><b>" + entry("2", "1") + "</b>")),
                "two wrapping elements: shape not recognised");
        assertNull(PanFailoverChecks.sessionMeter(
                "<!DOCTYPE r [<!ENTITY x \"1\">]><response status=\"success\"><result>" + entry("1", "&x;") + "</result></response>"));
    }

    @Test void theEvidenceNamesTheSource() {
        assertEquals("{\"count\":7,\"source\":\"session meter\"}", PanFailoverChecks.sessionsCounted(7L, PanFailoverChecks.SOURCE_SESSION_METER));
        assertEquals("{\"count\":7}", PanFailoverChecks.sessionsCounted(7L, PanFailoverChecks.SOURCE_SESSION_INFO),
                "below PAN-OS 12 the evidence is unchanged");
        assertTrue(PanFailoverChecks.sessionMeterUnknown(false).contains("\"reason\":\"field not found\""));
        assertTrue(PanFailoverChecks.sessionMeterUnknown(true).contains("\"source\":\"session meter\""));
        assertEquals("{\"reason\":\"mixed_sources\",\"source\":\"session info\",\"other_source\":\"session meter\"}",
                PanFailoverChecks.sessionsMixedSources("session info", "session meter"));
    }
}
