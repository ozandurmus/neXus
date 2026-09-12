package com.securityexpert.nexus.ui2.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Contract §9 check 8 / NP-5 (AC-6): classifying the same enumeration once
 * as returned and once with every display-name field replaced by one
 * constant must produce identical kinds, host resolutions and cluster
 * links. Any difference proves a rule read a name.
 */
class Check8NameBlindnessTest {

    private static Map<String, CandidateRow> byId(List<CandidateRow> rows) {
        return rows.stream().collect(Collectors.toMap(r -> r.key().stableIdentifier().value(), Function.identity()));
    }

    @Test
    void replacingEveryDisplayNameChangesNothingButTheName() {
        List<RawCandidateInput> original = Fixtures.allInputs();
        List<RawCandidateInput> renamed = Fixtures.withEveryDisplayNameReplaced(original, "CONSTANT-NAME");

        assertFalse(original.isEmpty());
        Map<String, CandidateRow> before = byId(CandidateRowAssembler.assemble(original));
        Map<String, CandidateRow> after = byId(CandidateRowAssembler.assemble(renamed));

        assertEquals(before.keySet(), after.keySet());
        for (String id : before.keySet()) {
            CandidateRow b = before.get(id);
            CandidateRow a = after.get(id);
            assertEquals(b.kind(), a.kind(), "kind changed for " + id);
            assertEquals(b.hostResolution(), a.hostResolution(), "host resolution changed for " + id);
            assertEquals(b.hostLink(), a.hostLink(), "host link changed for " + id);
            assertEquals(describeClusterLink(b.clusterLink()), describeClusterLink(a.clusterLink()),
                    "cluster link changed for " + id);
            assertEquals("CONSTANT-NAME", a.displayName());
        }
    }

    private static String describeClusterLink(ClusterLink link) {
        return switch (link) {
            case ClusterLink.Linked linked -> "LINKED:" + linked.clusterStableIdentifier().value();
            case ClusterLink.NotEvaluable ignored -> "NOT_EVALUABLE";
        };
    }
}
