package com.securityexpert.nexus.ui2.service.systemlog;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.SecretFile;

/**
 * One-off, read-only measurement: the "question funnel" of the log anomaly agent
 * (docs/design/LOG_QUESTION_FUNNEL_MEASUREMENT_2026_11_10.md). It walks stored system-log chunks device by device,
 * builds message templates, applies a draft of the deterministic detectors of LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md
 * section 4.4 and counts how many lines, findings and device-hours would or would not become a question to a model.
 *
 * <p>Privacy: raw lines stay in this JVM. It prints fixed labels and numbers only; every printed field must match
 * {@code [a-z0-9_.]+} ({@link #emit}). No template text, word, name, address or hash is printed. Database access is
 * read-only; the artefact store is only read; nothing is written and no model is called.</p>
 *
 * <p>Every keyword class below is PROVISIONAL and was written without reading log text. A class name is a label
 * for counting, not a statement about vendor semantics.</p>
 */
public final class LogFunnelMeasure {

    static final String[] DEFAULT_SOURCES = {"cp_messages", "cp_cpwd_elg", "cp_cpd_elg", "cp_fwd_elg", "cp_fwk_elg",
            "pan_system"};
    static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");

    static int windowDays = 35;
    static int testDays = 7;
    static int minLearnDays = 7;
    static int maxDevices = 0;
    static int maxKeysPerDevice = 60_000;
    static long maxSeconds = 2400;
    static String[] sources = DEFAULT_SOURCES;
    static Set<String> tech = new HashSet<>();

    /** Semantic line classes in priority order (the first matching class wins). PROVISIONAL keyword classes. */
    enum Cls {
        crash, proc_restart, ha_state, link, routing, vpn, auth_admin, config_change, resource, license_update,
        time_dns, log_transport, cron_routine, other
    }

    static final int SEV_ERR = 1;
    static final int SEV_WARN = 2;
    static final Map<String, Integer> KW = new HashMap<>();
    static final Map<String, Integer> BIGRAM = new HashMap<>();
    static final Set<String> BIGRAM_SECOND = new HashSet<>();
    static final Set<String> ERR_WORDS = Set.of("error", "errors", "err", "failed", "failure", "failures", "fail",
            "fails", "critical", "crit", "alert", "emerg", "emergency", "fatal", "panic");
    static final Set<String> WARN_WORDS = Set.of("warn", "warning", "warnings");
    static final Map<String, Cls> PROC_CLS = new HashMap<>();
    static final Map<String, Cls> PAN_SUBTYPE_CLS = new HashMap<>();
    static final Set<String> PAN_SUBTYPE_ALLOW = new HashSet<>();
    static final Set<String> PROC_ALLOW = Set.of("kernel", "sshd", "crond", "cron", "anacron", "cpwd", "cpd", "fwd",
            "fwk", "fw", "fwm", "routed", "vpnd", "iked", "ntpd", "chronyd", "syslogd", "rsyslogd", "xpand", "confd",
            "clish", "monitord", "snmpd", "httpd", "httpd2", "cphaprob", "cphamcset", "mpdaemon", "dhcpd", "dhclient",
            "pm", "cloningd", "rtmd", "usrchkd", "pdpd", "pepd", "ted", "wsdnsd", "rad", "lpd", "cprid", "cpridd",
            "cpca", "logrotate", "su", "sudo", "login", "systemd", "init", "udevd", "auditd", "xinetd", "postfix",
            "sendmail", "smartd", "ipmievd", "acpid", "nscd", "netflowd", "cpview", "cpviewd", "hcp", "spike_detective",
            "cp_file_convertd", "scanengine_b", "in.asessiond", "in.acapd", "mta_monitor", "api", "cpm", "fwssd",
            "dlpu", "cposd", "sxl", "ppk", "nohdr", "noproc");

    static void kw(Cls c, String... words) {
        for (String w : words) KW.merge(w, 1 << c.ordinal(), (a, b) -> a | b);
    }

    static void bigram(Cls c, String first, String second) {
        BIGRAM.merge(first + " " + second, 1 << c.ordinal(), (a, b) -> a | b);
        BIGRAM_SECOND.add(second);
    }

    static {
        kw(Cls.crash, "panic", "oops", "segfault", "segv", "sigsegv", "sigabrt", "sigbus", "coredump", "core-dump",
                "lockup", "oom", "oom-killer", "crash", "crashed", "crashing", "traceback", "abort", "aborted");
        bigram(Cls.crash, "core", "dumped");
        bigram(Cls.crash, "dumped", "core");
        bigram(Cls.crash, "general", "protection");
        bigram(Cls.crash, "call", "trace");
        bigram(Cls.crash, "kernel", "bug");
        bigram(Cls.crash, "killed", "process");
        bigram(Cls.crash, "of", "memory");
        kw(Cls.proc_restart, "restart", "restarts", "restarted", "restarting", "respawn", "respawning", "terminated",
                "exited", "died");
        kw(Cls.ha_state, "failover", "fail-over", "failback", "clusterxl", "cphaprob", "cphastart", "cphastop",
                "pnote", "ccp", "fwha", "ha", "ha1", "ha2", "ha3", "preempt", "preemption", "split-brain",
                "heartbeat", "standby", "high-availability");
        bigram(Cls.ha_state, "state", "change");
        bigram(Cls.ha_state, "state", "changed");
        kw(Cls.link, "carrier", "duplex", "flap", "flapping", "lacp", "linkdown", "linkup", "link-state");
        bigram(Cls.link, "link", "down");
        bigram(Cls.link, "link", "up");
        bigram(Cls.link, "is", "down");
        bigram(Cls.link, "is", "up");
        kw(Cls.routing, "bgp", "ospf", "ospf3", "rip", "pim", "igmp", "isis", "bfd", "neighbor", "neighbour",
                "adjacency", "route", "routes", "rib", "nexthop", "next-hop");
        kw(Cls.vpn, "ike", "ikev1", "ikev2", "ipsec", "isakmp", "vpn", "tunnel", "tunnels", "phase-1", "phase-2",
                "esp", "globalprotect", "ssl-vpn");
        kw(Cls.auth_admin, "login", "logins", "logout", "logged", "accepted", "password", "passwd", "authentication",
                "authenticated", "pam", "pam_unix", "sudo", "su", "tacacs", "radius", "ldap", "auth", "admin",
                "administrator");
        bigram(Cls.auth_admin, "session", "opened");
        bigram(Cls.auth_admin, "session", "closed");
        kw(Cls.config_change, "commit", "committed", "commits", "config", "configuration", "configured");
        bigram(Cls.config_change, "policy", "install");
        bigram(Cls.config_change, "policy", "installed");
        bigram(Cls.config_change, "policy", "installation");
        bigram(Cls.config_change, "install", "policy");
        bigram(Cls.config_change, "installing", "policy");
        bigram(Cls.config_change, "fetch", "policy");
        kw(Cls.resource, "cpu", "memory", "mem", "disk", "swap", "temperature", "fan", "fans", "psu", "voltage",
                "inode", "inodes", "filesystem", "partition");
        kw(Cls.license_update, "license", "licenses", "licence", "licensing", "contract", "contracts", "update",
                "updates", "updated", "upgrade", "upgraded", "download", "downloaded", "antivirus", "wildfire",
                "signature", "signatures");
        kw(Cls.time_dns, "ntp", "ntpd", "ntpdate", "chrony", "chronyd", "dns", "resolver", "dnsproxy");
        kw(Cls.log_transport, "syslog", "syslogd", "rsyslogd", "logging", "log-forwarding", "logserver", "logd");
        bigram(Cls.log_transport, "log", "server");
        kw(Cls.cron_routine, "cron", "crond", "crontab", "anacron", "logrotate", "run-parts");

        PROC_CLS.put("sshd", Cls.auth_admin);
        PROC_CLS.put("login", Cls.auth_admin);
        PROC_CLS.put("su", Cls.auth_admin);
        PROC_CLS.put("sudo", Cls.auth_admin);
        PROC_CLS.put("clish", Cls.auth_admin);
        PROC_CLS.put("crond", Cls.cron_routine);
        PROC_CLS.put("cron", Cls.cron_routine);
        PROC_CLS.put("anacron", Cls.cron_routine);
        PROC_CLS.put("logrotate", Cls.cron_routine);
        PROC_CLS.put("ntpd", Cls.time_dns);
        PROC_CLS.put("chronyd", Cls.time_dns);
        PROC_CLS.put("vpnd", Cls.vpn);
        PROC_CLS.put("iked", Cls.vpn);

        for (String s : new String[] {"ha"}) PAN_SUBTYPE_CLS.put(s, Cls.ha_state);
        for (String s : new String[] {"vpn", "globalprotect", "gp", "ras", "satd"}) PAN_SUBTYPE_CLS.put(s, Cls.vpn);
        for (String s : new String[] {"routing", "bgp", "ospf", "bfd", "pbf"}) PAN_SUBTYPE_CLS.put(s, Cls.routing);
        for (String s : new String[] {"auth", "userid", "user-id"}) PAN_SUBTYPE_CLS.put(s, Cls.auth_admin);
        for (String s : new String[] {"dhcp", "dnsproxy", "ntpd"}) PAN_SUBTYPE_CLS.put(s, Cls.time_dns);
        for (String s : new String[] {"port", "lacp", "lldp"}) PAN_SUBTYPE_CLS.put(s, Cls.link);
        for (String s : new String[] {"hw"}) PAN_SUBTYPE_CLS.put(s, Cls.resource);
        for (String s : new String[] {"wildfire", "url-filtering", "antivirus", "upgrade", "content"}) {
            PAN_SUBTYPE_CLS.put(s, Cls.license_update);
        }
        for (String s : new String[] {"syslog", "logging"}) PAN_SUBTYPE_CLS.put(s, Cls.log_transport);
        PAN_SUBTYPE_ALLOW.addAll(PAN_SUBTYPE_CLS.keySet());
        PAN_SUBTYPE_ALLOW.addAll(List.of("general", "crypto", "sslmgr", "tls", "dos", "fb", "iot", "device-telemetry",
                "pppoe", "sslvpn", "dynamic-updates", "nat", "ntp", "vsys", "ocsp", "packet"));
    }

    // ------------------------------------------------------------------ primitive long -> int map
    static final class LongIntMap {
        long[] keys = new long[1 << 12];
        int[] vals = new int[1 << 12];
        boolean[] used = new boolean[1 << 12];
        int size;

        int slot(long k) {
            long h = k * 0x9E3779B97F4A7C15L;
            int i = (int) (h >>> 32) & (keys.length - 1);
            while (used[i] && keys[i] != k) i = (i + 1) & (keys.length - 1);
            return i;
        }

        int add(long k, int delta) {
            if (size * 2 >= keys.length) grow();
            int i = slot(k);
            if (!used[i]) {
                used[i] = true;
                keys[i] = k;
                size++;
            }
            long v = (long) vals[i] + delta;
            vals[i] = v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
            return vals[i];
        }

        int get(long k) {
            int i = slot(k);
            return used[i] ? vals[i] : 0;
        }

        void grow() {
            long[] ok = keys;
            int[] ov = vals;
            boolean[] ou = used;
            keys = new long[ok.length * 2];
            vals = new int[ok.length * 2];
            used = new boolean[ok.length * 2];
            size = 0;
            for (int i = 0; i < ok.length; i++) {
                if (ou[i]) {
                    int j = slot(ok[i]);
                    used[j] = true;
                    keys[j] = ok[i];
                    vals[j] = ov[i];
                    size++;
                }
            }
        }
    }

    static final class IntList {
        int[] a = new int[16];
        int n;

        void add(int v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }
    }

    // ------------------------------------------------------------------ line analysis
    static final Pattern MSG_HEADER = Pattern.compile(
            "^([A-Z][a-z]{2})\\s+(\\d{1,2})\\s+(\\d{2}):(\\d{2}):(\\d{2})\\s+(\\d{4})\\s+(\\S+)\\s+(.*)$", Pattern.DOTALL);
    static final Pattern MSG_PROC = Pattern.compile("^([A-Za-z0-9_./\\-]+?)(?:\\[(\\d+)\\])?:\\s?(.*)$", Pattern.DOTALL);
    static final Pattern ELG_HEADER = Pattern.compile(
            "^\\[([^\\s\\]]+)[^\\]]*\\]@[^\\s\\[\\]]*\\[(\\d{1,2}) ([A-Za-z]{3}) (\\d{1,2}):(\\d{2}):(\\d{2})\\]\\s*(.*)$",
            Pattern.DOTALL);
    static final Pattern FWK_HEADER = Pattern.compile(
            "^\\[(\\d{1,2}) ([A-Za-z]{3}) (\\d{1,2}):(\\d{2}):(\\d{2})\\](?:\\[[^\\]]*\\];\\[[^\\]]*\\];)?\\s*(.*)$",
            Pattern.DOTALL);
    static final Pattern PAN_TIME = Pattern.compile("^(\\d{4})/(\\d{2})/(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})$");
    static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![\\d.])");
    static final Pattern IFACE = Pattern.compile(
            "^(?:eth|bond|lo|mgmt|sync|wrp|wrpj|vlan|ae|ethernet|tunnel|loopback|vti|br)\\d[a-z0-9./:\\-]*$");
    static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
    static final JsonFactory JSON = new JsonFactory();
    static final Map<String, Integer> PAN_KEYS = Map.of("receive_time", 0, "severity", 1, "subtype", 2, "eventid", 3,
            "module", 4, "opaque", 5, "type", 6);
    static final int MAX_LINE_CHARS = 4096;
    static final int MAX_TYPED = 4_000_000;
    static final int C_LINES = 0;
    static final int C_UNPARSED = 1;
    static final int C_TIME_ON_LINE = 2;
    static final int C_TIME_INHERITED = 3;
    static final int C_TIME_COLLECTION = 4;
    static final int C_OVER_CAP = 5;
    static final int MAX_TEMPLATE_TOKENS = 40;

    static final long FNV_OFFSET = 0xcbf29ce484222325L;
    static final long FNV_PRIME = 0x100000001b3L;

    static long fnv(long h, String s) {
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= FNV_PRIME;
        }
        h ^= 0x1f;
        h *= FNV_PRIME;
        return h;
    }

    static int month(String m) {
        String l = m.toLowerCase(Locale.ROOT);
        for (int i = 0; i < 12; i++) if (MONTHS[i].equals(l)) return i + 1;
        return 0;
    }

    /** Result of analysing one message text. */
    static final class Info {
        long hv;
        long ht;
        int clsMask;
        int sev;
    }

    /** Normalises one message into the vocabulary template hash (hv), the typed template hash (ht), classes and severity. */
    static void analyse(String msg, long seed, Info out) {
        long hv = seed;
        long ht = seed;
        int mask = 0;
        int sev = 0;
        int n = msg.length();
        int i = 0;
        int tokens = 0;
        String prev = "";
        while (i < n) {
            while (i < n && Character.isWhitespace(msg.charAt(i))) i++;
            int s = i;
            while (i < n && !Character.isWhitespace(msg.charAt(i))) i++;
            if (s == i) break;
            String raw = msg.substring(s, i);
            // classification words: [a-z0-9_-] runs inside the token
            String lower = raw.toLowerCase(Locale.ROOT);
            int j = 0;
            int m = lower.length();
            while (j < m) {
                while (j < m && !wordChar(lower.charAt(j))) j++;
                int ws = j;
                while (j < m && wordChar(lower.charAt(j))) j++;
                if (ws == j) break;
                int we = j;
                while (ws < we && (lower.charAt(ws) == '-' || lower.charAt(ws) == '_')) ws++;
                while (we > ws && (lower.charAt(we - 1) == '-' || lower.charAt(we - 1) == '_')) we--;
                if (ws == we) continue;
                String w = lower.substring(ws, we);
                Integer k = KW.get(w);
                if (k != null) mask |= k;
                if (BIGRAM_SECOND.contains(w) && !prev.isEmpty()) {
                    Integer b = BIGRAM.get(prev + " " + w);
                    if (b != null) mask |= b;
                }
                if (ERR_WORDS.contains(w)) sev |= SEV_ERR;
                else if (WARN_WORDS.contains(w)) sev |= SEV_WARN;
                prev = w;
            }
            if (tokens < MAX_TEMPLATE_TOKENS) {
                tokens++;
                String core = strip(raw);
                if (!core.isEmpty()) {
                    int eq = core.indexOf('=');
                    if (eq > 0 && eq < core.length() - 1) {
                        String l = core.substring(0, eq);
                        String r = core.substring(eq + 1);
                        hv = fnv(fnv(hv, norm(l, true)), norm(r, true));
                        ht = fnv(fnv(ht, norm(l, false)), norm(r, false));
                    } else {
                        hv = fnv(hv, norm(core, true));
                        ht = fnv(ht, norm(core, false));
                    }
                }
            }
        }
        out.hv = hv;
        out.ht = ht;
        out.clsMask = mask;
        out.sev = sev;
    }

    static boolean wordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    static String strip(String t) {
        int a = 0;
        int b = t.length();
        while (a < b && !Character.isLetterOrDigit(t.charAt(a))) a++;
        while (b > a && !Character.isLetterOrDigit(t.charAt(b - 1))) b--;
        return t.substring(a, b);
    }

    /** One token as a template element: a typed placeholder, a kept word, or {@code <w>} (vocabulary form only). */
    static String norm(String x, boolean vocabulary) {
        int letters = 0;
        int digits = 0;
        int hexLetters = 0;
        int colons = 0;
        boolean slash = false;
        for (int i = 0; i < x.length(); i++) {
            char c = x.charAt(i);
            if (c >= '0' && c <= '9') digits++;
            else if (Character.isLetter(c)) {
                letters++;
                char lc = Character.toLowerCase(c);
                if (lc >= 'a' && lc <= 'f') hexLetters++;
            } else if (c == ':') colons++;
            else if (c == '/') slash = true;
        }
        if (letters == 0 && digits == 0) return "<s>";
        if (letters == 0) return IPV4.matcher(x).find() ? "<ip>" : "<n>";
        if (slash) return "<p>";
        if (x.length() > 2 && x.charAt(0) == '0' && (x.charAt(1) == 'x' || x.charAt(1) == 'X')) return "<x>";
        if (letters == hexLetters && digits > 0 && colons == 5 && x.length() == 17) return "<mac>";
        if (letters == hexLetters && digits > 0 && colons >= 2) return "<ip>";
        if (letters == hexLetters && digits > 0 && x.length() >= 8 && colons == 0) return "<x>";
        String lower = x.toLowerCase(Locale.ROOT);
        if (digits > 0) {
            if (IFACE.matcher(lower).matches()) return "<if>";
            if (vocabulary) return "<w>";
            StringBuilder sb = new StringBuilder(lower.length());
            boolean lastDigit = false;
            for (int i = 0; i < lower.length(); i++) {
                char c = lower.charAt(i);
                boolean d = c >= '0' && c <= '9';
                if (d) {
                    if (!lastDigit) sb.append('0');
                } else {
                    sb.append(c);
                }
                lastDigit = d;
            }
            return sb.toString();
        }
        if (!vocabulary) return lower;
        if (tech.contains(lower)) return lower;
        int start = 0;
        boolean all = true;
        boolean any = false;
        for (int i = 0; i <= lower.length(); i++) {
            if (i == lower.length() || !(lower.charAt(i) >= 'a' && lower.charAt(i) <= 'z')) {
                if (i > start) {
                    any = true;
                    if (!tech.contains(lower.substring(start, i))) {
                        all = false;
                        break;
                    }
                }
                start = i + 1;
            }
        }
        return any && all ? lower : "<w>";
    }

    static Cls pick(int mask) {
        if (mask == 0) return Cls.other;
        return Cls.values()[Integer.numberOfTrailingZeros(mask)];
    }

    // ------------------------------------------------------------------ engine
    static final int T_OLD = -2;
    static final int T_FUTURE = -3;

    static final class Key {
        int[] hours;
        IntList mins;
        int first = Integer.MAX_VALUE;
        int total;
        int testTotal;
        byte src;
        byte cls;
        byte sev;
        boolean old;
    }

    // device-hour finding bits
    static final int B_CRASH = 1;
    static final int B_RESTART = 1 << 1;
    static final int B_BURST_HA = 1 << 2;
    static final int B_BURST_LINK = 1 << 3;
    static final int B_RATE = 1 << 4;
    static final int B_RATE_EXCL = 1 << 5;
    static final int B_RATE_STRICT = 1 << 6;
    static final int B_RATE_LOOSE = 1 << 7;
    static final int B_NEW_RARE_SEV = 1 << 8;
    static final int B_NEW_RARE = 1 << 9;
    static final int B_NEW_RARE_EXCL = 1 << 10;
    static final int B_NEW_COMMON = 1 << 11;
    static final int B_NEW_SIG = 1 << 12;
    static final int SIG_BITS = B_CRASH | B_RESTART | B_BURST_HA | B_BURST_LINK;

    record NewEv(int dev, int src, long hv, int cls, int sev, int hour, int lines) {
    }

    static final class DevOut {
        boolean pan;
        int[] mask;
        boolean[] observedDay;
    }

    static final class Engine {
        final int S = sources.length;
        final int W = windowDays * 24;
        final int testStart = (windowDays - testDays) * 24;
        final int testHours = testDays * 24;
        final LocalDateTime base;
        final TreeMap<String, long[]> agg = new TreeMap<>();
        final LongIntMap[] prev = new LongIntMap[S];
        final LongIntMap[] tplLines = new LongIntMap[S];
        final LongIntMap[] typed = new LongIntMap[S];
        final int[] devicesWithSource = new int[S];
        final List<NewEv> newEvents = new ArrayList<>();
        final List<DevOut> devs = new ArrayList<>();
        final LongIntMap ipDevices = new LongIntMap();
        final LongIntMap ipLines = new LongIntMap();
        final List<int[]> coverage = new ArrayList<>(); // {src, learnDays, testDays}
        final Info info = new Info();
        final String[] pan = new String[7];
        // per-line counters, kept as arrays (labels are attached in report())
        final long[][] cov = new long[S][6];
        final long[][][][] clsCount = new long[S][Cls.values().length][3][4];
        final List<HashMap<String, long[]>> procCount = new ArrayList<>();
        final HashMap<String, long[]> panSubtype = new HashMap<>();
        long authLines;
        long authLinesWithIp;

        // per device
        int dev = -1;
        boolean devPan;
        HashMap<Long, Key> keys;
        boolean[][] covered;
        LongIntMap devIps;
        long[] devLines;
        long[] devTimed;
        boolean[] activeHour;

        // per chunk
        int chunkHour;
        int lastHour;
        int lastMinute;
        int chunkYear;
        LocalDate chunkDate;

        Engine(LocalDate endDay) {
            base = endDay.minusDays(windowDays - 1L).atStartOfDay();
            for (int i = 0; i < S; i++) {
                procCount.add(new HashMap<>());
                prev[i] = new LongIntMap();
                tplLines[i] = new LongIntMap();
                typed[i] = new LongIntMap();
            }
        }

        void count(String key, long delta) {
            agg.computeIfAbsent(key, k -> new long[1])[0] += delta;
        }

        void startDevice(boolean isPan) {
            if (dev >= 0) finishDevice();
            dev++;
            devPan = isPan;
            keys = new HashMap<>();
            covered = new boolean[S][windowDays];
            devIps = new LongIntMap();
            devLines = new long[S];
            devTimed = new long[S];
            activeHour = new boolean[testHours];
        }

        void startChunk(long collectedEpoch) {
            LocalDateTime c = LocalDateTime.ofInstant(Instant.ofEpochSecond(collectedEpoch), ZONE);
            chunkDate = c.toLocalDate();
            chunkYear = c.getYear();
            long h = ChronoUnit.HOURS.between(base, c);
            chunkHour = h < 0 ? T_OLD : h >= W ? T_FUTURE : (int) h;
            lastHour = Integer.MIN_VALUE;
            lastMinute = 0;
        }

        /** Hour index in the window, T_OLD, T_FUTURE, or Integer.MIN_VALUE when the parts are not a date. */
        int hourOf(int year, int mon, int day, int hh, int mm) {
            if (mon < 1 || mon > 12 || day < 1 || day > 31 || hh > 23 || mm > 59) return Integer.MIN_VALUE;
            LocalDateTime t;
            try {
                t = LocalDateTime.of(year, mon, day, hh, mm);
            } catch (RuntimeException e) {
                return Integer.MIN_VALUE;
            }
            long h = ChronoUnit.HOURS.between(base, t);
            if (t.isBefore(base)) return T_OLD;
            return h >= W ? T_FUTURE : (int) h;
        }

        /** Year of a line that prints no year: the latest date with that day and month not after the chunk's date. */
        int yearFor(int mon, int day) {
            if (mon > chunkDate.getMonthValue() || (mon == chunkDate.getMonthValue() && day > chunkDate.getDayOfMonth() + 1)) {
                return chunkYear - 1;
            }
            return chunkYear;
        }

        void line(int src, String rawLine) {
            String line = rawLine.length() > MAX_LINE_CHARS ? rawLine.substring(0, MAX_LINE_CHARS) : rawLine;
            String source = sources[src];
            cov[src][C_LINES]++;
            String proc = "nohdr";
            String msg = line;
            int hour = Integer.MIN_VALUE;
            int minute = 0;
            int forced = -1;
            int sevExtra = 0;
            boolean timed = true;
            long seed = fnv(FNV_OFFSET, source);
            if (devPan) {
                Arrays.fill(pan, null);
                if (!parsePan(line, pan)) {
                    cov[src][C_UNPARSED]++;
                    proc = "noproc";
                } else {
                    proc = "noproc";
                    if (pan[0] != null) {
                        Matcher t = PAN_TIME.matcher(pan[0]);
                        if (t.matches()) {
                            hour = hourOf(Integer.parseInt(t.group(1)), Integer.parseInt(t.group(2)),
                                    Integer.parseInt(t.group(3)), Integer.parseInt(t.group(4)), Integer.parseInt(t.group(5)));
                            minute = Integer.parseInt(t.group(5));
                        }
                    }
                    String sub = pan[2] == null ? "" : pan[2].toLowerCase(Locale.ROOT);
                    panSubtype.computeIfAbsent(PAN_SUBTYPE_ALLOW.contains(sub) ? sub.replace('-', '_') : "other_subtype",
                            x -> new long[1])[0]++;
                    Cls c = PAN_SUBTYPE_CLS.get(sub);
                    if (c != null) forced = c.ordinal();
                    String sv = pan[1] == null ? "" : pan[1].toLowerCase(Locale.ROOT);
                    if (sv.equals("high") || sv.equals("critical")) sevExtra = SEV_ERR;
                    else if (sv.equals("medium")) sevExtra = SEV_WARN;
                    seed = fnv(fnv(fnv(seed, sub), pan[3] == null ? "" : pan[3]), pan[4] == null ? "" : pan[4]);
                    msg = (pan[3] == null ? "" : pan[3] + " ") + (pan[5] == null ? "" : pan[5]);
                }
            } else if (source.equals("cp_messages")) {
                Matcher h = MSG_HEADER.matcher(line);
                if (h.matches()) {
                    int mon = month(h.group(1));
                    hour = hourOf(Integer.parseInt(h.group(6)), mon, Integer.parseInt(h.group(2)), Integer.parseInt(h.group(3)),
                            Integer.parseInt(h.group(4)));
                    minute = Integer.parseInt(h.group(4));
                    String rest = h.group(8);
                    Matcher p = MSG_PROC.matcher(rest);
                    if (p.matches()) {
                        proc = p.group(1).toLowerCase(Locale.ROOT);
                        int slash = proc.lastIndexOf('/');
                        if (slash >= 0) proc = proc.substring(slash + 1);
                        msg = p.group(3);
                    } else {
                        proc = "noproc";
                        msg = rest;
                    }
                }
            } else {
                Matcher h = ELG_HEADER.matcher(line);
                if (h.matches()) {
                    int mon = month(h.group(3));
                    int day = Integer.parseInt(h.group(2));
                    hour = hourOf(yearFor(mon, day), mon, day, Integer.parseInt(h.group(4)), Integer.parseInt(h.group(5)));
                    minute = Integer.parseInt(h.group(5));
                    proc = h.group(1).toLowerCase(Locale.ROOT);
                    msg = h.group(7);
                } else {
                    Matcher f = source.equals("cp_fwk_elg") ? FWK_HEADER.matcher(line) : null;
                    if (f != null && f.matches()) {
                        int mon = month(f.group(2));
                        int day = Integer.parseInt(f.group(1));
                        hour = hourOf(yearFor(mon, day), mon, day, Integer.parseInt(f.group(3)), Integer.parseInt(f.group(4)));
                        minute = Integer.parseInt(f.group(4));
                        proc = "fwk";
                        msg = f.group(6);
                    }
                }
            }
            devLines[src]++;
            if (hour == Integer.MIN_VALUE) {
                // no usable time on the line: inherit the last one of the chunk, else the collection time
                timed = false;
                if (lastHour != Integer.MIN_VALUE) {
                    hour = lastHour;
                    minute = lastMinute;
                    cov[src][C_TIME_INHERITED]++;
                } else {
                    hour = chunkHour;
                    minute = 0;
                    cov[src][C_TIME_COLLECTION]++;
                }
            } else {
                lastHour = hour;
                lastMinute = minute;
                devTimed[src]++;
                cov[src][C_TIME_ON_LINE]++;
            }
            analyse(msg, fnv(seed, proc), info);
            if (devPan) {
                // Palo Alto: the detection key is the vendor enumeration (subtype, event id, module); the text
                // pattern under it is counted as the finer template only.
                info.ht = info.hv;
                info.hv = fnv(seed, "pan");
            }
            int mask = info.clsMask;
            Cls cls;
            if ((mask & (1 << Cls.crash.ordinal())) != 0) cls = Cls.crash;
            else if (forced >= 0) cls = Cls.values()[forced];
            else if (PROC_CLS.containsKey(proc)) cls = PROC_CLS.get(proc);
            else cls = pick(mask);
            if (cls == Cls.proc_restart && !source.equals("cp_cpwd_elg") && (mask & ~(1 << Cls.proc_restart.ordinal())) != 0) {
                cls = pick(mask & ~(1 << Cls.proc_restart.ordinal())); // outside cpwd, a restart word alone decides only when nothing else matched
            }
            int sev = info.sev | sevExtra;
            clsCount[src][cls.ordinal()][(sev & SEV_ERR) != 0 ? 0 : (sev & SEV_WARN) != 0 ? 1 : 2][hour == T_OLD ? 0
                    : hour == T_FUTURE ? 1 : hour >= testStart ? 3 : 2]++;
            procCount.get(src).computeIfAbsent(PROC_ALLOW.contains(proc) ? proc : "other_process", x -> new long[1])[0]++;
            if (typed[src].size < MAX_TYPED) typed[src].add(info.ht, 1);
            if (cls == Cls.auth_admin) {
                authLines++;
                Matcher ip = IPV4.matcher(msg);
                if (ip.find()) {
                    authLinesWithIp++;
                    long ih = fnv(FNV_OFFSET, ip.group());
                    ipLines.add(ih, 1);
                    devIps.add(ih, 1);
                }
            }
            if (hour == T_FUTURE) return;
            Key k = keys.get(info.hv);
            if (k == null) {
                if (keys.size() >= maxKeysPerDevice) {
                    cov[src][C_OVER_CAP]++;
                    return;
                }
                k = new Key();
                k.src = (byte) src;
                k.cls = (byte) cls.ordinal();
                keys.put(info.hv, k);
            }
            k.sev |= (byte) sev;
            k.total++;
            if (hour == T_OLD) {
                k.old = true;
                return;
            }
            covered[src][hour / 24] = true;
            if (k.hours == null) k.hours = new int[W];
            k.hours[hour]++;
            if (hour < k.first) k.first = hour;
            if (hour >= testStart) {
                k.testTotal++;
                activeHour[hour - testStart] = true;
                if (timed && (cls == Cls.ha_state || cls == Cls.link)) { // a burst needs the line's own minute
                    if (k.mins == null) k.mins = new IntList();
                    if (k.mins.n < 20_000) k.mins.add((hour - testStart) * 60 + minute);
                }
            }
        }

        static boolean parsePan(String line, String[] out) {
            try (JsonParser p = JSON.createParser(line)) {
                if (p.nextToken() != JsonToken.START_OBJECT) return false;
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String name = p.currentName();
                    JsonToken v = p.nextToken();
                    if (v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY) {
                        p.skipChildren();
                        continue;
                    }
                    Integer idx = PAN_KEYS.get(name);
                    if (idx != null) out[idx] = p.getValueAsString();
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        void finishDevice() {
            String vendor = devPan ? "pan" : "cp";
            int[] learnDays = new int[S];
            int[] testCov = new int[S];
            DevOut o = new DevOut();
            o.pan = devPan;
            o.mask = new int[testHours];
            o.observedDay = new boolean[testDays];
            int learnDayCount = windowDays - testDays;
            for (int s = 0; s < S; s++) {
                boolean any = false;
                for (int d = 0; d < windowDays; d++) {
                    if (!covered[s][d]) continue;
                    any = true;
                    if (d < learnDayCount) learnDays[s]++;
                    else {
                        testCov[s]++;
                        o.observedDay[d - learnDayCount] = true;
                    }
                }
                if (any) {
                    devicesWithSource[s]++;
                    coverage.add(new int[] {s, learnDays[s], testCov[s]});
                }
            }
            // an hourly rate needs lines that carry their own time: at least 80 % of this device's lines in the source
            boolean[] rateEvaluable = new boolean[S];
            for (int s = 0; s < S; s++) {
                if (devLines[s] == 0) continue;
                rateEvaluable[s] = devTimed[s] * 10 >= devLines[s] * 8;
                count("ratescope\t" + sources[s] + "\t" + (rateEvaluable[s] ? "devices_evaluable" : "devices_not_evaluable"), 1);
            }
            long active = 0;
            for (boolean b : activeHour) if (b) active++;
            count("hours\t" + vendor + "\tdevice_hours_with_lines", active);
            IntList haMins = new IntList();
            IntList linkMins = new IntList();
            for (Map.Entry<Long, Key> e : keys.entrySet()) {
                Key k = e.getValue();
                int s = k.src;
                String source = sources[s];
                long hv = e.getKey();
                prev[s].add(hv, 1);
                tplLines[s].add(hv, k.total);
                Cls cls = Cls.values()[k.cls];
                boolean excluded = cls == Cls.auth_admin || cls == Cls.cron_routine;
                boolean enoughHistory = learnDays[s] >= minLearnDays;
                int daysSeen = 0;
                if (k.hours != null) {
                    for (int d = 0; d < learnDayCount; d++) {
                        if (!covered[s][d]) continue;
                        for (int h = d * 24; h < d * 24 + 24; h++) {
                            if (k.hours[h] > 0) {
                                daysSeen++;
                                break;
                            }
                        }
                    }
                }
                boolean routine = enoughHistory && daysSeen * 10 >= learnDays[s] * 8;
                count("routine\t" + source + "\ttest_lines", k.testTotal);
                if (routine) count("routine\t" + source + "\ttest_lines_routine", k.testTotal);
                if (!enoughHistory) count("routine\t" + source + "\ttest_lines_short_history", k.testTotal);
                boolean isNew = enoughHistory && !k.old && k.first >= testStart && k.first != Integer.MAX_VALUE;
                if (isNew) {
                    if (newEvents.size() < 2_000_000) {
                        newEvents.add(new NewEv(dev, s, hv, k.cls, k.sev, k.first - testStart, k.testTotal));
                    } else {
                        count("new\tover_event_cap", 1);
                    }
                } else if (enoughHistory && rateEvaluable[s] && k.hours != null && k.testTotal >= 10) {
                    rate(k, s, source, cls, excluded, learnDayCount, o);
                }
                if (k.hours != null && k.testTotal > 0) {
                    boolean crash = cls == Cls.crash;
                    boolean restart = cls == Cls.proc_restart && source.equals("cp_cpwd_elg");
                    if (crash || restart) {
                        String label = crash ? "crash" : "restart";
                        for (int h = testStart; h < W; h++) {
                            if (k.hours[h] == 0) continue;
                            if (routine) {
                                count("sig\t" + label + "\t" + source + "\troutine_suppressed_lines", k.hours[h]);
                            } else {
                                count("sig\t" + label + "\t" + source + "\tlines", k.hours[h]);
                                o.mask[h - testStart] |= crash ? B_CRASH : B_RESTART;
                            }
                        }
                    }
                    if (k.mins != null) {
                        if (routine) {
                            count("sig\t" + (cls == Cls.ha_state ? "burst_ha" : "burst_link") + "\t" + source
                                    + "\troutine_suppressed_lines", k.mins.n);
                        } else {
                            IntList t = cls == Cls.ha_state ? haMins : linkMins;
                            for (int i = 0; i < k.mins.n; i++) t.add(k.mins.a[i]);
                        }
                    }
                }
            }
            bursts(haMins, B_BURST_HA, "burst_ha", o);
            bursts(linkMins, B_BURST_LINK, "burst_link", o);
            for (int i = 0; i < devIps.keys.length; i++) {
                if (devIps.used[i]) ipDevices.add(devIps.keys[i], 1);
            }
            count("meta\tdevices_" + vendor, 1);
            devs.add(o);
            keys = null;
            covered = null;
            devIps = null;
            devLines = null;
            devTimed = null;
            activeHour = null;
        }

        /** Rate change of one template against the device's own learning hours; three threshold sets. */
        void rate(Key k, int s, String source, Cls cls, boolean excluded, int learnDayCount, DevOut o) {
            int n = 0;
            int[] base = new int[learnDayCount * 24];
            long sum = 0;
            for (int d = 0; d < learnDayCount; d++) {
                if (!covered[s][d]) continue;
                for (int h = d * 24; h < d * 24 + 24; h++) {
                    base[n++] = k.hours[h];
                    sum += k.hours[h];
                }
            }
            if (n == 0) return;
            int[] sorted = Arrays.copyOf(base, n);
            Arrays.sort(sorted);
            double median = sorted[n / 2];
            int[] absDev = new int[n];
            for (int i = 0; i < n; i++) absDev[i] = (int) Math.abs(sorted[i] - median);
            Arrays.sort(absDev);
            double mad = absDev[n / 2];
            double mean = (double) sum / n;
            double scale = Math.max(1.4826 * mad, 1.0);
            double level = Math.max(Math.max(median, mean), 1.0);
            int[] minCount = {20, 50, 10};
            double[] minZ = {6, 10, 4};
            double[] mult = {5, 10, 3};
            String[] label = {"base", "strict", "loose"};
            int[] bit = {excluded ? B_RATE_EXCL : B_RATE, B_RATE_STRICT, B_RATE_LOOSE};
            for (int t = 0; t < 3; t++) {
                boolean prevHit = false;
                for (int h = testStart; h < W; h++) {
                    if (!covered[s][h / 24]) {
                        prevHit = false;
                        continue;
                    }
                    int x = k.hours[h];
                    boolean hit = x >= minCount[t] && (x - median) / scale >= minZ[t] && x >= mult[t] * level;
                    if (hit) {
                        count("rate\t" + label[t] + "\t" + source + "\t" + cls.name() + "\thours", 1);
                        if (!prevHit) count("rate\t" + label[t] + "\t" + source + "\t" + cls.name() + "\tfindings", 1);
                        if (t == 0 || !excluded) o.mask[h - testStart] |= bit[t];
                    }
                    prevHit = hit;
                }
            }
        }

        /** Three or more lines of one class within ten minutes; overlapping windows are one episode. */
        void bursts(IntList mins, int bit, String label, DevOut o) {
            if (mins.n < 3) return;
            int[] a = Arrays.copyOf(mins.a, mins.n);
            Arrays.sort(a);
            int lastEnd = Integer.MIN_VALUE;
            for (int i = 0; i + 2 < a.length; i++) {
                if (a[i + 2] - a[i] <= 10) {
                    if (a[i] > lastEnd) count("sig\t" + label + "\tepisodes", 1);
                    lastEnd = Math.max(lastEnd, a[i + 2]);
                    for (int h = a[i] / 60; h <= a[i + 2] / 60 && h < testHours; h++) o.mask[h] |= bit;
                }
            }
        }

        void finish() {
            if (dev >= 0 && keys != null) finishDevice();
            // new-template events, with fleet prevalence known now
            for (NewEv e : newEvents) {
                int devices = prev[e.src()].get(e.hv());
                int withSource = Math.max(devicesWithSource[e.src()], 1);
                String p = devices <= 1 ? "device_only" : devices * 2 >= withSource ? "fleet_common" : "fleet_some";
                Cls cls = Cls.values()[e.cls()];
                String sevLabel = (e.sev() & SEV_ERR) != 0 ? "sev_error" : (e.sev() & SEV_WARN) != 0 ? "sev_warn" : "sev_none";
                count("new\t" + sources[e.src()] + "\t" + p + "\t" + cls.name() + "\t" + sevLabel + "\tevents", 1);
                count("new\t" + sources[e.src()] + "\t" + p + "\t" + cls.name() + "\t" + sevLabel + "\tlines", e.lines());
                boolean excluded = cls == Cls.auth_admin || cls == Cls.cron_routine;
                // a rare new template of a signature class is covered by the signature finding, not asked again
                boolean signatureClass = cls == Cls.crash || cls == Cls.ha_state || cls == Cls.link
                        || (cls == Cls.proc_restart && sources[e.src()].equals("cp_cpwd_elg"));
                int bit;
                if (p.equals("fleet_common")) bit = B_NEW_COMMON;
                else if (excluded) bit = B_NEW_RARE_EXCL;
                else if (signatureClass) bit = B_NEW_SIG;
                else bit = e.sev() != 0 ? B_NEW_RARE_SEV : B_NEW_RARE;
                if (e.hour() >= 0 && e.hour() < testHours) devs.get(e.dev()).mask[e.hour()] |= bit;
            }
            // templates
            for (int s = 0; s < S; s++) {
                String source = sources[s];
                LongIntMap lines = tplLines[s];
                int[] v = new int[lines.size];
                int n = 0;
                long total = 0;
                long one = 0;
                long common = 0;
                long linesCommon = 0;
                long linesOne = 0;
                for (int i = 0; i < lines.keys.length; i++) {
                    if (!lines.used[i]) continue;
                    v[n++] = lines.vals[i];
                    total += lines.vals[i];
                    int devices = prev[s].get(lines.keys[i]);
                    if (devices <= 1) {
                        one++;
                        linesOne += lines.vals[i];
                    }
                    if (devices * 2 >= Math.max(devicesWithSource[s], 1)) {
                        common++;
                        linesCommon += lines.vals[i];
                    }
                }
                Arrays.sort(v);
                long top10 = 0;
                long top100 = 0;
                long top1000 = 0;
                for (int i = 0; i < n; i++) {
                    int x = v[n - 1 - i];
                    if (i < 10) top10 += x;
                    if (i < 100) top100 += x;
                    if (i < 1000) top1000 += x;
                }
                count("tpl\t" + source + "\tdevices", devicesWithSource[s]);
                count("tpl\t" + source + "\ttemplates_vocabulary", n);
                count("tpl\t" + source + "\ttemplates_typed", typed[s].size);
                count("tpl\t" + source + "\tkeyed_lines", total);
                count("tpl\t" + source + "\tlines_top10", top10);
                count("tpl\t" + source + "\tlines_top100", top100);
                count("tpl\t" + source + "\tlines_top1000", top1000);
                count("tpl\t" + source + "\ttemplates_one_device", one);
                count("tpl\t" + source + "\tlines_one_device", linesOne);
                count("tpl\t" + source + "\ttemplates_fleet_common", common);
                count("tpl\t" + source + "\tlines_fleet_common", linesCommon);
            }
            // coverage distribution
            for (int s = 0; s < S; s++) {
                List<Integer> learn = new ArrayList<>();
                List<Integer> test = new ArrayList<>();
                for (int[] c : coverage) {
                    if (c[0] != s) continue;
                    learn.add(c[1]);
                    test.add(c[2]);
                }
                if (learn.isEmpty()) continue;
                learn.sort(null);
                test.sort(null);
                String source = sources[s];
                count("days\t" + source + "\tlearn_min", learn.get(0));
                count("days\t" + source + "\tlearn_median", learn.get(learn.size() / 2));
                count("days\t" + source + "\tlearn_max", learn.get(learn.size() - 1));
                count("days\t" + source + "\ttest_min", test.get(0));
                count("days\t" + source + "\ttest_median", test.get(test.size() / 2));
                count("days\t" + source + "\ttest_max", test.get(test.size() - 1));
                long enough = learn.stream().filter(d -> d >= minLearnDays).count();
                count("days\t" + source + "\tdevices_enough_history", enough);
                count("days\t" + source + "\tdevices_short_history", learn.size() - enough);
            }
            // self noise: the address that occurs on the most devices in session lines
            long best = 0;
            int bestDevices = 0;
            for (int i = 0; i < ipDevices.keys.length; i++) {
                if (ipDevices.used[i] && ipDevices.vals[i] > bestDevices) {
                    bestDevices = ipDevices.vals[i];
                    best = ipDevices.keys[i];
                }
            }
            count("self\ttop_source_devices", bestDevices);
            count("self\ttop_source_lines", bestDevices == 0 ? 0 : ipLines.get(best));
            count("self\tdistinct_sources", ipDevices.size);
            policies();
        }

        /** Device-hours and episodes per policy; an episode is a run of consecutive qualifying hours on one device. */
        void policies() {
            String[] names = {"narrow", "base", "wide", "code_only", "excluded_only", "strict_rate", "loose_rate"};
            for (int p = 0; p < names.length; p++) {
                long[] perDay = new long[testDays];
                List<Integer> perDeviceDay = new ArrayList<>();
                for (DevOut o : devs) {
                    int[] startsPerDay = new int[testDays];
                    boolean prevHit = false;
                    for (int h = 0; h < testHours; h++) {
                        boolean hit = qualifies(p, o.mask[h]);
                        if (hit) {
                            count("q\t" + names[p] + "\t" + (o.pan ? "pan" : "cp") + "\tdevice_hours", 1);
                            if (!prevHit) {
                                count("q\t" + names[p] + "\t" + (o.pan ? "pan" : "cp") + "\tepisodes", 1);
                                startsPerDay[h / 24]++;
                                perDay[h / 24]++;
                            }
                        }
                        prevHit = hit;
                    }
                    for (int d = 0; d < testDays; d++) {
                        if (o.observedDay[d]) perDeviceDay.add(startsPerDay[d]);
                    }
                }
                perDeviceDay.sort(null);
                int n = perDeviceDay.size();
                count("qdist\t" + names[p] + "\tobserved_device_days", n);
                if (n > 0) {
                    String[] bucket = {"q0", "q1", "q2", "q3_5", "q6_10", "q11_plus"};
                    long[] b = new long[bucket.length];
                    long sum = 0;
                    for (int x : perDeviceDay) {
                        sum += x;
                        b[x == 0 ? 0 : x == 1 ? 1 : x == 2 ? 2 : x <= 5 ? 3 : x <= 10 ? 4 : 5]++;
                    }
                    for (int i = 0; i < bucket.length; i++) count("qdist\t" + names[p] + "\t" + bucket[i], b[i]);
                    count("qdist\t" + names[p] + "\tepisodes_on_observed_days", sum);
                    count("qdist\t" + names[p] + "\tp50", perDeviceDay.get(n / 2));
                    count("qdist\t" + names[p] + "\tp90", perDeviceDay.get(Math.min(n - 1, n * 9 / 10)));
                    count("qdist\t" + names[p] + "\tp99", perDeviceDay.get(Math.min(n - 1, n * 99 / 100)));
                    count("qdist\t" + names[p] + "\tmax", perDeviceDay.get(n - 1));
                }
                for (int d = 0; d < testDays; d++) count("qday\t" + names[p] + "\td" + d, perDay[d]);
            }
            for (int d = 0; d < testDays; d++) {
                long observed = 0;
                for (DevOut o : devs) if (o.observedDay[d]) observed++;
                count("qday\tobserved_devices\td" + d, observed);
            }
            // finding kinds per device-hour, for the composition table
            String[] kind = {"crash", "restart", "burst_ha", "burst_link", "rate", "rate_excluded_class", "rate_strict",
                    "rate_loose", "new_rare_with_severity", "new_rare", "new_rare_excluded_class", "new_fleet_common",
                    "new_rare_signature_class"};
            for (DevOut o : devs) {
                for (int h = 0; h < testHours; h++) {
                    int m = o.mask[h];
                    if (m == 0) continue;
                    for (int b = 0; b < kind.length; b++) {
                        if ((m & (1 << b)) != 0) count("kind\t" + kind[b] + "\tdevice_hours", 1);
                    }
                }
            }
        }

        static boolean qualifies(int policy, int m) {
            boolean sig = (m & SIG_BITS) != 0;
            boolean rate = (m & B_RATE) != 0;
            boolean newRare = (m & (B_NEW_RARE_SEV | B_NEW_RARE)) != 0;
            int kinds = (sig ? 1 : 0) + (rate ? 1 : 0) + (newRare ? 1 : 0);
            boolean base = newRare || (rate && !sig) || kinds >= 2;
            return switch (policy) {
                case 0 -> (m & B_NEW_RARE_SEV) != 0;
                case 1 -> base;
                case 2 -> (m & (SIG_BITS | B_RATE | B_RATE_EXCL | B_NEW_RARE_SEV | B_NEW_RARE | B_NEW_RARE_EXCL | B_NEW_COMMON
                        | B_NEW_SIG)) != 0;
                case 3 -> sig && !base;
                case 4 -> (m & (B_RATE_EXCL | B_NEW_RARE_EXCL)) != 0
                        && (m & (SIG_BITS | B_RATE | B_NEW_RARE_SEV | B_NEW_RARE | B_NEW_COMMON | B_NEW_SIG)) == 0;
                case 5 -> newRare || ((m & B_RATE_STRICT) != 0 && !sig) || (sig ? 1 : 0) + ((m & B_RATE_STRICT) != 0 ? 1 : 0) + (newRare ? 1 : 0) >= 2;
                default -> newRare || ((m & B_RATE_LOOSE) != 0 && !sig) || (sig ? 1 : 0) + ((m & B_RATE_LOOSE) != 0 ? 1 : 0) + (newRare ? 1 : 0) >= 2;
            };
        }

        void report(PrintStream out) {
            String[] covLabel = {"lines", "unparsed_lines", "time_on_line", "time_inherited", "time_from_collection",
                    "lines_over_key_cap"};
            String[] sevLabel = {"sev_error", "sev_warn", "sev_none"};
            String[] whereLabel = {"before_window", "after_window", "learn", "test"};
            for (int s = 0; s < S; s++) {
                for (int i = 0; i < covLabel.length; i++) count("cov\t" + sources[s] + "\t" + covLabel[i], cov[s][i]);
                for (Cls c : Cls.values()) {
                    for (int v = 0; v < 3; v++) {
                        for (int w = 0; w < 4; w++) {
                            long n = clsCount[s][c.ordinal()][v][w];
                            if (n > 0) count("cls\t" + sources[s] + "\t" + c.name() + "\t" + sevLabel[v] + "\t" + whereLabel[w], n);
                        }
                    }
                }
                for (Map.Entry<String, long[]> e : procCount.get(s).entrySet()) {
                    count("proc\t" + sources[s] + "\t" + e.getKey().replace('-', '_'), e.getValue()[0]);
                }
                count("tpl\t" + sources[s] + "\ttyped_set_saturated", typed[s].size >= MAX_TYPED ? 1 : 0);
            }
            for (Map.Entry<String, long[]> e : panSubtype.entrySet()) count("pansub\t" + e.getKey(), e.getValue()[0]);
            count("self\tauth_lines", authLines);
            count("self\tauth_lines_with_ipv4", authLinesWithIp);
            for (Map.Entry<String, long[]> e : agg.entrySet()) {
                String[] f = e.getKey().split("\t");
                String[] all = Arrays.copyOf(f, f.length + 1);
                all[f.length] = Long.toString(e.getValue()[0]);
                emit(out, all);
            }
        }
    }

    // ------------------------------------------------------------------ main
    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        System.setOut(new PrintStream(OutputStream.nullOutputStream())); // library writes to stdout are dropped too
        silenceLogging();
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) a.put(args[i], args[i + 1]);
        windowDays = Integer.parseInt(a.getOrDefault("--window-days", "35"));
        testDays = Integer.parseInt(a.getOrDefault("--test-days", "7"));
        minLearnDays = Integer.parseInt(a.getOrDefault("--min-learn-days", "7"));
        maxDevices = Integer.parseInt(a.getOrDefault("--devices", "0"));
        maxSeconds = Long.parseLong(a.getOrDefault("--max-seconds", "2400"));
        if (a.containsKey("--sources")) sources = a.get("--sources").split(",");
        try {
            tech = loadWords(Path.of(a.get("--tech")));
            tech.addAll(FreeTextIdentityRules.STOPWORDS);
            tech.addAll(FreeTextIdentityRules.HOST_CONTEXT_WORDS);
            emit(out, "meta", "tech_vocab_words", String.valueOf(tech.size()));
            emit(out, "meta", "window_days", String.valueOf(windowDays));
            emit(out, "meta", "test_days", String.valueOf(testDays));
            emit(out, "meta", "min_learn_days", String.valueOf(minLearnDays));
            if (a.containsKey("--selftest")) selftest(out, Path.of(a.get("--selftest")));
            else run(out);
        } catch (Throwable t) {
            emit(out, "fatal", t.getClass().getSimpleName().toLowerCase(Locale.ROOT));
        }
        out.flush();
    }

    static void silenceLogging() {
        try {
            java.util.logging.LogManager.getLogManager().reset();
            java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.OFF);
        } catch (Throwable ignored) {
        }
        try {
            Object root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            Class<?> level = Class.forName("ch.qos.logback.classic.Level");
            root.getClass().getMethod("setLevel", level).invoke(root, level.getField("OFF").get(null));
        } catch (Throwable ignored) {
        }
    }

    /** Chunk rows: {device_id, source index, path, wrapped key, collected epoch seconds, bytes}. */
    static void run(PrintStream out) throws Exception {
        long t0 = System.nanoTime();
        Properties props = new Properties();
        props.setProperty("user", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_USER_FILE")), "db_user").strip());
        props.setProperty("password", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_PASSWORD_FILE")), "db_password").strip());
        props.setProperty("ApplicationName", "logfunnel-measure-readonly");
        List<Object[]> chunks = new ArrayList<>();
        long maxEpoch = 0;
        try (Connection conn = new org.postgresql.Driver().connect(System.getenv("UI2_DB_URL"), props)) {
            conn.setAutoCommit(false);
            conn.setReadOnly(true);
            try (Statement s = conn.createStatement()) {
                s.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
                s.execute("SET statement_timeout = '120s'");
                // volumes of every stored source, read or not (plain aggregates over the chunk ledger)
                try (ResultSet rs = s.executeQuery("select c.source_key, count(*), coalesce(sum(c.bytes),0), coalesce(sum(c.lines),0),"
                        + " count(distinct c.device_id) from system_log_chunk c where c.pruned_at is null group by c.source_key")) {
                    while (rs.next()) {
                        String source = rs.getString(1);
                        emit(out, "stored", source, "chunks", Long.toString(rs.getLong(2)));
                        emit(out, "stored", source, "bytes", Long.toString(rs.getLong(3)));
                        emit(out, "stored", source, "lines", Long.toString(rs.getLong(4)));
                        emit(out, "stored", source, "devices", Long.toString(rs.getLong(5)));
                    }
                }
                // the same join as JooqSystemLogReadRepository.findChunk: the stored path is recovery_volume_path
                try (ResultSet rs = s.executeQuery("select c.device_id, c.source_key, a.recovery_volume_path, c.wrapped_data_key,"
                        + " extract(epoch from c.collected_at)::bigint, c.bytes"
                        + " from system_log_chunk c join backup_artefact a on a.artefact_id = c.artefact_ref"
                        + " where c.pruned_at is null and a.recovery_volume_path is not null and c.wrapped_data_key is not null"
                        + " order by c.device_id, c.source_key, c.collected_at, c.start_offset, c.chunk_id")) {
                    while (rs.next()) {
                        int src = Arrays.asList(sources).indexOf(rs.getString(2));
                        if (src < 0) continue;
                        chunks.add(new Object[] {rs.getString(1), src, rs.getString(3), rs.getBytes(4), rs.getLong(5), rs.getLong(6)});
                        maxEpoch = Math.max(maxEpoch, rs.getLong(5));
                    }
                }
            }
            conn.rollback();
        }
        emit(out, "meta", "chunks_listed", String.valueOf(chunks.size()));
        ArtefactStore store = new FileArtefactStore(Path.of(System.getenv("UI2_ARTEFACT_STORE_ROOT")),
                ArtefactStoreCipher.fromBase64Key(SecretFile.readRequired(
                        Path.of(System.getenv("UI2_ARTEFACT_STORE_KEY_FILE")), "artefact_store_key")));
        Engine engine = new Engine(LocalDateTime.ofInstant(Instant.ofEpochSecond(maxEpoch), ZONE).toLocalDate());
        String current = null;
        int cpDevices = 0;
        int panDevices = 0;
        boolean skip = false;
        long chunksRead = 0;
        long bytesRead = 0;
        long errors = 0;
        boolean truncated = false;
        for (Object[] c : chunks) {
            String device = (String) c[0];
            int src = (Integer) c[1];
            boolean isPan = sources[src].startsWith("pan_");
            if (!device.equals(current)) {
                if ((System.nanoTime() - t0) / 1_000_000_000L > maxSeconds) {
                    truncated = true;
                    break;
                }
                current = device;
                int seen = isPan ? panDevices : cpDevices;
                skip = maxDevices > 0 && seen >= maxDevices;
                if (!skip) {
                    if (isPan) panDevices++;
                    else cpDevices++;
                    engine.startDevice(isPan);
                    emit(out, "progress", String.valueOf(cpDevices + panDevices), String.valueOf(chunksRead),
                            String.valueOf((System.nanoTime() - t0) / 1_000_000_000L));
                }
            }
            if (skip) continue;
            try (InputStream in = store.retrieve(new ArtefactRef((String) c[2]), (byte[]) c[3], true)) {
                engine.startChunk((Long) c[4]);
                readLines(engine, src, in);
                chunksRead++;
                bytesRead += (Long) c[5];
            } catch (Throwable t) {
                errors++;
            }
        }
        engine.finish();
        engine.report(out);
        emit(out, "meta", "chunks_read", String.valueOf(chunksRead));
        emit(out, "meta", "bytes_read", String.valueOf(bytesRead));
        emit(out, "meta", "chunk_errors", String.valueOf(errors));
        emit(out, "meta", "truncated_by_time", truncated ? "1" : "0");
        emit(out, "meta", "total_seconds", String.valueOf((System.nanoTime() - t0) / 1_000_000_000L));
    }

    static void readLines(Engine engine, int src, InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)), 1 << 16);
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty()) continue;
            engine.line(src, line);
        }
    }

    /** Local check on synthetic files named {@code <device>__<source>__<epochSeconds>.log}; the newest epoch ends the window. */
    static void selftest(PrintStream out, Path dir) throws Exception {
        List<Path> files = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".log")).forEach(files::add);
        }
        List<Object[]> rows = new ArrayList<>();
        long maxEpoch = 0;
        for (Path p : files) {
            String[] f = p.getFileName().toString().replace(".log", "").split("__");
            int src = Arrays.asList(sources).indexOf(f[1]);
            if (src < 0) continue;
            long epoch = Long.parseLong(f[2]);
            maxEpoch = Math.max(maxEpoch, epoch);
            rows.add(new Object[] {f[0], src, p, epoch});
        }
        rows.sort((x, y) -> {
            int c = ((String) x[0]).compareTo((String) y[0]);
            if (c != 0) return c;
            c = Integer.compare((Integer) x[1], (Integer) y[1]);
            return c != 0 ? c : Long.compare((Long) x[3], (Long) y[3]);
        });
        Engine engine = new Engine(LocalDateTime.ofInstant(Instant.ofEpochSecond(maxEpoch), ZONE).toLocalDate());
        String current = null;
        for (Object[] row : rows) {
            if (!row[0].equals(current)) {
                current = (String) row[0];
                engine.startDevice(sources[(Integer) row[1]].startsWith("pan_"));
            }
            engine.startChunk((Long) row[3]);
            try (InputStream in = Files.newInputStream((Path) row[2])) {
                readLines(engine, (Integer) row[1], in);
            }
        }
        engine.finish();
        engine.report(out);
    }

    static final Pattern SAFE = Pattern.compile("[a-z0-9_.]+");

    static void emit(PrintStream out, String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append('\t');
            String f = fields[i] == null ? "null" : fields[i];
            sb.append(SAFE.matcher(f).matches() ? f : "invalid_field");
        }
        out.println(sb);
    }

    static Set<String> loadWords(Path p) throws Exception {
        Set<String> out = new HashSet<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            int hashAt = line.indexOf('#');
            String l = (hashAt >= 0 ? line.substring(0, hashAt) : line).strip().toLowerCase(Locale.ROOT);
            if (l.isEmpty()) continue;
            out.addAll(Arrays.asList(l.split("\\s+")));
        }
        return out;
    }

    private LogFunnelMeasure() {
    }
}
