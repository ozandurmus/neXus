package nexusmeasure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.jobs.pan.PanOsVersion;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.SecretFile;
import com.securityexpert.nexus.ui2.worker.inventory.pan.PaloAltoConfiguredInterfaceParser;

/**
 * Counts-only measurement (backlog policy_collection_gaps): for every enrolled, not disabled Palo Alto gateway whose PAN-OS
 * major is not 12, is there a stored effective-running document, how old is it, does the existing parser read it, and how
 * many virtual systems and zones does it carry. Reads only the stored document (no device command), decrypts it in memory
 * with the pod's own artefact-store key, discards it, and prints counters only. Every output field matches [a-z0-9_.]+:
 * devices are ordinals, versions are bucket tokens, everything else is a fixed counter name or an integer. No name,
 * address, serial, vsys name, zone name or exception text ever reaches the output file.
 *
 * <p>{@code --selftest} runs the pure counting logic over inline synthetic documents and needs no database and no store.
 */
public final class PanPre12ZoneSetMeasure {
    static final Pattern SAFE = Pattern.compile("[a-z0-9_.]+");
    static final long SIZE_CAP_BYTES = 64L * 1024 * 1024;
    static final List<String> BUCKETS = List.of("v9", "v10", "v11", "vother", "vunknown");
    static final String ALL = "all";
    static final String MAX_KEY = "max_zones_in_vsys";
    /** The closed counter vocabulary, in output order. */
    static final List<String> COUNTERS = List.of("firewalls_enrolled", "with_stored_document", "without_stored_document",
            "age_le_24h", "age_le_7d", "age_older", "age_unknown", "parsed_ok", "not_parseable", "skipped_size",
            "artefact_unreadable", "with_vsys", "vsys_total", "vsys_with_zone", "vsys_zero_zone", "zone_total",
            "zone_set_candidate_le_24h", MAX_KEY);

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

    record Row(String version, String ref, byte[] wrapped, boolean gzip, Instant collectedAt) {
    }

    static void run() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_USER_FILE")), "db_user").strip());
        props.setProperty("password", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_PASSWORD_FILE")), "db_password").strip());
        List<Row> rows = new ArrayList<>();
        long pan12 = 0;
        try (Connection conn = new org.postgresql.Driver().connect(System.getenv("UI2_DB_URL"), props)) {
            conn.setReadOnly(true);
            // The configuration plane's own join (LatestEffectiveRunningReader): the view holds the newest stored
            // effective-running run per device, wrapped key and compression included, minus removed artefacts.
            String sql = "select d.device_id, d.observed_software_version, v.artefact_ref, v.wrapped_data_key, v.compression, "
                    + "v.collected_at from devices d "
                    + "left join v_latest_effective_running_config v on v.device_id = d.device_id "
                    + "where d.vendor_hint = 'palo_alto' and d.role = 'gateway' and not d.disabled "
                    + "and d.enrollment_state in ('ENROLLED', 'DEGRADED') order by d.device_id";
            List<Object[]> raw = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp(6);
                    raw.add(new Object[] {rs.getString(1), new Row(rs.getString(2), rs.getString(3), rs.getBytes(4),
                            "gzip".equals(rs.getString(5)), ts == null ? null : ts.toInstant())});
                }
            }
            raw.sort((x, y) -> ((String) x[0]).compareTo((String) y[0]));
            for (Object[] r : raw) {
                Row row = (Row) r[1];
                OptionalInt major = PanOsVersion.major(row.version());
                if (major.isPresent() && major.getAsInt() == 12) {
                    pan12++;
                    continue;
                }
                rows.add(row);
            }
        }
        ArtefactStore store = new FileArtefactStore(Path.of(System.getenv("UI2_ARTEFACT_STORE_ROOT")),
                ArtefactStoreCipher.fromBase64Key(SecretFile.readRequired(
                        Path.of(System.getenv("UI2_ARTEFACT_STORE_KEY_FILE")), "artefact_store_key")));
        Instant now = Instant.now();
        Map<String, Map<String, Long>> byBucket = new TreeMap<>();
        emit("scope", "fleet", "total_pan_gateways", rows.size() + pan12);
        emit("scope", "fleet", "pan_os_12_gateways", pan12);
        emit("scope", "fleet", "measured_not_12_gateways", rows.size());
        int n = 0;
        for (Row row : rows) {
            String dev = String.format("pan_%02d", ++n);
            String bucket = versionBucket(row.version());
            Map<String, Long> doc = null;
            boolean unreadable = false;
            if (row.ref() != null) {
                try (InputStream in = store.retrieve(new ArtefactRef(row.ref()), row.wrapped(), row.gzip())) {
                    doc = measureDocument(in, SIZE_CAP_BYTES);
                } catch (Exception e) {
                    unreadable = true;
                }
            }
            Map<String, Long> c = deviceCounters(row.ref() != null, row.collectedAt(), now, doc, unreadable);
            emitText("device", dev, "version_bucket", bucket);
            for (String key : COUNTERS) {
                emit("device", dev, key, c.getOrDefault(key, 0L));
            }
            merge(byBucket.computeIfAbsent(bucket, k -> new TreeMap<>()), c);
            merge(byBucket.computeIfAbsent(ALL, k -> new TreeMap<>()), c);
        }
        List<String> scopes = new ArrayList<>(BUCKETS);
        scopes.add(ALL);
        for (String scope : scopes) {
            Map<String, Long> c = byBucket.getOrDefault(scope, Map.of());
            for (String key : COUNTERS) {
                emit("bucket", scope, key, c.getOrDefault(key, 0L));
            }
        }
    }

    /** Bucket token of an observed software version: only the major leaves this method, and only as a closed token. */
    static String versionBucket(String version) {
        OptionalInt major = PanOsVersion.major(version);
        if (major.isEmpty()) {
            return "vunknown";
        }
        return switch (major.getAsInt()) {
            case 9 -> "v9";
            case 10 -> "v10";
            case 11 -> "v11";
            default -> "vother";
        };
    }

    /** Age bucket of the stored document at measurement time; a missing or future timestamp is not a proven age. */
    static String ageBucket(Instant collectedAt, Instant now) {
        if (collectedAt == null || collectedAt.isAfter(now)) {
            return "age_unknown";
        }
        Duration age = Duration.between(collectedAt, now);
        if (age.compareTo(Duration.ofHours(24)) <= 0) {
            return "age_le_24h";
        }
        return age.compareTo(Duration.ofDays(7)) <= 0 ? "age_le_7d" : "age_older";
    }

    /** One firewall's counters (0/1 flags and counts) from the stored-document facts and the document measurement. */
    static Map<String, Long> deviceCounters(boolean hasDocument, Instant collectedAt, Instant now, Map<String, Long> doc,
            boolean unreadable) {
        Map<String, Long> c = new TreeMap<>();
        c.put("firewalls_enrolled", 1L);
        if (!hasDocument) {
            c.put("without_stored_document", 1L);
            return c;
        }
        c.put("with_stored_document", 1L);
        c.put(ageBucket(collectedAt, now), 1L);
        if (unreadable || doc == null) {
            c.put("artefact_unreadable", 1L);
            return c;
        }
        c.putAll(doc);
        boolean candidate = c.getOrDefault("age_le_24h", 0L) == 1 && c.getOrDefault("parsed_ok", 0L) == 1
                && c.getOrDefault("with_vsys", 0L) == 1 && c.getOrDefault("vsys_zero_zone", 0L) == 0;
        c.put("zone_set_candidate_le_24h", candidate ? 1L : 0L);
        return c;
    }

    static void merge(Map<String, Long> into, Map<String, Long> c) {
        for (Map.Entry<String, Long> e : c.entrySet()) {
            if (e.getKey().equals(MAX_KEY)) {
                into.merge(e.getKey(), e.getValue(), Math::max);
            } else {
                into.merge(e.getKey(), e.getValue(), Long::sum);
            }
        }
    }

    /**
     * Pure and database-free: the document-level counters of one stored effective-running document, through the
     * existing PaloAltoConfiguredInterfaceParser (no second zone parser). The read is capped at {@code capBytes} of
     * decrypted, decompressed text. Exactly one of parsed_ok, not_parseable, skipped_size, artefact_unreadable is 1.
     * The parser leaves out a virtual system it cannot read in full, so that system is not counted at all.
     */
    static Map<String, Long> measureDocument(InputStream raw, long capBytes) {
        Capped in = new Capped(raw, capBytes);
        Optional<PaloAltoConfiguredInterfaceParser.Configured> cfg;
        try {
            cfg = PaloAltoConfiguredInterfaceParser.parse(in);
        } catch (RuntimeException e) {
            cfg = Optional.empty();
        }
        Map<String, Long> c = new TreeMap<>();
        if (in.exceeded) {
            c.put("skipped_size", 1L);
        } else if (cfg.isEmpty()) {
            c.put(in.failed ? "artefact_unreadable" : "not_parseable", 1L);
        } else {
            c.put("parsed_ok", 1L);
            Map<String, List<String>> zones = cfg.get().zoneNamesByVsys();
            long withZone = 0, zeroZone = 0, total = 0, max = 0;
            for (List<String> names : zones.values()) {
                if (names.isEmpty()) {
                    zeroZone++;
                } else {
                    withZone++;
                }
                total += names.size();
                max = Math.max(max, names.size());
            }
            c.put("with_vsys", zones.isEmpty() ? 0L : 1L);
            c.put("vsys_total", (long) zones.size());
            c.put("vsys_with_zone", withZone);
            c.put("vsys_zero_zone", zeroZone);
            c.put("zone_total", total);
            c.put(MAX_KEY, max);
        }
        return c;
    }

    /** Counts decrypted bytes, fails the read past the cap, and records whether the cause was the cap or the source. */
    static final class Capped extends InputStream {
        private final InputStream in;
        private final long cap;
        private long total;
        boolean exceeded;
        boolean failed;

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
            int n;
            try {
                n = in.read(b, off, len);
            } catch (IOException e) {
                failed = true;
                throw e;
            }
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

    // ---- self-test: synthetic documents only, no database, no store ----

    static final String TWO_VSYS = "<response status=\"success\"><result><config><devices><entry name=\"localhost.localdomain\">"
            + "<vsys><entry name=\"vsys1\"><zone><entry name=\"ZONE-TRUST\"/><entry name=\"ZONE-TPL\" src=\"tpl\"/></zone></entry>"
            + "<entry name=\"vsys2\"><zone/></entry></vsys></entry></devices></config></result></response>";
    static final String NO_VSYS = "<config><devices><entry name=\"localhost.localdomain\"><deviceconfig/></entry></devices></config>";

    static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Null when every check holds, else the name of the first failing check (a literal from this file). */
    static String selfTest() {
        Map<String, Long> a = measureDocument(bytes(TWO_VSYS), SIZE_CAP_BYTES);
        if (!Long.valueOf(1).equals(a.get("parsed_ok")) || a.size() != 7) return "two_vsys_shape";
        if (a.get("vsys_total") != 2 || a.get("vsys_with_zone") != 1 || a.get("vsys_zero_zone") != 1) return "two_vsys_vsys";
        if (a.get("zone_total") != 2 || a.get(MAX_KEY) != 2 || a.get("with_vsys") != 1) return "two_vsys_zones";
        Map<String, Long> b = measureDocument(bytes("this is not xml <<<"), SIZE_CAP_BYTES);
        if (!Map.of("not_parseable", 1L).equals(b)) return "not_xml";
        Map<String, Long> d = measureDocument(bytes(NO_VSYS), SIZE_CAP_BYTES);
        if (!Long.valueOf(1).equals(d.get("parsed_ok")) || d.get("with_vsys") != 0 || d.get("vsys_total") != 0) return "no_vsys";
        if (!Map.of("skipped_size", 1L).equals(measureDocument(bytes(TWO_VSYS), 10))) return "size_cap";
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("synthetic");
            }
        };
        if (!Map.of("artefact_unreadable", 1L).equals(measureDocument(broken, SIZE_CAP_BYTES))) return "unreadable";
        Instant now = Instant.parse("2026-11-23T12:00:00Z");
        if (!ageBucket(now.minus(Duration.ofHours(24)), now).equals("age_le_24h")) return "age_24h";
        if (!ageBucket(now.minus(Duration.ofHours(25)), now).equals("age_le_7d")) return "age_7d";
        if (!ageBucket(now.minus(Duration.ofDays(8)), now).equals("age_older")) return "age_older";
        if (!ageBucket(null, now).equals("age_unknown") || !ageBucket(now.plusSeconds(1), now).equals("age_unknown")) {
            return "age_unknown";
        }
        if (!versionBucket("11.1.4-h7").equals("v11") || !versionBucket("10.2.9").equals("v10")
                || !versionBucket("9.1.16").equals("v9") || !versionBucket("8.1.0").equals("vother")
                || !versionBucket("13.0.1").equals("vother") || !versionBucket(null).equals("vunknown")
                || !versionBucket("fw-tango-04").equals("vunknown")) {
            return "version_bucket";
        }
        Map<String, Long> fresh = deviceCounters(true, now.minusSeconds(60), now, a, false);
        if (fresh.get("zone_set_candidate_le_24h") != 0) return "candidate_zero_zone_vsys";
        Map<String, Long> single = measureDocument(bytes(TWO_VSYS.replace("<entry name=\"vsys2\"><zone/></entry>", "")),
                SIZE_CAP_BYTES);
        if (deviceCounters(true, now.minusSeconds(60), now, single, false).get("zone_set_candidate_le_24h") != 1) {
            return "candidate_fresh";
        }
        if (deviceCounters(true, now.minus(Duration.ofDays(2)), now, single, false).get("zone_set_candidate_le_24h") != 0) {
            return "candidate_stale";
        }
        if (deviceCounters(false, null, now, null, false).get("without_stored_document") != 1) return "no_document";
        Map<String, Long> sum = new TreeMap<>();
        merge(sum, a);
        merge(sum, single);
        if (sum.get("vsys_total") != 3 || sum.get(MAX_KEY) != 2) return "merge";
        for (String k : COUNTERS) if (!SAFE.matcher(k).matches()) return "counter_vocabulary";
        if (SAFE.matcher("FW-TANGO-04").matches() || !SAFE.matcher("pan_01").matches()) return "safe_filter";
        return null;
    }
}
