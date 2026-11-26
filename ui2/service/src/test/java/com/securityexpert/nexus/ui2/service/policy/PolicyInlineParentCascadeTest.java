package com.securityexpert.nexus.ui2.service.policy;

import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.cpSection;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.edit;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/** P1 part A: the mapper now names an inline-layer parent "Apply Layer"; rules below it become decidable (synthetic). */
class PolicyInlineParentCascadeTest {
    @Test void rulesBelowAnInlineParentAreDecidedOnlyWhenTheParentIsApplyLayer() {
        var snapshot = cpSnapshot(cpSection("s1", null, targeted(applyLayer("p", 1, "net", "inline-1")),
            cp("r2", 2, "Accept", "host", "targets"), cp("r3", 3, "Accept", "any", "targets")));
        // host is inside net: never reached, and never an action conflict (the inline verdict is not analysed).
        assertInlineCover(snapshot, "r2", "p");
        assertEquals("NOT_SHADOWED", at(snapshot, "r3").shadowStatus()); // not inside the parent's match: not covered
        // The parent left as UNKNOWN (the pre-fix mapping) blocks every rule below it.
        var blocked = cpSnapshot(cpSection("s1", null, unknownAction(targeted(applyLayer("p", 1, "net", "inline-1"))),
            cp("r2", 2, "Accept", "host", "targets"), cp("r3", 3, "Accept", "any", "targets")));
        assertEquals("UNKNOWN", at(blocked, "r3").shadowStatus());
    }
    private static com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule unknownAction(com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule r) {
        return new com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule(r.id(), r.uuid(), r.number(), r.name(), r.enabled(), r.source(), r.destination(),
            r.service(), r.application(), "UNKNOWN", r.log(), r.comment(), r.extras(), r.hitCounts());
    }
    private static com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule targeted(com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule r) {
        return edit(r, r.source(), Map.of("install-on", List.of("targets")));
    }
}
