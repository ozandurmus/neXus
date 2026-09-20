package com.securityexpert.nexus.ui2.worker.backup.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SemanticDeviationEngineTest {

    private SemanticDeviationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new SemanticDeviationEngine();
    }

    @Test
    void firstRunWithNullPreviousConfigReturnsFirstRun() {
        SemanticDeviationEngine.DeviationOutcome outcome = engine.evaluate(
                "check_point",
                null,
                "set interface eth0 ipv4-address 192.0.2.1 mask-length 24"
        );
        assertEquals(SemanticDeviationEngine.DeviationClass.FIRST_RUN, outcome.deviationClass());
        assertFalse(outcome.majorAlert());
    }

    @Test
    void identicalConfigReturnsUnchanged() {
        String config = "set interface eth0 ipv4-address 192.0.2.1 mask-length 24\nset static-route default nexthop 192.0.2.254";
        SemanticDeviationEngine.DeviationOutcome outcome = engine.evaluate("check_point", config, config);
        assertEquals(SemanticDeviationEngine.DeviationClass.UNCHANGED, outcome.deviationClass());
        assertFalse(outcome.majorAlert());
    }

    @Test
    void checkPointInterfaceChangeTriggersMajorAlert() {
        String prev = "set interface eth0 ipv4-address 192.0.2.1 mask-length 24\nset static-route default nexthop 192.0.2.254";
        String curr = "set interface eth0 ipv4-address 192.0.2.2 mask-length 24\nset static-route default nexthop 192.0.2.254";

        SemanticDeviationEngine.DeviationOutcome outcome = engine.evaluate("check_point", prev, curr);
        assertEquals(SemanticDeviationEngine.DeviationClass.MAJOR, outcome.deviationClass());
        assertTrue(outcome.majorAlert());
        assertTrue(outcome.summary().contains("interface change"));
    }

    @Test
    void checkPointAdminUserAddedTriggersMajorAlert() {
        String prev = "set interface eth0 ipv4-address 192.0.2.1 mask-length 24\nadd user admin password-hash $1$abc";
        String curr = "set interface eth0 ipv4-address 192.0.2.1 mask-length 24\nadd user admin password-hash $1$abc\nadd user backdoor password-hash $1$xyz";

        SemanticDeviationEngine.DeviationOutcome outcome = engine.evaluate("check_point", prev, curr);
        assertEquals(SemanticDeviationEngine.DeviationClass.MAJOR, outcome.deviationClass());
        assertTrue(outcome.majorAlert());
        assertTrue(outcome.summary().contains("admin user"));
    }

    @Test
    void paloAltoSecurityRuleAddedTriggersMajorAlert() {
        String prev = "<config><rulebase><security><rules>\n<entry name=\"Allow-DNS\"></entry>\n</rules></security></rulebase></config>";
        String curr = "<config><rulebase><security><rules>\n<entry name=\"Allow-DNS\"></entry>\n<entry name=\"Any-Any-Allow\"></entry>\n</rules></security></rulebase></config>";

        SemanticDeviationEngine.DeviationOutcome outcome = engine.evaluate("palo_alto", prev, curr);
        assertEquals(SemanticDeviationEngine.DeviationClass.MAJOR, outcome.deviationClass());
        assertTrue(outcome.majorAlert());
        assertTrue(outcome.summary().contains("security rule(s) added"));
    }

    @Test
    void minorCommentOrTimestampChangeReturnsMinor() {
        String prev = "set interface eth0 ipv4-address 192.0.2.1 mask-length 24\n# Generated at 2026-09-20T01:00:00Z";
        String curr = "set interface eth0 ipv4-address 192.0.2.1 mask-length 24\n# Generated at 2026-09-20T02:00:00Z";

        SemanticDeviationEngine.DeviationOutcome outcome = engine.evaluate("check_point", prev, curr);
        assertEquals(SemanticDeviationEngine.DeviationClass.MINOR, outcome.deviationClass());
        assertFalse(outcome.majorAlert());
    }
}
