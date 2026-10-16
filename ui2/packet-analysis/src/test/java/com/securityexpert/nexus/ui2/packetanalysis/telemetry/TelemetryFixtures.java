package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

import java.nio.charset.StandardCharsets;

/**
 * Synthetic telemetry in the common Linux text forms. Host names are reserved example names, every number is made up, and
 * the story is the same everywhere: at 10:00:00 UTC the run queue and sys CPU jump, retransmissions start at 10:00:01, the
 * TX queue of eth1 starts dropping, the process count steps up and syslog gets busy.
 */
public final class TelemetryFixtures {
    private TelemetryFixtures() {
    }

    public static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public static String sar24h() {
        StringBuilder b = new StringBuilder();
        b.append("Linux 5.14.0-284.el9.x86_64 (srv-a.example.test) \t10/08/2026 \t_x86_64_\t(64 CPU)\n\n");
        b.append("09:59:59        CPU     %user     %nice   %system   %iowait    %steal     %idle\n");
        int[][] cpu = {{13, 18, 1, 68}, {7, 36, 1, 56}, {6, 47, 1, 46}, {6, 47, 1, 46}};
        for (int i = 0; i < 4; i++) {
            String t = String.format("10:00:%02d", i);
            b.append(String.format("%s        all %9.2f      0.00 %9.2f %9.2f      0.00 %9.2f\n", t, (double) cpu[i][0], (double) cpu[i][1],
                    (double) cpu[i][2], (double) cpu[i][3]));
            b.append(String.format("%s          0 %9.2f      0.00 %9.2f %9.2f      0.00 %9.2f\n", t, 99.0, 1.0, 0.0, 0.0));
        }
        b.append("Average:        all      8.00      0.00     37.00      1.00      0.00     54.00\n\n");
        b.append("09:59:59      runq-sz  plist-sz   ldavg-1   ldavg-5  ldavg-15   blocked\n");
        int[] runq = {317, 555, 886, 900};
        for (int i = 0; i < 4; i++) {
            b.append(String.format("10:00:%02d %10d      2955     12.50     10.20      9.80         0\n", i, runq[i]));
        }
        b.append("\n09:59:59      proc/s   cswch/s\n");
        for (int i = 0; i < 4; i++) {
            b.append(String.format("10:00:%02d     %d  %d\n", i, 120 + i * 40, 1396109 - i * 50000));
        }
        b.append("\n09:59:59        IFACE   rxpck/s   txpck/s    rxkB/s    txkB/s   rxcmp/s   txcmp/s  rxmcst/s   %ifutil\n");
        for (int i = 0; i < 4; i++) {
            b.append(String.format("10:00:%02d         eth1 %9.2f %9.2f   1000.00   2000.00      0.00      0.00      0.00      0.50\n", i,
                    390141.0 - i * 20000, 500000.0 - i * 90000));
            b.append(String.format("10:00:%02d           lo %9.2f %9.2f      1.00      1.00      0.00      0.00      0.00      0.00\n", i, 10.0, 10.0));
        }
        b.append("\n09:59:59     active/s passive/s    iseg/s    oseg/s\n");
        b.append("10:00:00         0.00      0.00  1000.00   2000.00\n");
        b.append("10:00:01        66.00    141.00 165084.00 390141.00\n");
        b.append("10:00:02       166.00    348.00 135436.00 203000.00\n");
        b.append("10:00:03        10.00     20.00 100000.00 150000.00\n");
        b.append("\n09:59:59     atmptf/s  estres/s retrans/s isegerr/s   orsts/s\n");
        b.append("10:00:00         0.00      0.00      0.00      0.00      0.00\n");
        b.append("10:00:01         0.00      0.00    518.00      0.00      0.00\n");
        b.append("10:00:02         0.00      0.00   1978.00      0.00      0.00\n");
        b.append("10:00:03         0.00      1.00   3316.00      0.00      0.00\n");
        b.append("\n09:59:59        call/s retrans/s    read/s   write/s  access/s getatt/s\n");
        b.append("10:00:01         12.00     77.00      1.00      2.00      3.00     4.00\n");
        return b.toString();
    }

    public static String vmstatStamped() {
        return "procs -----------memory---------- ---swap-- -----io---- -system-- ------cpu----- -----timestamp-----\n"
                + " r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st                 UTC\n"
                + " 2  0      0 8037584  79200 5978804    0    0     7    23   40   64  1  0 99  0  0 2026-10-08 09:59:58\n"
                + " 3  0      0 8037000  79200 5978804    0    0     0     0 1403540 1396109 13 18 69  0  0 2026-10-08 10:00:00\n"
                + " 555  1      0 8030000  79200 5978804    0    0     0     0 1130827 1181985  7 36 56  1  0 2026-10-08 10:00:01\n"
                + " 886  0      0 8030000  79200 5978804    0    0     0     0 997107 979342  6 47 47  0  0 2026-10-08 10:00:02\n"
                + " 9   0      0 8030000  79200 5978804    0    0     0     0 1 1  6 4x 47  0  0 2026-10-08 10:00:03\n";
    }

    public static String vmstatPlain() {
        return "procs -----------memory---------- ---swap-- -----io---- -system-- ------cpu-----\n"
                + " r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st\n"
                + " 2  0      0 8037584  79200 5978804    0    0     7    23   40   64  1  0 99  0  0\n"
                + " 317  0      0 8037000  79200 5978804    0    0     0     0 1403540 1396109 13 18 69  0  0\n"
                + " 555  1      0 8030000  79200 5978804    0    0     0     0 1130827 1181985  7 36 56  1  0\n";
    }

    public static String pidstat() {
        StringBuilder b = new StringBuilder();
        b.append("Linux 5.14.0-284.el9.x86_64 (srv-a.example.test) \t10/08/2026 \t_x86_64_\t(64 CPU)\n\n");
        int[] rows = {3, 3, 6, 4};
        for (int i = 0; i < rows.length; i++) {
            String t = String.format("10:00:%02d", i);
            b.append(t).append("      UID       PID    %usr %system  %guest   %wait    %CPU   CPU  Command\n");
            for (int r = 0; r < rows[i]; r++) {
                b.append(t).append(String.format("        0     %5d    0.00    0.10    0.00    0.00    0.10     3  worker-%d\n", 100 + r, r));
            }
            b.append("\n");
        }
        b.append("Average:      UID       PID    %usr %system  %guest   %wait    %CPU   CPU  Command\n");
        b.append("Average:        0       100    0.00    0.10    0.00    0.00    0.10     -  worker-0\n");
        return b.toString();
    }

    public static String snapshots() {
        StringBuilder b = new StringBuilder();
        long[][] drops = {{39588, 35636}, {39628, 35668}, {39674, 35728}, {39750, 35788}, {39976, 35918}};
        String[] stamps = {"2026-10-08 10:00:00.125", "2026-10-08 10:00:01.591", "2026-10-08 10:00:01.978", "2026-10-08 10:00:02.357",
            "2026-10-08 10:00:02.737"};
        for (int i = 0; i < drops.length; i++) {
            b.append("### ").append(stamps[i]).append(" ###\n");
            b.append("qdisc mq 0: dev eth1 root\n");
            b.append(" Sent 123456789 bytes 4567890 pkt (dropped ").append(drops[i][0]).append(", overlimits 0 requeues 5)\n");
            b.append(" backlog 0b 0p requeues 5\n");
            b.append("qdisc fq_codel 0: dev eth1 parent :1 limit 10240p flows 1024\n");
            b.append(" Sent 1000 bytes 10 pkt (dropped 999999, overlimits 0 requeues 0)\n");
            b.append("qdisc mq 0: dev eth2 root\n");
            b.append(" Sent 123456789 bytes 4567890 pkt (dropped ").append(drops[i][1]).append(", overlimits 0 requeues 5)\n");
            b.append("qdisc noqueue 0: dev lo root refcnt 2\n");
            b.append(" Sent 0 bytes 0 pkt (dropped 0, overlimits 0 requeues 0)\n");
            b.append(String.format("%08x 00000000 %08x 00000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000\n",
                    1000 + i, 231634 + (i == 4 ? 1 : 0)));
            b.append(String.format("%08x 00000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000 00000001 00000000\n",
                    2000 + i));
        }
        return b.toString();
    }

    public static String syslogClassic() {
        return "Oct  8 09:59:59 srv-a systemd[1]: Started Session 4711 of user svc-batch.\n"
                + "Oct  8 10:00:00 srv-a kernel: nf_conntrack: table full, dropping packet\n"
                + "Oct  8 10:00:00 srv-a sshd[2201]: Accepted publickey for secret-user from 192.0.2.77 port 4022\n"
                + "Oct  8 10:00:00 srv-a CRON[2210]: (root) CMD (run-parts /etc/cron.hourly)\n"
                + "   continuation line without a time stamp\n"
                + "Oct  8 10:00:01 srv-a kernel: eth1: tx queue stalled\n"
                + "Oct  8 10:00:01 srv-a kernel: eth1: tx queue stalled again\n";
    }

    public static String syslogIso() {
        return "2026-10-08T10:00:00.123456+03:00 srv-b app[11]: hello\n"
                + "2026-10-08T10:00:00.900000+03:00 srv-b app[11]: hello again\n"
                + "2026-10-08T10:00:01.000001+03:00 srv-b other: x\n"
                + "2026-10-08T10:00:01.500000+03:00 srv-b other: y\n";
    }
}
