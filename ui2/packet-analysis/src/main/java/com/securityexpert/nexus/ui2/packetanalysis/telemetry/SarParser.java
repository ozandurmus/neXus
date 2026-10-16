package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code sar} text reports. Blocks are told apart by their column names: CPU ({@code %user}/{@code %usr}, {@code %system}/
 * {@code %sys}, {@code %iowait}, {@code %idle}; the {@code all} row only), queue length and load ({@code runq-sz},
 * {@code plist-sz}), task creation ({@code proc/s}, {@code cswch/s}), network interfaces ({@code rxpck/s}, {@code txpck/s}),
 * TCP ({@code active/s}, {@code passive/s}, {@code iseg/s}, {@code oseg/s}) and TCP errors ({@code atmptf/s},
 * {@code estres/s}, {@code retrans/s}, {@code isegerr/s}, {@code orsts/s}). NFS also has a {@code retrans/s} column, which is
 * why a block is classified from its whole header and never from one column. Every other block is ignored.
 */
final class SarParser extends Sysstat {
    enum Block { CPU, QUEUE, TASK, DEV, TCP, ETCP, OTHER }

    static final int MAX_INTERFACES = 64;

    private Block block;
    private String[] columns = new String[0];
    private Map<String, Integer> positions = Map.of();
    private Map<String, String> wanted = Map.of();
    private final Set<String> interfaces = new HashSet<>();

    SarParser(ParsedTelemetry out) {
        super(out);
    }

    /** The block a header's columns belong to, or {@code null} when the line is not a sar header. */
    static Block classify(String[] columns) {
        List<String> cols = Arrays.asList(columns);
        if (cols.contains("CPU") && (cols.contains("%user") || cols.contains("%usr"))) {
            return Block.CPU;
        }
        if (cols.contains("runq-sz")) {
            return Block.QUEUE;
        }
        if (cols.contains("proc/s") || cols.contains("cswch/s")) {
            return Block.TASK;
        }
        if (cols.contains("IFACE") && cols.contains("rxpck/s")) {
            return Block.DEV;
        }
        if (cols.contains("active/s") && cols.contains("passive/s")) {
            return Block.TCP;
        }
        if (cols.contains("atmptf/s") || cols.contains("retrans/s") && cols.contains("estres/s")) {
            return Block.ETCP;
        }
        return Block.OTHER;
    }

    /** True for a header of one of the blocks this parser reads (used to recognise the format). */
    static boolean isKnownHeader(String line) {
        String[] tokens = split(line);
        Timed t = timed(tokens);
        if (t == null || t.timeOfDay < 0 || nonNumeric(t.rest, 0) < 2 || line.contains("RESTART")) {
            return false;
        }
        return classify(t.rest) != Block.OTHER;
    }

    @Override
    public void line(String text) {
        String line = text.strip();
        if (line.isEmpty()) {
            out.ignored();
            return;
        }
        if (banner(line)) {
            out.ignored();
            return;
        }
        Timed t = timed(split(line));
        if (t == null) {
            out.ignored();
            return;
        }
        if (t.timeOfDay < 0) {
            out.skipped("BAD_TIME");
            return;
        }
        if (line.contains("RESTART")) {
            out.note("RESTART");
            out.ignored();
            return;
        }
        if (nonNumeric(t.rest, 0) >= 2) {
            header(t.rest);
            return;
        }
        row(t);
    }

    private void header(String[] cols) {
        block = classify(cols);
        columns = cols;
        positions = index(cols);
        markSectionStart(Arrays.asList(cols));
        wanted = switch (block) {
            case CPU -> metrics("%user", "sar.cpu.usr", "%usr", "sar.cpu.usr", "%system", "sar.cpu.sys", "%sys", "sar.cpu.sys",
                    "%iowait", "sar.cpu.iowait", "%idle", "sar.cpu.idle");
            case QUEUE -> metrics("runq-sz", "sar.runq", "plist-sz", "sar.plist", "ldavg-1", "sar.ldavg1", "blocked", "sar.blocked");
            case TASK -> metrics("proc/s", "sar.proc/s", "cswch/s", "sar.cswch/s");
            case DEV -> metrics("rxpck/s", "sar.rxpck/s", "txpck/s", "sar.txpck/s");
            case TCP -> metrics("active/s", "sar.active/s", "passive/s", "sar.passive/s", "iseg/s", "sar.iseg/s",
                    "oseg/s", "sar.oseg/s");
            case ETCP -> metrics("atmptf/s", "sar.atmptf/s", "estres/s", "sar.estres/s", "retrans/s", "sar.retrans/s",
                    "isegerr/s", "sar.isegerr/s", "orsts/s", "sar.orsts/s");
            case OTHER -> Map.of();
        };
        out.ignored();
    }

    private void row(Timed t) {
        if (block == null) {
            out.skipped("NO_HEADER");
            return;
        }
        if (block == Block.OTHER) {
            out.ignored();
            return;
        }
        if (t.rest.length != columns.length) {
            out.skipped("BAD_COLUMN_COUNT");
            return;
        }
        String label = null;
        if (block == Block.CPU) {
            label = t.rest[positions.get("CPU")];
            if (!label.equals("all")) {
                out.ignored();
                return;
            }
        } else if (block == Block.DEV) {
            label = t.rest[positions.get("IFACE")];
            if (!interfaces.contains(label)) {
                if (interfaces.size() >= MAX_INTERFACES) {
                    out.note("INTERFACE_CAP");
                    out.ignored();
                    return;
                }
            }
        }
        // Parse every wanted column before touching a series: a bad value skips the whole line.
        int n = 0;
        String[] keys = new String[wanted.size()];
        double[] values = new double[wanted.size()];
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            Integer at = positions.get(e.getKey());
            if (at == null) {
                continue;
            }
            double v = number(t.rest[at]);
            if (Double.isNaN(v)) {
                out.skipped("BAD_NUMBER");
                return;
            }
            String key = e.getValue() + (block == Block.DEV ? "|" + label : "");
            if (contains(keys, n, key)) {
                continue; // %user and %usr cannot both be in one header; keep the first
            }
            keys[n] = key;
            values[n] = v;
            n++;
        }
        Stamp stamp = stamp(t.timeOfDay);
        if (block == Block.DEV) {
            interfaces.add(label);
        }
        for (int i = 0; i < n; i++) {
            out.series(keys[i], Series.Kind.GAUGE).add(stamp, values[i]);
        }
        out.used();
    }

    private static boolean contains(String[] keys, int n, String key) {
        for (int i = 0; i < n; i++) {
            if (keys[i].equals(key)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void finish() {
        if (dateless()) {
            out.base(ParsedTelemetry.TimeBase.DATE_RELATIVE);
        }
        if (!out.hasSamples()) {
            out.reason("NO_SAMPLES");
        }
    }
}
