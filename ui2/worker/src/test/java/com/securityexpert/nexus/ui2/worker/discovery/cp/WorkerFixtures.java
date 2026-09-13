package com.securityexpert.nexus.ui2.worker.discovery.cp;

/**
 * Synthetic response text for the adapter's fixture tests, in the measured
 * shapes the aligned transport now reads: one domain identifier per line
 * (record §10), the {@code cpmiquerybin object} tree format (record §10 row
 * 4), and {@code netstat -an} text (record §4 item 6). No real address,
 * hostname, domain name or object name appears here -- every value is
 * invented for this test source (platform-core's {@code discovery/cp/
 * Fixtures.java} discipline).
 */
final class WorkerFixtures {

    private WorkerFixtures() {
    }

    static final String DOMAIN_A_UID = "fixture-domain-a";
    static final String DOMAIN_B_UID = "fixture-domain-b";

    static final String DOMAIN_A_GATEWAY_MGMT_ADDRESS = "198.51.100.10";
    static final String DOMAIN_A_MEMBER_MGMT_ADDRESS = "198.51.100.11";
    static final String DOMAIN_B_GATEWAY_MGMT_ADDRESS = "198.51.100.20";
    static final String DOMAIN_B_MEMBER_MGMT_ADDRESS = "198.51.100.21";
    /** Present in the raw connection table but matches no object's management address (CS-6a: dropped, never reported). */
    static final String UNMATCHED_TABLE_ADDRESS = "198.51.100.99";

    static String twoDomainsResponse() {
        return DOMAIN_A_UID + "\n" + DOMAIN_B_UID + "\n";
    }

    /** K-1 standalone product gateway. */
    static String gatewayObject(String uid, String name, String managementAddress) {
        return "(" + name + "\n"
                + "\t:name (" + name + ")\n"
                + "\t:type (gateway)\n"
                + "\t:AdminInfo (\n"
                + "\t\t:chkpf_uid (" + uid + ")\n"
                + "\t)\n"
                + "\t:ipaddr (" + managementAddress + ")\n"
                + "\t:mgmt_ip (" + managementAddress + ")\n"
                + "\t:cp_products_installed (true)\n"
                + "\t:vsx_netobj (false)\n"
                + "\t:vs_netobj (false)\n"
                + "\t:appliance_type (fixture-model)\n"
                + "\t:svn_version_name (fixture-version)\n"
                + "\t:connection_state (fixture-most-positive-state)\n"
                + ")\n";
    }

    /** K-7 plain HA cluster: no address fields at all (CR-3a/CR-4: absent, never a physical device). */
    static String clusterObject(String uid, String name) {
        return "(" + name + "\n"
                + "\t:name (" + name + ")\n"
                + "\t:type (gateway_cluster)\n"
                + "\t:AdminInfo (\n"
                + "\t\t:chkpf_uid (" + uid + ")\n"
                + "\t)\n"
                + "\t:cp_products_installed (true)\n"
                + "\t:vsx_cluster_netobj (false)\n"
                + "\t:vs_cluster_netobj (false)\n"
                + "\t:appliance_type (fixture-cluster-model)\n"
                + "\t:svn_version_name (fixture-cluster-version)\n"
                + ")\n";
    }

    /** K-10 plain cluster member, referencing the given cluster by identifier. Software version absent (record §5). */
    static String memberObject(String uid, String name, String managementAddress, String clusterUid, String clusterName) {
        return "(" + name + "\n"
                + "\t:name (" + name + ")\n"
                + "\t:type (cluster_member)\n"
                + "\t:AdminInfo (\n"
                + "\t\t:chkpf_uid (" + uid + ")\n"
                + "\t)\n"
                + "\t:ipaddr (" + managementAddress + ")\n"
                + "\t:mgmt_ip (" + managementAddress + ")\n"
                + "\t:cp_products_installed (true)\n"
                + "\t:vsx_cluster_member (false)\n"
                + "\t:vs_cluster_member (false)\n"
                + "\t:cluster_object (\n"
                + "\t\t:chkpf_uid (" + clusterUid + ")\n"
                + "\t\t:name (" + clusterName + ")\n"
                + "\t)\n"
                + "\t:connection_state (fixture-most-positive-state)\n"
                + ")\n";
    }

    static String objectDump(String... objects) {
        return String.join("", objects);
    }

    static String emptyObjectDump() {
        return "";
    }

    static String netstatRow(String foreignAddress, int port, boolean established) {
        return "tcp        0      0 0.0.0.0:0               " + foreignAddress + ":" + port + "          "
                + (established ? "ESTABLISHED" : "SYN_SENT");
    }

    static String connectionTable(String... rows) {
        return String.join("\n", rows) + "\n";
    }
}
