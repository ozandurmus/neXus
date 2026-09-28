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
    public static void main(String[] args) {
        var test=new PanFailoverChecksTest(); test.pairPassFailAndUnknown(); test.activeActiveRefused(); test.addedChecksPassFailUnknown();
    }
}
