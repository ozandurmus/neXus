package com.securityexpert.nexus.ui2.policy.fortigate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable model of a FortiGate configuration's policy constructs across all VDOMs in configuration order.
 */
public record FortiGatePolicyConfig(
        String version,
        String minorVersion,
        boolean multiVdom,
        Map<String, Interface> globalInterfaces,
        Map<String, VdomConfig> vdoms,
        Counters counters) {

    public FortiGatePolicyConfig {
        version = version == null ? "" : version;
        minorVersion = minorVersion == null ? "" : minorVersion;
        globalInterfaces = copyOrderedMap(globalInterfaces);
        vdoms = copyOrderedMap(vdoms);
        counters = counters == null ? new Counters(0, 0, 0, 0, 0, 0, 0, 0, 0) : counters;
    }

    /** Per-VDOM policy configuration (on a single-VDOM unit, the single VDOM is {@code "root"}). */
    public record VdomConfig(
            String name,
            VdomSettings settings,
            Map<String, Interface> interfaces,
            Map<String, Zone> zones,
            Map<String, SdwanZone> sdwanZones,
            Map<String, AddressObject> addresses,
            Map<String, AddressGroup> addressGroups,
            Map<String, Address6Object> addresses6,
            Map<String, AddressGroup6> addressGroups6,
            Map<String, ServiceObject> services,
            Map<String, ServiceGroup> serviceGroups,
            Map<String, VipObject> vips,
            Map<String, VipGroup> vipGroups,
            Map<String, IpPool> ipPools,
            List<CentralSnatRule> centralSnatRules,
            List<FirewallPolicy> policies,
            List<NonEvaluatedPolicy> nonEvaluatedPolicies,
            VdomCounters counters) {

        public VdomConfig {
            name = name == null ? "root" : name;
            settings = settings == null ? new VdomSettings(null, null, null, null) : settings;
            interfaces = copyOrderedMap(interfaces);
            zones = copyOrderedMap(zones);
            sdwanZones = copyOrderedMap(sdwanZones);
            addresses = copyOrderedMap(addresses);
            addressGroups = copyOrderedMap(addressGroups);
            addresses6 = copyOrderedMap(addresses6);
            addressGroups6 = copyOrderedMap(addressGroups6);
            services = copyOrderedMap(services);
            serviceGroups = copyOrderedMap(serviceGroups);
            vips = copyOrderedMap(vips);
            vipGroups = copyOrderedMap(vipGroups);
            ipPools = copyOrderedMap(ipPools);
            centralSnatRules = centralSnatRules == null ? List.of() : List.copyOf(centralSnatRules);
            policies = policies == null ? List.of() : List.copyOf(policies);
            nonEvaluatedPolicies = nonEvaluatedPolicies == null ? List.of() : List.copyOf(nonEvaluatedPolicies);
            counters = counters == null ? new VdomCounters(0, 0, 0, 0, 0, 0, 0) : counters;
        }
    }

    /**
     * Per-VDOM {@code config system settings} mode fields (A0-FG-15, A0-FG-21).
     * Null values mean the setting was omitted by {@code show}.
     */
    public record VdomSettings(
            String opmode,
            String ngfwMode,
            String centralNat,
            String inspectionMode) {}

    /** Interface entry from {@code config system interface} (A0-FG-9, A0-FG-23). */
    public record Interface(
            String name,
            String vdom,
            String ip,
            String mask,
            String status,
            String type,
            String role) {}

    /** Zone entry from {@code config system zone} (A0-FG-9, A0-FG-10). */
    public record Zone(
            String name,
            List<String> interfaces,
            String intrazone,
            String description) {
        public Zone {
            interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
        }
    }

    /** SD-WAN zone and its member interfaces from {@code config system sdwan} (A0-FG-9). */
    public record SdwanZone(
            String name,
            List<String> interfaces) {
        public SdwanZone {
            interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
        }
    }

    /**
     * IPv4 address object from {@code config firewall address} (A0-FG-23, A0-FG-24).
     * {@code type} is null when omitted by {@code show} (defaults to {@code ipmask} on FortiOS 7.4).
     * {@code subnetIp} and {@code subnetMask} are null when {@code set subnet} is omitted by {@code show}
     * (defaults to {@code 0.0.0.0 0.0.0.0} on FortiOS 7.4).
     */
    public record AddressObject(
            String name,
            String uuid,
            String type,
            String subnetIp,
            String subnetMask,
            String startIp,
            String endIp,
            String fqdn,
            String country,
            String wildcardIp,
            String wildcardMask,
            String interfaceName,
            String routeTag,
            String comment,
            boolean unparsed) {}

    /** IPv4 address group from {@code config firewall addrgrp} (A0-FG-25). */
    public record AddressGroup(
            String name,
            String uuid,
            List<String> members,
            String exclude,
            List<String> excludeMembers,
            String comment,
            boolean unparsed) {
        public AddressGroup {
            members = members == null ? List.of() : List.copyOf(members);
            excludeMembers = excludeMembers == null ? List.of() : List.copyOf(excludeMembers);
        }
    }

    /** IPv6 address object from {@code config firewall address6} (A0-FG-16). */
    public record Address6Object(
            String name,
            String uuid,
            String type,
            String ip6,
            String startIp,
            String endIp,
            String comment) {}

    /** IPv6 address group from {@code config firewall addrgrp6} (A0-FG-16). */
    public record AddressGroup6(
            String name,
            String uuid,
            List<String> members,
            String comment) {
        public AddressGroup6 {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }

    /**
     * Parsed TCP or UDP port range token from {@code tcp-portrange} / {@code udp-portrange} (A0-FG-26):
     * {@code dst}, {@code dst-dst}, or {@code dst[-dst]:src[-src]}.
     * {@code srcStart} and {@code srcEnd} are null when no source-port restriction is present.
     */
    public record PortRange(
            int dstStart,
            int dstEnd,
            Integer srcStart,
            Integer srcEnd) {}

    /**
     * Custom service object from {@code config firewall service custom} (A0-FG-26).
     * {@code protocol} is null when omitted by {@code show} (defaults to {@code TCP/UDP/SCTP} on FortiOS 7.4).
     */
    public record ServiceObject(
            String name,
            String uuid,
            String protocol,
            List<PortRange> tcpRanges,
            List<PortRange> udpRanges,
            List<String> sctpRawRanges,
            String icmpType,
            String icmpCode,
            String protocolNumber,
            boolean hasAddressRestriction,
            String comment,
            boolean unparsed) {
        public ServiceObject {
            tcpRanges = tcpRanges == null ? List.of() : List.copyOf(tcpRanges);
            udpRanges = udpRanges == null ? List.of() : List.copyOf(udpRanges);
            sctpRawRanges = sctpRawRanges == null ? List.of() : List.copyOf(sctpRawRanges);
        }
    }

    /** Service group from {@code config firewall service group} (A0-FG-27). */
    public record ServiceGroup(
            String name,
            String uuid,
            List<String> members,
            String comment,
            boolean unparsed) {
        public ServiceGroup {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }

    /** Virtual IP (DNAT) object from {@code config firewall vip} (A0-FG-17/18/19/20). */
    public record VipObject(
            String name,
            String uuid,
            String type,
            String extip,
            List<String> mappedip,
            String extintf,
            String portforward,
            String protocol,
            String extport,
            String mappedport,
            String comment,
            boolean unparsed) {
        public VipObject {
            mappedip = mappedip == null ? List.of() : List.copyOf(mappedip);
        }
    }

    /** Virtual IP group from {@code config firewall vipgrp} (A0-FG-17/18/19). */
    public record VipGroup(
            String name,
            String uuid,
            String interfaceName,
            List<String> members,
            String comment) {
        public VipGroup {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }

    /** IP pool (SNAT) from {@code config firewall ippool} (A0-FG-22). */
    public record IpPool(
            String name,
            String uuid,
            String type,
            String startip,
            String endip,
            String comment,
            boolean unparsed) {}

    /** Central SNAT rule from {@code config firewall central-snat-map} (A0-FG-21). */
    public record CentralSnatRule(
            String ruleId,
            String uuid,
            String status,
            List<String> srcintf,
            List<String> dstintf,
            List<String> origAddr,
            List<String> dstAddr,
            String protocol,
            String origPort,
            String nat,
            List<String> natIppool,
            String natPort,
            String comments) {
        public CentralSnatRule {
            srcintf = srcintf == null ? List.of() : List.copyOf(srcintf);
            dstintf = dstintf == null ? List.of() : List.copyOf(dstintf);
            origAddr = origAddr == null ? List.of() : List.copyOf(origAddr);
            dstAddr = dstAddr == null ? List.of() : List.copyOf(dstAddr);
            natIppool = natIppool == null ? List.of() : List.copyOf(natIppool);
        }
    }

    /**
     * Firewall policy entry from {@code config firewall policy} in printed (sequence) order (A0-FG-1..22).
     * Fields omitted by {@code show} stay null/empty here so the mapper can apply {@link FortiGateDefaults}
     * for the unit's minor version or mark the rule {@code UNKNOWN}.
     */
    public record FirewallPolicy(
            String policyId,
            String uuid,
            String name,
            String status,
            String action,
            List<String> srcintf,
            List<String> dstintf,
            List<String> srcaddr,
            List<String> dstaddr,
            List<String> srcaddr6,
            List<String> dstaddr6,
            String srcaddrNegate,
            String dstaddrNegate,
            List<String> service,
            String serviceNegate,
            String schedule,
            String logtraffic,
            String comments,
            List<String> users,
            List<String> groups,
            List<String> fssoGroups,
            String internetService,
            List<String> internetServiceNames,
            String internetServiceSrc,
            List<String> internetServiceSrcNames,
            List<String> applications,
            String applicationList,
            List<String> appCategories,
            List<String> appGroups,
            List<String> urlCategories,
            String nat,
            String ippool,
            List<String> poolname,
            String matchVip,
            String matchVipOnly,
            String vpntunnel,
            boolean unparsed) {

        public FirewallPolicy {
            srcintf = srcintf == null ? List.of() : List.copyOf(srcintf);
            dstintf = dstintf == null ? List.of() : List.copyOf(dstintf);
            srcaddr = srcaddr == null ? List.of() : List.copyOf(srcaddr);
            dstaddr = dstaddr == null ? List.of() : List.copyOf(dstaddr);
            srcaddr6 = srcaddr6 == null ? List.of() : List.copyOf(srcaddr6);
            dstaddr6 = dstaddr6 == null ? List.of() : List.copyOf(dstaddr6);
            service = service == null ? List.of() : List.copyOf(service);
            users = users == null ? List.of() : List.copyOf(users);
            groups = groups == null ? List.of() : List.copyOf(groups);
            fssoGroups = fssoGroups == null ? List.of() : List.copyOf(fssoGroups);
            internetServiceNames = internetServiceNames == null ? List.of() : List.copyOf(internetServiceNames);
            internetServiceSrcNames = internetServiceSrcNames == null ? List.of() : List.copyOf(internetServiceSrcNames);
            applications = applications == null ? List.of() : List.copyOf(applications);
            appCategories = appCategories == null ? List.of() : List.copyOf(appCategories);
            appGroups = appGroups == null ? List.of() : List.copyOf(appGroups);
            urlCategories = urlCategories == null ? List.of() : List.copyOf(urlCategories);
            poolname = poolname == null ? List.of() : List.copyOf(poolname);
        }
    }

    /**
     * Entry in a policy table outside the transit IPv4 firewall policy model
     * ({@code local-in-policy}, {@code proxy-policy}, {@code security-policy}, {@code multicast-policy},
     * {@code dos-policy}, {@code policy6}, etc.; A0-FG-13/14/15).
     */
    public record NonEvaluatedPolicy(
            String table,
            String policyId,
            String uuid,
            String name,
            String comments) {}

    /** Per-VDOM parsed and unparsed construct counters. */
    public record VdomCounters(
            int parsedPolicies,
            int unparsedPolicies,
            int parsedAddresses,
            int unparsedAddresses,
            int parsedServices,
            int unparsedServices,
            int nonEvaluatedPolicies) {}

    /** Whole-configuration parsed and unparsed construct counters. */
    public record Counters(
            int totalLines,
            int unbalancedBlocks,
            int parsedPolicies,
            int unparsedPolicies,
            int parsedAddresses,
            int unparsedAddresses,
            int parsedServices,
            int unparsedServices,
            int nonEvaluatedPolicies) {}

    private static <K, V> Map<K, V> copyOrderedMap(Map<K, V> input) {
        if (input == null || input.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }
}
