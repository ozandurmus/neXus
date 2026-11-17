package com.securityexpert.nexus.ui2.worker.failover;

/** Synthetic FortiGate answers only: serials FGT60F000000000N, host names FGT-SYNTH-N, addresses 192.0.2.x. */
final class FgtFixtures {
    private FgtFixtures() {}
    static final String SERIAL_A = "FGT60F0000000001", SERIAL_B = "FGT60F0000000002";

    static String status(String serial, boolean multiVdom) {
        return "Version: FortiGate-60F v7.4.5,build2702,240101 (GA.M)\nVirus-DB: 1.0\nSerial-Number: " + serial
            + "\nHostname: FGT-SYNTH-1\nVirtual domain configuration: " + (multiVdom ? "multiple" : "disable")
            + "\nCurrent HA mode: a-p, primary\nSystem time: Tue Nov 17 12:00:00 2026\n";
    }

    /** Options of one {@code get system ha status} answer. */
    record Ha(String primaryWord, String secondaryWord, String health, String mode, int vclusters, boolean syncB, boolean hbBDown,
            boolean monBDown, boolean withHostnames, String stateChange) {
        static Ha ok() { return new Ha("Primary", "Secondary", "OK", "HA A-P", 1, true, false, false, true, "2026-11-10 08:00:00"); }
        Ha words(String p, String s) { return new Ha(p, s, health, mode, vclusters, syncB, hbBDown, monBDown, withHostnames, stateChange); }
        Ha health(String h) { return new Ha(primaryWord, secondaryWord, h, mode, vclusters, syncB, hbBDown, monBDown, withHostnames, stateChange); }
        Ha mode(String m) { return new Ha(primaryWord, secondaryWord, health, m, vclusters, syncB, hbBDown, monBDown, withHostnames, stateChange); }
        Ha vclusters(int n) { return new Ha(primaryWord, secondaryWord, health, mode, n, syncB, hbBDown, monBDown, withHostnames, stateChange); }
        Ha outOfSync() { return new Ha(primaryWord, secondaryWord, health, mode, vclusters, false, hbBDown, monBDown, withHostnames, stateChange); }
        Ha hbDown() { return new Ha(primaryWord, secondaryWord, health, mode, vclusters, syncB, true, monBDown, withHostnames, stateChange); }
        Ha monDown() { return new Ha(primaryWord, secondaryWord, health, mode, vclusters, syncB, hbBDown, true, withHostnames, stateChange); }
        Ha bare() { return new Ha(primaryWord, secondaryWord, health, mode, vclusters, syncB, hbBDown, monBDown, false, stateChange); }
        Ha changed(String t) { return new Ha(primaryWord, secondaryWord, health, mode, vclusters, syncB, hbBDown, monBDown, withHostnames, t); }

        String text() {
            StringBuilder b = new StringBuilder();
            b.append("HA Health Status: ").append(health).append("\nModel: FortiGate-60F\nMode: ").append(mode)
                .append("\nGroup Name: synth-grp\nGroup ID: 7\nDebug: 0\nCluster Uptime: 11 days 2:33:31\n");
            if (stateChange != null) b.append("Cluster state change time: ").append(stateChange).append('\n');
            b.append("Primary selected using:\n    <2026/11/10 08:00:00> ").append(SERIAL_A)
                .append(" is selected as the primary because it has the largest value of override priority.\n")
                .append("    <2026/11/01 08:00:00> ").append(SERIAL_A).append(" is selected as the primary because it is the only member.\n")
                .append("ses_pickup: enable, ses_pickup_delay=disable\noverride: disable\nConfiguration Status:\n")
                .append("    ").append(SERIAL_A).append("(updated 2 seconds ago): in-sync\n")
                .append("    ").append(SERIAL_B).append("(updated 3 seconds ago): ").append(syncB ? "in-sync" : "out-of-sync").append('\n')
                .append("System Usage stats:\n    ").append(SERIAL_A).append("(updated 2 seconds ago):\n        sessions=12, memory=20%\n")
                .append("HBDEV stats:\n    ").append(SERIAL_A).append("(updated 2 seconds ago):\n")
                .append("        ha1: physical/1000auto, up, rx-bytes/packets/dropped/errors=1/2/0/0\n")
                .append("    ").append(SERIAL_B).append("(updated 1 seconds ago):\n")
                .append("        ha1: physical/1000auto, ").append(hbBDown ? "down" : "up").append(", rx-bytes/packets/dropped/errors=1/2/0/0\n")
                .append("MONDEV stats:\n    ").append(SERIAL_A).append("(updated 2 seconds ago):\n")
                .append("        wan1: physical/1000auto, up, rx-bytes/packets/dropped/errors=1/2/0/0\n")
                .append("        lan: physical/1000auto, up, rx-bytes/packets/dropped/errors=1/2/0/0\n")
                .append("    ").append(SERIAL_B).append("(updated 1 seconds ago):\n")
                .append("        wan1: physical/1000auto, up, rx-bytes/packets/dropped/errors=1/2/0/0\n")
                .append("        lan: physical/1000auto, ").append(monBDown ? "down" : "up").append(", rx-bytes/packets/dropped/errors=1/2/0/0\n");
            String a = withHostnames ? "FGT-SYNTH-1 , " : "", c = withHostnames ? "FGT-SYNTH-2 , " : "";
            String idx = withHostnames ? "HA cluster index" : "HA operating index";
            b.append(primaryWord).append("       : ").append(a).append(SERIAL_A).append(", ").append(idx).append(" = 0\n")
                .append(secondaryWord).append("     : ").append(c).append(SERIAL_B).append(", ").append(idx).append(" = 1\n")
                .append("number of vcluster: ").append(vclusters).append("\nvcluster 1: work 169.254.0.1\n")
                .append(primaryWord).append(": ").append(SERIAL_A).append(", HA operating index = 0\n")
                .append(secondaryWord).append(": ").append(SERIAL_B).append(", HA operating index = 1\n");
            return b.toString();
        }
    }

    static String config(String override, String pickup, String margin) {
        return "config system ha\n    set group-id 7\n    set group-name \"synth-grp\"\n    set mode a-p\n    set password ENC xxxx\n"
            + "    set hbdev \"ha1\" 50\n    set session-pickup " + pickup + "\n    set session-pickup-connectionless disable\n"
            + "    set override " + override + "\n    set ha-uptime-diff-margin " + margin + "\n    config vcluster\n        edit 1\n"
            + "            set override " + override + "\n        next\n    end\nend\n";
    }

    static String checksums(boolean equal) {
        String same = "all: 0a 1b 2c 3d 4e 5f 60 71 82 93 a4 b5 c6 d7 e8 f9\n";
        String other = equal ? same : "all: 0a 1b 2c 3d 4e 5f 60 71 82 93 a4 b5 c6 d7 e8 00\n";
        return "================== " + SERIAL_A + " ==================\n\nis_manage_master()=1, is_root_master()=1\ndebugzone\nglobal: 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff 00\n"
            + "\nchecksum\nglobal: 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff 00\nroot: 01 02 03 04 05 06 07 08 09 0a 0b 0c 0d 0e 0f 10\n" + same
            + "\n================== " + SERIAL_B + " ==================\n\nis_manage_master()=0, is_root_master()=0\ndebugzone\nglobal: 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff 00\n"
            + "\nchecksum\nglobal: 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff 00\nroot: 01 02 03 04 05 06 07 08 09 0a 0b 0c 0d 0e 0f 10\n" + other;
    }

    static String dump(long uptimeB) {
        return "HA information\ngroup-id=7, group-name='synth-grp'\n'" + SERIAL_A + "': ha_prio=0, uptime/reset_cnt=900000/0\n'"
            + SERIAL_B + "': ha_prio=0, uptime/reset_cnt=" + uptimeB + "/0\n";
    }
    static String flag(boolean latched) {
        return latched ? "failover flag is set for vcluster 1\n" : "failover flag is not set for vcluster 1\n";
    }
}
