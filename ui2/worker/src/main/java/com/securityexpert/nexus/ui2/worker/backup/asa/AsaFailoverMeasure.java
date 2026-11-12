package com.securityexpert.nexus.ui2.worker.backup.asa;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one-time, PO-attended read-only measurement of the Cisco ASA failover reads
 * (PO_DECISION_RECORD_2026_11_13_FAILOVER_ASA_FORTIGATE_PHASE0.md item 4: "Onaylıyorum (tek çift, tek sefer)").
 *
 * <p>Off unless {@value #VARIABLE} names the device and the current time is inside its window:
 * {@code <device id>,<device id>@<from ISO-8601 instant>/<until ISO-8601 instant>}, the window at most 60 minutes.
 * Each listed device is measured at most once per worker process. Settings are read once, when the switch is built.
 *
 * <p>Only a shape is ever logged: IPv4/IPv6 addresses and MAC addresses become placeholders, a word is kept only when
 * it is ASA failover vocabulary (else {@code a}), every digit becomes {@code 9}. No host name, interface name, address,
 * serial or version value survives (AGENTS.md raw-evidence law).
 */
public final class AsaFailoverMeasure {

    public static final String VARIABLE = "UI2_ASA_FAILOVER_MEASURE";
    static final long MAX_WINDOW_SECONDS = 3600;

    /** The four approved literals, in the order they are sent, with their gate ids and timeouts. */
    public enum Read {
        SHOW_FAILOVER("asa_measure_show_failover", "show failover", 30, 160),
        SHOW_FAILOVER_STATE("asa_measure_show_failover_state", "show failover state", 30, 60),
        SHOW_FAILOVER_HISTORY("asa_measure_show_failover_history", "show failover history", 60, 80),
        SHOW_CONN_COUNT("asa_measure_show_conn_count", "show conn count", 30, 10);

        private final String gateId;
        private final String command;
        private final int timeoutS;
        private final int maxLines;

        Read(String gateId, String command, int timeoutS, int maxLines) {
            this.gateId = gateId;
            this.command = command;
            this.timeoutS = timeoutS;
            this.maxLines = maxLines;
        }

        public String gateId() { return gateId; }
        public String command() { return command; }
        public int timeoutS() { return timeoutS; }
        public int maxLines() { return maxLines; }
    }

    private static final System.Logger LOG = System.getLogger(AsaFailoverMeasure.class.getName());

    /** Nothing is ever measured. */
    public static final AsaFailoverMeasure OFF = new AsaFailoverMeasure(Set.of(), Instant.EPOCH, Instant.EPOCH);

    private final Set<String> devices;
    private final Instant from;
    private final Instant until;
    private final Set<String> measured = ConcurrentHashMap.newKeySet();

    AsaFailoverMeasure(Set<String> devices, Instant from, Instant until) {
        this.devices = Set.copyOf(devices);
        this.from = from;
        this.until = until;
    }

    public static AsaFailoverMeasure fromEnvironment() {
        return parse(System.getenv(VARIABLE));
    }

    /** A malformed, open-ended or over-long setting measures nothing (logged once, without its value). */
    static AsaFailoverMeasure parse(String value) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        String[] parts = value.strip().split("@", -1);
        String[] window = parts.length == 2 ? parts[1].split("/", -1) : new String[0];
        if (parts.length != 2 || window.length != 2) {
            LOG.log(System.Logger.Level.WARNING, "[ASA] MEASURE setting malformed; measurement off");
            return OFF;
        }
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (String id : parts[0].split(",", -1)) {
            String trimmed = id.strip();
            if (!trimmed.matches("[A-Za-z0-9-]{1,64}")) {
                LOG.log(System.Logger.Level.WARNING, "[ASA] MEASURE device list malformed; measurement off");
                return OFF;
            }
            ids.add(trimmed);
        }
        try {
            Instant f = Instant.parse(window[0].strip());
            Instant u = Instant.parse(window[1].strip());
            if (!u.isAfter(f) || u.getEpochSecond() - f.getEpochSecond() > MAX_WINDOW_SECONDS || ids.isEmpty() || ids.size() > 2) {
                LOG.log(System.Logger.Level.WARNING, "[ASA] MEASURE window or device count out of bounds; measurement off");
                return OFF;
            }
            return new AsaFailoverMeasure(ids, f, u);
        } catch (DateTimeParseException e) {
            LOG.log(System.Logger.Level.WARNING, "[ASA] MEASURE window malformed; measurement off");
            return OFF;
        }
    }

    /** True once per listed device, inside the window; every later call for that device is false. */
    public boolean claim(String deviceId, Instant now) {
        return deviceId != null && devices.contains(deviceId) && !now.isBefore(from) && now.isBefore(until)
                && measured.add(deviceId);
    }

    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?:/\\d{1,2})?\\b");
    private static final Pattern IPV6 = Pattern.compile("(?i)\\b(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4}(?:/\\d{1,3})?");
    private static final Pattern MAC = Pattern.compile("(?i)\\b[0-9a-f]{4}\\.[0-9a-f]{4}\\.[0-9a-f]{4}\\b");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z_]*");

    /** ASA failover output vocabulary kept verbatim in a shape (vendor field names and state words only). */
    static final Set<String> VOCABULARY = Set.of(
            "failover", "on", "off", "unit", "primary", "secondary", "lan", "interface", "interfaces", "up", "down",
            "poll", "frequency", "seconds", "milliseconds", "holdtime", "policy", "monitored", "of", "maximum", "mac",
            "address", "move", "notification", "version", "ours", "mate", "serial", "number", "last", "at", "this",
            "host", "other", "active", "standby", "ready", "failed", "cold", "sync", "config", "bulk", "disabled",
            "negotiation", "just", "normal", "waiting", "no", "link", "testing", "unknown", "not", "stateful",
            "logical", "update", "statistics", "status", "general", "sys", "cmd", "time", "rpc", "services", "tcp",
            "udp", "conn", "arp", "tbl", "xlate_timeout", "ipv", "nd", "vpn", "ikev", "sa", "p", "ctcp", "cudp",
            "sip", "session", "route", "router", "id", "user", "identity", "cts", "sgtname", "pac", "queue",
            "information", "cur", "max", "total", "recv", "q", "xmit", "xerr", "rcv", "rerr", "group", "state",
            "configuration", "communication", "done", "set", "failure", "reason", "comm", "backplane", "from", "to",
            "in", "use", "most", "used", "utc", "ago", "connections", "connection", "replication", "http", "health",
            "check", "file", "system", "none", "ifc", "events", "is", "the", "and", "for", "a", "an", "hardware",
            "model", "mode", "single", "multiple", "context", "contexts", "jan", "feb", "mar", "apr", "may", "jun",
            "jul", "aug", "sep", "oct", "nov", "dec", "mon", "tue", "wed", "thu", "fri", "sat", "sun", "inspection",
            "bfd", "monitoring", "rule", "timer", "auth", "data", "service", "license");

    /** The shape of one answer: at most {@code maxLines} non-empty lines, each projected by {@link #shapeLine}. */
    public static List<String> shape(String output, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (output == null) {
            return lines;
        }
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            if (lines.size() >= maxLines) {
                lines.add("[more]");
                break;
            }
            lines.add(shapeLine(line));
        }
        return lines;
    }

    static String shapeLine(String line) {
        String s = MAC.matcher(line).replaceAll("<mac>");
        s = IPV4.matcher(s).replaceAll("<ip>");
        s = IPV6.matcher(s).replaceAll("<ip6>");
        StringBuilder out = new StringBuilder();
        Matcher w = WORD.matcher(s);
        int last = 0;
        while (w.find()) {
            out.append(s, last, w.start());
            String word = w.group();
            boolean placeholder = w.start() > 0 && s.charAt(w.start() - 1) == '<'
                    && (word.equals("ip") || word.equals("ip6") || word.equals("mac"));
            out.append(placeholder || VOCABULARY.contains(word.toLowerCase(Locale.ROOT)) ? word : "a");
            last = w.end();
        }
        out.append(s.substring(last));
        return out.toString().replaceAll("[0-9]", "9").replaceAll("\\s+", " ").strip();
    }

    /** The two counts of {@code show conn count} ("N in use, M most used"), or empty when the answer has another shape. */
    public static Optional<long[]> connCount(String output) {
        if (output == null) {
            return Optional.empty();
        }
        Matcher m = Pattern.compile("(\\d+) in use, (\\d+) most used").matcher(output);
        return m.find() ? Optional.of(new long[] {Long.parseLong(m.group(1)), Long.parseLong(m.group(2))}) : Optional.empty();
    }
}
