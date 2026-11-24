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
        verify(query, times(1)).hygieneReport(any(), anyInt());

        var v2 = meta("p1", "c1", "2026-11-03T10:00:00Z");
        doReturn(List.of(v2)).when(query).catalog();
        doReturn(Optional.of(snapshot(v2, List.of(rule, PolicyHygieneTest.rule("r2", "host", "port", "allow", false))))).when(query).find(anyString());
        assertEquals(1, summary.view().get("pending"), "a new snapshot is not served with the old counts");
        assertTrue(summary.refreshNext());
        verify(query, times(2)).hygieneReport(any(), anyInt());
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

    @SuppressWarnings("unchecked") private static Map<String, Integer> ints(Object value) { return (Map<String, Integer>) value; }

    @Test void unknownReasonsAndBlockerReasonsAreCountedPerContainerAndInTotalsFromOneAnalysis() {
        // r1 carries a constraint the engine cannot read (UNKNOWN for its own reason); r2 is behind it and ends UNKNOWN "earlier rule has unsupported constraints" with r1 as blocker.
        var blocker = PolicyHygieneTest.edit(PolicyHygieneTest.rule("r1", "net", "port", "allow", true), PolicyHygieneTest.cell("net"), Map.of("unexpected-field", List.of("yes")));
        var behind = PolicyHygieneTest.rule("r2", "host", "port", "allow", true);
        var a = meta("p1", "c1", "2026-11-01T10:00:00Z"); var b = meta("p2", "c2", "2026-11-02T10:00:00Z"); var c = meta("p3", "c3", "2026-11-03T10:00:00Z");
        doReturn(List.of(a, b, c)).when(query).catalog();
        doReturn(Optional.of(snapshot(a, List.of(blocker, behind)))).when(query).find("p1");
        doReturn(Optional.of(snapshot(b, List.of(blocker, behind)))).when(query).find("p2");
        doReturn(Optional.empty()).when(query).find("p3"); // never analysed: stays PENDING
        var summary = new PolicyHygieneFleetSummary(query);
        for (int pass = 0; pass < 3; pass++) assertTrue(summary.refreshNext()); // one container per pass; the unreadable one is retried, so never loop until false
        verify(query, times(2)).hygieneReport(any(), anyInt()); // one analysis per snapshot, not two

        var expected = PolicyHygiene.analyze(snapshot(a, List.of(blocker, behind)), PolicyHygieneFleetSummary.DAYS, java.time.Instant.now(), PolicyHygiene.TOTAL_WORK).rules();
        assertEquals("UNKNOWN", expected.get("r1").shadowStatus());
        assertEquals(PolicyHygiene.EARLIER_UNSUPPORTED, expected.get("r2").shadowReason());
        assertEquals("r1", expected.get("r2").blockingRuleId());
        String blockerReason = expected.get("r1").shadowReason();
        assertNotEquals(PolicyHygiene.EARLIER_UNSUPPORTED, blockerReason);

        var view = summary.view();
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) view.get("containers");
        for (var row : rows.subList(0, 2)) {
            assertEquals("READY", row.get("state"));
            assertEquals(Map.of(blockerReason, 1, PolicyHygiene.EARLIER_UNSUPPORTED, 1), ints(row.get("unknownReasons")));
            assertEquals(Map.of(blockerReason, 1), ints(row.get("blockerReasons")), "the rule behind a blocker is counted under the blocker's own reason");
        }
        assertEquals("PENDING", rows.get(2).get("state"));
        assertFalse(rows.get(2).containsKey("unknownReasons"), "a pending container carries no maps, never zeros");
        assertFalse(rows.get(2).containsKey("blockerReasons"));
        assertEquals(1, view.get("pending"));
        assertEquals(Map.of(blockerReason, 2, PolicyHygiene.EARLIER_UNSUPPORTED, 2), ints(view.get("unknownReasons")), "totals cover READY containers only");
        assertEquals(Map.of(blockerReason, 2), ints(view.get("blockerReasons")));

        // Reasons are the engine's fixed literals: never a rule id, rule name, object name or uuid of the fixture.
        var keys = new TreeSet<>(ints(view.get("unknownReasons")).keySet()); keys.addAll(ints(view.get("blockerReasons")).keySet());
        for (var key : keys) {
            assertTrue(PolicyHygiene.REASONS.contains(key) || key.equals(PolicyQueryService.NO_REASON), key);
            for (String fixture : List.of("r1", "r2", "OBJ-", "uuid-", "POL-", "DOM-", "MGR-")) assertFalse(key.contains(fixture), key);
        }
    }

    @Test void reasonsAreRankedByCountThenName() {
        var sorted = new PolicyHygieneFleetSummary(query);
        var entries = new LinkedHashMap<String, Integer>();
        entries.put("b reason", 2); entries.put("a reason", 2); entries.put("z reason", 5);
        var a = meta("p1", "c1", "2026-11-01T10:00:00Z");
        doReturn(List.of(a)).when(query).catalog();
        doReturn(Optional.of(snapshot(a, List.of(PolicyHygieneTest.rule("r1", "net", "port", "allow", true))))).when(query).find("p1");
        doReturn(new PolicyQueryService.HygieneReport(new TreeMap<>(), entries, new TreeMap<>())).when(query).hygieneReport(any(), anyInt());
        sorted.refreshNext();
        assertEquals(List.of("z reason", "a reason", "b reason"), List.copyOf(ints(sorted.view().get("unknownReasons")).keySet()));
    }
}
