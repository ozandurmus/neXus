package com.securityexpert.nexus.ui2.service.policy;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.SecretFile;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.CollectionFailure;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Section;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGateConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.Interface;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.VdomConfig;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.VipGroup;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.VipObject;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyMapper;

/**
 * Counts-only, in-pod hygiene and lookup evaluation measurement over stored FortiGate {@code show_configuration}
 * artefacts (backlog {@code asa_fortigate_policy_model}; {@code PO_DECISION_RECORD_2026_11_14_ASA_FORTIGATE_POLICY_MODEL.md}
 * items 1-3; {@code PATH_ANALYSIS_VENDOR_SEMANTICS_AMENDMENT_2026_11_14_ASA_FORTIGATE.md} A0-FG-1..27).
 *
 * <p>Strictly read-only ({@code conn.setReadOnly(true)}, {@code SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY}),
 * zero network-device contact, zero stdout/stderr output in live mode (writing only to {@code <workDir>/evaluation.out}).
 * Every emitted token matches {@code [a-z0-9_.]+}: devices are {@code fgt_01}, {@code fgt_02}, ..., VDOMs within each
 * device are {@code fgt_01.v01}, {@code fgt_01.v02}, ..., and all reason/verdict/status tokens come from closed sets.
 * Raw configuration text, device IDs, hostnames, VDOM names, rule names, VIP names, interface names, and IP addresses
 * never leave memory.
 */
public final class FortiGateEvaluationMeasure {

    static final Pattern SAFE = Pattern.compile("[a-z0-9_.]+");
    static final Pattern SAFE_LINE = Pattern.compile("^[a-z0-9_.]+(\t[a-z0-9_.]+)*$");

    private static final String MAPPED_AT = "2026-11-14T00:00:00Z";
    private static final Instant HYGIENE_NOW = Instant.parse("2026-11-14T12:00:00Z");
    private static final int HYGIENE_DAYS = 90;
    private static final int LOOKUP_PREPARE_BUDGET = 250_000;
    private static final int MAX_QUERIES_PER_VDOM = 200;
    private static final int MAX_VIP_QUERIES_PER_VDOM = 100;
    private static final String ANY_SENTINEL = "0.0.0.0/0";
    private static final String FALLBACK_SRC_IP = "192.0.2.5";
    private static final String FALLBACK_DST_IP = "198.51.100.25";
    private static final String FALLBACK_SERVICE = "tcp/443";

    private static final Set<String> KNOWN_SHADOW_STATUSES =
            Set.of("NOT_SHADOWED", "REDUNDANT", "CONFLICT", "UNKNOWN");
    private static final Set<String> KNOWN_VERDICTS =
            Set.of("ALLOWED", "DENIED", "UNKNOWN", "PARTIAL");
    private static final Set<String> KNOWN_FAILURE_REASONS = Set.of(
            "FGT_UNSUPPORTED_MODE",
            "FGT_DEFAULTS_UNPROVEN",
            "VENDOR_NAME_NOT_UNIQUE",
            "POLICY_HITS_UNAVAILABLE",
            "POLICY_DB_INVENTORY_WRITE_FAILED",
            "COLLECTION_PENDING");

    static PrintWriter out;

    private FortiGateEvaluationMeasure() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--selftest".equals(args[0])) {
            Path optionalOut = args.length > 1 ? Path.of(args[1]) : null;
            String failure = selfTest(optionalOut);
            if (failure != null) {
                System.out.println("selftest\tfail\t" + failure);
                System.exit(1);
                return;
            }
            System.out.println("selftest\tok");
            System.exit(0);
            return;
        }
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        silence();
        Path workDir = Path.of(args.length > 0 ? args[0] : ".");
        out = new PrintWriter(Files.newBufferedWriter(workDir.resolve("evaluation.out"), StandardCharsets.UTF_8), true);
        try {
            runLive();
            out.println("done");
        } catch (Throwable t) {
            out.println("fatal\t" + slug(t.getClass().getSimpleName()));
        } finally {
            out.close();
        }
    }

    static void silence() {
        try {
            java.util.logging.LogManager.getLogManager().reset();
        } catch (Throwable ignored) {
            // best effort: library logging must not reach stdout/stderr
        }
        try {
            Object root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            Class<?> level = Class.forName("ch.qos.logback.classic.Level");
            root.getClass().getMethod("setLevel", level).invoke(root, level.getField("OFF").get(null));
        } catch (Throwable ignored) {
            // best effort
        }
    }

    // ---- Live DB + artefact store read-only pass ----------------------------------------------------------------------

    private record DeviceRow(
            String deviceId,
            String softwareVersion,
            String artefactRef,
            byte[] wrappedDataKey,
            boolean gzip) {}

    static void runLive() throws Exception {
        Properties props = new Properties();
        props.setProperty(
                "user",
                SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_USER_FILE")), "db_user").strip());
        props.setProperty(
                "password",
                SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_PASSWORD_FILE")), "db_password").strip());
        props.setProperty("ApplicationName", "fgt-eval-measure-readonly");

        List<DeviceRow> rows = new ArrayList<>();
        try (Connection conn = new org.postgresql.Driver().connect(System.getenv("UI2_DB_URL"), props)) {
            conn.setAutoCommit(false);
            conn.setReadOnly(true);
            try (Statement st = conn.createStatement()) {
                st.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
                st.execute("SET statement_timeout = '120s'");
            }
            String sql = "select distinct on (d.device_id) d.device_id, d.observed_software_version, "
                    + "r.artefact_ref, a.wrapped_data_key, c.compression "
                    + "from devices d "
                    + "join device_configuration_run r on r.device_id = d.device_id and r.read_kind = 'show_configuration' "
                    + "join configuration_artefact c on c.artefact_ref = r.artefact_ref "
                    + "join backup_artefact a on a.recovery_volume_path = r.artefact_ref and a.device_id = d.device_id "
                    + "where d.vendor_hint = 'fortinet' and d.role in ('gateway', 'firewall') and not d.disabled "
                    + "and d.enrollment_state in ('ENROLLED', 'DEGRADED') "
                    + "order by d.device_id, r.collected_at desc";
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new DeviceRow(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getBytes(4),
                            "gzip".equals(rs.getString(5))));
                }
            }
            conn.rollback();
        }
        rows.sort(Comparator.comparing(DeviceRow::deviceId));

        ArtefactStore store = new FileArtefactStore(
                Path.of(System.getenv("UI2_ARTEFACT_STORE_ROOT")),
                ArtefactStoreCipher.fromBase64Key(
                        SecretFile.readRequired(Path.of(System.getenv("UI2_ARTEFACT_STORE_KEY_FILE")), "artefact_store_key")));

        List<ConfigSupplier> suppliers = new ArrayList<>(rows.size());
        for (DeviceRow row : rows) {
            suppliers.add(() -> {
                try (InputStream in = store.retrieve(new ArtefactRef(row.artefactRef()), row.wrappedDataKey(), row.gzip())) {
                    String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    FortiGatePolicyConfig parsed = FortiGateConfigPolicyParser.parse(raw, row.softwareVersion());
                    return parsed;
                }
            });
        }
        evaluateFleet(suppliers);
    }

    @FunctionalInterface
    interface ConfigSupplier {
        FortiGatePolicyConfig load() throws Exception;
    }

    static void evaluateFleet(List<ConfigSupplier> suppliers) {
        gc();
        long heap0 = usedHeap();
        List<PolicySnapshot> retainedSnapshots = new ArrayList<>();
        Map<String, Long> fleetTotals = new TreeMap<>();
        long totalDevices = 0;
        long totalVdoms = 0;

        for (ConfigSupplier supplier : suppliers) {
            totalDevices++;
            String devOrd = String.format(Locale.ROOT, "fgt_%02d", totalDevices);
            FortiGatePolicyConfig parsed;
            try {
                parsed = supplier.load();
            } catch (Throwable e) {
                String errKey = "error." + slug(e.getClass().getSimpleName());
                emit(devOrd, "vdoms", 0L);
                emit(devOrd, errKey, 1L);
                inc(fleetTotals, errKey);
                continue;
            }

            List<PolicySnapshot> snapshots;
            List<VdomConfig> vdomConfigs;
            try {
                snapshots = FortiGatePolicyMapper.map("dev", "masked", MAPPED_AT, "artefact", parsed);
                vdomConfigs = new ArrayList<>(parsed.vdoms().values());
            } catch (Throwable e) {
                String errKey = "error." + slug(e.getClass().getSimpleName());
                emit(devOrd, "vdoms", 0L);
                emit(devOrd, errKey, 1L);
                inc(fleetTotals, errKey);
                continue;
            }
            parsed = null;

            emit(devOrd, "vdoms", snapshots.size());
            totalVdoms += snapshots.size();
            retainedSnapshots.addAll(snapshots);

            for (int vi = 0; vi < snapshots.size(); vi++) {
                String vdomOrd = String.format(Locale.ROOT, "%s.v%02d", devOrd, vi + 1);
                PolicySnapshot snapshot = snapshots.get(vi);
                VdomConfig vdomCfg = vi < vdomConfigs.size() ? vdomConfigs.get(vi) : null;
                Map<String, Long> vc = evaluateVdom(snapshot, vdomCfg);
                for (Map.Entry<String, Long> entry : vc.entrySet()) {
                    emit(vdomOrd, entry.getKey(), entry.getValue());
                    if (!"vdoms".equals(entry.getKey())) {
                        fleetTotals.merge(entry.getKey(), entry.getValue(), Long::sum);
                    }
                }
            }
            vdomConfigs = null;
        }

        gc();
        long heap1 = usedHeap();
        long retainedMb = Math.max(0L, (heap1 - heap0) / (1024L * 1024L));
        if (retainedSnapshots.size() < 0) {
            // keep retainedSnapshots live across the heap1 measurement
            emit("summary", "devices", 0L);
        }

        emit("summary", "devices", totalDevices);
        emit("summary", "vdoms", totalVdoms);
        emit("summary", "heap_retained_mb", retainedMb);
        for (Map.Entry<String, Long> entry : fleetTotals.entrySet()) {
            emit("summary", entry.getKey(), entry.getValue());
        }
    }

    // ---- Per-VDOM hygiene + general lookup + VIP-targeted lookup ------------------------------------------------------

    static Map<String, Long> evaluateVdom(PolicySnapshot snapshot, VdomConfig vdomCfg) {
        Map<String, Long> c = new TreeMap<>();
        c.put("vdoms", 1L);
        c.put("rules", (long) snapshot.ruleCount());
        long securityRules = 0;
        for (Section sec : snapshot.sections()) {
            if (!PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), sec)) {
                securityRules += sec.rules().size();
            }
        }
        c.put("security_rules", securityRules);
        c.put("objects", (long) snapshot.objects().size());
        c.put("vips", vdomCfg == null ? 0L : (long) vdomCfg.vips().size());
        c.put("failures", (long) snapshot.failures().size());
        for (CollectionFailure f : snapshot.failures()) {
            String fSlug = f.reason() != null && KNOWN_FAILURE_REASONS.contains(f.reason()) ? slug(f.reason()) : "other";
            inc(c, "failure." + fSlug);
        }

        try {
            long h0 = System.nanoTime();
            PolicyHygiene.Result hygiene =
                    PolicyHygiene.analyze(snapshot, HYGIENE_DAYS, HYGIENE_NOW, PolicyHygiene.TOTAL_WORK);
            c.put("hygiene_ms", Math.max(0L, (System.nanoTime() - h0) / 1_000_000L));
            c.put("hygiene_budget_reached", hygiene.budgetReached() ? 1L : 0L);
            for (PolicyHygiene.Assessment a : hygiene.rules().values()) {
                String st = a.shadowStatus() != null && KNOWN_SHADOW_STATUSES.contains(a.shadowStatus())
                        ? slug(a.shadowStatus())
                        : "other";
                inc(c, "shadow." + st);
                if (!"NOT_SHADOWED".equals(a.shadowStatus())
                        && !"REDUNDANT".equals(a.shadowStatus())
                        && !"CONFLICT".equals(a.shadowStatus())) {
                    String rSlug = a.shadowReason() != null && PolicyHygiene.REASONS.contains(a.shadowReason())
                            ? slug(a.shadowReason())
                            : "other";
                    inc(c, "shadow_reason." + rSlug);
                }
                for (PolicyHygiene.Finding f : a.findings()) {
                    String cSlug = f.findingClass() != null && PolicyHygiene.CLASSES.contains(f.findingClass())
                            ? slug(f.findingClass())
                            : "other";
                    inc(c, "finding." + cSlug);
                }
            }

            Random random = new Random(7);
            long p0 = System.nanoTime();
            PolicyLookup.Prepared prepared = PolicyLookup.prepare(snapshot, LOOKUP_PREPARE_BUDGET);
            long prepNs = Math.max(0L, System.nanoTime() - p0);

            List<String> vdomIfs = collectVdomInterfaces(snapshot, vdomCfg);
            List<PolicyLookup.Query> vipQueries =
                    buildVipQueries(snapshot, vdomCfg, vdomIfs, random, MAX_VIP_QUERIES_PER_VDOM);
            int generalCap = Math.max(0, MAX_QUERIES_PER_VDOM - vipQueries.size());
            List<PolicyLookup.Query> generalQueries =
                    buildGeneralQueries(snapshot, vdomIfs, random, generalCap);

            long g0 = System.nanoTime();
            for (PolicyLookup.Query q : generalQueries) {
                Map<String, Object> body = prepared.evaluate(q).body();
                recordLookupOutcome(c, body, "verdict.", "unknown_reason.");
            }
            long generalNs = Math.max(0L, System.nanoTime() - g0);
            c.put("lookup_queries", (long) generalQueries.size());
            c.put("lookup_ms", Math.max(0L, (prepNs + generalNs) / 1_000_000L));

            long v0 = System.nanoTime();
            for (PolicyLookup.Query q : vipQueries) {
                Map<String, Object> body = prepared.evaluate(q).body();
                recordLookupOutcome(c, body, "vip_verdict.", "vip_unknown_reason.");
            }
            long vipNs = Math.max(0L, System.nanoTime() - v0);
            c.put("vip_queries", (long) vipQueries.size());
            c.put("vip_ms", Math.max(0L, vipNs / 1_000_000L));
        } catch (Throwable e) {
            inc(c, "error." + slug(e.getClass().getSimpleName()));
        }
        return c;
    }

    private static void recordLookupOutcome(
            Map<String, Long> c, Map<String, Object> body, String verdictPrefix, String unknownReasonPrefix) {
        String rawVerdict = String.valueOf(body.get("verdict"));
        String vSlug = KNOWN_VERDICTS.contains(rawVerdict) ? slug(rawVerdict) : "other";
        inc(c, verdictPrefix + vSlug);
        if ("UNKNOWN".equals(rawVerdict)) {
            inc(c, unknownReasonPrefix + classifyUnknownReason(body));
        }
    }

    static String classifyUnknownReason(Map<String, Object> body) {
        Object rawReason = body.get("lookupReason");
        String top = rawReason == null ? "" : String.valueOf(rawReason);
        if ("earlier rule could match".equals(top)) {
            Object undec = body.get("undecidableRules");
            if (undec instanceof List<?> list && !list.isEmpty()) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m && "FGT_MATCH_VIP_UNPROVEN".equals(m.get("lookupReason"))) {
                        return "fgt_match_vip_unproven";
                    }
                }
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m && "FGT_VIP_PRIORITY_UNPROVEN".equals(m.get("lookupReason"))) {
                        return "fgt_vip_priority_unproven";
                    }
                }
                Object first = list.get(0);
                if (first instanceof Map<?, ?> m && m.get("lookupReason") != null) {
                    return closedLookupReasonSlug(String.valueOf(m.get("lookupReason")));
                }
            }
        }
        if ("partial query coverage".equals(top)) {
            Object parts = body.get("parts");
            if (parts instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> part && "UNKNOWN".equals(String.valueOf(part.get("verdict")))) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> sub = (Map<String, Object>) part;
                        return classifyUnknownReason(sub);
                    }
                }
            }
        }
        return closedLookupReasonSlug(top);
    }

    private static String closedLookupReasonSlug(String reason) {
        if (reason != null && (PolicyLookup.REASONS.contains(reason) || PolicyHygiene.REASONS.contains(reason))) {
            return slug(reason);
        }
        return "other";
    }

    // ---- Query synthesis (in memory only; never emitted) --------------------------------------------------------------

    private record RuleSample(Rule rule, String srcIp, String dstIp, String service, String inIf, String outIf) {}

    private static List<String> collectVdomInterfaces(PolicySnapshot snapshot, VdomConfig vdomCfg) {
        Set<String> ifs = new LinkedHashSet<>();
        if (vdomCfg != null) {
            for (String name : vdomCfg.interfaces().keySet()) {
                if (isQuerySafeZone(name) && !"any".equals(name)) {
                    ifs.add(name);
                }
            }
        }
        for (PolicyObject o : snapshot.objects().values()) {
            if ("fgt-interfaces".equals(o.type())) {
                for (String val : o.values()) {
                    if (val.startsWith("interface: ")) {
                        String name = val.substring("interface: ".length());
                        if (isQuerySafeZone(name) && !"any".equals(name)) {
                            ifs.add(name);
                        }
                    }
                }
            }
        }
        for (Section sec : snapshot.sections()) {
            if (PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), sec)) continue;
            for (Rule r : sec.rules()) {
                for (String key : List.of("from", "to")) {
                    for (String z : r.extras().getOrDefault(key, List.of())) {
                        if (isQuerySafeZone(z) && !"any".equals(z)) {
                            ifs.add(z);
                        }
                    }
                }
            }
        }
        return new ArrayList<>(ifs);
    }

    private static List<PolicyLookup.Query> buildGeneralQueries(
            PolicySnapshot snapshot, List<String> vdomIfs, Random random, int cap) {
        if (cap <= 0 || !snapshot.failures().isEmpty()) {
            return List.of();
        }
        Set<PolicyLookup.Query> candidates = new LinkedHashSet<>();
        for (Section sec : snapshot.sections()) {
            if (PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), sec)) continue;
            for (Rule rule : sec.rules()) {
                if (Boolean.FALSE.equals(rule.enabled())) continue;
                List<String> srcs = sampleIpsFromCell(snapshot, rule.source(), FALLBACK_SRC_IP, 2);
                List<String> dsts = sampleIpsFromCell(snapshot, rule.destination(), FALLBACK_DST_IP, 2);
                List<String> svcs = sampleServicesFromCell(snapshot, rule.service(), 2);
                if (srcs.isEmpty() || dsts.isEmpty() || svcs.isEmpty()) continue;

                String inIf = chooseInterface(rule.extras().getOrDefault("from", List.of()), vdomIfs, null);
                String outIf = chooseInterface(rule.extras().getOrDefault("to", List.of()), vdomIfs, inIf);
                for (String src : srcs) {
                    for (String dst : dsts) {
                        for (String svc : svcs) {
                            addQuery(candidates, src, dst, svc, inIf, outIf);
                        }
                    }
                }
                if (vdomIfs.size() >= 2) {
                    String altIn = vdomIfs.get(random.nextInt(vdomIfs.size()));
                    String altOut = chooseDifferentInterface(vdomIfs, altIn);
                    addQuery(candidates, srcs.get(0), dsts.get(0), svcs.get(0), altIn, altOut);
                }
            }
        }
        return sampleBounded(candidates, random, cap);
    }

    private static List<PolicyLookup.Query> buildVipQueries(
            PolicySnapshot snapshot, VdomConfig vdomCfg, List<String> vdomIfs, Random random, int cap) {
        if (cap <= 0 || vdomCfg == null || vdomCfg.vips().isEmpty()) {
            return List.of();
        }
        List<RuleSample> ruleSamples = collectRuleSamples(snapshot, vdomIfs);
        Map<String, Set<String>> vipRefTokensByVip = buildVipReferenceTokens(vdomCfg);
        Set<PolicyLookup.Query> candidates = new LinkedHashSet<>();

        for (VipObject vip : vdomCfg.vips().values()) {
            List<String> extIps = sampleIpsFromVipSpec(vip.extip());
            List<String> mappedIps = new ArrayList<>();
            for (String mip : vip.mappedip()) {
                for (String parsedIp : sampleIpsFromVipSpec(mip)) {
                    if (!mappedIps.contains(parsedIp)) {
                        mappedIps.add(parsedIp);
                    }
                }
            }
            if (extIps.isEmpty() && mappedIps.isEmpty()) continue;

            String vipInIf = vip.extintf() != null && !vip.extintf().isEmpty() && !"any".equals(vip.extintf()) && isQuerySafeZone(vip.extintf())
                    ? vip.extintf()
                    : null;
            String extSvc = vipPortService(vip.portforward(), vip.protocol(), vip.extport());
            String mappedSvc = vipPortService(vip.portforward(), vip.protocol(), vip.mappedport());
            if (mappedSvc == null) {
                mappedSvc = extSvc;
            }

            Set<String> refTokens = vipRefTokensByVip.getOrDefault(vip.name(), Set.of(vip.name()));
            boolean matchedAnyRule = false;

            for (RuleSample rs : ruleSamples) {
                boolean referencesVip = rs.rule().extras().getOrDefault("fgt-vip", List.of()).stream().anyMatch(refTokens::contains);
                boolean coversExt = false;
                for (String eip : extIps) {
                    Long ipLong = ipv4ToLong(eip);
                    if (ipLong != null && cellCoversIp(snapshot, rs.rule().destination(), ipLong, new HashSet<>(), 0)) {
                        coversExt = true;
                        break;
                    }
                }
                boolean coversMapped = false;
                for (String mip : mappedIps) {
                    Long ipLong = ipv4ToLong(mip);
                    if (ipLong != null && cellCoversIp(snapshot, rs.rule().destination(), ipLong, new HashSet<>(), 0)) {
                        coversMapped = true;
                        break;
                    }
                }
                if (!referencesVip && !coversExt && !coversMapped) continue;

                matchedAnyRule = true;
                String inIf = vipInIf != null ? vipInIf : rs.inIf();
                String outIf = rs.outIf().isEmpty() ? inferEgressInterface(mappedIps, vdomCfg, inIf, vdomIfs) : rs.outIf();
                String svcForExt = extSvc != null ? extSvc : rs.service();
                String svcForMapped = mappedSvc != null ? mappedSvc : rs.service();

                for (String eip : extIps) {
                    addQuery(candidates, rs.srcIp(), eip, svcForExt, inIf, outIf);
                }
                for (String mip : mappedIps) {
                    addQuery(candidates, rs.srcIp(), mip, svcForMapped, inIf, outIf);
                }
            }

            if (!matchedAnyRule) {
                String inIf = vipInIf != null ? vipInIf : (vdomIfs.isEmpty() ? "" : vdomIfs.get(0));
                String outIf = inferEgressInterface(mappedIps, vdomCfg, inIf, vdomIfs);
                String svcForExt = extSvc != null ? extSvc : FALLBACK_SERVICE;
                String svcForMapped = mappedSvc != null ? mappedSvc : FALLBACK_SERVICE;
                for (String eip : extIps) {
                    addQuery(candidates, FALLBACK_SRC_IP, eip, svcForExt, inIf, outIf);
                }
                for (String mip : mappedIps) {
                    addQuery(candidates, FALLBACK_SRC_IP, mip, svcForMapped, inIf, outIf);
                }
            }
        }
        return sampleBounded(candidates, random, cap);
    }

    private static List<RuleSample> collectRuleSamples(PolicySnapshot snapshot, List<String> vdomIfs) {
        List<RuleSample> outList = new ArrayList<>();
        for (Section sec : snapshot.sections()) {
            if (PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), sec)) continue;
            for (Rule r : sec.rules()) {
                if (Boolean.FALSE.equals(r.enabled())) continue;
                List<String> srcs = sampleIpsFromCell(snapshot, r.source(), FALLBACK_SRC_IP, 1);
                List<String> dsts = sampleIpsFromCell(snapshot, r.destination(), FALLBACK_DST_IP, 1);
                List<String> svcs = sampleServicesFromCell(snapshot, r.service(), 1);
                String src = srcs.isEmpty() ? FALLBACK_SRC_IP : srcs.get(0);
                String dst = dsts.isEmpty() ? FALLBACK_DST_IP : dsts.get(0);
                String svc = svcs.isEmpty() ? FALLBACK_SERVICE : svcs.get(0);
                String inIf = chooseInterface(r.extras().getOrDefault("from", List.of()), vdomIfs, null);
                String outIf = chooseInterface(r.extras().getOrDefault("to", List.of()), vdomIfs, inIf);
                outList.add(new RuleSample(r, src, dst, svc, inIf, outIf));
            }
        }
        return outList;
    }

    private static Map<String, Set<String>> buildVipReferenceTokens(VdomConfig vdomCfg) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (String vipName : vdomCfg.vips().keySet()) {
            Set<String> tokens = new LinkedHashSet<>();
            tokens.add(vipName);
            for (VipGroup vg : vdomCfg.vipGroups().values()) {
                if (vg.members().contains(vipName)) {
                    tokens.add(vg.name());
                }
            }
            map.put(vipName, tokens);
        }
        return map;
    }

    private static String inferEgressInterface(
            List<String> mappedIps, VdomConfig vdomCfg, String inIf, List<String> vdomIfs) {
        if (vdomCfg != null) {
            for (String mip : mappedIps) {
                Long target = ipv4ToLong(mip);
                if (target == null) continue;
                for (Interface iface : vdomCfg.interfaces().values()) {
                    if (iface.ip() == null || iface.mask() == null || !isQuerySafeZone(iface.name())) continue;
                    Long ifIp = ipv4ToLong(iface.ip());
                    Integer prefix = contiguousMaskPrefix(iface.mask());
                    if (ifIp == null || prefix == null || prefix == 0) continue;
                    long mask = maskForPrefix(prefix);
                    if ((ifIp & mask) == (target & mask)) {
                        return iface.name();
                    }
                }
            }
        }
        return chooseDifferentInterface(vdomIfs, inIf);
    }

    private static String vipPortService(String portforward, String protocol, String rawPort) {
        if (!"enable".equals(portforward) || rawPort == null || rawPort.isEmpty()) {
            return null;
        }
        String proto = (protocol == null || protocol.isEmpty()) ? "tcp" : protocol.toLowerCase(Locale.ROOT);
        if (!"tcp".equals(proto) && !"udp".equals(proto)) {
            return null;
        }
        Integer port = firstPortFromSpec(rawPort);
        return port == null ? null : (proto + "/" + port);
    }

    private static List<PolicyLookup.Query> sampleBounded(
            Set<PolicyLookup.Query> candidates, Random random, int cap) {
        if (candidates.isEmpty() || cap <= 0) {
            return List.of();
        }
        List<PolicyLookup.Query> list = new ArrayList<>(candidates);
        if (list.size() <= cap) {
            return list;
        }
        Collections.shuffle(list, random);
        return new ArrayList<>(list.subList(0, cap));
    }

    private static void addQuery(
            Set<PolicyLookup.Query> set, String src, String dst, String svc, String inIf, String outIf) {
        if (!isValidIpv4(src) || !isValidIpv4(dst) || svc == null || !isQuerySafeZone(inIf) || !isQuerySafeZone(outIf)) {
            return;
        }
        try {
            set.add(new PolicyLookup.Query(src, dst, svc, inIf, outIf, null, null));
        } catch (IllegalArgumentException ignored) {
            // reject malformed candidate silently
        }
    }

    private static String chooseInterface(List<String> ruleZones, List<String> vdomIfs, String avoid) {
        List<String> concrete = new ArrayList<>();
        for (String z : ruleZones) {
            if (isQuerySafeZone(z) && !"any".equals(z)) {
                concrete.add(z);
            }
        }
        if (!concrete.isEmpty()) {
            if (avoid != null) {
                for (String z : concrete) {
                    if (!z.equals(avoid)) return z;
                }
            }
            return concrete.get(0);
        }
        return chooseDifferentInterface(vdomIfs, avoid);
    }

    private static String chooseDifferentInterface(List<String> vdomIfs, String avoid) {
        if (vdomIfs.isEmpty()) return "";
        if (avoid != null) {
            for (String z : vdomIfs) {
                if (!z.equals(avoid)) return z;
            }
        }
        return vdomIfs.get(0);
    }

    private static boolean isQuerySafeZone(String z) {
        return z != null && z.length() <= 200 && z.chars().noneMatch(Character::isISOControl);
    }

    private static List<String> sampleIpsFromCell(
            PolicySnapshot snapshot, Cell cell, String anyFallback, int limit) {
        if (cell == null || cell.negated() || cell.refs().isEmpty() || limit <= 0) {
            return List.of();
        }
        Set<String> raw = new LinkedHashSet<>();
        for (String ref : cell.refs()) {
            collectAddressSamples(snapshot, ref, new HashSet<>(), 0, raw, limit + 2);
            if (raw.size() >= limit + 2) break;
        }
        List<String> resolved = new ArrayList<>(limit);
        boolean sawAny = false;
        for (String item : raw) {
            if (ANY_SENTINEL.equals(item)) {
                sawAny = true;
            } else if (isValidIpv4(item) && !resolved.contains(item)) {
                resolved.add(item);
                if (resolved.size() >= limit) return resolved;
            }
        }
        if (resolved.isEmpty() && sawAny) {
            resolved.add(anyFallback);
        }
        return resolved;
    }

    private static void collectAddressSamples(
            PolicySnapshot snapshot, String ref, Set<String> visited, int depth, Set<String> outSet, int cap) {
        if (depth > 32 || outSet.size() >= cap || !visited.add(ref)) return;
        PolicyObject o = snapshot.objects().get(ref);
        if (o == null || !"RESOLVED".equals(o.status())) return;
        if ("any".equals(o.type())) {
            outSet.add(ANY_SENTINEL);
            return;
        }
        for (String member : o.members()) {
            collectAddressSamples(snapshot, member, visited, depth + 1, outSet, cap);
            if (outSet.size() >= cap) return;
        }
        for (String v : o.values()) {
            if (v.startsWith("ip-netmask: ")) {
                String cidr = v.substring("ip-netmask: ".length()).strip();
                int slash = cidr.indexOf('/');
                if (slash > 0) {
                    String ip = cidr.substring(0, slash);
                    Integer prefix = parsePrefix(cidr.substring(slash + 1));
                    Long ipLong = ipv4ToLong(ip);
                    if (ipLong != null && prefix != null) {
                        if (prefix == 0) {
                            outSet.add(ANY_SENTINEL);
                        } else if (prefix == 32) {
                            outSet.add(ip);
                        } else {
                            long net = ipLong & maskForPrefix(prefix);
                            outSet.add(formatIpv4(prefix <= 30 ? (net + 1L) : net));
                        }
                    }
                }
            } else if (v.startsWith("ip-range: ")) {
                String range = v.substring("ip-range: ".length()).strip();
                int dash = range.indexOf('-');
                if (dash > 0) {
                    String startIp = range.substring(0, dash).strip();
                    if (isValidIpv4(startIp)) {
                        outSet.add(startIp);
                    }
                }
            }
            if (outSet.size() >= cap) return;
        }
    }

    private static boolean cellCoversIp(
            PolicySnapshot snapshot, Cell cell, long targetIp, Set<String> visited, int depth) {
        if (cell == null || cell.negated() || depth > 32) return false;
        for (String ref : cell.refs()) {
            if (objectCoversIp(snapshot, ref, targetIp, visited, depth)) return true;
        }
        return false;
    }

    private static boolean objectCoversIp(
            PolicySnapshot snapshot, String ref, long targetIp, Set<String> visited, int depth) {
        if (depth > 32 || !visited.add(ref)) return false;
        PolicyObject o = snapshot.objects().get(ref);
        if (o == null || !"RESOLVED".equals(o.status())) return false;
        if ("any".equals(o.type())) return true;
        for (String member : o.members()) {
            if (objectCoversIp(snapshot, member, targetIp, visited, depth + 1)) return true;
        }
        for (String v : o.values()) {
            if (v.startsWith("ip-netmask: ")) {
                String cidr = v.substring("ip-netmask: ".length()).strip();
                int slash = cidr.indexOf('/');
                if (slash > 0) {
                    Long ipLong = ipv4ToLong(cidr.substring(0, slash));
                    Integer prefix = parsePrefix(cidr.substring(slash + 1));
                    if (ipLong != null && prefix != null) {
                        long mask = maskForPrefix(prefix);
                        long start = ipLong & mask;
                        long end = start | (~mask & 0xFFFFFFFFL);
                        if (targetIp >= start && targetIp <= end) return true;
                    }
                }
            } else if (v.startsWith("ip-range: ")) {
                String range = v.substring("ip-range: ".length()).strip();
                int dash = range.indexOf('-');
                if (dash > 0) {
                    Long s = ipv4ToLong(range.substring(0, dash).strip());
                    Long e = ipv4ToLong(range.substring(dash + 1).strip());
                    if (s != null && e != null && targetIp >= s && targetIp <= e) return true;
                }
            }
        }
        return false;
    }

    private static List<String> sampleIpsFromVipSpec(String spec) {
        if (spec == null || spec.isBlank()) return List.of();
        String s = spec.strip();
        int dash = s.indexOf('-');
        if (dash < 0) {
            int slash = s.indexOf('/');
            String base = slash > 0 ? s.substring(0, slash) : s;
            return isValidIpv4(base) ? List.of(base) : List.of();
        }
        String start = s.substring(0, dash).strip();
        String end = s.substring(dash + 1).strip();
        List<String> ips = new ArrayList<>(2);
        if (isValidIpv4(start)) ips.add(start);
        if (isValidIpv4(end) && !end.equals(start)) ips.add(end);
        return ips;
    }

    private static List<String> sampleServicesFromCell(PolicySnapshot snapshot, Cell cell, int limit) {
        if (cell == null || cell.negated() || cell.refs().isEmpty() || limit <= 0) {
            return List.of();
        }
        Set<String> svcs = new LinkedHashSet<>();
        for (String ref : cell.refs()) {
            collectServiceSamples(snapshot, ref, new HashSet<>(), 0, svcs, limit);
            if (svcs.size() >= limit) break;
        }
        return new ArrayList<>(svcs);
    }

    private static void collectServiceSamples(
            PolicySnapshot snapshot, String ref, Set<String> visited, int depth, Set<String> outSet, int limit) {
        if (depth > 32 || outSet.size() >= limit || !visited.add(ref)) return;
        PolicyObject o = snapshot.objects().get(ref);
        if (o == null || !"RESOLVED".equals(o.status())) return;
        if ("any".equals(o.type())) {
            outSet.add("tcp/443");
            if (outSet.size() < limit) outSet.add("udp/53");
            return;
        }
        for (String member : o.members()) {
            collectServiceSamples(snapshot, member, visited, depth + 1, outSet, limit);
            if (outSet.size() >= limit) return;
        }
        for (String v : o.values()) {
            if (v.startsWith("protocol/tcp/port: ")) {
                addParsedPorts(v.substring("protocol/tcp/port: ".length()), "tcp", outSet, limit);
            } else if (v.startsWith("protocol/udp/port: ")) {
                addParsedPorts(v.substring("protocol/udp/port: ".length()), "udp", outSet, limit);
            }
            if (outSet.size() >= limit) return;
        }
    }

    private static void addParsedPorts(String spec, String proto, Set<String> outSet, int limit) {
        for (String part : spec.split(",")) {
            Integer port = firstPortFromSpec(part.strip());
            if (port != null) {
                outSet.add(proto + "/" + port);
                if (outSet.size() >= limit) return;
            }
        }
    }

    private static Integer firstPortFromSpec(String token) {
        if (token == null || token.isEmpty()) return null;
        String base = token;
        int colon = base.indexOf(':');
        if (colon >= 0) base = base.substring(0, colon);
        int dash = base.indexOf('-');
        if (dash >= 0) base = base.substring(0, dash);
        if (base.isEmpty() || base.length() > 5) return null;
        for (int i = 0; i < base.length(); i++) {
            char ch = base.charAt(i);
            if (ch < '0' || ch > '9') return null;
        }
        int p = Integer.parseInt(base);
        return (p >= 0 && p <= 65535) ? p : null;
    }

    // ---- IPv4 + formatting + output safety helpers --------------------------------------------------------------------

    private static boolean isValidIpv4(String ip) {
        return ipv4ToLong(ip) != null;
    }

    private static Long ipv4ToLong(String ip) {
        if (ip == null || ip.length() < 7 || ip.length() > 15) return null;
        int dots = 0;
        int cur = 0;
        int digits = 0;
        long acc = 0;
        for (int i = 0; i < ip.length(); i++) {
            char ch = ip.charAt(i);
            if (ch == '.') {
                if (digits == 0 || cur > 255) return null;
                acc = (acc << 8) | cur;
                dots++;
                cur = 0;
                digits = 0;
            } else if (ch >= '0' && ch <= '9') {
                if (digits > 0 && cur == 0) return null;
                cur = cur * 10 + (ch - '0');
                digits++;
                if (digits > 3) return null;
            } else {
                return null;
            }
        }
        if (dots != 3 || digits == 0 || cur > 255) return null;
        return (acc << 8) | cur;
    }

    private static Integer parsePrefix(String s) {
        if (s == null || s.isEmpty() || s.length() > 2) return null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return null;
        }
        int p = Integer.parseInt(s);
        return (p >= 0 && p <= 32) ? p : null;
    }

    private static Integer contiguousMaskPrefix(String mask) {
        Long v = ipv4ToLong(mask);
        if (v == null) return null;
        long inv = (~v) & 0xFFFFFFFFL;
        if (((inv + 1L) & inv) != 0L) return null;
        return Long.bitCount(v);
    }

    private static long maskForPrefix(int prefix) {
        return prefix <= 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
    }

    private static String formatIpv4(long value) {
        return ((value >>> 24) & 0xFF)
                + "." + ((value >>> 16) & 0xFF)
                + "." + ((value >>> 8) & 0xFF)
                + "." + (value & 0xFF);
    }

    private static void gc() {
        System.gc();
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.gc();
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    static String slug(String s) {
        if (s == null) return "null";
        String x = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (x.isEmpty()) return "other";
        if (x.length() > 60) {
            x = x.substring(0, 60).replaceAll("_+$", "");
        }
        return !x.isEmpty() && SAFE.matcher(x).matches() ? x : "other";
    }

    static void inc(Map<String, Long> c, String k) {
        c.merge(k, 1L, Long::sum);
    }

    static void emit(String subject, String key, long value) {
        long clamped = Math.max(0L, value);
        String line = subject + "\t" + key + "\t" + clamped;
        for (String f : line.split("\t", -1)) {
            if (!SAFE.matcher(f).matches()) {
                out.println("unsafe_field_dropped");
                return;
            }
        }
        out.println(line);
    }

    // ---- Offline --selftest mode (zero DB, zero secret files, zero pod/SSH) -------------------------------------------

    private static final String SYNTH_CONFIG_TRAD_VIP = """
            #config-version=FGVM64-7.4.8-FW-build2795-250521:opmode=0:vdom=0:user=admin
            config system global
                set hostname "FW-TANGO-04-CANARY"
            end
            config system interface
                edit "port1-canary"
                    set vdom "root"
                    set ip 192.0.2.1 255.255.255.0
                next
                edit "port2-canary"
                    set vdom "root"
                    set ip 203.0.113.1 255.255.255.0
                next
            end
            config firewall address
                edit "all"
                next
                edit "SYNTH-CLIENT-CANARY"
                    set subnet 192.0.2.0 255.255.255.0
                next
                edit "SYNTH-EXT-HOST-CANARY"
                    set subnet 198.51.100.20 255.255.255.255
                next
                edit "SYNTH-SSH-HOST-CANARY"
                    set subnet 203.0.113.50 255.255.255.255
                next
            end
            config firewall vip
                edit "SYNTH-VIP-CANARY"
                    set extip 198.51.100.20
                    set mappedip "203.0.113.10"
                    set extintf "port1-canary"
                next
            end
            config firewall service custom
                edit "HTTPS"
                    set tcp-portrange 443
                next
                edit "SSH"
                    set tcp-portrange 22
                next
            end
            config firewall policy
                edit 1
                    set name "SYNTH-SSH-ALLOW-CANARY"
                    set srcintf "port1-canary"
                    set dstintf "port2-canary"
                    set srcaddr "SYNTH-CLIENT-CANARY"
                    set dstaddr "SYNTH-SSH-HOST-CANARY"
                    set action accept
                    set schedule "always"
                    set service "SSH"
                next
                edit 2
                    set name "SYNTH-SSH-REDUNDANT-CANARY"
                    set srcintf "port1-canary"
                    set dstintf "port2-canary"
                    set srcaddr "SYNTH-CLIENT-CANARY"
                    set dstaddr "SYNTH-SSH-HOST-CANARY"
                    set action accept
                    set schedule "always"
                    set service "SSH"
                next
                edit 3
                    set name "SYNTH-SSH-CONFLICT-CANARY"
                    set srcintf "port1-canary"
                    set dstintf "port2-canary"
                    set srcaddr "SYNTH-CLIENT-CANARY"
                    set dstaddr "SYNTH-SSH-HOST-CANARY"
                    set action deny
                    set schedule "always"
                    set service "SSH"
                next
                edit 4
                    set name "SYNTH-ORDINARY-DENY-CANARY"
                    set srcintf "port1-canary"
                    set dstintf "port2-canary"
                    set srcaddr "all"
                    set dstaddr "SYNTH-EXT-HOST-CANARY"
                    set action deny
                    set schedule "always"
                    set service "HTTPS"
                next
                edit 5
                    set name "SYNTH-VIP-ACCEPT-CANARY"
                    set srcintf "port1-canary"
                    set dstintf "port2-canary"
                    set srcaddr "all"
                    set dstaddr "SYNTH-VIP-CANARY"
                    set action accept
                    set schedule "always"
                    set service "HTTPS"
                next
            end
            """;

    private static final String SYNTH_CONFIG_MODES_AND_MATCH_VIP = """
            #config-version=FG80F-7.4.8-FW-build2795-250521:opmode=0:vdom=1:user=admin
            config vdom
            edit VDOM-CANARY-RAW
            next
            edit VDOM-CANARY-MVIP
            next
            edit VDOM-CANARY-TRANSPARENT
            next
            end
            config global
            config system interface
                edit "wan1-canary"
                    set vdom "VDOM-CANARY-MVIP"
                    set ip 198.51.100.1 255.255.255.0
                next
                edit "dmz1-canary"
                    set vdom "VDOM-CANARY-MVIP"
                    set ip 203.0.113.1 255.255.255.0
                next
            end
            end
            config vdom
            edit VDOM-CANARY-RAW
            config system settings
                set ngfw-mode policy-based
            end
            end
            config vdom
            edit VDOM-CANARY-MVIP
            config firewall address
                edit "all"
                next
                edit "SYNTH-MAPPED-NET-CANARY"
                    set subnet 203.0.113.0 255.255.255.0
                next
                edit "SYNTH-PUB-WEB-CANARY"
                    set subnet 198.51.100.30 255.255.255.255
                next
            end
            config firewall vip
                edit "SYNTH-VIP-MVIP-CANARY"
                    set extip 198.51.100.30
                    set mappedip "203.0.113.30"
                    set extintf "wan1-canary"
                next
            end
            config firewall service custom
                edit "HTTPS"
                    set tcp-portrange 443
                next
            end
            config firewall policy
                edit 1
                    set name "SYNTH-DENY-MAPPED-CANARY"
                    set srcintf "wan1-canary"
                    set dstintf "dmz1-canary"
                    set srcaddr "all"
                    set dstaddr "SYNTH-MAPPED-NET-CANARY"
                    set action deny
                    set schedule "always"
                    set service "HTTPS"
                next
                edit 2
                    set name "SYNTH-ALLOW-EXT-CANARY"
                    set srcintf "wan1-canary"
                    set dstintf "dmz1-canary"
                    set srcaddr "all"
                    set dstaddr "SYNTH-PUB-WEB-CANARY"
                    set action accept
                    set schedule "always"
                    set service "HTTPS"
                next
            end
            end
            config vdom
            edit VDOM-CANARY-TRANSPARENT
            config system settings
                set opmode transparent
            end
            end
            """;

    static String selfTest(Path optionalOutputFile) {
        try {
            // 1. Verify unsafe-field guard drops unsafe tokens before anything else.
            StringWriter guardSw = new StringWriter();
            out = new PrintWriter(guardSw, true);
            emit("FW-TANGO-04", "rules", 3L);
            emit("fgt_01.v01", "rules", 3L);
            out.flush();
            if (!("unsafe_field_dropped\nfgt_01.v01\trules\t3\n").equals(guardSw.toString())) {
                return "unsafe_field_guard";
            }

            // 2. Run the full measurement pipeline over the two synthetic FortiOS 7.4 configurations.
            StringWriter sw = new StringWriter();
            out = new PrintWriter(sw, true);
            List<ConfigSupplier> suppliers = List.of(
                    () -> FortiGateConfigPolicyParser.parse(SYNTH_CONFIG_TRAD_VIP),
                    () -> FortiGateConfigPolicyParser.parse(SYNTH_CONFIG_MODES_AND_MATCH_VIP));
            evaluateFleet(suppliers);
            out.println("done");
            out.flush();
            String report = sw.toString();

            if (optionalOutputFile != null) {
                Files.writeString(optionalOutputFile, report, StandardCharsets.UTF_8);
            }

            String[] lines = report.split("\n");
            if (lines.length == 0 || !"done".equals(lines[lines.length - 1])) {
                return "missing_done_line";
            }

            // 3. Assert every emitted TSV line matches ^[a-z0-9_.]+(\t[a-z0-9_.]+)*$ and none dropped a field.
            for (String line : lines) {
                if (!SAFE_LINE.matcher(line).matches()) {
                    return "unsafe_line_format";
                }
                if (line.startsWith("unsafe_field_dropped")) {
                    return "unexpected_dropped_field";
                }
            }

            // 4. Assert zero canary strings (case-insensitive) appear anywhere in the output.
            String upper = report.toUpperCase(Locale.ROOT);
            for (String canary : List.of(
                    "SYNTH", "CANARY", "TANGO", "192.0.2", "198.51.100", "203.0.113", "PORT1", "PORT2", "WAN1", "DMZ1")) {
                if (upper.contains(canary)) {
                    return "canary_leaked";
                }
            }

            // 5. Parse emitted counters and verify VIP, A0-FG-18, A0-FG-20, unsupported mode, hygiene, and lookup counts.
            Map<String, Long> parsedCounters = new LinkedHashMap<>();
            for (String line : lines) {
                String[] parts = line.split("\t", -1);
                if (parts.length == 3) {
                    parsedCounters.put(parts[0] + "|" + parts[1], Long.parseLong(parts[2]));
                }
            }

            if (parsedCounters.getOrDefault("summary|devices", 0L) != 2L) return "summary_devices";
            if (parsedCounters.getOrDefault("summary|vdoms", 0L) != 4L) return "summary_vdoms";
            if (!parsedCounters.containsKey("summary|heap_retained_mb")) return "summary_heap_missing";

            if (parsedCounters.getOrDefault("fgt_01.v01|vip_queries", 0L) <= 0L) return "v01_vip_queries_zero";
            if (parsedCounters.getOrDefault("fgt_01.v01|vip_unknown_reason.fgt_vip_priority_unproven", 0L) <= 0L) {
                return "missing_fgt_vip_priority_unproven";
            }
            if (parsedCounters.getOrDefault("fgt_01.v01|shadow.redundant", 0L) <= 0L) return "missing_shadow_redundant";
            if (parsedCounters.getOrDefault("fgt_01.v01|shadow.conflict", 0L) <= 0L) return "missing_shadow_conflict";
            if (parsedCounters.getOrDefault("fgt_01.v01|finding.shadowed", 0L) <= 0L) return "missing_finding_shadowed";
            if (parsedCounters.getOrDefault("fgt_01.v01|finding.conflict", 0L) <= 0L) return "missing_finding_conflict";
            if (parsedCounters.getOrDefault("fgt_01.v01|lookup_queries", 0L) <= 0L) return "missing_lookup_queries";
            if (parsedCounters.getOrDefault("fgt_01.v01|verdict.allowed", 0L) <= 0L) return "missing_verdict_allowed";

            if (parsedCounters.getOrDefault("fgt_02.v01|failure.fgt_unsupported_mode", 0L) != 1L) {
                return "missing_policy_based_unsupported_mode";
            }
            if (parsedCounters.getOrDefault("fgt_02.v02|vip_unknown_reason.fgt_match_vip_unproven", 0L) <= 0L) {
                return "missing_fgt_match_vip_unproven";
            }
            if (parsedCounters.getOrDefault("fgt_02.v02|vip_verdict.denied", 0L) <= 0L) {
                return "missing_vip_verdict_denied";
            }
            if (parsedCounters.getOrDefault("fgt_02.v03|failure.fgt_unsupported_mode", 0L) != 1L) {
                return "missing_transparent_unsupported_mode";
            }
            if (parsedCounters.getOrDefault("summary|vip_queries", 0L) <= 0L) return "summary_vip_queries_zero";
            if (parsedCounters.getOrDefault("summary|vip_unknown_reason.fgt_vip_priority_unproven", 0L) <= 0L) {
                return "summary_fgt_vip_priority_unproven_zero";
            }
            if (parsedCounters.getOrDefault("summary|failure.fgt_unsupported_mode", 0L) < 2L) {
                return "summary_fgt_unsupported_mode_zero";
            }

            return null;
        } catch (Throwable t) {
            return "exception_" + slug(t.getClass().getSimpleName());
        }
    }
}
