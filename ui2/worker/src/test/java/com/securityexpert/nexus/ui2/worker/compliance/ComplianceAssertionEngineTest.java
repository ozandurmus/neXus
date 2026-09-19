package com.securityexpert.nexus.ui2.worker.compliance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.compliance.engine.ComplianceAssertionEngine;
import com.securityexpert.nexus.ui2.worker.compliance.model.AssertionRule;

class ComplianceAssertionEngineTest {

    @Test
    void testPresentAndAbsent() {
        assertTrue(ComplianceAssertionEngine.evaluate("value", AssertionRule.present()));
        assertFalse(ComplianceAssertionEngine.evaluate(null, AssertionRule.present()));
        assertFalse(ComplianceAssertionEngine.evaluate("", AssertionRule.present()));

        assertTrue(ComplianceAssertionEngine.evaluate(null, AssertionRule.absent()));
        assertTrue(ComplianceAssertionEngine.evaluate("", AssertionRule.absent()));
        assertFalse(ComplianceAssertionEngine.evaluate("value", AssertionRule.absent()));
    }

    @Test
    void testEqualsAndNotEquals() {
        assertTrue(ComplianceAssertionEngine.evaluate("on", AssertionRule.equalsStr("on")));
        assertFalse(ComplianceAssertionEngine.evaluate("off", AssertionRule.equalsStr("on")));
        assertTrue(ComplianceAssertionEngine.evaluate("off", AssertionRule.notEqualsStr("on")));
    }

    @Test
    void testNumericComparisons() {
        assertTrue(ComplianceAssertionEngine.evaluate("14", AssertionRule.gte(12)));
        assertTrue(ComplianceAssertionEngine.evaluate("12", AssertionRule.gte(12)));
        assertFalse(ComplianceAssertionEngine.evaluate("10", AssertionRule.gte(12)));

        assertTrue(ComplianceAssertionEngine.evaluate("3", AssertionRule.lte(5)));
        assertTrue(ComplianceAssertionEngine.evaluate("5", AssertionRule.lte(5)));
        assertFalse(ComplianceAssertionEngine.evaluate("6", AssertionRule.lte(5)));
    }

    @Test
    void testPatternMatching() {
        assertTrue(ComplianceAssertionEngine.evaluate("AES256-GCM", AssertionRule.matches("(?i)aes")));
        assertFalse(ComplianceAssertionEngine.evaluate("3DES-CBC", AssertionRule.noneMatch("(?i)(cbc|3des)")));
        assertTrue(ComplianceAssertionEngine.evaluate("AES256-GCM", AssertionRule.noneMatch("(?i)(cbc|3des)")));
    }

    @Test
    void testListOperations() {
        assertTrue(ComplianceAssertionEngine.evaluateList(List.of("10.0.0.1", "10.0.0.2"), AssertionRule.count_gte(2)));
        assertFalse(ComplianceAssertionEngine.evaluateList(List.of("10.0.0.1"), AssertionRule.count_gte(2)));
        assertTrue(ComplianceAssertionEngine.evaluateList(List.of("10.0.0.1"), AssertionRule.count_lte(2)));
    }
}
