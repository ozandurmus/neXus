package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;

/** The rule reference both sides of the actor join compute: synthetic names only, no key material beyond zero bytes. */
class PanRuleRefTest {
    private static final String DG = "/config/devices/entry[@name='localhost.localdomain']/device-group/entry[@name='DG-EXAMPLE']";
    private static final HostnameFingerprint KEY = HostnameFingerprint.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));

    @Test void aDeviceGroupRulePathAndTheSnapshotSectionGiveTheSameReference() {
        var fromPath = PanRuleRef.fromPath(DG + "/pre-rulebase/security/rules/entry[@name='Rule-EXAMPLE']").orElseThrow();
        var fromSnapshot = PanRuleRef.fromSnapshot("DG-EXAMPLE", "Pre rules", "Rule-EXAMPLE").orElseThrow();
        assertEquals(fromSnapshot, fromPath);
        assertEquals(PanRuleRef.hmac(KEY, fromSnapshot), PanRuleRef.hmac(KEY, fromPath));
        assertTrue(PanRuleRef.hmac(KEY, fromPath).matches("[0-9a-f]{64}"));
    }

    @Test void theSharedScopePostAndNatRulebasesAreDistinct() {
        var shared = PanRuleRef.fromPath("/config/shared/post-rulebase/nat/rules/entry[@name='Rule-EXAMPLE']").orElseThrow();
        assertEquals(shared, PanRuleRef.fromSnapshot("Shared", "Post NAT rules", "Rule-EXAMPLE").orElseThrow());
        assertNotEquals(PanRuleRef.hmac(KEY, shared), PanRuleRef.hmac(KEY, PanRuleRef.fromSnapshot("Shared", "Post rules", "Rule-EXAMPLE").orElseThrow()));
        assertNotEquals(PanRuleRef.hmac(KEY, shared), PanRuleRef.hmac(KEY, PanRuleRef.fromSnapshot("DG-EXAMPLE", "Post NAT rules", "Rule-EXAMPLE").orElseThrow()));
    }

    @Test void aDeeperPathStillNamesNoRuleUnlessItEndsAtTheRuleEntry() {
        // The config log may name a field of the rule; that is UNKNOWN in the design, so only the rule entry itself is a reference.
        assertTrue(PanRuleRef.fromPath(DG + "/pre-rulebase/security/rules/entry[@name='R']/source").isEmpty());
        assertTrue(PanRuleRef.fromPath(DG + "/pre-rulebase/security/rules").isEmpty());
        assertTrue(PanRuleRef.fromPath(DG + "/address/entry[@name='obj']").isEmpty());
        assertTrue(PanRuleRef.fromPath("").isEmpty());
        assertTrue(PanRuleRef.fromPath(null).isEmpty());
    }

    @Test void localAndDefaultSectionsAndRedactedNamesAreNotReferences() {
        assertTrue(PanRuleRef.fromSnapshot("DG-EXAMPLE", "Local rules", "R").isEmpty());
        assertTrue(PanRuleRef.fromSnapshot("DG-EXAMPLE", "Pre default rules", "R").isEmpty());
        assertTrue(PanRuleRef.fromSnapshot("DG-EXAMPLE", "Pre rules", "").isEmpty());
        assertTrue(PanRuleRef.fromSnapshot("DG-EXAMPLE", "Pre rules", PanLogRedactor.REDACTED).isEmpty());
        assertTrue(PanRuleRef.fromPath(DG.replace("DG-EXAMPLE", PanLogRedactor.REDACTED) + "/pre-rulebase/security/rules/entry[@name='R']").isEmpty());
    }

    @Test void bothQuoteStylesAreRead() {
        assertEquals(PanRuleRef.fromSnapshot("DG-EXAMPLE", "Pre rules", "Name 'quoted'").orElseThrow(),
                PanRuleRef.fromPath(DG + "/pre-rulebase/security/rules/entry[@name=\"Name 'quoted'\"]").orElseThrow());
    }

    @Test void pathKindsAreCountedWithoutKeepingAnyPart() {
        assertEquals("RULE_PATH", PanRuleRef.shape(DG + "/pre-rulebase/security/rules/entry[@name='R']"));
        assertEquals("SHARED_RULE_PATH", PanRuleRef.shape("/config/shared/post-rulebase/nat/rules/entry[@name='R']"));
        assertEquals("RULE_FIELD_PATH", PanRuleRef.shape(DG + "/pre-rulebase/security/rules/entry[@name='R']/source"));
        assertEquals("RULE_FIELD_PATH", PanRuleRef.shape("/config/shared/pre-rulebase/security/rules/entry[@name='R']/action"));
        assertEquals("OTHER_OBJECT", PanRuleRef.shape(DG + "/address/entry[@name='obj']"));
        assertEquals("OTHER_OBJECT", PanRuleRef.shape(DG + "/pre-rulebase/security/rules"));
        assertEquals("UNMATCHED", PanRuleRef.shape(""));
        assertEquals("UNMATCHED", PanRuleRef.shape(null));
        assertEquals("UNMATCHED", PanRuleRef.shape("synthetic text that is not a path"));
    }
}
