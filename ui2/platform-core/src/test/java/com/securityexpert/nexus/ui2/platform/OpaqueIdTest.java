package com.securityexpert.nexus.ui2.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OpaqueIdTest {

    @Test
    void randomIdsAreDistinct() {
        assertNotEquals(OpaqueId.random(), OpaqueId.random());
    }

    @Test
    void equalValuesAreEqualIds() {
        assertEquals(OpaqueId.of("abc"), OpaqueId.of("abc"));
    }

    @Test
    void blankValueIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> OpaqueId.of(" "));
    }
}
