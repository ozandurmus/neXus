package com.securityexpert.nexus.ui2.jobs.pathvalidation;

import java.util.List;
import java.util.Optional;

/**
 * What one {@code path_validation} job asks of one hop's device and what the path engine expects the answer to be. The
 * service builds it from the computed path result only; it is stored on the hop row while the job is pending (the only
 * place its addresses and names live) and cleared, in the same statement, when the job records its outcome. It never
 * carries command text: the worker builds the command from these fields with {@link PathValidation}'s builders.
 *
 * <p>{@code null} means "not part of this plan" (or UNKNOWN for an expectation). Field names are the JSON keys.</p>
 *
 * @param vsid           Check Point VSID (the stored context name) for the {@code vsenv} form; {@code null} on physical
 * @param routeDestination the destination the hop routed on (post-NAT when the hop translated it)
 * @param natToZone      the zone NAT was matched against (the pre-NAT egress zone); {@code null} when it is not in the
 *                       result, and then the NAT check is not planned
 */
public record HopPlan(int version, String vendor, String deviceId, String context, String vsid, String virtualRouter,
        String routeDestination, String source, String destination, int protocol, int port, String fromZone,
        String toZone, String natToZone, List<PathValidation.Check> checks, Expect expect) {

    public static final String CHECK_POINT = "check_point";
    public static final String PALO_ALTO = "palo_alto";

    public HopPlan {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    /**
     * The engine's own answer for this hop.
     *
     * @param routeKind       {@code IP | CONNECTED | DISCARD | NEXT_VR | NO_ROUTE}, or {@code null} (UNKNOWN)
     * @param ecmp            the engine's route is one of several equal-cost choices
     * @param securityVerdict {@code ALLOWED | DENIED}; a rule name is compared, the verdict only reported
     * @param natStatus       {@code TRANSLATED | NO_TRANSLATION}
     * @param natRule         the first deciding NAT rule name, {@code null} when no rule matched
     */
    public record Expect(String routeKind, String egressInterface, String nextHop, boolean ecmp, String securityVerdict,
            String securityRule, String natStatus, String natRule) {
    }

    /** Syntax of every field the worker will put into a command; empty when valid, else the reason code. */
    public Optional<String> invalid() {
        if (version != PathValidation.PLAN_VERSION) return Optional.of("PLAN_VERSION_UNSUPPORTED");
        if (deviceId == null || deviceId.isBlank() || context == null || checks.isEmpty() || expect == null)
            return Optional.of("PLAN_INCOMPLETE");
        if (CHECK_POINT.equals(vendor)) {
            if (!checks.equals(List.of(PathValidation.Check.CP_ROUTE))) return Optional.of("PLAN_CHECKS_INVALID");
            if (vsid != null && (!PathValidation.vsid(vsid) || !vsid.equals(context))) return Optional.of("VSID_INVALID");
            if (vsid == null && !"physical".equals(context)) return Optional.of("CONTEXT_UNSUPPORTED");
            return PathValidation.ipv4(routeDestination) ? Optional.empty() : Optional.of("DESTINATION_INVALID");
        }
        if (!PALO_ALTO.equals(vendor)) return Optional.of("VENDOR_UNSUPPORTED");
        if (vsid != null || checks.stream().anyMatch(c -> c == PathValidation.Check.CP_ROUTE)
                || checks.stream().distinct().count() != checks.size()) return Optional.of("PLAN_CHECKS_INVALID");
        if (checks.contains(PathValidation.Check.PAN_FIB)
                && (!PathValidation.name(virtualRouter) || !PathValidation.ipv4(routeDestination)))
            return Optional.of("FIB_PARAMETER_INVALID");
        boolean policy = checks.contains(PathValidation.Check.PAN_SECURITY) || checks.contains(PathValidation.Check.PAN_NAT);
        if (policy && (!PathValidation.ipv4(source) || !PathValidation.ipv4(destination) || protocol != PathValidation.TCP
                || !PathValidation.port(port) || !PathValidation.name(fromZone))) return Optional.of("POLICY_PARAMETER_INVALID");
        if (checks.contains(PathValidation.Check.PAN_SECURITY) && !PathValidation.name(toZone))
            return Optional.of("POLICY_PARAMETER_INVALID");
        if (checks.contains(PathValidation.Check.PAN_NAT) && !PathValidation.name(natToZone))
            return Optional.of("POLICY_PARAMETER_INVALID");
        return Optional.empty();
    }
}
