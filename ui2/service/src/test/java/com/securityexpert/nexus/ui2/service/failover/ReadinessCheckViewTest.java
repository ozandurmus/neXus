package com.securityexpert.nexus.ui2.service.failover;

import java.time.Instant;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReadinessCheckViewTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Instant OBSERVED=Instant.parse("2026-09-30T12:00:00Z");
    private static Map<String,Object> row(boolean pan,int no,String status,String derived) throws Exception {
        return ReadinessCheckView.fields(pan?"palo_alto":"check_point",no,"Member 1",status,JSON.readTree(derived),OBSERVED);
    }
    @Test void cpDerivedFixturesHaveReadableSentences() throws Exception {
        Object[][] cases={
            {1,"{\"role\":\"ACTIVE\"}","Active"},
            {2,"{\"entries\":4}","4 cluster IP entries, same on both members"},
            {3,"{\"up\":3,\"required\":3}","3 of 3 required interfaces up"},
            {5,"{\"count\":4}","4 ARP entries"},
            {6,"{\"count\":100,\"peak\":150}","100 connections, 150 peak"},
            {8,"{\"bytesPerSecond\":400}","400 bytes/s"},
            {9,"{\"sentRejectNotifications\":4554}","Sync OK, 0 lost updates (4554 sent rejects)"},
            {10,"{\"installedAt\":\"10Sep2018 14:01:25\"}","Same policy on both members"},
            {11,"{\"pnotes\":[]}","No pnotes in problem state"},
            {12,"{\"noneConfigured\":true}","No bonds configured"},
            {13,"{\"lastFailoverAt\":\"2026-01-11T12:00:00Z\"}","Last failover 11 Jan 2026 (262 days before this check)"},
            {14,"{\"defaultRoute\":true,\"routeCount\":4}","Default route present, 4 routes"}
        };
        for (Object[] c:cases) {
            var view=row(false,(int)c[0],"PASS",(String)c[1]);
            assertEquals(c[2],view.get("summary"));
            assertEquals((int)c[0]!=5 && (int)c[0]!=13,view.get("blocking"));
            assertEquals("Member 1",view.get("member"));
            assertEquals("PASS",view.get("result"));
        }
    }
    @Test void panChecksAndUnknownReasonsAreReadable() throws Exception {
        String[] summaries={"Active","Reciprocal peer relationship verified","Required HA links up on both members",
            "Running configuration synchronized on both members","Sessions synchronized on both members",
            "100 active sessions","Software and content versions match on both members"};
        for (int no=1;no<=7;no++) {
            assertEquals(summaries[no-1],row(true,no,"PASS","{\"role\":\"active\",\"count\":100}").get("summary"));
            assertTrue((boolean)row(true,no,"UNKNOWN","{}").get("blocking"));
            assertFalse(row(true,no,"UNKNOWN","{}").get("summary").toString().contains("{"));
        }
        assertEquals("Interface table not recognised",row(false,3,"UNKNOWN","{\"up\":0}").get("summary"));
    }
    @Test void sensitiveDerivedStringsNeverEnterSummaries() throws Exception {
        String derived="{\"role\":\"SYNTHETIC-PRIVATE-NAME\",\"reason\":\"SYNTHETIC-PRIVATE-NAME\","
            +"\"policy\":\"SYNTHETIC-PRIVATE-NAME\",\"up\":\"SYNTHETIC-PRIVATE-NAME\"}";
        for (boolean pan:new boolean[]{false,true}) for (int no=1;no<=14;no++)
            for (String status:new String[]{"PASS","FAIL","WARN","UNKNOWN"})
                assertFalse(row(pan,no,status,derived).get("summary").toString().contains("SYNTHETIC-PRIVATE-NAME"));
    }
}
