package nexusmeasure;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.SecretFile;

/**
 * Step-0 shape measurement for the Cisco ASA / FortiGate policy model (ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md
 * section 7; PO_DECISION_RECORD_2026_11_14_ASA_FORTIGATE_POLICY_MODEL.md items 1-2).
 *
 * <p>Runs inside ui2-service. Reads the latest show_configuration configuration artefact of every enrolled ASA and
 * FortiGate gateway, decrypts it in memory, counts vendor keywords and discards the text. Output: one TSV line per
 * counter, every field [a-z0-9_.]+ (device and VDOM are ordinals; names, addresses and values never leave the JVM).
 * Port literals are emitted only when they are in Cisco's published Table 7 (cisco_port_literals.tsv).
 */
public final class AsaFgtShapeMeasure {

    static PrintWriter out;
    static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    static final Pattern NUM = Pattern.compile("\\d+");
    static final Pattern SAFE = Pattern.compile("[a-z0-9_.]+");
    static Set<String> portLiterals = new HashSet<>();
    static Set<String> protoLiterals = new HashSet<>();

    public static void main(String[] args) throws Exception {
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        Path dir = Path.of(args.length > 0 ? args[0] : ".");
        out = new PrintWriter(Files.newBufferedWriter(dir.resolve("shape.out")), true);
        try {
            for (String l : Files.readAllLines(dir.resolve("cisco_port_literals.tsv")))
                if (!l.startsWith("#") && !l.isBlank()) portLiterals.add(l.split("\t")[0]);
            for (String l : Files.readAllLines(dir.resolve("cisco_protocol_literals.tsv")))
                if (!l.startsWith("#") && !l.isBlank()) protoLiterals.add(l.split("\t")[0]);
            run();
            out.println("done");
        } catch (Throwable t) {
            out.println("fatal\t" + t.getClass().getSimpleName().toLowerCase(Locale.ROOT));
        }
        out.close();
    }

    record Row(String deviceId, String vendor, String pairClaim, String version, String ref, byte[] wrapped, boolean gzip) {}

    static void run() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_USER_FILE")), "db_user").strip());
        props.setProperty("password", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_PASSWORD_FILE")), "db_password").strip());
        List<Row> rows = new ArrayList<>();
        try (Connection conn = new org.postgresql.Driver().connect(System.getenv("UI2_DB_URL"), props)) {
            conn.setReadOnly(true);
            String sql = "select distinct on (d.device_id) d.device_id, d.vendor_hint, d.ha_pair_claim, d.observed_software_version, "
                    + "r.artefact_ref, a.wrapped_data_key, c.compression from devices d "
                    + "join device_configuration_run r on r.device_id = d.device_id and r.read_kind = 'show_configuration' "
                    + "join configuration_artefact c on c.artefact_ref = r.artefact_ref "
                    + "join backup_artefact a on a.recovery_volume_path = r.artefact_ref and a.device_id = d.device_id "
                    + "where d.vendor_hint in ('cisco_asa', 'fortinet') and d.role = 'gateway' and not d.disabled "
                    + "and d.enrollment_state in ('ENROLLED', 'DEGRADED') order by d.device_id, r.collected_at desc";
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBytes(6), "gzip".equals(rs.getString(7))));
            }
        }
        rows.sort((x, y) -> (x.vendor() + x.deviceId()).compareTo(y.vendor() + y.deviceId()));
        ArtefactStore store = new FileArtefactStore(Path.of(System.getenv("UI2_ARTEFACT_STORE_ROOT")),
                ArtefactStoreCipher.fromBase64Key(SecretFile.readRequired(
                        Path.of(System.getenv("UI2_ARTEFACT_STORE_KEY_FILE")), "artefact_store_key")));
        int asaN = 0, fgtN = 0;
        Map<String, List<String>> pairHashes = new TreeMap<>();
        for (Row row : rows) {
            boolean asa = row.vendor().equals("cisco_asa");
            String dev = asa ? String.format("asa_%02d", ++asaN) : String.format("fgt_%02d", ++fgtN);
            String text;
            try (InputStream in = store.retrieve(new ArtefactRef(row.ref()), row.wrapped(), row.gzip())) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                emit(asa ? "asa" : "fgt", dev, "all", "read_error_" + e.getClass().getSimpleName().toLowerCase(Locale.ROOT), 1);
                continue;
            }
            String version = row.version() == null ? "" : row.version().replaceAll("^v?(\\d+\\.\\d+).*$", "$1");
            if (!version.matches("\\d+\\.\\d+")) version = "";
            emitS(asa ? "asa" : "fgt", dev, "all", "software", version.isEmpty() ? "unknown" : version);
            emit(asa ? "asa" : "fgt", dev, "all", "in_pair", row.pairClaim() == null ? 0 : 1);
            String hash = asa ? asa(dev, text) : fgt(dev, text);
            if (row.pairClaim() != null) pairHashes.computeIfAbsent(row.vendor() + "|" + row.pairClaim(), k -> new ArrayList<>()).add(hash);
            text = null;
        }
        int p = 0;
        for (var e : pairHashes.entrySet()) {
            String v = e.getKey().startsWith("cisco_asa") ? "asa" : "fgt";
            List<String> h = e.getValue();
            String verdict = h.size() != 2 ? "members_" + h.size() : h.get(0).equals(h.get(1)) ? "match" : "mismatch";
            String pair = String.format("pair_%02d", ++p);
            emitS(v, pair, "all", "policy_hash", verdict);
            if (h.size() == 2 && v.equals("asa")) {
                Map<String, String> a = famMap(h.get(0)), b = famMap(h.get(1));
                Set<String> keys = new TreeSet<>(a.keySet()); keys.addAll(b.keySet());
                for (String k : keys) emitS(v, pair, "all", "family." + k, java.util.Objects.equals(a.get(k), b.get(k)) ? "match" : "mismatch");
            }
        }
    }

    // ------------------------------------------------------------------ ASA

    static String asa(String dev, String raw) throws Exception {
        Map<String, Long> c = new TreeMap<>();
        Map<String, Integer> aclBindings = new HashMap<>();
        Map<String, Map<String, Long>> perAcl = new HashMap<>();
        Map<String, Set<String>> aclRefs = new TreeMap<>();
        Map<String, StringBuilder> fam = new TreeMap<>();
        Set<String> aclNames = new HashSet<>();
        Map<String, Set<String>> groupEdges = new HashMap<>();
        Set<String> groups = new HashSet<>();
        Set<String> portsUsed = new TreeSet<>();
        Map<String, String> ifLevel = new HashMap<>();
        List<String> levels = new ArrayList<>();
        StringBuilder relevant = new StringBuilder();
        String block = null; // header of the current top-level block
        String blockName = null;
        List<String> objChildren = new ArrayList<>();
        boolean ifNameif = false;
        String ifLevelValue = null;
        Runnable[] closeHolder = new Runnable[1];
        String[] lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        inc(c, "lines_total");
        c.put("lines_total", (long) lines.length);
        // close-out logic for a finished top-level block
        List<String[]> finished = new ArrayList<>();
        for (int li = 0; li <= lines.length; li++) {
            String line = li < lines.length ? lines[li].stripTrailing() : "";
            boolean child = li < lines.length && !line.isEmpty() && Character.isWhitespace(line.charAt(0));
            String t = line.strip();
            if (li < lines.length && (t.isEmpty() || t.equals("!") || t.startsWith(":"))) continue;
            if (li < lines.length) aclReferences(t.split("\\s+"), aclRefs);
            if (child) {
                String[] w = t.split("\\s+");
                if (block != null) fam(fam, block.startsWith("object") ? block.replace(' ', '_').replace('-', '_') : block.equals("interface") ? "interface" : "other", t);
                if (block == null) { inc(c, "orphan_child_lines"); continue; }
                if (block.equals("interface")) {
                    if (w[0].equals("nameif")) { ifNameif = true; relevant.append(" nameif\n"); }
                    else if (w[0].equals("security-level") && w.length > 1) { ifLevelValue = w[1]; relevant.append(" ").append(t).append('\n'); }
                    else if (w[0].equals("ipv6") && w.length > 1 && w[1].equals("address")) inc(c, "interface_ipv6_address");
                    continue;
                }
                if (block.equals("object network")) {
                    objChildren.add(w[0]);
                    relevant.append(' ').append(t).append('\n');
                    if (w[0].equals("nat")) natLine(c, w, "nat_section2");
                    else if (w[0].equals("subnet") && w.length > 2) mask(c, w[2]);
                    continue;
                }
                if (block.equals("object service")) {
                    relevant.append(' ').append(t).append('\n');
                    inc(c, "object_service_child_" + kw(w[0], Set.of("service", "description")));
                    continue;
                }
                if (block.startsWith("object-group")) {
                    relevant.append(' ').append(t).append('\n');
                    String k = kw(w[0], Set.of("network-object", "group-object", "service-object", "port-object",
                            "protocol-object", "icmp-object", "description", "user", "user-group", "security-group"));
                    inc(c, "og_child_" + k.replace('-', '_'));
                    if (w[0].equals("group-object") && w.length > 1) groupEdges.computeIfAbsent(blockName, x -> new HashSet<>()).add(w[1]);
                    if (w[0].equals("network-object") && w.length > 2 && IPV4.matcher(w[1]).matches()) mask(c, w[2]);
                    if (w[0].equals("port-object") || w[0].equals("service-object")) portOps(c, w, portsUsed);
                    continue;
                }
                continue;
            }
            // a new top-level line closes the previous block
            if (block != null) {
                if (block.equals("interface")) {
                    if (ifNameif) { inc(c, "interfaces_nameif"); if (ifLevelValue != null) { levels.add(ifLevelValue); } else inc(c, "interfaces_nameif_without_level"); }
                } else if (block.equals("object network")) {
                    if (objChildren.size() == 1 && objChildren.get(0).equals("nat")) inc(c, "object_network_nat_only_blocks");
                    else for (String ch : objChildren) if (Set.of("host", "subnet", "range", "fqdn").contains(ch)) inc(c, "object_network_" + ch);
                }
                block = null; blockName = null; objChildren.clear(); ifNameif = false; ifLevelValue = null;
            }
            if (li == lines.length) break;
            String[] w = t.split("\\s+");
            String first = w[0];
            if (first.equals("interface")) { block = "interface"; relevant.append("interface\n"); fam(fam, "interface", "interface"); continue; }
            if (first.equals("object") && w.length > 2) {
                block = "object " + w[1]; blockName = w[2]; relevant.append(t).append('\n');
                fam(fam, block.replace(' ', '_'), t);
                if (!w[1].equals("network") && !w[1].equals("service")) inc(c, "object_other_type");
                else if (w[1].equals("service")) inc(c, "object_service");
                continue;
            }
            if (first.equals("object-group") && w.length > 2) {
                block = "object-group"; blockName = w[2]; groups.add(w[2]); relevant.append(t).append('\n');
                fam(fam, "object_group", t);
                inc(c, "object_group_" + kw(w[1], Set.of("network", "service", "protocol", "icmp-type", "user", "security", "network-service")).replace('-', '_'));
                if (w[1].equals("service") && w.length > 3) inc(c, "object_group_service_legacy_" + kw(w[3], Set.of("tcp", "udp", "tcp-udp")).replace('-', '_'));
                continue;
            }
            if (first.equals("access-list") && w.length > 2) {
                relevant.append(t).append('\n');
                aclNames.add(w[1]);
                if (w[2].equals("remark")) { inc(c, "remarks"); fam(fam, "acl_remark", t); continue; }
                String type = kw(w[2], Set.of("extended", "standard", "ethertype", "webtype", "advanced"));
                inc(c, "aces_" + type);
                if (type.equals("extended") || type.equals("advanced")) {
                    ace(c, w, portsUsed);
                    Map<String, Long> pa = perAcl.computeIfAbsent(w[1], x -> new TreeMap<>());
                    inc(pa, "aces");
                    ace(pa, w, new TreeSet<>());
                }
                fam(fam, "acl", t);
                continue;
            }
            if (first.equals("access-group") && w.length > 2) {
                relevant.append(t).append('\n');
                boolean cp = t.endsWith(" control-plane");
                if (cp) inc(c, "access_group_control_plane");
                else if (w[2].equals("global")) inc(c, "access_group_global");
                else if (w[2].equals("in")) inc(c, "access_group_in");
                else if (w[2].equals("out")) inc(c, "access_group_out");
                else inc(c, "access_group_other");
                if (!cp) aclBindings.merge(w[1], 1, Integer::sum);
                fam(fam, "access_group", t);
                continue;
            }
            if (first.equals("nat")) {
                relevant.append(t).append('\n');
                natLine(c, w, t.contains(" after-auto ") ? "nat_section3" : "nat_section1");
                fam(fam, "nat_manual", t);
                continue;
            }
            if (first.equals("names")) { c.put("names_enabled", 1L); continue; }
            if (first.equals("name") && w.length > 2) { inc(c, "name_lines"); relevant.append(t).append('\n'); fam(fam, "names", t); continue; }
            if (first.equals("same-security-traffic")) {
                relevant.append(t).append('\n');
                fam(fam, "same_security", t);
                if (t.contains("inter-interface")) c.put("same_security_inter", 1L);
                if (t.contains("intra-interface")) c.put("same_security_intra", 1L);
                continue;
            }
            if (first.equals("firewall") && t.contains("transparent")) { c.put("firewall_transparent", 1L); continue; }
            if (first.equals("zone")) { inc(c, "zone_lines"); continue; }
            if (first.equals("time-range")) { inc(c, "time_range_objects"); continue; }
            if (first.equals("ipv6") && w.length > 1 && w[1].equals("access-list")) { inc(c, "ipv6_access_list_legacy"); continue; }
        }
        c.put("access_list_names", (long) aclNames.size());
        long bound = aclNames.stream().filter(aclBindings::containsKey).count();
        c.put("acl_bound", bound);
        c.put("acl_unbound", aclNames.size() - bound);
        c.put("acl_bound_multi", aclBindings.values().stream().filter(n -> n > 1).count());
        c.put("acl_bound_not_defined", aclBindings.keySet().stream().filter(n -> !aclNames.contains(n)).count());
        Map<String, Integer> levelCount = new HashMap<>();
        for (String l : levels) levelCount.merge(l, 1, Integer::sum);
        c.put("security_levels_distinct", (long) levelCount.size());
        c.put("security_level_tie_interfaces", levelCount.values().stream().filter(n -> n > 1).mapToLong(Integer::longValue).sum());
        int[] depthCycle = depth(groupEdges);
        c.put("group_nesting_max_depth", (long) depthCycle[0]);
        c.put("group_cycles", (long) depthCycle[1]);
        c.put("group_object_ref_undefined", groupEdges.values().stream().flatMap(Set::stream).filter(g -> !groups.contains(g)).count());
        Map<String, Long> boundStats = new TreeMap<>();
        for (var e : perAcl.entrySet()) if (aclBindings.containsKey(e.getKey()))
            for (var x : e.getValue().entrySet()) boundStats.merge(x.getKey(), x.getValue(), Long::sum);
        for (var x : boundStats.entrySet()) c.put("bound." + x.getKey(), x.getValue());
        Set<String> anyRef = new HashSet<>(aclBindings.keySet());
        for (var e : aclRefs.entrySet()) {
            long n = e.getValue().stream().filter(aclNames::contains).count();
            c.put("acl_referenced_by." + e.getKey(), n);
            e.getValue().stream().filter(aclNames::contains).forEach(anyRef::add);
        }
        c.put("acl_referenced_nowhere", aclNames.stream().filter(a -> !anyRef.contains(a)).count());
        c.put("aces_in_acls_referenced_nowhere", perAcl.entrySet().stream().filter(e -> !anyRef.contains(e.getKey()))
                .mapToLong(e -> e.getValue().getOrDefault("aces", 0L)).sum());
        long maxBound = perAcl.entrySet().stream().filter(e -> aclBindings.containsKey(e.getKey()))
                .mapToLong(e -> e.getValue().getOrDefault("aces", 0L)).max().orElse(0);
        c.put("bound_acl_max_aces", maxBound);
        for (var e : c.entrySet()) emit("asa", dev, "all", e.getKey().replace('-', '_'), e.getValue());
        for (String p : portsUsed) emit("asa", dev, "all", "port_literal_used." + p.replace('-', '_'), 1);
        StringBuilder famHash = new StringBuilder();
        for (var e : fam.entrySet()) famHash.append(e.getKey()).append('=').append(sha(e.getValue().toString())).append(';');
        return famHash.toString();
    }

    static void fam(Map<String, StringBuilder> fam, String family, String line) {
        fam.computeIfAbsent(family, x -> new StringBuilder()).append(line).append('\n');
    }

    /** ACL names used outside access-group: crypto map match address, vpn-filter, split tunnel, class-map, route-map, capture. */
    static void aclReferences(String[] w, Map<String, Set<String>> refs) {
        for (int i = 0; i + 2 < w.length + 1; i++) {
            String k = w[i];
            String next = i + 1 < w.length ? w[i + 1] : "";
            String arg = i + 2 < w.length ? w[i + 2] : null;
            if (k.equals("match") && next.equals("address") && arg != null) add(refs, "crypto_match_address", arg);
            else if (k.equals("vpn-filter") && next.equals("value") && arg != null) add(refs, "vpn_filter", arg);
            else if (k.equals("ipv6-vpn-filter") && next.equals("value") && arg != null) add(refs, "vpn_filter", arg);
            else if (k.equals("split-tunnel-network-list") && next.equals("value") && arg != null) add(refs, "split_tunnel", arg);
            else if (k.equals("match") && next.equals("access-list") && arg != null) add(refs, "class_map", arg);
            else if (k.equals("match") && next.equals("ip") && arg != null && arg.equals("address")) for (int j = i + 3; j < w.length; j++) add(refs, "route_map", w[j]);
            else if (k.equals("access-list") && i > 0 && w[0].equals("capture") && i + 1 < w.length) add(refs, "capture", next);
            else if (k.equals("filter") && next.equals("value") && arg != null) add(refs, "webvpn_filter", arg);
            else if (k.equals("access-list") && i > 0 && w[0].equals("aaa")) add(refs, "aaa", next);
        }
    }

    static void add(Map<String, Set<String>> refs, String kind, String name) {
        refs.computeIfAbsent(kind, x -> new HashSet<>()).add(name);
    }

    static void ace(Map<String, Long> c, String[] w, Set<String> portsUsed) {
        // access-list NAME extended permit|deny PROTOCOL ...
        if (w.length < 5) { inc(c, "aces_short"); return; }
        inc(c, "aces_action_" + kw(w[3], Set.of("permit", "deny")));
        String proto = w[4];
        String pk = proto.equals("object-group") || proto.equals("object") ? proto.replace('-', '_')
                : NUM.matcher(proto).matches() ? "number" : protoLiterals.contains(proto) ? "literal_" + proto : "other";
        inc(c, "aces_protocol_" + pk);
        Set<String> seen = new HashSet<>();
        for (int i = 5; i < w.length; i++) {
            String k = w[i];
            switch (k) {
                case "inactive", "log", "time-range", "user", "user-group", "object-group-user", "security-group",
                        "object-group-security", "interface", "host", "any", "any4", "any6", "object", "object-group" -> {
                    if (seen.add(k)) inc(c, "aces_with_" + k.replace('-', '_'));
                    inc(c, "ace_tokens_" + k.replace('-', '_'));
                    if (Set.of("time-range", "user", "user-group", "object-group-user", "security-group", "object-group-security",
                            "interface", "host", "object", "object-group").contains(k)) i++; // the operand is a name or address
                    if (k.equals("security-group") && i < w.length && (w[i].equals("name") || w[i].equals("tag"))) i++;
                }
                case "eq", "lt", "gt", "neq", "range" -> {
                    inc(c, "ace_port_op_" + k);
                    int n = k.equals("range") ? 2 : 1;
                    for (int j = 1; j <= n && i + j < w.length; j++) port(c, w[i + j], portsUsed);
                    i += n;
                }
                default -> {
                    if (IPV4.matcher(k).matches()) {
                        if (i + 1 < w.length && IPV4.matcher(w[i + 1]).matches()) { inc(c, "ace_tokens_inline_mask"); mask(c, w[i + 1]); i++; }
                        else inc(c, "ace_tokens_ipv4_single");
                    } else if (k.contains(":") && k.contains("/")) inc(c, "ace_tokens_ipv6_literal");
                }
            }
        }
    }

    static void portOps(Map<String, Long> c, String[] w, Set<String> portsUsed) {
        for (int i = 1; i < w.length; i++) {
            if (Set.of("eq", "lt", "gt", "neq").contains(w[i]) && i + 1 < w.length) { inc(c, "og_port_op_" + w[i]); port(c, w[i + 1], portsUsed); i++; }
            else if (w[i].equals("range") && i + 2 < w.length) { inc(c, "og_port_op_range"); port(c, w[i + 1], portsUsed); port(c, w[i + 2], portsUsed); i += 2; }
            else if (Set.of("source", "destination").contains(w[i])) inc(c, "og_service_" + w[i]);
        }
    }

    static void port(Map<String, Long> c, String token, Set<String> portsUsed) {
        if (NUM.matcher(token).matches()) { inc(c, "port_numeric"); return; }
        if (portLiterals.contains(token)) { inc(c, "port_literal_in_table"); portsUsed.add(token); return; }
        inc(c, "port_word_not_in_table");
    }

    static void natLine(Map<String, Long> c, String[] w, String section) {
        inc(c, section);
        String t = String.join(" ", w);
        if (t.contains(" static ")) inc(c, section + "_static");
        if (t.contains(" dynamic ")) inc(c, section + "_dynamic");
        if (t.contains(" destination ")) inc(c, section + "_twice_destination");
        if (t.contains(" service ")) inc(c, section + "_service");
        if (t.matches(".* dynamic .*interface.*")) inc(c, section + "_interface_pat");
        if (t.contains(" no-proxy-arp")) inc(c, section + "_no_proxy_arp");
        if (t.contains(" inactive")) inc(c, section + "_inactive");
        if (t.contains(" any ")) inc(c, section + "_any_interface");
    }

    static void mask(Map<String, Long> c, String m) {
        if (!IPV4.matcher(m).matches()) return;
        long v = 0;
        for (String o : m.split("\\.")) v = (v << 8) | (Integer.parseInt(o) & 0xff);
        long inv = ~v & 0xffffffffL;
        boolean contiguous = ((inv + 1) & inv) == 0;
        inc(c, contiguous ? "masks_contiguous" : "masks_non_contiguous");
    }

    static int[] depth(Map<String, Set<String>> edges) {
        Map<String, Integer> memo = new HashMap<>();
        int[] cycles = {0};
        int max = 0;
        for (String g : edges.keySet()) max = Math.max(max, dfs(g, edges, memo, new HashSet<>(), cycles));
        return new int[] {max, cycles[0]};
    }

    static int dfs(String g, Map<String, Set<String>> edges, Map<String, Integer> memo, Set<String> path, int[] cycles) {
        if (memo.containsKey(g)) return memo.get(g);
        if (!path.add(g)) { cycles[0]++; return 0; }
        int d = 0;
        for (String n : edges.getOrDefault(g, Set.of())) d = Math.max(d, 1 + dfs(n, edges, memo, path, cycles));
        path.remove(g);
        memo.put(g, d);
        return d;
    }

    static Map<String, String> famMap(String s) {
        Map<String, String> m = new TreeMap<>();
        for (String part : s.split(";")) { int e = part.indexOf('='); if (e > 0) m.put(part.substring(0, e), part.substring(e + 1)); }
        return m;
    }

    // ------------------------------------------------------------------ FortiGate

    static final class Vdom {
        final Map<String, Long> c = new TreeMap<>();
        final Set<String> addresses = new HashSet<>(), groups = new HashSet<>(), vips = new HashSet<>(), services = new HashSet<>();
        final List<String> policyServiceRefs = new ArrayList<>(), policyDstRefs = new ArrayList<>();
        final List<Long> policyIds = new ArrayList<>();
        final Map<String, Set<String>> groupEdges = new HashMap<>();
        final Map<String, String> modes = new TreeMap<>();
    }

    static String fgt(String dev, String raw) throws Exception {
        Map<String, Vdom> vdoms = new LinkedHashMap<>();
        Deque<String> stack = new ArrayDeque<>(); // innermost first
        String vdom = "root";
        boolean multi = false;
        long total = 0;
        Map<String, String> entry = null; // current edit entry under a firewall table
        Map<String, List<String>> entryMulti = null;
        String table = null;
        String entryName = null;
        StringBuilder relevant = new StringBuilder();
        boolean quoted = false;
        for (String rawLine : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            total++;
            String t = rawLine.strip();
            if (quoted) { if (quotes(rawLine) % 2 == 1) quoted = false; continue; }
            if (t.isEmpty() || t.startsWith("#")) continue;
            List<String> w = tokens(t);
            if (w.isEmpty()) continue;
            String k = w.get(0);
            if (k.equals("config")) {
                String name = String.join(" ", w.subList(1, w.size()));
                if (stack.isEmpty() && name.equals("vdom")) multi = true;
                if (stack.isEmpty() && name.equals("global")) vdom = "global";
                stack.push("config " + name);
                String outer = outerSection(stack);
                if (outer != null && (stack.size() == (insideVdom(stack) ? 3 : 1) || (stack.size() == 2 && stack.peekLast().equals("config global")))) {
                    table = outer;
                    Vdom v = vdoms.computeIfAbsent(vdom, x -> new Vdom());
                    inc(v.c, "section_" + slug(outer));
                }
                continue;
            }
            if (k.equals("edit")) {
                String name = w.size() > 1 ? w.get(1) : "";
                if (stack.size() == 1 && "config vdom".equals(stack.peek())) vdom = name;
                stack.push("edit " + name);
                if (table != null && stack.size() == (insideVdom(stack) ? 4 : 2)) {
                    entry = new HashMap<>(); entryMulti = new HashMap<>(); entryName = name;
                }
                continue;
            }
            if (k.equals("next") || k.equals("end")) {
                if (k.equals("next") && entry != null && !stack.isEmpty() && stack.peek().startsWith("edit ")
                        && stack.size() == (insideVdom(stack) ? 4 : 2)) {
                    finishEntry(vdoms.computeIfAbsent(vdom, x -> new Vdom()), table, entryName, entry, entryMulti, relevant);
                    entry = null; entryMulti = null;
                }
                if (!stack.isEmpty()) {
                    boolean closesVdom = k.equals("end") && stack.size() == 2 && stack.peekLast().equals("config vdom") && stack.peek().startsWith("edit ");
                    String popped = stack.pop();
                    if (closesVdom) stack.pop();
                    if (k.equals("end") && popped.startsWith("config ") && table != null && outerSectionAfterPop(stack) == null) table = null;
                    if (closesVdom || (k.equals("end") && stack.isEmpty())) { table = null; if (stack.isEmpty()) vdom = multi ? "global" : "root"; }
                    if (k.equals("end") && table != null && stack.size() < (insideVdom(stack) ? 3 : 1)) table = null;
                }
                continue;
            }
            if (k.equals("set") || k.equals("unset")) {
                if (t.startsWith("set ") && quotes(t) % 2 == 1) quoted = true;
                if (k.equals("set") && w.size() > 1) {
                    if (entry != null) {
                        entry.put(w.get(1), w.size() > 2 ? w.get(2) : "");
                        entryMulti.put(w.get(1), new ArrayList<>(w.subList(Math.min(2, w.size()), w.size())));
                    } else if (table != null && table.equals("system settings")) {
                        Vdom v = vdoms.computeIfAbsent(vdom, x -> new Vdom());
                        String key = w.get(1), val = w.size() > 2 ? w.get(2) : "";
                        if (Set.of("central-nat", "ngfw-mode", "inspection-mode", "opmode").contains(key)
                                && Set.of("enable", "disable", "profile-based", "policy-based", "flow", "proxy", "nat", "transparent").contains(val))
                            v.modes.put(key.replace('-', '_'), val.replace('-', '_'));
                    }
                }
            }
        }
        Map<String, String> vdomOrdinal = new LinkedHashMap<>();
        int n = 0;
        for (String name : vdoms.keySet()) vdomOrdinal.put(name, name.equals("global") ? "global" : "v" + (n++));
        emit("fgt", dev, "all", "lines_total", total);
        emit("fgt", dev, "all", "multi_vdom", multi ? 1 : 0);
        for (var e : vdoms.entrySet()) {
            Vdom v = e.getValue();
            String vo = vdomOrdinal.get(e.getKey());
            Set<String> definedSvc = new HashSet<>(v.services);
            long undefined = v.policyServiceRefs.stream().filter(s -> !definedSvc.contains(s)).count();
            v.c.put("services_referenced_undefined", undefined);
            v.c.put("services_referenced_undefined_all", v.policyServiceRefs.stream().filter(s -> s.equals("ALL") && !definedSvc.contains(s)).count());
            v.c.put("vip_in_dstaddr", v.policyDstRefs.stream().filter(v.vips::contains).count());
            v.c.put("policy_order_vs_id", -1L);
            boolean asc = true;
            for (int i = 1; i < v.policyIds.size(); i++) if (v.policyIds.get(i) <= v.policyIds.get(i - 1)) asc = false;
            String order = v.policyIds.isEmpty() ? "empty" : asc ? "ascending" : "not_ascending";
            v.c.remove("policy_order_vs_id");
            int[] dc = depth(v.groupEdges);
            v.c.put("addrgrp_nesting_max_depth", (long) dc[0]);
            v.c.put("addrgrp_cycles", (long) dc[1]);
            for (var c : v.c.entrySet()) emit("fgt", dev, vo, c.getKey().replace('-', '_'), c.getValue());
            emitS("fgt", dev, vo, "policy_order_vs_id", order);
            for (var m : v.modes.entrySet()) emitS("fgt", dev, vo, "mode_" + m.getKey(), m.getValue());
        }
        return sha(relevant.toString());
    }

    static void finishEntry(Vdom v, String table, String name, Map<String, String> e, Map<String, List<String>> m, StringBuilder relevant) {
        if (table == null) return;
        Map<String, Long> c = v.c;
        switch (table) {
            case "firewall policy" -> {
                inc(c, "policies");
                relevant.append("policy ").append(name).append(' ').append(new TreeMap<>(m)).append('\n');
                try { v.policyIds.add(Long.parseLong(name)); } catch (NumberFormatException x) { inc(c, "policy_id_not_numeric"); }
                String action = e.get("action");
                inc(c, "action_" + (action == null ? "absent" : kw(action, Set.of("accept", "deny", "ipsec"))));
                if ("disable".equals(e.get("status"))) inc(c, "status_disable");
                for (String neg : List.of("srcaddr-negate", "dstaddr-negate", "service-negate"))
                    if ("enable".equals(e.get(neg))) inc(c, neg.replace('-', '_'));
                for (String intf : List.of("srcintf", "dstintf")) {
                    List<String> vals = m.getOrDefault(intf, List.of());
                    if (vals.isEmpty()) inc(c, intf + "_absent");
                    else if (vals.contains("any")) inc(c, intf + "_any");
                    if (vals.size() > 1) inc(c, intf + "_multi");
                }
                if (!m.containsKey("schedule")) inc(c, "schedule_absent");
                else if (!"always".equals(e.get("schedule"))) inc(c, "schedule_not_always");
                if (m.containsKey("groups") || m.containsKey("users") || m.containsKey("fsso-groups")) inc(c, "identity_criteria");
                if ("enable".equals(e.get("internet-service")) || "enable".equals(e.get("internet-service-src"))) inc(c, "internet_service");
                if (m.containsKey("application") || m.containsKey("app-category") || m.containsKey("url-category")) inc(c, "application_criteria");
                if ("enable".equals(e.get("nat"))) inc(c, "nat_enable");
                if ("enable".equals(e.get("ippool"))) inc(c, "ippool_enable");
                if (e.containsKey("match-vip")) inc(c, "match_vip_set_" + kw(e.get("match-vip"), Set.of("enable", "disable")));
                if (e.containsKey("match-vip-only")) inc(c, "match_vip_only_set");
                if (e.containsKey("uuid")) inc(c, "uuid_present");
                if (m.containsKey("srcaddr6") || m.containsKey("dstaddr6")) inc(c, "addr6_cells");
                if (!m.containsKey("srcaddr")) inc(c, "srcaddr_absent");
                if (!m.containsKey("dstaddr")) inc(c, "dstaddr_absent");
                if (!m.containsKey("service")) inc(c, "service_absent");
                if (m.containsKey("vpntunnel") || "ipsec".equals(action)) inc(c, "vpn_tunnel");
                v.policyServiceRefs.addAll(m.getOrDefault("service", List.of()));
                v.policyDstRefs.addAll(m.getOrDefault("dstaddr", List.of()));
            }
            case "firewall address" -> {
                inc(c, "addresses");
                v.addresses.add(name);
                String type = e.get("type");
                inc(c, "address_type_" + (type == null ? "absent" : kw(type, Set.of("ipmask", "iprange", "fqdn", "geography", "wildcard",
                        "dynamic", "interface-subnet", "mac", "route-tag", "wildcard-fqdn")).replace('-', '_')));
                if ((type == null || type.equals("ipmask")) && !e.containsKey("subnet")) inc(c, "ipmask_without_subnet");
                if (e.containsKey("subnet")) {
                    List<String> s = m.get("subnet");
                    if (s.size() == 2) mask(c, s.get(1));
                    else if (s.size() == 1 && s.get(0).contains("/")) inc(c, "subnet_prefix_form");
                }
            }
            case "firewall addrgrp" -> {
                inc(c, "addrgrps");
                v.groups.add(name);
                for (String mem : m.getOrDefault("member", List.of())) v.groupEdges.computeIfAbsent(name, x -> new HashSet<>()).add(mem);
                if (!m.containsKey("member")) inc(c, "addrgrp_empty");
                if ("enable".equals(e.get("exclude"))) inc(c, "addrgrp_exclude");
            }
            case "firewall address6" -> inc(c, "address6");
            case "firewall addrgrp6" -> inc(c, "addrgrp6");
            case "firewall service custom" -> {
                inc(c, "services_custom");
                v.services.add(name);
                String proto = e.get("protocol");
                inc(c, "service_protocol_" + (proto == null ? "absent" : kw(proto, Set.of("TCP/UDP/SCTP", "ICMP", "ICMP6", "IP", "ALL", "HTTP", "FTP", "CONNECT", "SOCKS-TCP", "SOCKS-UDP"))
                        .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_")));
                for (String r : List.of("tcp-portrange", "udp-portrange", "sctp-portrange")) {
                    List<String> vals = m.getOrDefault(r, List.of());
                    if (vals.size() > 1) inc(c, "portrange_multiple");
                    for (String x : vals) {
                        String form = x.matches("\\d+") ? "dst" : x.matches("\\d+-\\d+") ? "dst_range"
                                : x.matches("\\d+(-\\d+)?:\\d+(-\\d+)?") ? "dst_src" : "other";
                        inc(c, "portrange_form_" + form);
                    }
                }
                if (e.containsKey("protocol-number")) inc(c, "service_protocol_number");
                if (e.containsKey("icmptype")) inc(c, "service_icmptype");
                if (e.containsKey("icmpcode")) inc(c, "service_icmpcode");
                if (e.containsKey("iprange") || e.containsKey("fqdn")) inc(c, "service_with_address");
            }
            case "firewall service group" -> { inc(c, "service_groups"); v.services.add(name); }
            case "firewall vip" -> {
                inc(c, "vips");
                v.vips.add(name);
                if ("enable".equals(e.get("portforward"))) inc(c, "vip_portforward");
                if (e.containsKey("type")) inc(c, "vip_type_" + kw(e.get("type"), Set.of("static-nat", "load-balance", "server-load-balance", "dns-translation", "fqdn", "access-proxy")).replace('-', '_'));
                if (m.getOrDefault("mappedip", List.of()).size() > 1) inc(c, "vip_mappedip_multi");
                if (e.containsKey("extintf") && !"any".equals(e.get("extintf"))) inc(c, "vip_extintf_set");
                if ("enable".equals(e.get("nat-source-vip"))) inc(c, "vip_nat_source_vip");
            }
            case "firewall vipgrp" -> { inc(c, "vipgrps"); v.vips.add(name); }
            case "firewall ippool" -> inc(c, "ippools");
            case "firewall central-snat-map" -> inc(c, "central_snat_rules");
            case "system zone" -> { inc(c, "zones"); if ("allow".equals(e.get("intrazone"))) inc(c, "zone_intrazone_allow"); }
            case "firewall local-in-policy" -> inc(c, "local_in_policies");
            case "firewall proxy-policy" -> inc(c, "proxy_policies");
            case "firewall security-policy" -> inc(c, "security_policies");
            case "firewall multicast-policy" -> inc(c, "multicast_policies");
            case "firewall dos-policy" -> inc(c, "dos_policies");
            case "firewall policy6", "firewall policy46", "firewall policy64" -> inc(c, "legacy_policy_tables_entries");
            case "firewall schedule recurring", "firewall schedule onetime" -> inc(c, "schedules");
            default -> { }
        }
    }

    static boolean insideVdom(Deque<String> stack) {
        return "config vdom".equals(stack.peekLast()) && stack.size() >= 2;
    }

    static String outerSection(Deque<String> stack) {
        List<String> l = new ArrayList<>(stack);
        java.util.Collections.reverse(l); // outermost first
        for (String s : l) {
            if (s.equals("config vdom") || s.equals("config global") || s.startsWith("edit ")) continue;
            return s.substring(7);
        }
        return null;
    }

    static String outerSectionAfterPop(Deque<String> stack) {
        return stack.isEmpty() ? null : outerSection(stack);
    }

    static List<String> tokens(String t) {
        List<String> r = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false, any = false;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '\\' && i + 1 < t.length()) { cur.append(t.charAt(++i)); any = true; continue; }
            if (ch == '"') { q = !q; any = true; continue; }
            if (!q && Character.isWhitespace(ch)) { if (any) { r.add(cur.toString()); cur.setLength(0); any = false; } continue; }
            cur.append(ch); any = true;
        }
        if (any) r.add(cur.toString());
        return r;
    }

    static int quotes(String line) {
        int n = 0;
        for (int i = 0; i < line.length(); i++) { if (line.charAt(i) == '\\') i++; else if (line.charAt(i) == '"') n++; }
        return n;
    }

    // ------------------------------------------------------------------ helpers

    static String slug(String s) {
        String x = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return SAFE.matcher(x).matches() && x.length() <= 48 ? x : "other";
    }

    static String kw(String v, Set<String> allowed) {
        return v != null && allowed.contains(v) ? v : "other";
    }

    static void inc(Map<String, Long> c, String k) {
        c.merge(k, 1L, Long::sum);
    }

    static void emit(String vendor, String dev, String vdom, String key, long value) {
        emitS(vendor, dev, vdom, key, Long.toString(value));
    }

    static void emitS(String vendor, String dev, String vdom, String key, String value) {
        String line = vendor + "\t" + dev + "\t" + vdom + "\t" + key + "\t" + value;
        for (String f : line.split("\t")) if (!SAFE.matcher(f).matches()) { out.println("unsafe_field_dropped"); return; }
        out.println(line);
    }

    static String sha(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02x", x));
        return b.toString();
    }
}
