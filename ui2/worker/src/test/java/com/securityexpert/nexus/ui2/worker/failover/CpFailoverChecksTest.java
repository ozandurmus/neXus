package com.securityexpert.nexus.ui2.worker.failover;

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
    private static final String CONN = "HOST NAME ID #VALS #PEAK #SLINKS\n"
        + "localhost connections 8158 100 150 0\n";
    private static final String SYNC = "Delta Sync Statistics\nSync status: OK\nDrops:\n"
        + "Lost updates................................. 0\nLost bulk update events...................... 0\n";
    private static final String POLICY = "HOST POLICY DATE\nlocalhost Sample_Policy 10Sep2018 14:01:25 : [>eth0]\n";
    private static final String DEV_A = "Inter-| Receive | Transmit\n face |bytes packets errs drop fifo frame compressed multicast|bytes packets errs drop fifo colls carrier compressed\n"
        + "eth0: 100 0 0 0 0 0 0 0 200 0 0 0 0 0 0 0\n"
        + "eth1: 300 0 0 0 0 0 0 0 400 0 0 0 0 0 0 0\n";
    private static final String DEV_B = DEV_A.replace("eth0: 100", "eth0: 600").replace("eth0: 600 0 0 0 0 0 0 0 200", "eth0: 600 0 0 0 0 0 0 0 700")
        .replace("eth1: 300", "eth1: 800").replace("eth1: 800 0 0 0 0 0 0 0 400", "eth1: 800 0 0 0 0 0 0 0 900");
    private static void check(boolean condition) { if(!condition) throw new AssertionError(); }

    @Test void approvedChecksAndVsContext() {
        check(CpFailoverChecks.corroborated(CpFailoverChecks.state(ACTIVE),CpFailoverChecks.state(STANDBY)));
        check(!CpFailoverChecks.corroborated(CpFailoverChecks.state(ACTIVE),CpFailoverChecks.state(ACTIVE)));
        check(CpFailoverChecks.ipTable(TABLE).size()==2);
        check(CpFailoverChecks.ipTable(TABLE).equals(CpFailoverChecks.ipTable(TABLE.replace("(Local)",""))));
        check(CpFailoverChecks.interfaces(IF).healthy());
        check(CpFailoverChecks.interfaces(IF).trafficNames().equals(Set.of("eth0")));
        check(!CpFailoverChecks.interfaces(IF.replace("eth1 UP", "eth1 DOWN")).healthy());
        check(CpFailoverChecks.arpCount("? (192.0.2.31) at 02:00:00:00:00:01 [ether] on eth0\n")==1);
        check(CpFailoverChecks.connections(CONN).count()==100);
        check(CpFailoverChecks.connections(CONN).peak()==150);
        check(CpFailoverChecks.trafficBytesPerSecond(CpFailoverChecks.bytesByInterface(DEV_A),
            CpFailoverChecks.bytesByInterface(DEV_B),Set.of("eth0","eth1"))==400);
        check(CpFailoverChecks.ratio(80,100,CpFailoverChecks.CONNECTION_MIN_RATIO));
        check(!CpFailoverChecks.ratio(49,100,CpFailoverChecks.TRAFFIC_MIN_RATIO));
        // The same strict projections apply to a VS after its command is wrapped by vsenv.
        check(CpFailoverChecks.state(ACTIVE).localRole().equals("ACTIVE"));
    }
    @Test void unrecognisedMeansUnknown() {
        check(CpFailoverChecks.state("unsupported").localRole().equals("UNKNOWN"));
        check(CpFailoverChecks.ipTable("unsupported").isEmpty());
        check(!CpFailoverChecks.interfaces("unsupported").healthy());
        check(CpFailoverChecks.arpCount("unsupported")==-1);
        check(CpFailoverChecks.connections("unsupported")==null);
        check(CpFailoverChecks.bytesByInterface("unsupported").isEmpty());
        check(CpFailoverChecks.trafficBytesPerSecond(Map.of(),Map.of(),Set.of("eth0"))==-1);
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
            check(state.pair() && state.members().size()==2);
            check(CpFailoverChecks.corroborated(state,CpFailoverChecks.state(output
                .replace("1 (local)","1").replace("2          ","2 (local) "))));
            check(CpFailoverChecks.policy(Fixtures.read("cp/failover_policy_"+shape+".txt")).status().equals("PASS"));
            String interfaces=Fixtures.read("cp/failover_interfaces_"+shape+".txt");
            var parsed=CpFailoverChecks.interfaces(interfaces);
            check(parsed.healthy() && parsed.names().size()==4);
            check(parsed.trafficNames().equals(Set.of("Mgmt","eth2-01","bond1.3843")));
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
        check(CpFailoverChecks.ipTable(table+"0 4 192.0.2.99\n").isEmpty());
        check(CpFailoverChecks.ipTable(table+"0 6 malformed\n").isEmpty());
        check(CpFailoverChecks.connections(Fixtures.read("cp/failover_connections.txt")).count()==4521);
        check(CpFailoverChecks.bytesByInterface(Fixtures.read("cp/failover_net_dev.txt")).get("gretap0")==19000);
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
        }
    }
    public static void main(String[] args) {
        var t=new CpFailoverChecksTest(); t.approvedChecksAndVsContext(); t.unrecognisedMeansUnknown(); t.syncAndPolicyPassFailUnknown();
    }
}
