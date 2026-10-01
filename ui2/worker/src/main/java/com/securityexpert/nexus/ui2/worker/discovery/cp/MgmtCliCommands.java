package com.securityexpert.nexus.ui2.worker.discovery.cp;

public final class MgmtCliCommands {
    private MgmtCliCommands() {}

    public static String domainList() {
        return "mgmt_cli -r true -f json show-domains limit 500 details-level full";
    }

    public static String showGatewaysAndServers(String domainIdentifier) {
        return "mgmt_cli -r true -d " + quote(domainIdentifier) + " -f json show-gateways-and-servers limit 500 details-level full";
    }

    public static String connectionTable() {
        return "netstat -an";
    }

    public static String showPackages(String domain) {
        return "mgmt_cli -r true -d " + quote(domain) + " -f json show-packages limit 500 details-level full";
    }

    public static String showAccessRulebase(String domain, String layer, int offset) {
        if (offset < 0) throw new IllegalArgumentException("Invalid policy offset");
        return "mgmt_cli -r true -d " + quote(domain) + " -f json show-access-rulebase name " + quote(layer)
                + " limit 500 offset " + quote(Integer.toString(offset)) + " details-level full use-object-dictionary true";
    }

    public static String showNatRulebase(String domain, String policy, int offset) {
        if (offset < 0) throw new IllegalArgumentException("Invalid policy offset");
        return "mgmt_cli -r true -d " + quote(domain) + " -f json show-nat-rulebase package " + quote(policy)
                + " limit 500 offset " + quote(Integer.toString(offset)) + " details-level standard use-object-dictionary true";
    }

    private static String quote(String value) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid management argument");
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
