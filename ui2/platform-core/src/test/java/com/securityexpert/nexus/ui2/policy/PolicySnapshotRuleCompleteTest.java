package com.securityexpert.nexus.ui2.policy;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

class PolicySnapshotRuleCompleteTest {
    private static PolicySnapshot snapshot(int rules, CollectionFailure... failures) {
        var cell = new Cell(List.of(), false);
        List<Rule> list = new ArrayList<>();
        for (int i = 0; i < rules; i++) list.add(new Rule("r" + i, "u" + i, i + 1, "RULE-TEST-" + i, true, cell, cell, cell, cell, "Accept", "Log", "", Map.of()));
        return new PolicySnapshot(new Metadata("p1", "s1", "MGR-BRAVO-01", "CP", "c1", "DOM-TANGO-01", "POL-ALPHA-01", "2026-11-13T00:00:00Z", "a1", List.of()),
            List.of(new Section("s1", "Section A", "CP access layer", null, list.subList(0, rules / 2)),
                new Section("s2", "Section B", "CP access layer", null, list.subList(rules / 2, rules))),
            Map.of(), List.of(failures));
    }

    @Test void inProgressCheckpointAndLostLayerAreNotRuleComplete() {
        assertFalse(snapshot(4, new CollectionFailure("layer-1", "COLLECTION_PENDING", "Network", 0, null)).ruleComplete());
        assertFalse(snapshot(4, new CollectionFailure("layer-1", "TIMEOUT")).ruleComplete());
        // The UNKNOWN reason counts only on the collector's NAT part.
        assertFalse(snapshot(4, new CollectionFailure("layer-1", VENDOR_NAME_NOT_UNIQUE, "Network", 0, null)).ruleComplete());
    }

    @Test void advisoryFailuresAndTheUnknownNatPartKeepEveryRule() {
        assertTrue(snapshot(4).ruleComplete());
        assertTrue(snapshot(4, new CollectionFailure("p1", "POLICY_HITS_UNAVAILABLE"),
            new CollectionFailure("c1", "POLICY_DB_INVENTORY_WRITE_FAILED"),
            new CollectionFailure("nat-1", VENDOR_NAME_NOT_UNIQUE, "NAT", 0, null)).ruleComplete());
    }

    @Test void ruleCountSpansEverySection() {
        assertEquals(7, snapshot(7).ruleCount());
        assertEquals(0, snapshot(0).ruleCount());
    }
}
