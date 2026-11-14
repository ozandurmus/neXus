package com.securityexpert.nexus.ui2.service.policy;

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
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.SecretFile;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.asa.AsaConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyMapper;

/**
 * Hygiene and lookup over the 12 stored ASA policies, inside ui2-service, counters only (fields [a-z0-9_.]+; closed
 * vocabularies: statuses, the fixed reason sets, verdicts). Lookup queries are built in memory from each policy's own
 * resolved host destinations and tcp ports, between its own interfaces; no address or name is printed.
 */
public final class AsaEvaluationMeasure {
    static PrintWriter out;
    static final Pattern SAFE = Pattern.compile("[a-z0-9_.]+");

    public static void main(String[] args) throws Exception {
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        out = new PrintWriter(Files.newBufferedWriter(Path.of(args[0]).resolve("evaluation.out")), true);
        try { run(); out.println("done"); }
        catch (Throwable t) { out.println("fatal\t" + t.getClass().getSimpleName().toLowerCase(Locale.ROOT)); }
        out.close();
    }

    static void run() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_USER_FILE")), "db_user").strip());
        props.setProperty("password", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_PASSWORD_FILE")), "db_password").strip());
        List<Object[]> rows = new ArrayList<>();
        try (Connection conn = new org.postgresql.Driver().connect(System.getenv("UI2_DB_URL"), props)) {
            conn.setReadOnly(true);
            String sql = "select distinct on (d.device_id) d.device_id, r.artefact_ref, a.wrapped_data_key, c.compression from devices d "
                    + "join device_configuration_run r on r.device_id = d.device_id and r.read_kind = 'show_configuration' "
                    + "join configuration_artefact c on c.artefact_ref = r.artefact_ref "
                    + "join backup_artefact a on a.recovery_volume_path = r.artefact_ref and a.device_id = d.device_id "
                    + "where d.vendor_hint = 'cisco_asa' and d.role = 'gateway' and not d.disabled "
                    + "and d.enrollment_state in ('ENROLLED', 'DEGRADED') order by d.device_id, r.collected_at desc";
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new Object[] {rs.getString(1), rs.getString(2), rs.getBytes(3), "gzip".equals(rs.getString(4))});
            }
        }
        rows.sort(Comparator.comparing(r -> (String) r[0]));
        ArtefactStore store = new FileArtefactStore(Path.of(System.getenv("UI2_ARTEFACT_STORE_ROOT")),
                ArtefactStoreCipher.fromBase64Key(SecretFile.readRequired(Path.of(System.getenv("UI2_ARTEFACT_STORE_KEY_FILE")), "artefact_store_key")));
        Runtime rt = Runtime.getRuntime();
        List<PolicySnapshot> kept = new ArrayList<>();
        System.gc(); long heap0 = rt.totalMemory() - rt.freeMemory();
        int n = 0;
        for (Object[] row : rows) {
            String dev = String.format("asa_%02d", ++n);
            Map<String, Long> c = new TreeMap<>();
            try {
                PolicySnapshot s;
                try (InputStream in = store.retrieve(new ArtefactRef((String) row[1]), (byte[]) row[2], (Boolean) row[3])) {
                    s = AsaPolicyMapper.map("dev", "masked", "2026-11-14T00:00:00Z", "artefact",
                            AsaConfigPolicyParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                }
                kept.add(s);
                c.put("failures", (long) s.failures().size());
                long t0 = System.nanoTime();
                var hygiene = PolicyHygiene.analyze(s, 90, Instant.parse("2026-11-14T12:00:00Z"), PolicyHygiene.TOTAL_WORK);
                c.put("hygiene_ms", (System.nanoTime() - t0) / 1_000_000);
                c.put("hygiene_budget_reached", hygiene.budgetReached() ? 1L : 0L);
                for (var a : hygiene.rules().values()) {
                    inc(c, "shadow." + slug(a.shadowStatus()));
                    if (!a.shadowStatus().equals("NOT_SHADOWED") && !a.shadowStatus().equals("REDUNDANT") && !a.shadowStatus().equals("CONFLICT"))
                        inc(c, "shadow_reason." + (PolicyHygiene.REASONS.contains(a.shadowReason()) ? slug(a.shadowReason()) : "other"));
                    for (var f : a.findings()) inc(c, "finding." + slug(f.findingClass()));
                }
                // lookups: each in-list rule's own resolved host destination and tcp port, entering on that list's interface
                Set<String> ifs = new TreeSet<>();
                for (var o : s.objects().values()) if ("asa-interfaces".equals(o.type()))
                    for (String v : o.values()) if (v.startsWith("nameif: ")) ifs.add(v.substring(8));
                var prepared = PolicyLookup.prepare(s, 250000);
                Random random = new Random(7);
                int queries = 0;
                long t1 = System.nanoTime();
                List<String> egress = new ArrayList<>(ifs);
                for (var section : s.sections()) {
                    if (!"ASA access-group".equals(section.source()) || !section.name().endsWith(" in")) continue;
                    String ingress = section.name().substring(0, section.name().length() - 3);
                    for (var rule : section.rules()) {
                        if (queries >= 400 || egress.isEmpty()) break;
                        if (random.nextInt(Math.max(1, section.rules().size() / 40 + 1)) != 0) continue;
                        String dst = host(s, rule.destination()), port = port(s, rule.service());
                        if (dst == null || port == null) continue;
                        String outIf = egress.get(random.nextInt(egress.size()));
                        if (outIf.equals(ingress)) continue;
                        var body = prepared.evaluate(new PolicyLookup.Query("0.0.0.0/0".equals("") ? "" : "192.0.2.1", dst, "tcp/" + port, ingress, outIf, null, null)).body();
                        queries++;
                        inc(c, "lookup.verdict_" + slug(String.valueOf(body.get("verdict"))));
                        String reason = String.valueOf(body.get("lookupReason"));
                        inc(c, "lookup.reason." + (PolicyLookup.REASONS.contains(reason) ? slug(reason) : "other"));
                        if (reason.equals("analysis budget")) {
                            long constrained = section.rules().stream().filter(r -> Boolean.TRUE.equals(r.enabled())
                                    && (r.extras().containsKey("user") || r.extras().containsKey("time") || r.extras().containsKey("security-group")
                                        || r.extras().containsKey("asa-unparsed"))).count();
                            inc(c, constrained >= 128 ? "lookup.budget_in_list_with_128_plus_constrained" : "lookup.budget_other_cause");
                        }
                    }
                }
                c.put("lookup.queries", (long) queries);
                // Why lookups stop: rules in each "in" list that the lookup cannot decide (identity, time, unparsed)
                for (var section : s.sections()) {
                    if (!"ASA access-group".equals(section.source())) continue;
                    long constrained = section.rules().stream().filter(r -> Boolean.TRUE.equals(r.enabled())
                            && (r.extras().containsKey("user") || r.extras().containsKey("time") || r.extras().containsKey("security-group")
                                || r.extras().containsKey("asa-unparsed"))).count();
                    inc(c, "lists.total");
                    if (constrained >= 128) inc(c, "lists.with_128_or_more_constrained_rules");
                    if (constrained > 0) inc(c, "lists.with_constrained_rules");
                    c.merge("lists.rules_in_lists_with_128_or_more", constrained >= 128 ? (long) section.rules().size() : 0L, Long::sum);
                    // normalize the whole list as the lookup does, with the list budget, and count rules it cannot decide
                    var budget = new PolicyNormalization.Budget(PolicyLookup.ASA_LIST_WORK);
                    var resolver = new PolicyNormalization.Resolver(s.objects(), budget);
                    long problems = 0; boolean exhausted = false;
                    try {
                        for (var r : section.rules()) {
                            if (Boolean.FALSE.equals(r.enabled())) continue;
                            if (PolicyNormalization.normalize(s, section, r, resolver, true).problem() != null) problems++;
                        }
                    } catch (RuntimeException e) { exhausted = true; }
                    if (exhausted) inc(c, "lists.normalization_budget_exhausted");
                    if (problems >= 128) inc(c, "lists.with_128_plus_undecidable");
                    c.put("lists.list_work_used_max", Math.max(c.getOrDefault("lists.list_work_used_max", 0L), (long) (PolicyLookup.ASA_LIST_WORK - budget.left[0])));
                }
                c.put("lookup_ms", (System.nanoTime() - t1) / 1_000_000);
            } catch (Throwable e) {
                inc(c, "error_" + e.getClass().getSimpleName().toLowerCase(Locale.ROOT));
            }
            for (var e : c.entrySet()) emit(dev, e.getKey(), e.getValue());
        }
        System.gc(); long heap1 = rt.totalMemory() - rt.freeMemory();
        emit("all", "retained_heap_mb_all_snapshots", (heap1 - heap0) / (1024 * 1024));
        emit("all", "snapshots", kept.size());
    }

    static String host(PolicySnapshot s, PolicySnapshot.Cell cell) {
        if (cell.refs().size() != 1) return null;
        var o = s.objects().get(cell.refs().get(0));
        if (o == null || !"host".equals(o.type()) || !"RESOLVED".equals(o.status()) || o.values().size() != 1) return null;
        String v = o.values().get(0);
        return v.startsWith("ip-netmask: ") && v.endsWith("/32") ? v.substring(12, v.length() - 3) : null;
    }

    static String port(PolicySnapshot s, PolicySnapshot.Cell cell) {
        if (cell.refs().size() != 1) return null;
        var o = s.objects().get(cell.refs().get(0));
        if (o == null || !"RESOLVED".equals(o.status()) || !o.values().contains("protocol: tcp")) return null;
        for (String v : o.values()) if (v.startsWith("port: ") && v.substring(6).matches("[0-9]+")) return v.substring(6);
        return null;
    }

    static String slug(String s) {
        String x = s == null ? "null" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return SAFE.matcher(x).matches() && x.length() <= 60 ? x : x.length() > 60 && SAFE.matcher(x.substring(0, 60)).matches() ? x.substring(0, 60) : "other";
    }

    static void inc(Map<String, Long> c, String k) { c.merge(k, 1L, Long::sum); }

    static void emit(String dev, String key, long value) {
        String line = "asa\t" + dev + "\t" + key + "\t" + value;
        for (String f : line.split("\t")) if (!SAFE.matcher(f).matches()) { out.println("unsafe_field_dropped"); return; }
        out.println(line);
    }
}
