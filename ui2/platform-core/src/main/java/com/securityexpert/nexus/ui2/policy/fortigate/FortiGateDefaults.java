package com.securityexpert.nexus.ui2.policy.fortigate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Versioned FortiOS defaults table for settings omitted by top-level {@code show} (A0-FG-6).
 *
 * <p>Every entry is keyed by FortiOS minor version ({@code "7.4"}) and cites its frozen amendment ID in
 * {@code docs/design/PATH_ANALYSIS_VENDOR_SEMANTICS_AMENDMENT_2026_11_14_ASA_FORTIGATE.md}, the official
 * Fortinet CLI reference source key, URL, and verified quote. Any setting absent from {@code show} that does
 * not have an entry in this table for the unit's minor version has no proven default and must fail closed to
 * {@code UNKNOWN} (design section 4.3).
 */
public final class FortiGateDefaults {

    public static final String FORTIOS_7_4 = "7.4";

    private static final String URL_FG_CLIPOL =
            "https://docs.fortinet.com/document/fortigate/7.4.7/cli-reference/333889629/config-firewall-policy";
    private static final String URL_FG_ADDR =
            "https://docs.fortinet.com/document/fortigate/7.4.4/cli-reference/306021697/config-firewall-address";
    private static final String URL_FG_SVC =
            "https://docs.fortinet.com/document/fortigate/7.4.0/cli-reference/236620/config-firewall-service-custom";
    private static final String URL_FG_ZONE =
            "https://docs.fortinet.com/document/fortigate/7.4.0/cli-reference/67620/config-system-zone";

    /**
     * One documented default value for a FortiOS configuration table setting.
     */
    public record CitedDefault(
            String minorVersion,
            String table,
            String setting,
            String defaultValue,
            String amendmentId,
            String sourceKey,
            String sourceUrl,
            String quote) {
        public CitedDefault {
            if (minorVersion == null || minorVersion.isBlank()
                    || table == null || table.isBlank()
                    || setting == null || setting.isBlank()
                    || defaultValue == null || defaultValue.isBlank()
                    || amendmentId == null || amendmentId.isBlank()
                    || sourceKey == null || sourceKey.isBlank()
                    || sourceUrl == null || sourceUrl.isBlank()
                    || quote == null || quote.isBlank()) {
                throw new IllegalArgumentException("Every FortiOS default entry must carry a complete citation");
            }
        }
    }

    /**
     * Settings that {@code config firewall policy} requires for evaluation and that have NO cited default in
     * {@code A0-FG-5} (for example, {@code schedule} explicitly has no default). If any of these is absent from
     * a policy entry, the rule must be marked {@code UNKNOWN}.
     */
    public static final Set<String> POLICY_SETTINGS_WITHOUT_DEFAULT = Set.of(
            "srcintf", "dstintf", "srcaddr", "dstaddr", "service", "schedule");

    private static final Map<String, Map<String, CitedDefault>> TABLES_BY_MINOR = buildTables();

    private FortiGateDefaults() {}

    private static Map<String, Map<String, CitedDefault>> buildTables() {
        Map<String, CitedDefault> v74 = new LinkedHashMap<>();

        // firewall policy defaults (A0-FG-4, A0-FG-5, A0-FG-8, A0-FG-20, A0-FG-22; FG-CLIPOL 7.4.7)
        register(v74, new CitedDefault(
                FORTIOS_7_4,
                "firewall policy",
                "action",
                "deny",
                "A0-FG-5",
                "FG-CLIPOL",
                URL_FG_CLIPOL,
                "\"Policy action (accept/deny/ipsec).\" (default deny)"));
        register(v74, new CitedDefault(
                FORTIOS_7_4,
                "firewall policy",
                "status",
                "enable",
                "A0-FG-5",
                "FG-CLIPOL",
                URL_FG_CLIPOL,
                "Enable or disable this policy. (default = enable)"));
        register(v74, new CitedDefault(
                FORTIOS_7_4,
                "firewall policy",
                "nat",
                "disable",
                "A0-FG-5",
                "FG-CLIPOL",
                URL_FG_CLIPOL,
                "Enable/disable network address translation (NAT) for this policy. (default = disable)"));
        register(v74, new CitedDefault(
                FORTIOS_7_4,
                "firewall policy",
                "srcaddr-negate",
                "disable",
                "A0-FG-4",
                "FG-CLIPOL",
                URL_FG_CLIPOL,
                "\"When enabled srcaddr specifies what the source address must NOT be.\" (default disable)"));
        register(v74, new CitedDefault(
                FORTIOS_7_4,
                "firewall policy",
                "dstaddr-negate",
                "disable",
                "A0-FG-4",
                "FG-CLIPOL",
                URL_FG_CLIPOL,
                "When enabled dstaddr specifies what the destination address must NOT be. (default disable)"));
        register(v74, new CitedDefault(
                FORTIOS_7_4,
                "firewall policy",
                "service-negate",
                "disable",
                "A0-FG-4",
                "FG-CLIPOL",
                URL_FG_CLIPOL,
                "When enabled service specifies what the service must NOT be. (default disable)"));
        register(v74, new CitedDefault(
                FORTIOS_7_4,
                "firewall policy",
                "internet-service",
                "disable",
                "A0-FG-8",
                "FG-CLIPOL",
                URL_FG_CLIPOL,
                "\"If enabled, destination address and service are not used.\" (default disable)"));
        register(v74, new CitedDefault(
                FORTIOS_7_4,
                "firewall policy",
                "internet-service-src",
                "disable",
                "A0-FG-8",
                "FG-CLIPOL",
                URL_FG_CLIPOL,
                "Enable/disable use of Internet Services in source for this policy. (default disable)"));
        register(v74, new CitedDefault(
                FORTIOS_7_4,
                "firewall policy",
                "ippool",
                "disable",
                "A0-FG-22",
                "FG-CLIPOL",
                URL_FG_CLIPOL,
                "\"Enable to use IP Pools for source NAT.\" (default disable)"));
        register(v74, new CitedDefault(
                FORTIOS_7_4,
                "firewall policy",
                "match-vip",
                "enable",
                "A0-FG-20",
                "FG-CLIPOL",
                URL_FG_CLIPOL,
                "\"Enable to match packets that have had their destination addresses changed by a VIP.\" (default enable)"));
        register(v74, new CitedDefault(
                FORTIOS_7_4,
                "firewall policy",
                "match-vip-only",
                "disable",
                "A0-FG-20",
                "FG-CLIPOL",
                URL_FG_CLIPOL,
                "Enable/disable match packets only that have had their destination addresses changed by a VIP. (default disable)"));

        // firewall address defaults (A0-FG-23, A0-FG-24; FG-ADDR 7.4.4)
        register(v74, new CitedDefault(
                FORTIOS_7_4,
                "firewall address",
                "type",
                "ipmask",
                "A0-FG-24",
                "FG-ADDR",
                URL_FG_ADDR,
                "\"Standard IPv4 address with subnet mask.\" (default ipmask)"));
        register(v74, new CitedDefault(
                FORTIOS_7_4,
                "firewall address",
                "subnet",
                "0.0.0.0 0.0.0.0",
                "A0-FG-24",
                "FG-ADDR",
                URL_FG_ADDR,
                "\"IP address and subnet mask of address.\" (default 0.0.0.0 0.0.0.0)"));

        // firewall service custom defaults (A0-FG-26; FG-SVC 7.4.0)
        register(v74, new CitedDefault(
                FORTIOS_7_4,
                "firewall service custom",
                "protocol",
                "TCP/UDP/SCTP",
                "A0-FG-26",
                "FG-SVC",
                URL_FG_SVC,
                "\"Multiple TCP port ranges.\" / Protocol type default TCP/UDP/SCTP"));

        // system zone defaults (A0-FG-10; FG-ZONE 7.4.0 / 7.4.4)
        register(v74, new CitedDefault(
                FORTIOS_7_4,
                "system zone",
                "intrazone",
                "deny",
                "A0-FG-10",
                "FG-ZONE",
                URL_FG_ZONE,
                "\"Allow or deny traffic routing between different interfaces in the same zone.\" (default deny)"));

        return Map.of(FORTIOS_7_4, Collections.unmodifiableMap(v74));
    }

    private static void register(Map<String, CitedDefault> map, CitedDefault entry) {
        String key = key(entry.table(), entry.setting());
        if (map.put(key, entry) != null) {
            throw new IllegalStateException("Duplicate default entry: " + key);
        }
    }

    private static String key(String table, String setting) {
        return table + "|" + setting;
    }

    /** True when the given FortiOS minor version (e.g. {@code "7.4"}) has a proven defaults table. */
    public static boolean isSupportedMinor(String minorVersion) {
        return minorVersion != null && TABLES_BY_MINOR.containsKey(minorVersion);
    }

    /** Looks up a cited default entry for {@code minorVersion}, {@code table}, and {@code setting}. */
    public static Optional<CitedDefault> lookup(String minorVersion, String table, String setting) {
        if (minorVersion == null || table == null || setting == null) return Optional.empty();
        Map<String, CitedDefault> entries = TABLES_BY_MINOR.get(minorVersion);
        if (entries == null) return Optional.empty();
        return Optional.ofNullable(entries.get(key(table, setting)));
    }

    /**
     * Returns the cited default value for {@code minorVersion}, {@code table}, and {@code setting},
     * or {@code null} when either the minor version has no table or the setting has no cited default.
     */
    public static String defaultValue(String minorVersion, String table, String setting) {
        return lookup(minorVersion, table, setting).map(CitedDefault::defaultValue).orElse(null);
    }

    /** Returns an unmodifiable view of all cited defaults for {@code minorVersion}, or an empty map if unsupported. */
    public static Map<String, CitedDefault> entriesForMinor(String minorVersion) {
        if (minorVersion == null) return Map.of();
        return TABLES_BY_MINOR.getOrDefault(minorVersion, Map.of());
    }
}
