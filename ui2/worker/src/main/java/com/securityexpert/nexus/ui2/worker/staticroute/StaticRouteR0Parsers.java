package com.securityexpert.nexus.ui2.worker.staticroute;

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

import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.BeforePathObservation;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.ExistingRouteAttributes;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.PreCheckVerdict;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.ValidatedRequest;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.worker.backup.asa.CiscoAsaPlan;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedRoute;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointHaStateParser;
import com.securityexpert.nexus.ui2.worker.inventory.cp.CheckPointIpRouteParser;

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

    public record PanHaResult(String status, String reasonCode, String localRole, String runningSync) {
    }

    public record PanEffectiveRunningResult(
            String status,
            String reasonCode,
            boolean vrFound,
            boolean templateManaged) {
    }

    /** R0-PAN-3: delegates to {@link PaloAltoR0Parsers#parsePanPendingChanges(String)}. */
    public static PanPendingChangesResult parsePanPendingChanges(String xml) {
        return PaloAltoR0Parsers.parsePanPendingChanges(xml);
    }

    /** R0-PAN-5: delegates to {@link PaloAltoR0Parsers#parsePanHaState(String)}. */
    public static PanHaResult parsePanHaState(String xml) {
        return PaloAltoR0Parsers.parsePanHaState(xml);
    }

    /** R0-PAN-4: delegates to {@link PaloAltoR0Parsers#parsePanEffectiveRunning(String, String, String, String)}. */
    public static PanEffectiveRunningResult parsePanEffectiveRunning(
            String xml,
            String virtualRouter,
            String targetDestinationCidr,
            String targetRouteName) {
        return PaloAltoR0Parsers.parsePanEffectiveRunning(xml, virtualRouter, targetDestinationCidr, targetRouteName);
    }

    /** R0-PAN-1: delegates to {@link PaloAltoR0Parsers#parsePanVrStaticRoutes(String)}. */
    public static ConfiguredRoutesParse parsePanVrStaticRoutes(String xml) {
        return PaloAltoR0Parsers.parsePanVrStaticRoutes(xml);
    }

    /** R0-PAN-6: delegates to {@link PaloAltoR0Parsers#parsePanRib(String, String)}. */
    public static RibParse parsePanRib(String xml, String virtualRouter) {
        return PaloAltoR0Parsers.parsePanRib(xml, virtualRouter);
    }

    /** R0-PAN-2: delegates to {@link PaloAltoR0Parsers#parsePanFibLookup(String, String, List)}. */
    public static BeforePathObservation parsePanFibLookup(
            String xml,
            String representativeHost,
            List<InventoryRoute> ribRoutes) {
        return PaloAltoR0Parsers.parsePanFibLookup(xml, representativeHost, ribRoutes);
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

    static Optional<String> normalizeDevicePrefix(String raw) {
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

    static BeforePathObservation unknownPath(String representativeHost, String reasonCode) {
        return new BeforePathObservation(representativeHost, STATUS_UNKNOWN, reasonCode, null, null, null, null, null);
    }

    static String computeRoutesDigest(List<ConfiguredStaticRoute> routes) {
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

}
