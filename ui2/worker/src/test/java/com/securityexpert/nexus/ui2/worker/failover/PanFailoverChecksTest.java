package com.securityexpert.nexus.ui2.worker.failover;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PanFailoverChecksTest {
    private static String xml(String local,String peer,String serial,String peerSerial,String connection,
            String ha2,String sync) {
        return "<response status=\"success\"><result><group><mode>Active-Passive</mode>"
            + "<running-sync>"+sync+"</running-sync><local-info><state>"+local+"</state>"
            + "<serial-num>"+serial+"</serial-num></local-info><peer-info><state>"+peer+"</state>"
            + "<serial-num>"+peerSerial+"</serial-num><conn-status>"+connection+"</conn-status>"
            + "<conn-ha1><conn-status>up</conn-status></conn-ha1>"
            + "<conn-ha2><conn-status>"+ha2+"</conn-status></conn-ha2></peer-info></group></result></response>";
    }
    @Test void pairPassFailAndUnknown() {
        var a=PanFailoverChecks.parse(xml("active","passive","0011","0022","up","up","synchronized"));
        var b=PanFailoverChecks.parse(xml("passive","active","0022","0011","up","up","synchronized"));
        assertEquals("PASS",PanFailoverChecks.roles(a,b,"active","passive"));
        assertEquals("PASS",PanFailoverChecks.relationship(a,b));
        assertEquals("PASS",PanFailoverChecks.links(a,b));
        assertEquals("PASS",PanFailoverChecks.sync(a,b));
        var bad=PanFailoverChecks.parse(xml("passive","active","0022","0099","down","down","not synchronized"));
        assertEquals("FAIL",PanFailoverChecks.relationship(a,bad));
        assertEquals("FAIL",PanFailoverChecks.links(a,bad));
        assertEquals("UNKNOWN",PanFailoverChecks.sync(a,bad));
        var unknown=PanFailoverChecks.parse("<response status=\"error\"/>");
        assertEquals("UNKNOWN",PanFailoverChecks.roles(a,unknown,"active","passive"));
        assertEquals("UNKNOWN",PanFailoverChecks.relationship(a,unknown));
        assertEquals("UNKNOWN",PanFailoverChecks.links(a,unknown));
        assertEquals("UNKNOWN",PanFailoverChecks.sync(a,unknown));
    }
    @Test void activeActiveRefused() {
        var a=PanFailoverChecks.parse(xml("active","active","0011","0022","up","up","synchronized")
            .replace("Active-Passive","Active-Active"));
        var b=PanFailoverChecks.parse(xml("active","active","0022","0011","up","up","synchronized")
            .replace("Active-Passive","Active-Active"));
        assertEquals("FAIL",PanFailoverChecks.roles(a,b,"active","passive"));
    }
    private static String result(String body) {
        return "<response status=\"success\"><result>"+body+"</result></response>";
    }
    @Test void addedChecksPassFailUnknown() {
        assertEquals("PASS",PanFailoverChecks.sessionSync(result("<session-sync>in-sync</session-sync>")));
        assertEquals("FAIL",PanFailoverChecks.sessionSync(result("<session-sync>disabled</session-sync>")));
        assertEquals("UNKNOWN",PanFailoverChecks.sessionSync(result("<session-sync>other</session-sync>")));
        assertEquals(100L,PanFailoverChecks.sessions(result("<active-sessions>100</active-sessions>")));
        assertEquals(null,PanFailoverChecks.sessions(result("<active-sessions>bad</active-sessions>")));
        assertEquals("PASS",PanFailoverChecks.carried(100L,80L));
        assertEquals("FAIL",PanFailoverChecks.carried(101L,80L));
        assertEquals("UNKNOWN",PanFailoverChecks.carried(null,80L));
        String version=result("<system><sw-version>1</sw-version><app-version>2</app-version><threat-version>3</threat-version></system>");
        assertEquals("PASS",PanFailoverChecks.versions(version,version));
        assertEquals("FAIL",PanFailoverChecks.versions(version,version.replace("<threat-version>3", "<threat-version>4")));
        assertEquals("UNKNOWN",PanFailoverChecks.versions(version,result("<system/>")));
    }
    @Test void unknownReasonsNameExistingLookupPathsWithoutGuessingFields() {
        String[] paths={"group/running-sync","session-sync","num-active or active-sessions"};
        for (int check=4;check<=6;check++) {
            assertEquals(false,PanFailoverChecks.fieldFound(check,result("<other/>")));
            assertEquals("{\"reason\":\"field not found\",\"looked_for\":\"/response/result/"+paths[check-4]+"\"}",
                PanFailoverChecks.unknownDerived(check,"UNKNOWN",false));
            assertEquals("{}",PanFailoverChecks.unknownDerived(check,"PASS",true));
            assertEquals("{}",PanFailoverChecks.unknownDerived(check,"FAIL",true));
        }
        assertEquals(true,PanFailoverChecks.fieldFound(4,result("<group><running-sync>other</running-sync></group>")));
        assertEquals(true,PanFailoverChecks.fieldFound(5,result("<session-sync/>")));
        assertEquals(true,PanFailoverChecks.fieldFound(6,result("<active-sessions>invalid</active-sessions>")));
        assertEquals("{\"reason\":\"unrecognised field value\",\"looked_for\":\"/response/result/num-active or active-sessions\"}",
            PanFailoverChecks.unknownDerived(6,"UNKNOWN",true));
    }
    @Test void sessionsUseTheExistingSessionInfoCountsAndFailClosed() {
        assertEquals(100L,PanFailoverChecks.sessions(result("<num-active>100</num-active>")));
        assertEquals(0L,PanFailoverChecks.sessions(result("<num-active>0</num-active>")));
        for(String invalid:new String[]{"-1","bad","9223372036854775808",""})
            assertEquals(null,PanFailoverChecks.sessions(result("<num-active>"+invalid+"</num-active>")));
        assertEquals(null,PanFailoverChecks.sessions(result("<num-active>1</num-active><active-sessions>2</active-sessions>")));
        assertEquals(1L,PanFailoverChecks.sessions(result("<num-active>1</num-active><active-sessions>1</active-sessions>")));
        assertEquals(true,PanFailoverChecks.fieldFound(6,result("<num-active/>")));
        assertEquals(null,PanFailoverChecks.sessions(result("<messages/>")));
    }
    @Test void measuredActivePassiveCliDoesNotProveXmlFieldMapping() {
        String cli=com.securityexpert.nexus.ui2.worker.inventory.Fixtures.read("pan/readiness_state_sync_cli.txt");
        org.junit.jupiter.api.Assertions.assertTrue(cli.contains("State Synchronization Status: Complete"));
        org.junit.jupiter.api.Assertions.assertTrue(cli.contains("no (device not in active state)"));
        // Synthetic shapes use only the field names supplied by the PO. The status/count paths are unproved.
        for(String enabled:new String[]{"yes","no (device not in active state)"}) {
            String shape=result("<dp><enabled>"+enabled+"</enabled><aa_enabled>no</aa_enabled></dp>");
            assertEquals("UNKNOWN",PanFailoverChecks.sessionSync(shape));
            assertEquals(null,PanFailoverChecks.sessions(shape));
            assertEquals(false,PanFailoverChecks.fieldFound(5,shape));
            assertEquals(false,PanFailoverChecks.fieldFound(6,shape));
        }
    }
    public static void main(String[] args) {
        var test=new PanFailoverChecksTest(); test.pairPassFailAndUnknown(); test.activeActiveRefused(); test.addedChecksPassFailUnknown();
    }
}
