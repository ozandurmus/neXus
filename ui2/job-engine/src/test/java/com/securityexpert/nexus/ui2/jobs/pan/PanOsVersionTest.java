package com.securityexpert.nexus.ui2.jobs.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

class PanOsVersionTest {

    @Test
    void readsTheLeadingMajorNumber() {
        assertEquals(OptionalInt.of(11), PanOsVersion.major("11.1.4-h7"));
        assertEquals(OptionalInt.of(10), PanOsVersion.major("10.2.9"));
        assertEquals(OptionalInt.of(12), PanOsVersion.major("12.1.2"));
        assertEquals(OptionalInt.of(9), PanOsVersion.major(" 9.1.16 "));
        assertEquals(OptionalInt.of(12), PanOsVersion.major("12"));
        assertEquals(OptionalInt.of(10), PanOsVersion.major("10-beta"));
    }

    @Test
    void anythingElseIsUnknown() {
        for (String v : new String[] {null, "", " ", "PAN-OS 11", "v11.1", ".11", "11x.2", "-1.2", "123456.1"}) {
            assertTrue(PanOsVersion.major(v).isEmpty(), String.valueOf(v));
        }
    }
}
