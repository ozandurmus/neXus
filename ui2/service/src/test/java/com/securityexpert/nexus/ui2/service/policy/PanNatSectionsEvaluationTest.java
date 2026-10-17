package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyLookupTest.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/** Palo Alto NAT and default-rule sections are stored beside the security rulebase but never evaluated as security. */
class PanNatSectionsEvaluationTest {
    private static Section nat(String name, Rule... rules) { return new Section(ref("p1", "Shared", "pre-rulebase", "nat"), name, "Shared", null, List.of(rules)); }
    private static Rule natRule(String id, Boolean enabled) {
        return edit(rule(id, "any", "any", "source-nat", enabled), cell("any"), Map.of());
    }

    @Test void lookupIgnoresNatAndDefaultSectionsInsteadOfFailingOrDeciding() {
        var security = panSection("Shared", "pre-rulebase", rule("r1", "host", "port", "allow", true));
        var plain = sections("PAN", security);
        var withNat = sections("PAN", security, nat(PAN_PRE_NAT, natRule("n1", true)),
            new Section(ref("p1", "Shared", "post-rulebase", "default-security-rules"), PAN_POST_DEFAULT, "Shared", null,
                List.of(rule("d1", "any", "any", "deny", true))));
        assertEquals("ALLOWED", verdict(withNat));
        assertEquals(run(plain, query("192.0.2.8")).get("decidingRule"), run(withNat, query("192.0.2.8")).get("decidingRule"));
    }

    @Test void hygieneDoesNotInventFindingsForNatRulesAndDoesNotDisturbSecurityResults() {
        var security = panSection("Shared", "pre-rulebase", rule("r1", "any", "any", "allow", true), rule("r2", "host", "port", "allow", true));
        var plain = PolicyHygiene.analyze(sections("PAN", security), 90, NOW, 250000);
        var snapshot = sections("PAN", security, nat(PAN_PRE_NAT, natRule("n1", true), natRule("n2", false)));
        var result = PolicyHygiene.analyze(snapshot, 90, NOW, 250000);
        assertEquals(plain.rules().get("r1"), result.rules().get("r1"));
        assertEquals(plain.rules().get("r2"), result.rules().get("r2"));
        var enabled = result.rules().get("n1");
        assertEquals("UNKNOWN", enabled.shadowStatus()); assertEquals("unsupported rulebase", enabled.shadowReason());
        assertTrue(enabled.findings().isEmpty(), "no unknown/broad/unused finding is manufactured for a NAT rule");
        assertEquals(List.of("disabled"), result.rules().get("n2").findings().stream().map(PolicyHygiene.Finding::findingClass).toList());
    }

    @Test void privacyProjectionKeepsNatSectionLabelsAndActionsAsFixedVocabulary() {
        assertEquals("source-and-destination-nat", PolicyPrivacy.mask("source-and-destination-nat", "action", null, null));
        assertEquals("UNKNOWN", PolicyPrivacy.mask("anything-else-nat", "action", null, null));
        assertEquals("external-list", PolicyPrivacy.mask("external-list", "type", null, null));
        // Translated addresses are opaque object references; other NAT detail stays withheld.
        var extras = (Map<?, ?>) PolicyPrivacy.mask(new LinkedHashMap<>(Map.of("translated-source", List.of("opaque-object-id"),
            "source-translation-interface", List.of("ethernet1/1"))), "extras", null, null);
        assertEquals(List.of("opaque-object-id"), extras.get("translated-source"));
        assertEquals(List.of("Withheld in AIView"), extras.get("source-translation-interface"));
    }
}
