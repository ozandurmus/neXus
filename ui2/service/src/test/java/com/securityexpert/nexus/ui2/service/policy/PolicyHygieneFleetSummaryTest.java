package com.securityexpert.nexus.ui2.service.policy;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import java.util.*;
import org.junit.jupiter.api.Test;

/** The fleet hygiene summary: counts per container from stored snapshots, one container per pass, unchanged snapshots skipped. Synthetic data only. */
class PolicyHygieneFleetSummaryTest {
    private static Metadata meta(String id, String container, String collectedAt) {
        return new Metadata(id, "s1", "MGR-BRAVO-01", "PAN", container, "DOM-" + container, "POL-" + id, collectedAt, "artefact-" + collectedAt, List.of());
    }
    private static PolicySnapshot snapshot(Metadata meta, List<Rule> rules) {
        return new PolicySnapshot(meta, List.of(new Section("s1", "Pre rules", "Shared", null, rules)), PolicyHygieneTest.objects());
    }
    private final PolicyQueryService query = spy(new PolicyQueryService(mock(TransactionBoundary.class), new ObjectMapper(), new TopologyNamePseudonymizer(new byte[32])));

    @Test void summarisesEveryContainerAndTotalsWithUnknownSeparate() {
        var a = meta("p1", "c1", "2026-11-01T10:00:00Z"); var b = meta("p2", "c2", "2026-11-02T10:00:00Z");
        var disabled = PolicyHygieneTest.rule("r1", "net", "port", "allow", false);
        var live = PolicyHygieneTest.rule("r2", "host", "port", "allow", true);
        doReturn(List.of(a, b)).when(query).catalog();
        doReturn(Optional.of(snapshot(a, List.of(disabled, live)))).when(query).find("p1");
        doReturn(Optional.of(snapshot(b, List.of(live)))).when(query).find("p2");
        var summary = new PolicyHygieneFleetSummary(query);

        assertEquals(2, summary.view().get("pending"), "nothing is shown as clean before it is analysed");
        assertTrue(summary.refreshNext()); // one container per pass
        assertEquals(1, summary.view().get("pending"));
        assertTrue(summary.refreshNext());
        assertFalse(summary.refreshNext(), "every container is current");

        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) summary.view().get("containers");
        @SuppressWarnings("unchecked") var first = (Map<String, Integer>) rows.get(0).get("counts");
        assertEquals("READY", rows.get(0).get("state"));
        assertEquals("2026-11-01T10:00:00Z", rows.get(0).get("snapshotAt"));
        assertEquals(2, rows.get(0).get("rules"));
        assertEquals(1, first.get("disabled"));
        assertEquals(List.of("shadowed", "conflict", "disabled", "unused", "expired", "broad", "unknown"), List.copyOf(first.keySet()));
        @SuppressWarnings("unchecked") var totals = (Map<String, Integer>) summary.view().get("totals");
        assertEquals(1, totals.get("disabled"));
        assertEquals(3, summary.view().get("rules"));
        assertEquals(0, summary.view().get("pending"));
    }

    @Test void unchangedSnapshotsAreSkippedAndANewSnapshotIsReanalysed() {
        var v1 = meta("p1", "c1", "2026-11-01T10:00:00Z");
        var rule = PolicyHygieneTest.rule("r1", "net", "port", "allow", false);
        doReturn(List.of(v1)).when(query).catalog();
        doReturn(Optional.of(snapshot(v1, List.of(rule)))).when(query).find(anyString());
        var summary = new PolicyHygieneFleetSummary(query);
        assertTrue(summary.refreshNext());
        assertFalse(summary.refreshNext());
        assertFalse(summary.refreshNext());
        verify(query, times(1)).hygieneCounts(any(), anyInt());

        var v2 = meta("p1", "c1", "2026-11-03T10:00:00Z");
        doReturn(List.of(v2)).when(query).catalog();
        doReturn(Optional.of(snapshot(v2, List.of(rule, PolicyHygieneTest.rule("r2", "host", "port", "allow", false))))).when(query).find(anyString());
        assertEquals(1, summary.view().get("pending"), "a new snapshot is not served with the old counts");
        assertTrue(summary.refreshNext());
        verify(query, times(2)).hygieneCounts(any(), anyInt());
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) summary.view().get("containers");
        assertEquals("2026-11-03T10:00:00Z", rows.get(0).get("snapshotAt"));
        assertEquals(2, rows.get(0).get("rules"));
    }

    @Test void aFailedContainerStaysPendingAndNeverShowsZeros() {
        var a = meta("p1", "c1", "2026-11-01T10:00:00Z");
        doReturn(List.of(a)).when(query).catalog();
        doReturn(Optional.empty()).when(query).find("p1");
        var summary = new PolicyHygieneFleetSummary(query);
        assertTrue(summary.refreshNext());
        var row = ((List<?>) summary.view().get("containers")).get(0);
        assertEquals("PENDING", ((Map<?, ?>) row).get("state"));
        assertFalse(((Map<?, ?>) row).containsKey("counts"));
    }
}
