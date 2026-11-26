package com.securityexpert.nexus.ui2.jobs.staticroute;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.capability.GateResolver;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * Operations static route -- Phase R0 (approved CLASS_0 reads, Stage A/B pre-check model, deterministic command-plan
 * and inverse-plan preview; ZERO writes).
 *
 * <p>Authorities:
 * <ul>
 *   <li>{@code docs/design/OPERATIONS_STATIC_ROUTE_R0_READ_COMMANDS_2026_11_18.md} (APPROVED by PO 2026-11-18)</li>
 *   <li>{@code docs/design/OPERATIONS_STATIC_ROUTE_CHANGE_CONTRACT_DRAFT_2026_11_17.md} (§1.3, §3, §4.1, §5.1–5.4,
 *       Path-confirmation appendix, R0 approval appendix)</li>
 * </ul>
 *
 * <p>Hard invariants enforced here:
 * <ul>
 *   <li>Only {@link ActionClass#CLASS_0_READ} gates exist in {@link ReadGate}; no write gate is registered or resolvable.</li>
 *   <li>{@code mutationPossible} is unconditionally {@code false} in R0.</li>
 *   <li>All string identifiers are validated with whole-string {@code \A...\z} anchors (never {@code ^...$}).</li>
 *   <li>All IPv4 addresses and CIDR prefixes are parsed to 32-bit integers and rebuilt from integer octets.</li>
 *   <li>Palo Alto XML fragments are built with {@link XMLStreamWriter}, never string concatenation.</li>
 * </ul>
 */
public final class StaticRoutePreview {

    public static final String CAPABILITY = "static_route_preview";
    public static final String GATE_VERSION = "R0-2026-11-18";
    public static final int OUTPUT_LIMIT_BYTES = 262_144;
    public static final int EFFECTIVE_RUNNING_LIMIT_BYTES = 16 * 1024 * 1024;

    public static final String VENDOR_CHECK_POINT = "check_point";
    public static final String VENDOR_PALO_ALTO = "palo_alto";
    public static final String VENDOR_CISCO_ASA = "cisco_asa";
    public static final String VENDOR_FORTINET = "fortinet";
    public static final Set<String> SUPPORTED_VENDORS = Set.of(VENDOR_CHECK_POINT, VENDOR_PALO_ALTO, VENDOR_CISCO_ASA);

    public static final String OP_ADD = "ADD";
    public static final String OP_DELETE = "DELETE";

    public static final String CP_DEFAULT_CONTEXT = "gaia-default";
    public static final String ASA_SINGLE_CONTEXT = "single";
    public static final String PREVIEW_ID_PREFIX = "srp-";

    // Whole-string anchor patterns (\A...\z) per Contract §5.1 invariant 1 and §5.3.
    private static final Pattern IPV4_OCTETS = Pattern.compile(
            "\\A(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\."
            + "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\."
            + "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\."
            + "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\z");
    private static final Pattern CIDR_FORMAT = Pattern.compile("\\A([^/]+)/([0-9]{1,2})\\z");
    private static final Pattern PAN_VR_NAME = Pattern.compile("\\A[A-Za-z0-9_.-]{1,63}\\z");
    private static final Pattern ASA_CTX_NAME = Pattern.compile("\\A[A-Za-z0-9_.-]{1,64}\\z");
    private static final Pattern INTERFACE_NAME = Pattern.compile("\\A[A-Za-z0-9_./:-]{1,48}\\z");
    private static final Pattern PAN_ROUTE_NAME = Pattern.compile("\\A[A-Za-z0-9_.-]{1,31}\\z");
    private static final Pattern DEVICE_COMMENT = Pattern.compile("\\A[A-Za-z0-9 _.:/-]{0,80}\\z");
    private static final Pattern TICKET_REF = Pattern.compile("\\A[A-Za-z0-9_.:/-]{3,64}\\z");
    private static final Pattern REASON = Pattern.compile("\\A[\\x20-\\x7E]{8,255}\\z");
    private static final Pattern ADMIN_USER = Pattern.compile("\\A[A-Za-z0-9_.-]{1,31}\\z");
    private static final Pattern PREVIEW_ID = Pattern.compile(
            "\\Asrp-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\z");

    private StaticRoutePreview() {
    }

    /**
     * Closed set of R0 read-only gates (7 NEW + 9 REUSED from OPERATIONS_STATIC_ROUTE_R0_READ_COMMANDS_2026_11_18.md).
     * Every entry is CLASS_0_READ. R0-CP-5 is not built (UNKNOWN). FortiGate is out of scope.
     */
    public enum ReadGate {
        // Check Point (cp_gaia_gateway, expert, SSH_EXEC)
        R0_CP_1_SHOW_ROUTE_STATIC("cp_route_show_route_static", VENDOR_CHECK_POINT, "cp_gaia_gateway",
                "expert", "SSH_EXEC", "clish -c 'show route static'", 30, true),
        R0_CP_2_IP_ROUTE_GET("cp_route_ip_route_get", VENDOR_CHECK_POINT, "cp_gaia_gateway",
                "expert", "SSH_EXEC", "ip -4 route get <DST>", 30, true),
        R0_CP_3_IP_ROUTE_SHOW_TABLE_ALL("cp_inventory_ip_route_show_table_all", VENDOR_CHECK_POINT, "cp_gaia_gateway",
                "expert", "SSH_EXEC", "ip -4 route show table all", 30, false),
        R0_CP_4_CPHAPROB_STAT("cp_inventory_cphaprob_stat", VENDOR_CHECK_POINT, "cp_gaia_gateway",
                "expert", "SSH_EXEC", "cphaprob stat", 30, false),

        // Palo Alto (pan_firewall, not_applicable, PAN_XML_API)
        R0_PAN_1_SHOW_VR_STATIC_ROUTE("pan_route_show_vr_static_route", VENDOR_PALO_ALTO, "pan_firewall",
                "not_applicable", "PAN_XML_API",
                "type=config&action=show&xpath=/config/devices/entry[@name='localhost.localdomain']/network/virtual-router/entry[@name='<VR>']/routing-table/ip/static-route",
                30, true),
        R0_PAN_2_FIB_LOOKUP("path_validation_pan_fib_lookup", VENDOR_PALO_ALTO, "pan_firewall",
                "not_applicable", "PAN_XML_API",
                "<test><routing><fib-lookup><virtual-router>VR</virtual-router><ip>DST</ip></fib-lookup></routing></test>",
                30, false),
        R0_PAN_3_CHECK_PENDING_CHANGES("pan_route_check_pending_changes", VENDOR_PALO_ALTO, "pan_firewall",
                "not_applicable", "PAN_XML_API",
                "type=op&cmd=<check><pending-changes/></check>", 30, true),
        R0_PAN_4_EFFECTIVE_RUNNING("pan_configuration_effective_running", VENDOR_PALO_ALTO, "pan_firewall",
                "not_applicable", "PAN_XML_API",
                "<show><config><effective-running/></config></show>", 120, false),
        R0_PAN_5_SHOW_HA_STATE("pan_inventory_show_ha_state", VENDOR_PALO_ALTO, "pan_firewall",
                "not_applicable", "PAN_XML_API",
                "<show><high-availability><state/></high-availability></show>", 30, false),
        R0_PAN_6_SHOW_ROUTING_ROUTE("pan_inventory_show_routing_route", VENDOR_PALO_ALTO, "pan_firewall",
                "not_applicable", "PAN_XML_API",
                "<show><routing><route/></routing></show>", 30, false),

        // Cisco ASA (cisco_asa_firewall)
        R0_ASA_1_SHOW_MODE("asa_show_mode", VENDOR_CISCO_ASA, "cisco_asa_firewall",
                "privileged_exec", "SSH_EXEC", "show mode", 30, false),
        R0_ASA_2_SHOW_CURPRIV("asa_show_curpriv", VENDOR_CISCO_ASA, "cisco_asa_firewall",
                "privileged_exec", "SSH_EXEC", "show curpriv", 30, false),
        R0_ASA_3_CHANGETO_CONTEXT("asa_route_changeto_context", VENDOR_CISCO_ASA, "cisco_asa_firewall",
                "context", "SSH_INTERACTIVE", "changeto context <CTX_NAME>", 30, true),
        R0_ASA_4_SHOW_RUNNING_ROUTE("asa_route_show_running_route", VENDOR_CISCO_ASA, "cisco_asa_firewall",
                "privileged_exec", "SSH_INTERACTIVE", "show running-config route", 30, true),
        R0_ASA_5_SHOW_ROUTE("asa_show_route", VENDOR_CISCO_ASA, "cisco_asa_firewall",
                "privileged_exec", "SSH_EXEC", "show route", 180, false),
        R0_ASA_6_SHOW_ROUTE_HOST("asa_route_show_route_host", VENDOR_CISCO_ASA, "cisco_asa_firewall",
                "privileged_exec", "SSH_INTERACTIVE", "show route <HOST>", 30, true);

        private final String gateId;
        private final String vendor;
        private final String platformRoleScope;
        private final String shellContext;
        private final String transportKind;
        private final String template;
        private final int timeoutS;
        private final boolean newInR0;

        ReadGate(String gateId, String vendor, String platformRoleScope, String shellContext, String transportKind,
                String template, int timeoutS, boolean newInR0) {
            this.gateId = gateId;
            this.vendor = vendor;
            this.platformRoleScope = platformRoleScope;
            this.shellContext = shellContext;
            this.transportKind = transportKind;
            this.template = template;
            this.timeoutS = timeoutS;
            this.newInR0 = newInR0;
        }

        public String gateId() { return gateId; }
        public String vendor() { return vendor; }
        public String platformRoleScope() { return platformRoleScope; }
        public String shellContext() { return shellContext; }
        public String transportKind() { return transportKind; }
        public String template() { return template; }
        public int timeoutS() { return timeoutS; }
        public boolean newInR0() { return newInR0; }

        public CanonicalCommandKey key() {
            return new CanonicalCommandKey(vendor, platformRoleScope, shellContext, transportKind, template);
        }
    }

    public static final Set<String> NEW_R0_GATE_IDS = Set.of(
            ReadGate.R0_CP_1_SHOW_ROUTE_STATIC.gateId(),
            ReadGate.R0_CP_2_IP_ROUTE_GET.gateId(),
            ReadGate.R0_PAN_1_SHOW_VR_STATIC_ROUTE.gateId(),
            ReadGate.R0_PAN_3_CHECK_PENDING_CHANGES.gateId(),
            ReadGate.R0_ASA_3_CHANGETO_CONTEXT.gateId(),
            ReadGate.R0_ASA_4_SHOW_RUNNING_ROUTE.gateId(),
            ReadGate.R0_ASA_6_SHOW_ROUTE_HOST.gateId());

    /**
     * Resolves one of the closed R0 {@link ReadGate} rows against the live registry; enforces {@link ActionClass#CLASS_0_READ}.
     */
    public static Optional<GateResolution.Known> resolve(ReadGate gate, GateRegistryPort registry) {
        if (gate == null || registry == null) {
            return Optional.empty();
        }
        try {
            var resolved = GateResolver.resolve(gate.key(), Optional.of(ActionClass.CLASS_0_READ), registry);
            return resolved instanceof GateResolution.Known known
                    && gate.gateId().equals(known.gateId())
                    && known.actionClass() == ActionClass.CLASS_0_READ
                    && known.timeoutS() == gate.timeoutS()
                    ? Optional.of(known)
                    : Optional.empty();
        } catch (RuntimeException invalidGate) {
            return Optional.empty();
        }
    }

    // ---- Approved R0 Read Command Builders (used by the worker to fill validated placeholders) ----

    public static String cpIpRouteGet(String destinationHost) {
        int ip = parseAndValidateHostIpv4(destinationHost, "REPRESENTATIVE_HOST_INVALID");
        return fillOnce(ReadGate.R0_CP_2_IP_ROUTE_GET.template(), "<DST>", formatIpv4(ip));
    }

    public static String panShowVrStaticRouteXpath(String virtualRouter) {
        if (virtualRouter == null || !PAN_VR_NAME.matcher(virtualRouter).matches()) {
            throw new ValidationException("ROUTING_CONTEXT_INVALID", "Invalid virtual router name.");
        }
        return "/config/devices/entry[@name='localhost.localdomain']/network/virtual-router/entry[@name='"
                + virtualRouter + "']/routing-table/ip/static-route";
    }

    public static String panFibLookupCmd(String virtualRouter, String destinationHost) {
        if (virtualRouter == null || !PAN_VR_NAME.matcher(virtualRouter).matches()) {
            throw new ValidationException("ROUTING_CONTEXT_INVALID", "Invalid virtual router name.");
        }
        int ip = parseAndValidateHostIpv4(destinationHost, "REPRESENTATIVE_HOST_INVALID");
        String host = formatIpv4(ip);
        try {
            StringWriter sw = new StringWriter();
            XMLStreamWriter xml = XMLOutputFactory.newInstance().createXMLStreamWriter(sw);
            xml.writeStartElement("test");
            xml.writeStartElement("routing");
            xml.writeStartElement("fib-lookup");
            xml.writeStartElement("virtual-router");
            xml.writeCharacters(virtualRouter);
            xml.writeEndElement();
            xml.writeStartElement("ip");
            xml.writeCharacters(host);
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndElement();
            xml.flush();
            return sw.toString();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Failed to encode PAN fib-lookup XML", e);
        }
    }

    public static String asaChangetoContext(String contextName) {
        if (contextName == null || !ASA_CTX_NAME.matcher(contextName).matches()
                || ASA_SINGLE_CONTEXT.equalsIgnoreCase(contextName)) {
            throw new ValidationException("ROUTING_CONTEXT_INVALID", "Invalid ASA security context name.");
        }
        return fillOnce(ReadGate.R0_ASA_3_CHANGETO_CONTEXT.template(), "<CTX_NAME>", contextName);
    }

    public static String asaShowRouteHost(String destinationHost) {
        int ip = parseAndValidateHostIpv4(destinationHost, "REPRESENTATIVE_HOST_INVALID");
        return fillOnce(ReadGate.R0_ASA_6_SHOW_ROUTE_HOST.template(), "<HOST>", formatIpv4(ip));
    }

    private static String fillOnce(String template, String placeholder, String value) {
        int at = template.indexOf(placeholder);
        if (at < 0 || template.indexOf(placeholder, at + 1) >= 0) {
            throw new IllegalStateException("Template placeholder missing or duplicated: " + placeholder);
        }
        return template.substring(0, at) + value + template.substring(at + placeholder.length());
    }

    // ---- Validation and Integer-Rebuilt IPv4 / CIDR (Contract §5.1 & §5.3) ----

    public static final class ValidationException extends RuntimeException {
        private final String code;

        public ValidationException(String code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        public String code() {
            return code;
        }
    }

    public record ParsedPrefix(
            int networkInt,
            int prefixLen,
            int netmaskInt,
            String canonicalCidr,
            String networkIp,
            String dottedNetmask) {
    }

    public record ValidatedRequest(
            String unitId,
            String vendor,
            String routingContext,
            String operation,
            ParsedPrefix prefix,
            int nextHopInt,
            String nextHopIp,
            String interfaceName,
            Integer distance,
            Integer metric,
            Integer priority,
            String routeName,
            String deviceComment,
            int representativeHostInt,
            String representativeHost,
            String changeTicketRef,
            String reason) {
    }

    /** Validates the V252 {@code static_route_preview.preview_id} format ({@code srp-<lowercase-uuid>}). */
    public static boolean isValidPreviewId(String value) {
        return value != null && PREVIEW_ID.matcher(value).matches();
    }

    /** Alias for {@link #isValidPreviewId(String)} retained for callers checking preview identifiers. */
    public static boolean isValidUuid(String value) {
        return isValidPreviewId(value);
    }

    public static int parseIpv4ToInt(String raw, String errorCode) {
        if (raw == null) {
            throw new ValidationException(errorCode, "IPv4 address is required.");
        }
        Matcher m = IPV4_OCTETS.matcher(raw);
        if (!m.matches()) {
            throw new ValidationException(errorCode, "Invalid IPv4 address format.");
        }
        int o1 = Integer.parseInt(m.group(1));
        int o2 = Integer.parseInt(m.group(2));
        int o3 = Integer.parseInt(m.group(3));
        int o4 = Integer.parseInt(m.group(4));
        return ((o1 & 0xFF) << 24) | ((o2 & 0xFF) << 16) | ((o3 & 0xFF) << 8) | (o4 & 0xFF);
    }

    public static String formatIpv4(int addr) {
        return String.format(Locale.ROOT, "%d.%d.%d.%d",
                (addr >>> 24) & 0xFF,
                (addr >>> 16) & 0xFF,
                (addr >>> 8) & 0xFF,
                addr & 0xFF);
    }

    public static int netmaskFromPrefixLen(int prefixLen) {
        if (prefixLen < 0 || prefixLen > 32) {
            throw new IllegalArgumentException("prefixLen out of range: " + prefixLen);
        }
        return prefixLen == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefixLen));
    }

    public static ParsedPrefix parseAndValidateDestinationPrefix(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException("INVALID_DESTINATION_PREFIX", "Destination prefix is required.");
        }
        if ("0.0.0.0/0".equals(raw) || "default".equalsIgnoreCase(raw)) {
            throw new ValidationException("DEFAULT_ROUTE_FORBIDDEN",
                    "Default route (0.0.0.0/0) is unconditionally forbidden.");
        }
        Matcher cm = CIDR_FORMAT.matcher(raw);
        if (!cm.matches()) {
            throw new ValidationException("INVALID_DESTINATION_PREFIX",
                    "Destination prefix must be canonical IPv4 CIDR A.B.C.D/M.");
        }
        int prefixLen = Integer.parseInt(cm.group(2));
        if (prefixLen == 0) {
            throw new ValidationException("DEFAULT_ROUTE_FORBIDDEN",
                    "Default route (/0) is unconditionally forbidden.");
        }
        if (prefixLen < 1 || prefixLen > 32) {
            throw new ValidationException("INVALID_DESTINATION_PREFIX", "Prefix length must be in 1..32.");
        }
        int ipInt = parseIpv4ToInt(cm.group(1), "INVALID_DESTINATION_PREFIX");
        int maskInt = netmaskFromPrefixLen(prefixLen);
        if ((ipInt & maskInt) != ipInt) {
            throw new ValidationException("NON_CANONICAL_PREFIX",
                    "Host bits after mask must be 0 (use " + formatIpv4(ipInt & maskInt) + "/" + prefixLen + ").");
        }
        rejectReservedOrNonUnicast(ipInt, "INVALID_DESTINATION_PREFIX");
        String networkIp = formatIpv4(ipInt);
        String dottedNetmask = formatIpv4(maskInt);
        String canonicalCidr = networkIp + "/" + prefixLen;
        return new ParsedPrefix(ipInt, prefixLen, maskInt, canonicalCidr, networkIp, dottedNetmask);
    }

    public static int parseAndValidateHostIpv4(String raw, String errorCode) {
        int ipInt = parseIpv4ToInt(raw, errorCode);
        rejectReservedOrNonUnicast(ipInt, errorCode);
        return ipInt;
    }

    private static void rejectReservedOrNonUnicast(int ipInt, String errorCode) {
        int firstOctet = (ipInt >>> 24) & 0xFF;
        int first16 = (ipInt >>> 16) & 0xFFFF;
        if (ipInt == 0 || firstOctet == 0) {
            throw new ValidationException(errorCode, "Unspecified 0.0.0.0/8 address is not permitted.");
        }
        if (firstOctet == 127) {
            throw new ValidationException(errorCode, "Loopback 127.0.0.0/8 address is not permitted.");
        }
        if (first16 == 0xA9FE) {
            throw new ValidationException(errorCode, "RFC 3927 link-local 169.254.0.0/16 address is not permitted.");
        }
        if (firstOctet >= 224) {
            throw new ValidationException(errorCode, "Multicast, reserved, or broadcast address is not permitted.");
        }
    }

    public static int resolveRepresentativeHost(ParsedPrefix prefix, String rawRepresentativeHost) {
        if (rawRepresentativeHost == null || rawRepresentativeHost.isBlank()) {
            return prefix.prefixLen() >= 31 ? prefix.networkInt() : (prefix.networkInt() + 1);
        }
        int hostInt = parseAndValidateHostIpv4(rawRepresentativeHost, "REPRESENTATIVE_HOST_INVALID");
        if ((hostInt & prefix.netmaskInt()) != prefix.networkInt()) {
            throw new ValidationException("REPRESENTATIVE_HOST_OUTSIDE_PREFIX",
                    "Representative host must lie inside destinationPrefix.");
        }
        return hostInt;
    }

    public static boolean prefixContains(int networkInt, int prefixLen, int candidateIpInt) {
        if (prefixLen <= 0) {
            return true;
        }
        int mask = netmaskFromPrefixLen(prefixLen);
        return (candidateIpInt & mask) == (networkInt & mask);
    }

    public static boolean prefixesOverlap(int netA, int lenA, int netB, int lenB) {
        int minLen = Math.min(lenA, lenB);
        if (minLen <= 0) {
            return true;
        }
        int mask = netmaskFromPrefixLen(minLen);
        return (netA & mask) == (netB & mask);
    }

    public static ValidatedRequest validateRequest(
            String unitId,
            String vendor,
            String routingContext,
            String operation,
            String destinationPrefix,
            String nextHopIp,
            String interfaceName,
            Integer distance,
            Integer metric,
            Integer priority,
            String routeName,
            String deviceComment,
            String representativeHost,
            String changeTicketRef,
            String reason) {

        if (unitId == null || unitId.isBlank() || unitId.length() > 128) {
            throw new ValidationException("INVALID_UNIT_ID", "unitId is required.");
        }
        if (VENDOR_FORTINET.equalsIgnoreCase(vendor)) {
            throw new ValidationException("FORTIMANAGER_MANAGED",
                    "FortiGate is out of scope for local static-route operations (FortiManager-managed).");
        }
        if (vendor == null || !SUPPORTED_VENDORS.contains(vendor)) {
            throw new ValidationException("VENDOR_NOT_SUPPORTED", "Unsupported vendor for static-route preview.");
        }
        if (!OP_ADD.equals(operation) && !OP_DELETE.equals(operation)) {
            throw new ValidationException("INVALID_OPERATION", "operation must be ADD or DELETE.");
        }

        ParsedPrefix prefix = parseAndValidateDestinationPrefix(destinationPrefix);
        int nextHopInt = parseAndValidateHostIpv4(nextHopIp, "INVALID_NEXTHOP_IP");
        String canonicalNextHop = formatIpv4(nextHopInt);
        int repHostInt = resolveRepresentativeHost(prefix, representativeHost);
        String canonicalRepHost = formatIpv4(repHostInt);

        String resolvedContext;
        String resolvedInterface = (interfaceName == null || interfaceName.isBlank()) ? null : interfaceName;
        Integer resolvedDistance = distance;
        Integer resolvedMetric = metric;
        Integer resolvedPriority = priority;
        String resolvedRouteName = (routeName == null || routeName.isBlank()) ? null : routeName;
        String resolvedComment = (deviceComment == null || deviceComment.isBlank()) ? null : deviceComment;

        switch (vendor) {
            case VENDOR_CHECK_POINT -> {
                String ctx = (routingContext == null || routingContext.isBlank()) ? CP_DEFAULT_CONTEXT : routingContext;
                if (!CP_DEFAULT_CONTEXT.equals(ctx)) {
                    throw new ValidationException("ROUTING_CONTEXT_UNSUPPORTED",
                            "Check Point Wave 1 supports gaia-default (non-VSX) only.");
                }
                resolvedContext = CP_DEFAULT_CONTEXT;
                if (resolvedInterface != null) {
                    throw new ValidationException("INTERFACE_NOT_SUPPORTED_ON_VENDOR",
                            "interfaceName is not supported on Check Point Gaia static routes.");
                }
                if (resolvedDistance != null) {
                    throw new ValidationException("DISTANCE_NOT_SUPPORTED_ON_VENDOR",
                            "distance is not supported on Check Point Gaia static routes.");
                }
                if (resolvedMetric != null) {
                    throw new ValidationException("METRIC_NOT_SUPPORTED_ON_VENDOR",
                            "metric is not supported on Check Point Gaia static routes; use priority 1..8.");
                }
                if (resolvedPriority != null && (resolvedPriority < 1 || resolvedPriority > 8)) {
                    throw new ValidationException("INVALID_PRIORITY",
                            "Check Point static-route priority must be in 1..8.");
                }
                if (resolvedRouteName != null) {
                    throw new ValidationException("ROUTE_NAME_NOT_SUPPORTED_ON_VENDOR",
                            "routeName is supported on Palo Alto only.");
                }
                if (resolvedComment != null && !DEVICE_COMMENT.matcher(resolvedComment).matches()) {
                    throw new ValidationException("INVALID_DEVICE_COMMENT",
                            "deviceComment must be 0..80 safe ASCII characters.");
                }
            }
            case VENDOR_PALO_ALTO -> {
                if (routingContext == null || routingContext.isBlank()) {
                    throw new ValidationException("ROUTING_CONTEXT_REQUIRED",
                            "Palo Alto virtual-router name is required.");
                }
                if ("ARE".equalsIgnoreCase(routingContext)
                        || routingContext.toLowerCase(Locale.ROOT).startsWith("logical-router")) {
                    throw new ValidationException("ROUTING_CONTEXT_UNSUPPORTED",
                            "Palo Alto Advanced Routing Engine (logical-router) is not supported.");
                }
                if (!PAN_VR_NAME.matcher(routingContext).matches()) {
                    throw new ValidationException("ROUTING_CONTEXT_INVALID",
                            "Invalid Palo Alto virtual-router name.");
                }
                resolvedContext = routingContext;
                if (resolvedInterface != null && !INTERFACE_NAME.matcher(resolvedInterface).matches()) {
                    throw new ValidationException("INVALID_INTERFACE_NAME", "Invalid interfaceName.");
                }
                if (resolvedDistance != null && (resolvedDistance < 1 || resolvedDistance > 255)) {
                    throw new ValidationException("INVALID_DISTANCE",
                            "Palo Alto administrative distance must be in 1..255.");
                }
                if (resolvedDistance == null && OP_ADD.equals(operation)) {
                    resolvedDistance = 10;
                }
                if (resolvedMetric != null && (resolvedMetric < 1 || resolvedMetric > 65535)) {
                    throw new ValidationException("INVALID_METRIC", "Palo Alto metric must be in 1..65535.");
                }
                if (resolvedMetric == null && OP_ADD.equals(operation)) {
                    resolvedMetric = 10;
                }
                if (resolvedPriority != null) {
                    throw new ValidationException("PRIORITY_NOT_SUPPORTED_ON_VENDOR",
                            "priority is not supported on Palo Alto; use metric and distance.");
                }
                if (resolvedRouteName != null && !PAN_ROUTE_NAME.matcher(resolvedRouteName).matches()) {
                    throw new ValidationException("INVALID_ROUTE_NAME",
                            "Palo Alto routeName must match [A-Za-z0-9_.-]{1,31}.");
                }
                if (resolvedRouteName == null) {
                    resolvedRouteName = defaultPanRouteName(unitId, resolvedContext, prefix.canonicalCidr(), canonicalNextHop);
                }
                if (resolvedComment != null) {
                    throw new ValidationException("COMMENT_NOT_SUPPORTED_ON_VENDOR",
                            "deviceComment is not supported on Palo Alto in Wave 1.");
                }
            }
            case VENDOR_CISCO_ASA -> {
                String ctx = (routingContext == null || routingContext.isBlank()) ? ASA_SINGLE_CONTEXT : routingContext;
                if (!ASA_CTX_NAME.matcher(ctx).matches()) {
                    throw new ValidationException("ROUTING_CONTEXT_INVALID", "Invalid Cisco ASA context name.");
                }
                resolvedContext = ctx;
                if (resolvedInterface == null || !INTERFACE_NAME.matcher(resolvedInterface).matches()) {
                    throw new ValidationException("INTERFACE_REQUIRED",
                            "interfaceName is required for Cisco ASA static routes.");
                }
                if (resolvedDistance != null && (resolvedDistance < 1 || resolvedDistance > 254)) {
                    throw new ValidationException("INVALID_DISTANCE",
                            "Cisco ASA administrative distance must be in 1..254.");
                }
                if (resolvedDistance == null && OP_ADD.equals(operation)) {
                    resolvedDistance = 1;
                }
                if (resolvedMetric != null) {
                    throw new ValidationException("METRIC_NOT_SUPPORTED_ON_VENDOR",
                            "metric is not supported on Cisco ASA.");
                }
                if (resolvedPriority != null) {
                    throw new ValidationException("PRIORITY_NOT_SUPPORTED_ON_VENDOR",
                            "priority is not supported on Cisco ASA.");
                }
                if (resolvedRouteName != null) {
                    throw new ValidationException("ROUTE_NAME_NOT_SUPPORTED_ON_VENDOR",
                            "routeName is supported on Palo Alto only.");
                }
                if (resolvedComment != null) {
                    throw new ValidationException("COMMENT_NOT_SUPPORTED_ON_VENDOR",
                            "Cisco ASA route has no CLI comment parameter.");
                }
            }
            default -> throw new ValidationException("VENDOR_NOT_SUPPORTED", "Unsupported vendor.");
        }

        String resolvedTicket = (changeTicketRef == null || changeTicketRef.isBlank()) ? null : changeTicketRef;
        if (resolvedTicket != null && !TICKET_REF.matcher(resolvedTicket).matches()) {
            throw new ValidationException("INVALID_CHANGE_TICKET_REF",
                    "changeTicketRef must match [A-Za-z0-9_.:/-]{3,64}.");
        }
        String resolvedReason = (reason == null || reason.isBlank()) ? null : reason;
        if (resolvedReason != null && !REASON.matcher(resolvedReason).matches()) {
            throw new ValidationException("INVALID_REASON",
                    "reason must be 8..255 printable ASCII characters without control characters.");
        }

        return new ValidatedRequest(
                unitId,
                vendor,
                resolvedContext,
                operation,
                prefix,
                nextHopInt,
                canonicalNextHop,
                resolvedInterface,
                resolvedDistance,
                resolvedMetric,
                resolvedPriority,
                resolvedRouteName,
                resolvedComment,
                repHostInt,
                canonicalRepHost,
                resolvedTicket,
                resolvedReason);
    }

    public static String defaultPanRouteName(String unitId, String vr, String cidr, String nextHop) {
        String seed = unitId + "|" + vr + "|" + cidr + "|" + nextHop;
        return "nxs-" + sha256Hex(seed).substring(0, 12);
    }

    // ---- Deterministic Command Plan & Inverse Plan Compilation (Preview ONLY; never sent in R0) ----

    public record CompiledPreviewStep(
            int stepNo,
            String phase,
            String memberDeviceId,
            String commandText,
            String commandSha256) {
    }

    public record PreviewPlanSet(
            String gateVersion,
            String requestDigest,
            boolean mutationPossible,
            List<CompiledPreviewStep> forwardSteps,
            List<String> approvedCommandSha256Set,
            List<CompiledPreviewStep> inverseSteps,
            List<String> inverseCommandSha256Set,
            String note) {
        public PreviewPlanSet {
            if (mutationPossible) {
                throw new IllegalArgumentException("mutationPossible must be false in Phase R0");
            }
            forwardSteps = List.copyOf(forwardSteps);
            approvedCommandSha256Set = List.copyOf(approvedCommandSha256Set);
            inverseSteps = List.copyOf(inverseSteps);
            inverseCommandSha256Set = List.copyOf(inverseCommandSha256Set);
        }
    }

    /**
     * Captured attributes of an existing route (used when previewing a DELETE so the inverse ADD restores identical attributes).
     */
    public record ExistingRouteAttributes(
            String routeName,
            String interfaceName,
            Integer distance,
            Integer metric,
            Integer priority,
            String comment) {
    }

    /**
     * One evaluated pre-check row (Contract §4.1 Checks 1..6).
     * {@code status}: {@code PASS | FAIL | WARN | UNKNOWN}.
     */
    public record PreCheckVerdict(
            int checkNumber,
            String checkId,
            String stage,
            String status,
            String reasonCode,
            String detail) {
    }

    /**
     * Derived before-state path lookup for the representative host (Path-confirmation appendix; raw output never stored).
     */
    public record BeforePathObservation(
            String representativeHost,
            String verdict,
            String reasonCode,
            String selectedPrefix,
            String nextHopIp,
            String egressInterface,
            String protocol,
            String digest) {
    }

    public static PreviewPlanSet compilePreviewPlans(
            ValidatedRequest req,
            List<String> memberDeviceIds,
            Optional<ExistingRouteAttributes> existingRoute,
            Optional<String> panAdminUser) {

        List<String> members = (memberDeviceIds == null || memberDeviceIds.isEmpty())
                ? List.of(req.unitId())
                : List.copyOf(memberDeviceIds);

        List<CompiledPreviewStep> forward = new ArrayList<>();
        List<CompiledPreviewStep> inverse = new ArrayList<>();

        switch (req.vendor()) {
            case VENDOR_CHECK_POINT -> compileCheckPointPlans(req, members, existingRoute, forward, inverse);
            case VENDOR_PALO_ALTO -> compilePaloAltoPlans(req, members.get(0), existingRoute, panAdminUser, forward, inverse);
            case VENDOR_CISCO_ASA -> compileCiscoAsaPlans(req, members.get(0), existingRoute, forward, inverse);
            default -> throw new IllegalArgumentException("Unsupported vendor: " + req.vendor());
        }

        List<String> forwardHashes = forward.stream().map(CompiledPreviewStep::commandSha256).toList();
        List<String> inverseHashes = inverse.stream().map(CompiledPreviewStep::commandSha256).toList();

        String canonicalRequest = canonicalRequestString(req, members, forwardHashes, inverseHashes);
        String requestDigest = sha256Hex(canonicalRequest);

        return new PreviewPlanSet(
                GATE_VERSION,
                requestDigest,
                false,
                forward,
                forwardHashes,
                inverse,
                inverseHashes,
                "Preview only -- nothing is sent to the device");
    }

    private static void compileCheckPointPlans(
            ValidatedRequest req,
            List<String> members,
            Optional<ExistingRouteAttributes> existingRoute,
            List<CompiledPreviewStep> forward,
            List<CompiledPreviewStep> inverse) {

        String cidr = req.prefix().canonicalCidr();
        String nh = req.nextHopIp();
        int fStep = 1;
        int iStep = 1;

        if (OP_ADD.equals(req.operation())) {
            String addCmd = req.priority() != null
                    ? "clish -c 'set static-route " + cidr + " nexthop gateway address " + nh + " priority " + req.priority() + " on'"
                    : "clish -c 'set static-route " + cidr + " nexthop gateway address " + nh + " on'";
            String commentCmd = req.deviceComment() != null
                    ? "clish -c 'set static-route " + cidr + " comment \"" + req.deviceComment() + "\"'"
                    : null;
            String saveCmd = "clish -c \"save config\"";
            String delCmd = "clish -c 'set static-route " + cidr + " nexthop gateway address " + nh + " off'";

            for (String member : members) {
                forward.add(step(fStep++, "WRITE", member, addCmd));
                if (commentCmd != null) {
                    forward.add(step(fStep++, "WRITE", member, commentCmd));
                }
                forward.add(step(fStep++, "SAVE", member, saveCmd));

                inverse.add(step(iStep++, "ROLLBACK_WRITE", member, delCmd));
                inverse.add(step(iStep++, "ROLLBACK_SAVE", member, saveCmd));
            }
        } else {
            String delCmd = "clish -c 'set static-route " + cidr + " nexthop gateway address " + nh + " off'";
            String saveCmd = "clish -c \"save config\"";
            Integer restoredPriority = existingRoute.map(ExistingRouteAttributes::priority).orElse(req.priority());
            String restoreAddCmd = restoredPriority != null
                    ? "clish -c 'set static-route " + cidr + " nexthop gateway address " + nh + " priority " + restoredPriority + " on'"
                    : "clish -c 'set static-route " + cidr + " nexthop gateway address " + nh + " on'";
            String restoredComment = existingRoute.map(ExistingRouteAttributes::comment).orElse(req.deviceComment());
            String restoreCommentCmd = (restoredComment != null && !restoredComment.isBlank())
                    ? "clish -c 'set static-route " + cidr + " comment \"" + restoredComment + "\"'"
                    : null;

            for (String member : members) {
                forward.add(step(fStep++, "WRITE", member, delCmd));
                forward.add(step(fStep++, "SAVE", member, saveCmd));

                inverse.add(step(iStep++, "ROLLBACK_WRITE", member, restoreAddCmd));
                if (restoreCommentCmd != null) {
                    inverse.add(step(iStep++, "ROLLBACK_WRITE", member, restoreCommentCmd));
                }
                inverse.add(step(iStep++, "ROLLBACK_SAVE", member, saveCmd));
            }
        }
    }

    private static void compilePaloAltoPlans(
            ValidatedRequest req,
            String activeMember,
            Optional<ExistingRouteAttributes> existingRoute,
            Optional<String> panAdminUser,
            List<CompiledPreviewStep> forward,
            List<CompiledPreviewStep> inverse) {

        String vr = req.routingContext();
        String routeName = existingRoute.map(ExistingRouteAttributes::routeName)
                .filter(n -> !n.isBlank())
                .orElse(req.routeName());
        String admin = panAdminUser.filter(u -> ADMIN_USER.matcher(u).matches()).orElse("nexus-admin");

        String xpath = "/config/devices/entry[@name='localhost.localdomain']/network/virtual-router/entry[@name='"
                + vr + "']/routing-table/ip/static-route/entry[@name='" + routeName + "']";

        String commitCmd = "type=commit&action=partial&cmd=" + encodePanPartialCommitXml(admin);
        String revertCmd = "type=op&cmd=<revert><config/></revert>";
        String deleteCmd = "type=config&action=delete&xpath=" + xpath;

        if (OP_ADD.equals(req.operation())) {
            String elementXml = encodePanStaticRouteElementXml(
                    req.prefix().canonicalCidr(),
                    req.nextHopIp(),
                    req.interfaceName(),
                    req.metric(),
                    req.distance());
            String setCmd = "type=config&action=set&xpath=" + xpath + "&element=" + elementXml;

            forward.add(step(1, "WRITE", activeMember, setCmd));
            forward.add(step(2, "SAVE", activeMember, commitCmd));
            forward.add(step(3, "CANDIDATE_REVERT", activeMember, revertCmd));

            inverse.add(step(1, "ROLLBACK_WRITE", activeMember, deleteCmd));
            inverse.add(step(2, "ROLLBACK_SAVE", activeMember, commitCmd));
        } else {
            String restoredIface = existingRoute.map(ExistingRouteAttributes::interfaceName).orElse(req.interfaceName());
            Integer restoredMetric = existingRoute.map(ExistingRouteAttributes::metric)
                    .orElse(req.metric() != null ? req.metric() : 10);
            Integer restoredDist = existingRoute.map(ExistingRouteAttributes::distance)
                    .orElse(req.distance() != null ? req.distance() : 10);
            String restoreElementXml = encodePanStaticRouteElementXml(
                    req.prefix().canonicalCidr(),
                    req.nextHopIp(),
                    restoredIface,
                    restoredMetric,
                    restoredDist);
            String restoreSetCmd = "type=config&action=set&xpath=" + xpath + "&element=" + restoreElementXml;

            forward.add(step(1, "WRITE", activeMember, deleteCmd));
            forward.add(step(2, "SAVE", activeMember, commitCmd));
            forward.add(step(3, "CANDIDATE_REVERT", activeMember, revertCmd));

            inverse.add(step(1, "ROLLBACK_WRITE", activeMember, restoreSetCmd));
            inverse.add(step(2, "ROLLBACK_SAVE", activeMember, commitCmd));
        }
    }

    /** Built with {@link XMLStreamWriter} per Contract §5.1 invariant 3 (never string concatenation). */
    public static String encodePanStaticRouteElementXml(
            String destinationCidr,
            String nextHopIp,
            String interfaceName,
            Integer metric,
            Integer distance) {
        try {
            StringWriter sw = new StringWriter();
            XMLStreamWriter xml = XMLOutputFactory.newInstance().createXMLStreamWriter(sw);
            xml.writeStartElement("destination");
            xml.writeCharacters(destinationCidr);
            xml.writeEndElement();
            xml.writeStartElement("nexthop");
            xml.writeStartElement("ip-address");
            xml.writeCharacters(nextHopIp);
            xml.writeEndElement();
            xml.writeEndElement();
            if (interfaceName != null && !interfaceName.isBlank()) {
                xml.writeStartElement("interface");
                xml.writeCharacters(interfaceName);
                xml.writeEndElement();
            }
            if (metric != null) {
                xml.writeStartElement("metric");
                xml.writeCharacters(String.valueOf(metric));
                xml.writeEndElement();
            }
            if (distance != null) {
                xml.writeStartElement("admin-dist");
                xml.writeCharacters(String.valueOf(distance));
                xml.writeEndElement();
            }
            xml.flush();
            return sw.toString();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Failed to encode PAN static-route element XML", e);
        }
    }

    public static String encodePanPartialCommitXml(String adminUser) {
        if (adminUser == null || !ADMIN_USER.matcher(adminUser).matches()) {
            throw new IllegalArgumentException("Invalid PAN admin username");
        }
        try {
            StringWriter sw = new StringWriter();
            XMLStreamWriter xml = XMLOutputFactory.newInstance().createXMLStreamWriter(sw);
            xml.writeStartElement("commit");
            xml.writeStartElement("partial");
            xml.writeStartElement("admin");
            xml.writeStartElement("member");
            xml.writeCharacters(adminUser);
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndElement();
            xml.flush();
            return sw.toString();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Failed to encode PAN partial-commit XML", e);
        }
    }

    private static void compileCiscoAsaPlans(
            ValidatedRequest req,
            String activeMember,
            Optional<ExistingRouteAttributes> existingRoute,
            List<CompiledPreviewStep> forward,
            List<CompiledPreviewStep> inverse) {

        String iface = req.interfaceName();
        String destIp = req.prefix().networkIp();
        String mask = req.prefix().dottedNetmask();
        String nh = req.nextHopIp();
        int dist = req.distance() != null ? req.distance()
                : existingRoute.map(ExistingRouteAttributes::distance).orElse(1);

        String ctxPrefix = ASA_SINGLE_CONTEXT.equals(req.routingContext())
                ? ""
                : "changeto context " + req.routingContext() + "\n";

        String addBlock = ctxPrefix + "configure terminal\nroute " + iface + " " + destIp + " " + mask + " " + nh + " " + dist + "\nend";
        String delBlock = ctxPrefix + "configure terminal\nno route " + iface + " " + destIp + " " + mask + " " + nh + "\nend";
        String writeMem = "write memory";

        if (OP_ADD.equals(req.operation())) {
            forward.add(step(1, "WRITE", activeMember, addBlock));
            forward.add(step(2, "SAVE", activeMember, writeMem));

            inverse.add(step(1, "ROLLBACK_WRITE", activeMember, delBlock));
            inverse.add(step(2, "ROLLBACK_SAVE", activeMember, writeMem));
        } else {
            forward.add(step(1, "WRITE", activeMember, delBlock));
            forward.add(step(2, "SAVE", activeMember, writeMem));

            inverse.add(step(1, "ROLLBACK_WRITE", activeMember, addBlock));
            inverse.add(step(2, "ROLLBACK_SAVE", activeMember, writeMem));
        }
    }

    private static CompiledPreviewStep step(int stepNo, String phase, String memberDeviceId, String commandText) {
        return new CompiledPreviewStep(stepNo, phase, memberDeviceId, commandText, sha256Hex(commandText));
    }

    public static String canonicalRequestString(
            ValidatedRequest req,
            List<String> memberDeviceIds,
            List<String> forwardHashes,
            List<String> inverseHashes) {
        return String.join("|",
                req.unitId(),
                String.join(",", memberDeviceIds),
                req.vendor(),
                req.routingContext(),
                req.operation(),
                req.prefix().canonicalCidr(),
                req.nextHopIp(),
                Objects.toString(req.interfaceName(), ""),
                Objects.toString(req.distance(), ""),
                Objects.toString(req.metric(), ""),
                Objects.toString(req.priority(), ""),
                Objects.toString(req.routeName(), ""),
                Objects.toString(req.deviceComment(), ""),
                req.representativeHost(),
                Objects.toString(req.changeTicketRef(), ""),
                GATE_VERSION,
                String.join(",", forwardHashes),
                String.join(",", inverseHashes));
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
