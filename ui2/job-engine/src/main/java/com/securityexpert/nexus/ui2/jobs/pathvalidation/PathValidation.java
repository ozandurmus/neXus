package com.securityexpert.nexus.ui2.jobs.pathvalidation;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.capability.GateResolver;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * Path analysis stage A6 (PATH_ANALYSIS_DESIGN.md §7, FROZEN; PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md rows 2-5, RATIFIED,
 * PO 2026-11-04 "1-5 onaylı devam"): the closed set of vendor read-only matchers a {@code path_validation} job may send
 * to the device of one computed path hop, and the strict validation of every value put into them.
 *
 * <p>Each command is one exact approved literal with its placeholders filled. A value is accepted only after
 * {@link #ipv4}, {@link #port}, {@link #vsid} or {@link #name} accepted it; anything else throws
 * {@link IllegalArgumentException} before a command string exists. The values themselves come only from a path result
 * the service computed (the browser never supplies a command, a gate, a context, a zone or a virtual router), and the
 * worker checks them again, plus their existence in the device's stored inventory, before sending.</p>
 *
 * <p>The approved Palo Alto policy-match literals carry {@code <protocol>6</protocol>}: protocol is not one of the
 * proposal's parameters, so only TCP is sent. A UDP hop is {@code NOT_EVALUABLE} ({@link #PROTOCOL_NOT_APPROVED}), never
 * sent with 17 (that would be a different, unapproved literal).</p>
 */
public final class PathValidation {

    public static final String CAPABILITY = "path_validation";
    /** At most this many hops per validation request; one job per hop (the claim model is per target device). */
    public static final int MAX_HOPS = 4;
    public static final int TIMEOUT_S = 30;
    /** Bounded read; a positive limit also keeps the command and its answer out of every transcript. */
    public static final int OUTPUT_LIMIT_BYTES = 65_536;
    public static final int PLAN_VERSION = 1;

    public static final String PROTOCOL_NOT_APPROVED = "PROTOCOL_NOT_APPROVED";
    public static final int TCP = 6;
    public static final int UDP = 17;

    /** One matcher kind: what the vendor's own read answers about one hop. */
    public enum Check { CP_ROUTE, PAN_FIB, PAN_SECURITY, PAN_NAT }

    /** Stored per check and per hop; {@code PENDING} exists only on the hop row before its job finished. */
    public enum Result { MATCH, MISMATCH, NOT_EVALUABLE }

    /** One gate row this job may resolve: the exact approved literal and the key it is registered under. */
    public record Gate(String gateId, String vendor, String platformRoleScope, String shellContext, String transportKind,
            String template) {
        public CanonicalCommandKey key() {
            return new CanonicalCommandKey(vendor, platformRoleScope, shellContext, transportKind, template);
        }
    }

    public static final Gate CP_ROUTE_GET = new Gate("path_validation_cp_route_get", "check_point", "cp_gaia_gateway",
            "expert", "SSH_EXEC", "bash -lc 'ip -4 route get <DST>'");
    public static final Gate CP_ROUTE_GET_VSX = new Gate("path_validation_cp_route_get_vsx", "check_point",
            "cp_gaia_gateway", "expert", "SSH_EXEC", "bash -lc 'vsenv <VSID> && ip -4 route get <DST>'");
    public static final Gate PAN_FIB_LOOKUP = new Gate("path_validation_pan_fib_lookup", "palo_alto", "pan_firewall",
            "not_applicable", "PAN_XML_API",
            "<test><routing><fib-lookup><virtual-router>VR</virtual-router><ip>DST</ip></fib-lookup></routing></test>");
    public static final Gate PAN_SECURITY_POLICY_MATCH = new Gate("path_validation_pan_security_policy_match", "palo_alto",
            "pan_firewall", "not_applicable", "PAN_XML_API",
            "<test><security-policy-match><from>Z1</from><to>Z2</to><source>SRC</source><destination>DST</destination>"
                    + "<protocol>6</protocol><destination-port>PORT</destination-port></security-policy-match></test>");
    public static final Gate PAN_NAT_POLICY_MATCH = new Gate("path_validation_pan_nat_policy_match", "palo_alto",
            "pan_firewall", "not_applicable", "PAN_XML_API",
            "<test><nat-policy-match><from>Z1</from><to>Z2</to><source>SRC</source><destination>DST</destination>"
                    + "<protocol>6</protocol><destination-port>PORT</destination-port></nat-policy-match></test>");

    public static final List<Gate> GATES = List.of(CP_ROUTE_GET, CP_ROUTE_GET_VSX, PAN_FIB_LOOKUP,
            PAN_SECURITY_POLICY_MATCH, PAN_NAT_POLICY_MATCH);
    public static final Set<String> GATE_IDS = Set.of(CP_ROUTE_GET.gateId(), CP_ROUTE_GET_VSX.gateId(),
            PAN_FIB_LOOKUP.gateId(), PAN_SECURITY_POLICY_MATCH.gateId(), PAN_NAT_POLICY_MATCH.gateId());

    private static final Pattern IPV4 = Pattern.compile(
            "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}");
    private static final Pattern VSID = Pattern.compile("[0-9]{1,5}");
    /** PAN zone and virtual-router names: letters, digits, space, '.', '_', '-'; 31 characters; no XML metacharacter. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,30}");

    private PathValidation() {
    }

    /** A dotted-quad IPv4 host literal in canonical form (no leading zeros, no prefix length, nothing else). */
    public static boolean ipv4(String value) {
        return value != null && IPV4.matcher(value).matches();
    }

    public static boolean port(int value) {
        return value >= 1 && value <= 65_535;
    }

    /** IANA protocol number of a path query: TCP or UDP only. */
    public static boolean protocol(int value) {
        return value == TCP || value == UDP;
    }

    /** A Check Point VSID exactly as the inventory stores it as a context name: digits only. */
    public static boolean vsid(String value) {
        return value != null && VSID.matcher(value).matches();
    }

    /** A zone or virtual-router name safe inside the approved XML literal; existence is checked separately. */
    public static boolean name(String value) {
        return value != null && NAME.matcher(value).matches() && value.equals(value.strip());
    }

    /** Row 2: {@code ip -4 route get} on the physical context, or with the {@code vsenv <VSID> &&} prefix on a VS. */
    public static String cpRouteGet(String destination, Optional<String> vsid) {
        require(ipv4(destination), "destination");
        if (vsid.isPresent()) {
            require(vsid(vsid.get()), "vsid");
            return fill(CP_ROUTE_GET_VSX.template(), "<VSID>", vsid.get(), "<DST>", destination);
        }
        return fill(CP_ROUTE_GET.template(), "<DST>", destination);
    }

    /** Row 3: the FIB decision of one virtual router for one destination. */
    public static String panFibLookup(String virtualRouter, String destination) {
        require(name(virtualRouter), "virtual router");
        require(ipv4(destination), "destination");
        return fill(PAN_FIB_LOOKUP.template(), ">VR<", ">" + virtualRouter + "<", ">DST<", ">" + destination + "<");
    }

    /** Row 4: the firewall's own first-match security rule (pre-NAT addresses, post-NAT egress zone). TCP only. */
    public static String panSecurityPolicyMatch(String fromZone, String toZone, String source, String destination,
            int protocol, int port) {
        return policyMatch(PAN_SECURITY_POLICY_MATCH, fromZone, toZone, source, destination, protocol, port);
    }

    /** Row 5: the firewall's own NAT rule decision (pre-NAT addresses and zones). TCP only. */
    public static String panNatPolicyMatch(String fromZone, String toZone, String source, String destination,
            int protocol, int port) {
        return policyMatch(PAN_NAT_POLICY_MATCH, fromZone, toZone, source, destination, protocol, port);
    }

    private static String policyMatch(Gate gate, String fromZone, String toZone, String source, String destination,
            int protocol, int port) {
        require(name(fromZone), "from zone");
        require(name(toZone), "to zone");
        require(ipv4(source), "source");
        require(ipv4(destination), "destination");
        require(protocol == TCP, PROTOCOL_NOT_APPROVED);
        require(port(port), "port");
        return fill(gate.template(), ">Z1<", ">" + fromZone + "<", ">Z2<", ">" + toZone + "<", ">SRC<", ">" + source + "<",
                ">DST<", ">" + destination + "<", ">PORT<", ">" + port + "<");
    }

    /**
     * The signed-off read gate of one of {@link #GATES}; empty when it is missing, not signed off, not a read, not the
     * expected gate id or timeout, or ambiguous. Resolved again against the live registry before every send.
     */
    public static Optional<GateResolution.Known> resolve(Gate gate, GateRegistryPort registry) {
        if (!GATES.contains(gate)) {
            return Optional.empty();
        }
        try {
            var resolved = GateResolver.resolve(gate.key(), Optional.of(ActionClass.CLASS_0_READ), registry);
            return resolved instanceof GateResolution.Known known && gate.gateId().equals(known.gateId())
                    && known.actionClass() == ActionClass.CLASS_0_READ && known.timeoutS() == TIMEOUT_S
                    ? Optional.of(known) : Optional.empty();
        } catch (RuntimeException invalidGate) {
            return Optional.empty();
        }
    }

    /** Each placeholder occurs exactly once in the template; a template that does not hold that is never filled. */
    private static String fill(String template, String... pairs) {
        String out = template;
        for (int i = 0; i < pairs.length; i += 2) {
            int at = out.indexOf(pairs[i]);
            if (at < 0 || out.indexOf(pairs[i], at + 1) >= 0) {
                throw new IllegalStateException("template placeholder");
            }
            out = out.substring(0, at) + pairs[i + 1] + out.substring(at + pairs[i].length());
        }
        return out;
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException(PROTOCOL_NOT_APPROVED.equals(what) ? what : "invalid " + what);
        }
    }
}
