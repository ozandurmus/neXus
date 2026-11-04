package com.securityexpert.nexus.ui2.service.pathanalysis.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;

/**
 * Stage A5a tests outside this package reuse the A4 synthetic estates and policies through this facade, so the API
 * golden runs over exactly the topology and snapshots the engine golden uses (documentation addresses, pseudonym names).
 */
public final class PathGoldenAccess {

    private PathGoldenAccess() {
    }

    public static JsonNode estate(String name) {
        return PathFixtures.estate(name);
    }

    /** The A4 Check Point chain policy: static destination NAT to the web server, clients allowed, then drop. */
    public static PolicySnapshot cpChain(String... deviceIds) {
        return PathEngineGoldenTest.cpChain(deviceIds);
    }

    /** The A4 Palo Alto local policy: HTTPS to the web server only, source NAT into the DMZ. */
    public static PolicySnapshot panHttpsOnlyWithSourceNat(String deviceId) {
        return PathEngineGoldenTest.panHttpsOnly(deviceId, PathFixtures.PAN_SNAT);
    }

    /** The A4 reference binding of the chain estate (what the production binder must reproduce). */
    public static HopPolicySource chainReferencePolicies() {
        return PathEngineGoldenTest.chainPolicies();
    }

    public static String render(String title, TopologyGraph graph, PathResult result) {
        return PathFixtures.render(title, graph, result);
    }

    public static java.util.Map<String, String> labels(TopologyGraph graph) {
        return PathFixtures.labels(graph);
    }
}
