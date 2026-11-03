package com.securityexpert.nexus.ui2.policy;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

class PolicyHitLevelTest {
    private static final String AT = "2026-10-20T00:00:00Z";
    private static Rule rule(String id, HitCounts counts) {
        var cell = new Cell(List.of(), false);
        return new Rule(id, id, 1, "RULE-TEST-" + id, true, cell, cell, cell, cell, "allow", "Log", "", Map.of(), counts);
    }
    private static PolicySnapshot snapshot(String vendor, Rule... rules) {
        return new PolicySnapshot(new Metadata("p1", "s1", "MGR-BRAVO-01", vendor, "c1", "DOM-TANGO-01", "POL-ALPHA-01", AT, "a1", List.of()),
            List.of(new Section("s1", "Pre rules", "Shared", null, List.of(rules))), Map.of());
    }
    private static HitCounts device(Long hits, String lastHit, String level) { return new HitCounts(hits, null, lastHit, "device", AT, level, List.of()); }

    @Test void deviceCountersGetZeroOnlyForACompleteZeroTotalAndUnknownOtherwise() {
        var out = snapshot("PAN", rule("r0", device(0L, null, null)), rule("r9", device(9L, AT, null)), rule("rn", device(null, null, null)),
            rule("rc", device(0L, AT, null)), rule("rx", null)).withHitLevels();
        var rules = out.sections().get(0).rules();
        assertEquals("zero", rules.get(0).hitCounts().level());
        assertEquals("UNKNOWN", rules.get(1).hitCounts().level());
        assertEquals("UNKNOWN", rules.get(2).hitCounts().level());
        assertEquals("UNKNOWN", rules.get(3).hitCounts().level());
        assertNull(rules.get(4).hitCounts());
        assertEquals(9L, rules.get(1).hitCounts().hits());
    }
    @Test void vendorLevelsAreNeverRewrittenAndAMissingCheckPointLevelIsUnknown() {
        var cp = snapshot("CP", rule("a", new HitCounts(12L, null, AT, "mds", AT, "high", List.of())),
            rule("b", new HitCounts(0L, null, null, "mds", AT, null, List.of())));
        var out = cp.withHitLevels();
        assertEquals("high", out.sections().get(0).rules().get(0).hitCounts().level());
        assertEquals("UNKNOWN", out.sections().get(0).rules().get(1).hitCounts().level());
    }
    @Test void snapshotWithNothingToFillIsReturnedUnchanged() {
        var snapshot = snapshot("PAN", rule("r", device(3L, AT, "UNKNOWN")), rule("n", null));
        assertSame(snapshot, snapshot.withHitLevels());
        assertSame(snapshot.withHitLevels(), snapshot.withHitLevels().withHitLevels());
    }
}
