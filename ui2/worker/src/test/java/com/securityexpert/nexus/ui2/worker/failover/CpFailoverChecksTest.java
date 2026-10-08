package com.securityexpert.nexus.ui2.worker.failover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.worker.inventory.Fixtures;

class CpFailoverChecksTest {
    private static final String ACTIVE = "Cluster Mode: High Availability (Active Up)\n"
        + "Number Unique Address Assigned Load State\n"
        + "1 (local) 192.0.2.11 100% Active\n2 192.0.2.12 0% Standby\n";
    private static final String STANDBY = "Cluster Mode: High Availability (Active Up)\n"
        + "Number Unique Address Assigned Load State\n"
        + "1 192.0.2.11 100% Active\n2 (local) 192.0.2.12 0% Standby\n";
    private static final String TABLE = "---- Unique IP's Table ----\nMember Interface IP-Address\n"
        + "(Local)\n0 1 192.0.2.21\n1 1 192.0.2.22\n";
    private static final String IF = "CCP mode: Automatic\nRequired interfaces: 2\n"
        + "Interface Name: Status:\neth0 UP non sync\neth1 UP sync\n";
    private static final String SYNC = "Delta Sync Statistics\nSync status: OK\nDrops:\n"
        + "Lost updates................................. 0\nLost bulk update events...................... 0\n";
    private static final String POLICY = "HOST POLICY DATE\nlocalhost Sample_Policy 10Sep2018 14:01:25 : [>eth0]\n";
    private static final String DEV_A = "Inter-| Receive | Transmit\n face |bytes packets errs drop fifo frame compressed multicast|bytes packets errs drop fifo colls carrier compressed\n"
        + "eth0: 100 0 0 0 0 0 0 0 200 0 0 0 0 0 0 0\n"
        + "eth1: 300 0 0 0 0 0 0 0 400 0 0 0 0 0 0 0\n";
    private static final String DEV_B = DEV_A.replace("eth0: 100", "eth0: 600").replace("eth0: 600 0 0 0 0 0 0 0 200", "eth0: 600 0 0 0 0 0 0 0 700")
        .replace("eth1: 300", "eth1: 800").replace("eth1: 800 0 0 0 0 0 0 0 400", "eth1: 800 0 0 0 0 0 0 0 900");
    private static void check(boolean condition) { if(!condition) throw new AssertionError(); }

    private static CpFailoverChecks.TrafficSample sample(Map<String,CpFailoverChecks.TrafficBytes> counters) {
        return new CpFailoverChecks.TrafficSample(counters,CpFailoverChecks.TrafficReason.NONE);
    }

    @Test void approvedChecksAndVsContext() {
        check(CpFailoverChecks.corroborated(CpFailoverChecks.state(ACTIVE),CpFailoverChecks.state(STANDBY)));
        check(!CpFailoverChecks.corroborated(CpFailoverChecks.state(ACTIVE),CpFailoverChecks.state(ACTIVE)));
        check(CpFailoverChecks.ipTable(TABLE).size()==2);
        check(CpFailoverChecks.ipTable(TABLE).equals(CpFailoverChecks.ipTable(TABLE.replace("(Local)",""))));
        check(CpFailoverChecks.interfaces(IF).healthy());
        check(CpFailoverChecks.interfaces(IF).trafficNames().equals(Set.of("eth0")));
        check(!CpFailoverChecks.interfaces(IF.replace("eth1 UP", "eth1 DOWN")).healthy());
        check(CpFailoverChecks.arpCount("? (192.0.2.31) at 02:00:00:00:00:01 [ether] on eth0\n")==1);
        check(CpFailoverChecks.trafficBytesPerSecond(CpFailoverChecks.bytesByInterface(DEV_A),
            CpFailoverChecks.bytesByInterface(DEV_B),Set.of("eth0","eth1"),5_000_000_000L).bytesPerSecond()==400);
        // The same strict projections apply to a VS after its command is wrapped by vsenv.
        check(CpFailoverChecks.state(ACTIVE).localRole().equals("ACTIVE"));
    }
    @Test void unrecognisedMeansUnknown() {
        check(CpFailoverChecks.state("unsupported").localRole().equals("UNKNOWN"));
        check(CpFailoverChecks.ipTable("unsupported").isEmpty());
        check(!CpFailoverChecks.interfaces("unsupported").healthy());
        check(CpFailoverChecks.arpCount("unsupported")==-1);
        check(CpFailoverChecks.bytesByInterface("unsupported").reason()==CpFailoverChecks.TrafficReason.EMPTY_SAMPLE);
        check(CpFailoverChecks.trafficBytesPerSecond(sample(Map.of()),sample(Map.of()),Set.of("eth0"),5_000_000_000L).bytesPerSecond()==-1);
    }
    @Test void inventoryStyleHaAndVsFixturesKeepOpaqueIdsAndCorroborate() {
        var plain=CpFailoverChecks.state(Fixtures.read("cp/cphaprob_stat_r8120_ha.txt"));
        var vs=CpFailoverChecks.state(Fixtures.read("cp/cphaprob_stat_vsx_vs.txt"));
        check(plain.localId().equals("01") && plain.localRole().equals("ACTIVE"));
        check(vs.localId().equals("02") && vs.localRole().equals("STANDBY"));
        check(CpFailoverChecks.corroborated(plain,vs));
        check(CpFailoverChecks.state(Fixtures.read("cp/cphaprob_stat.txt")).pair());
        check(!CpFailoverChecks.state(Fixtures.read("cp/cphaprob_stat_vsls.txt")).pair());
    }
    @Test void readyIsRecognizedButCannotFormAReadyPair() {
        var ready=CpFailoverChecks.state(Fixtures.read("cp/cphaprob_stat_vsx_vs.txt").replace("STANDBY","READY"));
        check(ready.mode().equals("HA") && ready.localRole().equals("READY"));
        check(!ready.pair());
    }
    @Test void unrecognizedRowsAndAmbiguousLocalMarkersStayUnknown() {
        String fixture=Fixtures.read("cp/cphaprob_stat_vsx_vs.txt");
        for (String text:new String[]{null,"Cluster Mode: High Availability\nunsupported",
                fixture.replace("STANDBY","UNRECOGNIZED"),fixture.replace("(local)",""),
                fixture.replace("gw-a","(local) gw-a"),fixture.replace("02  ","01  ")}) {
            check(CpFailoverChecks.state(text).mode().equals("UNKNOWN"));
        }
        for (String row:new String[]{"(local) 1 192.0.2.11 100% Active", "1 192.0.2.11 (local) 100% Active",
                "1 192.0.2.11 100% Active (local)"}) {
            check(CpFailoverChecks.state("CLUSTER MODE: HIGH AVAILABILITY\n"+row
                +"\n2 192.0.2.12 0% STANDBY").pair());
        }
    }
    @Test void vslsVocabularyPreservesUnhealthyEvidenceAndBackupRole() {
        String fixture="Cluster Mode: Virtual System Load Sharing (Active Up)\n"
            +"ID Unique Address Assigned Load State Name\n"
            +"01 192.0.2.11 100% ACTIVE(!) FW-TEST-01\n"
            +"02 (local) 192.0.2.12 0% DOWN FW-TEST-02\nActive PNOTEs: synthetic\n";
        var unhealthy=CpFailoverChecks.state(fixture);
        check(unhealthy.mode().equals("VSLS") && !unhealthy.pair());
        check(CpFailoverChecks.stateEvidence(unhealthy).equals(Map.of(
            "mode","VSLS","role","DOWN","reason","UNSUPPORTED_MODE","local_state","DOWN","peer_state","ACTIVE(!)")));
        for(String token:new String[]{"ACTIVE(!)","ACTIVE","STANDBY","BACKUP","DOWN","READY","INIT","LOST","ACTIVE ATTENTION"}) {
            var state=CpFailoverChecks.state(fixture.replace("ACTIVE(!)","ACTIVE").replace("DOWN",token));
            check(!state.mode().equals("UNKNOWN"));
            check(state.members().get("02").equals(token));
            check(state.localRole().equals(token));
            check(!state.pair());
        }
        check(CpFailoverChecks.state(fixture.replace("DOWN","UNRECOGNIZED")).mode().equals("UNKNOWN"));
        check(CpFailoverChecks.state(fixture.replace("(local)","")).mode().equals("UNKNOWN"));
        var backup=CpFailoverChecks.state(fixture.replace("ACTIVE(!)","ACTIVE").replace("DOWN","BACKUP"));
        var active=CpFailoverChecks.state(fixture.replace("ACTIVE(!)","ACTIVE").replace("DOWN","BACKUP")
            .replace("02 (local)","02").replace("01 192", "01 (local) 192"));
        check(!CpFailoverChecks.corroborated(backup,active));
    }
    @Test void reciprocalClaimsFailClosedWithoutNormalizingOpaqueIds() {
        var a=CpFailoverChecks.state(ACTIVE);
        assertEquals("PASS",CpFailoverChecks.reciprocal(a,CpFailoverChecks.state(STANDBY)),
            "Coherent reciprocal member observations");
        for(var scenario:Map.of("opposed peer role",STANDBY.replace("% Active","% Down"),
                "different opaque local ID",STANDBY.replace("2 (local)","02 (local)"),
                "same local observer",ACTIVE).entrySet()) {
            var b=CpFailoverChecks.state(scenario.getValue());
            assertEquals("HA",b.mode(),"Fixture must preserve HA mode: "+scenario.getKey());
            assertEquals("FAIL",CpFailoverChecks.reciprocal(a,b),"Reciprocal check: "+scenario.getKey());
        }
        var padded=CpFailoverChecks.state(STANDBY.replace("2 (local)","02 (local)"));
        assertEquals("02",padded.localId(),"Opaque local ID must retain its leading zero");
        assertEquals("PASS",CpFailoverChecks.reciprocal(CpFailoverChecks.state(ACTIVE.replace("\n2 ","\n02 ")),padded),
            "Exactly matching opaque IDs must corroborate");
        assertEquals("UNKNOWN",CpFailoverChecks.reciprocal(a,CpFailoverChecks.state(STANDBY.replace("(local)",""))),
            "Missing local observation must fail closed");
        assertFalse(CpFailoverChecks.corroborated(a,CpFailoverChecks.state(STANDBY.replace("Standby","Active"))),
            "Conflicting active roles must not corroborate");
        assertFalse(CpFailoverChecks.corroborated(a,CpFailoverChecks.state("Cluster Mode: High Availability\n2 (local) 192.0.2.12 Standby")),
            "Incomplete member table must not corroborate");
    }
    @Test void onlyReportedHaModeIsAdmittedRegardlessOfAssignedLoad() {
        for(String mode:new String[]{"Virtual System Load Sharing","Load Sharing Unicast","Load Sharing Multicast",
                "VRRP","unknown","High Availability unexpected"}) {
            var a=CpFailoverChecks.state(ACTIVE.replace("High Availability",mode));
            var b=CpFailoverChecks.state(STANDBY.replace("High Availability",mode));
            check(!CpFailoverChecks.corroborated(a,b));
            assertEquals("UNKNOWN".equals(a.mode())?"UNRECOGNIZED_STATE":"UNSUPPORTED_MODE",
                CpFailoverChecks.stateEvidence(a).get("reason"));
        }
        check(!CpFailoverChecks.state(ACTIVE+"Cluster Mode: Virtual System Load Sharing\n").pair());
    }
    @Test void vsContextIsOpaqueAndChassisTablesAreNotVsEvidence() {
        String vs=Fixtures.read("cp/cphaprob_stat_vsx_vs.txt");
        check(CpFailoverChecks.state(vs,"12").pair());
        for(String expected:new String[]{null,"012","13"}) check(!CpFailoverChecks.state(vs,expected).pair());
        check(!CpFailoverChecks.state("Context is set to Virtual Device VS-SYNTHETIC (ID 012).\n"+ACTIVE,"12").pair());
        check(!CpFailoverChecks.state(ACTIVE+"Virtual Devices Status on each Cluster Member\n","12").pair());
    }

    @Test void vslsLabelRequiresMatchingOpaqueVsContextAndContractStandbyRole() {
        String a=ACTIVE.replace("High Availability","Virtual System Load Sharing");
        String b=STANDBY.replace("High Availability","Virtual System Load Sharing");
        var first=CpFailoverChecks.state(a,"012");
        var second=CpFailoverChecks.state(b,"012");
        check(CpFailoverChecks.corroborated(first,second));
        assertEquals("012",first.vsId());
        assertEquals("VSLS",first.mode());
        assertEquals("OBSERVED",CpFailoverChecks.stateEvidence(second).get("reason"));
        assertEquals("STANDBY",CpFailoverChecks.stateEvidence(second).get("role"));
        for(String context:new String[]{null,"12","13",""})
            assertFalse(CpFailoverChecks.corroborated(first,CpFailoverChecks.state(b,context)));
        for(String role:new String[]{"BACKUP","ACTIVE","DOWN","READY","INIT","LOST","ACTIVE(!)"}) {
            var other=CpFailoverChecks.state(b.replace("Standby",role),"012");
            assertFalse(other.pair(),"Frozen CP contract section 10 requires exactly Active + Standby");
            assertEquals(role,CpFailoverChecks.stateEvidence(other).get("role"));
        }
        assertFalse(CpFailoverChecks.state(a+"Virtual Devices Status on each Cluster Member\n","012").pair());
        assertFalse(CpFailoverChecks.state("Context is set to Virtual Device VS-SYNTHETIC (ID 12).\n"+a,"012").pair());
        assertFalse(CpFailoverChecks.state(a+"3 192.0.2.13 0% Standby\n","012").pair());
    }

    @Test void arpTablesAcceptVsPreambleAndZeroEntriesButRejectErrors() {
        String preamble="Context is set to Virtual Device FW-TEST-01 (ID 01).\n";
        String entry="? (192.0.2.31) at 02:00:00:00:00:01 [ether] on eth0\n";
        check(CpFailoverChecks.arpCount(preamble+entry)==1);
        check(CpFailoverChecks.arpCount(preamble+entry+entry)==2);
        check(CpFailoverChecks.arpCount(preamble)==0);
        check(CpFailoverChecks.arpCount("")==0);
        for(String error:new String[]{"Permission denied","arp: command not found","invalid output"})
            check(CpFailoverChecks.arpCount(preamble+error)==-1);
        check(CpFailoverChecks.arpCount(entry+preamble)==-1);
    }

    @Test void syncAndPolicyPassFailUnknown() {
        check(CpFailoverChecks.syncStatus(SYNC).equals("PASS"));
        check(CpFailoverChecks.syncStatus(SYNC.replace("Sync status: OK", "Sync status: Off - Full-sync failure")).equals("FAIL"));
        check(CpFailoverChecks.syncStatus(SYNC.replace("Lost updates................................. 0", "Lost updates................................. 1")).equals("FAIL"));
        check(CpFailoverChecks.syncStatus(SYNC.replace("Lost bulk update events...................... 0", "Lost bulk update events...................... 2")).equals("FAIL"));
        check(CpFailoverChecks.syncStatus(SYNC+"Unsynchronized updates...................... 1\n").equals("FAIL"));
        check(CpFailoverChecks.syncStatus(SYNC.replace("Sync status: OK", "Sync status: strange")).equals("UNKNOWN"));
        check(CpFailoverChecks.syncStatus("unsupported").equals("UNKNOWN"));
        check(CpFailoverChecks.policy(POLICY).status().equals("PASS"));
        check(CpFailoverChecks.policy(POLICY).name().equals("Sample_Policy"));
        check(CpFailoverChecks.policy(POLICY).installedAt().equals("10Sep2018 14:01:25"));
        check(CpFailoverChecks.policy("HOST POLICY DATE\n").status().equals("FAIL"));
        check(CpFailoverChecks.policy("unsupported").status().equals("UNKNOWN"));
    }
    @Test void syncDetailsPreserveVerdictsAndOmitMissingCounters() {
        for(String state:new String[]{"OK","Off - Full-sync failure","Fullsync in progress"}) {
            String output=SYNC.replace("Sync status: OK","Sync status: "+state);
            var derived=CpFailoverChecks.syncInformation(output);
            check(state.equals(derived.get("syncStatus")));
            check(((Number)derived.get("lostUpdates")).longValue()==0);
            check(((Number)derived.get("lostBulkUpdateEvents")).longValue()==0);
            check(!derived.containsKey("unsynchronizedUpdates"));
            check(CpFailoverChecks.syncStatus(output).equals(state.equals("OK")?"PASS":"FAIL"));
        }
        String bulk=SYNC.replace("Lost bulk update events...................... 0",
            "Lost bulk update events...................... 12");
        check(CpFailoverChecks.syncStatus(bulk).equals("FAIL"));
        check(((Number)CpFailoverChecks.syncInformation(bulk).get("lostBulkUpdateEvents")).longValue()==12);
        check(((Number)CpFailoverChecks.syncInformation(SYNC+"Unsynchronized updates.... 3\n")
            .get("unsynchronizedUpdates")).longValue()==3);
        check(CpFailoverChecks.syncInformation("Sync status: Off - Full-sync failure\n")
            .equals(Map.of("syncStatus","Off - Full-sync failure")));
        check(CpFailoverChecks.syncInformation(null).isEmpty());
        check(!CpFailoverChecks.syncInformation(SYNC+"Sync status: OK\n").containsKey("syncStatus"));
        check(!CpFailoverChecks.syncInformation(SYNC+"Lost updates.... 2\n").containsKey("lostUpdates"));
    }

    @Test void measuredHaChassisAndVsShapes() {
        for(String shape:new String[]{"ha","vsx_chassis","vsx_vs"}) {
            String output=Fixtures.read("cp/failover_stat_"+shape+".txt");
            var state=CpFailoverChecks.state(output);
            check(state.pair()==shape.equals("ha") && state.members().size()==2);
            check(CpFailoverChecks.corroborated(state,CpFailoverChecks.state(output
                .replace("1 (local)","1").replace("2          ","2 (local) ")))==shape.equals("ha"));
            check(CpFailoverChecks.policy(Fixtures.read("cp/failover_policy_"+shape+".txt")).status().equals("PASS"));
            String interfaces=Fixtures.read("cp/failover_interfaces_"+shape+".txt");
            var parsed=CpFailoverChecks.interfaces(interfaces);
            check(parsed.healthy() && parsed.names().size()==4);
            assertEquals(Set.of("Mgmt","eth2-01","bond1.3843"),parsed.trafficNames(),
                "Measured non-sync interface classification: "+shape);
            check(!CpFailoverChecks.interfaces(interfaces.replace("(LS) UP","(LS) DOWN")).healthy());
            String sync=Fixtures.read("cp/failover_sync_"+shape+".txt");
            check(CpFailoverChecks.syncStatus(sync).equals("PASS"));
            check(((Number)CpFailoverChecks.syncInformation(sync).get("sentRejectNotifications")).longValue()== (shape.equals("vsx_vs")?4554:0));
        }
        check(CpFailoverChecks.policy(Fixtures.read("cp/failover_policy_vsx_chassis.txt")).installedAt()
            .equals("18Sep2026 0:44:41"));
        String interfaces=Fixtures.read("cp/failover_interfaces_vsx_vs.txt")
            .replace("Required interfaces: 4\n", "").replace("CCP mode: Automatic\n", "");
        check(CpFailoverChecks.interfaces(interfaces).healthy());
        check(!CpFailoverChecks.interfaces(interfaces.replace("(LS) UP","(LS) UNKNOWN")).healthy());
    }
    @Test void paddedPtyInterfacesAcceptFlagsLegendAndRequiredCount() {
        for(String shape:new String[]{"ha","vsx_vs"}) {
            String fixture=Fixtures.read("cp/failover_interfaces_"+shape+"_pty.txt");
            check(fixture.contains(" \r\n"));
            var parsed=CpFailoverChecks.interfaces(fixture);
            check(parsed.healthy() && parsed.required()==2);
            check(CpFailoverChecks.interfaces(fixture.replace("Manual (Unicast)","Automatic")).healthy());
            check(parsed.names().equals(Set.of("Sync","bond1.3843")));
            check(parsed.trafficNames().equals(Set.of("bond1.3843")));
            check(!CpFailoverChecks.interfaces(fixture.replace("Required interfaces: 2","Required interfaces: 3")).healthy());
            check(!CpFailoverChecks.interfaces(fixture.replace("UP","DOWN")).healthy());
            check(!CpFailoverChecks.interfaces(fixture.replace("(LS)","(INVALID)")).ccpPresent());
            for(String flags:new String[]{"S, LS","HA","LS","LM","P","HA, LM, P"}) {
                var combined=CpFailoverChecks.interfaces(fixture.replace("(LS)","("+flags+")"));
                check(combined.healthy());
                check(combined.trafficNames().contains("bond1.3843")==!flags.startsWith("S,"));
            }
        }
    }

    @Test void loginWarningLayoutParsesHealthyLfAndCrlfBodies() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String,String> fixture=json.readValue(Fixtures.read("cp/readiness_login_warning.json"),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>() {});
        for(String eol:java.util.List.of("\n","\r\n")) {
            String interfaces=fixture.get("cphaprob -a if").replace("\n",eol);
            var parsed=CpFailoverChecks.interfaces(interfaces);
            check(parsed.ccpPresent() && parsed.healthy() && parsed.required()==7);
            check(parsed.names().equals(Set.of("eth1-01","eth1-02","Sync","eth1-01.1234",
                "eth1-01.1235","eth1-01.1236","eth1-01.1237")));
            check(parsed.trafficNames().size()==5 && !parsed.trafficNames().contains("Sync"));
            check(!CpFailoverChecks.interfaces(interfaces.replace("Sync (S)             UP", "Sync (S)             DOWN")).healthy());
            check(!CpFailoverChecks.interfaces(interfaces.replace("eth1-01.1237         UP", "truncated row")).ccpPresent());
            check(CpFailoverChecks.arpCount(fixture.get("arp -an").replace("\n",eol))==1);
            var bytes=CpFailoverChecks.bytesByInterface(fixture.get("cat /proc/net/dev").replace("\n",eol));
            check(bytes.counters().keySet().equals(parsed.names()) && bytes.counters().values().stream().allMatch(v -> v.received()+v.transmitted()==3000L));
            check(CpFailoverChecks.trafficBytesPerSecond(bytes,bytes,parsed.trafficNames(),5_000_000_000L).bytesPerSecond()==0);
            check(CpFailoverChecks.policy(fixture.get("fw stat").replace("\n",eol)).status().equals("PASS"));
            check(CpFailoverChecks.pnotes(fixture.get("cphaprob -ia list").replace("\n",eol)).status().equals("PASS"));
            check(CpFailoverChecks.bonds(fixture.get("cphaprob show_bond").replace("\n",eol)).equals("PASS"));
        }
    }

    @Test void measuredTablesIgnoreMacButPreserveMemberAndInterfaceMappings() {
        String table=Fixtures.read("cp/failover_tablestat.txt");
        var rows=CpFailoverChecks.ipTable(table);
        check(rows.size()==4 && CpFailoverChecks.twoTableMembers(rows));
        check(rows.equals(CpFailoverChecks.ipTable(table.replace("(Local)\n", "")
            .replace("1  4", "1 (Local) 4"))));
        check(rows.equals(CpFailoverChecks.ipTable(table.replace("(Local)\n", "")
            .replace("0  4", "(Local) 0 4").replace("\n", "   \r\n"))));
        check(rows.equals(CpFailoverChecks.ipTable(table.replace("(Local)\n", "")
            .replace("1  4", "(Local)\n1  4").replace("00:00:5e:00:53:06","00:00:5e:00:53:16"))));
        check(!rows.equals(CpFailoverChecks.ipTable(table.replace("0  4", "1  4").replace("1  5", "0  5"))));
        check(!rows.equals(CpFailoverChecks.ipTable(table.replace("192.0.2.95", "192.0.2.96"))));
        check(CpFailoverChecks.ipTable(table+"0 4 192.0.2.99\n").size()==5);
        check(CpFailoverChecks.ipTable(table+"0 6 malformed\n").isEmpty());
        check(CpFailoverChecks.bytesByInterface(Fixtures.read("cp/failover_net_dev.txt")).counters().get("gretap0").equals(new CpFailoverChecks.TrafficBytes(9000,10000)));
    }
    @Test void approvedCriticalDeviceAndBondShapes() {
        check(CpFailoverChecks.pnotes(Fixtures.read("cp/failover_pnotes_ok.txt")).status().equals("PASS"));
        var problem=CpFailoverChecks.pnotes(Fixtures.read("cp/failover_pnotes_problem.txt"));
        check(problem.status().equals("FAIL") && problem.names().equals(java.util.List.of("Interface Active Check","fwd")));
        check(CpFailoverChecks.pnotes("Device Name: FW-ALPHA-01\nCurrent state: problem").names()
            .equals(java.util.List.of("CUSTOM_PNOTE_1")));
        check(CpFailoverChecks.pnotes("unsupported").status().equals("UNKNOWN"));
        check(CpFailoverChecks.bonds(Fixtures.read("cp/failover_bonds_none.txt")).equals("PASS"));
        String bonds=Fixtures.read("cp/failover_bonds.txt");
        check(CpFailoverChecks.bonds(bonds).equals("PASS"));
        check(CpFailoverChecks.bonds(bonds.replace("|UP    |", "|UP!   |")).equals("FAIL"));
        check(CpFailoverChecks.bonds(bonds.replace("|UP    |", "|DOWN  |")).equals("FAIL"));
        check(CpFailoverChecks.bonds(bonds.replace("|2       |1", "|0       |1")).equals("FAIL"));
        check(CpFailoverChecks.bonds(bonds.replace("|2       |1", "|bad     |1")).equals("UNKNOWN"));
        check(CpFailoverChecks.bonds("unsupported").equals("UNKNOWN"));
    }
    @Test void failoverRecencyBoundariesAndRoutingProjection() {
        String event=Fixtures.read("cp/failover_last_event.txt");
        var time=java.time.Instant.parse("2026-09-30T09:00:00Z");
        check(CpFailoverChecks.lastFailover(event,time).status().equals("WARN"));
        check(CpFailoverChecks.lastFailover(event,time.plusSeconds(21599)).status().equals("WARN"));
        check(CpFailoverChecks.lastFailover(event,time.plusSeconds(21600)).status().equals("PASS"));
        check(CpFailoverChecks.lastFailover(event,time.minusSeconds(1)).status().equals("UNKNOWN"));
        check(CpFailoverChecks.lastFailover("unsupported",time).status().equals("UNKNOWN"));
        String routes=Fixtures.read("cp/failover_routing.txt");
        check(CpFailoverChecks.routing(routes).equals(new CpFailoverChecks.Routing(2,true)));
        check(CpFailoverChecks.routing(routes.replace("|0.0.0.0|0.0.0.0|", "|192.0.2.0|255.255.255.0|")).equals(
            new CpFailoverChecks.Routing(2,false)));
        check(CpFailoverChecks.routing("unsupported")==null);
        check(CpFailoverChecks.routing(routes.replace("192.0.2.1", "999.0.2.1"))==null);
        check(CpFailoverChecks.routing(routes+"truncated route row")==null);
    }
    @Test void readinessCounterDeltasDoNotFailOnCumulativeLosses() {
        String measured=SYNC.replace("Lost updates................................. 0","Lost updates................................. 853745");
        var first=CpFailoverChecks.readinessSync(measured,Map.of(),null);
        check(first.status().equals("PASS") && Boolean.TRUE.equals(first.derived().get("baselineRecorded")));
        var at=java.time.Instant.parse("2026-09-30T12:00:00Z");
        check(CpFailoverChecks.readinessSync(measured,first.derived(),at).status().equals("PASS"));
        var increased=CpFailoverChecks.readinessSync(measured.replace("853745","853747"),first.derived(),at);
        check(increased.status().equals("FAIL") && increased.derived().get("lostUpdatesIncrease").toString().equals("2"));
        var reset=CpFailoverChecks.readinessSync(SYNC,first.derived(),at);
        check(reset.status().equals("PASS") && Boolean.TRUE.equals(reset.derived().get("counterReset")));
        check(CpFailoverChecks.readinessSync("unsupported",first.derived(),at).status().equals("UNKNOWN"));
        check(CpFailoverChecks.readinessSync(measured.replace("Sync status: OK","Sync status: Off - disabled"),
            first.derived(),at).status().equals("FAIL"));
    }
    @Test void measuredTableOrderingAndLocalMarkerDoNotChangeEquality() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        for(String shape:java.util.List.of("vs0","vs1","gateway")) {
            String table=json.readTree(Fixtures.read("cp/readiness_measured_"+shape+".json"))
                .path("cphaprob tablestat").asText();
            var rows=new java.util.ArrayList<>(table.lines().filter(l -> l.strip().matches("[01]\\s+.*")).toList());
            java.util.Collections.reverse(rows);
            String peer="---- Unique IP's Table ----\n"+String.join("\n",rows)+"\n(Local)\n";
            check(CpFailoverChecks.twoTableMembers(CpFailoverChecks.ipTable(table)));
            check(CpFailoverChecks.ipTable(table).equals(CpFailoverChecks.ipTable(peer)));
            check(CpFailoverChecks.tableDifference(CpFailoverChecks.ipTable(table),CpFailoverChecks.ipTable(peer))
                .get("differences").equals(java.util.List.of()));
        }
    }
    @Test void policySkewBoundariesAndFailureReasons() {
        var a=new CpFailoverChecks.Policy("PASS","Sample_Policy","30Sep2026 23:55:00");
        for(int seconds:new int[]{0,1,599,600,601,7200}) {
            String time=java.time.LocalDateTime.of(2026,9,30,23,55).plusSeconds(seconds)
                .format(java.time.format.DateTimeFormatter.ofPattern("dMMMuuuu H:mm:ss",java.util.Locale.ENGLISH));
            var b=new CpFailoverChecks.Policy("PASS","Sample_Policy",time);
            for(var pair:java.util.List.of(java.util.List.of(a,b),java.util.List.of(b,a))) {
                var result=CpFailoverChecks.policyParity(pair.get(0),pair.get(1),null,null);
                check(result.status().equals(seconds<=600?"PASS":"FAIL"));
                check(result.derived().get("installSkewSeconds").equals((long)seconds));
                check(result.derived().get("firstInstalledAt").equals(pair.get(0).installedAt()));
                check(result.derived().get("secondInstalledAt").equals(pair.get(1).installedAt()));
                check(!result.derived().toString().contains("Sample_Policy"));
            }
        }
        check(CpFailoverChecks.policyParity(a,new CpFailoverChecks.Policy("PASS","Other_Policy",a.installedAt()),null,null)
            .derived().get("reason").equals("POLICY_NAMES_DIFFER"));
        check(CpFailoverChecks.policyParity(a,a,"Other_Policy",a.name()).derived().get("reason").equals("POLICY_CHANGED"));
        check(CpFailoverChecks.policyParity(a,new CpFailoverChecks.Policy("FAIL",null,null),null,null).status().equals("FAIL"));
        check(CpFailoverChecks.policyParity(a,new CpFailoverChecks.Policy("UNKNOWN",null,null),null,null).status().equals("UNKNOWN"));
        for(String invalid:new String[]{null,"31Sep2026 12:00:00","01Oct2026 24:00:00","bad"})
            check(CpFailoverChecks.policyParity(a,new CpFailoverChecks.Policy("PASS",a.name(),invalid),null,null)
                .status().equals("UNKNOWN"));
        var singleHour=new CpFailoverChecks.Policy("PASS",a.name(),"1Oct2026 0:01:00");
        check(CpFailoverChecks.policyParity(a,singleHour,null,null).status().equals("PASS"));
    }
    @Test void sessionContinuityIsNeverPass() {
        assertEquals("NOT_EVALUATED",CpFailoverChecks.sessionContinuity().status());
        assertEquals(Map.of("reason","SESSION_CONTINUITY_NOT_EVALUATED"),CpFailoverChecks.sessionContinuity().derived());
    }
    @Test void trafficUsesMeasuredElapsedAndRejectsMissingResetOrZeroBaseline() {
        var before=sample(Map.of("eth0",new CpFailoverChecks.TrafficBytes(100,100)));
        var after=sample(Map.of("eth0",new CpFailoverChecks.TrafficBytes(1100,100)));
        assertEquals(100.0,CpFailoverChecks.trafficBytesPerSecond(before,after,Set.of("eth0"),10_000_000_000L).bytesPerSecond());
        for(long elapsed:new long[]{0,-1})
            assertEquals(-1.0,CpFailoverChecks.trafficBytesPerSecond(before,after,Set.of("eth0"),elapsed).bytesPerSecond());
        assertEquals(-1.0,CpFailoverChecks.trafficBytesPerSecond(after,before,Set.of("eth0"),5_000_000_000L).bytesPerSecond());
        assertEquals(-1.0,CpFailoverChecks.trafficBytesPerSecond(before,sample(Map.of()),Set.of("eth0"),5_000_000_000L).bytesPerSecond());
        assertEquals(-1.0,CpFailoverChecks.trafficBytesPerSecond(before,
            sample(Map.of("eth0",new CpFailoverChecks.TrafficBytes(50,10000))),Set.of("eth0"),5_000_000_000L).bytesPerSecond());
        assertEquals(-1.0,CpFailoverChecks.trafficBytesPerSecond(before,after,Set.of("bond1","eth0"),5_000_000_000L).bytesPerSecond());
        assertEquals("UNKNOWN",CpFailoverChecks.trafficStatus(0,100));
        assertEquals("UNKNOWN",CpFailoverChecks.trafficStatus(100,-1));
        assertEquals("FAIL",CpFailoverChecks.trafficStatus(100,0));
        assertEquals("FAIL",CpFailoverChecks.trafficStatus(100,49.99));
        assertEquals("PASS",CpFailoverChecks.trafficStatus(100,50));
    }
    @Test void trafficMeasurementExcludesNonForwardingAndOverlappingParents() {
        var selected=CpFailoverChecks.interfaces("CCP mode: Automatic\nRequired interfaces: 1\n"
            +"lo UP non sync\nMgmt UP non sync\nSync (S) UP\neth1 Non-Monitored\n"
            +"bond1 UP non sync\nbond1.10 UP non sync\n");
        assertEquals(Set.of("lo","Mgmt","bond1.10"),selected.trafficNames());
        var before=sample(Map.of("bond1.10",new CpFailoverChecks.TrafficBytes(100,100)));
        var after=sample(Map.of("bond1.10",new CpFailoverChecks.TrafficBytes(600,600)));
        assertEquals(200.0,CpFailoverChecks.trafficBytesPerSecond(before,after,selected.trafficNames(),5_000_000_000L).bytesPerSecond(),
            "Missing non-forwarding counters must neither block nor contribute to forwarding traffic");
        assertEquals(-1.0,CpFailoverChecks.trafficBytesPerSecond(before,after,Set.of("lo","Mgmt"),5_000_000_000L).bytesPerSecond(),
            "A selection with no forwarding interfaces must remain unknown");
    }
    private static CpFailoverChecks.TrafficMeasurement traffic(String before,String after,Set<String> selected) {
        return CpFailoverChecks.trafficBytesPerSecond(CpFailoverChecks.bytesByInterface(before),
            CpFailoverChecks.bytesByInterface(after),selected,5_000_000_000L);
    }
    @Test void trafficEmptySampleHasReasonAndPresenceCounts() {
        for(String empty:new String[]{null,"unsupported","Inter-| Receive | Transmit\n"}) {
            var result=traffic(empty,DEV_B,Set.of("eth0","eth1"));
            assertEquals(CpFailoverChecks.TrafficReason.EMPTY_SAMPLE,result.reason());
            assertEquals(-1.0,result.bytesPerSecond());
            assertEquals(2,result.selectedCount());
            assertEquals(0,result.presentBeforeCount());
            assertEquals(2,result.presentAfterCount());
            assertEquals(CpFailoverChecks.TrafficReason.EMPTY_SAMPLE,traffic(DEV_A,empty,Set.of("eth0")).reason());
        }
    }
    @Test void trafficDuplicateInterfaceIsNotAnEmptySample() {
        String duplicate=DEV_A+"eth0: 100 0 0 0 0 0 0 0 200 0 0 0 0 0 0 0\n";
        assertEquals(CpFailoverChecks.TrafficReason.DUPLICATE_INTERFACE,traffic(duplicate,DEV_B,Set.of("eth0")).reason());
        assertEquals(CpFailoverChecks.TrafficReason.DUPLICATE_INTERFACE,traffic(DEV_A,duplicate,Set.of("eth0")).reason());
    }
    @Test void trafficMalformedOrOutOfRangeCounterHasParseReason() {
        for(String invalid:new String[]{"invalid","9223372036854775808","-1"}) {
            String malformed=DEV_A.replace("eth0: 100","eth0: "+invalid);
            assertEquals(CpFailoverChecks.TrafficReason.PARSE_ERROR,traffic(malformed,DEV_B,Set.of("eth0")).reason());
            assertEquals(CpFailoverChecks.TrafficReason.PARSE_ERROR,traffic(DEV_A,malformed,Set.of("eth0")).reason());
        }
    }
    @Test void trafficEmptyOrFullyExcludedSelectionHasReason() {
        for(Set<String> selected:java.util.List.of(Set.<String>of(),Set.of("lo","Mgmt","management0"))) {
            var result=traffic(DEV_A,DEV_B,selected);
            assertEquals(CpFailoverChecks.TrafficReason.NO_TRAFFIC_INTERFACES,result.reason());
            assertEquals(0,result.selectedCount());
        }
    }
    @Test void bondSummaryCountsCannotProveMixedSelectionIsNonOverlapping() {
        String mixed="CCP mode: Automatic\nRequired interfaces: 3\n"
            +"bond1 UP non sync\neth1-01 UP non sync\neth1-02 UP non sync\n";
        var selected=CpFailoverChecks.interfaces(mixed).trafficNames();
        assertEquals(Set.of("bond1","eth1-01","eth1-02"),selected);
        assertEquals("PASS",CpFailoverChecks.bonds(Fixtures.read("cp/failover_bonds.txt")));
        String before=DEV_A.replace("eth0:","bond1:").replace("eth1:","eth1-01:")
            +"eth1-02: 300 0 0 0 0 0 0 0 400 0 0 0 0 0 0 0\n";
        String after=before.replace(": 100",": 600");
        var result=traffic(before,after,selected);
        assertEquals(CpFailoverChecks.TrafficReason.BOND_PHYSICAL_OVERLAP,result.reason());
        assertEquals(-1.0,result.bytesPerSecond());
        assertEquals(3,result.selectedCount());
        assertEquals(3,result.presentBeforeCount());
        assertEquals(3,result.presentAfterCount());
        assertEquals(100.0,traffic(before,after,Set.of("bond1")).bytesPerSecond(),
            "Changing only the interface selection reproduces PASS versus UNKNOWN");
    }
    @Test void bondMembershipRequiresCompleteMatchingUniqueRows() {
        String detail=Fixtures.read("cp/failover_bond_members.txt");
        for(String eol:java.util.List.of("\n","\r\n"))
            assertEquals(Set.of("eth7","eth8"),CpFailoverChecks.bondSlaves(detail.replace("\n",eol),"bond1",2));
        for(String bad:java.util.List.of(detail.replace("bond1","bond2"),detail.replace("eth8","eth7"),
                detail.substring(0,detail.indexOf("eth8")),detail+"unexpected\n",
                detail.replace("Configured slave interfaces: 2","Configured slave interfaces: 3")))
            assertEquals(Set.of(),CpFailoverChecks.bondSlaves(bad,"bond1",2));
        assertEquals(java.util.Map.of("bond1",2),CpFailoverChecks.bondCounts(Fixtures.read("cp/failover_bonds.txt")));
        assertEquals(java.util.Map.of(),CpFailoverChecks.bondCounts(
            Fixtures.read("cp/failover_bonds.txt").replace("bond1 ","bond1;bad ")));
    }
    @Test void provenSlaveIsDroppedAndIndependentInterfaceIsSummed() {
        var before=CpFailoverChecks.bytesByInterface(DEV_A.replace("eth0:","bond1:").replace("eth1:","eth7:"));
        var after=CpFailoverChecks.bytesByInterface(DEV_B.replace("eth0:","bond1:").replace("eth1:","eth7:"));
        var members=CpFailoverChecks.bondSlaves(Fixtures.read("cp/failover_bond_members.txt"),"bond1",2);
        var selected=Set.of("bond1","eth7");
        var slave=CpFailoverChecks.trafficBytesPerSecond(before,after,selected,5_000_000_000L,java.util.Map.of("bond1",members));
        assertEquals(CpFailoverChecks.TrafficReason.NONE,slave.reason());
        assertEquals(1,slave.selectedCount());
        assertEquals(CpFailoverChecks.trafficBytesPerSecond(before,after,Set.of("bond1"),5_000_000_000L).bytesPerSecond(),slave.bytesPerSecond());
        var independent=CpFailoverChecks.trafficBytesPerSecond(before,after,selected,5_000_000_000L,
            java.util.Map.of("bond1",Set.of("eth8","eth9")));
        assertEquals(CpFailoverChecks.TrafficReason.NONE,independent.reason());
        assertEquals(2,independent.selectedCount());
        assertEquals(slave.bytesPerSecond()+CpFailoverChecks.trafficBytesPerSecond(before,after,Set.of("eth7"),5_000_000_000L).bytesPerSecond(),independent.bytesPerSecond());
        var unknown=CpFailoverChecks.trafficBytesPerSecond(before,after,selected,5_000_000_000L,
            java.util.Map.of("bond1",CpFailoverChecks.bondSlaves("invalid","bond1",2)));
        assertEquals(CpFailoverChecks.TrafficReason.BOND_PHYSICAL_OVERLAP,unknown.reason());
    }
    @Test void everySelectedBondMustProveNonMembershipWithoutConflictingSlaves() {
        var before=new CpFailoverChecks.TrafficSample(java.util.Map.of(
            "bond1",new CpFailoverChecks.TrafficBytes(0,0),"bond2",new CpFailoverChecks.TrafficBytes(0,0),
            "eth9",new CpFailoverChecks.TrafficBytes(0,0)),CpFailoverChecks.TrafficReason.NONE);
        var after=new CpFailoverChecks.TrafficSample(java.util.Map.of(
            "bond1",new CpFailoverChecks.TrafficBytes(100,0),"bond2",new CpFailoverChecks.TrafficBytes(200,0),
            "eth9",new CpFailoverChecks.TrafficBytes(300,0)),CpFailoverChecks.TrafficReason.NONE);
        var selected=Set.of("bond1","bond2","eth9");
        var complete=java.util.Map.of("bond1",Set.of("eth7"),"bond2",Set.of("eth8"));
        var independent=CpFailoverChecks.trafficBytesPerSecond(before,after,selected,1_000_000_000L,complete);
        assertEquals(600.0,independent.bytesPerSecond());
        assertEquals(3,independent.selectedCount());
        for(var ambiguous:java.util.List.of(java.util.Map.of("bond1",Set.of("eth7")),
                java.util.Map.of("bond1",Set.of("eth7"),"bond2",Set.of("eth7"))))
            assertEquals(CpFailoverChecks.TrafficReason.BOND_PHYSICAL_OVERLAP,
                CpFailoverChecks.trafficBytesPerSecond(before,after,selected,1_000_000_000L,ambiguous).reason());
    }
    @Test void trafficMissingSubinterfaceHasReasonAndUnequalPresenceCounts() {
        String before=DEV_A.replace("eth0:","eth0.10:");
        var result=traffic(before,DEV_B,Set.of("eth0.10"));
        assertEquals(CpFailoverChecks.TrafficReason.INTERFACE_MISSING_IN_SAMPLE,result.reason());
        assertEquals(1,result.selectedCount());
        assertEquals(1,result.presentBeforeCount());
        assertEquals(0,result.presentAfterCount());
        assertEquals(CpFailoverChecks.TrafficReason.INTERFACE_MISSING_IN_SAMPLE,
            traffic(DEV_A,before,Set.of("eth0.10")).reason());
    }
    @Test void trafficCounterRegressionHasReason() {
        assertEquals(CpFailoverChecks.TrafficReason.COUNTER_REGRESSION,traffic(DEV_B,DEV_A,Set.of("eth0")).reason());
        assertEquals(CpFailoverChecks.TrafficReason.COUNTER_REGRESSION,
            traffic(DEV_A,DEV_B.replace("0 700","0 199"),Set.of("eth0")).reason());
    }
    @Test void trafficCounterSumOverflowHasReason() {
        var zero=new CpFailoverChecks.TrafficBytes(0,0);
        var huge=new CpFailoverChecks.TrafficBytes(Long.MAX_VALUE,1);
        assertEquals(CpFailoverChecks.TrafficReason.OVERFLOW,CpFailoverChecks.trafficBytesPerSecond(
            sample(Map.of("eth0",zero)),sample(Map.of("eth0",huge)),Set.of("eth0"),5_000_000_000L).reason());
        huge=new CpFailoverChecks.TrafficBytes(Long.MAX_VALUE,0);
        assertEquals(CpFailoverChecks.TrafficReason.OVERFLOW,CpFailoverChecks.trafficBytesPerSecond(
            sample(Map.of("eth0",zero,"eth1",zero)),sample(Map.of("eth0",huge,"eth1",huge)),
            Set.of("eth0","eth1"),5_000_000_000L).reason());
    }
    @Test void trafficInvalidElapsedHasDistinctReason() {
        var sample=CpFailoverChecks.bytesByInterface(DEV_A);
        for(long elapsed:new long[]{0,-1}) assertEquals(CpFailoverChecks.TrafficReason.INVALID_ELAPSED,
            CpFailoverChecks.trafficBytesPerSecond(sample,sample,Set.of("eth0"),elapsed).reason());
    }
    @Test void trafficDerivedEvidenceContainsOnlyReasonRateTimeAndCounts() {
        var result=traffic(DEV_A,DEV_B,Set.of("eth0","eth1"));
        assertEquals(Map.of("bytesPerSecond",400.0,"elapsedNanos",5_000_000_000L,"reason","NONE",
            "selectedCount",2,"presentBeforeCount",2,"presentAfterCount",2),result.derived(5_000_000_000L));
    }
    @Test void onlyIntentionalAdminDownIsAccepted() {
        for(String name:java.util.List.of("ADMIN_DOWN","admin_down")) {
            String output="Device Name: "+name+"\nCurrent state: problem\n";
            assertEquals("FAIL",CpFailoverChecks.pnotes(output).status());
            assertEquals("PASS",CpFailoverChecks.pnotes(output,true).status());
            assertEquals("FAIL",CpFailoverChecks.pnotes(output+"Device Name: fwd\n",true).status());
        }
        assertEquals("UNKNOWN",CpFailoverChecks.pnotes("unrecognized",true).status());
    }
    @Test void cpstatUsesExistingParserAndPolicyRules() {
        String output="Policy name: Sample_Policy\nInstall time: 2026-10-06 12:00:00\n";
        var a=CpFailoverChecks.cpstatPolicy(output);
        assertEquals("PASS",CpFailoverChecks.policyParity(a,a,null,null).status());
        assertEquals("FAIL",CpFailoverChecks.policyParity(a,
            CpFailoverChecks.cpstatPolicy(output.replace("Sample_Policy","Other_Policy")),null,null).status());
        assertEquals("FAIL",CpFailoverChecks.policyParity(a,a,"Other_Policy",a.name()).status());
        for(int skew:new int[]{600,601}) {
            var b=CpFailoverChecks.cpstatPolicy(output.replace("12:00:00",skew==600?"12:10:00":"12:10:01"));
            assertEquals(skew==600?"PASS":"FAIL",CpFailoverChecks.policyParity(a,b,null,null).status());
        }
        assertEquals("UNKNOWN",CpFailoverChecks.policyParity(a,CpFailoverChecks.cpstatPolicy(""),null,null).status());
        assertEquals("UNKNOWN",CpFailoverChecks.policyParity(a,
            CpFailoverChecks.cpstatPolicy("Policy name: Sample_Policy\n"),null,null).status());
    }
    @Test void tableDifferencesExplainCoordinatesWithoutRawAddresses() {
        var a=CpFailoverChecks.ipTable(TABLE);
        var b=CpFailoverChecks.ipTable(TABLE.replace("192.0.2.22","192.0.2.99"));
        var diff=CpFailoverChecks.tableDifference(a,b);
        check(diff.toString().contains("MISSING_ON_FIRST") && diff.toString().contains("MISSING_ON_SECOND"));
        check(diff.toString().contains("member=1") && !diff.toString().contains("interface="));
        check(!diff.toString().contains("192.0.2."));
        var missing=CpFailoverChecks.ipTable(TABLE.replace("1 1 192.0.2.22", "1 2 192.0.2.22"));
        String details=CpFailoverChecks.tableDifference(a,missing).toString();
        check(details.contains("differences=[]"));
        check(CpFailoverChecks.tableDifference(a,a).get("differences").equals(java.util.List.of()));
        // Each member keeps its own address; numeric indices are observer-local.
        check(CpFailoverChecks.tableDifference(a,CpFailoverChecks.ipTable(TABLE.replace("(Local)","(local)")))
            .get("differences").equals(java.util.List.of()));
        check(CpFailoverChecks.tableDifference(a,Set.of()).toString().contains("MISSING_ON_SECOND"));
        var opaque=CpFailoverChecks.ipTable("Unique IP's Table\n01 001 192.0.2.1\n");
        check(CpFailoverChecks.tableDifference(opaque,Set.of()).toString().contains("member=01"));
    }
    @Test void observerLocalIndexShapeComparesPerMemberAddressSets() {
        String first="Unique IP's Table\n0 1 192.0.2.10\n0 6 192.0.2.11\n"
            +"1 1 192.0.2.20\n1 6 192.0.2.21\n";
        String second=first.replace(" 6 "," 3 ");
        var a=CpFailoverChecks.ipTable(first); var b=CpFailoverChecks.ipTable(second);
        check(a.size()==4 && b.size()==4 && a.equals(b));
        check(CpFailoverChecks.tableDifference(a,b).get("differences").equals(java.util.List.of()));
        var missing=CpFailoverChecks.ipTable(second.replace("1 3 192.0.2.21\n",""));
        check(CpFailoverChecks.tableDifference(a,missing).toString().contains("MISSING_ON_SECOND"));
        check(CpFailoverChecks.tableDifference(missing,a).toString().contains("MISSING_ON_FIRST"));
        check(!a.equals(CpFailoverChecks.ipTable(second.replace("0 3 192.0.2.11","1 3 192.0.2.11"))));
        String named=first.replace(" 6 "," eth1 ");
        check(!CpFailoverChecks.ipTable(named).equals(CpFailoverChecks.ipTable(named.replace("eth1","eth2"))));
        check(CpFailoverChecks.ipTable(first+"0 3 192.0.2.11\n").equals(a));
    }
    public static void main(String[] args) {
        var t=new CpFailoverChecksTest(); t.approvedChecksAndVsContext(); t.unrecognisedMeansUnknown(); t.syncAndPolicyPassFailUnknown();
    }
}
