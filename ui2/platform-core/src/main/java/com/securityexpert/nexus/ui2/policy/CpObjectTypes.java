package com.securityexpert.nexus.ui2.policy;

import java.util.Set;

/**
 * Check Point native object type to the viewer's object class, shared by the package-snapshot mapper (worker) and the
 * domain-inventory join (service) so both classify an object identically. Anything unlisted stays UNSUPPORTED.
 */
public final class CpObjectTypes {
    private CpObjectTypes() {}
    /** Membership decided at enforcement time (identity, DNS, feeds, topology); never evaluated as static content. */
    public static final Set<String> DYNAMIC_TYPES = Set.of("access-role", "dynamic-object", "dns-domain", "security-zone", "updatable-object");
    /** Management and gateway object classes returned by show-gateways-and-servers. */
    public static final Set<String> GATEWAY_TYPES = Set.of("simple-gateway", "simple-cluster", "checkpoint-host", "cluster-member",
        "CpmiGatewayCluster", "CpmiClusterMember", "CpmiHostCkp", "CpmiGatewayPlain", "CpmiGatewayCkp", "CpmiVsClusterNetobj",
        "CpmiVsxClusterNetobj", "CpmiVsxClusterMember", "CpmiVsxNetobj", "CpmiVsNetobj");
    /** Native dictionary type to the viewer's object class; anything else is "unresolved" (fail closed). */
    public static String type(String nativeType) {
        if (GATEWAY_TYPES.contains(nativeType)) return "gateway";
        return switch (nativeType) {
            case "CpmiAnyObject" -> "any";
            // A multicast address range has the address-range shape (ipv4/ipv6-address-first/-last) over multicast
            // addresses; it selects those destination addresses like any other range.
            case "host", "network", "address-range", "multicast-address-range" -> "address";
            case "group", "group-with-exclusion" -> "group";
            case "service-tcp", "service-udp", "service-icmp", "service-icmp6", "service-other", "service-sctp",
                 "service-dce-rpc", "service-rpc" -> "service";
            case "service-group" -> "service-group";
            case "time", "time-group" -> nativeType;
            case "access-role", "dynamic-object", "dns-domain", "security-zone", "updatable-object" -> nativeType;
            case "application-site", "application-site-category" -> "application";
            case "application-site-group" -> "application-group";
            case "RulebaseAction" -> "action";
            case "Track" -> "track";
            default -> "unresolved";
        };
    }
    /** RESOLVED, DYNAMIC or UNSUPPORTED for a native type, as the package-snapshot mapper records it. */
    public static String status(String nativeType) {
        String type = type(nativeType);
        return type.equals("unresolved") || nativeType.equals("group-with-exclusion") ? "UNSUPPORTED"
            : DYNAMIC_TYPES.contains(nativeType) ? "DYNAMIC" : "RESOLVED";
    }
}
