package com.securityexpert.nexus.ui2.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Contract §9 check 12 / MC-1, MC-2 (AC-7): links are recorded by
 * identifier; replacing every cluster display name changes nothing;
 * removing every identifier yields zero links and NOT_EVALUABLE, never a
 * name-based fallback.
 */
class Check12IdentifierOnlyJoinTest {

    private static Map<String, CandidateRow> byId(List<CandidateRow> rows) {
        return rows.stream().collect(Collectors.toMap(r -> r.key().stableIdentifier().value(), Function.identity()));
    }

    @Test
    void linksAreRecordedByIdentifier() {
        Map<String, CandidateRow> rows = byId(CandidateRowAssembler.assemble(Fixtures.allInputs()));
        ClusterLink.Linked chassisLink = assertInstanceOf(ClusterLink.Linked.class, rows.get("chassis-member-1").clusterLink());
        assertEquals("vcluster-1", chassisLink.clusterStableIdentifier().value());
        ClusterLink.Linked vsMemberLink = assertInstanceOf(ClusterLink.Linked.class, rows.get("vs-member-1").clusterLink());
        assertEquals("vscluster-1", vsMemberLink.clusterStableIdentifier().value());
    }

    @Test
    void replacingEveryClusterDisplayNameProducesIdenticalLinks() {
        Map<String, CandidateRow> before = byId(CandidateRowAssembler.assemble(Fixtures.allInputs()));
        Map<String, CandidateRow> after = byId(CandidateRowAssembler.assemble(
                Fixtures.withEveryDisplayNameReplaced(Fixtures.allInputs(), "CONSTANT-NAME")));

        for (String id : before.keySet()) {
            assertEquals(before.get(id).clusterLink(), after.get(id).clusterLink(), "cluster link changed for " + id);
        }
    }

    /** MC-2: removing the identifier from every cluster reference yields zero links and NOT_EVALUABLE. */
    @Test
    void removingEveryIdentifierYieldsZeroLinksNeverANameFallback() {
        List<RawCandidateInput> identifierless = Fixtures.withEveryClusterReferenceIdentifierRemoved(Fixtures.allInputs());
        List<CandidateRow> rows = CandidateRowAssembler.assemble(identifierless);

        long linkedCount = rows.stream().filter(r -> r.clusterLink() instanceof ClusterLink.Linked).count();
        assertEquals(0, linkedCount);

        List<CandidateRow> memberRowsThatHadAReference = rows.stream()
                .filter(r -> r.key().stableIdentifier().value().equals("chassis-member-1")
                        || r.key().stableIdentifier().value().equals("vs-member-1")
                        || r.key().stableIdentifier().value().equals("plain-member-1")
                        || r.key().stableIdentifier().value().equals("plain-member-name-only-ref"))
                .toList();
        assertTrue(!memberRowsThatHadAReference.isEmpty());
        for (CandidateRow row : memberRowsThatHadAReference) {
            assertInstanceOf(ClusterLink.NotEvaluable.class, row.clusterLink());
        }
    }
}
