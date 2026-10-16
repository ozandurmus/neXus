package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repeated snapshots of {@code tc -s qdisc} and of {@code /proc/net/softnet_stat}, each introduced by a line holding its
 * timestamp (the output of {@code date}, in ISO form, as epoch seconds, or framed by {@code #}/{@code =}/brackets). A
 * snapshot belongs to the latest timestamp line above it. Counters are cumulative; the report shows their increase.
 *
 * <p>Per device the root qdisc's {@code dropped} counter is used (for {@code mq} that is already the sum of its queues);
 * when only child qdiscs are listed they are added. softnet_stat rows (one per CPU, hexadecimal) are added across CPUs:
 * processed, dropped and time_squeeze are its first three columns.</p>
 */
final class SnapshotParser implements LineParser {
    private static final Pattern SENT = Pattern.compile(
            "^\\s+Sent\\s+(\\d+)\\s+bytes\\s+(\\d+)\\s+pkt\\s+\\(dropped\\s+(\\d+),\\s+overlimits\\s+(\\d+)\\s+requeues\\s+(\\d+)\\)");
    static final Pattern QDISC = Pattern.compile("^qdisc\\s+\\S+\\s+\\S+");
    static final Pattern SOFTNET = Pattern.compile("^[0-9a-fA-F]{8}(?:\\s+[0-9a-fA-F]{8}){9,}\\s*$");

    private static final class Device {
        Long root;
        long children;
        boolean seen;
    }

    private final ParsedTelemetry out;
    private Stamp stamp;
    private final Map<String, Device> devices = new TreeMap<>();
    private Device pending;
    private boolean pendingRoot;
    private boolean softnetSeen;
    private long processed;
    private long dropped;
    private long squeezed;

    SnapshotParser(ParsedTelemetry out) {
        this.out = out;
    }

    @Override
    public void line(String text) {
        if (text.isBlank()) {
            out.ignored();
            return;
        }
        if (QDISC.matcher(text).find()) {
            qdisc(text);
            return;
        }
        if (text.startsWith(" ") || text.startsWith("\t")) {
            Matcher m = SENT.matcher(text);
            if (m.find()) {
                sent(m);
                return;
            }
        }
        if (text.length() >= 80 && SOFTNET.matcher(text).matches()) {
            softnet(text);
            return;
        }
        if (text.length() <= 64) {
            Stamp s = TimeText.stampLine(text);
            if (s != null) {
                flush();
                stamp = s;
                if (s.explicit()) {
                    out.note("EXPLICIT_OFFSETS");
                }
                out.used();
                return;
            }
        }
        out.ignored();
    }

    private void qdisc(String text) {
        pending = null;
        if (stamp == null) {
            out.skipped("NO_TIMESTAMP");
            return;
        }
        String[] t = Sysstat.split(text);
        String dev = "?";
        boolean root = false;
        for (int i = 3; i < t.length; i++) {
            if (t[i].equals("dev") && i + 1 < t.length) {
                dev = t[i + 1];
            } else if (t[i].equals("root")) {
                root = true;
            }
        }
        pending = devices.computeIfAbsent(dev, k -> new Device());
        pendingRoot = root;
        out.ignored();
    }

    private void sent(Matcher m) {
        if (pending == null) {
            out.ignored();
            return;
        }
        long drops;
        try {
            drops = Long.parseLong(m.group(3));
        } catch (NumberFormatException tooLarge) {
            out.skipped("BAD_NUMBER");
            pending = null;
            return;
        }
        pending.seen = true;
        if (pendingRoot) {
            pending.root = drops;
        } else {
            pending.children += drops;
        }
        pending = null;
        out.used();
    }

    private void softnet(String text) {
        if (stamp == null) {
            out.skipped("NO_TIMESTAMP");
            return;
        }
        String[] t = Sysstat.split(text);
        processed += Long.parseLong(t[0], 16);
        dropped += Long.parseLong(t[1], 16);
        squeezed += Long.parseLong(t[2], 16);
        softnetSeen = true;
        out.used();
    }

    private void flush() {
        if (stamp != null) {
            for (Map.Entry<String, Device> e : devices.entrySet()) {
                Device d = e.getValue();
                if (d.seen) {
                    long value = d.root != null ? d.root : d.children;
                    out.series("qdisc.dropped|" + e.getKey(), Series.Kind.COUNTER).add(stamp, value);
                }
            }
            if (softnetSeen) {
                out.series("softnet.processed", Series.Kind.COUNTER).add(stamp, processed);
                out.series("softnet.dropped", Series.Kind.COUNTER).add(stamp, dropped);
                out.series("softnet.time_squeeze", Series.Kind.COUNTER).add(stamp, squeezed);
            }
        }
        devices.clear();
        pending = null;
        softnetSeen = false;
        processed = 0;
        dropped = 0;
        squeezed = 0;
    }

    @Override
    public void finish() {
        flush();
        if (!out.hasSamples()) {
            out.reason(out.linesSkipped() > 0 ? "NO_TIMESTAMPS" : "NO_SAMPLES");
        }
    }
}
