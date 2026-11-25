package nexusmeasure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.device.PanVsysZoneSet;
import com.securityexpert.nexus.ui2.platform.SecretFile;
import com.securityexpert.nexus.ui2.policy.CpObjectInventory;
import com.securityexpert.nexus.ui2.policy.CpObjectTypes;
import com.securityexpert.nexus.ui2.policy.PanoramaPolicyMapper;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Metadata;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Section;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Target;
import com.securityexpert.nexus.ui2.service.policy.PolicyHygiene;

/**
 * Counts-only, in-pod measurement (backlog policy_collection_gaps): after the three gap fixes (D-1 gateway bound, App-ID
 * {@code defaultPortsHold}, {@code ZoneUniverse}), how many rules does the real {@code PolicyHygiene} engine decide or
 * leave UNKNOWN per group, and why do stored Check Point gateway objects lack {@code gateway-addresses: complete}.
 *
 * <p>Reads only stored data over a READ ONLY connection (policy_snapshot, pan_vsys_zone_set, the gateways-and-servers
 * rows of cp_policy_object_inventory and their chunks). No device command, no write. Runs the engine twice per stored
 * snapshot with the stored zone sets joined exactly as {@code PolicyQueryService.zoneUniverse} does: the sound analysis
 * ({@code analyze}, what the policy screen shows) and the legacy upper-bound analysis ({@code analyzeLegacy}'s view with
 * the zone universe, where {@code defaultPortsHold} acts). The Check Point domain-inventory merge of
 * {@code PolicyQueryService.breadthObjects} is not applied: it only feeds the permissiveness verdict, never shadowing.
 *
 * <p>Every output field matches {@code [a-z0-9_.]+}: ordinals ({@code p01}..), vendor and mode tokens, fixed counter and
 * reason slugs, integers. No policy id, device id, uid, name, address, zone name or exception text can reach the output;
 * a failure prints only {@code fatal<TAB><lowercase exception class>}.
 *
 * <p>{@code --selftest} runs inline synthetic snapshots and inventory nodes and needs no database and no pod.
 * The engine classes are package-private, so they are reached by reflection (the harness is compiled against the
 * service classes and loads beside them).
 */
public final class GapEngineMeasure {
    static final Pattern SAFE = Pattern.compile("[a-z0-9_.]+");
    static final Pattern SAFE_LINE = Pattern.compile("[a-z0-9_.]+(\t[a-z0-9_.]+)*");
    static final int DAYS = 90, TOP = 10;
    static final String P = "com.securityexpert.nexus.ui2.service.policy.";
    /** The engine's reason texts this harness attributes by; the self-test proves the engine still produces them. */
    static final String DEFAULT_PORTS = "application default ports are unknown", ZONE_UNPROVEN = "zone or install-on coverage is unproven",
            MARKER = "gateway-addresses: complete";
    static final List<String> MODES = List.of("sound", "legacy");
    static final List<String> ENGINE_COUNTERS = List.of("rules_evaluated", "not_shadowed", "redundant", "conflict", "unknown_total",
            "gateway_own_usable", "gateway_own_unmarked", "gateway_blocker_usable", "gateway_blocker_unmarked", "gateway_any",
            "app_default_ports", "zone_list_universe_known", "zone_list_universe_unknown", "other_unknown", "unknown_attributed");
    /** The closed completeness vocabulary: the first eight mirror CpObjectInventoryParser.gatewayAddresses, the last two are stored-data only. */
    static final String COMPLETE = "complete", NOT_IN_INVENTORY = "not_in_inventory_or_stale", MAIN_IPV4 = "missing_or_invalid_main_ipv4",
            NO_INTERFACES = "missing_interfaces", INTERFACE_IP = "interface_missing_ip", CLUSTER_MEMBERS = "cluster_missing_members",
            MEMBER_IP = "member_missing_ip_or_interfaces", EMPTY_KEPT = "empty_kept_addresses",
            NOT_ATTRIBUTABLE = "incomplete_not_attributable", MARKER_UNUSABLE = "marker_present_unusable";
    static final List<String> GATEWAY_REASONS = List.of(COMPLETE, NOT_IN_INVENTORY, MAIN_IPV4, NO_INTERFACES, INTERFACE_IP,
            CLUSTER_MEMBERS, MEMBER_IP, EMPTY_KEPT, NOT_ATTRIBUTABLE, MARKER_UNUSABLE);
    static final Set<String> CLUSTER_TYPES = Set.of("simple-cluster", "CpmiGatewayCluster");
    static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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
        silence();
        Path dir = Path.of(args.length > 0 ? args[0] : ".");
        out = new PrintWriter(Files.newBufferedWriter(dir.resolve("measure.out")), true);
        try {
            Report report = new Report();
            run(report);
            emitReport(report);
            out.println("done");
        } catch (Throwable t) {
            String name = t.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            out.println("fatal\t" + (SAFE.matcher(name).matches() ? name : "other"));
        }
        out.close();
    }

    static void silence() {
        try {
            java.util.logging.LogManager.getLogManager().reset();
        } catch (Throwable ignored) {
            // best effort: library logging must not reach the output
        }
        try {
            Object root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            Class<?> level = Class.forName("ch.qos.logback.classic.Level");
            root.getClass().getMethod("setLevel", level).invoke(root, level.getField("OFF").get(null));
        } catch (Throwable ignored) {
            // best effort
        }
    }

    // ---- engine access (package-private classes, by reflection) ----

    static final class Engine {
        static final Method SOUND, LEGACY, VIEW, UNIVERSE, KNOWN;
        static final Object UNKNOWN_UNIVERSE;

        static {
            try {
                Class<?> hygiene = Class.forName(P + "PolicyHygiene");
                Class<?> zones = Class.forName(P + "ZoneUniverse");
                Class<?> view = Class.forName(P + "PolicyLegacyView");
                SOUND = hygiene.getDeclaredMethod("analyze", PolicySnapshot.class, int.class, Instant.class, int.class, Map.class, zones);
                LEGACY = hygiene.getDeclaredMethod("analyze", PolicySnapshot.class, int.class, Instant.class, int.class, int.class,
                        Map.class, boolean.class, boolean.class, zones);
                VIEW = view.getDeclaredMethod("of", PolicySnapshot.class);
                UNIVERSE = zones.getDeclaredMethod("of", PolicySnapshot.class, Map.class, Instant.class);
                KNOWN = zones.getDeclaredMethod("known");
                java.lang.reflect.Field unknown = zones.getDeclaredField("UNKNOWN");
                for (var m : List.of(SOUND, LEGACY, VIEW, UNIVERSE, KNOWN)) {
                    m.setAccessible(true);
                }
                unknown.setAccessible(true);
                UNKNOWN_UNIVERSE = unknown.get(null);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        static Object call(Method m, Object... args) throws Exception {
            try {
                return m.invoke(null, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() instanceof Exception ex ? ex : e;
            }
        }
    }

    // ---- per-snapshot engine counters ----

    /** Counters of one snapshot: keys {@code <mode>.<counter>}, {@code <mode>.reason.<slug>} and {@code snap.*}. */
    static Map<String, Long> measure(PolicySnapshot s, Map<String, Map<String, PanVsysZoneSet>> zoneSets, Instant now)
            throws Exception {
        boolean pan = "PAN".equals(s.metadata().vendor());
        Object zones = pan ? Engine.call(Engine.UNIVERSE, s, zoneSets, now) : Engine.UNKNOWN_UNIVERSE;
        boolean known = pan && (Boolean) Engine.KNOWN.invoke(zones);
        Map<String, Long> c = new TreeMap<>();
        if (pan) {
            add(c, known ? "snap.zone_universe_known" : "snap.zone_universe_unknown", 1);
        }
        var sound = (PolicyHygiene.Result) Engine.call(Engine.SOUND, s, DAYS, now, PolicyHygiene.TOTAL_WORK, s.objects(), zones);
        tally(c, "sound", s, sound, pan, known);
        var view = (PolicySnapshot) Engine.call(Engine.VIEW, s);
        var legacy = (PolicyHygiene.Result) Engine.call(Engine.LEGACY, view, DAYS, now, PolicyHygiene.TOTAL_WORK,
                PolicyHygiene.RULE_WORK, view.objects(), true, true, zones);
        tally(c, "legacy", s, legacy, pan, known);
        return c;
    }

    static void add(Map<String, Long> c, String key, long n) {
        c.merge(key, n, Long::sum);
    }

    static boolean security(PolicySnapshot s, Section section) {
        return !PolicySnapshot.outsideSecurityEvaluation(s.metadata(), section);
    }

    static long securityRules(PolicySnapshot s) {
        long n = 0;
        for (Section section : s.sections()) {
            if (security(s, section)) {
                n += section.rules().size();
            }
        }
        return n;
    }

    static void tally(Map<String, Long> c, String mode, PolicySnapshot s, PolicyHygiene.Result result, boolean pan, boolean known) {
        Map<String, Rule> rules = new HashMap<>();
        for (Section section : s.sections()) {
            if (security(s, section)) {
                for (Rule r : section.rules()) {
                    rules.put(r.id(), r);
                }
            }
        }
        for (Rule rule : rules.values()) {
            var a = result.rules().get(rule.id());
            add(c, mode + ".rules_evaluated", 1);
            if (a == null) {
                add(c, mode + ".unknown_total", 1);
                add(c, mode + ".other_unknown", 1);
                add(c, mode + ".reason.none", 1);
                continue;
            }
            switch (a.shadowStatus()) {
                case "NOT_SHADOWED" -> add(c, mode + ".not_shadowed", 1);
                case "REDUNDANT" -> add(c, mode + ".redundant", 1);
                case "CONFLICT" -> add(c, mode + ".conflict", 1);
                default -> {
                    add(c, mode + ".unknown_total", 1);
                    add(c, mode + ".reason." + reasonSlug(a.shadowReason()), 1);
                    int own = gatewayClass(s, rule);
                    Rule blocker = a.blockingRuleId() == null ? null : rules.get(a.blockingRuleId());
                    int blk = blocker == null ? 0 : gatewayClass(s, blocker);
                    boolean tagged = false;
                    if (own != 0) {
                        add(c, mode + (own == 1 ? ".gateway_own_usable" : ".gateway_own_unmarked"), 1);
                    }
                    if (blk != 0) {
                        add(c, mode + (blk == 1 ? ".gateway_blocker_usable" : ".gateway_blocker_unmarked"), 1);
                    }
                    if (own != 0 || blk != 0) {
                        add(c, mode + ".gateway_any", 1);
                        tagged = true;
                    }
                    if (DEFAULT_PORTS.equals(a.shadowReason())) {
                        add(c, mode + ".app_default_ports", 1);
                        tagged = true;
                    }
                    if (pan && ZONE_UNPROVEN.equals(a.shadowReason())) {
                        add(c, mode + (known ? ".zone_list_universe_known" : ".zone_list_universe_unknown"), 1);
                        tagged = true;
                    }
                    add(c, mode + (tagged ? ".unknown_attributed" : ".other_unknown"), 1);
                }
            }
        }
    }

    /** The closed slug of an engine reason; anything outside the engine's public reason set is {@code other}. */
    static String reasonSlug(String reason) {
        if (reason == null) {
            return "none";
        }
        if (!PolicyHygiene.REASONS.contains(reason)) {
            return "other";
        }
        String slug = reason.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return SAFE.matcher(slug).matches() ? slug : "other";
    }

    /** 0: the rule names no gateway-class object in source or destination; 1: every one it names is usable; 2: some is not. */
    static int gatewayClass(PolicySnapshot s, Rule rule) {
        List<PolicyObject> gateways = new ArrayList<>();
        for (Cell cell : List.of(rule.source(), rule.destination())) {
            for (String id : cell.refs()) {
                collectGateways(s, id, gateways, new HashSet<>(), 0);
            }
        }
        if (gateways.isEmpty()) {
            return 0;
        }
        return gateways.stream().allMatch(GapEngineMeasure::usable) ? 1 : 2;
    }

    static void collectGateways(PolicySnapshot s, String id, List<PolicyObject> out, Set<String> seen, int depth) {
        PolicyObject o = s.objects().get(id);
        if (o == null || depth > 32 || !seen.add(id)) {
            return;
        }
        if ("gateway".equals(o.type())) {
            out.add(o);
            return;
        }
        for (String member : o.members()) {
            collectGateways(s, member, out, seen, depth + 1);
        }
    }

    /** What the engine's D-1 bound needs: RESOLVED, no members, the completeness marker and at least one IPv4 value. */
    static boolean usable(PolicyObject o) {
        return "RESOLVED".equals(o.status()) && o.members().isEmpty() && o.values().contains(MARKER)
                && o.values().stream().anyMatch(v -> v.startsWith("gateway-ipv4: ") || v.startsWith("ipv4-address: "));
    }

    // ---- gateway completeness ----

    /** Native Check Point gateway type to the closed slug of the report. */
    static String typeSlug(String type) {
        return switch (type) {
            case "simple-gateway" -> "simple_gateway";
            case "simple-cluster" -> "simple_cluster";
            case "CpmiGatewayCluster" -> "cpmi_gateway_cluster";
            case "CpmiVsxNetobj" -> "vsx_gateway";
            case "CpmiVsxClusterNetobj" -> "vsx_cluster";
            case "CpmiVsClusterNetobj" -> "vs_cluster_netobj";
            default -> "other";
        };
    }

    /**
     * The first reason, in this fixed order, that keeps a raw {@code show-gateways-and-servers} object from being complete;
     * {@code complete} when none does. Mirrors CpObjectInventoryParser.gatewayAddresses condition by condition (the
     * self-test compares the complete/incomplete outcome with the parser itself). A main address that is merely absent is
     * not a failure in the parser, so {@code missing_or_invalid_main_ipv4} means present and not a valid IPv4 text.
     * Only the collector sees the raw object; the stored projection cannot be classified this way ({@link #storedReason}).
     */
    static String rawReason(JsonNode node, String type) {
        Set<String> kept = new LinkedHashSet<>();
        if (!address(node.path("ipv4-address"), kept)) {
            return MAIN_IPV4;
        }
        JsonNode interfaces = node.path("interfaces");
        if (!interfaces.isArray() || interfaces.size() == 0) {
            return NO_INTERFACES;
        }
        if (!interfacesComplete(interfaces, kept)) {
            return INTERFACE_IP;
        }
        JsonNode members = node.path("cluster-members");
        if (CLUSTER_TYPES.contains(type) && !(members.isArray() && members.size() > 0)) {
            return CLUSTER_MEMBERS;
        }
        if (!members.isMissingNode() && !members.isNull()) {
            if (!members.isArray()) {
                return MEMBER_IP;
            }
            for (JsonNode member : members) {
                if (!member.isObject()) {
                    return MEMBER_IP;
                }
                boolean ip = address(member.path("ip-address"), kept) & address(member.path("ipv4-address"), kept);
                boolean any = member.path("ip-address").isTextual() || member.path("ipv4-address").isTextual();
                boolean listed = member.path("interfaces").isArray() && member.path("interfaces").size() > 0;
                if (!ip || !any && !listed || listed && !interfacesComplete(member.path("interfaces"), kept)) {
                    return MEMBER_IP;
                }
            }
        }
        return kept.isEmpty() ? EMPTY_KEPT : COMPLETE;
    }

    private static boolean address(JsonNode value, Set<String> kept) {
        if (value.isMissingNode() || value.isNull()) {
            return true;
        }
        if (!value.isTextual() || !validIpv4(value.textValue())) {
            return false;
        }
        kept.add(value.textValue());
        return true;
    }

    private static boolean interfacesComplete(JsonNode list, Set<String> kept) {
        if (!list.isArray() || list.size() == 0) {
            return false;
        }
        boolean complete = true;
        for (JsonNode iface : list) {
            if (!iface.isObject()) {
                complete = false;
                continue;
            }
            boolean v4 = iface.path("ipv4-address").isTextual(), v6 = iface.path("ipv6-address").isTextual();
            if (!address(iface.path("ipv4-address"), kept) || !v4 && !v6) {
                complete = false;
            }
        }
        return complete;
    }

    static boolean validIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
                return false;
            }
            if (part.length() > 1 && part.startsWith("0") || Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /** A stored gateway-class inventory item: its native type slug and the reason its stored projection gives. */
    record GwInfo(String slug, String reason) {
    }

    /**
     * What the stored inventory item can say (the raw object is never persisted): {@code complete} (marker true with an
     * address), {@code not_in_inventory_or_stale} (no gateway projection stored), {@code missing_or_invalid_main_ipv4}
     * (the stored main address text is not a valid IPv4), {@code empty_kept_addresses} (no address kept), else
     * {@code incomplete_not_attributable} (some address kept but the stored projection keeps no interface or member
     * structure to say which part failed). Null for an item that is not gateway-class.
     */
    static GwInfo storedInfo(JsonNode item) {
        String type = item.path("type").asText("");
        if (!CpObjectTypes.GATEWAY_TYPES.contains(type)) {
            return null;
        }
        String slug = typeSlug(type);
        JsonNode gateway = item.path("gateway");
        if (!gateway.isObject()) {
            return new GwInfo(slug, NOT_IN_INVENTORY);
        }
        boolean complete = gateway.path("complete").asBoolean(false);
        int addresses = gateway.path("ipv4").size();
        if (complete) {
            return new GwInfo(slug, addresses > 0 ? COMPLETE : EMPTY_KEPT);
        }
        for (JsonNode value : item.path("values")) {
            String text = value.asText("");
            if (text.startsWith("ipv4-address: ") && !validIpv4(text.substring("ipv4-address: ".length()))) {
                return new GwInfo(slug, MAIN_IPV4);
            }
        }
        return new GwInfo(slug, addresses == 0 ? EMPTY_KEPT : NOT_ATTRIBUTABLE);
    }

    /** Streams the items of one stored inventory document ({"objects":[...]}) without materialising the array. */
    static void readInventory(Reader reader, Consumer<JsonNode> consume) throws IOException {
        try (JsonParser parser = MAPPER.getFactory().createParser(reader)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("invalid");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();
                if (!"objects".equals(field)) {
                    parser.skipChildren();
                    continue;
                }
                if (parser.currentToken() != JsonToken.START_ARRAY) {
                    throw new IllegalStateException("invalid");
                }
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    consume.accept(MAPPER.readTree(parser));
                }
            }
        }
    }

    // ---- the report ----

    record Done(String vendor, long securityRules, Map<String, Long> counters) {
    }

    static final class Report {
        final List<Done> dones = new ArrayList<>();
        final Map<String, Long> scope = new TreeMap<>();
        /** key: population.slug, then reason (tab separated). */
        final Map<String, Long> gateway = new TreeMap<>();

        void scope(String key, long n) {
            scope.merge(key, n, Long::sum);
        }

        void gateway(String population, String slug, String reason) {
            gateway.merge(population + "." + slug + "\t" + reason, 1L, Long::sum);
        }
    }

    static String vendorSlug(String vendor) {
        return "CP".equals(vendor) ? "cp" : "PAN".equals(vendor) ? "pan" : "other";
    }

    /** Measures one parsed snapshot into the report; a failed analysis only counts. */
    static void account(Report report, PolicySnapshot s, Map<String, Map<String, PanVsysZoneSet>> zoneSets,
            Map<String, Map<String, GwInfo>> inventory, Instant now) {
        String vendor = vendorSlug(s.metadata().vendor());
        if (vendor.equals("cp")) {
            gatewayCensus(report, s, inventory);
        }
        if (!s.failures().isEmpty()) {
            report.scope("snapshots_with_failures_skipped", 1);
            return;
        }
        try {
            Map<String, Long> counters = measure(s, zoneSets, now);
            report.dones.add(new Done(vendor, securityRules(s), counters));
            report.scope("snapshots_analysed", 1);
            report.scope("snapshots_analysed_" + vendor, 1);
            for (String k : List.of("snap.zone_universe_known", "snap.zone_universe_unknown")) {
                if (counters.containsKey(k)) {
                    report.scope("pan_" + k.substring("snap.".length()) + "_snapshots", counters.get(k));
                }
            }
        } catch (Exception | StackOverflowError failed) {
            report.scope("snapshots_analysis_failed", 1);
        }
    }

    static String inventoryKey(PolicySnapshot s) {
        return s.metadata().sourceId() + "\u0000" + s.metadata().containerId();
    }

    /** The gateway-class objects a stored Check Point snapshot carries, by why each does or does not carry the marker. */
    static void gatewayCensus(Report report, PolicySnapshot s, Map<String, Map<String, GwInfo>> inventory) {
        Map<String, GwInfo> byRef = new HashMap<>();
        for (var e : inventory.getOrDefault(inventoryKey(s), Map.of()).entrySet()) {
            byRef.put(PolicySnapshot.ref(s.metadata().id(), "object", e.getKey()), e.getValue());
        }
        boolean any = false;
        for (PolicyObject o : s.objects().values()) {
            if (!"gateway".equals(o.type())) {
                continue;
            }
            any = true;
            GwInfo info = byRef.get(o.id());
            String slug = info == null ? "other" : info.slug();
            String reason;
            if (usable(o)) {
                reason = COMPLETE;
            } else if (o.values().contains(MARKER)) {
                reason = MARKER_UNUSABLE;
            } else if (info == null || COMPLETE.equals(info.reason())) {
                // No inventory row, or a complete row the snapshot did not carry: the snapshot is older than the inventory.
                reason = NOT_IN_INVENTORY;
            } else {
                reason = info.reason();
            }
            report.gateway("snapshot", slug, reason);
        }
        report.scope("cp_snapshots_gateway_census", 1);
        if (any) {
            report.scope("cp_snapshots_with_gateway_objects", 1);
        }
    }

    static String id(String... parts) {
        return String.join(".", parts);
    }

    static void emitReport(Report report) {
        report.scope.forEach((k, v) -> emit("scope", "fleet", k, v));
        List<Done> ranked = new ArrayList<>(report.dones);
        ranked.sort(Comparator.comparingLong((Done d) -> -d.securityRules()));
        for (int i = 0; i < Math.min(TOP, ranked.size()); i++) {
            String label = String.format("p%02d", i + 1);
            Done d = ranked.get(i);
            emitText("package", label, "vendor", d.vendor());
            emit("package", label, "security_rules", d.securityRules());
            emitEngine(label, d.counters());
        }
        Set<String> vendors = new TreeSet<>();
        report.dones.forEach(d -> vendors.add(d.vendor()));
        Map<String, Long> all = new TreeMap<>();
        for (String vendor : vendors) {
            Map<String, Long> sum = new TreeMap<>();
            report.dones.stream().filter(d -> d.vendor().equals(vendor)).forEach(d -> d.counters().forEach((k, v) -> sum.merge(k, v, Long::sum)));
            emitEngine(id("fleet", vendor), sum);
            sum.forEach((k, v) -> all.merge(k, v, Long::sum));
        }
        emitEngine(id("fleet", "all"), all);
        Map<String, Map<String, Long>> byPopulation = new TreeMap<>();
        Map<String, Map<String, Long>> totals = new TreeMap<>();
        for (var e : report.gateway.entrySet()) {
            String[] key = e.getKey().split("\t", -1);
            emit("gateway", key[0], key[1], e.getValue());
            String population = key[0].substring(0, key[0].indexOf('.'));
            totals.computeIfAbsent(population, k -> new TreeMap<>()).merge(key[1], e.getValue(), Long::sum);
        }
        for (String population : List.of("snapshot", "inventory")) {
            Map<String, Long> t = totals.getOrDefault(population, Map.of());
            long total = 0;
            for (String reason : GATEWAY_REASONS) {
                emit("gateway", population + ".all", reason, t.getOrDefault(reason, 0L));
                total += t.getOrDefault(reason, 0L);
            }
            emit("gateway", population + ".all", "total", total);
        }
    }

    static void emitEngine(String prefix, Map<String, Long> c) {
        for (String mode : MODES) {
            for (String counter : ENGINE_COUNTERS) {
                emit("engine", id(prefix, mode), counter, c.getOrDefault(mode + "." + counter, 0L));
            }
            String start = mode + ".reason.";
            c.forEach((k, v) -> {
                if (k.startsWith(start)) {
                    emit("reason", id(prefix, mode), k.substring(start.length()), v);
                }
            });
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

    // ---- database (read only) ----

    static Connection connect() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_USER_FILE")), "db_user").strip());
        props.setProperty("password", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_PASSWORD_FILE")), "db_password").strip());
        props.setProperty("ApplicationName", "gap-engine-measure-readonly");
        Connection conn = new org.postgresql.Driver().connect(System.getenv("UI2_DB_URL"), props);
        conn.setAutoCommit(false);
        conn.setReadOnly(true);
        try (Statement st = conn.createStatement()) {
            st.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
            st.execute("SET statement_timeout = '120s'");
        }
        return conn;
    }

    static void run(Report report) throws Exception {
        Map<String, Map<String, PanVsysZoneSet>> zoneSets = new HashMap<>();
        try (Connection conn = connect()) {
            loadZoneSets(conn, zoneSets, report);
            conn.commit();
        } catch (Exception unreadable) {
            zoneSets.clear();
            report.scope("zone_sets_unreadable", 1);
        }
        Map<String, Map<String, GwInfo>> inventory = new HashMap<>();
        try (Connection conn = connect()) {
            loadInventory(conn, inventory, report);
            conn.commit();
        } catch (Exception unreadable) {
            inventory.clear();
            report.scope("inventory_unreadable", 1);
        }
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "select snapshot::text from policy_snapshot order by policy_id")) {
            ps.setFetchSize(1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    report.scope("snapshots_total", 1);
                    PolicySnapshot s;
                    try {
                        s = MAPPER.readValue(rs.getString(1), PolicySnapshot.class).withPageSplitsMerged().withHitLevels();
                    } catch (Exception bad) {
                        report.scope("snapshots_parse_failed", 1);
                        continue;
                    }
                    account(report, s, zoneSets, inventory, Instant.now());
                }
            }
            conn.commit();
        }
    }

    static void loadZoneSets(Connection conn, Map<String, Map<String, PanVsysZoneSet>> into, Report report) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("select device_id, vsys, zone_names, read_at from pan_vsys_zone_set");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                report.scope("zone_set_rows", 1);
                try {
                    Array names = rs.getArray(3);
                    Timestamp readAt = rs.getTimestamp(4);
                    var row = new PanVsysZoneSet(rs.getString(1), rs.getString(2),
                            new HashSet<>(Arrays.asList((String[]) names.getArray())), readAt.toInstant());
                    into.computeIfAbsent(row.deviceId(), k -> new HashMap<>()).put(row.vsys(), row);
                } catch (RuntimeException invalid) {
                    report.scope("zone_set_rows_invalid", 1);
                }
            }
        }
    }

    record InventoryRow(String source, String domain, String inline, String generation, int chunks) {
    }

    static void loadInventory(Connection conn, Map<String, Map<String, GwInfo>> into, Report report) throws Exception {
        List<InventoryRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("select source_id, domain_ref, "
                + "case when chunk_generation is null then snapshot::text end, chunk_generation, coalesce(chunk_count, 0) "
                + "from cp_policy_object_inventory where object_type = 'gateways-and-servers'");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new InventoryRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)));
            }
        }
        report.scope("inventory_gateway_rows", rows.size());
        for (InventoryRow row : rows) {
            Map<String, GwInfo> byUid = into.computeIfAbsent(row.source() + "\u0000" + row.domain(), k -> new HashMap<>());
            try (Reader reader = row.generation() == null ? new StringReader(row.inline())
                    : new ChunkReader(conn, row.generation(), row.source(), row.domain(), row.chunks())) {
                readInventory(reader, item -> {
                    GwInfo info = storedInfo(item);
                    if (info != null) {
                        byUid.put(item.path("uid").asText(""), info);
                        report.gateway("inventory", info.slug(), info.reason());
                    }
                });
            }
        }
    }

    /** The gzip chunks of one stored inventory generation, read in order (cp_policy_json_chunk, kind INVENTORY). */
    static final class ChunkReader extends Reader {
        private static final int MAX = 1_048_576;
        private final Connection conn;
        private final String generation, source, domain;
        private final int count;
        private int ordinal;
        private Reader current = Reader.nullReader();

        ChunkReader(Connection conn, String generation, String source, String domain, int count) {
            this.conn = conn;
            this.generation = generation;
            this.source = source;
            this.domain = domain;
            this.count = count;
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            while (true) {
                int n = current.read(buffer, offset, length);
                if (n >= 0) {
                    return n;
                }
                current.close();
                if (ordinal >= count) {
                    return -1;
                }
                current = new StringReader(new String(chunk(ordinal++), java.nio.charset.StandardCharsets.UTF_8));
            }
        }

        private byte[] chunk(int page) throws IOException {
            try (PreparedStatement ps = conn.prepareStatement("select payload from cp_policy_json_chunk where generation = ? "
                    + "and source_id = ? and domain_ref = ? and object_type = 'gateways-and-servers' and kind = 'INVENTORY' and ordinal = ?")) {
                ps.setString(1, generation);
                ps.setString(2, source);
                ps.setString(3, domain);
                ps.setInt(4, page);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IOException("missing");
                    }
                    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(rs.getBytes(1)))) {
                        byte[] plain = gzip.readNBytes(MAX + 1);
                        if (plain.length > MAX) {
                            throw new IOException("too large");
                        }
                        return plain;
                    }
                }
            } catch (java.sql.SQLException e) {
                throw new IOException("sql");
            }
        }

        @Override
        public void close() throws IOException {
            current.close();
        }
    }

    // ---- self-test: synthetic snapshots and inventory nodes only; no database, no pod, no device ----

    static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
    static final String ZA = "ZONE-ALPHA-01", ZB = "ZONE-BRAVO-02";

    static Cell cell(String... ids) {
        return new Cell(List.of(ids), false);
    }

    static PolicyObject object(String id, String type, String... values) {
        return new PolicyObject(id, "OBJ-TEST-" + id, type, List.of(), List.of(values), "RESOLVED");
    }

    static PolicySnapshot snapshot(String id, String vendor, List<Target> targets, List<Section> sections, Map<String, PolicyObject> objects) {
        return new PolicySnapshot(new Metadata(id, "s1", "MGR-BRAVO-01", vendor, "c1", "DOM-TANGO-01", "POL-ALPHA-01", NOW.toString(),
                "a1", targets), sections, objects);
    }

    static Rule cpRule(int number, String destination) {
        Map<String, List<String>> extras = Map.of("from", List.of("any"), "to", List.of("any"), "time", List.of("any"),
                "install-on", List.of("any"), "layer-name", List.of("LAYER-ALPHA"));
        return new Rule("r" + number, "uuid-r" + number, number, "OBJ-RULE-" + number, true, cell("any"), cell(destination),
                cell("any"), new Cell(List.of(), false), "Accept", "Log", "", extras);
    }

    static PolicySnapshot cpSnapshot() {
        Map<String, PolicyObject> objects = new HashMap<>();
        objects.put("any", object("any", "any"));
        objects.put("far", object("far", "address", "ipv4-address: 203.0.113.9"));
        objects.put("gw-host", object("gw-host", "address", "ipv4-address: 192.0.2.2"));
        objects.put("other-net", object("other-net", "network", "ip-netmask: 198.51.100.0/24"));
        objects.put("gw", new PolicyObject("gw", "OBJ-GW-1", "gateway", List.of(), List.of("ipv4-address: 192.0.2.1",
                "gateway-ipv4: 192.0.2.1", "gateway-ipv4: 192.0.2.2", MARKER), "RESOLVED"));
        objects.put("gw-unmarked", new PolicyObject("gw-unmarked", "OBJ-GW-2", "gateway", List.of(),
                List.of("ipv4-address: 192.0.2.1", "gateway-ipv4: 192.0.2.1"), "RESOLVED"));
        List<Rule> access = List.of(cpRule(1, "far"), cpRule(2, "gw"), cpRule(3, "gw-host"), cpRule(4, "gw-unmarked"), cpRule(5, "other-net"));
        List<Rule> nat = List.of(cpRule(6, "far"));
        return snapshot("snap-cp-1", "CP", List.of(), List.of(new Section("s1", "OBJ-SECTION-01", "CP access layer", null, access),
                new Section("s2", "OBJ-SECTION-02", "CP NAT rulebase", null, nat)), objects);
    }

    static Rule panRule(int number, String service, String apps, List<String> from, List<String> to) {
        return new Rule("r" + number, "uuid-r" + number, number, "OBJ-RULE-" + number, true, cell("net"), cell("any"), cell(service),
                cell(apps), "allow", "Log", "", Map.of("from", from, "to", to));
    }

    static Map<String, PolicyObject> panObjects(String snapshotId) {
        Map<String, PolicyObject> objects = new HashMap<>();
        objects.put("any", object("any", "any"));
        objects.put("net", object("net", "address", "ip-netmask: 192.0.2.0/24"));
        objects.put("port", object("port", "service", "protocol: tcp", "port: 443"));
        String wide = PolicySnapshot.ref(snapshotId, "service", "wide");
        String appdef = PolicySnapshot.ref(snapshotId, "service", "application-default");
        String app = PolicySnapshot.ref(snapshotId, "application", "APP-ALPHA-01");
        objects.put(wide, object(wide, "service", "protocol/tcp/port: 1-1024"));
        objects.put(appdef, new PolicyObject(appdef, "application-default", "service", List.of(), List.of(), "RESOLVED"));
        objects.put(app, new PolicyObject(app, "APP-ALPHA-01", "unresolved", List.of(), List.of(), "UNRESOLVED"));
        return objects;
    }

    /** A Palo Alto snapshot: an explicit-port rule, then an application-default rule for one predefined application. */
    static PolicySnapshot panAppSnapshot(String id, String catalogueValue) {
        String wide = PolicySnapshot.ref(id, "service", "wide"), appdef = PolicySnapshot.ref(id, "service", "application-default");
        String app = PolicySnapshot.ref(id, "application", "APP-ALPHA-01");
        List<Rule> rules = List.of(panRule(1, wide, app, List.of("any"), List.of("any")), panRule(2, appdef, app, List.of("any"), List.of("any")));
        var base = snapshot(id, "PAN", List.of(), List.of(new Section("s1", "Pre rules", "Shared", null, rules)), panObjects(id));
        return PanoramaPolicyMapper.withPredefinedApplications(base, Map.of("APP-ALPHA-01", List.of(catalogueValue)));
    }

    static PolicySnapshot panZoneSnapshot(String id, String device, boolean mixedLast) {
        List<Rule> rules = new ArrayList<>();
        rules.add(panRule(1, "port", "any", List.of(ZA, ZB), List.of(ZA, ZB)));
        rules.add(mixedLast ? panRule(2, "port", "any", List.of("any", ZA), List.of("any"))
                : panRule(2, "port", "any", List.of("any"), List.of("any")));
        return snapshot(id, "PAN", List.of(new Target(device, "FW-TANGO-01", "vsys1", "IN_SYNC")),
                List.of(new Section("s1", "Pre rules", "Shared", null, rules)), panObjects(id));
    }

    static Map<String, Long> expected(Object... kv) {
        Map<String, Long> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], ((Number) kv[i + 1]).longValue());
        }
        return m;
    }

    /** Null when the actual counters equal the expected ones, else a literal name of the failing check. */
    static String same(String name, Map<String, Long> actual, Map<String, Long> expected) {
        Map<String, Long> nonZero = new TreeMap<>();
        actual.forEach((k, v) -> {
            if (v != 0) {
                nonZero.put(k, v);
            }
        });
        if (System.getProperty("gap.debug") != null) {
            System.err.println(name + " " + nonZero);
        }
        return nonZero.equals(expected) ? null : name;
    }

    static JsonNode json(String text) throws IOException {
        return MAPPER.readTree(text);
    }

    /** Null when every check holds, else the name of the first failing check (a literal from this file). */
    static String selfTest() {
        try {
            return selfTestChecks();
        } catch (Throwable t) {
            if (System.getProperty("gap.debug") != null) {
                t.printStackTrace();
            }
            return "exception_" + t.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        }
    }

    static String selfTestChecks() throws Exception {
        // The engine texts this harness attributes by must still be the engine's own.
        if (!PolicyHygiene.REASONS.contains(DEFAULT_PORTS) || !PolicyHygiene.REASONS.contains(ZONE_UNPROVEN)) {
            return "engine_reason_texts";
        }
        // Check Point: a usable gateway rule and the rule it blocks, an unmarked one and the rule it blocks, a NAT rule left out.
        Map<String, Long> cpExpected = expected("rules_evaluated", 5, "not_shadowed", 1, "unknown_total", 4, "gateway_own_usable", 1,
                "gateway_own_unmarked", 1, "gateway_blocker_usable", 1, "gateway_blocker_unmarked", 1, "gateway_any", 4,
                "unknown_attributed", 4, "reason.dynamic_or_unsupported_object_type", 2,
                "reason.earlier_rule_has_unsupported_constraints", 2);
        Map<String, Long> cp = measure(cpSnapshot(), Map.of(), NOW);
        for (String mode : MODES) {
            Map<String, Long> forMode = new TreeMap<>();
            String prefix = mode + ".";
            cp.forEach((k, v) -> {
                if (k.startsWith(prefix)) {
                    forMode.put(k.substring(prefix.length()), v);
                }
            });
            String bad = same("cp_" + mode, forMode, cpExpected);
            if (bad != null) {
                return bad;
            }
        }
        // Palo Alto application default ports: known ports decide the pair in the legacy analysis only; UNPARSED ports decide nothing.
        Map<String, Long> known = measure(panAppSnapshot("snap-pan-app-1", "default/port: tcp/443"), Map.of(), NOW);
        if (same("pan_app_known", known, expected("sound.rules_evaluated", 2, "sound.not_shadowed", 1, "sound.unknown_total", 1,
                "sound.app_default_ports", 1, "sound.unknown_attributed", 1, "sound.reason.application_default_ports_are_unknown", 1,
                "legacy.rules_evaluated", 2, "legacy.not_shadowed", 1, "legacy.redundant", 1, "snap.zone_universe_unknown", 1)) != null) {
            return "pan_app_known";
        }
        Map<String, Long> unparsed = measure(panAppSnapshot("snap-pan-app-2", "default/port: UNPARSED"), Map.of(), NOW);
        Map<String, Long> unparsedExpected = expected("sound.rules_evaluated", 2, "sound.not_shadowed", 1, "sound.unknown_total", 1,
                "sound.app_default_ports", 1, "sound.unknown_attributed", 1, "sound.reason.application_default_ports_are_unknown", 1,
                "legacy.rules_evaluated", 2, "legacy.not_shadowed", 1, "legacy.unknown_total", 1, "legacy.app_default_ports", 1,
                "legacy.unknown_attributed", 1, "legacy.reason.application_default_ports_are_unknown", 1,
                "snap.zone_universe_unknown", 1);
        if (same("pan_app_unparsed", unparsed, unparsedExpected) != null) {
            return "pan_app_unparsed";
        }
        // Palo Alto zone lists: a lone any decided against a known universe, undecided against an unknown one.
        Map<String, Map<String, PanVsysZoneSet>> stored = Map.of("dev1", Map.of("vsys1",
                new PanVsysZoneSet("dev1", "vsys1", Set.of(ZA, ZB), NOW.minus(Duration.ofHours(1)))));
        Map<String, Long> zoneKnown = measure(panZoneSnapshot("snap-pan-zone-1", "dev1", false), stored, NOW);
        if (same("pan_zone_known", zoneKnown, expected("sound.rules_evaluated", 2, "sound.not_shadowed", 1, "sound.redundant", 1,
                "legacy.rules_evaluated", 2, "legacy.not_shadowed", 1, "legacy.redundant", 1, "snap.zone_universe_known", 1)) != null) {
            return "pan_zone_known";
        }
        Map<String, Long> zoneMixed = measure(panZoneSnapshot("snap-pan-zone-2", "dev1", true), stored, NOW);
        Map<String, Long> mixedExpected = expected("snap.zone_universe_known", 1);
        for (String mode : MODES) {
            mixedExpected.putAll(expected(mode + ".rules_evaluated", 2, mode + ".not_shadowed", 1, mode + ".unknown_total", 1,
                    mode + ".zone_list_universe_known", 1, mode + ".unknown_attributed", 1,
                    mode + ".reason.zone_or_install_on_coverage_is_unproven", 1));
        }
        if (same("pan_zone_mixed", zoneMixed, mixedExpected) != null) {
            return "pan_zone_mixed";
        }
        Map<String, Long> zoneUnknown = measure(panZoneSnapshot("snap-pan-zone-3", "dev2", false), stored, NOW);
        Map<String, Long> unknownExpected = expected("snap.zone_universe_unknown", 1);
        for (String mode : MODES) {
            unknownExpected.putAll(expected(mode + ".rules_evaluated", 2, mode + ".not_shadowed", 1, mode + ".unknown_total", 1,
                    mode + ".zone_list_universe_unknown", 1, mode + ".unknown_attributed", 1,
                    mode + ".reason.zone_or_install_on_coverage_is_unproven", 1));
        }
        if (same("pan_zone_unknown", zoneUnknown, unknownExpected) != null) {
            return "pan_zone_unknown";
        }
        // A stale zone set is not a universe.
        Map<String, Map<String, PanVsysZoneSet>> stale = Map.of("dev1", Map.of("vsys1",
                new PanVsysZoneSet("dev1", "vsys1", Set.of(ZA, ZB), NOW.minus(Duration.ofHours(30)))));
        if (measure(panZoneSnapshot("snap-pan-zone-4", "dev1", false), stale, NOW).get("legacy.zone_list_universe_unknown") != 1L) {
            return "pan_zone_stale";
        }
        String reasons = rawReasonChecks();
        if (reasons != null) {
            return reasons;
        }
        String census = censusChecks();
        if (census != null) {
            return census;
        }
        return outputChecks();
    }

    static final String SIMPLE_OK = "{\"ipv4-address\":\"192.0.2.1\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.1\"}]}";

    /** Each reason from a synthetic raw node, and the same complete/incomplete outcome as the real parser. */
    static String rawReasonChecks() throws Exception {
        record Case(String json, String type, String reason) {
        }
        List<Case> cases = List.of(
                new Case(SIMPLE_OK, "simple-gateway", COMPLETE),
                new Case("{\"ipv4-address\":\"192.0.2.999\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.1\"}]}", "simple-gateway", MAIN_IPV4),
                new Case("{\"ipv4-address\":7,\"interfaces\":[{\"ipv4-address\":\"192.0.2.1\"}]}", "simple-gateway", MAIN_IPV4),
                new Case("{\"ipv4-address\":\"192.0.2.1\"}", "simple-gateway", NO_INTERFACES),
                new Case("{\"ipv4-address\":\"192.0.2.1\",\"interfaces\":[]}", "CpmiVsxNetobj", NO_INTERFACES),
                new Case("{\"ipv4-address\":\"192.0.2.1\",\"interfaces\":[{\"name\":\"eth0\"}]}", "simple-gateway", INTERFACE_IP),
                new Case("{\"ipv4-address\":\"192.0.2.1\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.300\"}]}", "simple-gateway", INTERFACE_IP),
                new Case(SIMPLE_OK, "simple-cluster", CLUSTER_MEMBERS),
                new Case("{\"ipv4-address\":\"192.0.2.1\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.1\"}],\"cluster-members\":[]}",
                        "CpmiGatewayCluster", CLUSTER_MEMBERS),
                new Case("{\"ipv4-address\":\"192.0.2.1\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.1\"}],\"cluster-members\":[{\"name\":\"m\"}]}",
                        "simple-cluster", MEMBER_IP),
                new Case("{\"ipv4-address\":\"192.0.2.1\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.1\"}],\"cluster-members\":[{\"ip-address\":\"x\"}]}",
                        "simple-cluster", MEMBER_IP),
                new Case("{\"interfaces\":[{\"ipv6-address\":\"2001:db8::1\"}]}", "simple-gateway", EMPTY_KEPT),
                new Case("{\"ipv4-address\":\"192.0.2.1\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.1\"}],\"cluster-members\":[{\"ip-address\":\"192.0.2.2\"}]}",
                        "simple-cluster", COMPLETE),
                new Case("{\"ipv4-address\":\"192.0.2.1\",\"interfaces\":[{\"ipv4-address\":\"192.0.2.1\"}],\"cluster-members\":[{\"interfaces\":[{\"ipv4-address\":\"192.0.2.3\"}]}]}",
                        "CpmiGatewayCluster", COMPLETE));
        Method parser = null;
        try {
            parser = Class.forName("com.securityexpert.nexus.ui2.worker.policy.CpObjectInventoryParser")
                    .getDeclaredMethod("gatewayAddresses", JsonNode.class, String.class);
            parser.setAccessible(true);
        } catch (ReflectiveOperationException absent) {
            return "parser_not_on_classpath";
        }
        Set<String> seen = new TreeSet<>();
        for (Case c : cases) {
            JsonNode node = json(c.json());
            String reason = rawReason(node, c.type());
            if (!reason.equals(c.reason())) {
                return "raw_reason_" + c.reason();
            }
            seen.add(reason);
            var real = (CpObjectInventory.GatewayAddresses) Engine.call(parser, node, c.type());
            if (real.complete() != reason.equals(COMPLETE)) {
                return "raw_reason_parser_disagrees_" + c.reason();
            }
        }
        for (String reason : GATEWAY_REASONS) {
            boolean raw = !List.of(NOT_IN_INVENTORY, NOT_ATTRIBUTABLE, MARKER_UNUSABLE).contains(reason);
            if (raw && !seen.contains(reason)) {
                return "raw_reason_uncovered_" + reason;
            }
        }
        return null;
    }

    static CpObjectInventory.Item item(String uid, String type, List<String> values, CpObjectInventory.GatewayAddresses gateway) {
        return new CpObjectInventory.Item("id-" + uid, uid, "OBJ-GW-" + uid, type, List.of(), values, null, List.of(), null, gateway);
    }

    static final String S = "snap-cp-census";

    /** Stored inventory items through the real record's JSON, then the census joined to a synthetic snapshot. */
    static String censusChecks() throws Exception {
        var items = List.of(
                item("u1", "simple-gateway", List.of("ipv4-address: 192.0.2.1"), new CpObjectInventory.GatewayAddresses(List.of("192.0.2.1"), true)),
                item("u2", "simple-cluster", List.of("ipv4-address: 192.0.2.999"), new CpObjectInventory.GatewayAddresses(List.of(), false)),
                item("u3", "CpmiGatewayCluster", List.of("ipv4-address: 192.0.2.5"), new CpObjectInventory.GatewayAddresses(List.of("192.0.2.5"), false)),
                item("u4", "CpmiVsxNetobj", List.of(), new CpObjectInventory.GatewayAddresses(List.of(), false)),
                item("u5", "CpmiVsClusterNetobj", List.of(), null),
                item("u6", "host", List.of("ipv4-address: 192.0.2.6"), null),
                item("u7", "CpmiVsxClusterNetobj", List.of("ipv4-address: 192.0.2.7"), new CpObjectInventory.GatewayAddresses(List.of("192.0.2.7"), true)),
                item("u8", "checkpoint-host", List.of("ipv4-address: 192.0.2.8"), new CpObjectInventory.GatewayAddresses(List.of("192.0.2.8"), true)));
        String document = MAPPER.writeValueAsString(new CpObjectInventory("s1", "c1", "gateways-and-servers", NOW.toString(), "COMPLETE", "",
                items, 1, 0.1));
        Report report = new Report();
        Map<String, GwInfo> byUid = new HashMap<>();
        readInventory(new StringReader(document), node -> {
            GwInfo info = storedInfo(node);
            if (info != null) {
                byUid.put(node.path("uid").asText(""), info);
                report.gateway("inventory", info.slug(), info.reason());
            }
        });
        Map<String, GwInfo> want = Map.of("u1", new GwInfo("simple_gateway", COMPLETE), "u2", new GwInfo("simple_cluster", MAIN_IPV4),
                "u3", new GwInfo("cpmi_gateway_cluster", NOT_ATTRIBUTABLE), "u4", new GwInfo("vsx_gateway", EMPTY_KEPT),
                "u5", new GwInfo("vs_cluster_netobj", NOT_IN_INVENTORY), "u7", new GwInfo("vsx_cluster", COMPLETE),
                "u8", new GwInfo("other", COMPLETE));
        if (!byUid.equals(want)) {
            return "stored_info";
        }
        // Snapshot objects: u1 carries the marker; u3, u2, u4, u5 do not; u7 is complete in the inventory but not in the snapshot;
        // u9 has no inventory row; one marker without a usable address value.
        Map<String, PolicyObject> objects = new HashMap<>();
        for (String uid : List.of("u1", "u2", "u3", "u4", "u5", "u7", "u9")) {
            String ref = PolicySnapshot.ref(S, "object", uid);
            List<String> values = uid.equals("u1") ? List.of("ipv4-address: 192.0.2.1", "gateway-ipv4: 192.0.2.1", MARKER)
                    : List.of("ipv4-address: 192.0.2.2");
            objects.put(ref, new PolicyObject(ref, "OBJ-GW-" + uid, "gateway", List.of(), values, "RESOLVED"));
        }
        String unusable = PolicySnapshot.ref(S, "object", "u8");
        objects.put(unusable, new PolicyObject(unusable, "OBJ-GW-u8", "gateway", List.of(), List.of(MARKER), "RESOLVED"));
        objects.put("plain", object("plain", "address", "ipv4-address: 192.0.2.77"));
        var snap = snapshot(S, "CP", List.of(), List.of(new Section("s1", "OBJ-SECTION-01", "CP access layer", null, List.of())), objects);
        gatewayCensus(report, snap, Map.of("s1\u0000c1", byUid));
        Map<String, Long> got = new TreeMap<>(report.gateway);
        Map<String, Long> census = expected(
                "inventory.simple_gateway\t" + COMPLETE, 1, "inventory.simple_cluster\t" + MAIN_IPV4, 1,
                "inventory.cpmi_gateway_cluster\t" + NOT_ATTRIBUTABLE, 1, "inventory.vsx_gateway\t" + EMPTY_KEPT, 1,
                "inventory.vs_cluster_netobj\t" + NOT_IN_INVENTORY, 1, "inventory.vsx_cluster\t" + COMPLETE, 1,
                "inventory.other\t" + COMPLETE, 1,
                "snapshot.simple_gateway\t" + COMPLETE, 1, "snapshot.simple_cluster\t" + MAIN_IPV4, 1,
                "snapshot.cpmi_gateway_cluster\t" + NOT_ATTRIBUTABLE, 1, "snapshot.vsx_gateway\t" + EMPTY_KEPT, 1,
                "snapshot.vs_cluster_netobj\t" + NOT_IN_INVENTORY, 1, "snapshot.vsx_cluster\t" + NOT_IN_INVENTORY, 1,
                // u9 has no inventory row; u8 carries the marker but no address value
                "snapshot.other\t" + NOT_IN_INVENTORY, 1, "snapshot.other\t" + MARKER_UNUSABLE, 1);
        if (!got.equals(census)) {
            if (System.getProperty("gap.debug") != null) {
                System.err.println("census got " + got + "\nwant " + census);
            }
            return "gateway_census";
        }
        return null;
    }

    /** The whole report over synthetic data: every line is a safe tab-separated line, and the counters add up. */
    static String outputChecks() throws Exception {
        Report report = new Report();
        Map<String, Map<String, PanVsysZoneSet>> stored = Map.of("dev1", Map.of("vsys1",
                new PanVsysZoneSet("dev1", "vsys1", Set.of(ZA, ZB), NOW.minus(Duration.ofHours(1)))));
        account(report, cpSnapshot(), stored, Map.of(), NOW);
        account(report, panAppSnapshot("snap-pan-app-3", "default/port: tcp/443"), stored, Map.of(), NOW);
        account(report, panZoneSnapshot("snap-pan-zone-5", "dev1", true), stored, Map.of(), NOW);
        PolicySnapshot partial = cpSnapshot();
        partial = new PolicySnapshot(partial.metadata(), partial.sections(), partial.objects(),
                List.of(new PolicySnapshot.CollectionFailure("layer", "POLICY_PAGE_TIMEOUT")));
        account(report, partial, stored, Map.of(), NOW);
        if (report.scope.get("snapshots_analysed") != 3L || report.scope.get("snapshots_with_failures_skipped") != 1L) {
            return "report_scope";
        }
        StringWriter text = new StringWriter();
        out = new PrintWriter(text, true);
        emitReport(report);
        String[] lines = text.toString().split("\n");
        Map<String, String> byKey = new HashMap<>();
        for (String line : lines) {
            if (!SAFE_LINE.matcher(line).matches()) {
                return "unsafe_line";
            }
            String[] f = line.split("\t");
            if (f.length != 4) {
                return "line_shape";
            }
            byKey.put(f[0] + "\t" + f[1] + "\t" + f[2], f[3]);
        }
        if (byKey.containsKey("unsafe_field_dropped")) {
            return "unsafe_field";
        }
        // fleet.all = cp (5 rules) + two Palo Alto snapshots (2 + 2 rules), sound mode; the largest package ranks first
        if (!"9".equals(byKey.get("engine\tfleet.all.sound\trules_evaluated"))
                || !"3".equals(byKey.get("scope\tfleet\tsnapshots_analysed"))) {
            return "report_totals";
        }
        if (!"cp".equals(byKey.get("package\tp01\tvendor")) || !"5".equals(byKey.get("package\tp01\tsecurity_rules"))) {
            return "report_rank";
        }
        // the unsafe-field guard drops a line carrying a name
        out = new PrintWriter(text = new StringWriter(), true);
        emitText("device", "FW-TANGO-04", "x", "1");
        if (!text.toString().strip().equals("unsafe_field_dropped")) {
            return "unsafe_guard";
        }
        if (SAFE.matcher("FW-TANGO-04").matches() || !SAFE.matcher("p01.sound").matches()) {
            return "safe_filter";
        }
        return null;
    }
}
