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
        String[] paths={"group/running-sync","enabled and messages/entry/{enable,sent,recv,desc}","num-active or active-sessions"};
        for (int check=4;check<=6;check++) {
            assertEquals(false,PanFailoverChecks.fieldFound(check,result("<other/>")));
            assertEquals("{\"reason\":\"field not found\",\"looked_for\":\"/response/result/"+paths[check-4]+"\"}",
                PanFailoverChecks.unknownDerived(check,"UNKNOWN",false));
            assertEquals("{}",PanFailoverChecks.unknownDerived(check,"PASS",true));
            assertEquals("{}",PanFailoverChecks.unknownDerived(check,"FAIL",true));
        }
        assertEquals(true,PanFailoverChecks.fieldFound(4,result("<group><running-sync>other</running-sync></group>")));
        assertEquals(true,PanFailoverChecks.fieldFound(5,result("<enabled/><messages/>")));
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
        // A CLI Complete label and nested dp shape do not substitute for the measured XML fields.
        for(String enabled:new String[]{"yes","no (device not in active state)"}) {
            String shape=result("<dp><enabled>"+enabled+"</enabled><aa_enabled>no</aa_enabled></dp>");
            assertEquals("UNKNOWN",PanFailoverChecks.sessionSync(shape,shape));
            assertEquals(null,PanFailoverChecks.sessions(shape));
            assertEquals(false,PanFailoverChecks.fieldFound(5,shape));
            assertEquals(false,PanFailoverChecks.fieldFound(6,shape));
        }
    }
    static String syncXml(String enabled,String sent,String recv) {
        String entries="";
        for(String desc:new String[]{"session setup","session update"})
            entries+="<entry><enable>yes</enable><version>1</version><sent>"+sent+"</sent>"
                +"<recv>"+recv+"</recv><desc>"+desc+"</desc></entry>";
        return result("<dp>0</dp><enabled>"+enabled+"</enabled><aa_enabled>no</aa_enabled>"
            +"<sync_conf>yes</sync_conf><messages>"+entries+"</messages>");
    }
    @Test void measuredSessionMessagesUseActiveSentAndPassiveReceived() {
        String active=syncXml("yes","20","0");
        String passive=syncXml("no (device not in active state)","0","10");
        assertEquals("PASS",PanFailoverChecks.sessionSync(active,passive));
        assertEquals(true,PanFailoverChecks.fieldFound(5,active));
        assertEquals("PASS",PanFailoverChecks.sessionSync(active,passive.replace("session setup","temporary")
            .replace("session update","session setup").replace("temporary","session update")));
        assertEquals("FAIL",PanFailoverChecks.sessionSync(syncXml("yes","0","0"),syncXml("no","0","0")));
        assertEquals("FAIL",PanFailoverChecks.sessionSync(syncXml("no","20","0"),passive));
        assertEquals("FAIL",PanFailoverChecks.sessionSync(syncXml("yes","0","0"),passive));
        assertEquals("FAIL",PanFailoverChecks.sessionSync(active,syncXml("no","0","0")));
        assertEquals("FAIL",PanFailoverChecks.sessionSync(active.replace("<enable>yes", "<enable>no"),passive));
        for(String invalid:new String[]{"bad","-1","9223372036854775808"})
            assertEquals("UNKNOWN",PanFailoverChecks.sessionSync(syncXml("yes",invalid,"0"),passive));
        for(String tag:new String[]{"enabled","sent","enable","desc"}) {
            String missing=active.replaceAll("<"+tag+">[^<]*</"+tag+">","");
            assertEquals("UNKNOWN",PanFailoverChecks.sessionSync(missing,passive));
        }
        assertEquals("UNKNOWN",PanFailoverChecks.sessionSync(active,passive.replace("<recv>10</recv>","")));
        assertEquals("UNKNOWN",PanFailoverChecks.sessionSync(active,passive.replace("session update","other")));
        assertEquals("UNKNOWN",PanFailoverChecks.sessionSync(active,result("<session-sync>in-sync</session-sync>")));
    }
    @Test void sessionSyncEvidenceIsFailClosedAndRetainsBothMembers() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        String active=syncXml("Enabled","1,234","0"),passive=syncXml("Disabled","0","1,234");
        var healthy=PanFailoverChecks.sessionSyncEvidence(active,passive);
        assertEquals("PASS",healthy.status());
        var facts=json.readTree(healthy.derived());
        assertEquals(true,facts.path("active").path("enabled").asBoolean());
        assertEquals(false,facts.path("passive").path("enabled").asBoolean());
        assertEquals(1234,facts.path("active").path("messages").path("session setup").path("sent").asLong());
        assertEquals(1234,facts.path("passive").path("messages").path("session update").path("recv").asLong());
        var disabled=PanFailoverChecks.sessionSyncEvidence(active.replace("Enabled","false"),passive);
        assertEquals("FAIL",disabled.status());
        assertEquals(false,json.readTree(disabled.derived()).path("active").path("enabled").asBoolean());
        assertEquals("UNKNOWN",PanFailoverChecks.sessionSyncEvidence("","").status());
        assertEquals("UNKNOWN",PanFailoverChecks.sessionSyncEvidence(result(""),result("")).status());
        for(String tag:new String[]{"enabled","messages","entry","enable","sent","recv","desc"})
            for(boolean first:new boolean[]{true,false}) {
                String incomplete=(first?active:passive).replaceAll("<"+tag+">.*?</"+tag+">","");
                var unknown=PanFailoverChecks.sessionSyncEvidence(first?incomplete:active,first?passive:incomplete);
                assertEquals("UNKNOWN",unknown.status(),tag);
                org.junit.jupiter.api.Assertions.assertTrue(json.readTree(unknown.derived()).path("missing").size()>0,tag);
            }
        for(String value:new String[]{"yes","true","Enabled","YES","TrUe","ENABLED"})
            assertEquals("PASS",PanFailoverChecks.sessionSync(active.replace("Enabled",value),passive));
        for(String value:new String[]{"no","false","Disabled","NO","FaLsE","DISABLED"})
            assertEquals("FAIL",PanFailoverChecks.sessionSync(active.replace("Enabled",value),passive));
        for(String value:new String[]{"bad99","1,23","-1","9223372036854775808",""}) {
            var unknown=PanFailoverChecks.sessionSyncEvidence(active.replace("1,234",value),passive);
            assertEquals("UNKNOWN",unknown.status());
            org.junit.jupiter.api.Assertions.assertTrue(json.readTree(unknown.derived()).path("unrecognised").size()>0);
            org.junit.jupiter.api.Assertions.assertFalse(unknown.derived().contains("bad99"));
        }
        var unknownEnabled=PanFailoverChecks.sessionSyncEvidence(active.replace("Enabled","pending99"),passive);
        assertEquals("UNKNOWN",unknownEnabled.status());
        org.junit.jupiter.api.Assertions.assertTrue(json.readTree(unknownEnabled.derived())
            .path("unrecognised").get(0).asText().equals("active.enabled:aaaaaaa99"));
        assertEquals("UNKNOWN",PanFailoverChecks.sessionSync(active,passive.replace("Disabled","pending99")));
        assertEquals("UNKNOWN",PanFailoverChecks.sessionSync(active.replace("Enabled","no"),passive.replace("<recv>1,234</recv>","")));
    }
    public static void main(String[] args) {
        var test=new PanFailoverChecksTest(); test.pairPassFailAndUnknown(); test.activeActiveRefused(); test.addedChecksPassFailUnknown();
    }
}
