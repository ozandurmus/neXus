package com.securityexpert.nexus.ui2.service.pathanalysis.engine;

import static com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathEngineGoldenTest.*;
import static com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.pathanalysis.query.CutoverPathTargets;
import com.securityexpert.nexus.ui2.service.policy.CutoverAssessment;

/**
 * The routed-path target of the pre-cutover impact analysis (PO 2026-11-14): a flow's decision is its computed path's
 * verdict over the golden estates; only ALLOWED and DENIED become decided, everything else stays NOT_DECIDED with the
 * path's own reason, and the firewalls on the path are listed.
 */
class CutoverPathDecisionTest {

    @Test
    void decidedPathsBecomeDecisionsAndListTheirFirewalls() {
        PathResult allowed = scenario("allowed-cp-pan-nat");
        var decision = CutoverPathTargets.decision(allowed);
        assertEquals("WOULD_ALLOW", decision.decision()); assertNull(decision.rule());
        assertEquals(List.of("CP", "PAN"), decision.path().stream().map(f -> f.get("vendor")).toList());
        assertTrue(decision.path().stream().allMatch(f -> "ALLOWED".equals(f.get("hopVerdict")) && f.get("deviceId") != null));

        PathResult denied = scenario("denied-at-second-hop");
        var drop = CutoverPathTargets.decision(denied);
        assertEquals("WOULD_DROP", drop.decision());
        assertNotNull(drop.rule());
        assertEquals("deny", drop.rule().get("action"));
        assertTrue(drop.path().stream().anyMatch(f -> "DENIED".equals(f.get("hopVerdict"))));
    }

    @Test
    void everyOtherPathVerdictStaysNotDecidedWithThePathsReason() {
        for (String name : List.of("exits-known-topology", "unknown-hop-taints-downstream", "ecmp-partial", "asymmetric-return",
                "ambiguous-ingress", "routing-loop", "discard-route")) {
            PathResult result = scenario(name);
            if (Set.of(PathReasons.ALLOWED, PathReasons.DENIED).contains(result.verdict())) continue;
            var decision = CutoverPathTargets.decision(result);
            assertEquals("NOT_DECIDED", decision.decision(), name);
            assertEquals(result.reason() == null || result.reason().isBlank() ? result.verdict() : result.reason(), decision.reason(), name);
            assertTrue(CutoverPathTargets.REASONS.contains(decision.reason()), name + " reason must be in the aiview allowlist: " + decision.reason());
            assertNull(decision.rule(), name);
        }
    }

    @Test
    void theRoutedPolicyTracesEachFlowThroughTheHeldTopology() {
        var routed = new CutoverPathTargets.Routed(graph("chain.json"), chainPolicies(), COMPUTED_AT);
        assertEquals(CutoverAssessment.SOURCE_PATH, routed.source());
        assertTrue(routed.coverage().contains("FABRIC_REDIRECT_NOT_MODELLED"));
        assertEquals("WOULD_ALLOW", routed.decide("198.51.100.20", "203.0.113.200", "tcp", 443, "", "").decision());
        assertEquals("WOULD_DROP", routed.decide("198.51.100.20", "203.0.113.200", "tcp", 22, "", "").decision());
        assertEquals("NOT_DECIDED", routed.decide("198.51.100.20", "203.0.113.150", "tcp", 443, "", "").decision());
    }
}
