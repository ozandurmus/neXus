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
            check(CpFailoverChecks.syncInformation(sync).get("sentRejectNotifications")== (shape.equals("vsx_vs")?4554:0));
        }
        check(CpFailoverChecks.policy(Fixtures.read("cp/failover_policy_vsx_chassis.txt")).installedAt()
            .equals("18Sep2026 0:44:41"));
        String interfaces=Fixtures.read("cp/failover_interfaces_vsx_vs.txt")
            .replace("Required interfaces: 4\n", "").replace("CCP mode: Automatic\n", "");
        check(CpFailoverChecks.interfaces(interfaces).healthy());
        check(!CpFailoverChecks.interfaces(interfaces.replace("(LS) UP","(LS) UNKNOWN")).healthy());
    }
    @Test void measuredTablesIgnoreMacButPreserveMemberAndInterfaceMappings() {
        String table=Fixtures.read("cp/failover_tablestat.txt");
        var rows=CpFailoverChecks.ipTable(table);
        check(rows.size()==4 && CpFailoverChecks.twoTableMembers(rows));
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
    public static void main(String[] args) {
        var t=new CpFailoverChecksTest(); t.approvedChecksAndVsContext(); t.unrecognisedMeansUnknown(); t.syncAndPolicyPassFailUnknown();
    }
}
