package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** C3A contract §11.2 test 7 (PW-1/PW-2/PW-3 enforced at credential-set/change time). */
class LocalPasswordPolicyTest {

    @Test
    void anElevenCharacterPasswordViolatesPw1() {
        assertEquals("password_too_short",
                LocalPasswordPolicy.violation("eleven-char".toCharArray(), "nexusadmin").orElseThrow());
    }

    @Test
    void anEmptyPasswordViolatesPw1Pw2BeforeAnyHashWouldBeComputed() {
        assertEquals("password_too_short", LocalPasswordPolicy.violation(new char[0], "nexusadmin").orElseThrow());
    }

    @Test
    void aPasswordEqualToTheIdentityNameCaseInsensitiveViolatesPw3() {
        assertEquals("password_equals_identity_name",
                LocalPasswordPolicy.violation("NexusAdmin12".toCharArray(), "nexusadmin12").orElseThrow());
    }

    @Test
    void aTwelveCharacterPasswordDistinctFromTheIdentityNameIsStorable() {
        assertTrue(LocalPasswordPolicy.violation("correct-horse-battery".toCharArray(), "nexusadmin").isEmpty());
    }
}
