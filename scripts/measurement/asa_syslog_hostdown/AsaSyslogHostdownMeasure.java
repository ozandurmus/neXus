package nexusmeasure;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.SecretFile;

/**
 * Counts-only in-pod measurement for FC-6 (docs/design/PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md
 * section 1.1 Q2 and section 3.2 FC-6): checks stored Cisco ASA running-config snapshots locally for
 * {@code logging permit-hostdown} presence and {@code logging host} transport counts ({@code udp}/{@code tcp}/
 * {@code secure}/{@code other}).
 *
 * <p>Reads only stored artefacts over a read-only PostgreSQL transaction (no device contact, no new device command),
 * decrypts each artefact in memory with the pod's artefact-store key (bounded to 64 MiB decompressed text), inspects it
 * line-by-line, and immediately discards it. Every output field matches {@code [a-z0-9_.]+}: devices are anonymous
 * ordinals ({@code asa_01}..), values are closed booleans/tokens or integer counts. Never prints hostnames, IPs,
 * interface names, serials, raw config lines, or exception messages.
 *
 * <p>{@code --selftest} runs {@link #analyzeRunningConfig(BufferedReader)} and fleet aggregation checks over inline
 * synthetic ASA configs with no database and no artefact store.
 */
public final class AsaSyslogHostdownMeasure {
    static final Pattern SAFE = Pattern.compile("[a-z0-9_.]+");
    static final long SIZE_CAP_BYTES = 64L * 1024 * 1024;
    static final List<String> DEVICE_COUNTERS = List.of(
            "has_stored_config",
            "permit_hostdown_present",
            "permit_hostdown_negated",
            "logging_host_total",
            "logging_host_udp",
            "logging_host_tcp",
            "logging_host_secure",
            "logging_host_other",
            "read_failed",
            "skipped_size");

    static PrintWriter out;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--selftest")) {
            String failed = selfTest();
            System.out.println(failed == null ? "selftest\tok" : "selftest\tfail\t" + failed);
            System.exit(failed == null ? 0 : 1);
            return;
        }
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        Path dir = Path.of(args.length > 0 ? args[0] : ".");
        out = new PrintWriter(Files.newBufferedWriter(dir.resolve("measure.out")), true);
        try {
            run();
            out.println("done");
        } catch (Throwable t) {
            String name = t.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            out.println("fatal\t" + (SAFE.matcher(name).matches() ? name : "other"));
        }
        out.close();
    }

    record DeviceRow(String deviceId, String ref, byte[] wrapped, boolean gzip) {
    }

    /** Result of scanning one ASA running-config text line-by-line (zero raw strings retained). */
    public record ConfigAnalysis(
            boolean permitHostdownPresent,
            boolean permitHostdownNegated,
            int loggingHostTotal,
            int loggingHostUdp,
            int loggingHostTcp,
            int loggingHostSecure,
            int loggingHostOther) {
    }

    /** Per-unit measurement outcome before fleet FC-6 aggregation. */
    record UnitOutcome(
            boolean hasStoredConfig,
            boolean readFailed,
            boolean skippedSize,
            ConfigAnalysis analysis) {
        Map<String, Long> toCounters() {
            Map<String, Long> c = new TreeMap<>();
            c.put("has_stored_config", hasStoredConfig ? 1L : 0L);
            c.put("read_failed", readFailed ? 1L : 0L);
            c.put("skipped_size", skippedSize ? 1L : 0L);
            if (analysis != null) {
                c.put("permit_hostdown_present", analysis.permitHostdownPresent() ? 1L : 0L);
                c.put("permit_hostdown_negated", analysis.permitHostdownNegated() ? 1L : 0L);
                c.put("logging_host_total", (long) analysis.loggingHostTotal());
                c.put("logging_host_udp", (long) analysis.loggingHostUdp());
                c.put("logging_host_tcp", (long) analysis.loggingHostTcp());
                c.put("logging_host_secure", (long) analysis.loggingHostSecure());
                c.put("logging_host_other", (long) analysis.loggingHostOther());
            } else {
                c.put("permit_hostdown_present", 0L);
                c.put("permit_hostdown_negated", 0L);
                c.put("logging_host_total", 0L);
                c.put("logging_host_udp", 0L);
                c.put("logging_host_tcp", 0L);
                c.put("logging_host_secure", 0L);
                c.put("logging_host_other", 0L);
            }
            return c;
        }
    }

    /** Fleet FC-6 summary metrics. */
    record FleetSummary(
            int asaTotalCount,
            int asaWithStoredConfig,
            int asaWithoutStoredConfig,
            int asaReadFailed,
            int asaSkippedSize,
            int asaPermitHostdownPresentCount,
            int asaPermitHostdownAbsentCount,
            int asaLoggingHostUdpUnits,
            int asaLoggingHostTcpUnits,
            int asaLoggingHostSecureUnits,
            boolean asaDirectTcpTlsSafe,
            String asaMtlsSupported) {
    }

    static void run() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_USER_FILE")), "db_user").strip());
        props.setProperty("password", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_PASSWORD_FILE")), "db_password").strip());
        List<DeviceRow> rows = new ArrayList<>();
        try (Connection conn = new org.postgresql.Driver().connect(System.getenv("UI2_DB_URL"), props)) {
            conn.setReadOnly(true);
            // Checks v_latest_effective_running_config (effective_running) and falls back to the newest non-removed
            // show_configuration run in device_configuration_run (asa_configuration_collect writes show_configuration).
            String sql = "select d.device_id, "
                    + "case when v.artefact_ref is not null and (s.collected_at is null or v.collected_at >= s.collected_at) "
                    + "then v.artefact_ref else s.artefact_ref end as artefact_ref, "
                    + "case when v.artefact_ref is not null and (s.collected_at is null or v.collected_at >= s.collected_at) "
                    + "then v.wrapped_data_key else s.wrapped_data_key end as wrapped_data_key, "
                    + "case when v.artefact_ref is not null and (s.collected_at is null or v.collected_at >= s.collected_at) "
                    + "then v.compression else s.compression end as compression "
                    + "from devices d "
                    + "left join v_latest_effective_running_config v on v.device_id = d.device_id "
                    + "left join lateral ("
                    + "  select r.artefact_ref, a.wrapped_data_key, c.compression, r.collected_at "
                    + "  from device_configuration_run r "
                    + "  join configuration_artefact c on c.artefact_ref = r.artefact_ref "
                    + "  join backup_artefact a on a.recovery_volume_path = r.artefact_ref and a.device_id = d.device_id "
                    + "  where r.device_id = d.device_id and r.read_kind = 'show_configuration' "
                    + "    and not exists (select 1 from artefact_retention_ledger l "
                    + "                    where l.artefact_id = a.artefact_id and l.event = 'removed') "
                    + "  order by r.collected_at desc limit 1"
                    + ") s on true "
                    + "where d.vendor_hint = 'cisco_asa' and not d.disabled "
                    + "and d.enrollment_state in ('ENROLLED', 'DEGRADED') "
                    + "order by d.device_id";
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new DeviceRow(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getBytes(3),
                            "gzip".equals(rs.getString(4))));
                }
            }
        }
        rows.sort(Comparator.comparing(DeviceRow::deviceId));
        ArtefactStore store = new FileArtefactStore(
                Path.of(System.getenv("UI2_ARTEFACT_STORE_ROOT")),
                ArtefactStoreCipher.fromBase64Key(SecretFile.readRequired(
                        Path.of(System.getenv("UI2_ARTEFACT_STORE_KEY_FILE")), "artefact_store_key")));

        List<UnitOutcome> outcomes = new ArrayList<>(rows.size());
        int n = 0;
        for (DeviceRow row : rows) {
            String dev = String.format(Locale.ROOT, "asa_%02d", ++n);
            UnitOutcome outcome = measureUnit(row, store, SIZE_CAP_BYTES);
            outcomes.add(outcome);
            Map<String, Long> counters = outcome.toCounters();
            for (String key : DEVICE_COUNTERS) {
                emit("device", dev, key, counters.getOrDefault(key, 0L));
            }
        }
        FleetSummary summary = summarize(outcomes);
        emitSummary(summary);
    }

    static UnitOutcome measureUnit(DeviceRow row, ArtefactStore store, long capBytes) {
        if (row.ref() == null) {
            return new UnitOutcome(false, false, false, null);
        }
        try (InputStream raw = store.retrieve(new ArtefactRef(row.ref()), row.wrapped(), row.gzip())) {
            return measureStream(raw, capBytes);
        } catch (Exception e) {
            return new UnitOutcome(true, true, false, null);
        }
    }

    static UnitOutcome measureStream(InputStream raw, long capBytes) {
        Capped capped = new Capped(raw, capBytes);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(capped, StandardCharsets.UTF_8))) {
            ConfigAnalysis analysis = analyzeRunningConfig(reader);
            return new UnitOutcome(true, false, false, analysis);
        } catch (Exception e) {
            if (capped.exceeded) {
                return new UnitOutcome(true, false, true, null);
            }
            return new UnitOutcome(true, true, false, null);
        }
    }

    /**
     * Pure, database-free ASA running-config line scanner.
     *
     * <p>Case-insensitive, trimmed, ignoring {@code !} comment lines:
     * <ul>
     *   <li>Tracks {@code logging permit-hostdown} vs {@code no logging permit-hostdown} (last directive wins).</li>
     *   <li>Tracks {@code logging host <iface> <ip> ...} lines into {@code secure}, {@code tcp}, {@code udp}, or
     *       {@code other} transport counts.</li>
     * </ul>
     * Never stores or retains any interface name, IP address, hostname, serial, or raw line.
     */
    public static ConfigAnalysis analyzeRunningConfig(BufferedReader reader) throws IOException {
        boolean permitPresent = false;
        boolean permitNegated = false;
        int hostTotal = 0;
        int hostUdp = 0;
        int hostTcp = 0;
        int hostSecure = 0;
        int hostOther = 0;

        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("!")) {
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            String[] tokens = lower.split("\\s+");
            if (tokens.length == 2 && tokens[0].equals("logging") && tokens[1].equals("permit-hostdown")) {
                permitPresent = true;
                permitNegated = false;
            } else if (tokens.length == 3 && tokens[0].equals("no") && tokens[1].equals("logging")
                    && tokens[2].equals("permit-hostdown")) {
                permitPresent = false;
                permitNegated = true;
            } else if (tokens.length >= 2 && tokens[0].equals("logging") && tokens[1].equals("host")) {
                hostTotal++;
                switch (classifyLoggingHost(tokens)) {
                    case SECURE -> hostSecure++;
                    case TCP -> hostTcp++;
                    case UDP -> hostUdp++;
                    case OTHER -> hostOther++;
                }
            }
        }
        return new ConfigAnalysis(permitPresent, permitNegated, hostTotal, hostUdp, hostTcp, hostSecure, hostOther);
    }

    enum Transport {
        SECURE, TCP, UDP, OTHER
    }

    static Transport classifyLoggingHost(String[] tokens) {
        if (tokens.length < 4) {
            return Transport.OTHER;
        }
        if (tokens.length == 4) {
            return Transport.UDP;
        }
        boolean hasTcp = false;
        boolean hasUdp = false;
        boolean hasSecure = false;
        boolean hasUnknown = false;
        for (int i = 4; i < tokens.length; i++) {
            String tok = tokens[i];
            if ((tok.startsWith("tcp/") && tok.length() > 4) || (tok.startsWith("6/") && tok.length() > 2)) {
                hasTcp = true;
            } else if ((tok.startsWith("udp/") && tok.length() > 4) || (tok.startsWith("17/") && tok.length() > 3)) {
                hasUdp = true;
            } else if (tok.equals("secure")) {
                hasSecure = true;
            } else if (tok.equals("format") && i + 1 < tokens.length && tokens[i + 1].equals("emblem")) {
                i++;
            } else {
                hasUnknown = true;
            }
        }
        if (hasUnknown || (hasTcp && hasUdp)) {
            return Transport.OTHER;
        }
        if (hasTcp) {
            return hasSecure ? Transport.SECURE : Transport.TCP;
        }
        if (hasUdp) {
            return hasSecure ? Transport.OTHER : Transport.UDP;
        }
        return hasSecure ? Transport.OTHER : Transport.UDP;
    }

    static FleetSummary summarize(List<UnitOutcome> outcomes) {
        int total = outcomes.size();
        int withConfig = 0;
        int withoutConfig = 0;
        int readFailed = 0;
        int skippedSize = 0;
        int presentCount = 0;
        int absentCount = 0;
        int udpUnits = 0;
        int tcpUnits = 0;
        int secureUnits = 0;

        for (UnitOutcome u : outcomes) {
            if (!u.hasStoredConfig()) {
                withoutConfig++;
                continue;
            }
            withConfig++;
            if (u.readFailed()) {
                readFailed++;
                continue;
            }
            if (u.skippedSize() || u.analysis() == null) {
                skippedSize++;
                continue;
            }
            ConfigAnalysis a = u.analysis();
            if (a.permitHostdownPresent()) {
                presentCount++;
            } else {
                absentCount++;
            }
            if (a.loggingHostUdp() > 0) {
                udpUnits++;
            }
            if (a.loggingHostTcp() > 0) {
                tcpUnits++;
            }
            if (a.loggingHostSecure() > 0) {
                secureUnits++;
            }
        }
        boolean directSafe = total > 0
                && presentCount == total
                && withoutConfig == 0
                && readFailed == 0
                && skippedSize == 0;
        return new FleetSummary(
                total,
                withConfig,
                withoutConfig,
                readFailed,
                skippedSize,
                presentCount,
                absentCount,
                udpUnits,
                tcpUnits,
                secureUnits,
                directSafe,
                "unknown");
    }

    static void emitSummary(FleetSummary s) {
        emit("summary", "fc6", "asa_total_count", s.asaTotalCount());
        emit("summary", "fc6", "asa_with_stored_config", s.asaWithStoredConfig());
        emit("summary", "fc6", "asa_without_stored_config", s.asaWithoutStoredConfig());
        emit("summary", "fc6", "asa_read_failed", s.asaReadFailed());
        emit("summary", "fc6", "asa_skipped_size", s.asaSkippedSize());
        emit("summary", "fc6", "asa_permit_hostdown_present_count", s.asaPermitHostdownPresentCount());
        emit("summary", "fc6", "asa_permit_hostdown_absent_count", s.asaPermitHostdownAbsentCount());
        emit("summary", "fc6", "asa_logging_host_udp_units", s.asaLoggingHostUdpUnits());
        emit("summary", "fc6", "asa_logging_host_tcp_units", s.asaLoggingHostTcpUnits());
        emit("summary", "fc6", "asa_logging_host_secure_units", s.asaLoggingHostSecureUnits());
        emitText("summary", "fc6", "asa_direct_tcp_tls_safe", Boolean.toString(s.asaDirectTcpTlsSafe()));
        emitText("summary", "fc6", "asa_mtls_supported", s.asaMtlsSupported());
    }

    /** Counts decrypted/decompressed bytes and stops past {@code capBytes}. */
    static final class Capped extends InputStream {
        private final InputStream in;
        private final long cap;
        private long total;
        boolean exceeded;

        Capped(InputStream in, long cap) {
            this.in = in;
            this.cap = cap;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                total += n;
                if (total > cap) {
                    exceeded = true;
                    throw new IOException("cap");
                }
            }
            return n;
        }
    }

    static void emit(String kind, String id, String key, long value) {
        emitText(kind, id, key, Long.toString(value));
    }

    static void emitText(String kind, String id, String key, String value) {
        String line = kind + "\t" + id + "\t" + key + "\t" + value;
        for (String f : line.split("\t", -1)) {
            if (!SAFE.matcher(f).matches()) {
                out.println("unsafe_field_dropped");
                return;
            }
        }
        out.println(line);
    }

    // ---- self-test: synthetic ASA configs only (RFC 5737 IPs), no database, no store ----

    static ConfigAnalysis parseText(String text) throws IOException {
        try (BufferedReader br = new BufferedReader(new StringReader(text))) {
            return analyzeRunningConfig(br);
        }
    }

    static InputStream utf8(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    static String selfTest() throws IOException {
        // (a) logging permit-hostdown present + logging host inside 192.0.2.10 tcp/6514 secure
        String cfgA = """
                ! ASA synthetic config A
                ASA Version 9.18(4)
                logging enable
                logging permit-hostdown
                logging host inside 192.0.2.10 tcp/6514 secure
                """;
        ConfigAnalysis a = parseText(cfgA);
        if (!a.permitHostdownPresent() || a.permitHostdownNegated()) return "case_a_permit";
        if (a.loggingHostTotal() != 1 || a.loggingHostSecure() != 1 || a.loggingHostTcp() != 0
                || a.loggingHostUdp() != 0 || a.loggingHostOther() != 0) {
            return "case_a_host";
        }

        // (b) absent permit-hostdown + UDP default logging host outside 198.51.100.10
        String cfgB = """
                ! logging permit-hostdown (commented out, must be ignored)
                logging enable
                logging host outside 198.51.100.10
                """;
        ConfigAnalysis b = parseText(cfgB);
        if (b.permitHostdownPresent() || b.permitHostdownNegated()) return "case_b_permit";
        if (b.loggingHostTotal() != 1 || b.loggingHostUdp() != 1 || b.loggingHostTcp() != 0
                || b.loggingHostSecure() != 0 || b.loggingHostOther() != 0) {
            return "case_b_host";
        }

        // (c) logging permit-hostdown overridden later by no logging permit-hostdown + plain TCP 6/1470
        String cfgC = """
                  LOGGING   PERMIT-HOSTDOWN
                logging host mgmt 203.0.113.5 6/1470
                  No   Logging   Permit-Hostdown
                """;
        ConfigAnalysis c = parseText(cfgC);
        if (c.permitHostdownPresent() || !c.permitHostdownNegated()) return "case_c_permit";
        if (c.loggingHostTotal() != 1 || c.loggingHostTcp() != 1 || c.loggingHostSecure() != 0
                || c.loggingHostUdp() != 0 || c.loggingHostOther() != 0) {
            return "case_c_host";
        }

        // Extra transport coverage: negated then re-enabled, 17/514, tcp/1470, 6/6514 secure, and unknown transport
        String cfgExtra = """
                no logging permit-hostdown
                logging permit-hostdown
                logging host dmz 192.0.2.20 17/514
                logging host dmz 192.0.2.21 udp/5514
                logging host dmz 192.0.2.22 format emblem
                logging host inside 198.51.100.20 6/6514 format emblem secure
                logging host inside 203.0.113.20 tcp/1514
                logging host outside 203.0.113.21 sctp/514
                """;
        ConfigAnalysis extra = parseText(cfgExtra);
        if (!extra.permitHostdownPresent() || extra.permitHostdownNegated()) return "extra_permit";
        if (extra.loggingHostTotal() != 6 || extra.loggingHostUdp() != 3 || extra.loggingHostSecure() != 1
                || extra.loggingHostTcp() != 1 || extra.loggingHostOther() != 1) {
            return "extra_hosts";
        }

        // (d) missing config / fail-closed asa_direct_tcp_tls_safe=false and asa_mtls_supported=unknown
        UnitOutcome outA = measureStream(utf8(cfgA), SIZE_CAP_BYTES);
        UnitOutcome outB = measureStream(utf8(cfgB), SIZE_CAP_BYTES);
        UnitOutcome outC = measureStream(utf8(cfgC), SIZE_CAP_BYTES);
        UnitOutcome missing = new UnitOutcome(false, false, false, null);
        FleetSummary mixed = summarize(List.of(outA, outB, outC, missing));
        if (mixed.asaTotalCount() != 4 || mixed.asaWithStoredConfig() != 3 || mixed.asaWithoutStoredConfig() != 1) {
            return "mixed_counts";
        }
        if (mixed.asaReadFailed() != 0 || mixed.asaPermitHostdownPresentCount() != 1
                || mixed.asaPermitHostdownAbsentCount() != 2) {
            return "mixed_permit_counts";
        }
        if (mixed.asaLoggingHostUdpUnits() != 1 || mixed.asaLoggingHostTcpUnits() != 1
                || mixed.asaLoggingHostSecureUnits() != 1) {
            return "mixed_unit_counts";
        }
        if (mixed.asaDirectTcpTlsSafe() || !"unknown".equals(mixed.asaMtlsSupported())) {
            return "mixed_fail_closed";
        }

        // Missing config alone must fail closed even when all other units have permit-hostdown
        FleetSummary presentPlusMissing = summarize(List.of(outA, missing));
        if (presentPlusMissing.asaDirectTcpTlsSafe() || !"unknown".equals(presentPlusMissing.asaMtlsSupported())) {
            return "missing_config_fail_closed";
        }

        // All units present with permit-hostdown -> asa_direct_tcp_tls_safe=true, asa_mtls_supported=unknown
        UnitOutcome outExtra = measureStream(utf8(cfgExtra), SIZE_CAP_BYTES);
        FleetSummary allSafe = summarize(List.of(outA, outExtra));
        if (!allSafe.asaDirectTcpTlsSafe() || !"unknown".equals(allSafe.asaMtlsSupported())) {
            return "all_safe";
        }

        // Empty fleet must fail closed
        FleetSummary emptyFleet = summarize(List.of());
        if (emptyFleet.asaDirectTcpTlsSafe() || !"unknown".equals(emptyFleet.asaMtlsSupported())) {
            return "empty_fail_closed";
        }

        // Size cap and stream failure must fail closed
        UnitOutcome cappedOutcome = measureStream(utf8(cfgA), 16);
        if (!cappedOutcome.skippedSize() || cappedOutcome.readFailed() || cappedOutcome.analysis() != null) {
            return "size_cap";
        }
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("synthetic");
            }
        };
        UnitOutcome failedOutcome = measureStream(broken, SIZE_CAP_BYTES);
        if (!failedOutcome.readFailed() || failedOutcome.skippedSize() || failedOutcome.analysis() != null) {
            return "read_failed";
        }
        if (summarize(List.of(outA, cappedOutcome)).asaDirectTcpTlsSafe()) return "cap_fail_closed";
        if (summarize(List.of(outA, failedOutcome)).asaDirectTcpTlsSafe()) return "read_fail_closed";

        for (String k : DEVICE_COUNTERS) {
            if (!SAFE.matcher(k).matches()) return "counter_vocabulary";
        }
        if (SAFE.matcher("FW-TANGO-04").matches() || SAFE.matcher("tcp/6514").matches()
                || SAFE.matcher("inside host").matches() || !SAFE.matcher("asa_01").matches()) {
            return "safe_filter";
        }
        return null;
    }
}
