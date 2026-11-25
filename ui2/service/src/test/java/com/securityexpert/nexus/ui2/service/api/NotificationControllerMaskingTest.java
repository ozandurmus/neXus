package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * Masking leak audit round 4 (2026-11-25): a configuration-override notification's paths end in the name of a
 * configuration object or zone the device owner chose; the replay viewer must not receive that name, even on a cold
 * process whose free-text pass has not met it yet.
 */
class NotificationControllerMaskingTest {

    private final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(new byte[32]);

    @Test
    void replayViewerGetsTheCategoryAndAPseudonymNotTheEntryName() {
        List<String> raw = List.of("address/address/h-synthetic-dbsrv", "zone/zone/zn-synthetic-cardholder");
        List<String> masked = NotificationController.overridePaths(raw, true, names);
        assertEquals(2, masked.size());
        assertTrue(masked.get(0).startsWith("address/"));
        assertTrue(masked.get(1).startsWith("zone/"));
        String all = String.join("\n", masked);
        assertFalse(all.contains("h-synthetic-dbsrv"));
        assertFalse(all.contains("zn-synthetic-cardholder"));
        assertEquals(masked, NotificationController.overridePaths(raw, true, names), "the pseudonym is stable");
    }

    @Test
    void anUnexpectedPathShapeIsPseudonymisedWhole() {
        List<String> masked = NotificationController.overridePaths(List.of("entry named zn-synthetic-core"), true, names);
        assertFalse(masked.get(0).contains("zn-synthetic-core"));
    }

    @Test
    void operatorsKeepTheRealPaths() {
        List<String> raw = List.of("zone/zone/zn-synthetic-core");
        assertEquals(raw, NotificationController.overridePaths(raw, false, names));
    }
}
