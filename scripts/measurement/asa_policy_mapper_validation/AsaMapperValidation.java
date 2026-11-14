package nexusmeasure;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.SecretFile;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.asa.AsaConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyMapper;

/**
 * Runs the ASA policy parser and mapper over the latest stored ASA configuration artefacts inside ui2-service and
 * prints counters only (every field [a-z0-9_.]+): parse/map health, sections, rule and object status distributions,
 * and how many access rules have fully resolved cells. Devices are ordinals; nothing else leaves the JVM.
 */
public final class AsaMapperValidation {
    static PrintWriter out;
    static final Pattern SAFE = Pattern.compile("[a-z0-9_.]+");

    public static void main(String[] args) throws Exception {
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        Path dir = Path.of(args.length > 0 ? args[0] : ".");
        out = new PrintWriter(Files.newBufferedWriter(dir.resolve("validation.out")), true);
        try { run(); out.println("done"); }
        catch (Throwable t) { out.println("fatal\t" + t.getClass().getSimpleName().toLowerCase(Locale.ROOT)); }
        out.close();
    }

    record Row(String deviceId, String ref, byte[] wrapped, boolean gzip) {}

    static void run() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_USER_FILE")), "db_user").strip());
        props.setProperty("password", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_PASSWORD_FILE")), "db_password").strip());
        List<Row> rows = new ArrayList<>();
        try (Connection conn = new org.postgresql.Driver().connect(System.getenv("UI2_DB_URL"), props)) {
            conn.setReadOnly(true);
            String sql = "select distinct on (d.device_id) d.device_id, r.artefact_ref, a.wrapped_data_key, c.compression from devices d "
                    + "join device_configuration_run r on r.device_id = d.device_id and r.read_kind = 'show_configuration' "
                    + "join configuration_artefact c on c.artefact_ref = r.artefact_ref "
                    + "join backup_artefact a on a.recovery_volume_path = r.artefact_ref and a.device_id = d.device_id "
                    + "where d.vendor_hint = 'cisco_asa' and d.role = 'gateway' and not d.disabled "
                    + "and d.enrollment_state in ('ENROLLED', 'DEGRADED') order by d.device_id, r.collected_at desc";
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new Row(rs.getString(1), rs.getString(2), rs.getBytes(3), "gzip".equals(rs.getString(4))));
            }
        }
        rows.sort((x, y) -> x.deviceId().compareTo(y.deviceId()));
        ArtefactStore store = new FileArtefactStore(Path.of(System.getenv("UI2_ARTEFACT_STORE_ROOT")),
                ArtefactStoreCipher.fromBase64Key(SecretFile.readRequired(
                        Path.of(System.getenv("UI2_ARTEFACT_STORE_KEY_FILE")), "artefact_store_key")));
        int n = 0;
        for (Row row : rows) {
            String dev = String.format("asa_%02d", ++n);
            Map<String, Long> c = new TreeMap<>();
            try {
                String text;
                try (InputStream in = store.retrieve(new ArtefactRef(row.ref()), row.wrapped(), row.gzip())) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                long t0 = System.nanoTime();
                AsaPolicyConfig cfg = AsaConfigPolicyParser.parse(text);
                long t1 = System.nanoTime();
                PolicySnapshot s = AsaPolicyMapper.map("dev", "masked", "2026-11-14T00:00:00Z", "artefact", cfg);
                long t2 = System.nanoTime();
                Map<String, Integer> idx = new java.util.HashMap<>();
                Map<String, Long> sigs = new TreeMap<>();
                long aligned = 0, misaligned = 0;
                for (String line : text.split("\n")) {
                    if (!line.startsWith("access-list ")) continue;
                    String[] w = line.strip().split("\\s+");
                    if (w.length < 3 || w[2].equals("remark")) continue;
                    int i = idx.merge(w[1], 1, Integer::sum) - 1;
                    var list = cfg.acls().get(w[1]);
                    if (list == null || i >= list.size()) { misaligned++; continue; }
                    aligned++;
                    if (list.get(i).unparsed()) sigs.merge(signature(w), 1L, Long::sum);
                }
                // Raw block inventory (names stay in memory): object network blocks by child kinds, object-group types
                Map<String, Set<String>> rawObjChildren = new java.util.HashMap<>();
                Map<String, String> rawGroupType = new java.util.HashMap<>();
                String cur = null;
                for (String line : text.split("\n")) {
                    if (line.isEmpty()) continue;
                    if (!Character.isWhitespace(line.charAt(0))) {
                        String[] w = line.strip().split("\\s+");
                        cur = (w.length >= 3 && w[0].equals("object") && w[1].equals("network")) ? w[2] : null;
                        if (cur != null) rawObjChildren.computeIfAbsent(cur, x -> new HashSet<>());
                        if (w.length >= 3 && w[0].equals("object-group")) rawGroupType.put(w[2], w[1]);
                    } else if (cur != null) {
                        rawObjChildren.get(cur).add(line.strip().split("\\s+")[0]);
                    }
                }
                c.put("raw.object_network_names", (long) rawObjChildren.size());
                String lastLine = "";
                for (String line : text.split("\n")) if (!line.isBlank()) lastLine = line.strip();
                c.put("raw.ends_with_end_marker", lastLine.equals(": end") ? 1L : 0L);
                Map<String, java.util.List<String>> tokenLines = new java.util.HashMap<>();
                final String[] rawLines = text.split("\n");
                java.util.function.Consumer<String> probe = name -> {
                    if (tokenLines.containsKey(name)) return;
                    java.util.List<String> kinds = new java.util.ArrayList<>();
                    for (String line : rawLines) {
                        String[] w = line.strip().split("\\s+");
                        for (int i = 0; i < w.length; i++) if (w[i].equals(name)) {
                            String lead = Character.isWhitespace(line.isEmpty() ? 'x' : line.charAt(0)) ? "child_" : "top_";
                            // first keyword only, from a closed vocabulary: a second token can be a configured name
                            String first = Set.of("access-list", "network-object", "object", "object-group", "nat", "access-group",
                                    "group-object", "service-object").contains(w[0]) ? slug(w[0]) : "other";
                            kinds.add(lead + first + "_pos" + Math.min(i, 9));
                        }
                    }
                    tokenLines.put(name, kinds);
                };
                Set<String> lowerObj = new HashSet<>(), lowerGrp = new HashSet<>(), lowerSvc = new HashSet<>();
                rawObjChildren.keySet().forEach(x -> lowerObj.add(x.toLowerCase(Locale.ROOT)));
                rawGroupType.keySet().forEach(x -> lowerGrp.add(x.toLowerCase(Locale.ROOT)));
                cfg.serviceObjects().keySet().forEach(x -> lowerSvc.add(x.toLowerCase(Locale.ROOT)));
                java.util.function.Function<String, String> where = v -> {
                    String l = v.toLowerCase(Locale.ROOT);
                    if (lowerObj.contains(l)) return "case_differs_object";
                    if (rawGroupType.containsKey(v)) return "is_group_" + slug(rawGroupType.get(v));
                    if (lowerGrp.contains(l)) return "case_differs_group";
                    if (lowerSvc.contains(l)) return "service_object";
                    if (cfg.names().containsKey(v)) return "names_alias";
                    if (ipv4(v)) return "ipv4_literal";
                    probe.accept(v);
                    for (String k : new java.util.TreeSet<>(tokenLines.get(v))) inc(c, "nowhere_seen_as." + k);
                    return v.length() <= 3 ? "short_token" : "nowhere";
                };
                c.put("parsed.network_objects", (long) cfg.networkObjects().size());
                for (var e : rawObjChildren.entrySet()) if (!cfg.networkObjects().containsKey(e.getKey()))
                    inc(c, "raw_not_parsed.children_" + slug(String.join("_", new java.util.TreeSet<>(e.getValue()))));
                // Why address references stay unresolved: classify each ACE object / object-group operand and each
                // group member reference by what the configuration defines under that name (names never printed).
                for (var list : cfg.acls().values()) for (var ace : list) {
                    if (ace.unparsed()) continue;
                    for (var spec : java.util.Arrays.asList(ace.source(), ace.destination())) {
                        if (spec == null) { inc(c, "ref.address_null"); continue; }
                        if (spec.kind().equals("object")) {
                            if (cfg.networkObjects().containsKey(spec.value1())) continue;
                            inc(c, cfg.serviceObjects().containsKey(spec.value1()) ? "ref.object_is_service"
                                    : rawObjChildren.containsKey(spec.value1()) ? "ref.object_missing_raw_" + slug(String.join("_", new java.util.TreeSet<>(rawObjChildren.get(spec.value1()))))
                                    : "ref.object_missing_" + where.apply(spec.value1()));
                        } else if (spec.kind().equals("object-group")) {
                            var og = cfg.objectGroups().get(spec.value1());
                            if (og == null) inc(c, rawGroupType.containsKey(spec.value1()) ? "ref.group_missing_raw_" + slug(rawGroupType.get(spec.value1())) : "ref.group_missing_" + where.apply(spec.value1()));
                            else if (!og.groupType().equals("network")) inc(c, "ref.group_type_" + slug(og.groupType()));
                        } else if (spec.kind().equals("host") || spec.kind().equals("subnet")) {
                            String v = spec.value1();
                            if (!ipv4(v))
                                inc(c, cfg.names().containsKey(v) ? "ref.literal_alias" : "ref.literal_not_ipv4_" + slug(spec.kind()));
                        } else if (!Set.of("any", "any4").contains(spec.kind())) inc(c, "ref.kind_" + slug(spec.kind()));
                    }
                }
                for (var og : cfg.objectGroups().values()) {
                    if (!og.groupType().equals("network")) continue;
                    for (var ch : og.children()) {
                        if (ch.unparsed()) { inc(c, "member.unparsed"); continue; }
                        switch (ch.kind()) {
                            case "network-object-ref" -> { if (!cfg.networkObjects().containsKey(ch.value1())) inc(c, rawObjChildren.containsKey(ch.value1())
                                    ? "member.object_missing_raw_" + slug(String.join("_", new java.util.TreeSet<>(rawObjChildren.get(ch.value1())))) : "member.object_missing_" + where.apply(ch.value1())); }
                            case "group-object" -> { var n2 = cfg.objectGroups().get(ch.value1());
                                if (n2 == null) inc(c, "member.group_missing"); else if (!n2.groupType().equals("network")) inc(c, "member.group_type_" + slug(n2.groupType())); }
                            case "host", "subnet" -> { if (!ipv4(ch.value1()))
                                inc(c, cfg.names().containsKey(ch.value1()) ? "member.alias" : "member.not_ipv4"); }
                            default -> inc(c, "member.kind_" + slug(ch.kind()));
                        }
                    }
                }
                c.put("align.aligned", aligned);
                c.put("align.misaligned", misaligned);
                sigs.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(25)
                        .forEach(e -> c.put("unparsed_sig." + e.getKey(), e.getValue()));
                text = null;
                c.put("parse_ms", (t1 - t0) / 1_000_000);
                c.put("map_ms", (t2 - t1) / 1_000_000);
                var k = cfg.counters();
                c.put("parser.parsed_aces", (long) k.parsedAces());
                c.put("parser.unparsed_aces", (long) k.unparsedAces());
                c.put("parser.parsed_nat", (long) k.parsedNatRules());
                c.put("parser.unparsed_nat", (long) k.unparsedNatRules());
                c.put("parser.parsed_og_children", (long) k.parsedObjectGroupChildren());
                c.put("parser.unparsed_og_children", (long) k.unparsedObjectGroupChildren());
                c.put("parser.unknown_top_level", (long) k.unknownTopLevelPolicyLines());
                c.put("parser.interfaces", (long) cfg.interfaces().size());
                c.put("parser.acls", (long) cfg.acls().size());
                c.put("parser.access_groups", (long) cfg.accessGroups().size());
                for (var f : s.failures()) inc(c, "failure." + slug(f.reason()));
                Set<String> uniqueAccess = new HashSet<>();
                for (var sec : s.sections()) {
                    String src = slug(sec.source());
                    inc(c, "sections." + src);
                    c.merge("rules." + src, (long) sec.rules().size(), Long::sum);
                    if (!"asa_access_group".equals(src)) continue;
                    for (var r : sec.rules()) {
                        uniqueAccess.add(r.extras().get("asa-acl") + ":" + r.number());
                        inc(c, "access.action_" + slug(r.action()));
                        if (Boolean.FALSE.equals(r.enabled())) inc(c, "access.disabled");
                        for (String key : r.extras().keySet())
                            if (!Set.of("from", "to", "asa-direction", "asa-acl").contains(key)) inc(c, "access.extra_" + slug(key));
                        String sv = leaf(s, r.source()), dv = leaf(s, r.destination()), vv = leaf(s, r.service());
                        inc(c, "access.src_" + sv);
                        inc(c, "access.dst_" + dv);
                        inc(c, "access.svc_" + vv);
                        boolean constraints = r.extras().containsKey("time") || r.extras().containsKey("user")
                                || r.extras().containsKey("security-group") || r.extras().containsKey("asa-unparsed");
                        if (sv.equals("resolved") && dv.equals("resolved") && vv.equals("resolved") && !constraints) inc(c, "access.rules_fully_resolved");
                    }
                }
                c.put("access.unique_aces", (long) uniqueAccess.size());
                for (var o : s.objects().values()) inc(c, "objects." + slug(o.type()) + "." + slug(o.status()));
            } catch (Throwable e) {
                inc(c, "error_" + e.getClass().getSimpleName().toLowerCase(Locale.ROOT));
            }
            for (var e : c.entrySet()) emit(dev, e.getKey(), e.getValue());
        }
    }

    /** Worst leaf status of a cell: resolved only when every reachable object is RESOLVED. */
    static String leaf(PolicySnapshot s, PolicySnapshot.Cell cell) {
        String[] worst = {"resolved"};
        for (String ref : cell.refs()) walk(s, ref, new java.util.ArrayDeque<>(), worst, 0);
        if (cell.refs().isEmpty()) return "empty";
        return worst[0];
    }

    static void walk(PolicySnapshot s, String id, java.util.Deque<String> path, String[] worst, int depth) {
        if (path.contains(id) || depth > 32) { worst[0] = "cycle"; return; }
        var o = s.objects().get(id);
        if (o == null) { worst[0] = "missing"; return; }
        if (!"RESOLVED".equals(o.status())) { if (worst[0].equals("resolved")) worst[0] = slug(o.status()); return; }
        path.push(id);
        for (String m : o.members()) walk(s, m, path, worst, depth + 1);
        path.pop();
    }

    static final Set<String> KEYWORDS = Set.of("permit", "deny", "ip", "tcp", "udp", "icmp", "icmp6", "gre", "esp", "ah",
            "any", "any4", "any6", "host", "object", "object-group", "interface", "user", "user-group", "object-group-user",
            "security-group", "object-group-security", "name", "tag", "eq", "lt", "gt", "neq", "range", "log", "time-range",
            "inactive", "disable", "default", "interval", "echo", "echo-reply", "unreachable", "time-exceeded", "extended",
            "standard", "ethertype", "webtype", "advanced", "fqdn", "url", "tcp-udp", "sctp", "trace", "remark");

    /** Signature of an ACE: closed keyword vocabulary kept, everything else reduced to its class. */
    static String signature(String[] w) {
        StringBuilder b = new StringBuilder();
        for (int i = 3; i < w.length && i < 24; i++) {
            String t = w[i], cls;
            if (KEYWORDS.contains(t)) cls = t.replace('-', '_');
            else if (t.matches("\\d{1,3}(\\.\\d{1,3}){3}")) cls = "ip4";
            else if (t.matches("\\d+")) cls = "n";
            else if (t.contains(":") && t.contains("/")) cls = "ip6";
            else if (t.contains("\\")) cls = "wbs";
            else cls = "w";
            if (b.length() > 0) b.append('.');
            b.append(cls);
        }
        return b.length() == 0 ? "empty" : b.toString();
    }

    static String slug(String s) {
        String x = s == null ? "null" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return SAFE.matcher(x).matches() && x.length() <= 40 ? x : "other";
    }

    static boolean ipv4(String v) { return v != null && v.matches("\\d{1,3}(\\.\\d{1,3}){3}"); }

    static void inc(Map<String, Long> c, String k) { c.merge(k, 1L, Long::sum); }

    static void emit(String dev, String key, long value) {
        String line = "asa\t" + dev + "\t" + key + "\t" + value;
        for (String f : line.split("\t")) if (!SAFE.matcher(f).matches()) { out.println("unsafe_field_dropped"); return; }
        out.println(line);
    }
}
