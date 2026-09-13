package com.securityexpert.nexus.ui2.worker.discovery.cp;

/**
 * Synthetic {@code -f json} response text for the adapter's fixture tests.
 * No real address, hostname, domain name or object name appears here --
 * every value is invented for this test source (platform-core's {@code
 * discovery/cp/Fixtures.java} discipline).
 */
final class WorkerFixtures {

    private WorkerFixtures() {
    }

    static final String TOP_SESSION_ID = "fixture-top-session-1";
    static final String DOMAIN_A_UID = "fixture-domain-a";
    static final String DOMAIN_A_SESSION_ID = "fixture-domain-a-session-1";
    static final String DOMAIN_B_UID = "fixture-domain-b";
    static final String DOMAIN_B_SESSION_ID = "fixture-domain-b-session-1";

    static final String DOMAIN_A_GATEWAY_MGMT_ADDRESS = "198.51.100.10";
    static final String DOMAIN_A_MEMBER_MGMT_ADDRESS = "198.51.100.11";
    static final String DOMAIN_B_GATEWAY_MGMT_ADDRESS = "198.51.100.20";
    static final String DOMAIN_B_MEMBER_MGMT_ADDRESS = "198.51.100.21";
    /** Present in the raw connection table but matches no object's management address (CS-6a: dropped, never reported). */
    static final String UNMATCHED_TABLE_ADDRESS = "198.51.100.99";

    static String loginResponse() {
        return "{\"sid\":\"" + TOP_SESSION_ID + "\"}";
    }

    static String loginToDomainResponse(String domainSessionId) {
        return "{\"sid\":\"" + domainSessionId + "\"}";
    }

    static String twoDomainsResponse() {
        return "[{\"uid\":\"" + DOMAIN_A_UID + "\"},{\"uid\":\"" + DOMAIN_B_UID + "\"}]";
    }

    static String emptyPageResponse() {
        return "[]";
    }

    /** K-1 standalone product gateway. */
    static String gatewayObject(String uid, String name, String managementAddress) {
        return "{\"uid\":\"" + uid + "\",\"name\":\"" + name + "\",\"type\":\"true\","
                + "\"chassis-role\":\"false\",\"hosted-instance-role\":\"false\","
                + "\"ipv4-address\":\"" + managementAddress + "\","
                + "\"controlling-device-address\":\"" + managementAddress + "\"}";
    }

    /** K-7 plain HA cluster: no address fields at all (CR-3a/CR-4: absent, never a physical device). */
    static String clusterObject(String uid, String name) {
        return "{\"uid\":\"" + uid + "\",\"name\":\"" + name + "\","
                + "\"type\":\"true\",\"chassis-role\":\"false\",\"hosted-instance-role\":\"false\"}";
    }

    /** K-10 plain cluster member, referencing the given cluster by identifier. */
    static String memberObject(String uid, String name, String managementAddress, String clusterUid, String clusterName) {
        return "{\"uid\":\"" + uid + "\",\"name\":\"" + name + "\",\"type\":\"true\","
                + "\"chassis-role\":\"false\",\"hosted-instance-role\":\"false\","
                + "\"ipv4-address\":\"" + managementAddress + "\","
                + "\"controlling-device-address\":\"" + managementAddress + "\","
                + "\"cluster-uid\":\"" + clusterUid + "\",\"cluster-name\":\"" + clusterName + "\"}";
    }

    static String array(String... objects) {
        return "[" + String.join(",", objects) + "]";
    }

    static String connectionTableRow(String address, int port, boolean established) {
        return "{\"peer-ip\":\"" + address + "\",\"peer-port\":" + port + ",\"channel-state\":\""
                + (established ? "established" : "attempting") + "\"}";
    }

    static String connectionTable(String... rows) {
        return "[" + String.join(",", rows) + "]";
    }
}
