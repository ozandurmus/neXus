package com.securityexpert.nexus.ui2.service.device.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.device.inventory.ClusterMemberDiff.Cell;
import com.securityexpert.nexus.ui2.service.device.inventory.ClusterMemberDiff.FieldSpec;

class ClusterMemberDiffTest {
    private static final List<FieldSpec> SPECS = List.of(new FieldSpec("version", "Version", null), new FieldSpec("ifaces", "Interfaces", null));

    private static ClusterMemberDiff.Field field(ClusterMemberDiff.Result r, String key) {
        return r.fields().stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void equalValuesOnEveryMemberAreSame() {
        var r = ClusterMemberDiff.compare("c1", List.of("b", "a"), SPECS,
                Map.of("version", Map.of("a", Cell.of("1.0"), "b", Cell.of("1.0"))));
        assertEquals(ClusterMemberDiff.SAME, field(r, "version").status());
        assertEquals(List.of("a", "b"), r.memberIds());
    }

    @Test
    void twoKnownDifferentValuesDifferEvenWhenAThirdMemberHasNone() {
        var r = ClusterMemberDiff.compare("c1", List.of("a", "b", "c"), SPECS,
                Map.of("version", Map.of("a", Cell.of("1.0"), "b", Cell.of("2.0"))));
        assertEquals(ClusterMemberDiff.DIFFERS, field(r, "version").status());
        assertNull(field(r, "version").values().get("c"));
    }

    @Test
    void aMissingValueWithNoKnownDifferenceIsUnknownNeverSame() {
        var r = ClusterMemberDiff.compare("c1", List.of("a", "b"), SPECS,
                Map.of("version", Map.of("a", Cell.of("1.0"))));
        assertEquals(ClusterMemberDiff.UNKNOWN, field(r, "version").status());
        assertEquals(ClusterMemberDiff.UNKNOWN, field(r, "ifaces").status());
    }

    @Test
    void setFieldsDifferByContentNotCountAndNameWhatIsNotShared() {
        var r = ClusterMemberDiff.compare("c1", List.of("a", "b"), SPECS, Map.of("ifaces", Map.of(
                "a", Cell.ofSet("2 interfaces", Set.of("x", "y")), "b", Cell.ofSet("2 interfaces", Set.of("x", "z")))));
        var f = field(r, "ifaces");
        assertEquals(ClusterMemberDiff.DIFFERS, f.status());
        assertEquals(List.of("y", "z"), f.detail());
        assertEquals("2 interfaces", f.values().get("a"));
    }

    @Test
    void aSingleMemberClusterIsNotComparable() {
        var r = ClusterMemberDiff.compare("c1", List.of("a"), SPECS, Map.of());
        assertFalse(r.comparable());
        assertEquals(ClusterMemberDiff.PAIR_INCOMPLETE, r.reason());
        assertEquals(List.of(), r.fields());
    }

    @Test
    void theSummaryCountsEachStatus() {
        var r = ClusterMemberDiff.compare("c1", List.of("a", "b"), SPECS,
                Map.of("version", Map.of("a", Cell.of("1"), "b", Cell.of("2"))));
        @SuppressWarnings("unchecked") var summary = (Map<String, Object>) r.toMap().get("summary");
        assertEquals(1L, summary.get("differs"));
        assertEquals(1L, summary.get("unknown"));
        assertEquals(0L, summary.get("same"));
    }
}
