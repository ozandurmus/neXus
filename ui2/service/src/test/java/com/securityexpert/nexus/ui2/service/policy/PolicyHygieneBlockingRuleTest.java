package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.service.privacy.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygienePartialPriorTest.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.assessment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * A rule that ends UNKNOWN "earlier rule has unsupported constraints" names the earliest earlier rule that kept it from
 * being proven (blockingRuleId); the hygiene page counts the distinct blockers and the five biggest. Synthetic fixtures only.
 */
class PolicyHygieneBlockingRuleTest {
    final ObjectMapper mapper = new ObjectMapper();
    final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(new byte[32]);
    final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(new byte[32]);
    final PolicyQueryService query = new PolicyQueryService(mock(TransactionBoundary.class), mapper, names, null, ips);

    @Test void theEarliestUnprovenPartialPriorIsNamedAndProvenOnesAreSkipped() {
        // r1 is disjoint from r4 (destination 198.51.100.0/24 against 192.0.2.0/24); r2 and r3 both overlap it; r2 is earlier.
        var snapshot = cp(cp("r1", 1, "Accept", "role", "other-net", "port"), cp("r2", 2, "Drop", "role", "host", "port"),
            cp("r3", 3, "Accept", "role", "net", "port"), cp("r4", 4, "Accept", "any", "net", "port"));
        var r4 = assessment(snapshot, "r4");
        assertEquals(UNSUPPORTED, r4.shadowReason());
        assertEquals("r2", r4.blockingRuleId()); assertEquals(2, r4.blockingRuleNumber());
        // Deterministic: the same answer on a repeated run.
        assertEquals("r2", assessment(cp(cp("r1", 1, "Accept", "role", "other-net", "port"), cp("r2", 2, "Drop", "role", "host", "port"),
            cp("r3", 3, "Accept", "role", "net", "port"), cp("r4", 4, "Accept", "any", "net", "port")), "r4").blockingRuleId());
        // The partial priors themselves are not blocked by a later rule, and carry no blocker.
        assertNull(assessment(snapshot, "r1").blockingRuleId()); assertNull(assessment(snapshot, "r2").blockingRuleId());
    }
    @Test void aRuleWithNoUsablePartialPriorBlocksEverythingAfterIt() {
        var unknown = with(pan("r1", "any", "net", "port", "allow"), Map.of("unexpected-field", List.of("yes")));
        var snapshot = pan(pan("r0", "any", "other-net", "port", "allow"), unknown, pan("r2", "any", "net", "port", "allow"));
        assertEquals("r1", assessment(snapshot, "r2").blockingRuleId());
        assertEquals(unknown.number(), assessment(snapshot, "r2").blockingRuleNumber());
    }
    @Test void provenCoveredAndOtherUnknownRulesNameNoBlocker() {
        var proven = cp(cp("r1", 1, "Accept", "role", "net", "port"), cp("r2", 2, "Accept", "any", "other-host", "port"));
        assertEquals("NOT_SHADOWED", assessment(proven, "r2").shadowStatus()); assertNull(assessment(proven, "r2").blockingRuleId());
        // A full cover found behind a partial prior stays a cover, not a blocked rule.
        var behind = cp(cp("r1", 1, "Accept", "role", "any", "any"), cp("r2", 2, "Drop", "any", "net", "any"), cp("r3", 3, "Accept", "host", "host", "port"));
        assertEquals("CONFLICT", assessment(behind, "r3").shadowStatus()); assertNull(assessment(behind, "r3").blockingRuleId());
        // Unknown for another reason (the rule's own unresolved source) is not "blocked by an earlier rule".
        var own = cp(cp("r1", 1, "Accept", "role", "net", "port"), cp("r2", 2, "Accept", "role", "net", "port"));
        assertNotEquals(UNSUPPORTED, assessment(own, "r2").shadowReason()); assertNull(assessment(own, "r2").blockingRuleId());
    }
    private static com.securityexpert.nexus.ui2.policy.PolicySnapshot withHosts(com.securityexpert.nexus.ui2.policy.PolicySnapshot base, Map<String, com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject> extra) {
        var objects = new HashMap<>(base.objects()); objects.putAll(extra);
        return new com.securityexpert.nexus.ui2.policy.PolicySnapshot(base.metadata(), base.sections(), objects);
    }
    private static com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject host(String id, String address) {
        return PolicyHygieneTest.object(id, "host", "ipv4-address: " + address);
    }
    @Test void theHygienePageCountsDistinctBlockersAndNamesThemOnRowsAndTheRuleView() {
        // Seven equal partial priors (one distinct prior: the earliest, p1, is the blocker); ten later rules on distinct hosts of the same net.
        var rules = new ArrayList<com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule>();
        var hosts = new HashMap<String, com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject>();
        for (int i = 1; i <= 7; i++) rules.add(cp("p" + i, i, "Accept", "role", "net", "port"));
        for (int i = 8; i <= 17; i++) { hosts.put("h" + i, host("h" + i, "192.0.2." + i)); rules.add(cp("x" + i, i, "Accept", "any", "h" + i, "port")); }
        var snapshot = withHosts(cp(rules.toArray(new com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule[0])), hosts);
        var page = mapper.valueToTree(query.hygiene(snapshot, 0, "unknown", "", 90).body());
        var blockers = page.path("blockers");
        assertEquals(1, blockers.path("distinct").asInt());
        assertEquals(1, blockers.path("top").size());
        assertEquals("p1", blockers.path("top").get(0).path("ruleId").asText());
        assertEquals(1, blockers.path("top").get(0).path("number").asInt());
        assertEquals(10, blockers.path("top").get(0).path("blocks").asInt());
        // The hygiene rows and the per-rule view carry the blocker too.
        JsonNode row = null;
        for (var candidate : page.path("rows")) if (candidate.path("ruleId").asText().equals("x8")) row = candidate;
        assertNotNull(row);
        assertEquals("p1", row.path("hygiene").path("blockingRuleId").asText());
        assertEquals(1, row.path("hygiene").path("blockingRuleNumber").asInt());
        var single = mapper.valueToTree(query.rule(snapshot, "x8", 90).orElseThrow().body());
        assertEquals("p1", single.path("rule").path("hygiene").path("blockingRuleId").asText());
        assertEquals(1, single.path("rule").path("hygiene").path("blockingRuleNumber").asInt());
        assertTrue(single.path("rule").path("hygiene").path("blockingRuleId").isTextual());
        assertTrue(mapper.valueToTree(query.rule(snapshot, "p2", 90).orElseThrow().body()).path("rule").path("hygiene").path("blockingRuleId").isNull());
    }
    @Test void onlyTheFiveBiggestBlockersAreListedBiggestFirst() {
        // Six partial priors on six disjoint /28 networks; blocker b is the only prior overlapping its b later rules (counts 1..6).
        var hosts = new HashMap<String, com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject>();
        var rules = new ArrayList<com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule>();
        int number = 1;
        for (int b = 1; b <= 6; b++) {
            hosts.put("net-" + b, PolicyHygieneTest.object("net-" + b, "network", "ip-netmask: 192.0.2." + (b * 16) + "/28"));
            rules.add(cp("p" + b, number++, "Accept", "role", "net-" + b, "port"));
        }
        for (int b = 1; b <= 6; b++) for (int k = 0; k < b; k++) {
            String id = "h" + b + "-" + k; hosts.put(id, host(id, "192.0.2." + (b * 16 + k)));
            rules.add(cp("x" + b + "-" + k, number++, "Accept", "any", id, "port"));
        }
        var snapshot = withHosts(cp(rules.toArray(new com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule[0])), hosts);
        var top = mapper.valueToTree(query.hygiene(snapshot, 0, "unknown", "", 90).body()).path("blockers");
        assertEquals(6, top.path("distinct").asInt());
        assertEquals(5, top.path("top").size());
        assertEquals(List.of("p6", "p5", "p4", "p3", "p2"), top.path("top").findValuesAsText("ruleId"));
        assertEquals(List.of(6, 5, 4, 3, 2), top.path("top").findValues("blocks").stream().map(n -> n.asInt()).toList());
    }
    @Test void aiviewKeepsTheBlockerIdOpaqueAndTheCountsNumeric() {
        var snapshot = cp(cp("r1", 1, "Accept", "role", "net", "port"), cp("r2", 2, "Accept", "any", "net", "port"));
        var masked = mapper.valueToTree(PolicyPrivacy.mask(query.hygiene(snapshot, 0, "unknown", "", 90).body(), "", names, ips));
        assertEquals("r1", masked.path("blockers").path("top").get(0).path("ruleId").asText());
        assertEquals(1, masked.path("blockers").path("top").get(0).path("blocks").asInt());
        JsonNode row = null;
        for (var candidate : masked.path("rows")) if (candidate.path("ruleId").asText().equals("r2")) row = candidate.path("hygiene");
        assertNotNull(row);
        assertEquals("r1", row.path("blockingRuleId").asText());
        assertEquals(1, row.path("blockingRuleNumber").asInt());
        // A rule id is an opaque key whatever its shape; a free-text value under any other new key is still withheld.
        assertEquals("opaque-id-7", PolicyPrivacy.mask("opaque-id-7", "blockingRuleId", names, ips));
    }
}
