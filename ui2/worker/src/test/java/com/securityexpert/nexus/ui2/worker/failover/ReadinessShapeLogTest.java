package com.securityexpert.nexus.ui2.worker.failover;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReadinessShapeLogTest {
    @Test void warningUsesReadableMaskedVocabularyAndOtherLinesStayShapes() {
        var log=new ReadinessShapeLog("check_point");
        log.capture(3,"Warning! This is a test sentence with 'set something' in it.\nopaque123");
        assertEquals("[READINESS_SHAPE] vendor=check_point check=3 shape="
            +"Warning! This is a [MASKED] [MASKED] with 'set [MASKED]' in it.\naaaaaa999",
            log.logUnknown(3,"UNKNOWN"));
    }

    @Test void diagnosticPrefixesMaskIdentitiesAddressesAndSecrets() {
        for(String prefix:java.util.List.of("Warning!","Error","ERROR","Usage")) {
            String output=ReadinessShapeLog.textShape(prefix
                +" host CP-SPARK-TEST-01 192.0.2.10 2001:db8::1 02:00:00:00:00:01 test@example.invalid serial-123");
            assertEquals(prefix+" host "+"[MASKED] ".repeat(5)+"[MASKED]",output);
        }
        assertEquals("[SECRET REDACTED]",ReadinessShapeLog.textShape(
                "Warning! password "+String.join("-","synthetic","value")));
        assertEquals("aaaaaaa! aaaaaaa",ReadinessShapeLog.textShape("warning! unknown"));
    }

    @Test void unknownLogsEachCapturedExecutionWithoutMergingIdenticalOutputs() {
        var log=new ReadinessShapeLog("check_point");
        log.capture(3,"é\n",true,3,125);
        log.capture(3,"é\n",true,3,250);
        assertNull(log.logUnknown(3,"PASS"));
        assertEquals("[READINESS_SHAPE] vendor=check_point check=3 executions="
            +"{outputBytes=3,lineCount=1,pty=true,sessionCommandIndex=3,sessionElapsedMs=125};"
            +"{outputBytes=3,lineCount=1,pty=true,sessionCommandIndex=3,sessionElapsedMs=250} shape=a",
            log.logUnknown(3,"UNKNOWN"));
        assertNull(log.logUnknown(3,"UNKNOWN"));
    }

    @Test void unknownTableLogAlsoIncludesExecutionTelemetry() {
        var log=new ReadinessShapeLog("check_point");
        log.capture(2,"",false,2,10);
        assertTrue(log.logTables("UNKNOWN",java.util.Set.of(),java.util.Set.of()).contains(
            "executions={outputBytes=0,lineCount=0,pty=false,sessionCommandIndex=2,sessionElapsedMs=10}"));
    }

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

    @Test void panCheckFiveLogsMaskedValuesOnlyForUnknown() {
        var log=new ReadinessShapeLog("palo_alto");
        log.capture(5,"<response><result><enabled>Enabled</enabled><sent>1,234</sent>"
            +"<recv><![CDATA[pending99]]></recv><name>FW-TEST-01</name></result></response>");
        assertNull(log.logUnknown(5,"PASS"));
        assertNull(log.logUnknown(5,"FAIL"));
        String message=log.logUnknown(5,"UNKNOWN");
        assertTrue(message.contains("<enabled>aaaaaaa</enabled>"));
        assertTrue(message.contains("<sent>9,999</sent>"));
        assertTrue(message.contains("<recv>aaaaaaa99</recv>"));
        assertFalse(message.contains("Enabled") || message.contains("pending99") || message.contains("FW-TEST-01"));
        assertNull(log.logUnknown(5,"UNKNOWN"));
        assertEquals("aaa99",ReadinessShapeLog.valueShape("bad99"));
    }

    @Test void shapesAreLimitedToFirst25LinesAndTwoKilobytes() {
        assertEquals(25,ReadinessShapeLog.textShape("abc123\n".repeat(30)).lines().count());
        assertFalse(ReadinessShapeLog.xmlShape("<response>\n"+"<entry/>\n".repeat(24)
            +"<beyond-limit/></response>").contains("beyond-limit"));
        for (String output:new String[]{"X".repeat(4000),"☃".repeat(2000),"Warning! "+"This ".repeat(1000)})
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
