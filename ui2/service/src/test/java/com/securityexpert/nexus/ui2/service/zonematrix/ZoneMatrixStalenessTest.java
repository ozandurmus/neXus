package com.securityexpert.nexus.ui2.service.zonematrix;

import static org.junit.jupiter.api.Assertions.*;

import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.CountHeadline;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.CoveredPolicy;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.PolicyInput;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.PolicyStaleness;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.Reason;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.RunState;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.StalenessThreshold;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Staleness unit tests (docs/design/ZONE_MATRIX_CONTRACT.md section 5.9; ZM-35). Synthetic ids only. */
class ZoneMatrixStalenessTest {
    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
    private static final StalenessThreshold T24 = ZoneMatrixStaleness.INTERIM_PO_2026_11_23;

    private static PolicyInput input(String collectedAt, RunState state, String cited, String active) {
        return new PolicyInput("POL-ALPHA", collectedAt, state, cited, active);
    }

    private static PolicyInput healthy() {
        return input(NOW.minus(Duration.ofHours(1)).toString(), RunState.COMPLETE, "ver-1", "ver-1");
    }

    private static PolicyStaleness assess(PolicyInput in) {
        return ZoneMatrixStaleness.assess(in, NOW, T24);
    }

    @Test
    void interimThresholdIs24HoursAndLabelledInterim() {
        assertEquals(Duration.ofHours(24), T24.value());
        assertEquals("interim (PO 2026-11-23) until the bank states it", T24.source());
        assertThrows(IllegalArgumentException.class, () -> new StalenessThreshold(Duration.ZERO, "x"));
        assertThrows(IllegalArgumentException.class, () -> new StalenessThreshold(Duration.ofHours(1), " "));
    }

    @Test
    void freshSnapshotCompleteRunActiveVersionIsCurrent() {
        PolicyStaleness s = assess(healthy());
        assertEquals(Verdict.CURRENT, s.verdict());
        assertTrue(s.reasons().isEmpty());
        assertEquals(Duration.ofHours(1), s.snapshotAge());
        assertEquals(T24, s.threshold());
    }

    @Test
    void zm35_snapshotOlderThan24hIsStale() {
        PolicyStaleness s = assess(input(NOW.minus(Duration.ofHours(30)).toString(), RunState.COMPLETE, "ver-1", "ver-1"));
        assertEquals(Verdict.STALE, s.verdict());
        assertEquals(List.of(Reason.SNAPSHOT_OLDER_THAN_THRESHOLD), s.reasons());
        assertEquals(Duration.ofHours(30), s.snapshotAge(), "a stale policy still states its age");
    }

    @Test
    void boundaryExactly24hIsCurrentAnd24hPlusOneSecondIsStale() {
        assertEquals(Verdict.CURRENT,
                assess(input(NOW.minus(Duration.ofHours(24)).toString(), RunState.COMPLETE, "ver-1", "ver-1")).verdict());
        PolicyStaleness over =
                assess(input(NOW.minus(Duration.ofHours(24)).minusSeconds(1).toString(), RunState.COMPLETE, "ver-1", "ver-1"));
        assertEquals(Verdict.STALE, over.verdict());
        assertEquals(List.of(Reason.SNAPSHOT_OLDER_THAN_THRESHOLD), over.reasons());
    }

    @Test
    void zm35_lastRunFailedIsStale() {
        PolicyStaleness s = assess(input(NOW.minus(Duration.ofHours(1)).toString(), RunState.FAILED, "ver-1", "ver-1"));
        assertEquals(Verdict.STALE, s.verdict());
        assertEquals(List.of(Reason.LAST_RUN_FAILED), s.reasons());
    }

    @Test
    void runningRunAloneDoesNotMakeAPolicyStale() {
        assertEquals(Verdict.CURRENT,
                assess(input(NOW.minus(Duration.ofHours(1)).toString(), RunState.RUNNING, "ver-1", "ver-1")).verdict());
    }

    @Test
    void zm35_inactiveVersionIsStale() {
        PolicyStaleness s = assess(input(NOW.minus(Duration.ofHours(1)).toString(), RunState.COMPLETE, "ver-1", "ver-2"));
        assertEquals(Verdict.STALE, s.verdict());
        assertEquals(List.of(Reason.VERSION_NOT_ACTIVE), s.reasons());
    }

    @Test
    void versionIdsAreComparedExactly() {
        assertEquals(Verdict.STALE, assess(input(NOW.toString(), RunState.COMPLETE, "VER-1", "ver-1")).verdict());
        assertEquals(Verdict.STALE, assess(input(NOW.toString(), RunState.COMPLETE, "007", "07")).verdict());
    }

    @Test
    void missingCitedOrActiveVersionIsNotShownAsActive() {
        assertEquals(List.of(Reason.VERSION_NOT_ACTIVE), assess(input(NOW.toString(), null, null, "ver-1")).reasons());
        assertEquals(List.of(Reason.VERSION_NOT_ACTIVE),
                assess(input(NOW.toString(), RunState.COMPLETE, "ver-1", null)).reasons());
    }

    @Test
    void everyApplicableReasonIsReported() {
        PolicyStaleness s = assess(input(NOW.minus(Duration.ofDays(3)).toString(), RunState.FAILED, "ver-1", "ver-2"));
        assertEquals(List.of(Reason.SNAPSHOT_OLDER_THAN_THRESHOLD, Reason.LAST_RUN_FAILED, Reason.VERSION_NOT_ACTIVE),
                s.reasons());
    }

    @Test
    void unknownOrUnparseableCollectedAtIsStaleNeverCurrent() {
        for (String bad : new String[] {null, "", "  ", "yesterday", "2026-13-45T99:00:00Z", NOW.plusSeconds(60).toString()}) {
            PolicyStaleness s = assess(input(bad, RunState.COMPLETE, "ver-1", "ver-1"));
            assertEquals(Verdict.STALE, s.verdict(), "collectedAt=" + bad);
            assertEquals(List.of(Reason.SNAPSHOT_TIME_UNKNOWN), s.reasons());
            assertNull(s.snapshotAge());
        }
    }

    @Test
    void offsetFormCollectedAtIsParsed() {
        PolicyStaleness s = assess(input("2026-11-23T14:00:00+03:00", RunState.COMPLETE, "ver-1", "ver-1"));
        assertEquals(Duration.ofHours(1), s.snapshotAge());
        assertEquals(Verdict.CURRENT, s.verdict());
    }

    @Test
    void zm35_headlineIsAtLeastUnlessAllCurrentAndComplete() {
        CoveredPolicy ok = new CoveredPolicy(assess(healthy()), "COMPLETE");
        CoveredPolicy incomplete = new CoveredPolicy(assess(healthy()), "INCOMPLETE");
        CoveredPolicy stale = new CoveredPolicy(
                assess(input(NOW.minus(Duration.ofDays(2)).toString(), RunState.COMPLETE, "ver-1", "ver-1")), "COMPLETE");

        CountHeadline exact = ZoneMatrixStaleness.headline(3, List.of(ok, ok));
        assertTrue(exact.exact());
        assertEquals("3", exact.text());

        CountHeadline withIncomplete = ZoneMatrixStaleness.headline(3, List.of(ok, incomplete));
        assertFalse(withIncomplete.exact());
        assertEquals("at least 3", withIncomplete.text());

        CountHeadline withStale = ZoneMatrixStaleness.headline(3, List.of(ok, stale));
        assertFalse(withStale.exact());
        assertEquals("at least 3", withStale.text());
        assertEquals(3, withStale.count(), "stale findings stay counted, never dropped as resolved");

        CountHeadline unknownCoverage =
                ZoneMatrixStaleness.headline(0, List.of(new CoveredPolicy(assess(healthy()), null)));
        assertFalse(unknownCoverage.exact());
        assertEquals("at least 0", unknownCoverage.text());
    }

    @Test
    void headlineWithNoCoveredPolicyIsNeverExact() {
        CountHeadline h = ZoneMatrixStaleness.headline(0, List.of());
        assertFalse(h.exact());
        assertEquals("at least 0", h.text());
        assertThrows(IllegalArgumentException.class, () -> ZoneMatrixStaleness.headline(-1, List.of()));
    }
}
