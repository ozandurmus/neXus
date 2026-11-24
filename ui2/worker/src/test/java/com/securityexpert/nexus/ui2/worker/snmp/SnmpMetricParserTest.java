package com.securityexpert.nexus.ui2.worker.snmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Metrics;
import com.securityexpert.nexus.ui2.worker.snmp.SnmpClient.SnmpAnswer;
import com.securityexpert.nexus.ui2.worker.snmp.SnmpClient.SnmpValue;

/** Synthetic SNMP answers for both pilot vendors: every fact parses, and anything missing or odd is UNKNOWN, never 0. */
class SnmpMetricParserTest {

    private static BigDecimal pct(String v) {
        return new BigDecimal(v);
    }

    private static Map<String, SnmpValue> checkPoint() {
        Map<String, SnmpValue> v = new HashMap<>();
        v.put(SnmpOids.SYS_UPTIME, SnmpValue.number(123_456_789L));
        v.put(SnmpOids.CP_PROC_USAGE, SnmpValue.number(37));
        v.put(SnmpOids.CP_MEM_TOTAL_REAL64, SnmpValue.number(16_000_000_000L));
        v.put(SnmpOids.CP_MEM_ACTIVE_REAL64, SnmpValue.number(6_000_000_000L));
        v.put(SnmpOids.CP_FW_NUM_CONN, SnmpValue.number(48_213));
        return v;
    }

    private static Map<String, SnmpValue> paloAlto() {
        Map<String, SnmpValue> v = new HashMap<>();
        v.put(SnmpOids.SYS_UPTIME, SnmpValue.number(9_876_543L));
        v.put(SnmpOids.HR_PROCESSOR_LOAD + ".1", SnmpValue.number(12));
        v.put(SnmpOids.HR_PROCESSOR_LOAD + ".2", SnmpValue.number(64));
        v.put(SnmpOids.HR_PROCESSOR_LOAD + ".10", SnmpValue.number(71));
        // hrStorage rows: 1 = virtual memory, 2 = RAM, 3 = a fixed disk
        v.put(SnmpOids.HR_STORAGE_TYPE + ".1", SnmpValue.oid("1.3.6.1.2.1.25.2.1.3"));
        v.put(SnmpOids.HR_STORAGE_SIZE + ".1", SnmpValue.number(900));
        v.put(SnmpOids.HR_STORAGE_USED + ".1", SnmpValue.number(800));
        v.put(SnmpOids.HR_STORAGE_TYPE + ".2", SnmpValue.oid(SnmpOids.HR_STORAGE_RAM));
        v.put(SnmpOids.HR_STORAGE_SIZE + ".2", SnmpValue.number(4000));
        v.put(SnmpOids.HR_STORAGE_USED + ".2", SnmpValue.number(1000));
        v.put(SnmpOids.HR_STORAGE_TYPE + ".3", SnmpValue.oid("1.3.6.1.2.1.25.2.1.4"));
        v.put(SnmpOids.HR_STORAGE_SIZE + ".3", SnmpValue.number(100));
        v.put(SnmpOids.HR_STORAGE_USED + ".3", SnmpValue.number(99));
        v.put(SnmpOids.PAN_SESSION_ACTIVE, SnmpValue.number(5_120));
        return v;
    }

    @Test
    void checkPointAnswerGivesAllFourFacts() {
        Metrics m = SnmpMetricParser.parse(SnmpVendorProfile.CHECK_POINT, new SnmpAnswer.Answered(checkPoint()));
        assertEquals(123_456_789L, m.sysUptimeCs());
        assertEquals(pct("37.00"), m.cpuPct());
        assertNull(m.cpuDataplanePct());
        assertEquals(pct("37.50"), m.memPct());
        assertEquals(48_213L, m.sessions());
        assertTrue(m.complete());
    }

    @Test
    void checkPointMemoryAsDigitsStringIsRead() {
        Map<String, SnmpValue> v = checkPoint();
        v.put(SnmpOids.CP_MEM_TOTAL_REAL64, SnmpValue.text("8000"));
        v.put(SnmpOids.CP_MEM_ACTIVE_REAL64, SnmpValue.text(" 2000 "));
        assertEquals(pct("25.00"), SnmpMetricParser.parse(SnmpVendorProfile.CHECK_POINT, new SnmpAnswer.Answered(v)).memPct());
    }

    @Test
    void checkPointMissingOrOddValuesAreUnknownNeverZero() {
        Map<String, SnmpValue> v = checkPoint();
        v.put(SnmpOids.CP_PROC_USAGE, SnmpValue.number(140));
        v.put(SnmpOids.CP_MEM_TOTAL_REAL64, SnmpValue.number(0));
        v.remove(SnmpOids.CP_FW_NUM_CONN);
        v.put(SnmpOids.SYS_UPTIME, SnmpValue.MISSING);
        Metrics m = SnmpMetricParser.parse(SnmpVendorProfile.CHECK_POINT, new SnmpAnswer.Answered(v));
        assertNull(m.cpuPct());
        assertNull(m.memPct());
        assertNull(m.sessions());
        assertNull(m.sysUptimeCs());
        assertFalse(m.complete());
        assertFalse(m.any());

        v = checkPoint();
        v.put(SnmpOids.CP_MEM_ACTIVE_REAL64, SnmpValue.number(17_000_000_000L));
        v.put(SnmpOids.CP_PROC_USAGE, SnmpValue.text("n/a"));
        v.put(SnmpOids.CP_FW_NUM_CONN, SnmpValue.number(-1));
        m = SnmpMetricParser.parse(SnmpVendorProfile.CHECK_POINT, new SnmpAnswer.Answered(v));
        assertNull(m.memPct(), "active above total is UNKNOWN");
        assertNull(m.cpuPct());
        assertNull(m.sessions());
        assertTrue(m.any());
    }

    @Test
    void paloAltoManagementPlaneIsTheLowestRowAndDataPlaneTheHighestOfTheRest() {
        Metrics m = SnmpMetricParser.parse(SnmpVendorProfile.PALO_ALTO, new SnmpAnswer.Answered(paloAlto()));
        assertEquals(9_876_543L, m.sysUptimeCs());
        assertEquals(pct("12.00"), m.cpuPct());
        assertEquals(pct("71.00"), m.cpuDataplanePct(), "row 10 sorts after row 2 numerically");
        assertEquals(pct("25.00"), m.memPct(), "the hrStorageRam row, not virtual memory or disk");
        assertEquals(5_120L, m.sessions());
        assertTrue(m.complete());
    }

    @Test
    void paloAltoWithoutRamRowOrWithOneProcessorRow() {
        Map<String, SnmpValue> v = paloAlto();
        v.put(SnmpOids.HR_STORAGE_TYPE + ".2", SnmpValue.oid("1.3.6.1.2.1.25.2.1.3"));
        v.remove(SnmpOids.HR_PROCESSOR_LOAD + ".2");
        v.remove(SnmpOids.HR_PROCESSOR_LOAD + ".10");
        Metrics m = SnmpMetricParser.parse(SnmpVendorProfile.PALO_ALTO, new SnmpAnswer.Answered(v));
        assertNull(m.memPct());
        assertEquals(pct("12.00"), m.cpuPct());
        assertNull(m.cpuDataplanePct());
        assertFalse(m.complete());
    }

    @Test
    void paloAltoEmptyWalkLeavesCpuAndMemoryUnknown() {
        Map<String, SnmpValue> v = new HashMap<>();
        v.put(SnmpOids.SYS_UPTIME, SnmpValue.number(1));
        v.put(SnmpOids.PAN_SESSION_ACTIVE, SnmpValue.number(0));
        Metrics m = SnmpMetricParser.parse(SnmpVendorProfile.PALO_ALTO, new SnmpAnswer.Answered(v));
        assertNull(m.cpuPct());
        assertNull(m.cpuDataplanePct());
        assertNull(m.memPct());
        assertEquals(0L, m.sessions(), "an answered 0 is a real 0");
    }

    @Test
    void aDataPlaneRowThatDidNotAnswerMakesDataPlaneUnknown() {
        Map<String, SnmpValue> v = paloAlto();
        v.put(SnmpOids.HR_PROCESSOR_LOAD + ".2", SnmpValue.MISSING);
        assertNull(SnmpMetricParser.parse(SnmpVendorProfile.PALO_ALTO, new SnmpAnswer.Answered(v)).cpuDataplanePct());
    }

    @Test
    void answersNeverPrintTheirValues() {
        var answer = new SnmpAnswer.Answered(checkPoint());
        assertEquals("Answered[5 values]", answer.toString());
        assertEquals("SnmpValue[NUMBER]", SnmpValue.number(42).toString());
    }
}
