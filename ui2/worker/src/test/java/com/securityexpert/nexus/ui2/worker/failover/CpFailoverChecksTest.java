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
            check(parsed.trafficNames().size()==6 && !parsed.trafficNames().contains("Sync"));
            check(!CpFailoverChecks.interfaces(interfaces.replace("Sync (S)             UP", "Sync (S)             DOWN")).healthy());
            check(!CpFailoverChecks.interfaces(interfaces.replace("eth1-01.1237         UP", "truncated row")).ccpPresent());
            check(CpFailoverChecks.arpCount(fixture.get("arp -an").replace("\n",eol))==1);
            var bytes=CpFailoverChecks.bytesByInterface(fixture.get("cat /proc/net/dev").replace("\n",eol));
            check(bytes.keySet().equals(parsed.names()) && bytes.values().stream().allMatch(v -> v==3000L));
            check(CpFailoverChecks.trafficBytesPerSecond(bytes,bytes,parsed.trafficNames())==0);
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
    @Test void connectionVolumeAndToleranceBoundaries() {
        long[][] cases={{3127,2285,1},{3000,1001,1},{3000,1000,0},{3000,999,0},
            {9999,4999,0},{9998,4999,1},{9999,5000,1},{10000,7999,0},{10000,8000,1},
            {10001,8000,0},{10001,8001,1},{0,0,1},{0,3000,1},{100,0,1},
            {2000,0,0},{4000,1999,0},{4000,2000,1},{9999,12000,1}};
        for(long[] c:cases) {
            var result=CpFailoverChecks.connectionParity(c[0],c[1],false);
            check(result.status().equals(c[2]==1?"PASS":"FAIL"));
            check(result.derived().get("rule").equals(c[0]<10000?"LOW_VOLUME_ABSOLUTE_OR_RATIO":"RATIO_80"));
            check(result.derived().get("activeCount").equals(c[0]));
            check(result.derived().get("comparedCount").equals(c[1]));
            check(result.derived().containsKey("ratio")== (c[0]>0));
        }
        check(CpFailoverChecks.connectionParity(-1,10,false).status().equals("UNKNOWN"));
        check(CpFailoverChecks.connectionParity(10,-1,false).status().equals("UNKNOWN"));
        check(CpFailoverChecks.connectionParity(100,79,true).status().equals("FAIL"));
        check(CpFailoverChecks.connectionParity(100,80,true).status().equals("PASS"));
        check(CpFailoverChecks.connectionParity(100,80,true).derived().get("rule").equals("POST_RATIO_80"));
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
