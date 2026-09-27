package com.securityexpert.nexus.ui2.worker.failover;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
    public static void main(String[] args) {
        var t=new CpFailoverChecksTest(); t.approvedChecksAndVsContext(); t.unrecognisedMeansUnknown(); t.syncAndPolicyPassFailUnknown();
    }
}
