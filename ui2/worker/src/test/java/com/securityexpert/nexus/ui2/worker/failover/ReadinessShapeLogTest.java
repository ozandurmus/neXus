package com.securityexpert.nexus.ui2.worker.failover;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReadinessShapeLogTest {
    @Test void textMasksNamesAddressesAndUnicodeButKeepsOnlyApprovedTokens() {
        String tokens="Cluster Mode High Availability Active ACTIVE Standby STANDBY Down DOWN UP Non-Monitored (S) (LS) (HA) (S, LS) READY (local) ID State Name";
        assertEquals(tokens,ReadinessShapeLog.textShape(tokens));
        assertEquals("aa-aaaa-99 999.9.9.99 aaa9",ReadinessShapeLog.textShape("gw-test-01 192.0.2.10 é界Ω3"));
        assertEquals("aaaaaaaaaa aaaaaaaa",ReadinessShapeLog.textShape("ActiveName xActivex"));
    }

    @Test void canonicalAddressSetsKeepMemberShapeWithoutInterfaceNamesOrAddresses() {
        var log=new ReadinessShapeLog("check_point");
        String message=log.logTables("FAIL",java.util.Set.of("01||192.0.2.1"),
            java.util.Set.of("02|eth1|198.51.100.1"));
        assertEquals("[READINESS_SHAPE] vendor=check_point check=2"
            +" a={entries=1,members=[01],interfaces=[]} b={entries=1,members=[02],interfaces=[]} shape=",message);
    }
    @Test void tableFailuresLogBothMembersCoordinatesWithoutAddressesOnce() {
        for(String status:java.util.List.of("FAIL","UNKNOWN")) {
            var log=new ReadinessShapeLog("check_point");
            var a=java.util.Set.of("01|04|192.0.2.1","02|05|198.51.100.1");
            var b=java.util.Set.of("02|05|198.51.100.2");
            assertNull(log.logTables("PASS",a,b));
            log.capture(2,"192.0.2.1");
            assertEquals("[READINESS_SHAPE] vendor=check_point check=2"
                +" a={entries=2,members=[01, 02],interfaces=[04, 05]}"
                +" b={entries=1,members=[02],interfaces=[05]} shape=999.9.9.9",log.logTables(status,a,b));
            assertNull(log.logTables(status,a,b));
            assertNull(log.logUnknown(2,"UNKNOWN"));
        }
    }

    @Test void xmlEmitsOnlyElementNamesNeverTextAttributesCommentsOrCdata() {
        String xml="<?xml version=\"1.0\"?><response status=\"success\" name=\"gw-test-01\">"
            +"<!-- <false-tag>192.0.2.10</false-tag> -->"
            +"<result><name>gw-test-01</name><address value=\"192.0.2.10\">192.0.2.10</address>"
            +"<state><![CDATA[<not-a-tag>ACTIVE gw-test-01</not-a-tag>]]></state></result></response>";
        assertEquals("<response><result><name></name><address></address><state></state></result></response>",
            ReadinessShapeLog.xmlShape(xml));
        assertEquals("<unparseable>",ReadinessShapeLog.xmlShape("<response>gw-test-01 &broken;</response>"));
        assertEquals("<unparseable>",ReadinessShapeLog.xmlShape(
            "<!DOCTYPE response [<!ENTITY local 'gw-test-01'>]><response>&local;</response>"));
    }

    @Test void shapesAreLimitedToFirst25LinesAndTwoKilobytes() {
        assertEquals(25,ReadinessShapeLog.textShape("abc123\n".repeat(30)).lines().count());
        assertFalse(ReadinessShapeLog.xmlShape("<response>\n"+"<entry/>\n".repeat(24)
            +"<beyond-limit/></response>").contains("beyond-limit"));
        for (String output:new String[]{"X".repeat(4000),"☃".repeat(2000)})
            assertTrue(ReadinessShapeLog.textShape(output).getBytes(StandardCharsets.UTF_8).length<=2048);
        assertTrue(ReadinessShapeLog.xmlShape("<response>"+"<entry/>".repeat(1000)+"</response>")
            .getBytes(StandardCharsets.UTF_8).length<=2048);
    }

    @Test void logsOncePerUnknownCheckPerRunAndCapsTheEntireMessage() {
        var run=new ReadinessShapeLog("check_point");
        run.capture(1,"gw-test-01 192.0.2.10");
        run.capture(1,"gw-test-02 192.0.2.11");
        assertNull(run.logUnknown(1,"PASS"));
        assertNull(run.logUnknown(1,"FAIL"));
        assertEquals("[READINESS_SHAPE] vendor=check_point check=1 shape=aa-aaaa-99 999.9.9.99",
            run.logUnknown(1,"UNKNOWN"));
        assertNull(run.logUnknown(1,"UNKNOWN"));
        run.capture(2,"X".repeat(4000));
        assertEquals(2048,run.logUnknown(2,"UNKNOWN").getBytes(StandardCharsets.UTF_8).length);
        assertNotNull(new ReadinessShapeLog("check_point").logUnknown(1,"UNKNOWN"));
    }
}
