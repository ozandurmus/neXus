package com.securityexpert.nexus.ui2.packetanalysis.export;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Synthetic server telemetry on the clock of the engine fixtures (1,700,000,000 = 2023-11-14 22:13:20 UTC; the event
 * onset is 12 s later). At +12 s the run queue and sys CPU step up, retransmissions start at +13, the eth1 TX queue starts
 * dropping at +13, the process count steps up at +12 and syslog gets busy at +12. Reserved example names only.
 */
final class HostFixtures {
    static final long BASE = 1_700_000_000L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter DATE_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
    private static final DateTimeFormatter SYSLOG = DateTimeFormatter.ofPattern("MMM d HH:mm:ss", Locale.US);

    private HostFixtures() {
    }

    static ExpertReportOptions.HostTelemetry file(String name, String text, String zone, int offset, String anchor) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new ExpertReportOptions.HostTelemetry(java.util.UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString(),
                name, bytes.length, "cd".repeat(32), zone, offset, anchor, () -> new ByteArrayInputStream(bytes));
    }

    private static ZonedDateTime at(long second, ZoneId zone) {
        return Instant.ofEpochSecond(BASE + second).atZone(zone);
    }

    /** vmstat -t: rows for seconds 0..40; header zone label as given. */
    static String vmstat(ZoneId zone, String label) {
        StringBuilder b = new StringBuilder();
        b.append("procs -----------memory---------- ---swap-- -----io---- -system-- ------cpu----- -----timestamp-----\n");
        b.append(" r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st                 ").append(label).append('\n');
        for (int s = -1; s <= 40; s++) {
            boolean storm = s >= 12;
            int r = storm ? 300 + 40 * (s - 12) : 2 + s % 3;
            int sy = storm ? 18 + Math.min(30, 2 * (s - 12)) : 1;
            b.append(String.format(Locale.ROOT, " %d  0      0 8037584  79200 5978804    0    0     7    23 %d %d  %d %d %d  0  0 %s\n", r,
                    storm ? 1_000_000 : 400, storm ? 1_200_000 : 640, 100 - sy - 2, sy, 2, at(s, zone).format(STAMP).substring(0, 19)));
        }
        return b.toString();
    }

    /** sar -u -q -w -n DEV,TCP,ETCP style report for seconds 0..40, 24-hour clock. */
    static String sar(ZoneId zone, String host) {
        ZonedDateTime day = at(0, zone);
        StringBuilder b = new StringBuilder();
        b.append("Linux 5.14.0-284.el9.x86_64 (").append(host).append(") \t").append(day.format(DATE_ISO)).append(" \t_x86_64_\t(64 CPU)\n\n");
        b.append(at(-1, zone).format(TIME)).append("        CPU     %user     %nice   %system   %iowait    %steal     %idle\n");
        for (int s = 0; s <= 40; s++) {
            double sys = s >= 12 ? 36.0 : 1.5;
            b.append(String.format(Locale.ROOT, "%s        all %9.2f      0.00 %9.2f      0.50      0.00 %9.2f\n", at(s, zone).format(TIME), 5.0, sys,
                    94.0 - sys));
        }
        b.append('\n').append(at(-1, zone).format(TIME)).append("      runq-sz  plist-sz   ldavg-1   ldavg-5  ldavg-15   blocked\n");
        for (int s = 0; s <= 40; s++) {
            b.append(String.format(Locale.ROOT, "%s %10d      2955      1.00      1.00      1.00         0\n", at(s, zone).format(TIME),
                    s >= 12 ? 317 + 50 * (s - 12) : 3));
        }
        b.append('\n').append(at(-1, zone).format(TIME)).append("        IFACE   rxpck/s   txpck/s    rxkB/s    txkB/s   rxcmp/s   txcmp/s  rxmcst/s   %ifutil\n");
        for (int s = 0; s <= 40; s++) {
            b.append(String.format(Locale.ROOT, "%s         eth1 %9.2f %9.2f   1000.00   2000.00      0.00      0.00      0.00      0.50\n",
                    at(s, zone).format(TIME), 100000.0 + s, 90000.0 - s));
            b.append(String.format(Locale.ROOT, "%s           lo %9.2f %9.2f      1.00      1.00      0.00      0.00      0.00      0.00\n",
                    at(s, zone).format(TIME), 5.0, 5.0));
        }
        b.append('\n').append(at(-1, zone).format(TIME)).append("     atmptf/s  estres/s retrans/s isegerr/s   orsts/s\n");
        for (int s = 0; s <= 40; s++) {
            b.append(String.format(Locale.ROOT, "%s         0.00      0.00 %9.2f      0.00      0.00\n", at(s, zone).format(TIME),
                    s >= 13 ? 518.0 * (s - 12) : 0.0));
        }
        return b.toString();
    }

    static String pidstat(ZoneId zone) {
        StringBuilder b = new StringBuilder();
        b.append("Linux 5.14.0-284.el9.x86_64 (srv-a.example.test) \t").append(at(0, zone).format(DATE_ISO)).append(" \t_x86_64_\t(64 CPU)\n\n");
        for (int s = 0; s <= 40; s++) {
            int rows = s >= 12 ? 40 + 6 * (s - 12) : 40;
            String t = at(s, zone).format(TIME);
            b.append(t).append("      UID       PID    %usr %system  %guest   %wait    %CPU   CPU  Command\n");
            for (int i = 0; i < rows; i++) {
                b.append(t).append(String.format(Locale.ROOT, "        0     %5d    0.00    0.10    0.00    0.00    0.10     3  worker-%d\n", 100 + i, i % 7));
            }
            b.append('\n');
        }
        return b.toString();
    }

    /** Snapshots with sub-second stamps in {@code date +"%F %T.%3N"} form. */
    static String snapshots(ZoneId zone) {
        StringBuilder b = new StringBuilder();
        long dropped = 39_000;
        long squeeze = 231_634;
        for (int i = 0; i < 80; i++) {
            long millis = i * 500L;
            ZonedDateTime when = Instant.ofEpochMilli(BASE * 1000 + millis).atZone(zone);
            boolean storm = millis >= 13_000;
            if (storm) {
                dropped += 20 + (millis - 13_000) / 100;
            }
            if (millis == 20_000) {
                squeeze += 3;
            }
            b.append(when.format(STAMP)).append('\n');
            b.append("qdisc mq 0: dev eth1 root\n");
            b.append(" Sent 123456789 bytes 4567890 pkt (dropped ").append(dropped).append(", overlimits 0 requeues 5)\n");
            b.append(" backlog 0b 0p requeues 5\n");
            b.append("qdisc noqueue 0: dev lo root refcnt 2\n");
            b.append(" Sent 0 bytes 0 pkt (dropped 0, overlimits 0 requeues 0)\n");
            b.append(String.format(Locale.ROOT, "%08x 00000000 %08x 00000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000\n",
                    1000 + i, squeeze));
        }
        return b.toString();
    }

    /** Classic syslog (no year, no zone): quiet, then busy from +12 s. Message texts are made up and must never surface. */
    static String messages(ZoneId zone) {
        StringBuilder b = new StringBuilder();
        for (int s = 0; s <= 40; s++) {
            int lines = s >= 12 ? 5 : 1;
            for (int i = 0; i < lines; i++) {
                String program = i == 0 ? "systemd" : "kernel";
                String message = i == 0 ? "Started job for batch-user-secret" : "nf_conntrack: table full from 192.0.2.77";
                b.append(at(s, zone).format(SYSLOG)).append(" srv-a ").append(program).append(i == 0 ? "[1]" : "")
                        .append(": ").append(message).append('\n');
            }
        }
        return classicDays(b.toString());
    }

    /** Classic syslog pads the day of month to two characters. */
    private static String classicDays(String text) {
        return text.replaceAll("(?m)^([A-Z][a-z]{2}) (\\d) ", "$1  $2 ");
    }

    static String unknown() {
        return "id,name,value\n1,alpha,2\n2,beta,3\n";
    }

    static ZoneId istanbul() {
        return ZoneId.of("Europe/Istanbul");
    }

    static ZoneId utc() {
        return ZoneOffset.UTC;
    }
}
