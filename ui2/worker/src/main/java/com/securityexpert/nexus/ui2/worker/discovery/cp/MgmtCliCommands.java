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

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
