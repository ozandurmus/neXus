package com.securityexpert.nexus.ui2.service.device.configuration;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which configuration sections make a change MAJOR (docs/design/BACKUP_MAJOR_DEVIATIONS_2026_10_23.md section 2):
 * interfaces, routing, access rules, NAT (architecture review section 4) plus admin access and cluster/HA. The rules
 * read only the section vocabulary the configuration index already stores (V16/V22) -- never a value. A vendor
 * without a table is not categorised, so its devices are "not comparable", never "no deviation".
 */
public final class MajorDeviationRules {

    /** A major category; {@link #wire()} is the response word. */
    public enum Category {
        INTERFACES, ROUTING, ACCESS_RULES, NAT, ADMIN_ACCESS, HIGH_AVAILABILITY;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Check Point Gaia sections (the one/two tokens after {@code set}). Rules and NAT live in the policy plane. */
    private static final Map<String, Category> CHECK_POINT = Map.ofEntries(
            Map.entry("interface", Category.INTERFACES),
            Map.entry("bonding group", Category.INTERFACES),
            Map.entry("bridging", Category.INTERFACES),
            Map.entry("interface-name", Category.INTERFACES),
            Map.entry("static-route", Category.ROUTING),
            Map.entry("bgp", Category.ROUTING),
            Map.entry("ospf", Category.ROUTING),
            Map.entry("ospf3", Category.ROUTING),
            Map.entry("rip", Category.ROUTING),
            Map.entry("ripng", Category.ROUTING),
            Map.entry("pim", Category.ROUTING),
            Map.entry("igmp", Category.ROUTING),
            Map.entry("route-redistribution", Category.ROUTING),
            Map.entry("inbound-route-filter", Category.ROUTING),
            Map.entry("routemap", Category.ROUTING),
            Map.entry("pbr", Category.ROUTING),
            Map.entry("user", Category.ADMIN_ACCESS),
            Map.entry("rba", Category.ADMIN_ACCESS),
            Map.entry("cluster", Category.HIGH_AVAILABILITY),
            Map.entry("vrrp", Category.HIGH_AVAILABILITY),
            Map.entry("mcvr", Category.HIGH_AVAILABILITY));

    /** Palo Alto effective-running categories under {@code network} / {@code mgt-config} / {@code deviceconfig}. */
    private static final Map<String, Category> PALO_ALTO_TOP_LEVEL = Map.ofEntries(
            Map.entry("network virtual-router", Category.ROUTING),
            Map.entry("network logical-router", Category.ROUTING),
            Map.entry("network virtual-wire", Category.INTERFACES),
            Map.entry("network vlan", Category.INTERFACES),
            Map.entry("network interface", Category.INTERFACES),
            Map.entry("mgt-config users", Category.ADMIN_ACCESS),
            Map.entry("deviceconfig high-availability", Category.HIGH_AVAILABILITY));

    /** Palo Alto categories that mean the same in any context ({@code shared} or a vsys). */
    private static final Map<String, Category> PALO_ALTO_ANY_CONTEXT = Map.ofEntries(
            Map.entry("zone", Category.INTERFACES),
            Map.entry("admin-role", Category.ADMIN_ACCESS),
            Map.entry("rulebase", Category.ACCESS_RULES),
            Map.entry("pre-rulebase", Category.ACCESS_RULES),
            Map.entry("post-rulebase", Category.ACCESS_RULES));

    private static final Set<String> PALO_ALTO_TOP_LEVEL_CONTEXTS = Set.of("network", "mgt-config", "deviceconfig");

    /** Cisco ASA sections as the ASA configuration processor names them (both name generations). */
    private static final Map<String, Category> CISCO_ASA = Map.ofEntries(
            Map.entry("interfaces", Category.INTERFACES),
            Map.entry("interface", Category.INTERFACES),
            Map.entry("routing", Category.ROUTING),
            Map.entry("route", Category.ROUTING),
            Map.entry("route map", Category.ROUTING),
            Map.entry("router", Category.ROUTING),
            Map.entry("access list", Category.ACCESS_RULES),
            Map.entry("access group", Category.ACCESS_RULES),
            Map.entry("nat", Category.NAT),
            Map.entry("username", Category.ADMIN_ACCESS),
            Map.entry("failover", Category.HIGH_AVAILABILITY),
            Map.entry("monitor interface", Category.HIGH_AVAILABILITY));

    /** FortiGate {@code config} blocks. */
    private static final Map<String, Category> FORTINET = Map.ofEntries(
            Map.entry("system interface", Category.INTERFACES),
            Map.entry("system zone", Category.INTERFACES),
            Map.entry("system virtual-wire-pair", Category.INTERFACES),
            Map.entry("router static", Category.ROUTING),
            Map.entry("router static6", Category.ROUTING),
            Map.entry("router bgp", Category.ROUTING),
            Map.entry("router ospf", Category.ROUTING),
            Map.entry("router ospf6", Category.ROUTING),
            Map.entry("router policy", Category.ROUTING),
            Map.entry("firewall policy", Category.ACCESS_RULES),
            Map.entry("firewall policy6", Category.ACCESS_RULES),
            Map.entry("firewall security-policy", Category.ACCESS_RULES),
            Map.entry("firewall vip", Category.NAT),
            Map.entry("firewall vip6", Category.NAT),
            Map.entry("firewall vipgrp", Category.NAT),
            Map.entry("firewall ippool", Category.NAT),
            Map.entry("firewall central-snat-map", Category.NAT),
            Map.entry("system admin", Category.ADMIN_ACCESS),
            Map.entry("system accprofile", Category.ADMIN_ACCESS),
            Map.entry("system api-user", Category.ADMIN_ACCESS),
            Map.entry("system ha", Category.HIGH_AVAILABILITY));

    /** What a categorised comparison can and cannot see, per vendor (served as the response's {@code coverage}). */
    public static final Map<String, String> COVERAGE = Map.of(
            "check_point", "Gaia interfaces, routing, admin users and cluster/VRRP, including same-count edits for runs "
                    + "recorded from 2026-10-23. Access rules and NAT are management-server policy: see Policy > Changes.",
            "palo_alto", "Routing (virtual routers), zones, virtual wires and admin users, by entry count. Rulebase, NAT, "
                    + "interfaces and HA are not in the stored index yet: rule changes are on Policy > Changes.",
            "cisco_asa", "Interfaces, routing, access lists, NAT, users and failover, by line count only.",
            "fortinet", "Interfaces, routing, firewall policy, VIP/IP pools, admins and HA, by entry count only.");

    private MajorDeviationRules() {
    }

    /** True when {@link #categoryOf} can answer for this vendor. */
    public static boolean categorises(String vendor) {
        return COVERAGE.containsKey(vendor);
    }

    /** The major category of one changed section, or empty when the change is minor (or the vendor is unknown). */
    public static Optional<Category> categoryOf(String vendor, String context, String section) {
        if (vendor == null || section == null) {
            return Optional.empty();
        }
        String name = section.strip().toLowerCase(Locale.ROOT);
        return switch (vendor) {
            case "check_point" -> Optional.ofNullable(CHECK_POINT.get(name));
            case "palo_alto" -> {
                String ctx = context == null ? "" : context.strip().toLowerCase(Locale.ROOT);
                Category any = PALO_ALTO_ANY_CONTEXT.get(name);
                if (any != null) {
                    yield Optional.of(any);
                }
                yield PALO_ALTO_TOP_LEVEL_CONTEXTS.contains(ctx)
                        ? Optional.ofNullable(PALO_ALTO_TOP_LEVEL.get(ctx + " " + name))
                        : Optional.empty();
            }
            case "cisco_asa" -> Optional.ofNullable(CISCO_ASA.get(name));
            case "fortinet" -> Optional.ofNullable(FORTINET.get(name));
            default -> Optional.empty();
        };
    }
}
