package com.securityexpert.nexus.ui2.service.systemlog;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts;
import com.securityexpert.nexus.ui2.persistence.diagnostic.JooqEstateNameRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.JooqDevicePlatformFactsRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.SecretFile;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.sanitizer.core.PublicSuffixes;
import com.securityexpert.nexus.ui2.service.privacy.EstateDictionarySource;
import com.securityexpert.nexus.ui2.service.privacy.HmacKeyProvider;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.sanitizer.SanitizerConfiguration;

/**
 * One-off, read-only measurement (2026-11-10): how much sensitive information the stored device system logs carry and
 * how much of it the aiview masking projection ({@link LogMasking}, the deployed {@code LogMasking.RULE_SET}) catches. Runs inside the
 * ui2-service pod with the service's own keys, as a second JVM on the service jar (PropertiesLauncher, loader.main).
 *
 * <p><b>Privacy boundary.</b> Raw text, masked text, names and values exist only in this process's memory. The output
 * is aggregate counts under fixed labels; every output field is checked against {@code [a-z0-9_.]+} before it is
 * printed. Library logging is switched off and {@code System.err} is discarded, so no library message (which could
 * carry a value) is printed. Errors are reported as lowercase exception class names only.</p>
 *
 * <p><b>Read only.</b> The database connection is set read-only at session level; the artefact store is only read.</p>
 */
public final class LogRiskMeasure {

    // ------------------------------------------------------------------ classes measured
    enum Cls {
        ipv4_private, ipv4_public, ipv4_special, ipv6, ipv6_special, mac, fqdn, url_host, email,
        host_known, host_header, user_known, user_mention, serial_known, serial_shape,
        name_rule, name_policy, name_object, name_zone, name_vs, name_domain,
        interface_known, interface_shape, interface_vlan_suffix,
        num_asn, num_vlan, num_vsid, num_5digit,
        secret_marker, private_key_marker, high_entropy
    }

    /** Classes whose survival is an identity leak (interfaces, numbers and kept ranges are reported, not counted here). */
    static final Set<Cls> IDENTITY = Set.of(Cls.ipv4_private, Cls.ipv4_public, Cls.ipv6, Cls.mac, Cls.fqdn, Cls.url_host,
            Cls.email, Cls.host_known, Cls.host_header, Cls.user_known, Cls.user_mention, Cls.serial_known,
            Cls.serial_shape, Cls.name_rule, Cls.name_policy, Cls.name_object, Cls.name_zone, Cls.name_vs,
            Cls.name_domain, Cls.secret_marker, Cls.private_key_marker);

    /** Profiles: p0 the deployed LogMasking masked line; p1 naive template; p2 egress-v1 as drafted; p3 egress-v1 with Argon fixes. */
    static final int P = 4;

    // ------------------------------------------------------------------ settings
    static long perSourceBytes = 150L * 1024 * 1024;
    static int perChunkBytes = 4 * 1024 * 1024;
    static int chunksPerDevice = 3;
    static final int DISTINCT_CAP = 4_000_000;

    // ------------------------------------------------------------------ vocabularies and dictionary
    static Set<String> tech = new HashSet<>();
    static Set<String> english = new HashSet<>();
    static final Map<String, Cls> dict = new HashMap<>();
    static final Map<String, List<String>> multiByFirst = new HashMap<>();
    static final Map<String, Cls> multiClass = new HashMap<>();
    static final Map<Cls, long[]> dictStats = new EnumMap<>(Cls.class); // entries, generic_dropped, english, multiword, short_or_shape_dropped

    // ------------------------------------------------------------------ patterns (detectors on the raw line)
    static final Pattern TOKEN = Pattern.compile("[^\\s\"'=,;()\\[\\]{}<>|`]+");
    static final Pattern IPV4 = Pattern.compile("(?<![0-9A-Za-z.])(\\d{1,3}(?:\\.\\d{1,3}){3})(?![0-9A-Za-z]|\\.\\d)");
    static final Pattern IPV6 = Pattern.compile("(?<![0-9A-Za-z:.])([0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7})(?![0-9A-Za-z:])");
    static final Pattern MAC = Pattern.compile("(?<![0-9A-Fa-f:-])([0-9A-Fa-f]{2}(?:[:-][0-9A-Fa-f]{2}){5})(?![0-9A-Fa-f:-])");
    static final Pattern MAC_DOTTED = Pattern.compile("(?<![0-9A-Fa-f.])([0-9A-Fa-f]{4}\\.[0-9A-Fa-f]{4}\\.[0-9A-Fa-f]{4})(?![0-9A-Fa-f.])");
    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,24}");
    static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|ftps?|ldaps?|sftp|tftp|ssh)://(?:[^@/\\s]*@)?(\\[[0-9A-Fa-f:]+\\]|[^/\\s:'\"<>?#]+)");
    static final Pattern FQDN = Pattern.compile(
            "(?<![A-Za-z0-9_.@/-])((?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.){1,10}[A-Za-z][A-Za-z0-9-]{0,23}[A-Za-z])(?![A-Za-z0-9_-]|\\.[A-Za-z0-9])");
    static final Pattern SYSLOG_HOST = Pattern.compile(
            "^(?:<\\d+>)?(?:[A-Z][a-z]{2}\\s+\\d{1,2}\\s\\d{2}:\\d{2}:\\d{2}|\\d{4}-\\d{2}-\\d{2}T\\S+)\\s+(\\S+)\\s");
    static final Pattern ELG_HOST = Pattern.compile("\\]@([A-Za-z0-9._-]+)\\[");
    static final Pattern[] USER_PATTERNS = {
            Pattern.compile("(?i)\\b(?:user(?:name)?|admin(?:istrator)?|login|account|owner|operator)\\s*[=:]\\s*['\"]?([A-Za-z0-9][A-Za-z0-9._@\\\\-]{1,63})"),
            Pattern.compile("(?i)\\b(?:user|admin(?:istrator)?|account)\\s+['\"]?([A-Za-z][A-Za-z0-9._@\\\\-]{1,63})"),
            Pattern.compile("(?i)\\bfor (?:invalid |illegal )?(?:user )?([A-Za-z][A-Za-z0-9._@\\\\-]{1,63}) from\\b"),
            Pattern.compile("(?i)\"(?:admin|user|username|admin_name|adminname)\"\\s*:\\s*\"([^\"]{2,64})\""),
            Pattern.compile("(?i)\\bby\\s+(?:user\\s+|admin\\s+)?['\"]?([A-Za-z][A-Za-z0-9._@\\\\-]{2,63})")};
    static final Pattern ASN = Pattern.compile(
            "(?<![A-Za-z])(?:AS|ASN)\\s?[:#]?\\s?(\\d{1,10})\\b|(?i)\\b(?:as[-_ ]?number|remote-as|local-as|peer-as|neighbor-as|autonomous[-_ ]system)\\s*[:=#]?\\s*(\\d{1,10})\\b");
    static final Pattern VLAN = Pattern.compile("(?i)\\bvlan(?:[-_ ]?id)?\\s*[:=#]?\\s*(\\d{1,4})\\b");
    static final Pattern VSID = Pattern.compile("(?i)\\b(?:vsid|vs[-_]?id|vs_|vs)\\s*[:=#]?\\s*(\\d{1,4})\\b");
    static final Pattern FIVE = Pattern.compile("(?<![\\d.:/A-Za-z-])(\\d{5})(?![\\d.:A-Za-z])");
    static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(?:password|passwd|pwd|passphrase|secret|token|api[-_]?key|apikey|community|psk|pre[-_ ]?shared[-_ ]?key|private[-_ ]?key|auth[-_]?key|bearer|sic[-_ ]?key|activation[-_ ]?key)\\b[\"']?\\s*[=:]\\s*[\"']?([^\\s\"',;}{]{4,})");
    static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");
    static final Pattern SERIAL_CONTEXT = Pattern.compile("(?i)\\bserial(?:[-_ ]?(?:number|no))?[\"']?\\s*[:=]\\s*[\"']?([A-Za-z0-9-]{6,32})");
    static final Pattern SERIAL_SHAPE = Pattern.compile("(?=[A-Z0-9]*\\d{4})(?=[A-Z0-9]*[A-Z])[A-Z0-9]{9,16}|0\\d{11,14}");
    static final Pattern IFACE = Pattern.compile(
            "(?i)(?:eth\\d+(?:[-/]\\d+)*|bond\\d+|ethernet\\d+/\\d+|ae\\d+|wrpj?\\d+|tunnel|loopback|vlan|ens\\d+\\w*|enp\\d+\\w*|lan\\d+|br\\d+|vpnt\\d+|mgmt|sync)(?:\\.(\\d+))?");
    static final Pattern UUID = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{32,}");
    static final Pattern B64 = Pattern.compile("[A-Za-z0-9+/=_-]{24,}");

    // shapes kept by the egress profiles (masked tokens, times, versions ...)
    static final Pattern MASKED = Pattern.compile(
            "(?:(?:h-[0-9a-f]{10}|(?:USR|SN|ID|POL|RUL|OBJ|ZON|VSN|DMN)-[0-9A-F]{10})(?:[@.](?:h-[0-9a-f]{10}|[A-Za-z]{2,24}))*|[A-Z]{2,4}(?:-[A-Z]+)+-M?\\d{1,3}(?:-[A-Z0-9]+)*)");
    static final Pattern TIME = Pattern.compile("\\d{1,2}:\\d{2}(?::\\d{2}(?:[.,]\\d+)?)?|\\d{4}[-/]\\d{2}[-/]\\d{2}(?:T[0-9:.+Z-]*)?|\\d{1,2}[-/][A-Za-z]{3}[-/]\\d{4}");
    static final Pattern VERSION = Pattern.compile("(?i)r\\d{2}(?:\\.\\d+)*|v?\\d+(?:\\.\\d+){1,3}(?:-h\\d+)?");
    static final Pattern ALLNUM = Pattern.compile("\\d+");
    static final Pattern HEXLIT = Pattern.compile("0x[0-9a-fA-F]+");
    static final Pattern IP_SHAPE = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}(?:/\\d{1,2})?|[0-9A-Fa-f:]*:[0-9A-Fa-f:]*:[0-9A-Fa-f:]*|[0-9A-Fa-f]{2}(?:[:-][0-9A-Fa-f]{2}){5}");
    static final Pattern PART_SPLIT = Pattern.compile("[._\\-/:@+%#\\\\]+");
    static final Pattern DIGITS = Pattern.compile("\\d+");
    static final Set<String> CODE_WORDS = Set.of("signal", "exit", "errno", "status", "code", "rc", "error");

    // ------------------------------------------------------------------ per-source statistics
    static final class ClsStat {
        long occ, lines, english;
        long[] surv = new long[P];
        final HashSet<Long> distinct = new HashSet<>();
    }

    static final class SourceStat {
        long chunks, lines, bytes, wordTokens, mismatchChunks, errors;
        long[] residualOcc = new long[P]; // tokens replaced by <W> in p2 / p3 (index 2, 3)
        long[] leakLines = new long[P];
        long identityLines;
        final HashMap<String, long[]> ctx = new HashMap<>(); // class|before|after|prev -> count (labels only)
        long residualKnownName;
        final Set<Integer> devices = new HashSet<>();
        final Map<Cls, ClsStat> cls = new EnumMap<>(Cls.class);
        final HashMap<Long, int[]> residual = new HashMap<>(); // hash -> {count, devices, lastDev, shape, english, known}
        final HashSet<Long> residualDrafted = new HashSet<>();
        final long[] shapeOcc = new long[SHAPES.length];
        boolean capped;

        ClsStat c(Cls k) {
            return cls.computeIfAbsent(k, x -> new ClsStat());
        }
    }

    static final String[] SHAPES = {"lowercase_word", "capitalized_word", "uppercase_word", "camelcase_word",
            "word_with_underscore", "word_with_hyphen", "dotted_name", "path_like", "letters_and_digits", "other"};
    static final Map<String, Long> errorCounts = new TreeMap<>();
    static int totalDistinct;

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        System.setOut(new PrintStream(OutputStream.nullOutputStream())); // library writes to stdout are dropped too
        silenceLogging();
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) a.put(args[i], args[i + 1]);
        perSourceBytes = Long.parseLong(a.getOrDefault("--per-source-mb", "150")) * 1024 * 1024;
        perChunkBytes = Integer.parseInt(a.getOrDefault("--per-chunk-mb", "4")) * 1024 * 1024;
        chunksPerDevice = Integer.parseInt(a.getOrDefault("--chunks-per-device", "3"));
        try {
            run(out, a.get("--tech"), a.get("--english"));
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
        System.setProperty("org.jooq.no-logo", "true");
        System.setProperty("org.jooq.no-tips", "true");
    }

    static void run(PrintStream out, String techFile, String englishFile) throws Exception {
        long t0 = System.nanoTime();
        tech = loadWords(Path.of(techFile), false);
        tech.addAll(FreeTextIdentityRules.STOPWORDS);
        tech.addAll(FreeTextIdentityRules.HOST_CONTEXT_WORDS);
        english = loadWords(Path.of(englishFile), true);
        emit(out, "meta", "tech_vocab_words", String.valueOf(tech.size()));
        emit(out, "meta", "english_words", String.valueOf(english.size()));

        Properties props = new Properties();
        props.setProperty("user", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_USER_FILE")), "db_user").strip());
        props.setProperty("password", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_PASSWORD_FILE")), "db_password").strip());
        props.setProperty("ApplicationName", "logrisk-measure-readonly");
        try (Connection conn = new org.postgresql.Driver().connect(System.getenv("UI2_DB_URL"), props)) {
            conn.setAutoCommit(false);
            conn.setReadOnly(true);
            try (Statement s = conn.createStatement()) {
                s.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
                s.execute("SET statement_timeout = '300s'");
            }
            Settings settings = new Settings().withExecuteLogging(false).withRenderSchema(true);
            TransactionBoundary tb = new TransactionBoundary() {
                @Override
                public <T> T inTransaction(java.util.function.Function<org.jooq.DSLContext, T> work) {
                    return work.apply(DSL.using(conn, SQLDialect.POSTGRES, settings));
                }
            };
            JooqDeviceRepository devices = new JooqDeviceRepository(tb);
            JooqDevicePlatformFactsRepository facts = new JooqDevicePlatformFactsRepository(tb);
            byte[] key = new HmacKeyProvider().getSecretKey();
            TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(key);
            EstateDictionarySource estate = new EstateDictionarySource(key,
                    SystemLogReadConfiguration.estateIdentities(devices, facts, names), Duration.ofHours(4));
            KnownNames known = SanitizerConfiguration.knownNames(devices, names).get();
            Supplier<KnownNames> knownOnce = () -> known;
            // logmask-v5: the masked view's estate-name dictionary, built by the service's own catalog code (read once).
            EstateNameCatalog estateNames = new EstateNameCatalog(key,
                    SystemLogReadConfiguration.estateNameSource(new JooqEstateNameRepository(tb)), Duration.ofHours(4));
            estateNames.get();
            EstateNameCatalog.Stats nameStats = estateNames.stats();
            for (Map.Entry<EstateNameCatalog.Kind, Integer> e : new java.util.TreeMap<>(nameStats.kept()).entrySet()) {
                emit(out, "meta", "masking_names_" + e.getKey().name().toLowerCase(Locale.ROOT), String.valueOf(e.getValue()));
            }
            emit(out, "meta", "masking_names_generic_dropped", String.valueOf(nameStats.genericDropped()));
            emit(out, "meta", "masking_names_snapshot_failures", String.valueOf(nameStats.snapshotFailures()));
            LogMasking masking = new LogMasking(key, knownOnce, estate, estateNames);
            emit(out, "meta", "rule_set", LogMasking.RULE_SET.replace('-', '_'));
            ArtefactStore store = new FileArtefactStore(Path.of(System.getenv("UI2_ARTEFACT_STORE_ROOT")),
                    ArtefactStoreCipher.fromBase64Key(SecretFile.readRequired(
                            Path.of(System.getenv("UI2_ARTEFACT_STORE_KEY_FILE")), "artefact_store_key")));

            buildDictionary(conn, devices, facts);
            emit(out, "meta", "dictionary_seconds", String.valueOf((System.nanoTime() - t0) / 1_000_000_000L));
            for (Map.Entry<Cls, long[]> e : dictStats.entrySet()) {
                long[] v = e.getValue();
                emit(out, "dict", e.getKey().name(), n(v[0]), n(v[1]), n(v[2]), n(v[3]), n(v[4]));
            }

            Map<String, SourceStat> stats = new TreeMap<>();
            List<Object[]> chosen = chooseChunks(conn);
            emit(out, "meta", "chunks_chosen", String.valueOf(chosen.size()));
            Map<String, Integer> deviceIndex = new HashMap<>();
            int done = 0;
            for (Object[] c : chosen) {
                String source = (String) c[2];
                SourceStat st = stats.computeIfAbsent(source, x -> new SourceStat());
                int dev = deviceIndex.computeIfAbsent((String) c[1], x -> deviceIndex.size());
                try {
                    scanChunk(store, masking, (String) c[3], (byte[]) c[4], source, dev, st);
                } catch (Throwable t) {
                    st.errors++;
                    errorCounts.merge(t.getClass().getSimpleName().toLowerCase(Locale.ROOT), 1L, Long::sum);
                }
                if (++done % 100 == 0) emit(out, "progress", String.valueOf(done), String.valueOf(chosen.size()));
            }
            conn.rollback();
            report(out, stats);
            emit(out, "meta", "total_seconds", String.valueOf((System.nanoTime() - t0) / 1_000_000_000L));
        }
    }

    // ------------------------------------------------------------------ dictionary of known names
    static void buildDictionary(Connection conn, JooqDeviceRepository devices, JooqDevicePlatformFactsRepository facts)
            throws Exception {
        for (Map.Entry<String, DevicePlatformFacts> e : facts.findAll().entrySet()) {
            e.getValue().serialNumber().ifPresent(v -> addName(v, Cls.serial_known));
        }
        for (DeviceSummaryRecord d : devices.listAll()) {
            d.observedHostname().ifPresent(v -> addName(v, Cls.host_known));
            d.clusterMemberRef().ifPresent(v -> addName(v, Cls.host_known));
        }
        query(conn, "select server_name, multi_domain_server, domain_name from cp_domain_server_placement", rs -> {
            addName(rs.getString(1), Cls.host_known);
            addName(rs.getString(2), Cls.host_known);
            addName(rs.getString(3), Cls.name_domain);
        });
        query(conn, "select distinct changed_by from policy_rule_history where changed_by is not null",
                rs -> addName(rs.getString(1), Cls.user_known));
        query(conn, "select distinct context from device_interface where context is not null", rs -> addName(rs.getString(1), Cls.name_vs));
        query(conn, "select distinct routing_context from device_interface where routing_context is not null",
                rs -> addName(rs.getString(1), Cls.name_vs));
        query(conn, "select distinct zone from device_interface where zone is not null", rs -> addName(rs.getString(1), Cls.name_zone));
        query(conn, "select distinct name from device_interface where name is not null",
                rs -> addName(rs.getString(1), Cls.interface_known));
        query(conn, "select distinct policy_name from device_policy_install where policy_name is not null",
                rs -> addName(rs.getString(1), Cls.name_policy));
        query(conn, "select snapshot::text from cp_policy_object_inventory", rs -> walkPolicy(rs.getString(1)));
        // One policy snapshot at a time (largest is about 11 MB compressed); the walk runs here, not in the database.
        List<String> ids = new ArrayList<>();
        query(conn, "select policy_id from policy_snapshot order by pg_column_size(snapshot)", rs -> ids.add(rs.getString(1)));
        for (String id : ids) {
            try (PreparedStatement ps = conn.prepareStatement("select metadata::text, snapshot::text from policy_snapshot where policy_id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        walkPolicy(rs.getString(1));
                        walkPolicy(rs.getString(2));
                    }
                }
            }
        }
    }

    interface Row {
        void accept(ResultSet rs) throws Exception;
    }

    static void query(Connection conn, String sql, Row row) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.setFetchSize(500);
            try (ResultSet rs = s.executeQuery(sql)) {
                while (rs.next()) row.accept(rs);
            }
        }
    }

    static final JsonFactory JSON = new JsonFactory();
    static final Set<String> REF_KEYS = Set.of("refs", "members", "source", "destination", "service", "application",
            "services", "applications", "sources", "destinations", "member");

    /** Collects the names in a policy snapshot by the path they sit under. Values are kept in this process only. */
    static void walkPolicy(String json) throws Exception {
        if (json == null || json.isEmpty()) return;
        try (JsonParser p = JSON.createParser(json)) {
            Deque<String> path = new ArrayDeque<>();
            String field = null;
            JsonToken t;
            while ((t = p.nextToken()) != null) {
                switch (t) {
                    case START_OBJECT, START_ARRAY -> {
                        path.push(field == null ? (t == JsonToken.START_ARRAY ? "[]" : "{}") : field);
                        field = null;
                    }
                    case END_OBJECT, END_ARRAY -> {
                        if (!path.isEmpty()) path.pop();
                        field = null;
                    }
                    case FIELD_NAME -> field = p.currentName();
                    case VALUE_STRING -> {
                        String key = field != null ? field : (path.isEmpty() ? "" : path.peek());
                        Cls c = classify(key.toLowerCase(Locale.ROOT), path);
                        if (c != null) addName(p.getText(), c);
                        field = null;
                    }
                    default -> field = null;
                }
            }
        }
    }

    static Cls classify(String key, Deque<String> path) {
        boolean inRules = path.contains("rules");
        boolean inObjects = path.contains("objects");
        boolean inSections = path.contains("sections");
        switch (key) {
            case "comment", "comments", "uuid", "id", "parentruleid", "type", "action", "time", "log", "enabled", "status",
                    "number", "schedule", "values", "vendor", "artefactref", "collectedat", "sourceid", "containerid",
                    "inventoryprovenance", "content", "vpn", "last-modified", "track-accounting", "track-per-connection",
                    "track-per-session", "track-alert", "negated", "failures", "kind" -> {
                return null;
            }
            default -> {
            }
        }
        if (key.equals("last-modifier") || key.equals("changed-by") || key.equals("creator")) return Cls.user_known;
        if (key.equals("layer-name") || key.equals("containername") || key.equals("layer") || key.equals("package"))
            return Cls.name_policy;
        if (key.equals("install-on") || key.equals("sourcename")) return Cls.host_known;
        if (key.contains("zone") || key.equals("from") || key.equals("to")) return Cls.name_zone;
        if (key.contains("vsys") || key.contains("virtual-system")) return Cls.name_vs;
        if (key.contains("domain")) return Cls.name_domain;
        if (key.equals("name")) {
            if (inRules) return path.peek() != null && (path.peek().equals("{}") || path.peek().equals("rules")) ? Cls.name_rule : Cls.name_object;
            if (inObjects) return Cls.name_object;
            if (inSections) return Cls.name_policy;
            return Cls.name_policy; // metadata name, the policy itself
        }
        if (REF_KEYS.contains(key)) return Cls.name_object;
        return null;
    }

    static final Set<String> dropped = new HashSet<>();

    static void addName(String raw, Cls c) {
        long[] st = dictStats.computeIfAbsent(c, x -> new long[5]);
        if (raw == null) return;
        String v = raw.strip();
        String lower = v.toLowerCase(Locale.ROOT);
        if (!dict.containsKey(lower) && !multiClass.containsKey(lower) && !dropped.add(c.ordinal() + ":" + lower)) return;
        if (lower.length() < 3 || ALLNUM.matcher(lower).matches() || UUID.matcher(lower).matches() || HEX.matcher(lower).matches()
                || IPV4.matcher(lower).find() || lower.chars().noneMatch(Character::isLetter)) {
            st[4]++;
            return;
        }
        if (isTechPhrase(lower)) {
            st[1]++;
            return;
        }
        if (lower.indexOf(' ') >= 0) {
            String first = lower.substring(0, lower.indexOf(' '));
            if (multiClass.putIfAbsent(lower, c) == null) {
                multiByFirst.computeIfAbsent(first, x -> new ArrayList<>()).add(lower);
                st[0]++;
                st[3]++;
            }
            return;
        }
        if (dict.putIfAbsent(lower, c) == null) {
            st[0]++;
            if (english.contains(lower)) st[2]++;
        }
    }

    /** A name made only of technical-vocabulary words is generic ("Cleanup rule", "Any"), not an identity. */
    static boolean isTechPhrase(String lower) {
        if (tech.contains(lower)) return true;
        String[] parts = lower.split("[\\s._\\-/:]+");
        int words = 0;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (ALLNUM.matcher(part).matches()) continue;
            if (!tech.contains(part)) return false;
            words++;
        }
        return words > 0;
    }

    // ------------------------------------------------------------------ chunk selection
    static List<Object[]> chooseChunks(Connection conn) throws Exception {
        Map<String, Map<String, List<Object[]>>> bySource = new TreeMap<>();
        // The same join as JooqSystemLogReadRepository.findChunk: the stored path is the artefact's recovery_volume_path.
        query(conn, "select c.chunk_id, c.device_id, c.source_key, a.recovery_volume_path, c.wrapped_data_key, c.bytes"
                + " from system_log_chunk c join backup_artefact a on a.artefact_id = c.artefact_ref"
                + " where c.pruned_at is null and a.recovery_volume_path is not null and c.wrapped_data_key is not null"
                + " order by c.source_key, c.device_id, c.collected_at desc, c.chunk_id", rs -> {
            Object[] row = {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBytes(5), rs.getLong(6)};
            bySource.computeIfAbsent((String) row[2], x -> new LinkedHashMap<>())
                    .computeIfAbsent((String) row[1], x -> new ArrayList<>()).add(row);
        });
        List<Object[]> chosen = new ArrayList<>();
        for (Map<String, List<Object[]>> perDevice : bySource.values()) {
            Map<String, List<Object[]>> picks = new LinkedHashMap<>();
            for (Map.Entry<String, List<Object[]>> e : perDevice.entrySet()) {
                List<Object[]> l = e.getValue();
                List<Object[]> p = new ArrayList<>();
                int[] idx = chunksPerDevice >= 3 ? new int[] {0, l.size() / 2, l.size() - 1} : new int[] {0};
                Set<Integer> seen = new HashSet<>();
                for (int i : idx) if (seen.add(i)) p.add(l.get(i));
                picks.put(e.getKey(), p);
            }
            long budget = perSourceBytes;
            for (int round = 0; round < 3 && budget > 0; round++) {
                for (List<Object[]> p : picks.values()) {
                    if (round >= p.size()) continue;
                    long cost = Math.min((Long) p.get(round)[5], perChunkBytes);
                    if (budget - cost < 0) continue;
                    budget -= cost;
                    chosen.add(p.get(round));
                }
            }
        }
        return chosen;
    }

    // ------------------------------------------------------------------ one chunk
    static void scanChunk(ArtefactStore store, LogMasking masking, String ref, byte[] wrapped, String source, int dev,
            SourceStat st) throws Exception {
        byte[] bytes;
        try (InputStream in = store.retrieve(new ArtefactRef(ref), wrapped, true)) {
            bytes = in.readNBytes(perChunkBytes);
            if (in.read() >= 0) {
                int lf = -1;
                for (int i = bytes.length - 1; i >= 0; i--) if (bytes[i] == '\n') { lf = i; break; }
                if (lf > 0) bytes = java.util.Arrays.copyOf(bytes, lf + 1);
            }
        }
        Charset cs = isValidUtf8(bytes) ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
        LogMasking.Session session = masking.session();
        String raw = new String(bytes, cs);
        String masked = new String(session.maskChunk(bytes), cs);
        String[] rawLines = raw.split("\n", -1);
        String[] maskedLines = masked.split("\n", -1);
        if (rawLines.length != maskedLines.length) {
            st.mismatchChunks++;
            maskedLines = new String[rawLines.length];
            for (int i = 0; i < rawLines.length; i++) maskedLines[i] = session.maskLine(rawLines[i]);
        }
        st.chunks++;
        st.bytes += bytes.length;
        st.devices.add(dev);
        for (int i = 0; i < rawLines.length; i++) {
            String r = stripCr(rawLines[i]);
            if (r.isEmpty() && i == rawLines.length - 1) continue;
            scanLine(r, stripCr(maskedLines[i]), dev, st);
        }
    }

    static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    record Occ(Cls cls, String value) {
    }

    static void scanLine(String raw, String p0, int dev, SourceStat st) {
        st.lines++;
        List<Occ> occ = new ArrayList<>(8);
        detect(raw, occ);
        String[] prof = new String[P];
        prof[0] = p0.toLowerCase(Locale.ROOT);
        prof[1] = DIGITS.matcher(prof[0]).replaceAll("#");
        prof[2] = egress(p0, true, st, dev).toLowerCase(Locale.ROOT);
        prof[3] = egress(p0, false, st, dev).toLowerCase(Locale.ROOT);
        boolean[] leak = new boolean[P];
        for (Occ o : occ) if (IDENTITY.contains(o.cls)) { st.identityLines++; break; }
        EnumMap<Cls, Boolean> seenInLine = new EnumMap<>(Cls.class);
        for (Occ o : occ) {
            ClsStat cs = st.c(o.cls);
            String v = o.value.toLowerCase(Locale.ROOT);
            cs.occ++;
            if (seenInLine.put(o.cls, Boolean.TRUE) == null) cs.lines++;
            if (cs.distinct.size() < 2_000_000) cs.distinct.add(hash(v));
            if (english.contains(v)) cs.english++;
            for (int k = 0; k < P; k++) {
                if (containsBounded(prof[k], v)) {
                    cs.surv[k]++;
                    if (k == 0 && IDENTITY.contains(o.cls)) context(raw, v, o.cls, st);
                    if (IDENTITY.contains(o.cls)) leak[k] = true;
                }
            }
        }
        for (int k = 0; k < P; k++) if (leak[k]) st.leakLines[k]++;
    }

    /** Where a surviving value sits: the character class before and after it and the technical word before it. */
    static void context(String raw, String valueLower, Cls c, SourceStat st) {
        String lower = raw.toLowerCase(Locale.ROOT);
        int i = -1, from = 0;
        while (true) {
            int j = lower.indexOf(valueLower, from);
            if (j < 0) break;
            int e = j + valueLower.length();
            if ((j == 0 || !Character.isLetterOrDigit(lower.charAt(j - 1)))
                    && (e >= lower.length() || !Character.isLetterOrDigit(lower.charAt(e)))) { i = j; break; }
            from = j + 1;
        }
        if (i < 0) return;
        int end = i + valueLower.length();
        String before = i == 0 ? "bol" : charLabel(raw.charAt(i - 1));
        String after = end >= raw.length() ? "eol" : charLabel(raw.charAt(end));
        String prev = "none";
        Matcher t = TOKEN.matcher(raw.substring(0, i));
        String lastTok = null;
        while (t.find()) lastTok = trimPunct(t.group());
        if (lastTok != null && !lastTok.isEmpty()) {
            String lt = lastTok.toLowerCase(Locale.ROOT);
            if (tech.contains(lt)) prev = SAFE.matcher(lt).matches() ? lt : "vocab_other";
            else if (ALLNUM.matcher(lt).matches()) prev = "number";
            else if (TIME.matcher(lt).matches()) prev = "time";
            else prev = "nonvocab";
        }
        st.ctx.computeIfAbsent(c.name() + "|" + before + "|" + after + "|" + prev, x -> new long[1])[0]++;
    }

    static String charLabel(char ch) {
        return switch (ch) {
            case ' ', '\t' -> "sp";
            case '=' -> "eq";
            case ':' -> "colon";
            case '[' -> "lbr";
            case ']' -> "rbr";
            case '(' -> "lpar";
            case ')' -> "rpar";
            case '"' -> "dq";
            case '\'' -> "sq";
            case '@' -> "at";
            case '_' -> "us";
            case '-' -> "hy";
            case '/' -> "sl";
            case '.' -> "dot";
            case ',' -> "comma";
            case ';' -> "semi";
            case '<' -> "lt";
            case '>' -> "gt";
            case '{' -> "lcb";
            case '}' -> "rcb";
            default -> Character.isLetter(ch) ? "alpha" : Character.isDigit(ch) ? "digit" : "other";
        };
    }

    // ------------------------------------------------------------------ detectors
    static void detect(String raw, List<Occ> out) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (raw.indexOf('.') >= 0) {
            Matcher m = IPV4.matcher(raw);
            while (m.find()) {
                Cls c = ipv4Class(m.group(1));
                if (c != null) out.add(new Occ(c, m.group(1)));
            }
            m = MAC_DOTTED.matcher(raw);
            while (m.find()) out.add(new Occ(Cls.mac, m.group(1)));
            m = FQDN.matcher(raw);
            while (m.find()) if (fqdnLike(m.group(1))) out.add(new Occ(Cls.fqdn, m.group(1)));
        }
        if (raw.indexOf(':') >= 0 || raw.indexOf('-') >= 0) {
            Matcher m = MAC.matcher(raw);
            while (m.find()) {
                String v = m.group(1).toLowerCase(Locale.ROOT);
                if (!v.equals("00:00:00:00:00:00") && !v.equals("ff:ff:ff:ff:ff:ff")) out.add(new Occ(Cls.mac, m.group(1)));
            }
        }
        if (raw.indexOf("::") >= 0 || raw.indexOf(':') >= 0) {
            Matcher m = IPV6.matcher(raw);
            while (m.find()) {
                String v = m.group(1);
                long colons = v.chars().filter(ch -> ch == ':').count();
                if (v.equals("::") || v.equalsIgnoreCase("::1")) continue;
                if (!(v.contains("::") || colons == 7)) continue;
                if (colons == 5 && MAC.matcher(v).matches()) continue;
                if (v.replace(":", "").isEmpty()) continue;
                try {
                    java.net.InetAddress ia = java.net.InetAddress.getByName(v); // a literal with ':' is parsed, never resolved
                    boolean special = ia.isLinkLocalAddress() || ia.isLoopbackAddress() || ia.isAnyLocalAddress()
                            || ia.isMulticastAddress() || ia instanceof java.net.Inet4Address;
                    out.add(new Occ(special ? Cls.ipv6_special : Cls.ipv6, v));
                } catch (Exception notAnAddress) {
                    // not an IPv6 literal: not counted
                }
            }
        }
        if (raw.indexOf('@') >= 0) {
            Matcher m = EMAIL.matcher(raw);
            while (m.find()) out.add(new Occ(Cls.email, m.group()));
            m = ELG_HOST.matcher(raw);
            while (m.find()) out.add(new Occ(Cls.host_header, m.group(1)));
        }
        if (raw.indexOf("://") >= 0) {
            Matcher m = URL.matcher(raw);
            while (m.find()) {
                String h = m.group(1);
                if (!IPV4.matcher(h).matches()) out.add(new Occ(Cls.url_host, h));
            }
        }
        Matcher sh = SYSLOG_HOST.matcher(raw);
        if (sh.find()) {
            String h = sh.group(1);
            if (!h.equals("-") && h.chars().anyMatch(Character::isLetter)) out.add(new Occ(Cls.host_header, h));
        }
        for (Pattern up : USER_PATTERNS) {
            Matcher m = up.matcher(raw);
            while (m.find()) {
                String u = trimPunct(m.group(1));
                String ul = u.toLowerCase(Locale.ROOT);
                if (ul.length() < 2 || tech.contains(ul) || ALLNUM.matcher(ul).matches() || MASKED.matcher(u).matches()) continue;
                out.add(new Occ(Cls.user_mention, u));
            }
        }
        if (lower.contains("as")) {
            Matcher m = ASN.matcher(raw);
            while (m.find()) out.add(new Occ(Cls.num_asn, m.group(1) != null ? m.group(1) : m.group(2)));
        }
        if (lower.contains("vlan")) {
            Matcher m = VLAN.matcher(raw);
            while (m.find()) if (!m.group(1).equals("0")) out.add(new Occ(Cls.num_vlan, m.group(1)));
        }
        if (lower.contains("vs")) {
            Matcher m = VSID.matcher(raw);
            while (m.find()) if (!m.group(1).equals("0")) out.add(new Occ(Cls.num_vsid, m.group(1)));
        }
        Matcher f = FIVE.matcher(raw);
        while (f.find()) out.add(new Occ(Cls.num_5digit, f.group(1)));
        if (lower.contains("pass") || lower.contains("pwd") || lower.contains("secret") || lower.contains("token")
                || lower.contains("key") || lower.contains("community") || lower.contains("psk") || lower.contains("bearer")) {
            Matcher m = SECRET.matcher(raw);
            while (m.find()) {
                String v = m.group(1);
                String vl = v.toLowerCase(Locale.ROOT);
                if (vl.contains("redact") || vl.contains("*") || vl.startsWith("<") || tech.contains(vl)
                        || Set.of("none", "null", "true", "false", "set", "unset", "required", "empty", "changed").contains(vl)) continue;
                out.add(new Occ(Cls.secret_marker, v));
            }
            m = PRIVATE_KEY.matcher(raw);
            while (m.find()) out.add(new Occ(Cls.private_key_marker, m.group()));
        }
        if (lower.contains("serial")) {
            Matcher m = SERIAL_CONTEXT.matcher(raw);
            while (m.find()) out.add(new Occ(Cls.serial_shape, m.group(1)));
        }
        // token-level detectors: dictionary, interfaces, serial shape, high entropy
        Matcher t = TOKEN.matcher(raw);
        while (t.find()) {
            String tok = trimPunct(t.group());
            if (tok.isEmpty()) continue;
            String tl = tok.toLowerCase(Locale.ROOT);
            Cls c = dict.get(tl);
            if (c != null) {
                out.add(new Occ(c, tok));
            } else if (tl.indexOf('/') >= 0 || tl.indexOf(':') >= 0 || tl.indexOf('@') >= 0 || tl.indexOf('\\') >= 0) {
                for (String part : tl.split("[/:@\\\\]+")) {
                    Cls pc = dict.get(trimPunct(part));
                    if (pc != null) out.add(new Occ(pc, trimPunct(part)));
                }
            }
            List<String> multi = multiByFirst.get(tl);
            if (multi != null) {
                int at = t.start() + (t.group().indexOf(tok) < 0 ? 0 : t.group().indexOf(tok));
                for (String full : multi) {
                    if (lower.startsWith(full, at) && (at + full.length() == lower.length()
                            || !Character.isLetterOrDigit(lower.charAt(at + full.length())))) {
                        out.add(new Occ(multiClass.get(full), full));
                    }
                }
            }
            Matcher im = IFACE.matcher(tok);
            if (im.matches()) {
                out.add(new Occ(Cls.interface_shape, tok));
                if (im.group(1) != null) out.add(new Occ(Cls.interface_vlan_suffix, tok));
            }
            if (SERIAL_SHAPE.matcher(tok).matches() && !HEX.matcher(tok).matches()) out.add(new Occ(Cls.serial_shape, tok));
            if (tok.length() >= 24 && !UUID.matcher(tok).find()) {
                if (HEX.matcher(tok).matches()) out.add(new Occ(Cls.high_entropy, tok));
                else if (B64.matcher(tok).matches() && entropy(tok) >= 4.0 && tok.chars().anyMatch(Character::isDigit)
                        && tok.chars().anyMatch(Character::isUpperCase) && tok.chars().anyMatch(Character::isLowerCase))
                    out.add(new Occ(Cls.high_entropy, tok));
            }
        }
    }

    static Cls ipv4Class(String ip) {
        String[] o = ip.split("\\.");
        int[] v = new int[4];
        for (int i = 0; i < 4; i++) {
            v[i] = Integer.parseInt(o[i]);
            if (v[i] > 255) return null;
        }
        if (v[0] == 0 || v[0] == 127 || v[0] >= 224 || (v[0] == 169 && v[1] == 254)) return Cls.ipv4_special;
        if (v[0] == 10 || (v[0] == 172 && v[1] >= 16 && v[1] <= 31) || (v[0] == 192 && v[1] == 168)
                || (v[0] == 100 && v[1] >= 64 && v[1] <= 127)) return Cls.ipv4_private;
        return Cls.ipv4_public;
    }

    static final Set<String> INTERNAL_SUFFIXES = Set.of("local", "lan", "corp", "intra", "intranet", "internal", "home",
            "localdomain", "ad", "int", "loc", "priv", "private", "domain", "grp", "dmz", "prod", "test");

    static boolean fqdnLike(String v) {
        String[] labels = v.toLowerCase(Locale.ROOT).split("\\.");
        String last = labels[labels.length - 1];
        if (FreeTextIdentityRules.EXTENSIONS.contains(last)) return false;
        int withLetter = 0;
        for (String l : labels) if (l.chars().anyMatch(Character::isLetter)) withLetter++;
        if (withLetter < 2) return false;
        return PublicSuffixes.get().isTld(last) || INTERNAL_SUFFIXES.contains(last);
    }

    static String trimPunct(String s) {
        int a = 0, b = s.length();
        while (a < b && ".:!?*#$,;@\\-".indexOf(s.charAt(a)) >= 0) a++;
        while (b > a && ".:!?*#$,;\\-".indexOf(s.charAt(b - 1)) >= 0) b--;
        return s.substring(a, b);
    }

    static double entropy(String s) {
        int[] f = new int[128];
        int n = 0;
        for (char c : s.toCharArray()) if (c < 128) { f[c]++; n++; }
        double h = 0;
        for (int x : f) if (x > 0) { double p = (double) x / n; h -= p * Math.log(p) / Math.log(2); }
        return h;
    }

    // ------------------------------------------------------------------ egress profile simulation
    /**
     * p2 (drafted, {@code drafted=true}): words survive when every alphabetic part is in the technical vocabulary or the
     * English word list; numbers of up to five digits, interface names and versions survive. p3 (Argon fixes): words
     * survive only when every part is in the technical vocabulary; every number becomes {@code <N>} except up to four
     * digits after a code keyword; an interface's VLAN suffix becomes {@code <VLAN>}. Masked tokens, addresses, times
     * and hex literals are kept as they are in both (at egress they would become aliases).
     */
    static String egress(String line, boolean drafted, SourceStat st, int dev) {
        StringBuilder sb = new StringBuilder(line.length() + 16);
        Matcher m = TOKEN.matcher(line);
        int last = 0;
        String prev = "";
        while (m.find()) {
            sb.append(line, last, m.start());
            String tok = m.group();
            String core = trimPunct(tok);
            boolean url = core.contains("://");
            String rep = core.isEmpty() ? core : url ? "<URL>" : egressToken(core, prev, drafted);
            if (!url && core.chars().anyMatch(Character::isLetter)) {
                if (!drafted) {
                    st.wordTokens++;
                    if (rep.equals("<W>")) residual(core, st, dev);
                } else if (rep.equals("<W>")) {
                    st.residualOcc[2]++;
                    if (st.residualDrafted.size() < 2_000_000) st.residualDrafted.add(hash(core.toLowerCase(Locale.ROOT)));
                }
            }
            if (core.isEmpty() || rep.equals(core)) sb.append(tok);
            else sb.append(tok.replace(core, rep));
            prev = core.toLowerCase(Locale.ROOT);
            last = m.end();
        }
        sb.append(line, last, line.length());
        return sb.toString();
    }

    static String egressToken(String core, String prev, boolean drafted) {
        if (MASKED.matcher(core).matches() || IP_SHAPE.matcher(core).matches() || TIME.matcher(core).matches()
                || HEXLIT.matcher(core).matches()) return core;
        if (ALLNUM.matcher(core).matches()) {
            if (drafted) return core.length() <= 5 || CODE_WORDS.contains(prev) ? core : "<N>";
            return CODE_WORDS.contains(prev) && core.length() <= 4 ? core : "<N>";
        }
        if (VERSION.matcher(core).matches()) return core;
        Matcher im = IFACE.matcher(core);
        if (im.matches()) {
            if (drafted || im.group(1) == null) return core;
            return core.substring(0, im.start(1)) + "<VLAN>";
        }
        String lower = core.toLowerCase(Locale.ROOT);
        if (tech.contains(lower) || (drafted && english.contains(lower))) return drafted ? core : DIGITS.matcher(core).replaceAll("<N>");
        boolean ok = true;
        for (String part : PART_SPLIT.split(lower)) {
            if (part.isEmpty() || ALLNUM.matcher(part).matches()) continue;
            String letters = part.replaceAll("\\d+$", "");
            if (tech.contains(part) || tech.contains(letters) || (drafted && (english.contains(part) || english.contains(letters)))) continue;
            ok = false;
            break;
        }
        if (!ok) return "<W>";
        return drafted ? core : DIGITS.matcher(core).replaceAll("<N>");
    }

    static void residual(String core, SourceStat st, int dev) {
        st.residualOcc[3]++;
        String lower = core.toLowerCase(Locale.ROOT);
        int shape = shape(core);
        st.shapeOcc[shape]++;
        if (dict.containsKey(lower)) st.residualKnownName++;
        long h = hash(lower);
        int[] e = st.residual.get(h);
        if (e == null) {
            if (totalDistinct >= DISTINCT_CAP) {
                st.capped = true;
                return;
            }
            totalDistinct++;
            e = new int[] {0, 0, -1, shape, english.contains(lower) ? 1 : 0, dict.containsKey(lower) ? 1 : 0};
            st.residual.put(h, e);
        }
        e[0]++;
        if (e[2] != dev) {
            e[1]++;
            e[2] = dev;
        }
    }

    static int shape(String s) {
        if (s.indexOf('/') >= 0 || s.indexOf('\\') >= 0) return 7;
        if (s.indexOf('_') >= 0) return 4;
        if (s.indexOf('-') >= 0) return 5;
        if (s.indexOf('.') >= 0) return 6;
        boolean digit = s.chars().anyMatch(Character::isDigit);
        boolean letter = s.chars().anyMatch(Character::isLetter);
        if (digit && letter) return 8;
        if (!letter) return 9;
        if (s.chars().allMatch(c -> !Character.isLetter(c) || Character.isLowerCase(c))) return 0;
        if (s.chars().allMatch(c -> !Character.isLetter(c) || Character.isUpperCase(c))) return 2;
        if (Character.isUpperCase(s.charAt(0)) && s.substring(1).chars().allMatch(c -> !Character.isLetter(c) || Character.isLowerCase(c))) return 1;
        return 3;
    }

    static boolean containsBounded(String hay, String needle) {
        if (needle.isEmpty()) return false;
        int from = 0;
        while (true) {
            int i = hay.indexOf(needle, from);
            if (i < 0) return false;
            int j = i + needle.length();
            boolean before = i == 0 || !Character.isLetterOrDigit(hay.charAt(i - 1));
            boolean after = j >= hay.length() || !Character.isLetterOrDigit(hay.charAt(j));
            if (before && after) return true;
            from = i + 1;
        }
    }

    static long hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    // ------------------------------------------------------------------ output (labels and numbers only)
    static void report(PrintStream out, Map<String, SourceStat> stats) {
        for (Map.Entry<String, SourceStat> e : stats.entrySet()) {
            String src = e.getKey();
            if (!SystemLogReadService.CHUNK_SOURCES.contains(src)) src = "other_source";
            SourceStat s = e.getValue();
            long dEnglish = 0, dKnown = 0, dev1 = 0, dev2to5 = 0, dev6 = 0;
            long[] shapeDistinct = new long[SHAPES.length];
            for (int[] v : s.residual.values()) {
                if (v[4] == 1) dEnglish++;
                if (v[5] == 1) dKnown++;
                if (v[1] <= 1) dev1++;
                else if (v[1] <= 5) dev2to5++;
                else dev6++;
                shapeDistinct[v[3]]++;
            }
            emit(out, "src", src, n(s.chunks), n(s.devices.size()), n(s.lines), n(s.bytes), n(s.wordTokens),
                    n(s.residualOcc[3]), n(s.residual.size()), n(s.residualOcc[2]), n(s.residualDrafted.size()), n(dEnglish),
                    n(dKnown), n(dev1), n(dev2to5), n(dev6), n(s.residualKnownName), n(s.leakLines[0]), n(s.leakLines[1]),
                    n(s.leakLines[2]), n(s.leakLines[3]), n(s.mismatchChunks), n(s.errors), n(s.identityLines),
                    s.capped ? "capped" : "complete");
            for (Map.Entry<Cls, ClsStat> c : s.cls.entrySet()) {
                ClsStat cs = c.getValue();
                emit(out, "cls", src, c.getKey().name(), n(cs.occ), n(cs.lines), n(cs.distinct.size()), n(cs.surv[0]),
                        n(cs.surv[1]), n(cs.surv[2]), n(cs.surv[3]), n(cs.english));
            }
            Map<String, List<Map.Entry<String, long[]>>> byCls = new TreeMap<>();
            for (Map.Entry<String, long[]> x : s.ctx.entrySet()) {
                byCls.computeIfAbsent(x.getKey().substring(0, x.getKey().indexOf('|')), k -> new ArrayList<>()).add(x);
            }
            for (List<Map.Entry<String, long[]>> l : byCls.values()) {
                l.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
                for (Map.Entry<String, long[]> x : l.subList(0, Math.min(8, l.size()))) {
                    String[] k = x.getKey().split("\\|");
                    emit(out, "ctx", src, k[0], k[1], k[2], k[3], n(x.getValue()[0]));
                }
            }
            for (int i = 0; i < SHAPES.length; i++) {
                if (s.shapeOcc[i] > 0) emit(out, "shape", src, SHAPES[i], n(s.shapeOcc[i]), n(shapeDistinct[i]));
            }
        }
        for (Map.Entry<String, Long> e : errorCounts.entrySet()) emit(out, "err", e.getKey(), n(e.getValue()));
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

    static String n(long v) {
        return Long.toString(v);
    }

    static Set<String> loadWords(Path p, boolean lettersOnly) throws Exception {
        Set<String> out = new HashSet<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            int hashAt = line.indexOf('#');
            String l = (hashAt >= 0 ? line.substring(0, hashAt) : line).strip().toLowerCase(Locale.ROOT);
            if (l.isEmpty()) continue;
            for (String w : l.split("\\s+")) {
                if (lettersOnly && !w.chars().allMatch(c -> c >= 'a' && c <= 'z')) continue;
                if (lettersOnly && w.length() < 2) continue;
                out.add(w);
            }
        }
        return out;
    }

    static boolean isValidUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private LogRiskMeasure() {
    }

}
