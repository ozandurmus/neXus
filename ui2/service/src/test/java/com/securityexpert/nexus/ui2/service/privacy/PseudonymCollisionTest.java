package com.securityexpert.nexus.ui2.service.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** F20 (2026-09-23): distinct real clusters (or standalone devices) never share a pseudonym while names last. */
class PseudonymCollisionTest {

    @Test
    void distinctClustersNeverShareAPseudonymAndAClusterKeepsItsName() {
        TopologyNamePseudonymizer p = new TopologyNamePseudonymizer("k".repeat(32).getBytes(StandardCharsets.UTF_8));
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 120; i++) {
            names.add(p.maskClusterName("SITE-" + i + "-CLS"));
        }
        assertEquals(120, names.size(), "120 real clusters, 120 pseudonyms");
        assertEquals(p.maskClusterName("SITE-7-CLS"), p.maskClusterName("SITE-7-CLS"));
    }

    @Test
    void standaloneDevicesNeverShareAPseudonym() {
        TopologyNamePseudonymizer p = new TopologyNamePseudonymizer("k".repeat(32).getBytes(StandardCharsets.UTF_8));
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            names.add(p.maskDeviceName("GW-STANDALONE-" + i, null));
        }
        assertEquals(60, names.size());
    }
}
