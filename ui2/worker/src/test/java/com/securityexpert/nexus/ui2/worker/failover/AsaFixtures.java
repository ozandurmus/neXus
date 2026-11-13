package com.securityexpert.nexus.ui2.worker.failover;

import java.util.ArrayList;
import java.util.List;

/** Synthetic Cisco ASA answers shaped like the measured live pair (docs/design/ASA_FAILOVER_MEASUREMENT_2026_11_13.md). */
final class AsaFixtures {
    private AsaFixtures() {}
    static final String SERIAL_A = "SYNTH0001", SERIAL_B = "SYNTH0002";

    /** One {@code show failover} answer; fields are public knobs a test turns. */
    static final class Show {
        boolean on = true, groups = false, stateful = true;
        String unit = "Primary", thisLabel = "Primary", thisState = "Active", otherLabel = "Secondary", otherState = "Standby Ready";
        String lan = "(up)", link = "(up)", versionOurs = "9.18(4)", versionMate = "9.18(4)", serialOurs = SERIAL_A, serialMate = SERIAL_B;
        List<String> thisInterfaces = new ArrayList<>(List.of("Normal (Monitored)", "Normal (Monitored)", "Normal (Monitored)"));
        List<String> otherInterfaces = new ArrayList<>(List.of("Normal (Monitored)", "Normal (Monitored)", "Normal (Monitored)"));
        /** General row: xmit, xerr, rcv, rerr (cumulative; rerr is large on a healthy pair). */
        long[] general = {1_000_000L, 0, 900_000L, 987_654_321L};
        long[] tcp = {500_000L, 0, 450_000L, 1234L};

        Show copy() {
            Show s = new Show();
            s.on = on; s.groups = groups; s.stateful = stateful; s.unit = unit; s.thisLabel = thisLabel; s.thisState = thisState;
            s.otherLabel = otherLabel; s.otherState = otherState; s.lan = lan; s.link = link; s.versionOurs = versionOurs;
            s.versionMate = versionMate; s.serialOurs = serialOurs; s.serialMate = serialMate;
            s.thisInterfaces = new ArrayList<>(thisInterfaces); s.otherInterfaces = new ArrayList<>(otherInterfaces);
            s.general = general.clone(); s.tcp = tcp.clone();
            return s;
        }

        String text() {
            StringBuilder b = new StringBuilder();
            b.append("Failover ").append(on ? "On" : "Off").append('\n');
            b.append("Failover unit ").append(unit).append('\n');
            b.append("Failover LAN Interface: failover GigabitEthernet0/3 ").append(lan).append('\n');
            b.append("Reconnect timeout 0:00:00\n");
            b.append("Unit Poll frequency 1 seconds, holdtime 15 seconds\n");
            b.append("Interface Poll frequency 5 seconds, holdtime 25 seconds\n");
            b.append("Interface Policy 1\n");
            b.append("Monitored Interfaces 3 of 1285 maximum\n");
            b.append("MAC Address Move Notification Interval not set\n");
            b.append("Version: Ours ").append(versionOurs).append(", Mate ").append(versionMate).append('\n');
            b.append("Serial Number: Ours ").append(serialOurs).append(", Mate ").append(serialMate).append('\n');
            b.append("Last Failover at: 10:11:12 +03 Nov 10 2026\n");
            if (groups) b.append("  Group 1 State: Active\n");
            host(b, "This", thisLabel, thisState, thisInterfaces);
            host(b, "Other", otherLabel, otherState, otherInterfaces);
            if (stateful) {
                b.append("Stateful Failover Logical Update Statistics\n");
                b.append("        Link : failover GigabitEthernet0/3 ").append(link).append('\n');
                b.append("        Stateful Obj    xmit       xerr       rcv        rerr\n");
                row(b, "General", general);
                row(b, "sys cmd", new long[] {1_000_000L, 0, 1_000_000L, 0});
                row(b, "up time", new long[] {0, 0, 0, 0});
                row(b, "TCP conn", tcp);
                row(b, "UDP conn", new long[] {300_000L, 0, 250_000L, 12});
                row(b, "ARP tbl", new long[] {200_000L, 0, 190_000L, 0});
                row(b, "VPN IKEv1 SA", new long[] {0, 0, 0, 0});
                row(b, "VPN IKEv2 SA", new long[] {0, 0, 0, 0});
                b.append("\n        Logical Update Queue Information\n");
                b.append("                        Cur     Max     Total\n");
                b.append("        Recv Q:         0       99      123456789\n");
                b.append("        Xmit Q:         0       1024    98765432101\n");
            }
            return b.toString();
        }

        private static void host(StringBuilder b, String which, String label, String state, List<String> interfaces) {
            b.append(which).append(" host: ").append(label).append(" - ").append(state).append('\n');
            b.append("        Active time: 123456 (sec)\n");
            b.append("        slot 0: ASA5545 hw/sw rev (1.0/9.18(4)) status (Up Sys)\n");
            String[] names = {"outside", "inside", "dmz"};
            for (int i = 0; i < interfaces.size(); i++)
                b.append("          Interface ").append(names[i % 3]).append(" (192.0.2.").append(1 + i).append("): ")
                    .append(interfaces.get(i)).append('\n');
        }

        private static void row(StringBuilder b, String name, long[] v) {
            b.append(String.format("        %-15s %-10d %-10d %-10d %d%n", name, v[0], v[1], v[2], v[3]));
        }
    }

    static Show active() { return new Show(); }

    static Show standby() {
        Show s = new Show();
        s.unit = "Secondary"; s.thisLabel = "Secondary"; s.thisState = "Standby Ready"; s.otherLabel = "Primary"; s.otherState = "Active";
        s.serialOurs = SERIAL_B; s.serialMate = SERIAL_A;
        s.general = new long[] {20, 0, 1_000_000L, 4321L};
        return s;
    }

    /** {@code show failover state}: last-failure columns and the failed interface line are history, never judged. */
    static String state(boolean standbyObserver, String configurationLine) {
        StringBuilder b = new StringBuilder();
        b.append("               State          Last Failure Reason      Date/Time\n");
        b.append("This host  -   ").append(standbyObserver ? "Secondary" : "Primary").append('\n');
        b.append("               ").append(standbyObserver ? "Standby Ready" : "Active").append("  Ifc Failure              10:11:12 +03 Sep 12 2026\n");
        b.append("                              outside: No Link\n");
        b.append("Other host -   ").append(standbyObserver ? "Primary" : "Secondary").append('\n');
        b.append("               ").append(standbyObserver ? "Active" : "Standby Ready").append("  Ifc Failure              10:11:12 +03 Oct 12 2026\n");
        b.append("                              outside: No Link\n\n");
        b.append("====Configuration State===\n");
        if (configurationLine != null) b.append("        ").append(configurationLine).append('\n');
        b.append("====Communication State===\n");
        b.append("        Mac set\n");
        return b.toString();
    }
    static String stateOk(boolean standbyObserver) {
        return standbyObserver ? state(true, "Sync Done - STANDBY") : state(false, "Sync Done\n        Sync Done - STANDBY");
    }

    /** {@code show failover history}: oldest first, newest last; {@code filler} extra old entries make it longer than 80 lines. */
    static String history(int filler, String newestStamp) {
        StringBuilder b = new StringBuilder();
        String rule = "==========================================================================\n";
        b.append(rule).append("From State                 To State                   Reason\n").append(rule);
        for (int i = 0; i < filler; i++) {
            b.append("10:11:12 +03 Aug ").append(1 + i % 28).append(" 2026\n");
            b.append("Just Active                Active Drain               Set by the config command\n");
            b.append("                                                      (failover active)\n");
        }
        b.append(newestStamp).append('\n');
        b.append("Standby Ready              Just Active                Set by the config command\n");
        b.append("                                                      (failover active)\n");
        b.append(rule);
        return b.toString();
    }

    static String conn(long inUse, long mostUsed) { return inUse + " in use, " + mostUsed + " most used\n"; }
}
