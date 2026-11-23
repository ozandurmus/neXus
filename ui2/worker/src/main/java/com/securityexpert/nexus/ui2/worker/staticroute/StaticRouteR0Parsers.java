package com.securityexpert.nexus.ui2.worker.staticroute;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.BeforePathObservation;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.ExistingRouteAttributes;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.PreCheckVerdict;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.ValidatedRequest;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.worker.backup.asa.CiscoAsaPlan;
import com.securityexpert.nexus.ui2.worker.failover.PanFailoverChecks;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedRoute;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointHaStateParser;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointIpRouteParser;
import com.securityexpert.nexus.ui2.worker.inventory.pan.PaloAltoHaStateParser;
import com.securityexpert.nexus.ui2.worker.inventory.pan.PaloAltoRouteParser;

/**
 * In-memory, fail-closed parsers and Stage A/B pre-check evaluator for Phase R0 static-route previews.
 *
 * <p>Constitutional invariants enforced here:
 * <ul>
 *   <li>Raw vendor output is parsed in memory to safe enums, counts, SHA-256 digests, and validated route structures,
 *       then discarded immediately (AGENTS.md Raw-evidence law).</li>
 *   <li>Any unrecognised or ambiguous vendor output shape produces {@code UNKNOWN}, never {@code PASS}
 *       (AGENTS.md UNKNOWN / fail-closed law; R0 read-commands doc).</li>
 *   <li>One bounded, masked shape line is formatted per read without ever logging IPv4 addresses, hostnames, serials,
 *       interface names, or route names.</li>
 * </ul>
 */
public final class StaticRouteR0Parsers {

    public static final String STATUS_PASS = "PASS";
    public static final String STATUS_FAIL = "FAIL";
    public static final String STATUS_WARN = "WARN";
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    public static final String CHECK_1_TARGET_READY = "TARGET_REACHABLE_AND_READY";
    public static final String CHECK_2_IDENTITY_HA_MGMT = "IDENTITY_HA_AND_CENTRAL_MGMT_VERIFIED";
    public static final String CHECK_3_DEFAULT_ROUTE = "DEFAULT_ROUTE_GUARD";
    public static final String CHECK_4_ROUTE_PRECONDITION = "ROUTE_STATE_AND_ATTRIBUTE_PRECONDITION";
    public static final String CHECK_5_NEXTHOP_CONNECTED = "NEXTHOP_ON_CONNECTED_SUBNET";
    public static final String CHECK_6_CONTROL_PLANE_OVERLAP = "CONTROL_PLANE_AND_OVERLAP_GUARD";

    private static final int MAX_SHAPE_LINES = 15;
    private static final int MAX_SHAPE_CHARS = 1024;

    private static final Set<String> SAFE_SHAPE_TOKENS = Set.of(
            "response", "status", "success", "error", "result", "entry", "yes", "no",
            "destination", "nexthop", "ip-address", "interface", "metric", "admin-dist",
            "virtual-router", "routing-table", "ip", "static-route", "flags", "fib-lookup",
            "high-availability", "group", "mode", "local-info", "peer-info", "state", "running-sync",
            "active", "passive", "standby", "synchronized", "single", "multiple",
            "Cluster", "Mode", "High", "Availability", "Virtual", "System", "Load", "Sharing",
            "ACTIVE", "STANDBY", "READY", "DOWN", "via", "dev", "proto", "scope", "link", "src",
            "set", "route", "static", "gateway", "address", "priority", "comment", "on", "off",
            "Codes", "Connected", "Static", "Routing", "Known", "directly", "connected");

    private static final Pattern WORD_OR_NUMBER = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*|\\d+");

    private StaticRouteR0Parsers() {
    }

    // ---- Masked Shape Line Formatter (one line per read, zero raw values) ----

    public static String formatMaskedShapeLine(String vendor, String gateId, String rawOutput, boolean parsed) {
        if (rawOutput == null) {
            return "[STATIC_ROUTE_R0_SHAPE] vendor=" + vendor + " gate=" + gateId + " parsed=" + parsed
                    + " lines=0 bytes=0 shape=[no_output]";
        }
        int bytes = rawOutput.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String[] lines = rawOutput.split("\\R");
        int lineCount = lines.length;
        StringBuilder sb = new StringBuilder();
        int included = 0;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (included > 0) {
                sb.append(" | ");
            }
            sb.append(maskLineTokens(trimmed));
            included++;
            if (included >= MAX_SHAPE_LINES || sb.length() >= MAX_SHAPE_CHARS) {
                break;
            }
        }
        String shape = sb.length() > MAX_SHAPE_CHARS ? sb.substring(0, MAX_SHAPE_CHARS) : sb.toString();
        return "[STATIC_ROUTE_R0_SHAPE] vendor=" + vendor + " gate=" + gateId + " parsed=" + parsed
                + " lines=" + lineCount + " bytes=" + bytes + " shape=" + (shape.isEmpty() ? "[blank]" : shape);
    }

    private static String maskLineTokens(String line) {
        StringBuilder out = new StringBuilder(line.length());
        Matcher m = WORD_OR_NUMBER.matcher(line);
        int last = 0;
        while (m.find()) {
            out.append(line, last, m.start());
            String token = m.group();
            if (SAFE_SHAPE_TOKENS.contains(token)) {
                out.append(token);
            } else {
                for (int i = 0; i < token.length(); i++) {
                    char c = token.charAt(i);
                    out.append(Character.isDigit(c) ? '9' : Character.isLetter(c) ? 'a' : c);
                }
            }
            last = m.end();
        }
        out.append(line, last, line.length());
        return out.toString();
    }

    // ---- Shared Parsed Route Structures ----

    public record ConfiguredStaticRoute(
            String destinationCidr,
            String nextHopIp,
            String interfaceName,
            Integer distance,
            Integer metric,
            Integer priority,
            String routeName,
            String comment,
            boolean templateManaged,
            boolean unsupportedAttribute,
            String unsupportedDetail) {
    }

    public record ConfiguredRoutesParse(
            String status,
            String reasonCode,
            List<ConfiguredStaticRoute> routes,
            String tableSha256) {
        public ConfiguredRoutesParse {
            routes = routes == null ? List.of() : List.copyOf(routes);
        }
    }

    public record RibParse(
            String status,
            String reasonCode,
            List<InventoryRoute> routes) {
        public RibParse {
            routes = routes == null ? List.of() : List.copyOf(routes);
        }
    }

    // ---- 1. Check Point Gaia R0 Parsers ----

    public record CpClusterState(
            String status,
            String reasonCode,
            String localRole,
            Optional<String> clusterMode) {
    }

    private static final Set<String> CP_SUPPORTED_CLUSTER_MODES = Set.of(
            "High Availability",
            "Virtual System Load Sharing",
            "Load Sharing unicast",
            "Load Sharing multicast");

    /**
     * R0-CP-4 ({@code cphaprob stat}): standalone is PASS; ClusterXL High Availability or VSLS with ACTIVE/STANDBY
     * local role is PASS; Cloning Group or any unrecognised mode string fails closed as {@code CLONING_GROUP_UNSUPPORTED}.
     */
    public static CpClusterState parseCpClusterState(String output) {
        if (output == null || output.isBlank()) {
            return new CpClusterState(STATUS_UNKNOWN, "HA_STATE_UNAVAILABLE", "UNKNOWN", Optional.empty());
        }
        if (output.toLowerCase(Locale.ROOT).contains("cloning")) {
            return new CpClusterState(STATUS_FAIL, "CLONING_GROUP_UNSUPPORTED", "UNKNOWN", Optional.of("Cloning Group"));
        }
        CheckPointHaStateParser.HaState parsed = CheckPointHaStateParser.parse(output);
        if ("STANDALONE".equalsIgnoreCase(parsed.role())) {
            return new CpClusterState(STATUS_PASS, "STANDALONE", "STANDALONE", Optional.empty());
        }
        if (parsed.clusterMode().isEmpty()) {
            return new CpClusterState(STATUS_FAIL, "CLONING_GROUP_UNSUPPORTED", parsed.role(), Optional.empty());
        }
        String mode = parsed.clusterMode().get();
        if (mode.toLowerCase(Locale.ROOT).contains("cloning") || !CP_SUPPORTED_CLUSTER_MODES.contains(mode)) {
            return new CpClusterState(STATUS_FAIL, "CLONING_GROUP_UNSUPPORTED", parsed.role(), Optional.of(mode));
        }
        String role = parsed.role().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(role) && !"STANDBY".equals(role)) {
            return new CpClusterState(STATUS_FAIL, "HA_STATE_NOT_READY", role, Optional.of(mode));
        }
        return new CpClusterState(STATUS_PASS, "CLUSTERXL_READY", role, Optional.of(mode));
    }

    private static final Pattern CP_SET_STATIC_ROUTE_NH = Pattern.compile(
            "\\Aset\\s+static-route\\s+(\\S+)\\s+nexthop\\s+gateway\\s+(?:address\\s+)?(\\d+\\.\\d+\\.\\d+\\.\\d+)"
            + "(?:\\s+priority\\s+(\\d+))?\\s+(on|off)\\z");
    private static final Pattern CP_SET_STATIC_ROUTE_COMMENT = Pattern.compile(
            "\\Aset\\s+static-route\\s+(\\S+)\\s+comment\\s+\"([^\"]*)\"\\z");
    private static final Pattern CP_SET_STATIC_ROUTE_OTHER = Pattern.compile(
            "\\Aset\\s+static-route\\s+(\\S+)\\s+(.+)\\z");
    private static final Pattern CP_SHOW_ROUTE_STATIC_ROW = Pattern.compile(
            "\\AS\\s+(\\S+)\\s+via\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)(?:,\\s*([A-Za-z0-9_./:-]+))?(?:,\\s*(.*))?\\z");
    private static final Pattern CP_SHOW_ROUTE_STATIC_SPECIAL = Pattern.compile(
            "\\AS\\s+(\\S+)\\s+(blackhole|reject|logical|is\\s+directly\\s+connected)(?:[\\s,].*)?\\z",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CP_UNSUPPORTED_ATTR_TOKEN = Pattern.compile(
            "(?i)\\b(logical|blackhole|reject|ping|rank|scopelocal)\\b");
    private static final Pattern CP_PRIORITY_TOKEN = Pattern.compile("(?i)\\b(?:priority|cost)\\s+(\\d+)\\b");

    /**
     * R0-CP-1 ({@code clish -c 'show route static'}): detects Gaia DB lock ({@code GAIA_DB_LOCKED}), parses static
     * routes, flags {@code logical}/{@code blackhole}/{@code reject}/{@code ping}/{@code rank}/{@code scopelocal} as
     * {@code UNSUPPORTED_ROUTE_ATTRIBUTE}, and fails closed ({@code UNKNOWN}) on any unrecognised line.
     */
    public static ConfiguredRoutesParse parseCpStaticRoutes(String output) {
        if (output == null) {
            return new ConfiguredRoutesParse(STATUS_UNKNOWN, "STATIC_ROUTE_CONFIG_UNAVAILABLE", List.of(), null);
        }
        String lower = output.toLowerCase(Locale.ROOT);
        if (lower.contains("database is locked") || lower.contains("lock database") || lower.contains("clinfr0329")) {
            return new ConfiguredRoutesParse(STATUS_FAIL, "GAIA_DB_LOCKED", List.of(), null);
        }
        List<ConfiguredStaticRoute> routes = new ArrayList<>();
        Map<String, String> commentsByPrefix = new java.util.LinkedHashMap<>();
        Map<String, String> unsupportedByPrefix = new java.util.LinkedHashMap<>();

        for (String rawLine : output.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()
                    || line.startsWith("#")
                    || line.startsWith("Codes:")
                    || line.startsWith("C - ")
                    || line.startsWith("S - ")
                    || line.startsWith("O - ")
                    || line.startsWith("B - ")
                    || line.startsWith("R - ")
                    || line.startsWith("K - ")
                    || line.startsWith("A - ")
                    || line.equalsIgnoreCase("No static routes")
                    || line.equalsIgnoreCase("No static routes configured")) {
                continue;
            }

            Matcher setNh = CP_SET_STATIC_ROUTE_NH.matcher(line);
            if (setNh.matches()) {
                if ("off".equalsIgnoreCase(setNh.group(4))) {
                    continue;
                }
                Optional<String> cidr = normalizeDevicePrefix(setNh.group(1));
                Optional<String> nh = normalizeDeviceIpv4(setNh.group(2));
                if (cidr.isEmpty() || nh.isEmpty()) {
                    return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
                }
                Integer prio = setNh.group(3) != null ? Integer.parseInt(setNh.group(3)) : null;
                routes.add(new ConfiguredStaticRoute(
                        cidr.get(), nh.get(), null, null, null, prio, null, null, false, false, null));
                continue;
            }

            Matcher setComment = CP_SET_STATIC_ROUTE_COMMENT.matcher(line);
            if (setComment.matches()) {
                Optional<String> cidr = normalizeDevicePrefix(setComment.group(1));
                if (cidr.isEmpty()) {
                    return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
                }
                commentsByPrefix.put(cidr.get(), setComment.group(2));
                continue;
            }

            Matcher setOther = CP_SET_STATIC_ROUTE_OTHER.matcher(line);
            if (setOther.matches()) {
                Optional<String> cidr = normalizeDevicePrefix(setOther.group(1));
                String tail = setOther.group(2);
                Matcher unsup = CP_UNSUPPORTED_ATTR_TOKEN.matcher(tail);
                if (cidr.isPresent() && unsup.find()) {
                    unsupportedByPrefix.put(cidr.get(), unsup.group(1).toLowerCase(Locale.ROOT));
                    routes.add(new ConfiguredStaticRoute(
                            cidr.get(), "", null, null, null, null, null, null, false, true,
                            unsup.group(1).toLowerCase(Locale.ROOT)));
                    continue;
                }
                return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
            }

            Matcher showRow = CP_SHOW_ROUTE_STATIC_ROW.matcher(line);
            if (showRow.matches()) {
                Optional<String> cidr = normalizeDevicePrefix(showRow.group(1));
                Optional<String> nh = normalizeDeviceIpv4(showRow.group(2));
                if (cidr.isEmpty() || nh.isEmpty()) {
                    return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
                }
                String iface = showRow.group(3);
                String tail = showRow.group(4) == null ? "" : showRow.group(4);
                Matcher unsup = CP_UNSUPPORTED_ATTR_TOKEN.matcher(tail);
                boolean hasUnsup = unsup.find();
                String unsupToken = hasUnsup ? unsup.group(1).toLowerCase(Locale.ROOT) : null;
                Integer prio = null;
                Matcher pm = CP_PRIORITY_TOKEN.matcher(tail);
                if (pm.find()) {
                    int parsedPrio = Integer.parseInt(pm.group(1));
                    if (parsedPrio >= 1 && parsedPrio <= 8) {
                        prio = parsedPrio;
                    }
                }
                routes.add(new ConfiguredStaticRoute(
                        cidr.get(), nh.get(), iface, null, null, prio, null, null, false, hasUnsup, unsupToken));
                continue;
            }

            Matcher showSpecial = CP_SHOW_ROUTE_STATIC_SPECIAL.matcher(line);
            if (showSpecial.matches()) {
                Optional<String> cidr = normalizeDevicePrefix(showSpecial.group(1));
                if (cidr.isEmpty()) {
                    return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
                }
                String kind = showSpecial.group(2).toLowerCase(Locale.ROOT);
                routes.add(new ConfiguredStaticRoute(
                        cidr.get(), "", null, null, null, null, null, null, false, true, kind));
                continue;
            }

            return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
        }

        List<ConfiguredStaticRoute> enriched = new ArrayList<>(routes.size());
        for (ConfiguredStaticRoute r : routes) {
            String comment = commentsByPrefix.getOrDefault(r.destinationCidr(), r.comment());
            String unsup = unsupportedByPrefix.get(r.destinationCidr());
            boolean isUnsup = r.unsupportedAttribute() || unsup != null;
            String unsupDetail = r.unsupportedDetail() != null ? r.unsupportedDetail() : unsup;
            enriched.add(new ConfiguredStaticRoute(
                    r.destinationCidr(), r.nextHopIp(), r.interfaceName(), r.distance(), r.metric(),
                    r.priority(), r.routeName(), comment, r.templateManaged(), isUnsup, unsupDetail));
        }
        return new ConfiguredRoutesParse(STATUS_PASS, "PARSED", enriched, computeRoutesDigest(enriched));
    }

    /**
     * R0-CP-3 ({@code ip -4 route show table all}): delegates to {@link CheckPointIpRouteParser}.
     */
    public static RibParse parseCpRib(String output) {
        if (output == null || output.isBlank()) {
            return new RibParse(STATUS_UNKNOWN, "RIB_UNAVAILABLE", List.of());
        }
        List<ParsedRoute> parsed = CheckPointIpRouteParser.parse(output);
        if (parsed.isEmpty()) {
            return new RibParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of());
        }
        List<InventoryRoute> routes = new ArrayList<>(parsed.size());
        for (ParsedRoute r : parsed) {
            routes.add(new InventoryRoute(
                    UUID.randomUUID().toString(),
                    r.destination(),
                    r.nextHop(),
                    r.interfaceName(),
                    r.protocol(),
                    r.routeTable(),
                    r.active(),
                    r.ecmp(),
                    r.metric(),
                    r.nextHopKind()));
        }
        return new RibParse(STATUS_PASS, "PARSED", routes);
    }

    private static final Set<String> CP_DISCARD_ROUTE_TYPES = Set.of("blackhole", "unreachable", "prohibit");
    private static final Set<String> CP_OTHER_ROUTE_TYPES = Set.of("broadcast", "throw", "multicast", "anycast", "nat");

    /**
     * R0-CP-2 ({@code ip -4 route get <DST>}): parses kernel route lookup for {@code representativeHost} fail-closed.
     */
    public static BeforePathObservation parseCpRouteGet(
            String output,
            String representativeHost,
            List<InventoryRoute> ribRoutes) {
        if (output == null || output.isBlank()) {
            return unknownPath(representativeHost, "PATH_LOOKUP_UNAVAILABLE");
        }
        int hostInt = StaticRoutePreview.parseIpv4ToInt(representativeHost, "REPRESENTATIVE_HOST_INVALID");
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("RTNETLINK answers:")) {
                return unknownPath(representativeHost, "NO_ROUTE_IN_FIB");
            }
            String[] tokens = line.split("\\s+");
            int idx = 0;
            String kind = "unicast";
            if (CP_DISCARD_ROUTE_TYPES.contains(tokens[0])) {
                return unknownPath(representativeHost, "FIB_DISCARD_ROUTE");
            } else if ("local".equals(tokens[0]) || "unicast".equals(tokens[0])) {
                kind = tokens[0];
                idx = 1;
            } else if (CP_OTHER_ROUTE_TYPES.contains(tokens[0])) {
                return unknownPath(representativeHost, "ANSWER_NOT_RECOGNISED");
            }
            if (tokens.length <= idx) {
                continue;
            }
            String targetToken = tokens[idx];
            Optional<String> explicitPrefix = targetToken.contains("/") ? normalizeDevicePrefix(targetToken) : Optional.empty();
            if (!targetToken.equals(representativeHost) && explicitPrefix.isEmpty()) {
                continue;
            }
            Optional<String> dev = tokenAfter(tokens, idx + 1, "dev");
            Optional<String> via = tokenAfter(tokens, idx + 1, "via");
            Optional<String> protoToken = tokenAfter(tokens, idx + 1, "proto");
            if (dev.isEmpty()) {
                return unknownPath(representativeHost, "ANSWER_NOT_RECOGNISED");
            }
            if (via.isPresent() && normalizeDeviceIpv4(via.get()).isEmpty()) {
                return unknownPath(representativeHost, "ANSWER_NOT_RECOGNISED");
            }
            Optional<InventoryRoute> lpm = longestPrefixMatch(ribRoutes, hostInt);
            String selectedPrefix = explicitPrefix
                    .or(() -> lpm.map(InventoryRoute::destination))
                    .orElse(representativeHost + "/32");
            String nextHopIp = via.orElse("CONNECTED");
            String egressIf = dev.get();
            String finalKind = kind;
            String protocol = lpm.map(InventoryRoute::protocol)
                    .orElseGet(() -> mapCpProtoToken(protoToken.orElse(null), via.isPresent(), finalKind));
            String digest = StaticRoutePreview.sha256Hex(selectedPrefix + "|" + nextHopIp + "|" + egressIf + "|" + protocol);
            return new BeforePathObservation(
                    representativeHost,
                    "OBSERVED",
                    "PATH_RECORDED",
                    selectedPrefix,
                    nextHopIp,
                    egressIf,
                    protocol,
                    digest);
        }
        return unknownPath(representativeHost, "ANSWER_NOT_RECOGNISED");
    }

    private static String mapCpProtoToken(String proto, boolean hasVia, String kind) {
        if ("local".equals(kind)) {
            return InventoryRoute.PROTOCOL_HOST;
        }
        if (proto == null) {
            return hasVia ? InventoryRoute.PROTOCOL_STATIC : InventoryRoute.PROTOCOL_CONNECTED;
        }
        return switch (proto) {
            case "kernel" -> InventoryRoute.PROTOCOL_CONNECTED;
            case "7", "boot", "static" -> InventoryRoute.PROTOCOL_STATIC;
            default -> InventoryRoute.PROTOCOL_UNKNOWN;
        };
    }

    // ---- 2. Palo Alto Networks R0 Parsers ----

    public record PanPendingChangesResult(String status, String reasonCode) {
    }

    /**
     * R0-PAN-3 ({@code <check><pending-changes/></check>}): {@code no} -> PASS; {@code yes} -> FAIL
     * ({@code PENDING_CHANGES_PRESENT}); any other response -> UNKNOWN.
     */
    public static PanPendingChangesResult parsePanPendingChanges(String xml) {
        Optional<Element> response = parsePanXmlResponse(xml, StaticRoutePreview.OUTPUT_LIMIT_BYTES);
        if (response.isEmpty() || !"success".equals(response.get().getAttribute("status"))) {
            return new PanPendingChangesResult(STATUS_UNKNOWN, "PENDING_CHANGES_UNKNOWN");
        }
        Optional<Element> result = uniqueChild(response.get(), "result");
        if (result.isEmpty()) {
            return new PanPendingChangesResult(STATUS_UNKNOWN, "PENDING_CHANGES_UNKNOWN");
        }
        // Fail closed if <result> has child elements instead of a plain yes/no text node.
        for (Node n = result.get().getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                return new PanPendingChangesResult(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED");
            }
        }
        String text = result.get().getTextContent().strip().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "no" -> new PanPendingChangesResult(STATUS_PASS, "NO_PENDING_CHANGES");
            case "yes" -> new PanPendingChangesResult(STATUS_FAIL, "PENDING_CHANGES_PRESENT");
            default -> new PanPendingChangesResult(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED");
        };
    }

    public record PanHaResult(String status, String reasonCode, String localRole, String runningSync) {
    }

    /**
     * R0-PAN-5 ({@code <show><high-availability><state/></high-availability></show>}): standalone is PASS;
     * active-passive with local state {@code active} and running-sync {@code synchronized} is PASS.
     */
    public static PanHaResult parsePanHaState(String xml) {
        if (xml == null || xml.isBlank()) {
            return new PanHaResult(STATUS_UNKNOWN, "HA_STATE_NOT_READY", "UNKNOWN", "UNKNOWN");
        }
        PaloAltoHaStateParser.HaState basic = PaloAltoHaStateParser.parse(xml);
        if (PaloAltoHaStateParser.STANDALONE.equals(basic.role())) {
            return new PanHaResult(STATUS_PASS, "STANDALONE", "STANDALONE", "not_applicable");
        }
        if (PaloAltoHaStateParser.UNKNOWN.equals(basic.role())) {
            return new PanHaResult(STATUS_UNKNOWN, "HA_STATE_NOT_READY", "UNKNOWN", "UNKNOWN");
        }
        PanFailoverChecks.State full = PanFailoverChecks.parse(xml);
        if (full.role().isEmpty()) {
            return new PanHaResult(STATUS_UNKNOWN, "HA_STATE_NOT_READY", basic.role(), "UNKNOWN");
        }
        if (!"active".equalsIgnoreCase(full.role())) {
            return new PanHaResult(STATUS_FAIL, "HA_STATE_NOT_READY", full.role(), full.runningSync());
        }
        if (!"synchronized".equalsIgnoreCase(full.runningSync())) {
            return new PanHaResult(STATUS_FAIL, "HA_CONFIG_OUT_OF_SYNC", full.role(), full.runningSync());
        }
        return new PanHaResult(STATUS_PASS, "HA_ACTIVE_SYNCHRONIZED", full.role(), full.runningSync());
    }

    public record PanEffectiveRunningResult(
            String status,
            String reasonCode,
            boolean vrFound,
            boolean templateManaged) {
    }

    /**
     * R0-PAN-4 ({@code <show><config><effective-running/></config></show>}): verifies the target VR exists under
     * legacy {@code virtual-router} (refuses {@code logical-router} with {@code ROUTING_CONTEXT_UNSUPPORTED}) and
     * checks that neither the VR nor a matching static route carries Panorama template provenance ({@code src="tpl"}).
     */
    public static PanEffectiveRunningResult parsePanEffectiveRunning(
            String xml,
            String virtualRouter,
            String targetDestinationCidr,
            String targetRouteName) {
        Optional<Element> response = parsePanXmlResponse(xml, StaticRoutePreview.EFFECTIVE_RUNNING_LIMIT_BYTES);
        if (response.isEmpty() || !"success".equals(response.get().getAttribute("status"))) {
            return new PanEffectiveRunningResult(STATUS_UNKNOWN, "EFFECTIVE_RUNNING_UNAVAILABLE", false, false);
        }
        Optional<Element> result = uniqueChild(response.get(), "result");
        if (result.isEmpty()) {
            return new PanEffectiveRunningResult(STATUS_UNKNOWN, "EFFECTIVE_RUNNING_UNAVAILABLE", false, false);
        }
        // Check for ARE logical-router entry matching virtualRouter first
        NodeList logicalRouters = result.get().getElementsByTagName("logical-router");
        for (int i = 0; i < logicalRouters.getLength(); i++) {
            Element lr = (Element) logicalRouters.item(i);
            for (Element entry : directChildEntries(lr)) {
                if (virtualRouter.equals(entry.getAttribute("name"))) {
                    return new PanEffectiveRunningResult(STATUS_FAIL, "ROUTING_CONTEXT_UNSUPPORTED", false, false);
                }
            }
        }

        NodeList virtualRouters = result.get().getElementsByTagName("virtual-router");
        Element matchedVr = null;
        for (int i = 0; i < virtualRouters.getLength(); i++) {
            Element vrContainer = (Element) virtualRouters.item(i);
            for (Element entry : directChildEntries(vrContainer)) {
                if (virtualRouter.equals(entry.getAttribute("name"))) {
                    matchedVr = entry;
                    break;
                }
            }
            if (matchedVr != null) {
                break;
            }
        }
        if (matchedVr == null) {
            return new PanEffectiveRunningResult(STATUS_FAIL, "ROUTING_CONTEXT_NOT_FOUND", false, false);
        }
        if (hasTemplateSrcAttribute(matchedVr, false)) {
            return new PanEffectiveRunningResult(STATUS_FAIL, "PANORAMA_TEMPLATE_MANAGED", true, true);
        }

        NodeList staticRoutes = matchedVr.getElementsByTagName("static-route");
        for (int i = 0; i < staticRoutes.getLength(); i++) {
            Element srContainer = (Element) staticRoutes.item(i);
            if (hasTemplateSrcAttribute(srContainer, false)) {
                return new PanEffectiveRunningResult(STATUS_FAIL, "PANORAMA_TEMPLATE_MANAGED", true, true);
            }
            for (Element routeEntry : directChildEntries(srContainer)) {
                String rName = routeEntry.getAttribute("name");
                String rDest = uniqueChildText(routeEntry, "destination").orElse("");
                Optional<String> normalizedDest = normalizeDevicePrefix(rDest);
                boolean matchesTarget = (targetRouteName != null && targetRouteName.equals(rName))
                        || (normalizedDest.isPresent() && normalizedDest.get().equals(targetDestinationCidr));
                if (matchesTarget && hasTemplateSrcAttribute(routeEntry, true)) {
                    return new PanEffectiveRunningResult(STATUS_FAIL, "PANORAMA_TEMPLATE_MANAGED", true, true);
                }
            }
        }
        return new PanEffectiveRunningResult(STATUS_PASS, "LOCAL_VR_VERIFIED", true, false);
    }

    /**
     * R0-PAN-1 ({@code type=config&action=show&xpath=.../virtual-router/entry[@name='<VR>']/routing-table/ip/static-route}):
     * parses running static routes for the target VR fail-closed.
     */
    public static ConfiguredRoutesParse parsePanVrStaticRoutes(String xml) {
        Optional<Element> response = parsePanXmlResponse(xml, StaticRoutePreview.OUTPUT_LIMIT_BYTES);
        if (response.isEmpty() || !"success".equals(response.get().getAttribute("status"))) {
            return new ConfiguredRoutesParse(STATUS_UNKNOWN, "STATIC_ROUTE_CONFIG_UNAVAILABLE", List.of(), null);
        }
        Optional<Element> result = uniqueChild(response.get(), "result");
        if (result.isEmpty()) {
            // Empty <response status="success"/> or <result/> means zero static routes in that VR.
            return new ConfiguredRoutesParse(STATUS_PASS, "PARSED", List.of(), computeRoutesDigest(List.of()));
        }
        Element container = uniqueChild(result.get(), "static-route").orElse(result.get());
        List<Element> entries = directChildEntries(container);
        List<ConfiguredStaticRoute> routes = new ArrayList<>(entries.size());

        for (Element entry : entries) {
            String routeName = entry.getAttribute("name");
            if (routeName == null || routeName.isBlank()) {
                return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
            }
            boolean tpl = hasTemplateSrcAttribute(entry, true);
            Optional<String> rawDest = uniqueChildText(entry, "destination");
            Optional<String> destCidr = rawDest.flatMap(StaticRouteR0Parsers::normalizeDevicePrefix);
            if (destCidr.isEmpty()) {
                return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
            }
            String iface = uniqueChildText(entry, "interface").filter(s -> !s.isBlank()).orElse(null);
            Integer metric = null;
            Optional<String> rawMetric = uniqueChildText(entry, "metric");
            if (rawMetric.isPresent() && !rawMetric.get().isBlank()) {
                if (!rawMetric.get().matches("\\d{1,5}")) {
                    return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
                }
                metric = Integer.parseInt(rawMetric.get());
            }
            Integer adminDist = null;
            Optional<String> rawDist = uniqueChildText(entry, "admin-dist");
            if (rawDist.isPresent() && !rawDist.get().isBlank()) {
                if (!rawDist.get().matches("\\d{1,3}")) {
                    return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
                }
                adminDist = Integer.parseInt(rawDist.get());
            }

            boolean unsupported = uniqueChild(entry, "bfd").isPresent()
                    || uniqueChild(entry, "path-monitor").isPresent()
                    || uniqueChild(entry, "route-table").isPresent();
            String unsupportedDetail = unsupported ? "bfd_or_path_monitor" : null;
            String nextHopIp = "";

            Optional<Element> nexthopEl = uniqueChild(entry, "nexthop");
            if (nexthopEl.isEmpty()) {
                unsupported = true;
                unsupportedDetail = "missing_nexthop";
            } else {
                List<Element> nhChildren = childElements(nexthopEl.get());
                if (nhChildren.size() != 1 || !"ip-address".equals(nhChildren.get(0).getTagName())) {
                    unsupported = true;
                    unsupportedDetail = nhChildren.isEmpty() ? "empty_nexthop" : nhChildren.get(0).getTagName();
                } else {
                    Optional<String> nh = normalizeDeviceIpv4(nhChildren.get(0).getTextContent().strip());
                    if (nh.isEmpty()) {
                        return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
                    }
                    nextHopIp = nh.get();
                }
            }

            routes.add(new ConfiguredStaticRoute(
                    destCidr.get(),
                    nextHopIp,
                    iface,
                    adminDist,
                    metric,
                    null,
                    routeName,
                    null,
                    tpl,
                    unsupported,
                    unsupportedDetail));
        }

        return new ConfiguredRoutesParse(STATUS_PASS, "PARSED", routes, computeRoutesDigest(routes));
    }

    /**
     * R0-PAN-6 ({@code <show><routing><route/></routing></show>}): delegates to {@link PaloAltoRouteParser}.
     */
    public static RibParse parsePanRib(String xml, String virtualRouter) {
        Optional<Element> response = parsePanXmlResponse(xml, StaticRoutePreview.OUTPUT_LIMIT_BYTES);
        if (response.isEmpty() || !"success".equals(response.get().getAttribute("status"))) {
            return new RibParse(STATUS_UNKNOWN, "RIB_UNAVAILABLE", List.of());
        }
        Map<String, List<ParsedRoute>> byVr = PaloAltoRouteParser.parse(xml);
        List<ParsedRoute> vrRoutes = byVr.getOrDefault(virtualRouter, List.of());
        if (vrRoutes.isEmpty() && byVr.isEmpty()) {
            return new RibParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of());
        }
        List<InventoryRoute> routes = new ArrayList<>(vrRoutes.size());
        for (ParsedRoute r : vrRoutes) {
            routes.add(new InventoryRoute(
                    UUID.randomUUID().toString(),
                    r.destination(),
                    r.nextHop(),
                    r.interfaceName(),
                    r.protocol(),
                    r.routeTable(),
                    r.active(),
                    r.ecmp(),
                    r.metric(),
                    r.nextHopKind()));
        }
        return new RibParse(STATUS_PASS, "PARSED", routes);
    }

    /**
     * R0-PAN-2 ({@code <test><routing><fib-lookup><virtual-router>VR</virtual-router><ip>DST</ip></fib-lookup></routing></test>}):
     * parses FIB lookup fail-closed.
     */
    public static BeforePathObservation parsePanFibLookup(
            String xml,
            String representativeHost,
            List<InventoryRoute> ribRoutes) {
        Optional<Element> response = parsePanXmlResponse(xml, StaticRoutePreview.OUTPUT_LIMIT_BYTES);
        if (response.isEmpty() || !"success".equals(response.get().getAttribute("status"))) {
            return unknownPath(representativeHost, "PATH_LOOKUP_UNAVAILABLE");
        }
        Optional<Element> result = uniqueChild(response.get(), "result");
        if (result.isEmpty()) {
            return unknownPath(representativeHost, "ANSWER_NOT_RECOGNISED");
        }
        Optional<String> nhType = uniqueChildText(result.get(), "nh").map(s -> s.toLowerCase(Locale.ROOT));
        if (nhType.filter(t -> t.equals("discard") || t.equals("drop")).isPresent()) {
            return unknownPath(representativeHost, "FIB_DISCARD_ROUTE");
        }
        Optional<String> iface = uniqueChildText(result.get(), "interface").filter(s -> !s.isBlank());
        Optional<String> nhIp = uniqueChildText(result.get(), "ip").filter(s -> !s.isBlank())
                .flatMap(StaticRouteR0Parsers::normalizeDeviceIpv4);
        if (iface.isEmpty()) {
            return unknownPath(representativeHost, "ANSWER_NOT_RECOGNISED");
        }
        int hostInt = StaticRoutePreview.parseIpv4ToInt(representativeHost, "REPRESENTATIVE_HOST_INVALID");
        Optional<InventoryRoute> lpm = longestPrefixMatch(ribRoutes, hostInt);
        Optional<String> xmlPrefix = uniqueChildText(result.get(), "prefix")
                .or(() -> uniqueChildText(result.get(), "destination"))
                .flatMap(StaticRouteR0Parsers::normalizeDevicePrefix);

        String selectedPrefix = xmlPrefix
                .or(() -> lpm.map(InventoryRoute::destination))
                .orElse("UNKNOWN");
        if ("UNKNOWN".equals(selectedPrefix)) {
            return unknownPath(representativeHost, "PREFIX_NOT_RESOLVED");
        }
        String nextHop = nhIp.orElseGet(() -> lpm.flatMap(InventoryRoute::nextHop).orElse("CONNECTED"));
        String protocol = lpm.map(InventoryRoute::protocol).orElse(InventoryRoute.PROTOCOL_UNKNOWN);
        String digest = StaticRoutePreview.sha256Hex(selectedPrefix + "|" + nextHop + "|" + iface.get() + "|" + protocol);
        return new BeforePathObservation(
                representativeHost,
                "OBSERVED",
                "PATH_RECORDED",
                selectedPrefix,
                nextHop,
                iface.get(),
                protocol,
                digest);
    }

    // ---- 3. Cisco ASA R0 Parsers ----

    /**
     * R0-ASA-3 ({@code changeto context <CTX_NAME>}): verifies the prompt ends as {@code <host>/<CTX_NAME>#}.
     */
    public static boolean verifyAsaContextPrompt(String observedPromptOrOutput, String contextName) {
        if (observedPromptOrOutput == null || contextName == null || contextName.isBlank()) {
            return false;
        }
        Pattern expected = Pattern.compile("\\A[A-Za-z0-9_.-]+/" + Pattern.quote(contextName) + "#\\z");
        String[] lines = observedPromptOrOutput.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String candidate = lines[i].strip();
            if (!candidate.isEmpty()) {
                return expected.matcher(candidate).matches();
            }
        }
        return false;
    }

    private static final Pattern ASA_RUNNING_ROUTE_LINE = Pattern.compile(
            "\\Aroute\\s+([A-Za-z0-9_./:-]+)\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+(\\S+)"
            + "(?:\\s+(\\d+))?(?:\\s+(.*))?\\z");
    private static final Pattern ASA_UNSUPPORTED_ROUTE_TOKEN = Pattern.compile(
            "(?i)\\b(track|tunneled|null0)\\b");

    /**
     * R0-ASA-4 ({@code show running-config route}): empty output means zero static routes; any unrecognised line fails
     * closed ({@code UNKNOWN}); {@code track}/{@code null0}/{@code tunneled} flags {@code UNSUPPORTED_ROUTE_ATTRIBUTE}.
     */
    public static ConfiguredRoutesParse parseAsaRunningConfigRoutes(String output) {
        if (output == null || CiscoAsaPlan.isCliError(output)) {
            return new ConfiguredRoutesParse(STATUS_UNKNOWN, "STATIC_ROUTE_CONFIG_UNAVAILABLE", List.of(), null);
        }
        List<ConfiguredStaticRoute> routes = new ArrayList<>();
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("!") || line.startsWith(":")) {
                continue;
            }
            Matcher m = ASA_RUNNING_ROUTE_LINE.matcher(line);
            if (!m.matches()) {
                return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
            }
            String iface = m.group(1);
            Optional<String> destCidr = normalizeIpAndMask(m.group(2), m.group(3));
            if (destCidr.isEmpty()) {
                return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
            }
            String rawNh = m.group(4);
            Integer distance = m.group(5) != null ? Integer.parseInt(m.group(5)) : 1;
            String tail = m.group(6) == null ? "" : m.group(6).strip();

            boolean unsupported = rawNh.equalsIgnoreCase("null0")
                    || ASA_UNSUPPORTED_ROUTE_TOKEN.matcher(tail).find()
                    || !tail.isEmpty();
            String unsupDetail = rawNh.equalsIgnoreCase("null0") ? "null0" : (!tail.isEmpty() ? tail : null);
            String nhIp = "";
            if (!rawNh.equalsIgnoreCase("null0")) {
                Optional<String> parsedNh = normalizeDeviceIpv4(rawNh);
                if (parsedNh.isEmpty()) {
                    return new ConfiguredRoutesParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of(), null);
                }
                nhIp = parsedNh.get();
            }

            routes.add(new ConfiguredStaticRoute(
                    destCidr.get(),
                    nhIp,
                    iface,
                    distance,
                    null,
                    null,
                    null,
                    null,
                    false,
                    unsupported,
                    unsupDetail));
        }
        return new ConfiguredRoutesParse(STATUS_PASS, "PARSED", routes, computeRoutesDigest(routes));
    }

    /**
     * R0-ASA-5 ({@code show route}): delegates to {@link CiscoAsaPlan#parseRoutes(String)}.
     */
    public static RibParse parseAsaRib(String output) {
        if (output == null || output.isBlank() || CiscoAsaPlan.isCliError(output)) {
            return new RibParse(STATUS_UNKNOWN, "RIB_UNAVAILABLE", List.of());
        }
        List<InventoryRoute> routes = CiscoAsaPlan.parseRoutes(output);
        if (routes.isEmpty()) {
            return new RibParse(STATUS_UNKNOWN, "ANSWER_NOT_RECOGNISED", List.of());
        }
        return new RibParse(STATUS_PASS, "PARSED", routes);
    }

    private static final Pattern ASA_ROUTING_ENTRY_HEADER = Pattern.compile(
            "(?m)^Routing entry for (\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)");
    private static final Pattern ASA_KNOWN_VIA = Pattern.compile(
            "(?m)^\\s*Known via \"([^\"]+)\"");
    private static final Pattern ASA_DESCRIPTOR_VIA = Pattern.compile(
            "(?m)^\\s*\\*?\\s*(\\d+\\.\\d+\\.\\d+\\.\\d+),\\s*via\\s+([A-Za-z0-9_./:-]+)");
    private static final Pattern ASA_DESCRIPTOR_CONNECTED = Pattern.compile(
            "(?m)^\\s*\\*?\\s*directly connected,\\s*via\\s+([A-Za-z0-9_./:-]+)");

    /**
     * R0-ASA-6 ({@code show route <HOST>}): supports both detailed {@code Routing entry for ...} output and tabular
     * {@code show route} output; returns {@code UNKNOWN} on "not in table" or unrecognised syntax.
     */
    public static BeforePathObservation parseAsaRouteHost(String output, String representativeHost) {
        if (output == null || output.isBlank() || CiscoAsaPlan.isCliError(output)) {
            return unknownPath(representativeHost, "PATH_LOOKUP_UNAVAILABLE");
        }
        String lower = output.toLowerCase(Locale.ROOT);
        if (lower.contains("not in table") || lower.contains("no route")) {
            return unknownPath(representativeHost, "ROUTE_NOT_IN_TABLE");
        }

        Matcher entryHeader = ASA_ROUTING_ENTRY_HEADER.matcher(output);
        if (entryHeader.find()) {
            Optional<String> selectedPrefix = normalizeIpAndMask(entryHeader.group(1), entryHeader.group(2));
            if (selectedPrefix.isEmpty()) {
                return unknownPath(representativeHost, "ANSWER_NOT_RECOGNISED");
            }
            Matcher knownVia = ASA_KNOWN_VIA.matcher(output);
            String rawProto = knownVia.find() ? knownVia.group(1).toLowerCase(Locale.ROOT) : "unknown";
            String protocol = switch (rawProto) {
                case "connected" -> InventoryRoute.PROTOCOL_CONNECTED;
                case "static" -> "0.0.0.0/0".equals(selectedPrefix.get())
                        ? InventoryRoute.PROTOCOL_DEFAULT : InventoryRoute.PROTOCOL_STATIC;
                case "ospf" -> InventoryRoute.PROTOCOL_OSPF;
                case "bgp" -> InventoryRoute.PROTOCOL_BGP;
                case "rip" -> InventoryRoute.PROTOCOL_RIP;
                default -> InventoryRoute.PROTOCOL_UNKNOWN;
            };
            Matcher viaDesc = ASA_DESCRIPTOR_VIA.matcher(output);
            if (viaDesc.find()) {
                Optional<String> nh = normalizeDeviceIpv4(viaDesc.group(1));
                if (nh.isEmpty()) {
                    return unknownPath(representativeHost, "ANSWER_NOT_RECOGNISED");
                }
                String iface = viaDesc.group(2);
                String digest = StaticRoutePreview.sha256Hex(selectedPrefix.get() + "|" + nh.get() + "|" + iface + "|" + protocol);
                return new BeforePathObservation(
                        representativeHost, "OBSERVED", "PATH_RECORDED", selectedPrefix.get(), nh.get(), iface, protocol, digest);
            }
            Matcher connDesc = ASA_DESCRIPTOR_CONNECTED.matcher(output);
            if (connDesc.find()) {
                String iface = connDesc.group(1);
                String digest = StaticRoutePreview.sha256Hex(selectedPrefix.get() + "|CONNECTED|" + iface + "|" + protocol);
                return new BeforePathObservation(
                        representativeHost, "OBSERVED", "PATH_RECORDED", selectedPrefix.get(), "CONNECTED", iface, protocol, digest);
            }
            return unknownPath(representativeHost, "ANSWER_NOT_RECOGNISED");
        }

        List<InventoryRoute> tabular = CiscoAsaPlan.parseRoutes(output);
        if (!tabular.isEmpty()) {
            InventoryRoute first = tabular.get(0);
            if (first.interfaceName().isEmpty()) {
                return unknownPath(representativeHost, "ANSWER_NOT_RECOGNISED");
            }
            String nh = first.nextHop().orElse("CONNECTED");
            String iface = first.interfaceName().get();
            String digest = StaticRoutePreview.sha256Hex(first.destination() + "|" + nh + "|" + iface + "|" + first.protocol());
            return new BeforePathObservation(
                    representativeHost, "OBSERVED", "PATH_RECORDED", first.destination(), nh, iface, first.protocol(), digest);
        }

        return unknownPath(representativeHost, "ANSWER_NOT_RECOGNISED");
    }

    // ---- 4. Stage A / Stage B Pre-Check Evaluator (Contract §4.1 Checks 1..6) ----

    public record PreCheckEvaluation(
            String overallVerdict,
            String stopCode,
            List<PreCheckVerdict> checks,
            Optional<ExistingRouteAttributes> matchedExistingRoute,
            Optional<String> preRouteTableSha256) {
    }

    /**
     * Evaluates Contract §4.1 Checks 1..6 for either Stage A (against stored inventory) or Stage B (against live R0
     * device reads plus stored interface inventory and protected control-plane addresses). Delegates to
     * {@link StaticRouteR0PreChecks}.
     */
    public static PreCheckEvaluation evaluatePreChecks(
            String stage,
            ValidatedRequest req,
            PreCheckVerdict check1TargetReady,
            PreCheckVerdict check2IdentityHaMgmt,
            ConfiguredRoutesParse configuredRoutes,
            RibParse ribParse,
            List<InventoryInterface> contextInterfaces,
            List<String> protectedControlPlaneIps) {
        return StaticRouteR0PreChecks.evaluate(stage, req, check1TargetReady, check2IdentityHaMgmt, configuredRoutes,
                ribParse, contextInterfaces, protectedControlPlaneIps);
    }


    // ---- Internal Helpers ----

    static Optional<InventoryRoute> longestPrefixMatch(List<InventoryRoute> routes, int hostIpInt) {
        if (routes == null || routes.isEmpty()) {
            return Optional.empty();
        }
        InventoryRoute best = null;
        int bestLen = -1;
        for (InventoryRoute r : routes) {
            if (r.active().isPresent() && !r.active().get()) {
                continue;
            }
            Optional<StaticRoutePreview.ParsedPrefix> parsed = parseRoutePrefix(r.destination());
            if (parsed.isEmpty()) {
                continue;
            }
            StaticRoutePreview.ParsedPrefix p = parsed.get();
            if (StaticRoutePreview.prefixContains(p.networkInt(), p.prefixLen(), hostIpInt) && p.prefixLen() > bestLen) {
                best = r;
                bestLen = p.prefixLen();
            }
        }
        return Optional.ofNullable(best);
    }

    static Optional<Integer> parsePrefixLen(String cidr) {
        return parseRoutePrefix(cidr).map(StaticRoutePreview.ParsedPrefix::prefixLen);
    }

    static Optional<StaticRoutePreview.ParsedPrefix> parseRoutePrefix(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        if ("0.0.0.0/0".equals(raw) || "default".equalsIgnoreCase(raw)) {
            return Optional.of(new StaticRoutePreview.ParsedPrefix(0, 0, 0, "0.0.0.0/0", "0.0.0.0", "0.0.0.0"));
        }
        int slash = raw.indexOf('/');
        String ipStr = slash >= 0 ? raw.substring(0, slash) : raw;
        int len = 32;
        if (slash >= 0) {
            String lenStr = raw.substring(slash + 1);
            if (!lenStr.matches("\\d{1,2}")) {
                return Optional.empty();
            }
            len = Integer.parseInt(lenStr);
            if (len < 0 || len > 32) {
                return Optional.empty();
            }
        }
        Optional<String> normIp = normalizeDeviceIpv4(ipStr);
        if (normIp.isEmpty()) {
            return Optional.empty();
        }
        int ipInt = StaticRoutePreview.parseIpv4ToInt(normIp.get(), "ROUTE_PREFIX_INVALID");
        int maskInt = StaticRoutePreview.netmaskFromPrefixLen(len);
        int netInt = ipInt & maskInt;
        String netIp = StaticRoutePreview.formatIpv4(netInt);
        return Optional.of(new StaticRoutePreview.ParsedPrefix(
                netInt, len, maskInt, netIp + "/" + len, netIp, StaticRoutePreview.formatIpv4(maskInt)));
    }

    private static Optional<String> normalizeDevicePrefix(String raw) {
        return parseRoutePrefix(raw).map(StaticRoutePreview.ParsedPrefix::canonicalCidr);
    }

    private static Optional<String> normalizeIpAndMask(String rawIp, String rawMask) {
        Optional<String> ip = normalizeDeviceIpv4(rawIp);
        Optional<String> mask = normalizeDeviceIpv4(rawMask);
        if (ip.isEmpty() || mask.isEmpty()) {
            return Optional.empty();
        }
        int ipInt = StaticRoutePreview.parseIpv4ToInt(ip.get(), "IP_INVALID");
        int maskInt = StaticRoutePreview.parseIpv4ToInt(mask.get(), "MASK_INVALID");
        // Verify contiguous netmask
        int inverted = ~maskInt;
        if ((inverted & (inverted + 1)) != 0) {
            return Optional.empty();
        }
        int len = Integer.bitCount(maskInt);
        int netInt = ipInt & maskInt;
        return Optional.of(StaticRoutePreview.formatIpv4(netInt) + "/" + len);
    }

    static Optional<String> normalizeDeviceIpv4(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            int ipInt = StaticRoutePreview.parseIpv4ToInt(raw.strip(), "IPV4_INVALID");
            return Optional.of(StaticRoutePreview.formatIpv4(ipInt));
        } catch (StaticRoutePreview.ValidationException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> tokenAfter(String[] tokens, int from, String keyword) {
        for (int i = from; i + 1 < tokens.length; i++) {
            if (keyword.equals(tokens[i])) {
                return Optional.of(tokens[i + 1]);
            }
        }
        return Optional.empty();
    }

    private static BeforePathObservation unknownPath(String representativeHost, String reasonCode) {
        return new BeforePathObservation(representativeHost, STATUS_UNKNOWN, reasonCode, null, null, null, null, null);
    }

    private static String computeRoutesDigest(List<ConfiguredStaticRoute> routes) {
        List<String> lines = routes.stream()
                .map(r -> String.join("|",
                        Objects.toString(r.destinationCidr(), ""),
                        Objects.toString(r.nextHopIp(), ""),
                        Objects.toString(r.interfaceName(), ""),
                        Objects.toString(r.distance(), ""),
                        Objects.toString(r.metric(), ""),
                        Objects.toString(r.priority(), ""),
                        Objects.toString(r.routeName(), ""),
                        Objects.toString(r.comment(), ""),
                        String.valueOf(r.templateManaged()),
                        String.valueOf(r.unsupportedAttribute())))
                .sorted(Comparator.naturalOrder())
                .toList();
        return StaticRoutePreview.sha256Hex(String.join("\n", lines));
    }

    private static Optional<Element> parsePanXmlResponse(String xml, int maxBytes) {
        if (xml == null || xml.isBlank() || xml.length() > maxBytes) {
            return Optional.empty();
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setExpandEntityReferences(false);
            Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            return root != null && "response".equals(root.getTagName()) ? Optional.of(root) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<Element> uniqueChild(Element parent, String tagName) {
        if (parent == null) {
            return Optional.empty();
        }
        Element found = null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && tagName.equals(el.getTagName())) {
                if (found != null) {
                    return Optional.empty();
                }
                found = el;
            }
        }
        return Optional.ofNullable(found);
    }

    private static Optional<String> uniqueChildText(Element parent, String tagName) {
        return uniqueChild(parent, tagName).map(e -> e.getTextContent().strip());
    }

    private static List<Element> childElements(Element parent) {
        List<Element> list = new ArrayList<>();
        if (parent == null) {
            return list;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el) {
                list.add(el);
            }
        }
        return list;
    }

    private static List<Element> directChildEntries(Element parent) {
        List<Element> list = new ArrayList<>();
        if (parent == null) {
            return list;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && "entry".equals(el.getTagName())) {
                list.add(el);
            }
        }
        return list;
    }

    private static boolean hasTemplateSrcAttribute(Element element, boolean checkDescendants) {
        if (element == null) {
            return false;
        }
        String src = element.getAttribute("src");
        if (src != null && !src.isBlank() && src.toLowerCase(Locale.ROOT).startsWith("tpl")) {
            return true;
        }
        if (!checkDescendants) {
            return false;
        }
        NodeList children = element.getElementsByTagName("*");
        for (int i = 0; i < children.getLength(); i++) {
            Element child = (Element) children.item(i);
            String childSrc = child.getAttribute("src");
            if (childSrc != null && !childSrc.isBlank() && childSrc.toLowerCase(Locale.ROOT).startsWith("tpl")) {
                return true;
            }
        }
        return false;
    }
}
